#!/usr/bin/env python3

import argparse
import json
import time
from collections import defaultdict
from copy import deepcopy
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


PIPER_BASE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main"
KOKORO_BASE_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0"
TTS_BASE_URL = "https://translator.davidv.dev/tts"
TTS_VERSION = 1
KOKORO_SHARED_PACK_ID = "tts-kokoro-v1.0-core"
CORE_ESPEAK_FILES = ("phondata", "phonindex", "phontab", "intonations")
QUALITY_PRIORITY = {
    "medium": 0,
    "low": 1,
    "x_low": 2,
    "high": 3,
}
DEFAULT_REGION_OVERRIDES = {
    "en": "US",
    "es": "ES",
    "nl": "NL",
    "pt": "BR",
}
APP_LANGUAGE_OVERRIDES = {
    "zh_CN": "zh",
    "zh_HK": "zh_hant",
    "yue_HK": "zh_hant",
}
ESPEAK_DICT_OVERRIDES = {
    "zh": "cmn",
    "zh_hant": "yue",
}
EXTRA_TTS_VOICES = {
    # External Polish Piper voice metadata source:
    # https://huggingface.co/WitoldG/polish_piper_models/resolve/main/pl_PL-jarvis_wg_glos-medium.onnx.json
    "pl_PL-jarvis_wg_glos-medium": {
        "engine": "piper",
        "key": "pl_PL-jarvis_wg_glos-medium",
        "name": "jarvis_wg_glos",
        "language": {
            "code": "pl_PL",
            "family": "pl",
            "region": "PL",
            "name_native": "Polski",
            "name_english": "Polish",
            "country_english": "Poland",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "pl/pl_PL/jarvis_wg_glos/medium/pl_PL-jarvis_wg_glos-medium.onnx": {
                "size_bytes": 63516050,
                "url": "https://huggingface.co/WitoldG/polish_piper_models/resolve/main/pl_PL-jarvis_wg_glos-medium.onnx",
            },
            "pl/pl_PL/jarvis_wg_glos/medium/pl_PL-jarvis_wg_glos-medium.onnx.json": {
                "size_bytes": 7104,
                "url": "https://huggingface.co/WitoldG/polish_piper_models/resolve/main/pl_PL-jarvis_wg_glos-medium.onnx.json",
            },
        },
        "aliases": [],
    },
    # External Hebrew Piper voice metadata source:
    # https://huggingface.co/notmax123/piper-medium-heb/resolve/main/model.config.json
    "he_IL-community_female-medium": {
        "engine": "piper",
        "key": "he_IL-community_female-medium",
        "name": "community_female",
        "language": {
            "code": "he_IL",
            "family": "he",
            "region": "IL",
            "name_native": "עברית",
            "name_english": "Hebrew",
            "country_english": "Israel",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "he/he_IL/community_female/medium/he_IL-community_female-medium.onnx": {
                "size_bytes": 63461522,
                "url": "https://huggingface.co/notmax123/piper-medium-heb/resolve/main/piper_medium_female.onnx",
            },
            "he/he_IL/community_female/medium/he_IL-community_female-medium.onnx.json": {
                "size_bytes": 8276,
                "url": "https://huggingface.co/notmax123/piper-medium-heb/resolve/main/model.config.json",
            },
        },
        "aliases": [],
    },
    # External Hebrew Piper voice metadata source:
    # https://huggingface.co/notmax123/piper-medium-heb/resolve/main/model.config.json
    "he_IL-community_male-medium": {
        "engine": "piper",
        "key": "he_IL-community_male-medium",
        "name": "community_male",
        "language": {
            "code": "he_IL",
            "family": "he",
            "region": "IL",
            "name_native": "עברית",
            "name_english": "Hebrew",
            "country_english": "Israel",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {
            "he/he_IL/community_male/medium/he_IL-community_male-medium.onnx": {
                "size_bytes": 63461522,
                "url": "https://huggingface.co/notmax123/piper-medium-heb/resolve/main/piper_medium_male.onnx",
            },
            "he/he_IL/community_male/medium/he_IL-community_male-medium.onnx.json": {
                "size_bytes": 8276,
                "url": "https://huggingface.co/notmax123/piper-medium-heb/resolve/main/model.config.json",
            },
        },
        "aliases": [],
    },
    # External Kokoro model metadata source:
    # https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0
    "ja_JP-jf_alpha-kokoro-v1.0": {
        "engine": "kokoro",
        "shared_pack": KOKORO_SHARED_PACK_ID,
        "depends_on": ["support-ja-mucab"],
        "key": "ja_JP-jf_alpha-kokoro-v1.0",
        "name": "jf_alpha",
        "language": {
            "code": "ja_JP",
            "family": "ja",
            "region": "JP",
            "name_native": "日本語",
            "name_english": "Japanese",
            "country_english": "Japan",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {},
        "aliases": [],
    },
    # External Kokoro model metadata source:
    # https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0
    "ko_KR-jf_alpha-kokoro-v1.0": {
        "engine": "kokoro",
        "shared_pack": KOKORO_SHARED_PACK_ID,
        "key": "ko_KR-jf_alpha-kokoro-v1.0",
        "name": "jf_alpha",
        "language": {
            "code": "ko_KR",
            "family": "ko",
            "region": "KR",
            "name_native": "한국어",
            "name_english": "Korean",
            "country_english": "South Korea",
        },
        "quality": "medium",
        "num_speakers": 1,
        "speaker_id_map": {},
        "files": {},
        "aliases": [],
    },
}

KOKORO_SHARED_FILES = {
    "kokoro-v1.0.int8.onnx": {
        "size_bytes": 92361271,
        "url": f"{KOKORO_BASE_URL}/kokoro-v1.0.int8.onnx",
    },
    "voices-v1.0.bin": {
        "size_bytes": 28214398,
        "url": f"{KOKORO_BASE_URL}/voices-v1.0.bin",
    },
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Merge Piper TTS voices into the app catalog")
    parser.add_argument(
        "--base-catalog",
        default="app/src/main/assets/index.json",
        help="Base catalog JSON to extend",
    )
    parser.add_argument(
        "--voices",
        default="piper/voices.json",
        help="Path to Piper voices.json",
    )
    parser.add_argument(
        "--output",
        default="app/src/main/assets/index.json",
        help="Where to write the merged catalog",
    )
    parser.add_argument(
        "--piper-base-url",
        default=PIPER_BASE_URL,
        help="Base URL used for Piper voice files",
    )
    parser.add_argument(
        "--tts-base-url",
        default=TTS_BASE_URL,
        help="Base URL used for shared eSpeak data",
    )
    parser.add_argument(
        "--tts-version",
        type=int,
        default=TTS_VERSION,
        help="Shared TTS asset version",
    )
    parser.add_argument(
        "--espeak-data-dir",
        default=None,
        help="Path to espeak-ng-data directory used to build the shared zip",
    )
    parser.add_argument(
        "--espeak-core-zip",
        default="tts/espeak-ng-data.zip",
        help="Local output path for the generated shared eSpeak zip",
    )
    return parser.parse_args()


def load_json(path: str) -> dict:
    with Path(path).open("r", encoding="utf-8") as handle:
        return json.load(handle)


def merge_voice_catalogs(voices: dict) -> dict:
    merged = deepcopy(voices)
    merged.update(EXTRA_TTS_VOICES)
    return merged


def app_language_code(voice: dict, supported_languages: set[str]) -> str | None:
    locale_code = voice["language"]["code"]
    if locale_code in APP_LANGUAGE_OVERRIDES:
        code = APP_LANGUAGE_OVERRIDES[locale_code]
        return code if code in supported_languages else None

    family = voice["language"]["family"]
    return family if family in supported_languages else None


def espeak_dict_code(app_language: str, locale_code: str) -> str:
    if locale_code.startswith("yue_"):
        return "yue"
    return ESPEAK_DICT_OVERRIDES.get(app_language, app_language)


def voice_sort_key(item: tuple[str, dict]) -> tuple[int, int, int, str]:
    key, voice = item
    quality_rank = QUALITY_PRIORITY.get(voice.get("quality"), 99)
    speaker_rank = 0 if voice.get("num_speakers", 1) == 1 else 1
    model_size = min(
        (
            file_info.get("size_bytes", 0)
            for path, file_info in voice.get("files", {}).items()
            if path.endswith(".onnx") and not path.endswith(".onnx.json")
        ),
        default=0,
    )
    return quality_rank, speaker_rank, model_size, key


def region_display_name(voice: dict) -> str:
    region = voice["language"].get("country_english")
    if region:
        return region
    return voice["language"].get("region", voice["language"]["code"])


def resolve_espeak_data_dir(configured: str | None) -> Path | None:
    if configured:
        path = Path(configured)
        return path if path.exists() else None

    repo_checkout = Path("/home/david/git/espeak-ng-rs/espeak-ng-data")
    if repo_checkout.exists():
        return repo_checkout

    candidates = sorted(
        Path("app/src/main/bindings").glob(
            "target/aarch64-linux-android/release/build/espeak-rs-sys-*/out/espeak-ng/espeak-ng-data"
        )
    )
    return candidates[-1] if candidates else None


def build_espeak_core_zip(espeak_data_dir: Path, output_path: Path) -> int:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output_path, "w", compression=ZIP_DEFLATED) as archive:
        for filename in CORE_ESPEAK_FILES:
            archive.write(
                espeak_data_dir / filename,
                arcname=f"espeak-ng-data/{filename}",
            )
        for directory_name in ("lang", "voices"):
            directory = espeak_data_dir / directory_name
            for path in sorted(directory.rglob("*")):
                if path.is_file():
                    archive.write(path, arcname=f"espeak-ng-data/{path.relative_to(espeak_data_dir)}")
    return output_path.stat().st_size


def build_espeak_support_packs(
    catalog: dict,
    dict_codes: set[str],
    tts_base_url: str,
    tts_version: int,
    espeak_core_zip_size: int,
) -> None:
    core_pack_id = f"tts-espeak-core-v{tts_version}"
    catalog["packs"][core_pack_id] = {
        "feature": "support",
        "kind": "tts-espeak-core",
        "files": [
            {
                "name": "espeak-ng-data.zip",
                "sizeBytes": espeak_core_zip_size,
                "installPath": "bin/espeak-ng-data.zip",
                "url": f"{tts_base_url.rstrip('/')}/{tts_version}/espeak-ng-data.zip",
                "archiveFormat": "zip",
                "extractTo": "bin",
                "deleteAfterExtract": True,
                "installMarkerPath": "bin/espeak-ng-data/.install-info.json",
                "installMarkerVersion": tts_version,
            }
        ],
        "dependsOn": [],
    }

    for dict_code in sorted(dict_codes):
        catalog["packs"][f"tts-espeak-dict-{dict_code}"] = {
            "feature": "support",
            "kind": "tts-espeak-dict",
            "files": [
                {
                    "name": f"{dict_code}_dict",
                    "sizeBytes": 0,
                    "installPath": f"bin/espeak-ng-data/{dict_code}_dict",
                    "url": f"{tts_base_url.rstrip('/')}/{tts_version}/espeak-ng-data/{dict_code}_dict",
                }
            ],
            "dependsOn": [core_pack_id],
        }


def build_shared_tts_support_packs(catalog: dict, voices: dict) -> None:
    if not any(voice.get("shared_pack") == KOKORO_SHARED_PACK_ID for voice in voices.values()):
        return

    catalog["packs"][KOKORO_SHARED_PACK_ID] = {
        "feature": "support",
        "kind": "tts-kokoro-core",
        "files": [
            {
                "name": filename,
                "sizeBytes": file_info["size_bytes"],
                "installPath": f"bin/kokoro/{filename}",
                "url": file_info["url"],
            }
            for filename, file_info in KOKORO_SHARED_FILES.items()
        ],
        "dependsOn": [],
    }


def merge_tts(
    base_catalog: dict,
    voices: dict,
    piper_base_url: str,
    tts_base_url: str,
    tts_version: int,
    espeak_core_zip_size: int,
) -> dict:
    catalog = deepcopy(base_catalog)
    voices = merge_voice_catalogs(voices)
    catalog["generatedAt"] = int(time.time())
    for entry in catalog["languages"].values():
        entry.pop("tts", None)
    catalog["packs"] = {
        pack_id: pack
        for pack_id, pack in catalog["packs"].items()
        if pack.get("feature") != "tts" and pack.get("kind") not in {"tts-espeak-core", "tts-espeak-dict"}
    }
    supported_languages = set(catalog["languages"].keys())
    grouped: dict[tuple[str, str], list[tuple[str, dict]]] = defaultdict(list)
    required_dict_codes: set[str] = set()

    for key, voice in voices.items():
        app_language = app_language_code(voice, supported_languages)
        if app_language is None:
            continue
        region = voice["language"].get("region")
        if not region:
            continue
        grouped[(app_language, region)].append((key, voice))
        required_dict_codes.add(espeak_dict_code(app_language, voice["language"]["code"]))

    build_espeak_support_packs(catalog, required_dict_codes, tts_base_url, tts_version, espeak_core_zip_size)
    build_shared_tts_support_packs(catalog, voices)

    regions_by_language: dict[str, dict[str, dict]] = defaultdict(dict)
    for (app_language, region), region_voices in sorted(grouped.items()):
        ranked = sorted(region_voices, key=voice_sort_key)[:4]
        voice_ids: list[str] = []

        for key, voice in ranked:
            engine = voice.get("engine", "piper")
            install_root = voice.get("install_root", engine)
            pack_id = f"tts-{engine}-{key.replace('_', '-').lower()}"
            locale_code = voice["language"]["code"]
            dict_code = espeak_dict_code(app_language, locale_code)
            files = []
            for source_path, file_info in voice.get("files", {}).items():
                if source_path.endswith("MODEL_CARD"):
                    continue
                filename = source_path.rsplit("/", 1)[-1]
                files.append(
                    {
                        "name": filename,
                        "sizeBytes": file_info.get("size_bytes", 0),
                        "installPath": f"bin/{install_root}/{source_path}",
                        "url": file_info.get("url") or f"{piper_base_url.rstrip('/')}/{source_path}",
                    }
                )

            default_speaker_id = None
            speaker_id_map = voice.get("speaker_id_map") or {}
            if speaker_id_map:
                default_speaker_id = sorted(speaker_id_map.values())[0]

            depends_on = [f"tts-espeak-dict-{dict_code}"]
            shared_pack = voice.get("shared_pack")
            if shared_pack:
                depends_on.append(shared_pack)
            depends_on.extend(voice.get("depends_on", []))

            pack = {
                "feature": "tts",
                "engine": engine,
                "language": app_language,
                "locale": locale_code,
                "region": region,
                "voice": voice["name"],
                "quality": voice.get("quality"),
                "numSpeakers": voice.get("num_speakers", 1),
                "defaultSpeakerId": default_speaker_id,
                "aliases": voice.get("aliases", []),
                "files": files,
                "dependsOn": depends_on,
            }
            if default_speaker_id is None:
                pack.pop("defaultSpeakerId")
            catalog["packs"][pack_id] = pack
            voice_ids.append(pack_id)

        if voice_ids:
            regions_by_language[app_language][region] = {
                "displayName": region_display_name(ranked[0][1]),
                "voices": voice_ids,
            }

    for language_code, regions in regions_by_language.items():
        default_region = DEFAULT_REGION_OVERRIDES.get(language_code)
        if default_region not in regions:
            default_region = next(iter(regions.keys()))
        catalog["languages"][language_code]["tts"] = {
            "defaultRegion": default_region,
            "regions": regions,
        }

    return catalog


def main() -> None:
    args = parse_args()
    espeak_core_zip_size = 0
    espeak_data_dir = resolve_espeak_data_dir(args.espeak_data_dir)
    if espeak_data_dir is not None:
        espeak_core_zip_size = build_espeak_core_zip(espeak_data_dir, Path(args.espeak_core_zip))
    merged = merge_tts(
        base_catalog=load_json(args.base_catalog),
        voices=load_json(args.voices),
        piper_base_url=args.piper_base_url,
        tts_base_url=args.tts_base_url,
        tts_version=args.tts_version,
        espeak_core_zip_size=espeak_core_zip_size,
    )
    Path(args.output).write_text(json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
