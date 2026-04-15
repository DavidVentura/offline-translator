use std::fs;
#[cfg(any(feature = "dictionary", feature = "tesseract", feature = "tts"))]
use std::path::Path;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use serde_json::Value;
#[cfg(feature = "tesseract")]
use translator::PageSegMode;
use thiserror::Error;
#[cfg(feature = "tesseract")]
use translator::build_text_blocks;
#[cfg(feature = "dictionary")]
use translator::{close_dictionary, lookup_dictionary};
#[cfg(feature = "tts")]
use translator::{clear_cached_model, list_voices, plan_speech_chunks_for_text, synthesize_pcm};
#[cfg(feature = "tesseract")]
use translator::TesseractWrapper;
use translator::{
    BergamotEngine, CatalogSnapshot, PackInstallChecker, build_catalog_snapshot,
    can_translate_in_snapshot, language_rows_in_snapshot, parse_and_validate_catalog,
    plan_delete_dictionary_in_snapshot, plan_delete_language_in_snapshot,
    plan_delete_superseded_tts_in_snapshot, plan_delete_tts_in_snapshot,
    plan_dictionary_download_in_snapshot, plan_language_download_in_snapshot, plan_tts_download_in_snapshot,
    sample_overlay_colors, select_best_catalog, translate_structured_fragments_in_snapshot,
    translate_mixed_texts_in_snapshot, translate_texts_in_snapshot,
    translate_texts_with_alignment_in_snapshot,
};
#[cfg(feature = "tts")]
use translator::resolve_tts_voice_files_in_snapshot;

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

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionaryGlossRecord {
    pub gloss_lines: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionarySenseRecord {
    pub pos: String,
    pub glosses: Vec<DictionaryGlossRecord>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionaryWordEntryRecord {
    pub senses: Vec<DictionarySenseRecord>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DictionaryWordRecord {
    pub word: String,
    pub tag: i32,
    pub entries: Vec<DictionaryWordEntryRecord>,
    pub sounds: Option<String>,
    pub hyphenations: Vec<String>,
    pub redirects: Vec<String>,
}

#[cfg(feature = "dictionary")]
fn dictionary_path_for_language(snapshot: &CatalogSnapshot, language_code: &str) -> Option<String> {
    let language = snapshot.catalog.language_by_code(language_code)?;
    let path = Path::new(&snapshot.base_dir)
        .join("dictionaries")
        .join(format!("{}.dict", language.dictionary_code));
    Some(path.to_string_lossy().into_owned())
}

#[cfg(feature = "dictionary")]
fn map_dictionary_word(word: translator::tarkka::WordWithTaggedEntries) -> DictionaryWordRecord {
    DictionaryWordRecord {
        word: word.word,
        tag: word.tag as i32,
        entries: word
            .entries
            .into_iter()
            .map(|entry| DictionaryWordEntryRecord {
                senses: entry
                    .senses
                    .into_iter()
                    .map(|sense| DictionarySenseRecord {
                        pos: sense.pos.to_string(),
                        glosses: sense
                            .glosses
                            .into_iter()
                            .map(|gloss| DictionaryGlossRecord {
                                gloss_lines: gloss.gloss_lines,
                            })
                            .collect(),
                    })
                    .collect(),
            })
            .collect(),
        sounds: word.sounds,
        hyphenations: word.hyphenations,
        redirects: word.redirects,
    }
}

#[cfg(feature = "dictionary")]
fn lookup_dictionary_in_snapshot(
    snapshot: &CatalogSnapshot,
    language_code: &str,
    word: &str,
) -> Option<DictionaryWordRecord> {
    let path = dictionary_path_for_language(snapshot, language_code)?;
    lookup_dictionary(&path, word).ok().flatten().map(map_dictionary_word)
}

#[cfg(not(feature = "dictionary"))]
fn lookup_dictionary_in_snapshot(
    _snapshot: &CatalogSnapshot,
    _language_code: &str,
    _word: &str,
) -> Option<DictionaryWordRecord> {
    None
}

#[cfg(feature = "dictionary")]
fn close_dictionary_in_snapshot(
    snapshot: &CatalogSnapshot,
    language_code: &str,
) {
    if let Some(path) = dictionary_path_for_language(snapshot, language_code) {
        let _ = close_dictionary(&path);
    }
}

#[cfg(not(feature = "dictionary"))]
fn close_dictionary_in_snapshot(
    _snapshot: &CatalogSnapshot,
    _language_code: &str,
) {
}

#[cfg(feature = "tts")]
struct ResolvedSpeechAssets {
    engine: String,
    model_path: String,
    aux_path: String,
    language_code: String,
    speaker_id: Option<i64>,
    support_data_root: Option<String>,
}

#[cfg(feature = "tts")]
fn support_data_root(snapshot: &CatalogSnapshot) -> Option<String> {
    let data_dir = Path::new(&snapshot.base_dir).join("bin");
    data_dir
        .join("espeak-ng-data")
        .exists()
        .then(|| data_dir.to_string_lossy().into_owned())
}

#[cfg(feature = "tts")]
fn resolve_speech_assets(
    snapshot: &CatalogSnapshot,
    language_code: &str,
) -> Option<ResolvedSpeechAssets> {
    let files = resolve_tts_voice_files_in_snapshot(snapshot, language_code)?;
    let base = Path::new(&snapshot.base_dir);
    let model_path = base.join(&files.model_install_path);
    if !model_path.exists() {
        return None;
    }
    let aux_path = base.join(&files.aux_install_path);
    if !aux_path.exists() {
        return None;
    }
    Some(ResolvedSpeechAssets {
        engine: files.engine,
        model_path: model_path.to_string_lossy().into_owned(),
        aux_path: aux_path.to_string_lossy().into_owned(),
        language_code: files.language_code,
        speaker_id: files.speaker_id.map(i64::from),
        support_data_root: support_data_root(snapshot),
    })
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

    fn lookup_dictionary(
        &self,
        language_code: String,
        word: String,
    ) -> Option<DictionaryWordRecord> {
        let normalized = word.trim();
        if normalized.is_empty() {
            return None;
        }
        lookup_dictionary_in_snapshot(&self.snapshot, &language_code, normalized)
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
        close_dictionary_in_snapshot(&self.snapshot, &language_code);
        #[cfg(feature = "tts")]
        clear_cached_model();
        plan_delete_language_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_delete_dictionary(&self, language_code: String) -> translator::DeletePlan {
        close_dictionary_in_snapshot(&self.snapshot, &language_code);
        plan_delete_dictionary_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_delete_tts(&self, language_code: String) -> translator::DeletePlan {
        #[cfg(feature = "tts")]
        clear_cached_model();
        plan_delete_tts_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_delete_superseded_tts(
        &self,
        language_code: String,
        selected_pack_id: String,
    ) -> translator::DeletePlan {
        #[cfg(feature = "tts")]
        clear_cached_model();
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

    fn available_tts_voices(&self, language_code: String) -> Vec<translator::TtsVoiceOption> {
        #[cfg(feature = "tts")]
        {
            let Some(assets) = resolve_speech_assets(&self.snapshot, &language_code) else {
                return Vec::new();
            };
            return list_voices(
                &assets.engine,
                &assets.model_path,
                &assets.aux_path,
                assets.support_data_root.as_deref(),
                &assets.language_code,
            )
            .unwrap_or_default()
            .into_iter()
            .map(|voice| translator::TtsVoiceOption {
                name: voice.name,
                speaker_id: voice.speaker_id,
                display_name: voice.display_name,
            })
            .collect();
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = language_code;
            Vec::new()
        }
    }

    fn plan_speech_chunks(
        &self,
        language_code: String,
        text: String,
    ) -> Vec<translator::SpeechChunk> {
        #[cfg(feature = "tts")]
        {
            let Some(assets) = resolve_speech_assets(&self.snapshot, &language_code) else {
                return Vec::new();
            };
            return plan_speech_chunks_for_text(
                &assets.engine,
                &assets.model_path,
                &assets.aux_path,
                assets.support_data_root.as_deref(),
                &assets.language_code,
                &text,
            )
            .unwrap_or_default();
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (language_code, text);
            Vec::new()
        }
    }

    fn synthesize_speech_pcm(
        &self,
        language_code: String,
        text: String,
        speech_speed: f32,
        voice_name: Option<String>,
        is_phonemes: bool,
    ) -> Option<translator::PcmAudio> {
        #[cfg(feature = "tts")]
        {
            let assets = resolve_speech_assets(&self.snapshot, &language_code)?;
            let audio = synthesize_pcm(
                &assets.engine,
                &assets.model_path,
                &assets.aux_path,
                assets.support_data_root.as_deref(),
                &assets.language_code,
                &text,
                speech_speed,
                voice_name.as_deref(),
                assets.speaker_id,
                is_phonemes,
            )
            .ok()?;
            return Some(translator::PcmAudio {
                sample_rate: audio.sample_rate,
                pcm_samples: audio.pcm_samples,
            });
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (language_code, text, speech_speed, voice_name, is_phonemes);
            None
        }
    }
}
