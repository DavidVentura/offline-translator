import json
import time
from collections import defaultdict
from pathlib import Path, PurePosixPath
from zipfile import ZipFile


def load_json(path: Path) -> dict:
    with path.open() as f:
        return json.load(f)


def make_file(
    *,
    name: str,
    size_bytes: int,
    install_path: str,
    url: str,
    role: str,
    priority: int = 0,
    source_path: str | None = None,
) -> dict:
    payload = {
        "name": name,
        "sizeBytes": size_bytes,
        "installPath": install_path,
        "url": url,
        "role": role,
        "priority": priority,
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


DICTIONARY_DEPENDENCY_OVERRIDES = {}


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


# p1 keeps the legacy flat `bin/<name>` so models already installed at that path
# still resolve; p2+ upgrade variants share the same file names (same pack infix)
# so they MUST live in a distinct dir or they collide with the flat p1 file. They
# are namespaced by `<pair>/<version>` from the source path. The runtime loads
# whichever file the planner resolves per role (highest priority present), so the
# split path is invisible to it — no on-startup migration needed.
def translation_install_path(name: str, source_path: str, priority: int) -> str:
    if priority < 2:
        return f"bin/{name}"
    parts = source_path.split("/")  # models/<pair>/<version>/exported/<file>
    if len(parts) < 4:
        return f"bin/{name}"
    return f"bin/{parts[1]}/{parts[2]}/{name}"


def translation_pack_files(direction: dict, translation_base_url: str) -> list[dict]:
    def entry(file_info: dict, role: str, priority: int) -> dict:
        return make_file(
            name=file_info["name"],
            size_bytes=int(file_info["sizeBytes"]),
            install_path=translation_install_path(
                file_info["name"], file_info["path"], priority
            ),
            url=f"{translation_base_url}/{file_info['path']}",
            role=role,
            priority=priority,
            source_path=file_info["path"],
        )

    files = []
    for variant in direction["variants"]:
        priority = variant["priority"]
        files.append(entry(variant["model"], "model", priority))
        files.append(entry(variant["lex"], "lex", priority))
        src_vocab = variant["srcVocab"]
        tgt_vocab = variant["tgtVocab"]
        if src_vocab["name"] == tgt_vocab["name"]:
            files.append(entry(src_vocab, "vocab", priority))
        else:
            files.append(entry(src_vocab, "srcVocab", priority))
            files.append(entry(tgt_vocab, "tgtVocab", priority))
    return dedupe_files_by_install_path(files)


def convert_v1_to_v2(language_index: dict, dictionary_index: dict) -> dict:
    translation_base_url = language_index["translationModelsBaseUrl"].rstrip("/")
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
                "experimental": bool(direction["experimental"]),
                "files": translation_pack_files(direction, translation_base_url),
                "dependsOn": [],
            }
            add_language_asset_ref(language_entry, "translate", pack_id)
            add_language_asset_ref(languages_v2[from_code], "translate", pack_id)
            add_language_asset_ref(languages_v2[to_code], "translate", pack_id)

        for extra_file in lang.get("extraFiles", []):
            support_pack_id = make_support_pack_id(code, extra_file)
            kind = "mucab" if extra_file == "mucab.bin" else "file"
            packs[support_pack_id] = {
                "feature": "support",
                "language": code,
                "kind": kind,
                "files": [
                    make_file(
                        name=extra_file,
                        size_bytes=0,
                        install_path=f"bin/{extra_file}",
                        url=f"{dictionary_base_url}/extra/{extra_file}",
                        role=kind,
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
                    role="dictionary",
                    source_path=f"{dictionary_version}/{dict_info['filename']}",
                )
            ],
            "dependsOn": DICTIONARY_DEPENDENCY_OVERRIDES.get(dictionary_code, []),
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
        "formatVersion": 6,
        "generatedAt": int(time.time()),
        "translationModelsBaseUrl": translation_base_url,
        "dictionaryBaseUrl": dictionary_base_url,
        "dictionaryVersion": dictionary_version,
        "sources": {
            "languageIndexVersion": int(language_index["version"]),
            "languageIndexUpdatedAt": int(language_index["updatedAt"]),
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

        tts = entry.get("tts")
        if tts is not None:
            regions = tts["regions"]
            if not regions:
                raise ValueError(f"{language_code}: TTS entry without regions")
            if tts["defaultRegion"] not in regions:
                raise ValueError(f"{language_code}: default TTS region {tts['defaultRegion']} has no voices")
            for region_code, region in regions.items():
                if not region["voices"]:
                    raise ValueError(f"{language_code}: TTS region {region_code} without voices")
                for pack_id in region["voices"]:
                    if pack_id not in pack_ids:
                        raise ValueError(f"{language_code}: missing TTS voice pack {pack_id}")

    for pack_id, pack in packs.items():
        if pack["feature"] == "translation":
            if pack["from"] not in language_codes or pack["to"] not in language_codes:
                raise ValueError(f"{pack_id}: unknown translation endpoints")
        if "language" in pack and pack["language"] not in language_codes:
            # PPOCR OCR packs use the `language` slot to carry a script slug
            # (or "_detector"), not a real language code — by design, one rec
            # model covers many languages of the same script.
            if not (pack.get("feature") == "ocr" and pack.get("engine") == "ppocr"):
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
            # The app drops a pack whose archive has no marker or whose marker has no archive.
            if ("archiveFormat" in file_info) != ("installMarkerPath" in file_info):
                raise ValueError(f"{pack_id}: {install_path} needs both archiveFormat and installMarkerPath, or neither")
            if "archiveFormat" in file_info:
                archive_root_dir(file_info)


# The app installs an archive by moving its single top-level directory into place under
# extractTo, where the marker lives; it rejects archives of any other shape.
def archive_root_dir(file_info: dict) -> str:
    extract_to = file_info["extractTo"]
    marker_path = file_info["installMarkerPath"]
    extract_parts = list(PurePosixPath(extract_to).parts)
    marker_dir = list(PurePosixPath(marker_path).parts)[:-1]
    escapes = any(part in ("..", "/") for part in extract_parts + marker_dir)
    if escapes or marker_dir[:-1] != extract_parts or len(marker_dir) != len(extract_parts) + 1:
        raise ValueError(
            f"{file_info['installPath']}: install marker {marker_path} must sit in a directory directly under {extract_to}"
        )
    return marker_dir[-1]


def validate_archive_contents(archive_path: Path, file_info: dict) -> None:
    root_dir = archive_root_dir(file_info)
    with ZipFile(archive_path) as archive:
        outside = [
            name
            for name in archive.namelist()
            if PurePosixPath(name).parts[:1] != (root_dir,) or ".." in PurePosixPath(name).parts
        ]
    if outside:
        raise ValueError(f"{archive_path}: entries outside {root_dir}/: {outside[:5]}")
