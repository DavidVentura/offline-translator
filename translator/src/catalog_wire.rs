use std::collections::HashMap;

use serde::Deserialize;

use crate::language::Language;

use super::model::{
    AssetFileV2, AssetPackMetadataV2, CatalogSourcesV2, DictionaryPack, LanguageCatalog,
    LanguageFeature, LanguageInfo, LanguageResources, LanguageTtsRegionV2, LanguageTtsV2, OcrPack,
    PackKind, PackRecord, SupportPack, TranslationPack, TtsPack,
};

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CatalogSourcesWire {
    language_index_version: i32,
    language_index_updated_at: i64,
    dictionary_index_version: i32,
    dictionary_index_updated_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
struct LanguageMetaWire {
    code: String,
    name: String,
    #[serde(rename = "shortName")]
    short_name: String,
    script: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Deserialize)]
struct LanguageAssetsWire {
    #[serde(default)]
    translate: Vec<String>,
    #[serde(default)]
    ocr: HashMap<String, String>,
    dictionary: Option<String>,
    #[serde(default)]
    support: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LanguageTtsRegionWire {
    #[serde(default)]
    display_name: Option<String>,
    #[serde(default)]
    voices: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LanguageTtsWire {
    default_region: Option<String>,
    #[serde(default)]
    regions: HashMap<String, LanguageTtsRegionWire>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
struct LanguageEntryWire {
    meta: LanguageMetaWire,
    #[serde(default)]
    assets: LanguageAssetsWire,
    #[serde(default)]
    tts: Option<LanguageTtsWire>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AssetFileWire {
    name: String,
    size_bytes: u64,
    install_path: String,
    url: String,
    source_path: Option<String>,
    archive_format: Option<String>,
    extract_to: Option<String>,
    #[serde(default)]
    delete_after_extract: bool,
    install_marker_path: Option<String>,
    install_marker_version: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AssetPackMetadataWire {
    date: Option<i64>,
    #[serde(rename = "type")]
    type_name: Option<String>,
    word_count: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PackCommonWire {
    files: Vec<AssetFileWire>,
    #[serde(default)]
    depends_on: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", tag = "feature")]
enum AssetPackWire {
    #[serde(rename = "translation")]
    Translation {
        from: String,
        to: String,
        #[serde(flatten)]
        common: PackCommonWire,
    },
    #[serde(rename = "ocr")]
    Ocr {
        language: String,
        #[serde(flatten)]
        common: PackCommonWire,
    },
    #[serde(rename = "tts")]
    Tts {
        language: String,
        engine: Option<String>,
        locale: Option<String>,
        region: Option<String>,
        voice: Option<String>,
        quality: Option<String>,
        #[serde(rename = "numSpeakers")]
        num_speakers: Option<i32>,
        #[serde(rename = "defaultSpeakerId")]
        default_speaker_id: Option<i32>,
        #[serde(flatten)]
        common: PackCommonWire,
    },
    #[serde(rename = "dictionary")]
    Dictionary {
        language: Option<String>,
        #[serde(rename = "dictionaryCode")]
        dictionary_code: String,
        metadata: Option<AssetPackMetadataWire>,
        #[serde(flatten)]
        common: PackCommonWire,
    },
    #[serde(rename = "support")]
    Support {
        language: Option<String>,
        #[serde(default)]
        languages: Vec<String>,
        #[serde(default)]
        aliases: Vec<String>,
        kind: Option<String>,
        metadata: Option<AssetPackMetadataWire>,
        #[serde(flatten)]
        common: PackCommonWire,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LanguageCatalogWire {
    format_version: i32,
    generated_at: i64,
    dictionary_version: i32,
    sources: CatalogSourcesWire,
    languages: HashMap<String, LanguageEntryWire>,
    packs: HashMap<String, AssetPackWire>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CatalogHeaderWire {
    format_version: i32,
    generated_at: i64,
}

impl From<CatalogSourcesWire> for CatalogSourcesV2 {
    fn from(value: CatalogSourcesWire) -> Self {
        Self {
            language_index_version: value.language_index_version,
            language_index_updated_at: value.language_index_updated_at,
            dictionary_index_version: value.dictionary_index_version,
            dictionary_index_updated_at: value.dictionary_index_updated_at,
        }
    }
}

impl From<AssetFileWire> for AssetFileV2 {
    fn from(value: AssetFileWire) -> Self {
        Self {
            name: value.name,
            size_bytes: value.size_bytes,
            install_path: value.install_path,
            url: value.url,
            source_path: value.source_path.filter(|value| !value.is_empty()),
            archive_format: value.archive_format.filter(|value| !value.is_empty()),
            extract_to: value.extract_to.filter(|value| !value.is_empty()),
            delete_after_extract: value.delete_after_extract,
            install_marker_path: value.install_marker_path.filter(|value| !value.is_empty()),
            install_marker_version: value.install_marker_version,
        }
    }
}

impl From<AssetPackMetadataWire> for AssetPackMetadataV2 {
    fn from(value: AssetPackMetadataWire) -> Self {
        Self {
            date: value.date,
            type_name: value.type_name.filter(|value| !value.is_empty()),
            word_count: value.word_count,
        }
    }
}

fn normalized(value: Option<String>) -> Option<String> {
    value.filter(|value| !value.is_empty())
}

impl AssetPackWire {
    fn into_record(self, id: String) -> PackRecord {
        match self {
            AssetPackWire::Translation { from, to, common } => PackRecord {
                id,
                files: common.files.into_iter().map(Into::into).collect(),
                depends_on: common.depends_on,
                kind: PackKind::Translation(TranslationPack { from, to }),
            },
            AssetPackWire::Ocr { language, common } => PackRecord {
                id,
                files: common.files.into_iter().map(Into::into).collect(),
                depends_on: common.depends_on,
                kind: PackKind::Ocr(OcrPack { language }),
            },
            AssetPackWire::Tts {
                language,
                engine,
                locale,
                region,
                voice,
                quality,
                num_speakers,
                default_speaker_id,
                common,
            } => PackRecord {
                id,
                files: common.files.into_iter().map(Into::into).collect(),
                depends_on: common.depends_on,
                kind: PackKind::Tts(TtsPack {
                    language,
                    engine: normalized(engine),
                    locale: normalized(locale),
                    region: normalized(region),
                    voice: normalized(voice),
                    quality: normalized(quality),
                    num_speakers,
                    default_speaker_id,
                }),
            },
            AssetPackWire::Dictionary {
                language,
                dictionary_code,
                metadata,
                common,
            } => PackRecord {
                id,
                files: common.files.into_iter().map(Into::into).collect(),
                depends_on: common.depends_on,
                kind: PackKind::Dictionary(DictionaryPack {
                    language: normalized(language),
                    dictionary_code,
                    metadata: metadata.map(Into::into),
                }),
            },
            AssetPackWire::Support {
                language,
                languages,
                aliases,
                kind,
                metadata,
                common,
            } => PackRecord {
                id,
                files: common.files.into_iter().map(Into::into).collect(),
                depends_on: common.depends_on,
                kind: PackKind::Support(SupportPack {
                    language: normalized(language),
                    languages,
                    aliases,
                    kind: normalized(kind),
                    metadata: metadata.map(Into::into),
                }),
            },
        }
    }
}

fn compile_tts_config(value: Option<LanguageTtsWire>) -> Option<LanguageTtsV2> {
    let value = value?;
    let mut regions = value
        .regions
        .into_iter()
        .map(|(region_code, region)| {
            let display_name = region
                .display_name
                .filter(|value| !value.is_empty())
                .unwrap_or_else(|| region_code.clone());
            (
                region_code,
                LanguageTtsRegionV2 {
                    display_name,
                    voices: region.voices,
                },
            )
        })
        .collect::<Vec<_>>();
    regions.sort_by(|left, right| left.0.cmp(&right.0));
    Some(LanguageTtsV2 {
        default_region: value.default_region.filter(|value| !value.is_empty()),
        regions,
    })
}

fn compile_language_info(
    entry: LanguageEntryWire,
    packs: &HashMap<String, PackRecord>,
) -> Option<LanguageInfo> {
    let mut ocr_packs = entry.assets.ocr.into_iter().collect::<Vec<_>>();
    ocr_packs.sort_by(|left, right| left.0.cmp(&right.0));

    let resources = LanguageResources {
        translation_root_packs: entry.assets.translate,
        ocr_packs,
        dictionary_pack_id: entry.assets.dictionary.filter(|value| !value.is_empty()),
        support_root_packs: entry.assets.support,
    };

    let tesseract_pack_id = resources
        .ocr_packs
        .iter()
        .find(|(engine, _)| engine == "tesseract")
        .map(|(_, pack_id)| pack_id)?;
    let tesseract_pack = packs.get(tesseract_pack_id)?;
    let tess_file = tesseract_pack.files.first()?;
    let tess_name = tess_file.name.strip_suffix(".traineddata")?.to_string();
    let tessdata_size_bytes = tesseract_pack
        .files
        .iter()
        .map(|file| file.size_bytes)
        .sum();
    let dictionary_code = resources
        .dictionary_pack_id
        .as_ref()
        .and_then(|pack_id| packs.get(pack_id))
        .and_then(|pack| match &pack.kind {
            PackKind::Dictionary(dictionary) => Some(dictionary.dictionary_code.clone()),
            _ => None,
        })
        .unwrap_or_else(|| entry.meta.code.clone());
    let code = entry.meta.code.clone();

    Some(LanguageInfo {
        language: Language {
            code: code.clone(),
            display_name: entry.meta.name,
            short_display_name: entry.meta.short_name,
            tess_name,
            script: entry.meta.script,
            dictionary_code,
            tessdata_size_bytes,
        },
        resources,
        tts: compile_tts_config(entry.tts),
    })
}

pub fn parse_language_catalog(json: &str) -> Result<LanguageCatalog, String> {
    let wire =
        serde_json::from_str::<LanguageCatalogWire>(json).map_err(|error| error.to_string())?;
    let packs = wire
        .packs
        .into_iter()
        .map(|(id, pack)| {
            let record = pack.into_record(id.clone());
            (id, record)
        })
        .collect::<HashMap<_, _>>();

    let mut languages = HashMap::new();
    for (code, entry) in wire.languages {
        let Some(info) = compile_language_info(entry, &packs) else {
            return Err(format!("Failed to compile language {code}"));
        };
        languages.insert(code, info);
    }

    let translation_pack_ids = packs
        .iter()
        .filter_map(|(id, pack)| match &pack.kind {
            PackKind::Translation(direction) => {
                Some(((direction.from.clone(), direction.to.clone()), id.clone()))
            }
            _ => None,
        })
        .collect::<HashMap<_, _>>();

    let dictionary_pack_ids_by_code = packs
        .iter()
        .filter_map(|(id, pack)| match &pack.kind {
            PackKind::Dictionary(dictionary) => {
                Some((dictionary.dictionary_code.clone(), id.clone()))
            }
            _ => None,
        })
        .collect::<HashMap<_, _>>();

    let mut root_pack_ids_by_language_feature = HashMap::new();
    for (language_code, info) in &languages {
        root_pack_ids_by_language_feature.insert(
            (language_code.clone(), LanguageFeature::Translation),
            info.resources.translation_root_packs.clone(),
        );
        root_pack_ids_by_language_feature.insert(
            (language_code.clone(), LanguageFeature::Ocr),
            info.resources
                .ocr_packs
                .iter()
                .map(|(_, pack_id)| pack_id.clone())
                .collect(),
        );
        root_pack_ids_by_language_feature.insert(
            (language_code.clone(), LanguageFeature::Support),
            info.resources.support_root_packs.clone(),
        );
        root_pack_ids_by_language_feature.insert(
            (language_code.clone(), LanguageFeature::Dictionary),
            info.resources.dictionary_pack_id.iter().cloned().collect(),
        );
        let tts_pack_ids = info
            .tts
            .as_ref()
            .map(|tts| {
                let mut seen = std::collections::HashSet::new();
                let mut pack_ids = Vec::new();
                for (_, region) in &tts.regions {
                    for voice in &region.voices {
                        if seen.insert(voice.clone()) {
                            pack_ids.push(voice.clone());
                        }
                    }
                }
                pack_ids
            })
            .unwrap_or_default();
        root_pack_ids_by_language_feature
            .insert((language_code.clone(), LanguageFeature::Tts), tts_pack_ids);
    }

    Ok(LanguageCatalog {
        format_version: wire.format_version,
        generated_at: wire.generated_at,
        dictionary_version: wire.dictionary_version,
        sources: wire.sources.into(),
        languages,
        packs,
        translation_pack_ids,
        dictionary_pack_ids_by_code,
        root_pack_ids_by_language_feature,
    })
}

pub fn parse_and_validate_catalog(json: &str) -> Result<LanguageCatalog, String> {
    let catalog = parse_language_catalog(json)?;
    if catalog.format_version != 2 {
        return Err(format!(
            "Unsupported catalog formatVersion={}",
            catalog.format_version
        ));
    }
    Ok(catalog)
}

pub fn select_best_catalog<'a>(
    bundled_json: &'a str,
    disk_json: Option<&'a str>,
) -> Result<&'a str, String> {
    let parse_header = |json: &str| -> Result<CatalogHeaderWire, String> {
        let header =
            serde_json::from_str::<CatalogHeaderWire>(json).map_err(|error| error.to_string())?;
        if header.format_version != 2 {
            return Err(format!(
                "Unsupported catalog formatVersion={}",
                header.format_version
            ));
        }
        Ok(header)
    };

    let bundled = parse_header(bundled_json);
    let disk = disk_json.map(parse_header).transpose();

    match (bundled, disk) {
        (Ok(bundled_header), Ok(Some(disk_header))) => {
            if disk_header.generated_at >= bundled_header.generated_at {
                Ok(disk_json.expect("disk_json present when disk catalog parsed"))
            } else {
                Ok(bundled_json)
            }
        }
        (Ok(_), Ok(None)) => Ok(bundled_json),
        (Err(_), Ok(Some(_))) => Ok(disk_json.expect("disk_json present when disk catalog parsed")),
        (Ok(_), Err(_)) => Ok(bundled_json),
        (Err(bundled_error), Ok(None)) => Err(bundled_error),
        (Err(bundled_error), Err(disk_error)) => Err(format!(
            "Bundled catalog invalid: {bundled_error}; disk catalog invalid: {disk_error}"
        )),
    }
}
