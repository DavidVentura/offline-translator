#!/usr/bin/env python3

import argparse
import json

from pathlib import Path

import catalog_base
import catalog_tts


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate the final app catalog in one step from the current source indices.",
    )
    parser.add_argument(
        "--language-index",
        default="catalog_sources/language_index.json",
        help="Path to the source language index JSON.",
    )
    parser.add_argument(
        "--dictionary-index",
        default="catalog_sources/dictionary_index.json",
        help="Path to the source dictionary index JSON.",
    )
    parser.add_argument(
        "--voices",
        default="piper/voices.json",
        help="Path to Piper voices.json.",
    )
    parser.add_argument(
        "--output",
        default="app/src/main/assets/index.json",
        help="Where to write the merged catalog.",
    )
    parser.add_argument(
        "--piper-base-url",
        default=catalog_tts.PIPER_BASE_URL,
        help="Base URL used for Piper voice files.",
    )
    parser.add_argument(
        "--tts-base-url",
        default=catalog_tts.TTS_BASE_URL,
        help="Base URL used for shared eSpeak data.",
    )
    parser.add_argument(
        "--tts-version",
        type=int,
        default=catalog_tts.TTS_VERSION,
        help="Shared TTS asset version.",
    )
    parser.add_argument(
        "--espeak-data-dir",
        default=None,
        help="Path to espeak-ng-data directory used to build the shared zip.",
    )
    parser.add_argument(
        "--espeak-core-zip",
        default="tts/espeak-ng-data.zip",
        help="Local output path for the generated shared eSpeak zip.",
    )
    return parser.parse_args()


def build_catalog(args: argparse.Namespace) -> dict:
    language_index = catalog_base.load_json(Path(args.language_index))
    dictionary_index = catalog_base.load_json(Path(args.dictionary_index))
    voices = catalog_tts.load_json(args.voices)

    espeak_core_zip_size = 0
    espeak_data_dir = catalog_tts.resolve_espeak_data_dir(args.espeak_data_dir)
    if espeak_data_dir is not None:
        espeak_core_zip_size = catalog_tts.build_espeak_core_zip(
            espeak_data_dir,
            Path(args.espeak_core_zip),
        )

    base_catalog = catalog_base.convert_v1_to_v2(language_index, dictionary_index)
    merged_catalog = catalog_tts.merge_tts(
        base_catalog=base_catalog,
        voices=voices,
        piper_base_url=args.piper_base_url,
        tts_base_url=args.tts_base_url,
        tts_version=args.tts_version,
        espeak_core_zip_size=espeak_core_zip_size,
    )
    return merged_catalog


def main() -> None:
    args = parse_args()
    output_path = Path(args.output)
    catalog = build_catalog(args)
    output_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {output_path}")
    print(f"languages={len(catalog['languages'])} packs={len(catalog['packs'])}")


if __name__ == "__main__":
    main()
