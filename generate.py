#!/usr/bin/env python3
import os
import sys
import json
import asyncio
import aiohttp
from typing import Dict, Set, Tuple

REMOTE_SETTINGS_URL = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records"
CDN_BASE_URL = "https://firefox-settings-attachments.cdn.mozilla.net"
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
    'mt': 'Maltese',
    'nb': 'Norwegian Bokmål',
    'nl': 'Dutch',
    'nn': 'Norwegian Nynorsk',
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
    'tr': 'Turkish',
    'uk': 'Ukrainian',
    'vi': 'Vietnamese',
    'zh': 'Chinese'
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
    'mt': 'mlt',
    'nb': 'nor',
    'nl': 'nld',
    'nn': 'nor',
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
    'tr': 'tur',
    'uk': 'ukr',
    'vi': 'vie',
    'zh': 'chi_sim',
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
    'mt': 'Latin',
    'nb': 'Latin',
    'nl': 'Latin',
    'nn': 'Latin',
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
    'tr': 'Latin',
    'uk': 'Cyrillic',
    'vi': 'Latin',
    'zh': 'Han',
}

PAIR_CODE_MAPPING = {
    'enzh-Hans': 'enzh',
    'enzh-Hant': 'enzh',
    'zh-Hansen': 'zhen',
    'zh-Hanten': 'zhen',
    'ensq': 'ensq',
}

def normalize_pair_code(pair_code: str) -> str | None:
    if pair_code in PAIR_CODE_MAPPING:
        return PAIR_CODE_MAPPING[pair_code]
    if len(pair_code) == 4:
        return pair_code
    return None


def parse_language_pair(pair: str) -> Tuple[str, str]:
    assert len(pair) == 4, f"Language pair '{pair}' must be exactly 4 characters"
    return pair[:2], pair[2:]


def get_best_model_type(model_types: Set[str]) -> str:
    if 'base-memory' in model_types:
        return 'base-memory'
    elif 'base' in model_types:
        return 'base'
    elif 'tiny' in model_types:
        return 'tiny'
    else:
        raise ValueError(f"No valid model type found in {model_types}")


async def fetch_records() -> list[dict]:
    cache_file = "data/remote_settings_v1.json"
    if os.path.exists(cache_file):
        with open(cache_file) as f:
            return json.load(f)

    async with aiohttp.ClientSession() as session:
        async with session.get(REMOTE_SETTINGS_URL) as resp:
            data = await resp.json()
            records = data["data"]

    os.makedirs("data", exist_ok=True)
    with open(cache_file, 'w') as f:
        json.dump(records, f, indent=2)

    return records


def build_model_index(records: list[dict]) -> dict:
    index = {}
    for record in records:
        from_lang = record.get("fromLang")
        to_lang = record.get("toLang")
        if not from_lang or not to_lang:
            continue

        raw_pair = f"{from_lang}{to_lang}"
        pair = normalize_pair_code(raw_pair)
        if pair is None:
            continue

        name = record.get("name", "")
        attachment = record.get("attachment", {})
        location = attachment.get("location", "")
        size = attachment.get("size", 0)
        file_type = record.get("fileType", "")
        version = record.get("version", "")

        if not location or not name:
            continue

        key = f"{pair}/{name}"

        existing = index.get(key)
        if existing and existing["version"] >= version:
            continue

        index[key] = {
            "name": name,
            "size": size,
            "path": location,
            "fileType": file_type,
            "fromLang": from_lang,
            "toLang": to_lang,
            "version": version,
        }

    return index


def build_language_data(index: dict) -> Tuple[dict, dict, set]:
    from_english = {}
    to_english = {}
    all_languages = set()

    pair_files = {}
    for key, entry in index.items():
        pair = key.split("/")[0]
        if pair not in pair_files:
            pair_files[pair] = {}
        pair_files[pair][entry["fileType"]] = entry

    for pair, files in pair_files.items():
        if "model" not in files:
            continue

        src, tgt = parse_language_pair(pair)
        if src != 'en' and tgt != 'en':
            continue

        all_languages.add(src)
        all_languages.add(tgt)

        model = files["model"]
        lex = files.get("lex")
        vocab = files.get("vocab")
        src_vocab = files.get("srcvocab", vocab)
        tgt_vocab = files.get("trgvocab", vocab)

        if not all([model, lex, src_vocab, tgt_vocab]):
            print(f"Warning: incomplete files for {pair}, skipping")
            continue

        entry = {
            "model": model,
            "srcVocab": src_vocab,
            "tgtVocab": tgt_vocab,
            "lex": lex,
        }

        if src == 'en':
            from_english[tgt] = entry
        else:
            to_english[src] = entry

    bidirectional = set(from_english.keys()) & set(to_english.keys())
    from_english = {k: v for k, v in from_english.items() if k in bidirectional}
    to_english = {k: v for k, v in to_english.items() if k in bidirectional}
    all_languages = bidirectional | {'en'}

    return from_english, to_english, all_languages


async def get_tessdata_sizes(languages: set) -> dict:
    sizes = {}
    async with aiohttp.ClientSession() as session:
        for lang_code in sorted(languages):
            if lang_code not in TESSERACT_LANGUAGE_MAPPINGS:
                continue
            tess_name = TESSERACT_LANGUAGE_MAPPINGS[lang_code]
            filename = f"{tess_name}.traineddata"
            url = f"{TESSERACT_BASE_URL}/{filename}"
            try:
                async with session.head(url) as resp:
                    if resp.status == 200:
                        sizes[lang_code] = int(resp.headers.get("Content-Length", 0))
                    else:
                        print(f"Warning: tessdata HEAD failed for {lang_code}: {resp.status}")
                        sizes[lang_code] = 0
            except Exception as e:
                print(f"Warning: tessdata HEAD error for {lang_code}: {e}")
                sizes[lang_code] = 0
    return sizes


def compute_language_size(lang_code: str, from_english: dict, to_english: dict, tessdata_sizes: dict) -> int:
    total = 0
    if lang_code in from_english:
        seen = set()
        for file_entry in from_english[lang_code].values():
            name = file_entry["name"]
            if name not in seen:
                total += file_entry["size"]
                seen.add(name)
    if lang_code in to_english:
        seen = set()
        for file_entry in to_english[lang_code].values():
            name = file_entry["name"]
            if name not in seen:
                total += file_entry["size"]
                seen.add(name)
    total += tessdata_sizes.get(lang_code, 0)
    return total


def format_model_file(entry: dict) -> str:
    return f'ModelFile("{entry["name"]}", {entry["size"]}, "{entry["path"]}")'


def generate_kotlin(from_english: dict, to_english: dict, all_languages: set, tessdata_sizes: dict) -> str:
    language_entries = []
    for lang_code in sorted(all_languages):
        if lang_code not in LANGUAGE_NAMES:
            continue
        if lang_code != 'en' and lang_code not in from_english:
            continue
        lang_name = LANGUAGE_NAMES[lang_code]
        tess_name = TESSERACT_LANGUAGE_MAPPINGS[lang_code]
        script = LANGUAGE_SCRIPTS[lang_code]
        enum_name = lang_name.upper().replace(' ', '_').replace('Å', 'A')
        tess_size = tessdata_sizes.get(lang_code, 0)
        total_size = compute_language_size(lang_code, from_english, to_english, tessdata_sizes)
        language_entries.append(f'  {enum_name}("{lang_code}", "{tess_name}", "{lang_name}", "{script}", {total_size}, {tess_size})')

    language_entries = sorted(language_entries)

    from_entries = []
    for lang_code in sorted(from_english.keys()):
        lang_name = LANGUAGE_NAMES[lang_code]
        lang_enum = f'Language.{lang_name.upper().replace(" ", "_").replace("Å", "A")}'
        e = from_english[lang_code]
        from_entries.append(
            f'  {lang_enum} to LanguageFiles({format_model_file(e["model"])}, '
            f'{format_model_file(e["srcVocab"])}, {format_model_file(e["tgtVocab"])}, '
            f'{format_model_file(e["lex"])})'
        )

    to_entries = []
    for lang_code in sorted(to_english.keys()):
        lang_name = LANGUAGE_NAMES[lang_code]
        lang_enum = f'Language.{lang_name.upper().replace(" ", "_").replace("Å", "A")}'
        e = to_english[lang_code]
        to_entries.append(
            f'  {lang_enum} to LanguageFiles({format_model_file(e["model"])}, '
            f'{format_model_file(e["srcVocab"])}, {format_model_file(e["tgtVocab"])}, '
            f'{format_model_file(e["lex"])})'
        )

    language_lines = ",\n".join(language_entries)
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
    "{CDN_BASE_URL}"
  const val DEFAULT_TESSERACT_MODELS_BASE_URL = "{TESSERACT_BASE_URL}"
  const val DEFAULT_DICTIONARY_BASE_URL = "{DICTIONARY_BASE_URL}"
}}

data class ModelFile(
  val name: String,
  val size: Int,
  val path: String,
)

enum class Language(val code: String, val tessName: String, val displayName: String, val script: String, val sizeBytes: Int, val tessdataSizeBytes: Int) {{
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
    print("Fetching records from Remote Settings API...")
    records = await fetch_records()
    print(f"Got {len(records)} records")

    index = build_model_index(records)
    print(f"Built index with {len(index)} entries")

    from_english, to_english, all_languages = build_language_data(index)
    print(f"Languages: {len(all_languages)}, from_english: {len(from_english)}, to_english: {len(to_english)}")

    print("Fetching tessdata sizes...")
    tessdata_sizes = await get_tessdata_sizes(all_languages)

    kotlin_code = generate_kotlin(from_english, to_english, all_languages, tessdata_sizes)

    output_file = "app/src/main/java/dev/davidv/translator/Language.kt"
    with open(output_file, 'w') as f:
        f.write(kotlin_code)

    print(f"Generated {output_file}")


if __name__ == "__main__":
    asyncio.run(main())
