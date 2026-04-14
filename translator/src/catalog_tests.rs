use std::collections::HashMap;

use crate::catalog::{
    AssetFileV2, AssetPackMetadataV2, CatalogSourcesV2, LangAvailability, LanguageCatalog,
    LanguageFeature, LanguageTtsRegionV2, LanguageTtsV2, PackInstallChecker, PackRecord,
    PackResolver, compute_language_availability, plan_language_download,
};
use crate::language::Language;
use crate::translate::resolve_translation_plan;

use super::model::{
    DictionaryPack, LanguageInfo, LanguageResources, OcrPack, PackKind, SupportPack,
    TranslationPack, TtsPack,
};

struct FakeInstallChecker {
    files: std::collections::HashSet<String>,
    markers: std::collections::HashSet<(String, i32)>,
}

impl FakeInstallChecker {
    fn with_files(paths: &[&str]) -> Self {
        Self {
            files: paths.iter().map(|path| path.to_string()).collect(),
            markers: std::collections::HashSet::new(),
        }
    }
}

impl PackInstallChecker for FakeInstallChecker {
    fn file_exists(&self, install_path: &str) -> bool {
        self.files.contains(install_path)
    }

    fn install_marker_exists(&self, marker_path: &str, expected_version: i32) -> bool {
        self.markers
            .contains(&(marker_path.to_string(), expected_version))
    }
}

fn asset_file(name: &str, install_path: &str, size_bytes: u64) -> AssetFileV2 {
    AssetFileV2 {
        name: name.to_string(),
        size_bytes,
        install_path: install_path.to_string(),
        url: format!("https://example.invalid/{name}"),
        source_path: None,
        archive_format: None,
        extract_to: None,
        delete_after_extract: false,
        install_marker_path: None,
        install_marker_version: None,
    }
}

fn pack_record(
    id: &str,
    kind: PackKind,
    files: Vec<AssetFileV2>,
    depends_on: Vec<&str>,
) -> PackRecord {
    PackRecord {
        id: id.to_string(),
        files,
        depends_on: depends_on.into_iter().map(ToString::to_string).collect(),
        kind,
    }
}

fn translation_pack(
    id: &str,
    from: &str,
    to: &str,
    files: Vec<AssetFileV2>,
    depends_on: Vec<&str>,
) -> PackRecord {
    pack_record(
        id,
        PackKind::Translation(TranslationPack {
            from: from.to_string(),
            to: to.to_string(),
        }),
        files,
        depends_on,
    )
}

fn ocr_pack(id: &str, language: &str, file: AssetFileV2) -> PackRecord {
    pack_record(
        id,
        PackKind::Ocr(OcrPack {
            language: language.to_string(),
        }),
        vec![file],
        vec![],
    )
}

fn dictionary_pack(
    id: &str,
    language: &str,
    dictionary_code: &str,
    file: AssetFileV2,
    metadata: Option<AssetPackMetadataV2>,
) -> PackRecord {
    pack_record(
        id,
        PackKind::Dictionary(DictionaryPack {
            language: Some(language.to_string()),
            dictionary_code: dictionary_code.to_string(),
            metadata,
        }),
        vec![file],
        vec![],
    )
}

fn support_pack(id: &str, language: Option<&str>, file: AssetFileV2) -> PackRecord {
    pack_record(
        id,
        PackKind::Support(SupportPack {
            language: language.map(ToString::to_string),
            languages: vec![],
            aliases: vec![],
            kind: None,
            metadata: None,
        }),
        vec![file],
        vec![],
    )
}

fn tts_pack(
    id: &str,
    language: &str,
    region: &str,
    default_speaker_id: i32,
    files: Vec<AssetFileV2>,
) -> PackRecord {
    pack_record(
        id,
        PackKind::Tts(TtsPack {
            language: language.to_string(),
            engine: Some("piper".to_string()),
            locale: None,
            region: Some(region.to_string()),
            voice: None,
            quality: None,
            num_speakers: None,
            default_speaker_id: Some(default_speaker_id),
        }),
        files,
        vec![],
    )
}

fn language_info(
    language: Language,
    translation_root_packs: Vec<&str>,
    ocr_pack_id: &str,
    dictionary_pack_id: Option<&str>,
    support_root_packs: Vec<&str>,
    tts_regions: Vec<(&str, &str, Vec<&str>)>,
    default_region: Option<&str>,
) -> LanguageInfo {
    LanguageInfo {
        language,
        resources: LanguageResources {
            translation_root_packs: translation_root_packs
                .into_iter()
                .map(ToString::to_string)
                .collect(),
            ocr_packs: vec![("tesseract".to_string(), ocr_pack_id.to_string())],
            dictionary_pack_id: dictionary_pack_id.map(ToString::to_string),
            support_root_packs: support_root_packs
                .into_iter()
                .map(ToString::to_string)
                .collect(),
        },
        tts: Some(LanguageTtsV2 {
            default_region: default_region.map(ToString::to_string),
            regions: tts_regions
                .into_iter()
                .map(|(code, display_name, voices)| {
                    (
                        code.to_string(),
                        LanguageTtsRegionV2 {
                            display_name: display_name.to_string(),
                            voices: voices.into_iter().map(ToString::to_string).collect(),
                        },
                    )
                })
                .collect(),
        }),
    }
}

fn base_catalog() -> LanguageCatalog {
    let english = Language {
        code: "en".to_string(),
        display_name: "English".to_string(),
        short_display_name: "English".to_string(),
        tess_name: "eng".to_string(),
        script: "Latn".to_string(),
        dictionary_code: "en".to_string(),
        tessdata_size_bytes: 10,
    };
    let spanish = Language {
        code: "es".to_string(),
        display_name: "Spanish".to_string(),
        short_display_name: "Spanish".to_string(),
        tess_name: "spa".to_string(),
        script: "Latn".to_string(),
        dictionary_code: "es".to_string(),
        tessdata_size_bytes: 11,
    };

    let languages = HashMap::from([
        (
            "en".to_string(),
            language_info(
                english,
                vec![],
                "ocr-en",
                Some("dict-en"),
                vec![],
                vec![("us", "United States", vec!["tts-en-us"])],
                Some("us"),
            ),
        ),
        (
            "es".to_string(),
            language_info(
                spanish,
                vec!["translate-es-en", "translate-en-es"],
                "ocr-es",
                Some("dict-es"),
                vec!["support-es"],
                vec![("es", "Spain", vec!["tts-es"])],
                Some("es"),
            ),
        ),
    ]);

    let packs = HashMap::from([
        (
            "ocr-en".to_string(),
            ocr_pack(
                "ocr-en",
                "en",
                asset_file("eng.traineddata", "tesseract/tessdata/eng.traineddata", 10),
            ),
        ),
        (
            "ocr-es".to_string(),
            ocr_pack(
                "ocr-es",
                "es",
                asset_file("spa.traineddata", "tesseract/tessdata/spa.traineddata", 11),
            ),
        ),
        (
            "dict-en".to_string(),
            dictionary_pack(
                "dict-en",
                "en",
                "en",
                asset_file("en.dict", "dictionaries/en.dict", 12),
                Some(AssetPackMetadataV2 {
                    date: Some(123),
                    type_name: Some("wiktionary".to_string()),
                    word_count: Some(456),
                }),
            ),
        ),
        (
            "dict-es".to_string(),
            dictionary_pack(
                "dict-es",
                "es",
                "es",
                asset_file("es.dict", "dictionaries/es.dict", 13),
                None,
            ),
        ),
        (
            "support-es".to_string(),
            support_pack(
                "support-es",
                Some("es"),
                asset_file("mucab.bin", "bin/mucab.bin", 14),
            ),
        ),
        (
            "translate-en-es".to_string(),
            translation_pack(
                "translate-en-es",
                "en",
                "es",
                vec![
                    asset_file("model.enes.bin", "bin/model.enes.bin", 20),
                    asset_file("lex.enen.bin", "bin/lex.enen.bin", 21),
                    asset_file("vocab.aa.spm", "bin/vocab.aa.spm", 22),
                    asset_file("vocab.zz.spm", "bin/vocab.zz.spm", 23),
                ],
                vec!["shared-translate"],
            ),
        ),
        (
            "translate-es-en".to_string(),
            translation_pack(
                "translate-es-en",
                "es",
                "en",
                vec![
                    asset_file("model.esen.bin", "bin/model.esen.bin", 24),
                    asset_file("lex.esen.bin", "bin/lex.esen.bin", 25),
                    asset_file("vocab.bb.spm", "bin/vocab.bb.spm", 26),
                    asset_file("vocab.yy.spm", "bin/vocab.yy.spm", 27),
                ],
                vec!["shared-translate"],
            ),
        ),
        (
            "shared-translate".to_string(),
            support_pack(
                "shared-translate",
                None,
                asset_file("shared.bin", "bin/shared.bin", 28),
            ),
        ),
        (
            "tts-en-us".to_string(),
            tts_pack(
                "tts-en-us",
                "en",
                "us",
                7,
                vec![
                    asset_file("voice.onnx", "tts/voice.onnx", 31),
                    asset_file("voice.onnx.json", "tts/voice.onnx.json", 32),
                ],
            ),
        ),
        (
            "tts-es".to_string(),
            tts_pack(
                "tts-es",
                "es",
                "es",
                8,
                vec![
                    asset_file("voice-es.onnx", "tts/voice-es.onnx", 33),
                    asset_file("voice-es.onnx.json", "tts/voice-es.onnx.json", 34),
                ],
            ),
        ),
    ]);

    LanguageCatalog {
        format_version: 2,
        generated_at: 1,
        dictionary_version: 1,
        sources: CatalogSourcesV2 {
            language_index_version: 1,
            language_index_updated_at: 1,
            dictionary_index_version: 1,
            dictionary_index_updated_at: 1,
        },
        languages,
        packs,
        translation_pack_ids: HashMap::from([
            (
                ("en".to_string(), "es".to_string()),
                "translate-en-es".to_string(),
            ),
            (
                ("es".to_string(), "en".to_string()),
                "translate-es-en".to_string(),
            ),
        ]),
        dictionary_pack_ids_by_code: HashMap::from([
            ("en".to_string(), "dict-en".to_string()),
            ("es".to_string(), "dict-es".to_string()),
        ]),
        root_pack_ids_by_language_feature: HashMap::from([
            (("en".to_string(), LanguageFeature::Translation), Vec::new()),
            (
                ("en".to_string(), LanguageFeature::Ocr),
                vec!["ocr-en".to_string()],
            ),
            (
                ("en".to_string(), LanguageFeature::Dictionary),
                vec!["dict-en".to_string()],
            ),
            (("en".to_string(), LanguageFeature::Support), Vec::new()),
            (
                ("en".to_string(), LanguageFeature::Tts),
                vec!["tts-en-us".to_string()],
            ),
            (
                ("es".to_string(), LanguageFeature::Translation),
                vec!["translate-es-en".to_string(), "translate-en-es".to_string()],
            ),
            (
                ("es".to_string(), LanguageFeature::Ocr),
                vec!["ocr-es".to_string()],
            ),
            (
                ("es".to_string(), LanguageFeature::Dictionary),
                vec!["dict-es".to_string()],
            ),
            (
                ("es".to_string(), LanguageFeature::Support),
                vec!["support-es".to_string()],
            ),
            (
                ("es".to_string(), LanguageFeature::Tts),
                vec!["tts-es".to_string()],
            ),
        ]),
    }
}

#[test]
fn computes_dependency_closure_and_pack_size_without_double_counting() {
    let catalog = base_catalog();

    let closure = catalog.dependency_closure(["translate-en-es"]);
    assert_eq!(
        closure,
        vec![
            "translate-en-es".to_string(),
            "shared-translate".to_string()
        ]
    );
    assert_eq!(
        catalog.pack_size_bytes("translate-en-es"),
        20 + 21 + 22 + 23 + 28
    );
    assert_eq!(
        catalog.translation_size_bytes_for_language("es"),
        (20 + 21 + 22 + 23 + 28) + (24 + 25 + 26 + 27 + 28)
    );
}

#[test]
fn resolves_missing_pack_files_through_install_boundary() {
    let catalog = base_catalog();
    let checker = FakeInstallChecker::with_files(&["bin/model.enes.bin", "bin/shared.bin"]);
    let plan = plan_language_download(&catalog, "es", &mut PackResolver::new(&catalog, &checker));
    let missing_paths = plan
        .tasks
        .into_iter()
        .filter(|task| task.pack_id == "translate-en-es")
        .map(|task| task.install_path)
        .collect::<Vec<_>>();

    assert_eq!(
        missing_paths,
        vec![
            "bin/lex.enen.bin".to_string(),
            "bin/vocab.aa.spm".to_string(),
            "bin/vocab.zz.spm".to_string(),
        ]
    );
}

#[test]
fn computes_language_availability_from_pack_install_state() {
    let catalog = base_catalog();
    let checker = FakeInstallChecker::with_files(&[
        "tesseract/tessdata/eng.traineddata",
        "dictionaries/en.dict",
        "tts/voice.onnx",
        "tts/voice.onnx.json",
        "bin/model.enes.bin",
        "bin/lex.enen.bin",
        "bin/vocab.aa.spm",
        "bin/vocab.zz.spm",
        "bin/model.esen.bin",
        "bin/lex.esen.bin",
        "bin/vocab.bb.spm",
        "bin/vocab.yy.spm",
        "bin/shared.bin",
        "tesseract/tessdata/spa.traineddata",
        "dictionaries/es.dict",
        "bin/mucab.bin",
        "tts/voice-es.onnx",
        "tts/voice-es.onnx.json",
    ]);
    let mut resolver = PackResolver::new(&catalog, &checker);

    let availability = compute_language_availability(&catalog, &mut resolver);
    let spanish = catalog.language_by_code("es").unwrap();
    let english = catalog.language_by_code("en").unwrap();

    assert_eq!(
        availability.get(&spanish),
        Some(&LangAvailability {
            has_from_english: true,
            has_to_english: true,
            ocr_files: true,
            dictionary_files: true,
            tts_files: true,
        })
    );
    assert_eq!(
        availability.get(&english),
        Some(&LangAvailability {
            has_from_english: true,
            has_to_english: true,
            ocr_files: true,
            dictionary_files: true,
            tts_files: true,
        })
    );
}

#[test]
fn builds_languages_and_dictionary_info_from_catalog() {
    let catalog = base_catalog();
    let spanish = catalog.language_by_code("es").unwrap();
    let dictionary_info = catalog.dictionary_info("en").unwrap();

    assert_eq!(spanish.tess_name, "spa");
    assert_eq!(dictionary_info.filename, "en.dict");
    assert_eq!(dictionary_info.type_name, "wiktionary");
    assert_eq!(dictionary_info.word_count, 456);
}

#[test]
fn parses_bundled_catalog_asset() {
    let json = include_str!("../../app/src/main/assets/index.json");
    let catalog =
        crate::catalog::parse_and_validate_catalog(json).expect("bundled catalog should parse");
    assert!(!catalog.language_list().is_empty());
    assert!(catalog.language_by_code("en").is_some());
}

#[test]
fn selects_best_catalog_using_headers_only() {
    let bundled = r#"{"formatVersion":2,"generatedAt":1}"#;
    let disk = r#"{"formatVersion":2,"generatedAt":2}"#;

    let selected = crate::catalog::select_best_catalog(bundled, Some(disk))
        .expect("header-only catalogs should still compare");

    assert_eq!(selected, disk);
}

#[test]
fn resolves_direct_translation_plan_from_installed_catalog() {
    let catalog = base_catalog();
    let checker = FakeInstallChecker::with_files(&[
        "bin/model.enes.bin",
        "bin/lex.enen.bin",
        "bin/vocab.aa.spm",
        "bin/vocab.zz.spm",
        "bin/shared.bin",
    ]);
    let mut resolver = PackResolver::new(&catalog, &checker);

    let plan = resolve_translation_plan(
        &catalog,
        "/data/user/0/dev.davidv.translator/files",
        "en",
        "es",
        &mut resolver,
    )
    .expect("direct plan should resolve");

    assert_eq!(plan.steps.len(), 1);
    assert_eq!(plan.steps[0].cache_key, "enes");
    assert!(
        plan.steps[0]
            .config
            .contains("/data/user/0/dev.davidv.translator/files/bin/model.enes.bin")
    );
    assert!(
        plan.steps[0]
            .config
            .contains("/data/user/0/dev.davidv.translator/files/bin/vocab.aa.spm")
    );
    assert!(
        plan.steps[0]
            .config
            .contains("/data/user/0/dev.davidv.translator/files/bin/vocab.zz.spm")
    );
}

#[test]
fn resolves_pivot_translation_plan_from_installed_catalog() {
    let mut catalog = base_catalog();
    let french = Language {
        code: "fr".to_string(),
        display_name: "French".to_string(),
        short_display_name: "French".to_string(),
        tess_name: "fra".to_string(),
        script: "Latn".to_string(),
        dictionary_code: "fr".to_string(),
        tessdata_size_bytes: 0,
    };
    catalog.languages.insert(
        "fr".to_string(),
        language_info(
            french,
            vec!["translate-fr-en", "translate-en-fr"],
            "ocr-en",
            None,
            vec![],
            vec![],
            None,
        ),
    );
    catalog.packs.insert(
        "translate-en-fr".to_string(),
        translation_pack(
            "translate-en-fr",
            "en",
            "fr",
            vec![
                asset_file("model.enfr.bin", "bin/model.enfr.bin", 40),
                asset_file("lex.enfr.bin", "bin/lex.enfr.bin", 41),
                asset_file("vocab.cc.spm", "bin/vocab.cc.spm", 42),
                asset_file("vocab.dd.spm", "bin/vocab.dd.spm", 43),
            ],
            vec!["shared-translate"],
        ),
    );
    catalog.packs.insert(
        "translate-fr-en".to_string(),
        translation_pack(
            "translate-fr-en",
            "fr",
            "en",
            vec![
                asset_file("model.fren.bin", "bin/model.fren.bin", 44),
                asset_file("lex.fren.bin", "bin/lex.fren.bin", 45),
                asset_file("vocab.ee.spm", "bin/vocab.ee.spm", 46),
                asset_file("vocab.ff.spm", "bin/vocab.ff.spm", 47),
            ],
            vec!["shared-translate"],
        ),
    );
    catalog.translation_pack_ids.insert(
        ("en".to_string(), "fr".to_string()),
        "translate-en-fr".to_string(),
    );
    catalog.translation_pack_ids.insert(
        ("fr".to_string(), "en".to_string()),
        "translate-fr-en".to_string(),
    );
    catalog.root_pack_ids_by_language_feature.insert(
        ("fr".to_string(), LanguageFeature::Translation),
        vec!["translate-fr-en".to_string(), "translate-en-fr".to_string()],
    );

    let checker = FakeInstallChecker::with_files(&[
        "bin/model.esen.bin",
        "bin/lex.esen.bin",
        "bin/vocab.bb.spm",
        "bin/vocab.yy.spm",
        "bin/model.enfr.bin",
        "bin/lex.enfr.bin",
        "bin/vocab.cc.spm",
        "bin/vocab.dd.spm",
        "bin/shared.bin",
    ]);
    let mut resolver = PackResolver::new(&catalog, &checker);

    let plan = resolve_translation_plan(&catalog, "/tmp/base", "es", "fr", &mut resolver)
        .expect("pivot plan should resolve");

    assert!(plan.is_pivot());
    assert_eq!(plan.steps.len(), 2);
    assert_eq!(plan.steps[0].cache_key, "esen");
    assert_eq!(plan.steps[1].cache_key, "enfr");
}

#[test]
fn refuses_translation_plan_when_required_direction_is_missing() {
    let catalog = base_catalog();
    let checker = FakeInstallChecker::with_files(&["bin/model.esen.bin"]);
    let mut resolver = PackResolver::new(&catalog, &checker);

    assert!(resolve_translation_plan(&catalog, "/tmp/base", "es", "en", &mut resolver).is_none());
}
