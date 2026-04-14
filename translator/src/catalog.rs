#[path = "catalog_model.rs"]
mod model;
#[path = "catalog_planner.rs"]
mod planner;
#[path = "catalog_wire.rs"]
mod wire;

pub use model::{
    AssetFileV2, AssetPackMetadataV2, CatalogSourcesV2, DeletePlan, DictionaryInfo, DownloadPlan,
    DownloadTask, LangAvailability, LanguageCatalog, LanguageFeature, LanguageTtsRegionV2,
    LanguageTtsV2, PackKind, PackRecord, ResolvedTtsVoiceFiles, TtsVoicePackInfo,
};
pub use planner::{
    CatalogSnapshot, PackInstallChecker, PackInstallStatus, PackResolver, build_catalog_snapshot,
    can_swap_languages_installed, can_translate, can_translate_in_snapshot,
    can_translate_with_checker, compute_language_availability, has_translation_direction_installed,
    installed_tts_pack_id_for_language, is_pack_installed, plan_delete_dictionary,
    plan_delete_dictionary_in_snapshot, plan_delete_language, plan_delete_language_in_snapshot,
    plan_delete_superseded_tts, plan_delete_superseded_tts_in_snapshot, plan_delete_tts,
    plan_delete_tts_in_snapshot, plan_dictionary_download, plan_dictionary_download_in_snapshot,
    plan_language_download, plan_language_download_in_snapshot, plan_tts_download,
    plan_tts_download_in_snapshot, resolve_tts_voice_files, resolve_tts_voice_files_in_snapshot,
};
pub use wire::{parse_and_validate_catalog, parse_language_catalog, select_best_catalog};

#[cfg(test)]
#[path = "catalog_tests.rs"]
mod tests;
