#!/usr/bin/env python3
import argparse
import json
import time
from collections import defaultdict
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a v2 language/asset manifest from the current bundled v1 indices.",
    )
    parser.add_argument(
        "--language-index",
        default="app/src/main/assets/language_index.json",
        help="Path to the current v1 language index JSON.",
    )
    parser.add_argument(
        "--dictionary-index",
        default="app/src/main/assets/dictionary_index.json",
        help="Path to the current dictionary index JSON.",
    )
    parser.add_argument(
        "--output",
        default="index_v2.json",
        help="Path for the generated v2 index.",
    )
    return parser.parse_args()


def load_json(path: Path) -> dict:
    with path.open() as f:
        return json.load(f)


def make_file(
    *,
    name: str,
    size_bytes: int,
    install_path: str,
    url: str,
    source_path: str | None = None,
) -> dict:
    payload = {
        "name": name,
        "sizeBytes": size_bytes,
        "installPath": install_path,
        "url": url,
    }
    if source_path is not None:
        payload["sourcePath"] = source_path
    return payload


def dedupe_files_by_install_path(files: list[dict]) -> list[dict]:
    deduped = []
    seen = set()
    for file_info in files:
        install_path = file_info["installPath"]
        if install_path in seen:
            continue
        seen.add(install_path)
        deduped.append(file_info)
    return deduped


def make_translation_pack_id(src: str, tgt: str) -> str:
    return f"translate-{src}-{tgt}"


def make_ocr_pack_id(engine: str, language_code: str) -> str:
    return f"ocr-{engine}-{language_code}"


def make_dictionary_pack_id(dictionary_code: str) -> str:
    return f"dict-{dictionary_code}"


def make_support_pack_id(language_code: str, file_name: str) -> str:
    stem = Path(file_name).stem.replace(".", "-")
    return f"support-{language_code}-{stem}"


def language_meta_entry(lang: dict) -> dict:
    return {
        "code": lang["code"],
        "name": lang["name"],
        "shortName": lang["shortName"],
        "script": lang["script"],
    }


def add_language_asset_ref(language_entry: dict, feature: str, ref) -> None:
    assets = language_entry["assets"]
    if feature == "translate":
        bucket = assets.setdefault("translate", [])
        if ref not in bucket:
            bucket.append(ref)
        return
    if feature == "support":
        bucket = assets.setdefault("support", [])
        if ref not in bucket:
            bucket.append(ref)
        return
    assets[feature] = ref


def convert_v1_to_v2(language_index: dict, dictionary_index: dict) -> dict:
    translation_base_url = language_index["translationModelsBaseUrl"].rstrip("/")
    tesseract_base_url = language_index["tesseractModelsBaseUrl"].rstrip("/")
    dictionary_base_url = language_index["dictionaryBaseUrl"].rstrip("/")
    dictionary_version = language_index["dictionaryVersion"]

    languages_v2 = {}
    packs = {}
    dictionary_consumers = defaultdict(list)

    for lang in sorted(language_index["languages"], key=lambda item: item["code"]):
        code = lang["code"]
        languages_v2[code] = {
            "meta": language_meta_entry(lang),
            "assets": {},
        }
        dictionary_consumers[lang["dictionaryCode"]].append(code)

    for lang in sorted(language_index["languages"], key=lambda item: item["code"]):
        code = lang["code"]
        language_entry = languages_v2[code]

        for direction_key, from_code, to_code in (
            ("toEnglish", code, "en"),
            ("fromEnglish", "en", code),
        ):
            direction = lang.get(direction_key)
            if direction is None:
                continue

            pack_id = make_translation_pack_id(from_code, to_code)
            packs[pack_id] = {
                "feature": "translation",
                "from": from_code,
                "to": to_code,
                "files": dedupe_files_by_install_path([
                    make_file(
                        name=file_info["name"],
                        size_bytes=int(file_info["sizeBytes"]),
                        install_path=f"bin/{file_info['name']}",
                        url=f"{translation_base_url}/{file_info['path']}",
                        source_path=file_info["path"],
                    )
                    for file_info in (
                        direction["model"],
                        direction["srcVocab"],
                        direction["tgtVocab"],
                        direction["lex"],
                    )
                ]),
                "dependsOn": [],
            }
            add_language_asset_ref(language_entry, "translate", pack_id)
            add_language_asset_ref(languages_v2[from_code], "translate", pack_id)
            add_language_asset_ref(languages_v2[to_code], "translate", pack_id)

        ocr_pack_id = make_ocr_pack_id("tesseract", code)
        packs[ocr_pack_id] = {
            "feature": "ocr",
            "engine": "tesseract",
            "language": code,
            "files": [
                make_file(
                    name=f"{lang['tessName']}.traineddata",
                    size_bytes=int(lang["tessdataSizeBytes"]),
                    install_path=f"tesseract/tessdata/{lang['tessName']}.traineddata",
                    url=f"{tesseract_base_url}/{lang['tessName']}.traineddata",
                    source_path=f"{lang['tessName']}.traineddata",
                )
            ],
            "dependsOn": [] if code == "en" else [make_ocr_pack_id("tesseract", "en")],
        }
        add_language_asset_ref(language_entry, "ocr", {"tesseract": ocr_pack_id})

        for extra_file in lang.get("extraFiles", []):
            support_pack_id = make_support_pack_id(code, extra_file)
            packs[support_pack_id] = {
                "feature": "support",
                "language": code,
                "kind": "mucab" if extra_file == "mucab.bin" else "file",
                "files": [
                    make_file(
                        name=extra_file,
                        size_bytes=0,
                        install_path=f"bin/{extra_file}",
                        url=f"{dictionary_base_url}/extra/{extra_file}",
                        source_path=f"extra/{extra_file}",
                    )
                ],
                "dependsOn": [],
            }
            add_language_asset_ref(language_entry, "support", support_pack_id)

    for dictionary_code, consumer_codes in sorted(dictionary_consumers.items()):
        dict_info = dictionary_index["dictionaries"].get(dictionary_code)
        if dict_info is None:
            continue

        pack_id = make_dictionary_pack_id(dictionary_code)
        packs[pack_id] = {
            "feature": "dictionary",
            "dictionaryCode": dictionary_code,
            "languages": sorted(consumer_codes),
            "files": [
                make_file(
                    name=dict_info["filename"],
                    size_bytes=int(dict_info["size"]),
                    install_path=f"dictionaries/{dict_info['filename']}",
                    url=f"{dictionary_base_url}/{dictionary_version}/{dict_info['filename']}",
                    source_path=f"{dictionary_version}/{dict_info['filename']}",
                )
            ],
            "dependsOn": [],
            "metadata": {
                "date": int(dict_info["date"]),
                "type": dict_info["type"],
                "wordCount": int(dict_info["word_count"]),
            },
        }
        for language_code in consumer_codes:
            add_language_asset_ref(languages_v2[language_code], "dictionary", pack_id)

    normalize_language_assets(languages_v2)
    validate_manifest(languages_v2, packs)

    return {
        "formatVersion": 2,
        "generatedAt": int(time.time()),
        "translationModelsBaseUrl": translation_base_url,
        "tesseractModelsBaseUrl": tesseract_base_url,
        "dictionaryBaseUrl": dictionary_base_url,
        "dictionaryVersion": dictionary_version,
        "sources": {
            "languageIndexPath": "app/src/main/assets/language_index.json",
            "languageIndexVersion": int(language_index["version"]),
            "languageIndexUpdatedAt": int(language_index["updatedAt"]),
            "dictionaryIndexPath": "app/src/main/assets/dictionary_index.json",
            "dictionaryIndexVersion": int(dictionary_index["version"]),
            "dictionaryIndexUpdatedAt": int(dictionary_index["updated_at"]),
        },
        "languages": languages_v2,
        "packs": {pack_id: packs[pack_id] for pack_id in sorted(packs)},
    }


def normalize_language_assets(languages_v2: dict) -> None:
    for entry in languages_v2.values():
        assets = entry["assets"]
        if "translate" in assets:
            assets["translate"] = sorted(set(assets["translate"]))
        if "support" in assets:
            assets["support"] = sorted(set(assets["support"]))


def validate_manifest(languages: dict, packs: dict) -> None:
    language_codes = set(languages)
    pack_ids = set(packs)

    for language_code, entry in languages.items():
        assets = entry["assets"]

        for pack_id in assets.get("translate", []):
            if pack_id not in pack_ids:
                raise ValueError(f"{language_code}: missing translation pack {pack_id}")

        dictionary_pack = assets.get("dictionary")
        if dictionary_pack is not None and dictionary_pack not in pack_ids:
            raise ValueError(f"{language_code}: missing dictionary pack {dictionary_pack}")

        ocr_assets = assets.get("ocr")
        if ocr_assets is not None:
            for pack_id in ocr_assets.values():
                if pack_id not in pack_ids:
                    raise ValueError(f"{language_code}: missing OCR pack {pack_id}")

        for pack_id in assets.get("support", []):
            if pack_id not in pack_ids:
                raise ValueError(f"{language_code}: missing support pack {pack_id}")

    for pack_id, pack in packs.items():
        if pack["feature"] == "translation":
            if pack["from"] not in language_codes or pack["to"] not in language_codes:
                raise ValueError(f"{pack_id}: unknown translation endpoints")
        if "language" in pack and pack["language"] not in language_codes:
            raise ValueError(f"{pack_id}: unknown language {pack['language']}")
        if "languages" in pack:
            unknown = sorted(set(pack["languages"]) - language_codes)
            if unknown:
                raise ValueError(f"{pack_id}: unknown consumer languages {unknown}")
        for dep in pack.get("dependsOn", []):
            if dep not in pack_ids:
                raise ValueError(f"{pack_id}: unknown dependency {dep}")

        seen_install_paths = set()
        for file_info in pack["files"]:
            install_path = file_info["installPath"]
            if install_path in seen_install_paths:
                raise ValueError(f"{pack_id}: duplicate installPath {install_path}")
            seen_install_paths.add(install_path)


def main() -> None:
    args = parse_args()
    language_index_path = Path(args.language_index)
    dictionary_index_path = Path(args.dictionary_index)
    output_path = Path(args.output)

    language_index = load_json(language_index_path)
    dictionary_index = load_json(dictionary_index_path)
    manifest_v2 = convert_v1_to_v2(language_index, dictionary_index)

    output_path.write_text(json.dumps(manifest_v2, indent=2, sort_keys=False) + "\n")
    print(f"Wrote {output_path}")
    print(f"languages={len(manifest_v2['languages'])} packs={len(manifest_v2['packs'])}")


if __name__ == "__main__":
    main()
