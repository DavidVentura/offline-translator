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
        "--custom-models",
        default=str(SCRIPT_DIR / "data_sources/custom_models.json"),
        help="Path to home-trained model pairs merged onto the upstream snapshot in --mode internal.",
    )
    parser.add_argument(
        "--upstream-previous",
        default=str(SCRIPT_DIR / "data_sources/upstream_previous.json"),
        help="Upstream models a snapshot refresh replaced (see upstream_upgrades.py), shipped as upgradable lower-priority variants.",
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
        return SCRIPT_DIR / "app/src/main/assets/index_v7.json"
    return SCRIPT_DIR / "catalog_sources/source_catalog.json"


def onnx_to_mnn(path: str) -> str:
    assert path.endswith(".onnx"), path
    return path[: -len(".onnx")] + ".mnn"


# Model blobs the MNN-only runtime can no longer load as ONNX: every TTS engine
# voice model and the doc-detection model. The kokoro ONNX engine is dropped
# entirely (see `is_deprecated_kokoro_onnx_pack`) rather than converted — it has
# no matching .mnn and is superseded by the native kokoro-mnn pack. `.onnx.json`
# sidecars stay.
def pack_models_are_convertible(pack: dict) -> bool:
    feature = pack.get("feature")
    return feature == "tts" or (feature == "support" and pack.get("kind") == "doc_detect")


# The pre-MNN kokoro engine: the ONNX core blob plus its `engine == "kokoro"`
# voice packs (which depend on it). Both are unloadable under the MNN-only runtime
# and fully superseded by the `kokoro_mnn` packs, so they are removed from the
# public index instead of migrated. The all-voices file goes with them: each
# `kokoro_mnn` voice pack carries its own voice.
def is_deprecated_kokoro_onnx_pack(pack: dict) -> bool:
    if pack.get("kind") in {"tts-kokoro-core", "tts-kokoro-voices"}:
        return True
    return pack.get("feature") == "tts" and pack.get("engine") == "kokoro"


def apply_upstream_previous(models: dict, previous_path: Path, custom_pairs: set[str]) -> None:
    """Ship a replaced upstream model as priority 1 under its successor at 2.

    Upstream lists carry no priority, so a refresh used to swap the model in
    place: same flat install path, and the planner (which only checks that files
    exist) kept old installs on the old weights. As explicit priority variants the
    old model keeps the flat path and the new one gets a namespaced path, which
    the app offers as an upgrade.
    """
    if not previous_path.exists():
        return
    previous = catalog_base.load_json(previous_path)["models"]
    for pair, entries in sorted(previous.items()):
        if pair in custom_pairs:
            raise SystemExit(f"upstream_previous {pair}: pair is overridden by custom_models.json")
        if pair not in models:
            raise SystemExit(f"upstream_previous {pair}: upstream no longer ships this pair")
        (old,) = entries
        current = catalog_upstream.select_best_entry(models[pair])
        if old["files"]["model"]["path"] == current["files"]["model"]["path"]:
            raise SystemExit(f"upstream_previous {pair}: entry matches the current upstream model")
        models[pair] = [{**old, "priority": 1}, {**current, "priority": 2}]


def build_internal_catalog(args: argparse.Namespace) -> dict:
    models_manifest = catalog_base.load_json(Path(args.gcs_models))
    custom_catalog = catalog_base.load_json(Path(args.custom_models))
    custom_models = custom_catalog["models"]
    # A pair upstream also ships stays ours only when listed in `overridesUpstream`
    # (e.g. to keep an upgrade path across our home-made priority variants); any
    # other overlap is an accident, and a stale override means upstream dropped it.
    overrides = set(custom_catalog.get("overridesUpstream", []))
    collisions = set(custom_models) & set(models_manifest["models"])
    unexpected = collisions - overrides
    if unexpected:
        raise SystemExit(f"custom models collide with upstream snapshot: {', '.join(sorted(unexpected))}")
    stale = overrides - collisions
    if stale:
        raise SystemExit(f"overridesUpstream lists pairs upstream no longer ships: {', '.join(sorted(stale))}")
    apply_upstream_previous(models_manifest["models"], Path(args.upstream_previous), set(custom_models))
    models_manifest["models"].update(custom_models)
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


# Mirror of `sanitize_filename` in translator-rs `bucket_samples`: the sample
# files are written with each component sanitized (non-alphanumerics collapsed to
# `_`), so the lookup path must sanitize the same way or a voice like
# "Mandarin (Traditional)" never resolves to its `Mandarin_Traditional` file.
def sanitize_sample_component(value: str) -> str:
    out = []
    last_was_sep = False
    for ch in value:
        if ch.isalnum() or ch in "_-.":
            out.append(ch)
            last_was_sep = False
        elif not last_was_sep:
            out.append("_")
            last_was_sep = True
    return "".join(out).strip("_")


def tts_sample_mirror_path(pack: dict) -> str | None:
    voice = pack.get("voice")
    language = pack.get("language")
    quality = pack.get("quality")
    if not voice or not language:
        return None
    voice = sanitize_sample_component(voice)
    stem = f"{voice}_{sanitize_sample_component(quality)}" if quality else voice
    return f"samples_ogg/{language}/{stem}.opus"


# A voice pack that is gone from the public index must also go from the language
# rows that offer it, or the app lists a voice it can neither resolve nor
# download. Every other reference to a dropped pack (translation, dictionary,
# OCR, support, `dependsOn`) means something is actually broken, so those are
# left for `validate_manifest` to reject.
def drop_voice_packs(languages: dict, dropped_pack_ids: set[str]) -> None:
    for entry in languages.values():
        tts = entry.get("tts")
        if tts is None:
            continue

        regions = {}
        for region_code, region in tts["regions"].items():
            voices = [pack_id for pack_id in region["voices"] if pack_id not in dropped_pack_ids]
            if voices:
                regions[region_code] = {**region, "voices": voices}

        if not regions:
            entry.pop("tts")
            continue

        tts["regions"] = regions
        if tts["defaultRegion"] not in regions:
            tts["defaultRegion"] = next(iter(regions))


def build_public_catalog(source_catalog: dict, bucket_dir: Path, base_url: str, allow_missing: bool) -> dict:
    published = deepcopy(source_catalog)
    published.pop("translationModelsBaseUrl", None)
    published.pop("dictionaryBaseUrl", None)

    packs = published.get("packs", {})
    dropped_pack_ids = {
        pack_id for pack_id, pack in packs.items() if is_deprecated_kokoro_onnx_pack(pack)
    }
    published["packs"] = {
        pack_id: pack for pack_id, pack in packs.items() if pack_id not in dropped_pack_ids
    }
    drop_voice_packs(published["languages"], dropped_pack_ids)

    missing_paths = []
    migrations = []

    catalog_adblock.publish_adblock_pack(published, bucket_dir, base_url)

    for pack in published.get("packs", {}).values():
        convertible = pack_models_are_convertible(pack)
        migration_feature = "doc_detect" if pack.get("kind") == "doc_detect" else "tts"
        for file_info in pack.get("files", []):
            mirror_path = catalog_mirror.mirror_path_for_file(pack, file_info)
            local_path = catalog_mirror.bucket_path(bucket_dir, mirror_path)

            # Rewrite a model .onnx entry to its sibling .mnn (what fresh installs
            # download) and record how an existing install converts its on-disk
            # .onnx instead of re-downloading.
            if convertible and file_info["name"].endswith(".onnx"):
                mnn_mirror = onnx_to_mnn(mirror_path)
                mnn_local = catalog_mirror.bucket_path(bucket_dir, mnn_mirror)
                if not mnn_local.exists():
                    if not allow_missing:
                        missing_paths.append(str(mnn_local))
                else:
                    onnx_install = file_info["installPath"]
                    onnx_bytes = local_path.stat().st_size if local_path.exists() else 0
                    mnn_bytes = mnn_local.stat().st_size
                    migrations.append(
                        {
                            "onnx": onnx_install,
                            "mnn": onnx_to_mnn(onnx_install),
                            "quantBits": 8,
                            "onnxBytes": onnx_bytes,
                            "mnnBytes": mnn_bytes,
                            "feature": migration_feature,
                        }
                    )
                    file_info["name"] = onnx_to_mnn(file_info["name"])
                    file_info["installPath"] = onnx_to_mnn(onnx_install)
                    file_info["sizeBytes"] = mnn_bytes
                    file_info["url"] = catalog_mirror.mirror_url(base_url, mnn_mirror)
                    file_info.pop("sourcePath", None)
                    file_info.pop("mirrorPath", None)
                    continue

            if local_path.exists():
                file_info["sizeBytes"] = local_path.stat().st_size
                file_info["url"] = catalog_mirror.mirror_url(base_url, mirror_path)
                file_info.pop("sourcePath", None)
            else:
                if not allow_missing:
                    missing_paths.append(str(local_path))
                file_info["url"] = catalog_mirror.mirror_url(base_url, mirror_path)
            file_info.pop("mirrorPath", None)

        if pack.get("feature") == "tts":
            sample_mirror_path = tts_sample_mirror_path(pack)
            if sample_mirror_path is not None:
                if catalog_mirror.bucket_path(bucket_dir, sample_mirror_path).exists():
                    pack["sampleUrl"] = catalog_mirror.mirror_url(base_url, sample_mirror_path)

    if missing_paths:
        sample = "\n".join(missing_paths[:20])
        raise FileNotFoundError(
            f"Missing {len(missing_paths)} mirrored files under {bucket_dir}.\n{sample}"
        )

    published["migrations"] = migrations
    catalog_base.validate_manifest(published["languages"], published["packs"])
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
    if "migrations" in catalog:
        print(f"migrations={len(catalog['migrations'])}")


if __name__ == "__main__":
    main()
