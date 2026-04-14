use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use serde_json::Value;
use translator::{
    CatalogSnapshot, PackInstallChecker, build_catalog_snapshot, can_translate_in_snapshot,
    parse_and_validate_catalog, plan_delete_dictionary_in_snapshot,
    plan_delete_language_in_snapshot, plan_delete_superseded_tts_in_snapshot,
    plan_delete_tts_in_snapshot, plan_dictionary_download_in_snapshot,
    plan_language_download_in_snapshot, plan_tts_download_in_snapshot,
    resolve_translation_plan_in_snapshot, resolve_tts_voice_files_in_snapshot, select_best_catalog,
};

struct FsInstallChecker {
    base_dir: PathBuf,
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
pub struct TtsRegionEntry {
    pub code: String,
    pub display_name: String,
    pub voices: Vec<String>,
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
pub struct TranslationStep {
    pub from_code: String,
    pub to_code: String,
    pub cache_key: String,
    pub config: String,
}

impl From<translator::TranslationStep> for TranslationStep {
    fn from(s: translator::TranslationStep) -> Self {
        Self {
            from_code: s.from_code,
            to_code: s.to_code,
            cache_key: s.cache_key,
            config: s.config,
        }
    }
}

#[derive(uniffi::Record)]
pub struct TranslationPlan {
    pub steps: Vec<TranslationStep>,
}

impl From<translator::TranslationPlan> for TranslationPlan {
    fn from(p: translator::TranslationPlan) -> Self {
        Self {
            steps: p.steps.into_iter().map(Into::into).collect(),
        }
    }
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
    ) -> Arc<Self> {
        let catalog = parse_selected_catalog(&bundled_json, disk_json.as_deref())
            .expect("failed to parse any catalog");
        let checker = FsInstallChecker {
            base_dir: PathBuf::from(&base_dir),
        };
        let snapshot = build_catalog_snapshot(catalog, base_dir, &checker);
        Arc::new(CatalogHandle { snapshot })
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

    fn languages(&self) -> Vec<LanguageInfo> {
        self.snapshot
            .catalog
            .language_list()
            .iter()
            .map(Into::into)
            .collect()
    }

    fn language_availability(&self) -> HashMap<String, LangAvailability> {
        self.snapshot
            .availability_by_code
            .iter()
            .map(|(code, avail)| (code.clone(), (*avail).into()))
            .collect()
    }

    fn dictionary_info(&self, dictionary_code: String) -> Option<DictionaryInfo> {
        self.snapshot
            .catalog
            .dictionary_info(&dictionary_code)
            .map(Into::into)
    }

    fn tts_pack_ids(&self, language_code: String) -> Vec<String> {
        self.snapshot
            .catalog
            .tts_pack_ids_for_language(&language_code)
    }

    fn ordered_tts_regions(&self, language_code: String) -> Vec<TtsRegionEntry> {
        self.snapshot
            .catalog
            .ordered_tts_regions_for_language(&language_code)
            .into_iter()
            .map(|(code, region)| TtsRegionEntry {
                code,
                display_name: region.display_name,
                voices: region.voices,
            })
            .collect()
    }

    fn tts_voice_pack_info(&self, pack_id: String) -> Option<TtsVoicePackInfo> {
        self.snapshot
            .catalog
            .tts_voice_pack_info(&pack_id)
            .map(Into::into)
    }

    fn can_swap_languages(&self, from_code: String, to_code: String) -> bool {
        self.snapshot
            .catalog
            .can_swap_languages(&from_code, &to_code)
    }

    fn can_translate(&self, from_code: String, to_code: String) -> bool {
        can_translate_in_snapshot(&self.snapshot, &from_code, &to_code)
    }

    fn resolve_translation_plan(
        &self,
        from_code: String,
        to_code: String,
    ) -> Option<TranslationPlan> {
        resolve_translation_plan_in_snapshot(&self.snapshot, &from_code, &to_code).map(Into::into)
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
        plan_tts_download_in_snapshot(
            &self.snapshot,
            &language_code,
            selected_pack_id.as_deref(),
        )
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
        plan_delete_superseded_tts_in_snapshot(
            &self.snapshot,
            &language_code,
            &selected_pack_id,
        )
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
