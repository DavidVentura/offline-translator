use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

#[cfg(feature = "tesseract")]
use crate::tesseract::TesseractWrapper;
use serde_json::Value;
#[cfg(feature = "tesseract")]
use tesseract::PageSegMode;
use thiserror::Error;
#[cfg(feature = "tesseract")]
use translator::build_text_blocks;
use translator::{
    BergamotEngine, CatalogSnapshot, PackInstallChecker, build_catalog_snapshot,
    can_translate_in_snapshot, language_rows_in_snapshot, parse_and_validate_catalog,
    plan_delete_dictionary_in_snapshot, plan_delete_language_in_snapshot,
    plan_delete_superseded_tts_in_snapshot, plan_delete_tts_in_snapshot,
    plan_dictionary_download_in_snapshot, plan_language_download_in_snapshot,
    plan_tts_download_in_snapshot, resolve_tts_voice_files_in_snapshot, sample_overlay_colors,
    select_best_catalog, translate_structured_fragments_in_snapshot,
    translate_mixed_texts_in_snapshot, translate_texts_in_snapshot,
    translate_texts_with_alignment_in_snapshot,
};

struct FsInstallChecker {
    base_dir: PathBuf,
}

static ENGINE: OnceLock<Mutex<BergamotEngine>> = OnceLock::new();
#[cfg(feature = "tesseract")]
static OCR_ENGINE: OnceLock<Mutex<Option<OcrEngineState>>> = OnceLock::new();

fn with_engine<T, F>(f: F) -> Result<T, String>
where
    F: FnOnce(&mut BergamotEngine) -> Result<T, String>,
{
    let mut engine = ENGINE
        .get_or_init(|| Mutex::new(BergamotEngine::new()))
        .lock()
        .map_err(|_| "Bergamot engine mutex poisoned".to_string())?;
    f(&mut engine)
}

impl FsInstallChecker {
    fn resolve(&self, relative_path: &str) -> PathBuf {
        self.base_dir.join(relative_path)
    }
}

impl PackInstallChecker for FsInstallChecker {
    fn file_exists(&self, install_path: &str) -> bool {
        self.resolve(install_path).exists()
    }

    fn install_marker_exists(&self, marker_path: &str, expected_version: i32) -> bool {
        let marker_file = self.resolve(marker_path);
        if !marker_file.exists() {
            return false;
        }

        let Ok(contents) = fs::read_to_string(marker_file) else {
            return false;
        };
        let Ok(json) = serde_json::from_str::<Value>(&contents) else {
            return false;
        };
        json.get("version")
            .and_then(Value::as_i64)
            .and_then(|value| i32::try_from(value).ok())
            == Some(expected_version)
    }
}

fn parse_selected_catalog(
    bundled_json: &str,
    disk_json: Option<&str>,
) -> Option<translator::LanguageCatalog> {
    let preferred = select_best_catalog(bundled_json, disk_json).ok()?;
    let fallback = if std::ptr::eq(preferred, bundled_json) {
        disk_json
    } else {
        Some(bundled_json)
    };

    parse_and_validate_catalog(preferred)
        .ok()
        .or_else(|| fallback.and_then(|json| parse_and_validate_catalog(json).ok()))
}

#[derive(Debug, Error, uniffi::Error)]
pub enum CatalogOpenError {
    #[error("failed to parse any catalog")]
    ParseFailed,
}

#[derive(uniffi::Record)]
pub struct LanguageInfo {
    pub code: String,
    pub display_name: String,
    pub short_display_name: String,
    pub tess_name: String,
    pub script: String,
    pub dictionary_code: String,
    pub tessdata_size_bytes: i64,
}

impl From<&translator::Language> for LanguageInfo {
    fn from(lang: &translator::Language) -> Self {
        Self {
            code: lang.code.clone(),
            display_name: lang.display_name.clone(),
            short_display_name: lang.short_display_name.clone(),
            tess_name: lang.tess_name.clone(),
            script: lang.script.clone(),
            dictionary_code: lang.dictionary_code.clone(),
            tessdata_size_bytes: lang.tessdata_size_bytes as i64,
        }
    }
}

#[derive(uniffi::Record)]
pub struct LangAvailability {
    pub has_from_english: bool,
    pub has_to_english: bool,
    pub ocr_files: bool,
    pub dictionary_files: bool,
    pub tts_files: bool,
}

impl From<translator::LangAvailability> for LangAvailability {
    fn from(a: translator::LangAvailability) -> Self {
        Self {
            has_from_english: a.has_from_english,
            has_to_english: a.has_to_english,
            ocr_files: a.ocr_files,
            dictionary_files: a.dictionary_files,
            tts_files: a.tts_files,
        }
    }
}

#[derive(uniffi::Record)]
pub struct LanguageRow {
    pub language: LanguageInfo,
    pub availability: LangAvailability,
}

#[derive(uniffi::Record)]
pub struct DictionaryInfo {
    pub date: i64,
    pub filename: String,
    pub size: i64,
    pub type_name: String,
    pub word_count: i64,
}

impl From<translator::DictionaryInfo> for DictionaryInfo {
    fn from(d: translator::DictionaryInfo) -> Self {
        Self {
            date: d.date,
            filename: d.filename,
            size: d.size as i64,
            type_name: d.type_name,
            word_count: d.word_count as i64,
        }
    }
}

#[derive(uniffi::Record)]
pub struct DownloadTask {
    pub pack_id: String,
    pub install_path: String,
    pub url: String,
    pub size_bytes: i64,
    pub decompress: bool,
    pub archive_format: Option<String>,
    pub extract_to: Option<String>,
    pub delete_after_extract: bool,
    pub install_marker_path: Option<String>,
    pub install_marker_version: Option<i32>,
}

impl From<translator::DownloadTask> for DownloadTask {
    fn from(t: translator::DownloadTask) -> Self {
        Self {
            pack_id: t.pack_id,
            install_path: t.install_path,
            url: t.url,
            size_bytes: t.size_bytes as i64,
            decompress: t.decompress,
            archive_format: t.archive_format,
            extract_to: t.extract_to,
            delete_after_extract: t.delete_after_extract,
            install_marker_path: t.install_marker_path,
            install_marker_version: t.install_marker_version,
        }
    }
}

#[derive(uniffi::Record)]
pub struct DownloadPlan {
    pub total_size: i64,
    pub tasks: Vec<DownloadTask>,
}

impl From<translator::DownloadPlan> for DownloadPlan {
    fn from(p: translator::DownloadPlan) -> Self {
        Self {
            total_size: p.total_size as i64,
            tasks: p.tasks.into_iter().map(Into::into).collect(),
        }
    }
}

#[derive(uniffi::Record)]
pub struct DeletePlan {
    pub file_paths: Vec<String>,
    pub directory_paths: Vec<String>,
}

impl From<translator::DeletePlan> for DeletePlan {
    fn from(p: translator::DeletePlan) -> Self {
        Self {
            file_paths: p.file_paths,
            directory_paths: p.directory_paths,
        }
    }
}

#[derive(uniffi::Record)]
pub struct TtsVoicePackInfo {
    pub pack_id: String,
    pub display_name: String,
    pub quality: Option<String>,
    pub size_bytes: i64,
}

impl From<translator::TtsVoicePackInfo> for TtsVoicePackInfo {
    fn from(i: translator::TtsVoicePackInfo) -> Self {
        Self {
            pack_id: i.pack_id,
            display_name: i.display_name,
            quality: i.quality,
            size_bytes: i.size_bytes as i64,
        }
    }
}

#[derive(uniffi::Record)]
pub struct TtsVoicePickerRegion {
    pub code: String,
    pub display_name: String,
    pub voices: Vec<TtsVoicePackInfo>,
}

#[derive(uniffi::Record)]
pub struct TtsVoiceFiles {
    pub engine: String,
    pub model_path: String,
    pub aux_path: String,
    pub language_code: String,
    pub speaker_id: Option<i32>,
}

#[derive(uniffi::Record)]
pub struct TokenAlignment {
    pub src_begin: i64,
    pub src_end: i64,
    pub tgt_begin: i64,
    pub tgt_end: i64,
}

impl From<translator::TokenAlignment> for TokenAlignment {
    fn from(value: translator::TokenAlignment) -> Self {
        Self {
            src_begin: value.src_begin as i64,
            src_end: value.src_end as i64,
            tgt_begin: value.tgt_begin as i64,
            tgt_end: value.tgt_end as i64,
        }
    }
}

#[derive(uniffi::Record)]
pub struct TranslationWithAlignment {
    pub source_text: String,
    pub translated_text: String,
    pub alignments: Vec<TokenAlignment>,
}

impl From<translator::TranslationWithAlignment> for TranslationWithAlignment {
    fn from(value: translator::TranslationWithAlignment) -> Self {
        Self {
            source_text: value.source_text,
            translated_text: value.translated_text,
            alignments: value.alignments.into_iter().map(Into::into).collect(),
        }
    }
}

#[derive(uniffi::Record)]
pub struct TextTranslation {
    pub source_text: String,
    pub translated_text: String,
}

impl From<translator::TextTranslation> for TextTranslation {
    fn from(value: translator::TextTranslation) -> Self {
        Self {
            source_text: value.source_text,
            translated_text: value.translated_text,
        }
    }
}

#[derive(uniffi::Record)]
pub struct MixedTextTranslationResult {
    pub translations: Vec<TextTranslation>,
    pub nothing_reason: Option<String>,
}

impl From<translator::MixedTextTranslationResult> for MixedTextTranslationResult {
    fn from(value: translator::MixedTextTranslationResult) -> Self {
        Self {
            translations: value.translations.into_iter().map(Into::into).collect(),
            nothing_reason: value
                .nothing_reason
                .map(|reason| reason.as_str().to_string()),
        }
    }
}

#[derive(uniffi::Record)]
pub struct StructuredTextStyle {
    pub text_color: Option<i64>,
    pub bg_color: Option<i64>,
    pub text_size: Option<f32>,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub strikethrough: bool,
}

impl From<translator::TextStyle> for StructuredTextStyle {
    fn from(value: translator::TextStyle) -> Self {
        Self {
            text_color: value.text_color.map(i64::from),
            bg_color: value.bg_color.map(i64::from),
            text_size: value.text_size,
            bold: value.bold,
            italic: value.italic,
            underline: value.underline,
            strikethrough: value.strikethrough,
        }
    }
}

impl From<StructuredTextStyle> for translator::TextStyle {
    fn from(value: StructuredTextStyle) -> Self {
        Self {
            text_color: value.text_color.and_then(|color| i32::try_from(color).ok()),
            bg_color: value.bg_color.and_then(|color| i32::try_from(color).ok()),
            text_size: value.text_size,
            bold: value.bold,
            italic: value.italic,
            underline: value.underline,
            strikethrough: value.strikethrough,
        }
    }
}

#[derive(uniffi::Record)]
pub struct StructuredFragment {
    pub text: String,
    pub bounding_box: OcrRect,
    pub style: Option<StructuredTextStyle>,
    pub layout_group: i32,
    pub translation_group: i32,
    pub cluster_group: i32,
}

impl From<StructuredFragment> for translator::StructuredStyledFragment {
    fn from(value: StructuredFragment) -> Self {
        Self {
            text: value.text,
            bounds: value.bounding_box.into(),
            style: value.style.map(Into::into),
            layout_group: value.layout_group,
            translation_group: value.translation_group,
            cluster_group: value.cluster_group,
        }
    }
}

#[derive(uniffi::Record)]
pub struct StructuredStyleSpan {
    pub start: i32,
    pub end: i32,
    pub style: Option<StructuredTextStyle>,
}

impl From<translator::StructuredStyleSpan> for StructuredStyleSpan {
    fn from(value: translator::StructuredStyleSpan) -> Self {
        Self {
            start: value.start,
            end: value.end,
            style: value.style.map(Into::into),
        }
    }
}

#[derive(uniffi::Record)]
pub struct StructuredTranslatedBlock {
    pub text: String,
    pub bounding_box: OcrRect,
    pub style_spans: Vec<StructuredStyleSpan>,
    pub background_argb: i64,
    pub foreground_argb: i64,
}

impl From<translator::TranslatedStyledBlock> for StructuredTranslatedBlock {
    fn from(value: translator::TranslatedStyledBlock) -> Self {
        Self {
            text: value.text,
            bounding_box: value.bounds.into(),
            style_spans: value.style_spans.into_iter().map(Into::into).collect(),
            background_argb: value.background_argb as i64,
            foreground_argb: value.foreground_argb as i64,
        }
    }
}

#[derive(uniffi::Record)]
pub struct StructuredTranslationResult {
    pub blocks: Vec<StructuredTranslatedBlock>,
    pub nothing_reason: Option<String>,
    pub error_message: Option<String>,
}

impl From<translator::StructuredTranslationResult> for StructuredTranslationResult {
    fn from(value: translator::StructuredTranslationResult) -> Self {
        Self {
            blocks: value.blocks.into_iter().map(Into::into).collect(),
            nothing_reason: value
                .nothing_reason
                .map(|reason| reason.as_str().to_string()),
            error_message: value.error_message,
        }
    }
}

#[derive(uniffi::Record)]
pub struct OverlayScreenshot {
    pub rgba_bytes: Vec<u8>,
    pub width: i32,
    pub height: i32,
}

impl From<OverlayScreenshot> for translator::OverlayScreenshot {
    fn from(value: OverlayScreenshot) -> Self {
        Self {
            rgba_bytes: value.rgba_bytes,
            width: value.width,
            height: value.height,
        }
    }
}

#[derive(uniffi::Record)]
pub struct OverlayColorsRecord {
    pub background_argb: i64,
    pub foreground_argb: i64,
}

impl From<translator::OverlayColors> for OverlayColorsRecord {
    fn from(value: translator::OverlayColors) -> Self {
        Self {
            background_argb: value.background_argb as i64,
            foreground_argb: value.foreground_argb as i64,
        }
    }
}

#[derive(uniffi::Enum, Clone, Copy)]
pub enum OcrReadingOrder {
    LeftToRight,
    TopToBottomLeftToRight,
}

impl From<OcrReadingOrder> for translator::ReadingOrder {
    fn from(value: OcrReadingOrder) -> Self {
        match value {
            OcrReadingOrder::LeftToRight => translator::ReadingOrder::LeftToRight,
            OcrReadingOrder::TopToBottomLeftToRight => {
                translator::ReadingOrder::TopToBottomLeftToRight
            }
        }
    }
}

#[derive(uniffi::Enum, Clone, Copy)]
pub enum OcrBackgroundMode {
    WhiteOnBlack,
    BlackOnWhite,
    AutoDetect,
}

impl From<OcrBackgroundMode> for translator::BackgroundMode {
    fn from(value: OcrBackgroundMode) -> Self {
        match value {
            OcrBackgroundMode::WhiteOnBlack => translator::BackgroundMode::WhiteOnBlack,
            OcrBackgroundMode::BlackOnWhite => translator::BackgroundMode::BlackOnWhite,
            OcrBackgroundMode::AutoDetect => translator::BackgroundMode::AutoDetect,
        }
    }
}

#[derive(uniffi::Record)]
pub struct OcrRect {
    pub left: i64,
    pub top: i64,
    pub right: i64,
    pub bottom: i64,
}

impl From<translator::Rect> for OcrRect {
    fn from(value: translator::Rect) -> Self {
        Self {
            left: value.left as i64,
            top: value.top as i64,
            right: value.right as i64,
            bottom: value.bottom as i64,
        }
    }
}

impl From<OcrRect> for translator::Rect {
    fn from(value: OcrRect) -> Self {
        Self {
            left: value.left as i32,
            top: value.top as i32,
            right: value.right as i32,
            bottom: value.bottom as i32,
        }
    }
}

#[derive(uniffi::Record)]
pub struct OcrLine {
    pub text: String,
    pub bounding_box: OcrRect,
    pub word_rects: Vec<OcrRect>,
    pub background_argb: i64,
    pub foreground_argb: i64,
}

impl From<translator::PreparedTextLine> for OcrLine {
    fn from(value: translator::PreparedTextLine) -> Self {
        Self {
            text: value.text,
            bounding_box: value.bounding_box.into(),
            word_rects: value.word_rects.into_iter().map(Into::into).collect(),
            background_argb: value.background_argb as i64,
            foreground_argb: value.foreground_argb as i64,
        }
    }
}

#[derive(uniffi::Record)]
pub struct ImageTranslationBlock {
    pub source_text: String,
    pub translated_text: String,
    pub bounding_box: OcrRect,
    pub lines: Vec<OcrLine>,
    pub background_argb: i64,
    pub foreground_argb: i64,
}

#[derive(uniffi::Record)]
pub struct ImageTranslationPlan {
    pub extracted_text: String,
    pub translated_text: String,
    pub erased_rgba_bytes: Vec<u8>,
    pub width: i32,
    pub height: i32,
    pub blocks: Vec<ImageTranslationBlock>,
}

#[cfg(feature = "tesseract")]
struct OcrEngineState {
    engine: TesseractWrapper,
    language_spec: String,
    reading_order: translator::ReadingOrder,
    tessdata_path: String,
}

#[cfg(feature = "tesseract")]
fn with_ocr_engine<T, F>(
    snapshot: &CatalogSnapshot,
    source_code: &str,
    reading_order: translator::ReadingOrder,
    f: F,
) -> Result<T, String>
where
    F: FnOnce(&mut TesseractWrapper) -> Result<T, String>,
{
    let language = snapshot
        .catalog
        .language_by_code(source_code)
        .ok_or_else(|| format!("unknown source language: {source_code}"))?;
    let tessdata_path = Path::new(&snapshot.base_dir)
        .join("tesseract")
        .join("tessdata");
    let has_japanese_vertical_model =
        source_code == "ja" && tessdata_path.join("jpn_vert.traineddata").exists();
    let language_spec = match (source_code, reading_order, has_japanese_vertical_model) {
        ("ja", translator::ReadingOrder::TopToBottomLeftToRight, true) => "jpn_vert".to_string(),
        _ => format!("{}+eng", language.tess_name),
    };

    let mut slot = OCR_ENGINE
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| "OCR engine mutex poisoned".to_string())?;

    let needs_reinit = slot.as_ref().is_none_or(|state| {
        state.language_spec != language_spec
            || state.reading_order != reading_order
            || state.tessdata_path != tessdata_path.to_string_lossy()
    });

    if needs_reinit {
        let engine = TesseractWrapper::new(
            Some(
                tessdata_path
                    .to_str()
                    .ok_or_else(|| "invalid tessdata path".to_string())?,
            ),
            Some(&language_spec),
        )
        .map_err(|err| format!("failed to initialize tesseract: {err}"))?;
        *slot = Some(OcrEngineState {
            engine,
            language_spec,
            reading_order,
            tessdata_path: tessdata_path.to_string_lossy().into_owned(),
        });
    }

    let state = slot
        .as_mut()
        .ok_or_else(|| "OCR engine unavailable".to_string())?;
    f(&mut state.engine)
}

#[cfg(feature = "tesseract")]
fn translate_image_plan_in_snapshot(
    snapshot: &CatalogSnapshot,
    rgba_bytes: &[u8],
    width: i32,
    height: i32,
    source_code: &str,
    target_code: &str,
    min_confidence: i32,
    reading_order: translator::ReadingOrder,
    background_mode: translator::BackgroundMode,
) -> Result<Option<ImageTranslationPlan>, String> {
    let bytes_per_pixel = 4;
    let bytes_per_line = width
        .checked_mul(bytes_per_pixel)
        .ok_or_else(|| "image width overflow".to_string())?;

    let page_seg_mode = match reading_order {
        translator::ReadingOrder::LeftToRight => PageSegMode::PsmAutoOsd,
        translator::ReadingOrder::TopToBottomLeftToRight => PageSegMode::PsmSingleBlockVertText,
    };

    let join_without_spaces = source_code == "ja";
    let relax_single_char_confidence =
        reading_order == translator::ReadingOrder::TopToBottomLeftToRight;

    let blocks = with_ocr_engine(snapshot, source_code, reading_order, |engine| {
        engine.set_page_seg_mode(page_seg_mode);
        engine
            .set_frame(rgba_bytes, width, height, bytes_per_pixel, bytes_per_line)
            .map_err(|err| format!("failed to set OCR frame: {err}"))?;
        let words = engine
            .get_word_boxes()
            .map_err(|err| format!("failed to read OCR words: {err}"))?;
        let detected_words = words
            .into_iter()
            .map(|word| translator::DetectedWord {
                text: word.text,
                confidence: word.confidence,
                bounding_box: translator::Rect {
                    left: word.bounding_rect.left,
                    top: word.bounding_rect.top,
                    right: word.bounding_rect.right,
                    bottom: word.bounding_rect.bottom,
                },
                is_at_beginning_of_para: word.is_at_beginning_of_para,
                end_para: word.end_para,
                end_line: word.end_line,
            })
            .collect::<Vec<_>>();
        Ok(build_text_blocks(
            &detected_words,
            min_confidence,
            join_without_spaces,
            relax_single_char_confidence,
        ))
    })?;

    let source_texts = blocks
        .iter()
        .map(translator::TextBlock::translation_text)
        .collect::<Vec<_>>();
    let translated_blocks = if source_code == target_code {
        source_texts.clone()
    } else {
        match with_engine(|engine| {
            translate_texts_in_snapshot(engine, snapshot, source_code, target_code, &source_texts)
                .transpose()
        }) {
            Ok(Some(values)) => values,
            Ok(None) => return Ok(None),
            Err(err) => return Err(err),
        }
    };

    let prepared = translator::prepare_overlay_image(
        rgba_bytes,
        width,
        height,
        &blocks,
        &translated_blocks,
        background_mode,
        reading_order,
    )?;
    let metadata_blocks = prepared
        .blocks
        .into_iter()
        .map(|block| ImageTranslationBlock {
            source_text: block.source_text,
            translated_text: block.translated_text,
            bounding_box: block.bounding_box.into(),
            lines: block.lines.into_iter().map(Into::into).collect(),
            background_argb: block.background_argb as i64,
            foreground_argb: block.foreground_argb as i64,
        })
        .collect();

    Ok(Some(ImageTranslationPlan {
        extracted_text: prepared.extracted_text,
        translated_text: prepared.translated_text,
        erased_rgba_bytes: prepared.rgba_bytes,
        width: prepared.width,
        height: prepared.height,
        blocks: metadata_blocks,
    }))
}

#[cfg(not(feature = "tesseract"))]
fn translate_image_plan_in_snapshot(
    _snapshot: &CatalogSnapshot,
    _rgba_bytes: &[u8],
    _width: i32,
    _height: i32,
    _source_code: &str,
    _target_code: &str,
    _min_confidence: i32,
    _reading_order: translator::ReadingOrder,
    _background_mode: translator::BackgroundMode,
) -> Result<Option<ImageTranslationPlan>, String> {
    Ok(None)
}

#[uniffi::export]
fn sample_overlay_colors_rgba(
    rgba_bytes: Vec<u8>,
    width: i32,
    height: i32,
    bounds: OcrRect,
    background_mode: OcrBackgroundMode,
    word_rects: Option<Vec<OcrRect>>,
) -> Option<OverlayColorsRecord> {
    let native_word_rects = word_rects.map(|rects| {
        rects
            .into_iter()
            .map(Into::into)
            .collect::<Vec<translator::Rect>>()
    });
    sample_overlay_colors(
        &rgba_bytes,
        width,
        height,
        bounds.into(),
        background_mode.into(),
        native_word_rects.as_deref(),
    )
    .ok()
    .map(Into::into)
}

#[derive(uniffi::Object)]
pub struct CatalogHandle {
    snapshot: CatalogSnapshot,
}

#[uniffi::export]
impl CatalogHandle {
    #[uniffi::constructor]
    fn open(
        bundled_json: String,
        disk_json: Option<String>,
        base_dir: String,
    ) -> Result<Arc<Self>, CatalogOpenError> {
        let catalog = parse_selected_catalog(&bundled_json, disk_json.as_deref())
            .ok_or(CatalogOpenError::ParseFailed)?;
        let checker = FsInstallChecker {
            base_dir: PathBuf::from(&base_dir),
        };
        let snapshot = build_catalog_snapshot(catalog, base_dir, &checker);
        Ok(Arc::new(CatalogHandle { snapshot }))
    }

    fn format_version(&self) -> i32 {
        self.snapshot.catalog.format_version
    }

    fn generated_at(&self) -> i64 {
        self.snapshot.catalog.generated_at
    }

    fn dictionary_version(&self) -> i32 {
        self.snapshot.catalog.dictionary_version
    }

    fn language_rows(&self) -> Vec<LanguageRow> {
        language_rows_in_snapshot(&self.snapshot)
            .into_iter()
            .map(|row| LanguageRow {
                language: (&row.language).into(),
                availability: row.availability.into(),
            })
            .collect()
    }

    fn dictionary_info(&self, dictionary_code: String) -> Option<DictionaryInfo> {
        self.snapshot
            .catalog
            .dictionary_info(&dictionary_code)
            .map(Into::into)
    }

    fn has_tts_voices(&self, language_code: String) -> bool {
        self.snapshot.catalog.has_tts_voices(&language_code)
    }

    fn tts_voice_picker_regions(&self, language_code: String) -> Vec<TtsVoicePickerRegion> {
        self.snapshot
            .catalog
            .tts_voice_picker_regions(&language_code)
            .into_iter()
            .map(|region| TtsVoicePickerRegion {
                code: region.code,
                display_name: region.display_name,
                voices: region.voices.into_iter().map(Into::into).collect(),
            })
            .collect()
    }

    fn can_swap_languages(&self, from_code: String, to_code: String) -> bool {
        self.snapshot
            .catalog
            .can_swap_languages(&from_code, &to_code)
    }

    fn can_translate(&self, from_code: String, to_code: String) -> bool {
        can_translate_in_snapshot(&self.snapshot, &from_code, &to_code)
    }

    fn translate_texts(
        &self,
        from_code: String,
        to_code: String,
        texts: Vec<String>,
    ) -> Option<Vec<String>> {
        with_engine(|engine| {
            translate_texts_in_snapshot(engine, &self.snapshot, &from_code, &to_code, &texts)
                .transpose()
        })
        .ok()
        .flatten()
    }

    fn translate_texts_with_alignment(
        &self,
        from_code: String,
        to_code: String,
        texts: Vec<String>,
    ) -> Option<Vec<TranslationWithAlignment>> {
        with_engine(|engine| {
            translate_texts_with_alignment_in_snapshot(
                engine,
                &self.snapshot,
                &from_code,
                &to_code,
                &texts,
            )
            .transpose()
            .map(|maybe| maybe.map(|values| values.into_iter().map(Into::into).collect()))
        })
        .ok()
        .flatten()
    }

    fn translate_mixed_texts(
        &self,
        inputs: Vec<String>,
        forced_source_code: Option<String>,
        target_code: String,
        available_language_codes: Vec<String>,
    ) -> MixedTextTranslationResult {
        with_engine(|engine| {
            translate_mixed_texts_in_snapshot(
                engine,
                &self.snapshot,
                &inputs,
                forced_source_code.as_deref(),
                &target_code,
                &available_language_codes,
            )
        })
        .unwrap_or_default()
        .into()
    }

    fn translate_structured_fragments(
        &self,
        fragments: Vec<StructuredFragment>,
        forced_source_code: Option<String>,
        target_code: String,
        available_language_codes: Vec<String>,
        screenshot: Option<OverlayScreenshot>,
        background_mode: OcrBackgroundMode,
    ) -> StructuredTranslationResult {
        let screenshot = screenshot.map(Into::into);
        let native_fragments = fragments.into_iter().map(Into::into).collect::<Vec<_>>();
        with_engine(|engine| {
            translate_structured_fragments_in_snapshot(
                engine,
                &self.snapshot,
                &native_fragments,
                forced_source_code.as_deref(),
                &target_code,
                &available_language_codes,
                screenshot.as_ref(),
                background_mode.into(),
            )
        })
        .unwrap_or_else(|error_message| translator::StructuredTranslationResult {
            blocks: Vec::new(),
            nothing_reason: None,
            error_message: Some(error_message),
        })
        .into()
    }

    fn translate_image_plan(
        &self,
        rgba_bytes: Vec<u8>,
        width: i32,
        height: i32,
        source_code: String,
        target_code: String,
        min_confidence: i32,
        reading_order: OcrReadingOrder,
        background_mode: OcrBackgroundMode,
    ) -> Option<ImageTranslationPlan> {
        translate_image_plan_in_snapshot(
            &self.snapshot,
            &rgba_bytes,
            width,
            height,
            &source_code,
            &target_code,
            min_confidence,
            reading_order.into(),
            background_mode.into(),
        )
        .ok()
        .flatten()
    }

    fn plan_language_download(&self, language_code: String) -> DownloadPlan {
        plan_language_download_in_snapshot(&self.snapshot, &language_code).into()
    }

    fn plan_dictionary_download(&self, language_code: String) -> Option<DownloadPlan> {
        plan_dictionary_download_in_snapshot(&self.snapshot, &language_code).map(Into::into)
    }

    fn plan_tts_download(
        &self,
        language_code: String,
        selected_pack_id: Option<String>,
    ) -> Option<DownloadPlan> {
        plan_tts_download_in_snapshot(&self.snapshot, &language_code, selected_pack_id.as_deref())
            .map(Into::into)
    }

    fn plan_delete_language(&self, language_code: String) -> DeletePlan {
        plan_delete_language_in_snapshot(&self.snapshot, &language_code).into()
    }

    fn plan_delete_dictionary(&self, language_code: String) -> DeletePlan {
        plan_delete_dictionary_in_snapshot(&self.snapshot, &language_code).into()
    }

    fn plan_delete_tts(&self, language_code: String) -> DeletePlan {
        plan_delete_tts_in_snapshot(&self.snapshot, &language_code).into()
    }

    fn plan_delete_superseded_tts(
        &self,
        language_code: String,
        selected_pack_id: String,
    ) -> DeletePlan {
        plan_delete_superseded_tts_in_snapshot(&self.snapshot, &language_code, &selected_pack_id)
            .into()
    }

    fn tts_size_bytes(&self, language_code: String) -> i64 {
        self.snapshot
            .catalog
            .tts_size_bytes_for_language(&language_code) as i64
    }

    fn translation_size_bytes(&self, language_code: String) -> i64 {
        self.snapshot
            .catalog
            .translation_size_bytes_for_language(&language_code) as i64
    }

    fn default_tts_pack_id(&self, language_code: String) -> Option<String> {
        self.snapshot
            .catalog
            .default_tts_pack_id_for_language(&language_code)
    }

    fn resolve_tts_voice_files(&self, language_code: String) -> Option<TtsVoiceFiles> {
        let files = resolve_tts_voice_files_in_snapshot(&self.snapshot, &language_code)?;
        let base = Path::new(&self.snapshot.base_dir);
        let model_path = base.join(&files.model_install_path);
        if !model_path.exists() {
            return None;
        }
        let aux_path = base.join(&files.aux_install_path);
        if !aux_path.exists() {
            return None;
        }
        Some(TtsVoiceFiles {
            engine: files.engine,
            model_path: model_path.to_string_lossy().into_owned(),
            aux_path: aux_path.to_string_lossy().into_owned(),
            language_code: files.language_code,
            speaker_id: files.speaker_id,
        })
    }
}
