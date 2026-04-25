#!/usr/bin/env python3

import argparse
import json

from copy import deepcopy
from pathlib import Path

import catalog_base
import catalog_adblock
import catalog_mirror
import catalog_tts
import catalog_upstream


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_BUCKET_DIR = SCRIPT_DIR.parent / "bucket"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate either the internal source catalog or the public app index.",
    )
    parser.add_argument(
        "--mode",
        choices=("internal", "public"),
        default="internal",
        help="`internal` builds the source catalog from upstream snapshots. `public` builds the app index from the source catalog plus bucket files.",
    )
    parser.add_argument(
        "--output",
        default=None,
        help="Where to write the generated JSON. Defaults depend on --mode.",
    )

    parser.add_argument(
        "--gcs-models",
        default=str(SCRIPT_DIR / "data_sources/gcs_models.json"),
        help="Path to the upstream GCS models.json snapshot for --mode internal.",
    )
    parser.add_argument(
        "--dictionary-index",
        default=str(SCRIPT_DIR / "data_sources/dictionary_index.json"),
        help="Path to the dictionary index snapshot for --mode internal.",
    )
    parser.add_argument(
        "--voices",
        default=str(SCRIPT_DIR / "data_sources/piper_voices.json"),
        help="Path to the Piper voices snapshot for --mode internal.",
    )
    parser.add_argument(
        "--piper-base-url",
        default=catalog_tts.PIPER_BASE_URL,
        help="Base URL used for Piper voice files in --mode internal.",
    )
    parser.add_argument(
        "--tts-base-url",
        default=catalog_tts.TTS_BASE_URL,
        help="Base URL used for shared eSpeak data in --mode internal.",
    )
    parser.add_argument(
        "--tts-version",
        type=int,
        default=catalog_tts.TTS_VERSION,
        help="Shared TTS asset version in --mode internal.",
    )
    parser.add_argument(
        "--espeak-data-dir",
        default=None,
        help="Path to espeak-ng-data used to build the shared zip when available in --mode internal.",
    )
    parser.add_argument(
        "--espeak-core-zip",
        default=str(SCRIPT_DIR / "tts/espeak-ng-data.zip"),
        help="Local output path for the generated shared eSpeak zip in --mode internal.",
    )

    parser.add_argument(
        "--source-catalog",
        default=str(SCRIPT_DIR / "catalog_sources/source_catalog.json"),
        help="Path to the generated source catalog for --mode public.",
    )
    parser.add_argument(
        "--bucket-dir",
        type=Path,
        default=DEFAULT_BUCKET_DIR,
        help=f"Directory containing mirrored files for --mode public. Default: {DEFAULT_BUCKET_DIR}",
    )
    parser.add_argument(
        "--base-url",
        default=None,
        help="Public base URL that serves the bucket contents for --mode public.",
    )
    parser.add_argument(
        "--allow-missing",
        action="store_true",
        help="Keep source-catalog metadata for files missing from the bucket instead of failing in --mode public.",
    )
    return parser.parse_args()


def default_output_for_mode(mode: str) -> Path:
    if mode == "public":
        return SCRIPT_DIR / "app/src/main/assets/index.json"
    return SCRIPT_DIR / "catalog_sources/source_catalog.json"


def build_internal_catalog(args: argparse.Namespace) -> dict:
    models_manifest = catalog_base.load_json(Path(args.gcs_models))
    dictionary_index = catalog_base.load_json(Path(args.dictionary_index))
    voices = catalog_tts.load_json(args.voices)

    espeak_core_zip_size = 0
    espeak_data_dir = catalog_tts.resolve_espeak_data_dir(args.espeak_data_dir)
    if espeak_data_dir is not None:
        espeak_core_zip_size = catalog_tts.build_espeak_core_zip(
            espeak_data_dir,
            Path(args.espeak_core_zip),
        )

    return catalog_upstream.build_source_catalog(
        models_manifest=models_manifest,
        dictionary_index=dictionary_index,
        voices=voices,
        piper_base_url=args.piper_base_url,
        tts_base_url=args.tts_base_url,
        tts_version=args.tts_version,
        espeak_core_zip_size=espeak_core_zip_size,
    )


def build_public_catalog(source_catalog: dict, bucket_dir: Path, base_url: str, allow_missing: bool) -> dict:
    published = deepcopy(source_catalog)
    published.pop("translationModelsBaseUrl", None)
    published.pop("tesseractModelsBaseUrl", None)
    published.pop("dictionaryBaseUrl", None)

    missing_paths = []

    catalog_adblock.publish_adblock_pack(published, bucket_dir, base_url)

    for pack in published.get("packs", {}).values():
        for file_info in pack.get("files", []):
            mirror_path = catalog_mirror.mirror_path_for_file(pack, file_info)
            local_path = catalog_mirror.bucket_path(bucket_dir, mirror_path)
            if local_path.exists():
                file_info["sizeBytes"] = local_path.stat().st_size
                file_info["url"] = catalog_mirror.mirror_url(base_url, mirror_path)
                file_info.pop("sourcePath", None)
            else:
                if not allow_missing:
                    missing_paths.append(str(local_path))
                file_info["url"] = catalog_mirror.mirror_url(base_url, mirror_path)
            file_info.pop("mirrorPath", None)

    if missing_paths:
        sample = "\n".join(missing_paths[:20])
        raise FileNotFoundError(
            f"Missing {len(missing_paths)} mirrored files under {bucket_dir}.\n{sample}"
        )

    return published


def main() -> None:
    args = parse_args()
    output_path = Path(args.output) if args.output else default_output_for_mode(args.mode)

    if args.mode == "internal":
        catalog = build_internal_catalog(args)
    else:
        if not args.base_url:
            raise SystemExit("--base-url is required for --mode public")
        source_catalog = catalog_base.load_json(Path(args.source_catalog))
        catalog = build_public_catalog(
            source_catalog=source_catalog,
            bucket_dir=args.bucket_dir,
            base_url=args.base_url,
            allow_missing=args.allow_missing,
        )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {output_path}")
    print(f"languages={len(catalog['languages'])} packs={len(catalog['packs'])}")


if __name__ == "__main__":
    main()
