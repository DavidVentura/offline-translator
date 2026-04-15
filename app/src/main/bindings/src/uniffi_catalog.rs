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
pub struct TtsVoiceFiles {
    pub engine: String,
    pub model_path: String,
    pub aux_path: String,
    pub language_code: String,
    pub speaker_id: Option<i32>,
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
    width: u32,
    height: u32,
    source_code: &str,
    target_code: &str,
    min_confidence: u32,
    reading_order: translator::ReadingOrder,
    background_mode: translator::BackgroundMode,
) -> Result<Option<translator::PreparedImageOverlay>, String> {
    let bytes_per_pixel = 4i32;
    let i_width = width as i32;
    let i_height = height as i32;
    let bytes_per_line = i_width
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
            .set_frame(rgba_bytes, i_width, i_height, bytes_per_pixel, bytes_per_line)
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
                    left: word.bounding_rect.left as u32,
                    top: word.bounding_rect.top as u32,
                    right: word.bounding_rect.right as u32,
                    bottom: word.bounding_rect.bottom as u32,
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

    translator::prepare_overlay_image(
        rgba_bytes,
        width,
        height,
        &blocks,
        &translated_blocks,
        background_mode,
        reading_order,
    )
    .map(Some)
}

#[cfg(not(feature = "tesseract"))]
fn translate_image_plan_in_snapshot(
    _snapshot: &CatalogSnapshot,
    _rgba_bytes: &[u8],
    _width: u32,
    _height: u32,
    _source_code: &str,
    _target_code: &str,
    _min_confidence: u32,
    _reading_order: translator::ReadingOrder,
    _background_mode: translator::BackgroundMode,
) -> Result<Option<translator::PreparedImageOverlay>, String> {
    Ok(None)
}

#[uniffi::export]
fn sample_overlay_colors_rgba(
    rgba_bytes: Vec<u8>,
    width: u32,
    height: u32,
    bounds: translator::Rect,
    background_mode: translator::BackgroundMode,
    word_rects: Option<Vec<translator::Rect>>,
) -> Option<translator::OverlayColors> {
    sample_overlay_colors(
        &rgba_bytes,
        width,
        height,
        bounds,
        background_mode,
        word_rects.as_deref(),
    )
    .ok()
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

    fn language_rows(&self) -> Vec<translator::LanguageAvailabilityRow> {
        language_rows_in_snapshot(&self.snapshot)
    }

    fn dictionary_info(&self, dictionary_code: String) -> Option<translator::DictionaryInfo> {
        self.snapshot.catalog.dictionary_info(&dictionary_code)
    }

    fn has_tts_voices(&self, language_code: String) -> bool {
        self.snapshot.catalog.has_tts_voices(&language_code)
    }

    fn tts_voice_picker_regions(
        &self,
        language_code: String,
    ) -> Vec<translator::TtsVoicePickerRegion> {
        self.snapshot
            .catalog
            .tts_voice_picker_regions(&language_code)
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
    ) -> Option<Vec<translator::TranslationWithAlignment>> {
        with_engine(|engine| {
            translate_texts_with_alignment_in_snapshot(
                engine,
                &self.snapshot,
                &from_code,
                &to_code,
                &texts,
            )
            .transpose()
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
    ) -> translator::MixedTextTranslationResult {
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
    }

    fn translate_structured_fragments(
        &self,
        fragments: Vec<translator::StructuredStyledFragment>,
        forced_source_code: Option<String>,
        target_code: String,
        available_language_codes: Vec<String>,
        screenshot: Option<translator::OverlayScreenshot>,
        background_mode: translator::BackgroundMode,
    ) -> translator::StructuredTranslationResult {
        with_engine(|engine| {
            translate_structured_fragments_in_snapshot(
                engine,
                &self.snapshot,
                &fragments,
                forced_source_code.as_deref(),
                &target_code,
                &available_language_codes,
                screenshot.as_ref(),
                background_mode,
            )
        })
        .unwrap_or_else(|error_message| translator::StructuredTranslationResult {
            blocks: Vec::new(),
            nothing_reason: None,
            error_message: Some(error_message),
        })
    }

    fn translate_image_plan(
        &self,
        rgba_bytes: Vec<u8>,
        width: u32,
        height: u32,
        source_code: String,
        target_code: String,
        min_confidence: u32,
        reading_order: translator::ReadingOrder,
        background_mode: translator::BackgroundMode,
    ) -> Option<translator::PreparedImageOverlay> {
        translate_image_plan_in_snapshot(
            &self.snapshot,
            &rgba_bytes,
            width,
            height,
            &source_code,
            &target_code,
            min_confidence,
            reading_order,
            background_mode,
        )
        .ok()
        .flatten()
    }

    fn plan_language_download(
        &self,
        language_code: String,
    ) -> translator::DownloadPlan {
        plan_language_download_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_dictionary_download(
        &self,
        language_code: String,
    ) -> Option<translator::DownloadPlan> {
        plan_dictionary_download_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_tts_download(
        &self,
        language_code: String,
        selected_pack_id: Option<String>,
    ) -> Option<translator::DownloadPlan> {
        plan_tts_download_in_snapshot(&self.snapshot, &language_code, selected_pack_id.as_deref())
    }

    fn plan_delete_language(&self, language_code: String) -> translator::DeletePlan {
        plan_delete_language_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_delete_dictionary(&self, language_code: String) -> translator::DeletePlan {
        plan_delete_dictionary_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_delete_tts(&self, language_code: String) -> translator::DeletePlan {
        plan_delete_tts_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_delete_superseded_tts(
        &self,
        language_code: String,
        selected_pack_id: String,
    ) -> translator::DeletePlan {
        plan_delete_superseded_tts_in_snapshot(&self.snapshot, &language_code, &selected_pack_id)
    }

    fn tts_size_bytes(&self, language_code: String) -> u64 {
        self.snapshot
            .catalog
            .tts_size_bytes_for_language(&language_code)
    }

    fn translation_size_bytes(&self, language_code: String) -> u64 {
        self.snapshot
            .catalog
            .translation_size_bytes_for_language(&language_code)
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
