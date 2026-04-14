pub mod bergamot;
pub mod catalog;
pub mod language;
pub mod language_detect;
pub mod ocr;
pub mod routing;
pub mod settings;
pub mod translate;
pub mod tts;

pub use bergamot::BergamotEngine;
pub use catalog::{
    AssetFileV2, AssetPackMetadataV2, CatalogSnapshot, CatalogSourcesV2, DeletePlan,
    DictionaryInfo, DownloadPlan, DownloadTask, LangAvailability, LanguageAvailabilityRow,
    LanguageCatalog, LanguageFeature, LanguageTtsRegionV2, LanguageTtsV2, PackInstallChecker,
    PackInstallStatus, PackKind, PackRecord, PackResolver, ResolvedTtsVoiceFiles,
    TtsVoicePackInfo, TtsVoicePickerRegion, build_catalog_snapshot,
    can_swap_languages_installed, can_translate, can_translate_in_snapshot,
    can_translate_with_checker, compute_language_availability, has_translation_direction_installed,
    installed_tts_pack_id_for_language, is_pack_installed, language_rows_in_snapshot,
    parse_and_validate_catalog, parse_language_catalog,
    plan_delete_dictionary, plan_delete_dictionary_in_snapshot, plan_delete_language,
    plan_delete_language_in_snapshot, plan_delete_superseded_tts,
    plan_delete_superseded_tts_in_snapshot, plan_delete_tts, plan_delete_tts_in_snapshot,
    plan_dictionary_download, plan_dictionary_download_in_snapshot, plan_language_download,
    plan_language_download_in_snapshot, plan_tts_download, plan_tts_download_in_snapshot,
    resolve_tts_voice_files, resolve_tts_voice_files_in_snapshot, select_best_catalog,
};
pub use language::Language;
pub use language_detect::{DetectionResult, detect_language};
pub use ocr::{DetectedWord, ReadingOrder, Rect, TextBlock, TextLine};
pub use routing::{
    BatchTextRoutingPlan, NothingReason, SourceTextBatch, detect_language_robust_code,
    plan_batch_text_translation,
};
pub use settings::{AppSettings, BackgroundMode, DEFAULT_CATALOG_INDEX_URL};
pub use translate::{
    TokenAlignment, TranslatedText, TranslationPlan, TranslationStep, TranslationWithAlignment,
    resolve_translation_plan, resolve_translation_plan_in_snapshot,
    resolve_translation_plan_with_checker,
};
pub use tts::{
    PcmAudio, PhonemeChunk, SpeechChunk, SpeechChunkBoundary, TtsVoiceOption, plan_speech_chunks,
};
