import time

import catalog_base
import catalog_mirror
import catalog_tts


ADBLOCK_PACK_KIND = "adblock"

ADBLOCK_LISTS = {
    "easylist": {
        "url": "https://easylist.to/easylist/easylist.txt",
        "filename": "easylist.txt",
        "description": "EasyList — primary advertising filter list.",
    },
    "easyprivacy": {
        "url": "https://easylist.to/easylist/easyprivacy.txt",
        "filename": "easyprivacy.txt",
        "description": "EasyPrivacy — tracking and analytics filter list.",
    },
    "fanboy-annoyance": {
        "url": "https://easylist.to/easylist/fanboy-annoyance.txt",
        "filename": "fanboy-annoyance.txt",
        "description": "Fanboy's Annoyances — cookie banners, social widgets, sticky video.",
    },
    "ublock-filters": {
        "url": "https://ublockorigin.github.io/uAssets/filters/filters.txt",
        "filename": "ublock-filters.txt",
        "description": "uBlock Origin filters — cosmetic supplement to EasyList.",
    },
    "ublock-unbreak": {
        "url": "https://ublockorigin.github.io/uAssets/filters/unbreak.txt",
        "filename": "ublock-unbreak.txt",
        "description": "uBlock Origin unbreak — exceptions to fix sites broken by other lists.",
    },
    "adguard-mobile": {
        "url": "https://filters.adtidy.org/extension/ublock/filters/11.txt",
        "filename": "adguard-mobile.txt",
        "description": "AdGuard Mobile Ads — mobile-specific ad slots and tracker patterns.",
    },
}


def add_adblock_packs(catalog: dict) -> None:
    for list_id, info in sorted(ADBLOCK_LISTS.items()):
        catalog["packs"][f"support-adblock-{list_id}"] = {
            "feature": "support",
            "kind": ADBLOCK_PACK_KIND,
            "files": [
                {
                    "name": info["filename"],
                    "sizeBytes": 0,
                    "installPath": f"adblock/{info['filename']}",
                    "url": info["url"],
                }
            ],
            "dependsOn": [],
            "metadata": {
                "listId": list_id,
                "description": info["description"],
            },
        }


MODELS_MANIFEST_URL = "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json"
TESSERACT_BASE_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/refs/heads/main"
DICTIONARY_BASE_URL = "https://offline-translator.davidv.dev/dictionaries"
DICT_VERSION = 1

LANGUAGE_NAMES = {
    "ar": "Arabic",
    "az": "Azerbaijani",
    "be": "Belarusian",
    "bg": "Bulgarian",
    "bn": "Bengali",
    "bs": "Bosnian",
    "ca": "Catalan",
    "cs": "Czech",
    "da": "Danish",
    "de": "German",
    "el": "Greek",
    "en": "English",
    "es": "Spanish",
    "et": "Estonian",
    "fa": "Persian",
    "fi": "Finnish",
    "fr": "French",
    "gu": "Gujarati",
    "he": "Hebrew",
    "hi": "Hindi",
    "hr": "Croatian",
    "hu": "Hungarian",
    "id": "Indonesian",
    "is": "Icelandic",
    "it": "Italian",
    "ja": "Japanese",
    "kn": "Kannada",
    "ko": "Korean",
    "lt": "Lithuanian",
    "lv": "Latvian",
    "ml": "Malayalam",
    "ms": "Malay",
    "nb": "Norwegian Bokmal",
    "nl": "Dutch",
    "nn": "Norwegian Nynorsk",
    "no": "Norwegian",
    "pl": "Polish",
    "pt": "Portuguese",
    "ro": "Romanian",
    "ru": "Russian",
    "sk": "Slovak",
    "sl": "Slovenian",
    "sq": "Albanian",
    "sr": "Serbian",
    "sv": "Swedish",
    "ta": "Tamil",
    "te": "Telugu",
    "th": "Thai",
    "tr": "Turkish",
    "uk": "Ukrainian",
    "vi": "Vietnamese",
    "zh": "Chinese (简体)",
    "zh_hant": "Chinese (繁體)",
}

TESSERACT_LANGUAGE_MAPPINGS = {
    "ar": "ara",
    "az": "aze",
    "be": "bel",
    "bg": "bul",
    "bn": "ben",
    "bs": "bos",
    "ca": "cat",
    "cs": "ces",
    "da": "dan",
    "de": "deu",
    "el": "ell",
    "en": "eng",
    "es": "spa",
    "et": "est",
    "fa": "fas",
    "fi": "fin",
    "fr": "fra",
    "gu": "guj",
    "he": "heb",
    "hi": "hin",
    "hr": "hrv",
    "hu": "hun",
    "id": "ind",
    "is": "isl",
    "it": "ita",
    "ja": "jpn",
    "kn": "kan",
    "ko": "kor",
    "lt": "lit",
    "lv": "lav",
    "ml": "mal",
    "ms": "msa",
    "nb": "nor",
    "nl": "nld",
    "nn": "nor",
    "no": "nor",
    "pl": "pol",
    "pt": "por",
    "ro": "ron",
    "ru": "rus",
    "sk": "slk",
    "sl": "slv",
    "sq": "sqi",
    "sr": "srp",
    "sv": "swe",
    "ta": "tam",
    "te": "tel",
    "th": "tha",
    "tr": "tur",
    "uk": "ukr",
    "vi": "vie",
    "zh": "chi_sim",
    "zh_hant": "chi_tra",
}

SHORT_DISPLAY_NAMES = {
    "zh": "简体",
    "zh_hant": "繁體",
    "nb": "Bokmal",
    "nn": "Nynorsk",
}

LANGUAGE_SCRIPTS = {
    "ar": "Arab",
    "az": "Latn",
    "be": "Cyrl",
    "bg": "Cyrl",
    "bn": "Beng",
    "bs": "Latn",
    "ca": "Latn",
    "cs": "Latn",
    "da": "Latn",
    "de": "Latn",
    "el": "Grek",
    "en": "Latn",
    "es": "Latn",
    "et": "Latn",
    "fa": "Arab",
    "fi": "Latn",
    "fr": "Latn",
    "gu": "Gujr",
    "he": "Hebr",
    "hi": "Deva",
    "hr": "Latn",
    "hu": "Latn",
    "id": "Latn",
    "is": "Latn",
    "it": "Latn",
    "ja": "Jpan",
    "kn": "Knda",
    "ko": "Hang",
    "lt": "Latn",
    "lv": "Latn",
    "ml": "Mlym",
    "ms": "Latn",
    "nb": "Latn",
    "nl": "Latn",
    "nn": "Latn",
    "no": "Latn",
    "pl": "Latn",
    "pt": "Latn",
    "ro": "Latn",
    "ru": "Cyrl",
    "sk": "Latn",
    "sl": "Latn",
    "sq": "Latn",
    "sr": "Cyrl",
    "sv": "Latn",
    "ta": "Taml",
    "te": "Telu",
    "th": "Thai",
    "tr": "Latn",
    "uk": "Cyrl",
    "vi": "Latn",
    "zh": "Hans",
    "zh_hant": "Hant",
}

MODEL_TYPE_PRIORITY = {
    "tiny": 1,
    "base": 2,
    "base-memory": 3,
}

MANIFEST_FILE_TYPES = {
    "model": "model",
    "lexicalShortlist": "lex",
    "vocab": "vocab",
    "srcVocab": "srcVocab",
    "trgVocab": "tgtVocab",
}

DICTIONARY_CODE_OVERRIDES = {
    "zh_hant": "zh",
    "bs": "hr",
    "sr": "hr",
}

EXTRA_FILES = {
    "ja": ["mucab.bin"],
}


def strip_compression_suffix(filename: str) -> str:
    if filename.endswith(".gz"):
        return filename[:-3]
    return filename


def select_best_entry(entries: list[dict]) -> dict:
    return max(entries, key=lambda entry: MODEL_TYPE_PRIORITY.get(entry.get("architecture", ""), 0))


def build_pair_files(manifest: dict) -> dict[tuple[str, str], dict]:
    pair_files = {}

    for pair_key, entries in manifest["models"].items():
        best_entry = select_best_entry(entries)
        src = best_entry["sourceLanguage"]
        tgt = best_entry["targetLanguage"]

        if src not in LANGUAGE_NAMES or tgt not in LANGUAGE_NAMES:
            continue

        files = {}
        for manifest_file_type, file_type in MANIFEST_FILE_TYPES.items():
            file_info = best_entry["files"].get(manifest_file_type)
            if file_info is None:
                continue

            path = file_info["path"]
            files[file_type] = {
                "name": strip_compression_suffix(path.rsplit("/", 1)[-1]),
                "sizeBytes": 0,
                "path": path,
            }

        pair_files[(src, tgt)] = files

    return pair_files


def build_language_data(pair_files: dict[tuple[str, str], dict]) -> tuple[dict, dict, set[str]]:
    from_english = {}
    to_english = {}

    for (src, tgt), files in pair_files.items():
        if "model" not in files:
            continue
        if src != "en" and tgt != "en":
            continue

        model = files["model"]
        lex = files.get("lex")
        vocab = files.get("vocab")
        src_vocab = files.get("srcVocab", vocab)
        tgt_vocab = files.get("tgtVocab", vocab)

        if not all([model, lex, src_vocab, tgt_vocab]):
            continue

        entry = {
            "model": model,
            "srcVocab": src_vocab,
            "tgtVocab": tgt_vocab,
            "lex": lex,
        }

        if src == "en":
            from_english[tgt] = entry
        else:
            to_english[src] = entry

    all_languages = set(from_english.keys()) | set(to_english.keys()) | {"en"}
    return from_english, to_english, all_languages


def format_direction(entry: dict) -> dict:
    return {
        "model": entry["model"],
        "srcVocab": entry["srcVocab"],
        "tgtVocab": entry["tgtVocab"],
        "lex": entry["lex"],
    }


def build_language_index(
    *,
    models_manifest: dict,
    tesseract_base_url: str = TESSERACT_BASE_URL,
    dictionary_base_url: str = DICTIONARY_BASE_URL,
    dictionary_version: int = DICT_VERSION,
) -> dict:
    pair_files = build_pair_files(models_manifest)
    from_english, to_english, all_languages = build_language_data(pair_files)

    languages = []
    for lang_code in sorted(all_languages):
        if lang_code not in LANGUAGE_NAMES:
            continue

        tess_name = TESSERACT_LANGUAGE_MAPPINGS.get(lang_code)
        if tess_name is None:
            continue

        languages.append(
            {
                "code": lang_code,
                "name": LANGUAGE_NAMES[lang_code],
                "shortName": SHORT_DISPLAY_NAMES.get(lang_code, LANGUAGE_NAMES[lang_code]),
                "tessName": tess_name,
                "script": LANGUAGE_SCRIPTS[lang_code],
                "dictionaryCode": DICTIONARY_CODE_OVERRIDES.get(lang_code, lang_code),
                "tessdataSizeBytes": 0,
                "toEnglish": format_direction(to_english[lang_code]) if lang_code in to_english else None,
                "fromEnglish": format_direction(from_english[lang_code]) if lang_code in from_english else None,
                "extraFiles": EXTRA_FILES.get(lang_code, []),
            }
        )

    return {
        "version": 1,
        "updatedAt": int(time.time()),
        "translationModelsBaseUrl": models_manifest["baseUrl"].rstrip("/"),
        "tesseractModelsBaseUrl": tesseract_base_url.rstrip("/"),
        "dictionaryBaseUrl": dictionary_base_url.rstrip("/"),
        "dictionaryVersion": int(dictionary_version),
        "languages": languages,
    }


def build_source_catalog(
    *,
    models_manifest: dict,
    dictionary_index: dict,
    voices: dict,
    piper_base_url: str = catalog_tts.PIPER_BASE_URL,
    tts_base_url: str = catalog_tts.TTS_BASE_URL,
    tts_version: int = catalog_tts.TTS_VERSION,
    espeak_core_zip_size: int = 0,
) -> dict:
    language_index = build_language_index(
        models_manifest=models_manifest,
        dictionary_version=dictionary_index["version"],
    )
    base_catalog = catalog_base.convert_v1_to_v2(language_index, dictionary_index)
    catalog = catalog_tts.merge_tts(
        base_catalog=base_catalog,
        voices=voices,
        piper_base_url=piper_base_url,
        tts_base_url=tts_base_url,
        tts_version=tts_version,
        espeak_core_zip_size=espeak_core_zip_size,
    )
    add_adblock_packs(catalog)
    catalog_mirror.apply_mirror_paths(catalog)
    catalog.setdefault("sources", {})
    catalog["sources"].update(
        {
            "gcsModelsUrl": MODELS_MANIFEST_URL,
            "gcsModelsGenerated": models_manifest.get("generated"),
            "dictionaryIndexVersion": int(dictionary_index["version"]),
            "piperVoiceCount": len(voices),
        }
    )
    return catalog
