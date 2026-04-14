use std::collections::{HashMap, HashSet};

use crate::language::Language;

use super::model::{
    AssetFileV2, DeletePlan, DownloadPlan, DownloadTask, LangAvailability, LanguageCatalog,
    PackKind, PackRecord, ResolvedTtsVoiceFiles,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackInstallStatus {
    pub pack_id: String,
    pub installed: bool,
    pub missing_files: Vec<AssetFileV2>,
    pub missing_dependency_ids: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct MissingPackFile {
    pack_id: String,
    file: AssetFileV2,
}

pub trait PackInstallChecker {
    fn file_exists(&self, install_path: &str) -> bool;

    fn install_marker_exists(&self, marker_path: &str, expected_version: i32) -> bool;
}

pub struct PackResolver<'a, C> {
    catalog: &'a LanguageCatalog,
    install_checker: &'a C,
    status_cache: HashMap<String, PackInstallStatus>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CatalogSnapshot {
    pub catalog: LanguageCatalog,
    pub base_dir: String,
    pub pack_statuses: HashMap<String, PackInstallStatus>,
    pub availability_by_code: HashMap<String, LangAvailability>,
}

impl<'a, C> PackResolver<'a, C>
where
    C: PackInstallChecker,
{
    pub fn new(catalog: &'a LanguageCatalog, install_checker: &'a C) -> Self {
        Self {
            catalog,
            install_checker,
            status_cache: HashMap::new(),
        }
    }

    pub fn status(&mut self, pack_id: &str) -> Option<PackInstallStatus> {
        if let Some(status) = self.status_cache.get(pack_id) {
            return Some(status.clone());
        }

        let pack = self.catalog.pack(pack_id)?;
        let missing_files = pack
            .files
            .iter()
            .filter(
                |file| match (&file.install_marker_path, file.install_marker_version) {
                    (Some(marker_path), Some(version)) => !self
                        .install_checker
                        .install_marker_exists(marker_path, version),
                    _ => !self.install_checker.file_exists(&file.install_path),
                },
            )
            .cloned()
            .collect::<Vec<_>>();

        let missing_dependency_ids = pack
            .depends_on
            .iter()
            .filter(|dep_id| self.status(dep_id).is_none_or(|status| !status.installed))
            .cloned()
            .collect::<Vec<_>>();

        let status = PackInstallStatus {
            pack_id: pack_id.to_string(),
            installed: missing_files.is_empty() && missing_dependency_ids.is_empty(),
            missing_files,
            missing_dependency_ids,
        };
        self.status_cache
            .insert(pack_id.to_string(), status.clone());
        Some(status)
    }

    pub fn is_installed(&mut self, pack_id: &str) -> bool {
        self.status(pack_id).is_some_and(|status| status.installed)
    }

    fn missing_files<'b, I>(&mut self, pack_ids: I) -> Vec<MissingPackFile>
    where
        I: IntoIterator<Item = &'b str>,
    {
        let mut missing = Vec::new();
        let mut seen_install_paths = HashSet::new();

        for pack_id in self.catalog.dependency_closure(pack_ids) {
            let Some(pack) = self.catalog.pack(&pack_id) else {
                continue;
            };
            let Some(status) = self.status(&pack_id) else {
                continue;
            };
            for file in status.missing_files {
                if seen_install_paths.insert(file.install_path.clone()) {
                    missing.push(MissingPackFile {
                        pack_id: pack.id.clone(),
                        file,
                    });
                }
            }
        }

        missing
    }
}

pub fn build_catalog_snapshot<C>(
    catalog: LanguageCatalog,
    base_dir: String,
    install_checker: &C,
) -> CatalogSnapshot
where
    C: PackInstallChecker,
{
    let mut resolver = PackResolver::new(&catalog, install_checker);
    let pack_ids = catalog.packs.keys().cloned().collect::<Vec<_>>();
    for pack_id in &pack_ids {
        let _ = resolver.status(pack_id);
    }

    let availability_by_code = compute_language_availability(&catalog, &mut resolver)
        .into_iter()
        .map(|(language, availability)| (language.code, availability))
        .collect::<HashMap<_, _>>();

    let pack_statuses = pack_ids
        .into_iter()
        .filter_map(|pack_id| resolver.status(&pack_id).map(|status| (pack_id, status)))
        .collect::<HashMap<_, _>>();

    CatalogSnapshot {
        catalog,
        base_dir,
        pack_statuses,
        availability_by_code,
    }
}

fn pack_installed_in_snapshot(snapshot: &CatalogSnapshot, pack_id: &str) -> bool {
    snapshot
        .pack_statuses
        .get(pack_id)
        .is_some_and(|status| status.installed)
}

fn missing_files_in_snapshot<'a, I>(
    snapshot: &'a CatalogSnapshot,
    pack_ids: I,
) -> Vec<MissingPackFile>
where
    I: IntoIterator<Item = &'a str>,
{
    let mut missing = Vec::new();
    let mut seen_install_paths = HashSet::new();

    for pack_id in snapshot.catalog.dependency_closure(pack_ids) {
        let Some(pack) = snapshot.catalog.pack(&pack_id) else {
            continue;
        };
        let Some(status) = snapshot.pack_statuses.get(&pack_id) else {
            continue;
        };
        for file in &status.missing_files {
            if seen_install_paths.insert(file.install_path.clone()) {
                missing.push(MissingPackFile {
                    pack_id: pack.id.clone(),
                    file: file.clone(),
                });
            }
        }
    }

    missing
}

fn pack_file_present<C>(install_checker: &C, file: &AssetFileV2) -> bool
where
    C: PackInstallChecker,
{
    match (&file.install_marker_path, file.install_marker_version) {
        (Some(marker_path), Some(version)) => {
            install_checker.install_marker_exists(marker_path, version)
        }
        _ => install_checker.file_exists(&file.install_path),
    }
}

pub fn is_pack_installed<C>(catalog: &LanguageCatalog, install_checker: &C, pack_id: &str) -> bool
where
    C: PackInstallChecker,
{
    fn visit<C>(
        catalog: &LanguageCatalog,
        install_checker: &C,
        pack_id: &str,
        seen: &mut HashSet<String>,
    ) -> bool
    where
        C: PackInstallChecker,
    {
        if !seen.insert(pack_id.to_string()) {
            return true;
        }

        let Some(pack) = catalog.pack(pack_id) else {
            return false;
        };

        pack.files
            .iter()
            .all(|file| pack_file_present(install_checker, file))
            && pack
                .depends_on
                .iter()
                .all(|dep_id| visit(catalog, install_checker, dep_id, seen))
    }

    visit(catalog, install_checker, pack_id, &mut HashSet::new())
}

pub fn has_translation_direction_installed<C>(
    catalog: &LanguageCatalog,
    from_code: &str,
    to_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> bool
where
    C: PackInstallChecker,
{
    catalog
        .translation_pack_id(from_code, to_code)
        .as_deref()
        .is_some_and(|pack_id| resolver.is_installed(pack_id))
}

pub fn can_translate<C>(
    catalog: &LanguageCatalog,
    from_code: &str,
    to_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> bool
where
    C: PackInstallChecker,
{
    if from_code == to_code {
        return true;
    }

    if from_code == "en" {
        return has_translation_direction_installed(catalog, "en", to_code, resolver);
    }
    if to_code == "en" {
        return has_translation_direction_installed(catalog, from_code, "en", resolver);
    }

    has_translation_direction_installed(catalog, from_code, "en", resolver)
        && has_translation_direction_installed(catalog, "en", to_code, resolver)
}

pub fn can_translate_with_checker<C>(
    catalog: &LanguageCatalog,
    from_code: &str,
    to_code: &str,
    install_checker: &C,
) -> bool
where
    C: PackInstallChecker,
{
    if from_code == to_code {
        return true;
    }

    let direction_installed = |from: &str, to: &str| {
        catalog
            .translation_pack_id(from, to)
            .as_deref()
            .is_some_and(|pack_id| is_pack_installed(catalog, install_checker, pack_id))
    };

    if from_code == "en" {
        return direction_installed("en", to_code);
    }
    if to_code == "en" {
        return direction_installed(from_code, "en");
    }

    direction_installed(from_code, "en") && direction_installed("en", to_code)
}

pub fn can_translate_in_snapshot(
    snapshot: &CatalogSnapshot,
    from_code: &str,
    to_code: &str,
) -> bool {
    if from_code == to_code {
        return true;
    }

    let direction_installed = |from: &str, to: &str| {
        snapshot
            .catalog
            .translation_pack_id(from, to)
            .as_deref()
            .is_some_and(|pack_id| pack_installed_in_snapshot(snapshot, pack_id))
    };

    if from_code == "en" {
        return direction_installed("en", to_code);
    }
    if to_code == "en" {
        return direction_installed(from_code, "en");
    }

    direction_installed(from_code, "en") && direction_installed("en", to_code)
}

pub fn can_swap_languages_installed<C>(
    catalog: &LanguageCatalog,
    from_code: &str,
    to_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> bool
where
    C: PackInstallChecker,
{
    let to_can_be_source =
        to_code == "en" || has_translation_direction_installed(catalog, to_code, "en", resolver);
    let from_can_be_target = from_code == "en"
        || has_translation_direction_installed(catalog, "en", from_code, resolver);
    to_can_be_source && from_can_be_target
}

pub fn installed_tts_pack_id_for_language<C>(
    catalog: &LanguageCatalog,
    language_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> Option<String>
where
    C: PackInstallChecker,
{
    catalog
        .tts_pack_ids_for_language(language_code)
        .into_iter()
        .find(|pack_id| resolver.is_installed(pack_id))
}

pub fn compute_language_availability<C>(
    catalog: &LanguageCatalog,
    resolver: &mut PackResolver<'_, C>,
) -> HashMap<Language, LangAvailability>
where
    C: PackInstallChecker,
{
    let mut rows = HashMap::new();

    for (code, info) in &catalog.languages {
        let language = info.language.clone();
        let ocr_pack_id = info
            .resources
            .ocr_packs
            .iter()
            .find(|(engine, _)| engine == "tesseract")
            .map(|(_, pack_id)| pack_id.as_str());
        let dictionary_pack_id = catalog.dictionary_pack_id_for_language(code);
        let availability = if language.is_english() {
            LangAvailability {
                has_from_english: true,
                has_to_english: true,
                ocr_files: ocr_pack_id.is_some_and(|pack_id| resolver.is_installed(pack_id)),
                dictionary_files: dictionary_pack_id
                    .as_deref()
                    .is_some_and(|pack_id| resolver.is_installed(pack_id)),
                tts_files: installed_tts_pack_id_for_language(catalog, code, resolver).is_some(),
            }
        } else {
            let from_pack_id = catalog.translation_pack_id("en", code);
            let to_pack_id = catalog.translation_pack_id(code, "en");
            LangAvailability {
                has_from_english: from_pack_id
                    .as_deref()
                    .is_some_and(|pack_id| resolver.is_installed(pack_id)),
                has_to_english: to_pack_id
                    .as_deref()
                    .is_some_and(|pack_id| resolver.is_installed(pack_id)),
                ocr_files: ocr_pack_id.is_some_and(|pack_id| resolver.is_installed(pack_id)),
                dictionary_files: dictionary_pack_id
                    .as_deref()
                    .is_some_and(|pack_id| resolver.is_installed(pack_id)),
                tts_files: installed_tts_pack_id_for_language(catalog, code, resolver).is_some(),
            }
        };
        rows.insert(language, availability);
    }

    rows
}

pub fn resolve_tts_voice_files<C>(
    catalog: &LanguageCatalog,
    language_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> Option<ResolvedTtsVoiceFiles>
where
    C: PackInstallChecker,
{
    let voice_pack_id = installed_tts_pack_id_for_language(catalog, language_code, resolver)?;
    let voice_pack = catalog.pack(&voice_pack_id)?;
    let PackKind::Tts(tts) = &voice_pack.kind else {
        return None;
    };
    let pack_files = catalog.pack_files_with_dependencies(&voice_pack_id);
    let model_asset = pack_files
        .iter()
        .find(|file| file.name.ends_with(".onnx") && !file.name.ends_with(".onnx.json"))?;
    let engine = tts.engine.clone().unwrap_or_else(|| "piper".to_string());
    let aux_asset = match engine.as_str() {
        "kokoro" => pack_files.iter().find(|file| file.name.ends_with(".bin")),
        "mms" => pack_files
            .iter()
            .find(|file| file.name.ends_with("tokens.txt")),
        "coqui_vits" | "sherpa_vits" => pack_files.iter().find(|file| file.name == "config.json"),
        _ => pack_files
            .iter()
            .find(|file| file.name.ends_with(".onnx.json")),
    }?;
    Some(ResolvedTtsVoiceFiles {
        engine,
        model_install_path: model_asset.install_path.clone(),
        aux_install_path: aux_asset.install_path.clone(),
        language_code: language_code.to_string(),
        speaker_id: tts.default_speaker_id,
    })
}

pub fn resolve_tts_voice_files_in_snapshot(
    snapshot: &CatalogSnapshot,
    language_code: &str,
) -> Option<ResolvedTtsVoiceFiles> {
    let voice_pack_id = snapshot
        .catalog
        .tts_pack_ids_for_language(language_code)
        .into_iter()
        .find(|pack_id| pack_installed_in_snapshot(snapshot, pack_id))?;
    let voice_pack = snapshot.catalog.pack(&voice_pack_id)?;
    let PackKind::Tts(tts) = &voice_pack.kind else {
        return None;
    };
    let pack_files = snapshot
        .catalog
        .pack_files_with_dependencies(&voice_pack_id);
    let model_asset = pack_files
        .iter()
        .find(|file| file.name.ends_with(".onnx") && !file.name.ends_with(".onnx.json"))?;
    let engine = tts.engine.clone().unwrap_or_else(|| "piper".to_string());
    let aux_asset = match engine.as_str() {
        "kokoro" => pack_files.iter().find(|file| file.name.ends_with(".bin")),
        "mms" => pack_files
            .iter()
            .find(|file| file.name.ends_with("tokens.txt")),
        "coqui_vits" | "sherpa_vits" => pack_files.iter().find(|file| file.name == "config.json"),
        _ => pack_files
            .iter()
            .find(|file| file.name.ends_with(".onnx.json")),
    }?;
    Some(ResolvedTtsVoiceFiles {
        engine,
        model_install_path: model_asset.install_path.clone(),
        aux_install_path: aux_asset.install_path.clone(),
        language_code: language_code.to_string(),
        speaker_id: tts.default_speaker_id,
    })
}

fn download_task_for(pack: &PackRecord, file: &AssetFileV2) -> DownloadTask {
    DownloadTask {
        pack_id: pack.id.clone(),
        install_path: file.install_path.clone(),
        url: file.url.clone(),
        size_bytes: file.size_bytes,
        decompress: matches!(&pack.kind, PackKind::Translation(_))
            && file
                .source_path
                .as_deref()
                .unwrap_or(file.url.as_str())
                .ends_with(".gz"),
        archive_format: file.archive_format.clone(),
        extract_to: file.extract_to.clone(),
        delete_after_extract: file.delete_after_extract,
        install_marker_path: file.install_marker_path.clone(),
        install_marker_version: file.install_marker_version,
    }
}

fn download_plan_for_root_packs<C, I>(
    catalog: &LanguageCatalog,
    root_pack_ids: I,
    resolver: &mut PackResolver<'_, C>,
) -> DownloadPlan
where
    C: PackInstallChecker,
    I: IntoIterator,
    I::Item: AsRef<str>,
{
    let root_pack_ids = root_pack_ids
        .into_iter()
        .map(|id| id.as_ref().to_string())
        .collect::<Vec<_>>();
    let tasks = resolver
        .missing_files(root_pack_ids.iter().map(String::as_str))
        .into_iter()
        .filter_map(|item| {
            let pack = catalog.pack(&item.pack_id)?;
            Some(download_task_for(pack, &item.file))
        })
        .collect::<Vec<_>>();
    DownloadPlan {
        total_size: tasks.iter().map(|task| task.size_bytes).sum(),
        tasks,
    }
}

pub fn plan_language_download<C>(
    catalog: &LanguageCatalog,
    language_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> DownloadPlan
where
    C: PackInstallChecker,
{
    download_plan_for_root_packs(
        catalog,
        catalog.core_pack_ids_for_language(language_code),
        resolver,
    )
}

pub fn plan_language_download_in_snapshot(
    snapshot: &CatalogSnapshot,
    language_code: &str,
) -> DownloadPlan {
    let root_pack_ids = snapshot.catalog.core_pack_ids_for_language(language_code);
    let tasks = missing_files_in_snapshot(snapshot, root_pack_ids.iter().map(String::as_str))
        .into_iter()
        .filter_map(|item| {
            let pack = snapshot.catalog.pack(&item.pack_id)?;
            Some(download_task_for(pack, &item.file))
        })
        .collect::<Vec<_>>();
    DownloadPlan {
        total_size: tasks.iter().map(|task| task.size_bytes).sum(),
        tasks,
    }
}

pub fn plan_dictionary_download<C>(
    catalog: &LanguageCatalog,
    language_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> Option<DownloadPlan>
where
    C: PackInstallChecker,
{
    let pack_id = catalog.dictionary_pack_id_for_language(language_code)?;
    Some(download_plan_for_root_packs(catalog, [pack_id], resolver))
}

pub fn plan_dictionary_download_in_snapshot(
    snapshot: &CatalogSnapshot,
    language_code: &str,
) -> Option<DownloadPlan> {
    let pack_id = snapshot
        .catalog
        .dictionary_pack_id_for_language(language_code)?;
    let tasks = missing_files_in_snapshot(snapshot, [pack_id.as_str()])
        .into_iter()
        .filter_map(|item| {
            let pack = snapshot.catalog.pack(&item.pack_id)?;
            Some(download_task_for(pack, &item.file))
        })
        .collect::<Vec<_>>();
    Some(DownloadPlan {
        total_size: tasks.iter().map(|task| task.size_bytes).sum(),
        tasks,
    })
}

pub fn plan_tts_download<C>(
    catalog: &LanguageCatalog,
    language_code: &str,
    selected_pack_id: Option<&str>,
    resolver: &mut PackResolver<'_, C>,
) -> Option<DownloadPlan>
where
    C: PackInstallChecker,
{
    let selected_pack_id = match selected_pack_id {
        Some(pack_id)
            if catalog
                .tts_pack_ids_for_language(language_code)
                .iter()
                .any(|candidate| candidate == pack_id) =>
        {
            pack_id.to_string()
        }
        Some(_) => return None,
        None => catalog.default_tts_pack_id_for_language(language_code)?,
    };
    Some(download_plan_for_root_packs(
        catalog,
        [selected_pack_id],
        resolver,
    ))
}

pub fn plan_tts_download_in_snapshot(
    snapshot: &CatalogSnapshot,
    language_code: &str,
    selected_pack_id: Option<&str>,
) -> Option<DownloadPlan> {
    let selected_pack_id = match selected_pack_id {
        Some(pack_id)
            if snapshot
                .catalog
                .tts_pack_ids_for_language(language_code)
                .iter()
                .any(|candidate| candidate == pack_id) =>
        {
            pack_id.to_string()
        }
        Some(_) => return None,
        None => snapshot
            .catalog
            .default_tts_pack_id_for_language(language_code)?,
    };
    let tasks = missing_files_in_snapshot(snapshot, [selected_pack_id.as_str()])
        .into_iter()
        .filter_map(|item| {
            let pack = snapshot.catalog.pack(&item.pack_id)?;
            Some(download_task_for(pack, &item.file))
        })
        .collect::<Vec<_>>();
    Some(DownloadPlan {
        total_size: tasks.iter().map(|task| task.size_bytes).sum(),
        tasks,
    })
}

fn delete_plan_for_pack_ids<'a, I>(catalog: &LanguageCatalog, pack_ids: I) -> DeletePlan
where
    I: IntoIterator<Item = &'a str>,
{
    let mut file_paths = Vec::new();
    let mut file_seen = HashSet::new();
    let mut directory_paths = Vec::new();
    let mut directory_seen = HashSet::new();

    for pack_id in pack_ids {
        let Some(pack) = catalog.pack(pack_id) else {
            continue;
        };
        for file in &pack.files {
            if file_seen.insert(file.install_path.clone()) {
                file_paths.push(file.install_path.clone());
            }
            if file.archive_format.as_deref() == Some("zip")
                && let Some(marker_path) = file.install_marker_path.as_deref()
                && let Some(parent) = std::path::Path::new(marker_path).parent()
            {
                let path = parent.to_string_lossy().to_string();
                if !path.is_empty() && directory_seen.insert(path.clone()) {
                    directory_paths.push(path);
                }
            }
        }
    }

    DeletePlan {
        file_paths,
        directory_paths,
    }
}

fn delete_pack_ids<'a, I>(
    catalog: &LanguageCatalog,
    target_root_packs: I,
    keep_root_packs: HashSet<String>,
) -> HashSet<String>
where
    I: IntoIterator<Item = &'a str>,
{
    let target = catalog
        .dependency_closure(target_root_packs)
        .into_iter()
        .collect::<HashSet<_>>();
    let keep = catalog
        .dependency_closure(keep_root_packs.iter().map(String::as_str))
        .into_iter()
        .collect::<HashSet<_>>();
    target.difference(&keep).cloned().collect()
}

pub fn plan_delete_dictionary<C>(
    catalog: &LanguageCatalog,
    language_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> DeletePlan
where
    C: PackInstallChecker,
{
    let Some(target_pack) = catalog.dictionary_pack_id_for_language(language_code) else {
        return DeletePlan::default();
    };
    let keep_root_packs = catalog
        .languages
        .keys()
        .filter(|code| code.as_str() != language_code)
        .filter_map(|code| catalog.dictionary_pack_id_for_language(code))
        .filter(|pack_id| pack_id != &target_pack && resolver.is_installed(pack_id))
        .collect::<HashSet<_>>();
    let delete_pack_ids = delete_pack_ids(catalog, [target_pack.as_str()], keep_root_packs);
    delete_plan_for_pack_ids(catalog, delete_pack_ids.iter().map(String::as_str))
}

pub fn plan_delete_dictionary_in_snapshot(
    snapshot: &CatalogSnapshot,
    language_code: &str,
) -> DeletePlan {
    let Some(target_pack) = snapshot
        .catalog
        .dictionary_pack_id_for_language(language_code)
    else {
        return DeletePlan::default();
    };
    let keep_root_packs = snapshot
        .catalog
        .languages
        .keys()
        .filter(|code| code.as_str() != language_code)
        .filter_map(|code| snapshot.catalog.dictionary_pack_id_for_language(code))
        .filter(|pack_id| pack_id != &target_pack && pack_installed_in_snapshot(snapshot, pack_id))
        .collect::<HashSet<_>>();
    let delete_pack_ids =
        delete_pack_ids(&snapshot.catalog, [target_pack.as_str()], keep_root_packs);
    delete_plan_for_pack_ids(
        &snapshot.catalog,
        delete_pack_ids.iter().map(String::as_str),
    )
}

pub fn plan_delete_language<C>(
    catalog: &LanguageCatalog,
    language_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> DeletePlan
where
    C: PackInstallChecker,
{
    let target_root_packs = catalog.core_pack_ids_for_language(language_code);
    let keep_root_packs = catalog
        .languages
        .keys()
        .filter(|code| code.as_str() != language_code)
        .flat_map(|code| catalog.core_pack_ids_for_language(code))
        .filter(|pack_id| resolver.is_installed(pack_id))
        .collect::<HashSet<_>>();
    let delete_pack_ids = delete_pack_ids(
        catalog,
        target_root_packs.iter().map(String::as_str),
        keep_root_packs,
    );
    delete_plan_for_pack_ids(catalog, delete_pack_ids.iter().map(String::as_str))
}

pub fn plan_delete_language_in_snapshot(
    snapshot: &CatalogSnapshot,
    language_code: &str,
) -> DeletePlan {
    let target_root_packs = snapshot.catalog.core_pack_ids_for_language(language_code);
    let keep_root_packs = snapshot
        .catalog
        .languages
        .keys()
        .filter(|code| code.as_str() != language_code)
        .flat_map(|code| snapshot.catalog.core_pack_ids_for_language(code))
        .filter(|pack_id| pack_installed_in_snapshot(snapshot, pack_id))
        .collect::<HashSet<_>>();
    let delete_pack_ids = delete_pack_ids(
        &snapshot.catalog,
        target_root_packs.iter().map(String::as_str),
        keep_root_packs,
    );
    delete_plan_for_pack_ids(
        &snapshot.catalog,
        delete_pack_ids.iter().map(String::as_str),
    )
}

pub fn plan_delete_tts<C>(
    catalog: &LanguageCatalog,
    language_code: &str,
    resolver: &mut PackResolver<'_, C>,
) -> DeletePlan
where
    C: PackInstallChecker,
{
    let target_root_packs = catalog
        .tts_pack_ids_for_language(language_code)
        .into_iter()
        .filter(|pack_id| resolver.is_installed(pack_id))
        .collect::<HashSet<_>>();
    if target_root_packs.is_empty() {
        return DeletePlan::default();
    }
    let keep_root_packs = catalog
        .languages
        .keys()
        .filter(|code| code.as_str() != language_code)
        .flat_map(|code| catalog.tts_pack_ids_for_language(code))
        .filter(|pack_id| resolver.is_installed(pack_id))
        .collect::<HashSet<_>>();
    let delete_pack_ids = delete_pack_ids(
        catalog,
        target_root_packs.iter().map(String::as_str),
        keep_root_packs,
    );
    delete_plan_for_pack_ids(catalog, delete_pack_ids.iter().map(String::as_str))
}

pub fn plan_delete_tts_in_snapshot(snapshot: &CatalogSnapshot, language_code: &str) -> DeletePlan {
    let target_root_packs = snapshot
        .catalog
        .tts_pack_ids_for_language(language_code)
        .into_iter()
        .filter(|pack_id| pack_installed_in_snapshot(snapshot, pack_id))
        .collect::<HashSet<_>>();
    if target_root_packs.is_empty() {
        return DeletePlan::default();
    }
    let keep_root_packs = snapshot
        .catalog
        .languages
        .keys()
        .filter(|code| code.as_str() != language_code)
        .flat_map(|code| snapshot.catalog.tts_pack_ids_for_language(code))
        .filter(|pack_id| pack_installed_in_snapshot(snapshot, pack_id))
        .collect::<HashSet<_>>();
    let delete_pack_ids = delete_pack_ids(
        &snapshot.catalog,
        target_root_packs.iter().map(String::as_str),
        keep_root_packs,
    );
    delete_plan_for_pack_ids(
        &snapshot.catalog,
        delete_pack_ids.iter().map(String::as_str),
    )
}

pub fn plan_delete_superseded_tts<C>(
    catalog: &LanguageCatalog,
    language_code: &str,
    selected_pack_id: &str,
    resolver: &mut PackResolver<'_, C>,
) -> DeletePlan
where
    C: PackInstallChecker,
{
    let installed_language_packs = catalog
        .tts_pack_ids_for_language(language_code)
        .into_iter()
        .filter(|pack_id| resolver.is_installed(pack_id))
        .collect::<HashSet<_>>();
    let superseded_root_packs = catalog
        .tts_pack_ids_for_language(language_code)
        .into_iter()
        .filter(|pack_id| pack_id != selected_pack_id && installed_language_packs.contains(pack_id))
        .collect::<HashSet<_>>();
    if superseded_root_packs.is_empty() {
        return DeletePlan::default();
    }
    let mut keep_root_packs = HashSet::new();
    if resolver.is_installed(selected_pack_id) {
        keep_root_packs.insert(selected_pack_id.to_string());
    }
    keep_root_packs.extend(
        catalog
            .languages
            .keys()
            .filter(|code| code.as_str() != language_code)
            .flat_map(|code| catalog.tts_pack_ids_for_language(code))
            .filter(|pack_id| resolver.is_installed(pack_id)),
    );
    let delete_pack_ids = delete_pack_ids(
        catalog,
        superseded_root_packs.iter().map(String::as_str),
        keep_root_packs,
    );
    delete_plan_for_pack_ids(catalog, delete_pack_ids.iter().map(String::as_str))
}

pub fn plan_delete_superseded_tts_in_snapshot(
    snapshot: &CatalogSnapshot,
    language_code: &str,
    selected_pack_id: &str,
) -> DeletePlan {
    let installed_language_packs = snapshot
        .catalog
        .tts_pack_ids_for_language(language_code)
        .into_iter()
        .filter(|pack_id| pack_installed_in_snapshot(snapshot, pack_id))
        .collect::<HashSet<_>>();
    let superseded_root_packs = snapshot
        .catalog
        .tts_pack_ids_for_language(language_code)
        .into_iter()
        .filter(|pack_id| pack_id != selected_pack_id && installed_language_packs.contains(pack_id))
        .collect::<HashSet<_>>();
    if superseded_root_packs.is_empty() {
        return DeletePlan::default();
    }
    let mut keep_root_packs = HashSet::new();
    if pack_installed_in_snapshot(snapshot, selected_pack_id) {
        keep_root_packs.insert(selected_pack_id.to_string());
    }
    keep_root_packs.extend(
        snapshot
            .catalog
            .languages
            .keys()
            .filter(|code| code.as_str() != language_code)
            .flat_map(|code| snapshot.catalog.tts_pack_ids_for_language(code))
            .filter(|pack_id| pack_installed_in_snapshot(snapshot, pack_id)),
    );
    let delete_pack_ids = delete_pack_ids(
        &snapshot.catalog,
        superseded_root_packs.iter().map(String::as_str),
        keep_root_packs,
    );
    delete_plan_for_pack_ids(
        &snapshot.catalog,
        delete_pack_ids.iter().map(String::as_str),
    )
}
