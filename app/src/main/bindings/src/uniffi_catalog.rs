use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

use serde_json::Value;
use thiserror::Error;
use translator::{
    BergamotEngine, CatalogSnapshot, PackInstallChecker, TextTranslationOutcome,
    TranslationWarmOutcome, Translator, build_catalog_snapshot, language_rows_in_snapshot,
    parse_and_validate_catalog, plan_delete_dictionary_in_snapshot,
    plan_delete_language_in_snapshot, plan_delete_superseded_tts_in_snapshot,
    plan_delete_tts_in_snapshot, plan_dictionary_download_in_snapshot,
    plan_language_download_in_snapshot, plan_tts_download_in_snapshot, sample_overlay_colors,
    select_best_catalog,
};
#[cfg(feature = "dictionary")]
use translator::{
    DictionaryLookupOutcome, close_dictionary_in_snapshot, lookup_dictionary_in_snapshot,
};
#[cfg(feature = "tts")]
use translator::{
    PcmSynthesisOutcome, SpeechChunkPlanningOutcome, TtsVoicesOutcome,
    available_tts_voices_in_snapshot, clear_cached_model, plan_speech_chunks_for_text_in_snapshot,
    synthesize_pcm_in_snapshot,
};
struct FsInstallChecker {
    base_dir: PathBuf,
}

static ENGINE: OnceLock<Mutex<BergamotEngine>> = OnceLock::new();

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

#[cfg(feature = "tesseract")]
fn translate_image_plan_in_snapshot(
    snapshot: &CatalogSnapshot,
    rgba_bytes: &[u8],
    width: u32,
    height: u32,
    source_code: &translator::LanguageCode,
    target_code: &translator::LanguageCode,
    min_confidence: u32,
    reading_order: translator::ReadingOrder,
    background_mode: translator::BackgroundMode,
) -> Result<translator::ImageTranslationOutcome, String> {
    with_engine(|engine| {
        Translator::new(engine, snapshot)
            .translate_image_rgba(
                rgba_bytes,
                width,
                height,
                source_code,
                target_code,
                min_confidence,
                reading_order,
                background_mode,
            )
            .map_err(|error| error.message)
    })
}

#[cfg(not(feature = "tesseract"))]
fn translate_image_plan_in_snapshot(
    _snapshot: &CatalogSnapshot,
    _rgba_bytes: &[u8],
    _width: u32,
    _height: u32,
    _source_code: &translator::LanguageCode,
    _target_code: &translator::LanguageCode,
    _min_confidence: u32,
    _reading_order: translator::ReadingOrder,
    _background_mode: translator::BackgroundMode,
) -> Result<translator::ImageTranslationOutcome, String> {
    Ok(translator::ImageTranslationOutcome::MissingLanguagePair)
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

    fn dictionary_info(
        &self,
        dictionary_code: translator::DictionaryCode,
    ) -> Option<translator::DictionaryInfo> {
        self.snapshot.catalog.dictionary_info(&dictionary_code)
    }

    fn lookup_dictionary(
        &self,
        language_code: translator::LanguageCode,
        word: String,
    ) -> Option<DictionaryWordRecord> {
        #[cfg(not(feature = "dictionary"))]
        {
            let _ = (language_code, word);
            return None;
        }

        #[cfg(feature = "dictionary")]
        match lookup_dictionary_in_snapshot(&self.snapshot, &language_code, &word) {
            Ok(DictionaryLookupOutcome::Found(word)) => Some(map_dictionary_word(word)),
            Ok(DictionaryLookupOutcome::NotFound | DictionaryLookupOutcome::MissingDictionary) => {
                None
            }
            Err(_) => None,
        }
    }

    fn has_tts_voices(&self, language_code: translator::LanguageCode) -> bool {
        self.snapshot.catalog.has_tts_voices(&language_code)
    }

    fn tts_voice_picker_regions(
        &self,
        language_code: translator::LanguageCode,
    ) -> Vec<translator::TtsVoicePickerRegion> {
        self.snapshot
            .catalog
            .tts_voice_picker_regions(&language_code)
    }

    fn can_swap_languages(
        &self,
        from_code: translator::LanguageCode,
        to_code: translator::LanguageCode,
    ) -> bool {
        self.snapshot
            .catalog
            .can_swap_languages(&from_code, &to_code)
    }

    fn can_translate(
        &self,
        from_code: translator::LanguageCode,
        to_code: translator::LanguageCode,
    ) -> bool {
        self.snapshot.can_translate(&from_code, &to_code)
    }

    fn warm_translation_models(
        &self,
        from_code: translator::LanguageCode,
        to_code: translator::LanguageCode,
    ) -> bool {
        with_engine(|engine| {
            Translator::new(engine, &self.snapshot)
                .warm(&from_code, &to_code)
                .map_err(|error| error.message)
        })
        .map(|outcome| matches!(outcome, TranslationWarmOutcome::Warmed))
        .unwrap_or(false)
    }

    fn translate_text(
        &self,
        from_code: translator::LanguageCode,
        to_code: translator::LanguageCode,
        text: String,
    ) -> Option<String> {
        with_engine(|engine| {
            Translator::new(engine, &self.snapshot)
                .translate_text(&from_code, &to_code, &text)
                .map_err(|error| error.message)
        })
        .ok()
        .and_then(|outcome| match outcome {
            TextTranslationOutcome::Translated(value)
            | TextTranslationOutcome::Passthrough(value) => Some(value),
            TextTranslationOutcome::MissingLanguagePair => None,
        })
    }

    fn translate_mixed_texts(
        &self,
        inputs: Vec<String>,
        forced_source_code: Option<translator::LanguageCode>,
        target_code: translator::LanguageCode,
        available_language_codes: Vec<translator::LanguageCode>,
    ) -> translator::MixedTextTranslationResult {
        with_engine(|engine| {
            Translator::new(engine, &self.snapshot)
                .translate_mixed_texts(
                    &inputs,
                    forced_source_code.as_ref(),
                    &target_code,
                    &available_language_codes,
                )
                .map_err(|error| error.message)
        })
        .unwrap_or_else(|_| translator::MixedTextTranslationResult::default())
    }

    fn translate_structured_fragments(
        &self,
        fragments: Vec<translator::StructuredStyledFragment>,
        forced_source_code: Option<translator::LanguageCode>,
        target_code: translator::LanguageCode,
        available_language_codes: Vec<translator::LanguageCode>,
        screenshot: Option<translator::OverlayScreenshot>,
        background_mode: translator::BackgroundMode,
    ) -> translator::StructuredTranslationResult {
        with_engine(|engine| {
            Translator::new(engine, &self.snapshot)
                .translate_structured_fragments(
                    &fragments,
                    forced_source_code.as_ref(),
                    &target_code,
                    &available_language_codes,
                    screenshot.as_ref(),
                    background_mode,
                )
                .map_err(|error| error.message)
        })
        .unwrap_or_else(|error| translator::StructuredTranslationResult {
            blocks: Vec::new(),
            nothing_reason: None,
            error_message: Some(error),
        })
    }

    fn translate_image_plan(
        &self,
        rgba_bytes: Vec<u8>,
        width: u32,
        height: u32,
        source_code: translator::LanguageCode,
        target_code: translator::LanguageCode,
        min_confidence: u32,
        reading_order: translator::ReadingOrder,
        background_mode: translator::BackgroundMode,
    ) -> translator::ImageTranslationOutcome {
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
        .unwrap_or(translator::ImageTranslationOutcome::MissingLanguagePair)
    }

    fn plan_language_download(
        &self,
        language_code: translator::LanguageCode,
    ) -> translator::DownloadPlan {
        plan_language_download_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_dictionary_download(
        &self,
        language_code: translator::LanguageCode,
    ) -> Option<translator::DownloadPlan> {
        plan_dictionary_download_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_tts_download(
        &self,
        language_code: translator::LanguageCode,
        selected_pack_id: Option<String>,
    ) -> Option<translator::DownloadPlan> {
        plan_tts_download_in_snapshot(&self.snapshot, &language_code, selected_pack_id.as_deref())
    }

    fn plan_delete_language(
        &self,
        language_code: translator::LanguageCode,
    ) -> translator::DeletePlan {
        #[cfg(feature = "dictionary")]
        let _ = close_dictionary_in_snapshot(&self.snapshot, &language_code);
        #[cfg(feature = "tts")]
        clear_cached_model();
        plan_delete_language_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_delete_dictionary(
        &self,
        language_code: translator::LanguageCode,
    ) -> translator::DeletePlan {
        #[cfg(feature = "dictionary")]
        let _ = close_dictionary_in_snapshot(&self.snapshot, &language_code);
        plan_delete_dictionary_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_delete_tts(&self, language_code: translator::LanguageCode) -> translator::DeletePlan {
        #[cfg(feature = "tts")]
        clear_cached_model();
        plan_delete_tts_in_snapshot(&self.snapshot, &language_code)
    }

    fn plan_delete_superseded_tts(
        &self,
        language_code: translator::LanguageCode,
        selected_pack_id: String,
    ) -> translator::DeletePlan {
        #[cfg(feature = "tts")]
        clear_cached_model();
        plan_delete_superseded_tts_in_snapshot(&self.snapshot, &language_code, &selected_pack_id)
    }

    fn tts_size_bytes(&self, language_code: translator::LanguageCode) -> u64 {
        self.snapshot
            .catalog
            .tts_size_bytes_for_language(&language_code)
    }

    fn translation_size_bytes(&self, language_code: translator::LanguageCode) -> u64 {
        self.snapshot
            .catalog
            .translation_size_bytes_for_language(&language_code)
    }

    fn default_tts_pack_id(&self, language_code: translator::LanguageCode) -> Option<String> {
        self.snapshot
            .catalog
            .default_tts_pack_id_for_language(&language_code)
    }

    fn available_tts_voices(
        &self,
        language_code: translator::LanguageCode,
    ) -> Vec<translator::TtsVoiceOption> {
        #[cfg(feature = "tts")]
        {
            return match available_tts_voices_in_snapshot(&self.snapshot, &language_code) {
                Ok(TtsVoicesOutcome::Available(voices)) => voices,
                Ok(TtsVoicesOutcome::MissingLanguage) | Err(_) => Vec::new(),
            };
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = language_code;
            Vec::new()
        }
    }

    fn plan_speech_chunks(
        &self,
        language_code: translator::LanguageCode,
        text: String,
    ) -> Vec<translator::SpeechChunk> {
        #[cfg(feature = "tts")]
        {
            return match plan_speech_chunks_for_text_in_snapshot(
                &self.snapshot,
                &language_code,
                &text,
            ) {
                Ok(SpeechChunkPlanningOutcome::Planned(chunks)) => chunks,
                Ok(SpeechChunkPlanningOutcome::MissingLanguage) | Err(_) => Vec::new(),
            };
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (language_code, text);
            Vec::new()
        }
    }

    fn synthesize_speech_pcm(
        &self,
        language_code: translator::LanguageCode,
        text: String,
        speech_speed: f32,
        voice_name: Option<translator::VoiceName>,
        is_phonemes: bool,
    ) -> Option<translator::PcmAudio> {
        #[cfg(feature = "tts")]
        {
            return match synthesize_pcm_in_snapshot(
                &self.snapshot,
                &language_code,
                &text,
                speech_speed,
                voice_name.as_ref(),
                is_phonemes,
            ) {
                Ok(PcmSynthesisOutcome::Ready(audio)) => Some(audio),
                Ok(PcmSynthesisOutcome::MissingLanguage) | Err(_) => None,
            };
        }

        #[cfg(not(feature = "tts"))]
        {
            let _ = (language_code, text, speech_speed, voice_name, is_phonemes);
            None
        }
    }
}
