#!/usr/bin/env python3
import asyncio
import json
import os
import re
import unicodedata
from typing import Dict

import aiohttp

MODELS_MANIFEST_URL = "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json"
MODELS_MANIFEST_CACHE = "data/models_manifest_v2.json"
MODEL_SIZE_CACHE = "data/model_sizes_v2.json"
TESSERACT_BASE_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/refs/heads/main"
DICTIONARY_BASE_URL = "https://translator.davidv.dev/dictionaries"
DICT_VERSION = 1

LANGUAGE_NAMES = {
    'ar': 'Arabic',
    'az': 'Azerbaijani',
    'be': 'Belarusian',
    'bg': 'Bulgarian',
    'bn': 'Bengali',
    'bs': 'Bosnian',
    'ca': 'Catalan',
    'cs': 'Czech',
    'da': 'Danish',
    'de': 'German',
    'el': 'Greek',
    'en': 'English',
    'es': 'Spanish',
    'et': 'Estonian',
    'fa': 'Persian',
    'fi': 'Finnish',
    'fr': 'French',
    'gu': 'Gujarati',
    'he': 'Hebrew',
    'hi': 'Hindi',
    'hr': 'Croatian',
    'hu': 'Hungarian',
    'id': 'Indonesian',
    'is': 'Icelandic',
    'it': 'Italian',
    'ja': 'Japanese',
    'kn': 'Kannada',
    'ko': 'Korean',
    'lt': 'Lithuanian',
    'lv': 'Latvian',
    'ml': 'Malayalam',
    'ms': 'Malay',
    'nb': 'Norwegian Bokmål',
    'nl': 'Dutch',
    'nn': 'Norwegian Nynorsk',
    'no': 'Norwegian',
    'pl': 'Polish',
    'pt': 'Portuguese',
    'ro': 'Romanian',
    'ru': 'Russian',
    'sk': 'Slovak',
    'sl': 'Slovenian',
    'sq': 'Albanian',
    'sr': 'Serbian',
    'sv': 'Swedish',
    'ta': 'Tamil',
    'te': 'Telugu',
    'th': 'Thai',
    'tr': 'Turkish',
    'uk': 'Ukrainian',
    'vi': 'Vietnamese',
    'zh': 'Chinese (简体)',
    'zh_hant': 'Chinese (繁體)',
}

TESSERACT_LANGUAGE_MAPPINGS = {
    'ar': 'ara',
    'az': 'aze',
    'be': 'bel',
    'bg': 'bul',
    'bn': 'ben',
    'bs': 'bos',
    'ca': 'cat',
    'cs': 'ces',
    'da': 'dan',
    'de': 'deu',
    'el': 'ell',
    'en': 'eng',
    'es': 'spa',
    'et': 'est',
    'fa': 'fas',
    'fi': 'fin',
    'fr': 'fra',
    'gu': 'guj',
    'he': 'heb',
    'hi': 'hin',
    'hr': 'hrv',
    'hu': 'hun',
    'id': 'ind',
    'is': 'isl',
    'it': 'ita',
    'ja': 'jpn',
    'kn': 'kan',
    'ko': 'kor',
    'lt': 'lit',
    'lv': 'lav',
    'ml': 'mal',
    'ms': 'msa',
    'nb': 'nor',
    'nl': 'nld',
    'nn': 'nor',
    'no': 'nor',
    'pl': 'pol',
    'pt': 'por',
    'ro': 'ron',
    'ru': 'rus',
    'sk': 'slk',
    'sl': 'slv',
    'sq': 'sqi',
    'sr': 'srp',
    'sv': 'swe',
    'ta': 'tam',
    'te': 'tel',
    'th': 'tha',
    'tr': 'tur',
    'uk': 'ukr',
    'vi': 'vie',
    'zh': 'chi_sim',
    'zh_hant': 'chi_tra',
}

SHORT_DISPLAY_NAMES = {
    'zh': '简体',
    'zh_hant': '繁體',
    'nb': 'Bokmål',
    'nn': 'Nynorsk',
}

LANGUAGE_SCRIPTS = {
    'ar': 'Arabic',
    'az': 'Latin',
    'be': 'Cyrillic',
    'bg': 'Cyrillic',
    'bn': 'Bengali',
    'bs': 'Latin',
    'ca': 'Latin',
    'cs': 'Latin',
    'da': 'Latin',
    'de': 'Latin',
    'el': 'Greek',
    'en': 'Latin',
    'es': 'Latin',
    'et': 'Latin',
    'fa': 'Arabic',
    'fi': 'Latin',
    'fr': 'Latin',
    'gu': 'Gujarati',
    'he': 'Hebrew',
    'hi': 'Devanagari',
    'hr': 'Latin',
    'hu': 'Latin',
    'id': 'Latin',
    'is': 'Latin',
    'it': 'Latin',
    'ja': 'Japanese',
    'kn': 'Kannada',
    'ko': 'Hangul',
    'lt': 'Latin',
    'lv': 'Latin',
    'ml': 'Malayalam',
    'ms': 'Latin',
    'nb': 'Latin',
    'nl': 'Latin',
    'nn': 'Latin',
    'no': 'Latin',
    'pl': 'Latin',
    'pt': 'Latin',
    'ro': 'Latin',
    'ru': 'Cyrillic',
    'sk': 'Latin',
    'sl': 'Latin',
    'sq': 'Latin',
    'sr': 'Cyrillic',
    'sv': 'Latin',
    'ta': 'Tamil',
    'te': 'Telugu',
    'th': 'Thai',
    'tr': 'Latin',
    'uk': 'Cyrillic',
    'vi': 'Latin',
    'zh': 'Han',
    'zh_hant': 'Han',
}

MODEL_TYPE_PRIORITY = {
    'tiny': 1,
    'base': 2,
    'base-memory': 3,
}

MANIFEST_FILE_TYPES = {
    'model': 'model',
    'lexicalShortlist': 'lex',
    'vocab': 'vocab',
    'srcVocab': 'srcVocab',
    'trgVocab': 'tgtVocab',
}


def sanitize_enum_name(name: str) -> str:
    normalized_name = (
        name.replace("简体", "sim")
        .replace("繁體", "tra")
        .replace("繁体", "tra")
    )
    ascii_name = unicodedata.normalize("NFKD", normalized_name).encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^A-Z0-9]+", "_", ascii_name.upper()).strip("_")


def get_best_model_type(model_types: set[str]) -> str:
    if 'base-memory' in model_types:
        return 'base-memory'
    if 'base' in model_types:
        return 'base'
    if 'tiny' in model_types:
        return 'tiny'
    raise ValueError(f"No valid model type found in {model_types}")


def strip_compression_suffix(filename: str) -> str:
    if filename.endswith(".gz"):
        return filename[:-3]
    return filename


def load_json_cache(cache_file: str, default):
    if os.path.exists(cache_file):
        with open(cache_file) as f:
            return json.load(f)
    return default


def save_json_cache(cache_file: str, data) -> None:
    os.makedirs(os.path.dirname(cache_file), exist_ok=True)
    with open(cache_file, "w") as f:
        json.dump(data, f, indent=2, sort_keys=True)


async def fetch_manifest() -> dict:
    cached = load_json_cache(MODELS_MANIFEST_CACHE, None)
    if cached is not None:
        return cached

    async with aiohttp.ClientSession() as session:
        async with session.get(MODELS_MANIFEST_URL) as resp:
            resp.raise_for_status()
            manifest = await resp.json()

    save_json_cache(MODELS_MANIFEST_CACHE, manifest)
    return manifest


def select_best_entry(entries: list[dict]) -> dict:
    return max(entries, key=lambda entry: MODEL_TYPE_PRIORITY.get(entry.get("architecture", ""), 0))


def build_pair_files(manifest: dict) -> dict[tuple[str, str], dict]:
    pair_files = {}

    for pair_key, entries in manifest["models"].items():
        best_entry = select_best_entry(entries)
        src, tgt = best_entry["sourceLanguage"], best_entry["targetLanguage"]

        if src not in LANGUAGE_NAMES or tgt not in LANGUAGE_NAMES:
            print(f"Skipping unsupported pair {pair_key}: {src}->{tgt}")
            continue

        files = {}
        for manifest_file_type, file_type in MANIFEST_FILE_TYPES.items():
            file_info = best_entry["files"].get(manifest_file_type)
            if file_info is None:
                continue

            path = file_info["path"]
            files[file_type] = {
                "name": strip_compression_suffix(os.path.basename(path)),
                "size": 0,
                "path": path,
                "fileType": file_type,
                "modelType": best_entry["architecture"],
            }

        pair_files[(src, tgt)] = files

    return pair_files


def collect_model_paths(pair_files: dict[tuple[str, str], dict]) -> set[str]:
    return {entry["path"] for files in pair_files.values() for entry in files.values()}


async def fetch_file_size(session: aiohttp.ClientSession, base_url: str, path: str, semaphore: asyncio.Semaphore) -> tuple[str, int]:
    url = f"{base_url.rstrip('/')}/{path}"
    async with semaphore:
        try:
            async with session.head(url) as resp:
                if resp.status == 200:
                    return path, int(resp.headers.get("Content-Length", 0))
                print(f"Warning: HEAD failed for {path}: {resp.status}")
        except Exception as exc:
            print(f"Warning: HEAD error for {path}: {exc}")
    return path, 0


async def get_model_sizes(base_url: str, pair_files: dict[tuple[str, str], dict]) -> dict[str, int]:
    cache = load_json_cache(MODEL_SIZE_CACHE, {})
    paths = sorted(collect_model_paths(pair_files))
    missing_paths = [path for path in paths if path not in cache]

    if missing_paths:
        timeout = aiohttp.ClientTimeout(total=60)
        semaphore = asyncio.Semaphore(16)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            tasks = [fetch_file_size(session, base_url, path, semaphore) for path in missing_paths]
            for path, size in await asyncio.gather(*tasks):
                cache[path] = size
        save_json_cache(MODEL_SIZE_CACHE, cache)

    return {path: int(cache.get(path, 0)) for path in paths}


def apply_model_sizes(pair_files: dict[tuple[str, str], dict], model_sizes: dict[str, int]) -> None:
    for files in pair_files.values():
        for entry in files.values():
            entry["size"] = model_sizes.get(entry["path"], 0)


def build_language_data(pair_files: dict[tuple[str, str], dict]) -> tuple[dict, dict, set]:
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
            print(f"Warning: incomplete files for {src}->{tgt}, skipping")
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

    bidirectional = set(from_english.keys()) & set(to_english.keys())
    from_english = {lang: from_english[lang] for lang in bidirectional}
    to_english = {lang: to_english[lang] for lang in bidirectional}
    all_languages = bidirectional | {"en"}

    return from_english, to_english, all_languages


async def get_tessdata_sizes(languages: set[str]) -> dict[str, int]:
    sizes = {}
    async with aiohttp.ClientSession() as session:
        for lang_code in sorted(languages):
            tess_name = TESSERACT_LANGUAGE_MAPPINGS.get(lang_code)
            if tess_name is None:
                continue

            url = f"{TESSERACT_BASE_URL}/{tess_name}.traineddata"
            try:
                async with session.head(url) as resp:
                    if resp.status == 200:
                        sizes[lang_code] = int(resp.headers.get("Content-Length", 0))
                    else:
                        print(f"Warning: tessdata HEAD failed for {lang_code}: {resp.status}")
                        sizes[lang_code] = 0
            except Exception as exc:
                print(f"Warning: tessdata HEAD error for {lang_code}: {exc}")
                sizes[lang_code] = 0
    return sizes


def compute_language_size(lang_code: str, from_english: dict, to_english: dict, tessdata_sizes: dict[str, int]) -> int:
    total = 0
    seen = set()

    for mapping in (from_english.get(lang_code), to_english.get(lang_code)):
        if mapping is None:
            continue
        for file_entry in mapping.values():
            if file_entry["name"] in seen:
                continue
            total += file_entry["size"]
            seen.add(file_entry["name"])

    total += tessdata_sizes.get(lang_code, 0)
    return total


def format_model_file(entry: dict) -> str:
    return f'ModelFile("{entry["name"]}", {entry["size"]}, "{entry["path"]}")'


def generate_kotlin(
    from_english: dict,
    to_english: dict,
    all_languages: set[str],
    tessdata_sizes: dict[str, int],
    translation_models_base_url: str,
) -> str:
    language_entries = []
    for lang_code in sorted(all_languages):
        if lang_code not in LANGUAGE_NAMES:
            continue
        if lang_code != "en" and lang_code not in from_english:
            continue

        lang_name = LANGUAGE_NAMES[lang_code]
        tess_name = TESSERACT_LANGUAGE_MAPPINGS[lang_code]
        script = LANGUAGE_SCRIPTS[lang_code]
        enum_name = sanitize_enum_name(lang_name)
        tess_size = tessdata_sizes.get(lang_code, 0)
        total_size = compute_language_size(lang_code, from_english, to_english, tessdata_sizes)
        short_name = SHORT_DISPLAY_NAMES.get(lang_code, lang_name)
        language_entries.append(
            f'  {enum_name}("{lang_code}", "{tess_name}", "{lang_name}", "{short_name}", "{script}", {total_size}, {tess_size})'
        )

    from_entries = []
    for lang_code in sorted(from_english.keys()):
        lang_enum = f'Language.{sanitize_enum_name(LANGUAGE_NAMES[lang_code])}'
        entry = from_english[lang_code]
        from_entries.append(
            f'  {lang_enum} to LanguageFiles({format_model_file(entry["model"])}, '
            f'{format_model_file(entry["srcVocab"])}, {format_model_file(entry["tgtVocab"])}, '
            f'{format_model_file(entry["lex"])})'
        )

    to_entries = []
    for lang_code in sorted(to_english.keys()):
        lang_enum = f'Language.{sanitize_enum_name(LANGUAGE_NAMES[lang_code])}'
        entry = to_english[lang_code]
        to_entries.append(
            f'  {lang_enum} to LanguageFiles({format_model_file(entry["model"])}, '
            f'{format_model_file(entry["srcVocab"])}, {format_model_file(entry["tgtVocab"])}, '
            f'{format_model_file(entry["lex"])})'
        )

    language_lines = ",\n".join(sorted(language_entries))
    from_lines = ",\n".join(from_entries)
    to_lines = ",\n".join(to_entries)
    extra_files_lines = '  Language.JAPANESE to listOf("mucab.bin")'

    return f"""/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

// This file was generated by `generate.py`. Do not edit.

package dev.davidv.translator

object Constants {{
  const val DICT_VERSION = {DICT_VERSION}
  const val DEFAULT_TRANSLATION_MODELS_BASE_URL =
    "{translation_models_base_url}"
  const val DEFAULT_TESSERACT_MODELS_BASE_URL = "{TESSERACT_BASE_URL}"
  const val DEFAULT_DICTIONARY_BASE_URL = "{DICTIONARY_BASE_URL}"
}}

data class ModelFile(
  val name: String,
  val size: Int,
  val path: String,
)

enum class Language(val code: String, val tessName: String, val displayName: String, val shortDisplayName: String, val script: String, val sizeBytes: Int, val tessdataSizeBytes: Int) {{
{language_lines};

  val tessFilename: String
    get() = "$tessName.traineddata"
}}

data class LanguageFiles(
  val model: ModelFile,
  val srcVocab: ModelFile,
  val tgtVocab: ModelFile,
  val lex: ModelFile,
) {{
  fun allFiles(): List<ModelFile> = listOf(model, srcVocab, tgtVocab, lex).distinctBy {{ it.name }}
}}

val fromEnglishFiles = mapOf(
{from_lines}
)

val toEnglishFiles = mapOf(
{to_lines}
)

val extraFiles = mapOf(
{extra_files_lines}
)"""


async def main():
    print("Fetching model manifest...")
    manifest = await fetch_manifest()
    base_url = manifest["baseUrl"].rstrip("/")
    print(f"Manifest generated at {manifest['generated']}")

    pair_files = build_pair_files(manifest)
    print(f"Built file map for {len(pair_files)} language pairs")

    print("Fetching model file sizes...")
    model_sizes = await get_model_sizes(base_url, pair_files)
    apply_model_sizes(pair_files, model_sizes)

    from_english, to_english, all_languages = build_language_data(pair_files)
    print(f"Languages: {len(all_languages)}, from_english: {len(from_english)}, to_english: {len(to_english)}")

    print("Fetching tessdata sizes...")
    tessdata_sizes = await get_tessdata_sizes(all_languages)

    kotlin_code = generate_kotlin(from_english, to_english, all_languages, tessdata_sizes, base_url)

    output_file = "app/src/main/java/dev/davidv/translator/Language.kt"
    with open(output_file, "w") as f:
        f.write(kotlin_code)

    print(f"Generated {output_file}")


if __name__ == "__main__":
    asyncio.run(main())
