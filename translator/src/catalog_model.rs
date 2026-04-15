use std::collections::{HashMap, HashSet};

use crate::language::{Language, LanguageDirection, ModelFile};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CatalogSourcesV2 {
    pub language_index_version: i32,
    pub language_index_updated_at: i64,
    pub dictionary_index_version: i32,
    pub dictionary_index_updated_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct LanguageTtsRegionV2 {
    pub display_name: String,
    pub voices: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct LanguageTtsV2 {
    pub default_region: Option<String>,
    pub regions: Vec<(String, LanguageTtsRegionV2)>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub(crate) struct LanguageResources {
    pub translation_root_packs: Vec<String>,
    pub ocr_packs: Vec<(String, String)>,
    pub dictionary_pack_id: Option<String>,
    pub support_root_packs: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct LanguageInfo {
    pub language: Language,
    pub resources: LanguageResources,
    pub tts: Option<LanguageTtsV2>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AssetFileV2 {
    pub name: String,
    pub size_bytes: u64,
    pub install_path: String,
    pub url: String,
    pub source_path: Option<String>,
    pub archive_format: Option<String>,
    pub extract_to: Option<String>,
    pub delete_after_extract: bool,
    pub install_marker_path: Option<String>,
    pub install_marker_version: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct AssetPackMetadataV2 {
    pub date: Option<i64>,
    pub type_name: Option<String>,
    pub word_count: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackRecord {
    pub id: String,
    pub files: Vec<AssetFileV2>,
    pub depends_on: Vec<String>,
    pub kind: PackKind,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PackKind {
    Translation(TranslationPack),
    Ocr(OcrPack),
    Tts(TtsPack),
    Dictionary(DictionaryPack),
    Support(SupportPack),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranslationPack {
    pub from: String,
    pub to: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OcrPack {
    pub language: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TtsPack {
    pub language: String,
    pub engine: Option<String>,
    pub locale: Option<String>,
    pub region: Option<String>,
    pub voice: Option<String>,
    pub quality: Option<String>,
    pub num_speakers: Option<i32>,
    pub default_speaker_id: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DictionaryPack {
    pub language: Option<String>,
    pub dictionary_code: String,
    pub metadata: Option<AssetPackMetadataV2>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SupportPack {
    pub language: Option<String>,
    pub languages: Vec<String>,
    pub aliases: Vec<String>,
    pub kind: Option<String>,
    pub metadata: Option<AssetPackMetadataV2>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct DictionaryInfo {
    pub date: i64,
    pub filename: String,
    pub size: u64,
    pub type_name: String,
    pub word_count: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum LanguageFeature {
    Translation,
    Ocr,
    Dictionary,
    Support,
    Tts,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct TtsVoicePackInfo {
    pub pack_id: String,
    pub display_name: String,
    pub quality: Option<String>,
    pub size_bytes: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct TtsVoicePickerRegion {
    pub code: String,
    pub display_name: String,
    pub voices: Vec<TtsVoicePackInfo>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedTtsVoiceFiles {
    pub engine: String,
    pub model_install_path: String,
    pub aux_install_path: String,
    pub language_code: String,
    pub speaker_id: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LanguageCatalog {
    pub format_version: i32,
    pub generated_at: i64,
    pub dictionary_version: i32,
    pub sources: CatalogSourcesV2,
    pub(crate) languages: HashMap<String, LanguageInfo>,
    pub(crate) packs: HashMap<String, PackRecord>,
    pub(crate) translation_pack_ids: HashMap<(String, String), String>,
    pub(crate) dictionary_pack_ids_by_code: HashMap<String, String>,
    pub(crate) root_pack_ids_by_language_feature: HashMap<(String, LanguageFeature), Vec<String>>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct DownloadTask {
    pub pack_id: String,
    pub install_path: String,
    pub url: String,
    pub size_bytes: u64,
    pub decompress: bool,
    pub archive_format: Option<String>,
    pub extract_to: Option<String>,
    pub delete_after_extract: bool,
    pub install_marker_path: Option<String>,
    pub install_marker_version: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct DownloadPlan {
    pub total_size: u64,
    pub tasks: Vec<DownloadTask>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct DeletePlan {
    pub file_paths: Vec<String>,
    pub directory_paths: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct LangAvailability {
    pub has_from_english: bool,
    pub has_to_english: bool,
    pub ocr_files: bool,
    pub dictionary_files: bool,
    pub tts_files: bool,
}

impl LangAvailability {
    pub fn translator_files(&self) -> bool {
        self.has_from_english || self.has_to_english
    }
}

fn translation_direction_from_pack(pack: &PackRecord) -> Option<LanguageDirection> {
    let PackKind::Translation(_) = &pack.kind else {
        return None;
    };

    let by_name = pack
        .files
        .iter()
        .map(|file| (file.name.clone(), file))
        .collect::<HashMap<_, _>>();
    let model = by_name
        .values()
        .find(|file| file.name.starts_with("model."))?;
    let lex = by_name
        .values()
        .find(|file| file.name.starts_with("lex."))?;
    let mut vocab_files = by_name
        .values()
        .filter(|file| file.name.contains("vocab"))
        .copied()
        .collect::<Vec<_>>();
    vocab_files.sort_by(|left, right| left.name.cmp(&right.name));
    let src_vocab = vocab_files.first()?;
    let tgt_vocab = vocab_files.get(1).copied().unwrap_or(src_vocab);

    let to_model_file = |file: &AssetFileV2| ModelFile {
        name: file.name.clone(),
        size_bytes: file.size_bytes,
        path: file.install_path.clone(),
    };

    Some(LanguageDirection {
        model: to_model_file(model),
        src_vocab: to_model_file(src_vocab),
        tgt_vocab: to_model_file(tgt_vocab),
        lex: to_model_file(lex),
    })
}

impl LanguageCatalog {
    pub(crate) fn language_info(&self, code: &str) -> Option<&LanguageInfo> {
        self.languages.get(code)
    }

    pub fn pack(&self, pack_id: &str) -> Option<&PackRecord> {
        self.packs.get(pack_id)
    }

    pub fn language_list(&self) -> Vec<Language> {
        let mut codes = self.languages.keys().cloned().collect::<Vec<_>>();
        codes.sort();
        codes
            .into_iter()
            .filter_map(|code| self.languages.get(&code).map(|info| info.language.clone()))
            .collect()
    }

    pub fn language_by_code(&self, code: &str) -> Option<Language> {
        self.languages.get(code).map(|info| info.language.clone())
    }

    pub fn english(&self) -> Option<Language> {
        self.language_by_code("en")
    }

    pub fn dictionary_info_for(&self, language: &Language) -> Option<DictionaryInfo> {
        self.dictionary_info(&language.dictionary_code)
    }

    pub fn dictionary_info(&self, dictionary_code: &str) -> Option<DictionaryInfo> {
        let pack_id = self.dictionary_pack_ids_by_code.get(dictionary_code)?;
        let pack = self.packs.get(pack_id)?;
        let file = pack.files.first()?;
        let metadata = match &pack.kind {
            PackKind::Dictionary(dictionary) => dictionary.metadata.as_ref(),
            PackKind::Support(support) => support.metadata.as_ref(),
            _ => None,
        };
        Some(DictionaryInfo {
            date: metadata.and_then(|m| m.date).unwrap_or(0),
            filename: file.name.clone(),
            size: file.size_bytes,
            type_name: metadata
                .and_then(|m| m.type_name.clone())
                .unwrap_or_default(),
            word_count: metadata.and_then(|m| m.word_count).unwrap_or(0),
        })
    }

    pub(crate) fn tts(&self, language_code: &str) -> Option<&LanguageTtsV2> {
        self.language_info(language_code)?.tts.as_ref()
    }

    pub fn tts_pack_ids_for_language(&self, language_code: &str) -> Vec<String> {
        let Some(tts) = self.tts(language_code) else {
            return Vec::new();
        };
        tts_pack_ids_from_config(tts)
    }

    pub fn ordered_tts_regions_for_language(
        &self,
        language_code: &str,
    ) -> Vec<(String, LanguageTtsRegionV2)> {
        let Some(tts) = self.tts(language_code) else {
            return Vec::new();
        };

        let mut ordered_codes = Vec::new();
        if let Some(default_region) = &tts.default_region {
            if tts.regions.iter().any(|(code, _)| code == default_region) {
                ordered_codes.push(default_region.clone());
            }
        }

        let mut remaining_codes = tts
            .regions
            .iter()
            .map(|(code, _)| code.clone())
            .filter(|code| Some(code) != tts.default_region.as_ref())
            .collect::<Vec<_>>();
        remaining_codes.sort();
        ordered_codes.extend(remaining_codes);

        ordered_codes
            .into_iter()
            .filter_map(|region_code| {
                tts.regions
                    .iter()
                    .find(|(code, _)| code == &region_code)
                    .map(|(_, region)| (region_code, region.clone()))
            })
            .collect()
    }

    pub fn default_tts_pack_id_for_language(&self, language_code: &str) -> Option<String> {
        let tts = self.tts(language_code)?;
        let region = tts
            .default_region
            .as_ref()
            .and_then(|code| {
                tts.regions
                    .iter()
                    .find(|(region_code, _)| region_code == code)
            })
            .map(|(_, region)| region)
            .or_else(|| tts.regions.first().map(|(_, region)| region))?;
        region.voices.first().cloned()
    }

    pub fn tts_size_bytes_for_language(&self, language_code: &str) -> u64 {
        self.default_tts_pack_id_for_language(language_code)
            .map(|pack_id| self.pack_size_bytes(&pack_id))
            .unwrap_or(0)
    }

    pub fn has_tts_voices(&self, language_code: &str) -> bool {
        !self.root_pack_ids_for_feature(language_code, LanguageFeature::Tts)
            .is_empty()
    }

    pub fn translation_size_bytes_for_language(&self, language_code: &str) -> u64 {
        self.root_pack_ids_for_feature(language_code, LanguageFeature::Translation)
            .iter()
            .map(|pack_id| self.pack_size_bytes(pack_id))
            .sum()
    }

    pub fn tts_voice_pack_info(&self, pack_id: &str) -> Option<TtsVoicePackInfo> {
        let pack = self.pack(pack_id)?;
        let PackKind::Tts(tts) = &pack.kind else {
            return None;
        };
        Some(TtsVoicePackInfo {
            pack_id: pack.id.clone(),
            display_name: tts.voice.clone().unwrap_or_else(|| pack.id.clone()),
            quality: tts.quality.clone(),
            size_bytes: self.pack_size_bytes(pack_id),
        })
    }

    pub fn tts_voice_picker_regions(&self, language_code: &str) -> Vec<TtsVoicePickerRegion> {
        self.ordered_tts_regions_for_language(language_code)
            .into_iter()
            .map(|(code, region)| TtsVoicePickerRegion {
                code,
                display_name: region.display_name,
                voices: region
                    .voices
                    .into_iter()
                    .filter_map(|pack_id| self.tts_voice_pack_info(&pack_id))
                    .collect(),
            })
            .filter(|region| !region.voices.is_empty())
            .collect()
    }

    pub fn pack_size_bytes(&self, pack_id: &str) -> u64 {
        self.unique_files_in_dependency_closure([pack_id])
            .into_iter()
            .map(|file| file.size_bytes)
            .sum()
    }

    pub(crate) fn translation_pack_id(&self, from: &str, to: &str) -> Option<String> {
        self.translation_pack_ids
            .get(&(from.to_string(), to.to_string()))
            .cloned()
    }

    pub fn can_swap_languages(&self, from_code: &str, to_code: &str) -> bool {
        let to_can_be_source = to_code == "en" || self.translation_pack_id(to_code, "en").is_some();
        let from_can_be_target =
            from_code == "en" || self.translation_pack_id("en", from_code).is_some();
        to_can_be_source && from_can_be_target
    }

    pub fn translation_direction(&self, from: &str, to: &str) -> Option<LanguageDirection> {
        let pack_id = self.translation_pack_id(from, to)?;
        let pack = self.pack(&pack_id)?;
        translation_direction_from_pack(pack)
    }

    pub(crate) fn root_pack_ids_for_feature(
        &self,
        language_code: &str,
        feature: LanguageFeature,
    ) -> &[String] {
        self.root_pack_ids_by_language_feature
            .get(&(language_code.to_string(), feature))
            .map(Vec::as_slice)
            .unwrap_or(&[])
    }

    pub(crate) fn core_pack_ids_for_language(&self, language_code: &str) -> HashSet<String> {
        let mut pack_ids = HashSet::new();
        if language_code != "en" {
            pack_ids.extend(
                self.root_pack_ids_for_feature(language_code, LanguageFeature::Translation)
                    .iter()
                    .cloned(),
            );
        }
        pack_ids.extend(
            self.root_pack_ids_for_feature(language_code, LanguageFeature::Ocr)
                .iter()
                .cloned(),
        );
        pack_ids.extend(
            self.root_pack_ids_for_feature(language_code, LanguageFeature::Support)
                .iter()
                .cloned(),
        );
        pack_ids
    }

    pub(crate) fn dictionary_pack_id_for_language(&self, language_code: &str) -> Option<String> {
        self.root_pack_ids_for_feature(language_code, LanguageFeature::Dictionary)
            .first()
            .cloned()
    }

    pub(crate) fn dependency_closure<'a, I>(&self, root_pack_ids: I) -> Vec<String>
    where
        I: IntoIterator<Item = &'a str>,
    {
        let mut resolved = Vec::new();
        let mut seen = HashSet::new();

        fn visit(
            catalog: &LanguageCatalog,
            pack_id: &str,
            resolved: &mut Vec<String>,
            seen: &mut HashSet<String>,
        ) {
            if !seen.insert(pack_id.to_string()) {
                return;
            }
            resolved.push(pack_id.to_string());
            if let Some(pack) = catalog.packs.get(pack_id) {
                for dep_id in &pack.depends_on {
                    visit(catalog, dep_id, resolved, seen);
                }
            }
        }

        for pack_id in root_pack_ids {
            visit(self, pack_id, &mut resolved, &mut seen);
        }
        resolved
    }

    pub fn pack_files_with_dependencies(&self, root_pack_id: &str) -> Vec<&AssetFileV2> {
        self.dependency_closure([root_pack_id])
            .into_iter()
            .filter_map(|pack_id| self.packs.get(&pack_id))
            .flat_map(|pack| pack.files.iter())
            .collect()
    }

    pub(crate) fn unique_files_in_dependency_closure<'a, I>(
        &'a self,
        root_pack_ids: I,
    ) -> Vec<&'a AssetFileV2>
    where
        I: IntoIterator<Item = &'a str>,
    {
        let mut seen_install_paths = HashSet::new();
        let mut files = Vec::new();
        for pack_id in self.dependency_closure(root_pack_ids) {
            let Some(pack) = self.packs.get(&pack_id) else {
                continue;
            };
            for file in &pack.files {
                if seen_install_paths.insert(file.install_path.clone()) {
                    files.push(file);
                }
            }
        }
        files
    }
}

pub(crate) fn tts_pack_ids_from_config(tts: &LanguageTtsV2) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut pack_ids = Vec::new();
    for (_, region) in &tts.regions {
        for voice in &region.voices {
            if seen.insert(voice.clone()) {
                pack_ids.push(voice.clone());
            }
        }
    }
    pack_ids
}
