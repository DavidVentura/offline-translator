#!/usr/bin/env python3
"""Download prebuilt offline assets from the Translator CDN into the app's
on-device directory layout.

Unlike download_bucket.py (which mirrors upstream sources to build the CDN),
this reads the served catalog index and pulls the already-built files, laying
them out exactly where the app looks for them. Point --output at a
`dev.davidv.translator` directory and copy it to the device."""

import argparse
import gzip
import io
import json
import sys
import time
import urllib.error
import urllib.request
import zipfile

from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote, urlsplit, urlunsplit

DEFAULT_INDEX_URL = "https://offline-translator.davidv.dev/index_v7.json"
USER_AGENT = "download_offline/1.0"
ALL_FEATURES = ("translation", "dictionary", "ocr", "tts")


@dataclass(frozen=True)
class FileItem:
    url: str
    install_path: str
    archive_format: str | None
    extract_to: str | None
    marker_path: str | None
    marker_version: int | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("languages", nargs="+", help="Language codes to install, e.g. de fr sr zh")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("dev.davidv.translator"),
        help="Directory to install into. Default: ./dev.davidv.translator",
    )
    parser.add_argument(
        "--index",
        default=DEFAULT_INDEX_URL,
        help=f"Catalog index URL or local path. Default: {DEFAULT_INDEX_URL}",
    )
    parser.add_argument("--ocr", action="store_true", help="Also download OCR models for each language.")
    parser.add_argument("--tts", action="store_true", help="Also download the default TTS voice for each language.")
    parser.add_argument("--dictionary", action="store_true", help="Also download the offline dictionary for each language.")
    parser.add_argument("--all", action="store_true", help="Download translation, dictionary, OCR and TTS for each language.")
    parser.add_argument("--timeout", type=int, default=120, help="Per-request timeout in seconds. Default: 120")
    parser.add_argument("--retries", type=int, default=2, help="Retry count per file after the first attempt. Default: 2")
    parser.add_argument("--dry-run", action="store_true", help="List the files that would be downloaded without fetching anything.")
    return parser.parse_args()


def normalized_request_url(url: str) -> str:
    parts = urlsplit(url)
    path = quote(parts.path, safe="/%:@!$&'()*+,;=-._~")
    return urlunsplit((parts.scheme, parts.netloc, path, parts.query, parts.fragment))


def fetch_bytes(url: str, timeout: int, retries: int) -> bytes:
    request = urllib.request.Request(normalized_request_url(url), headers={"User-Agent": USER_AGENT})
    last_error: Exception | None = None
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.read()
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as exc:
            last_error = exc
            if attempt == retries:
                break
            time.sleep(min(2**attempt, 5))
    assert last_error is not None
    raise last_error


def load_index(source: str, timeout: int, retries: int) -> dict:
    if source.startswith(("http://", "https://")):
        data = fetch_bytes(source, timeout, retries)
    else:
        data = Path(source).read_bytes()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return json.loads(data)


def features_from_args(args: argparse.Namespace) -> set[str]:
    features = {"translation"}
    if args.all:
        features.update(ALL_FEATURES)
    if args.ocr:
        features.add("ocr")
    if args.tts:
        features.add("tts")
    if args.dictionary:
        features.add("dictionary")
    return features


def default_voice_pack_ids(tts: dict) -> list[str]:
    regions = tts.get("regions", {})
    region = tts.get("defaultRegion")
    if region not in regions:
        region = next(iter(regions), None)
    if region is None:
        return []
    voices = regions[region].get("voices", [])
    return voices[:1]


def root_pack_ids(index: dict, language: str, features: set[str]) -> list[str]:
    lang = index["languages"].get(language)
    if lang is None:
        raise KeyError(language)
    assets = lang.get("assets", {})
    roots: list[str] = []
    if "translation" in features:
        roots.extend(assets.get("translate", []))
    if "dictionary" in features and assets.get("dictionary"):
        roots.append(assets["dictionary"])
    if "ocr" in features:
        roots.extend(assets.get("ocr", {}).values())
    if "tts" in features and lang.get("tts"):
        roots.extend(default_voice_pack_ids(lang["tts"]))
    return roots


def expand_dependencies(packs: dict, root_ids: list[str]) -> list[str]:
    resolved: list[str] = []
    seen: set[str] = set()
    queue = list(root_ids)
    while queue:
        pack_id = queue.pop(0)
        if pack_id in seen:
            continue
        seen.add(pack_id)
        pack = packs.get(pack_id)
        if pack is None:
            print(f"WARNING: unknown pack referenced: {pack_id}", file=sys.stderr)
            continue
        resolved.append(pack_id)
        queue.extend(pack.get("dependsOn", []))
    return resolved


def file_items_for_packs(packs: dict, pack_ids: list[str]) -> list[FileItem]:
    items: dict[str, FileItem] = {}
    for pack_id in pack_ids:
        for file_info in packs[pack_id].get("files", []):
            install_path = file_info["installPath"]
            items.setdefault(
                install_path,
                FileItem(
                    url=file_info["url"],
                    install_path=install_path,
                    archive_format=file_info.get("archiveFormat"),
                    extract_to=file_info.get("extractTo"),
                    marker_path=file_info.get("installMarkerPath"),
                    marker_version=file_info.get("installMarkerVersion"),
                ),
            )
    return sorted(items.values(), key=lambda item: item.install_path)


def is_present(item: FileItem, output: Path) -> bool:
    if item.marker_path is not None:
        marker = output / item.marker_path
        if not marker.exists():
            return False
        try:
            return json.loads(marker.read_text()).get("version") == item.marker_version
        except (json.JSONDecodeError, OSError):
            return False
    return (output / item.install_path).exists()


def install_file(item: FileItem, output: Path, timeout: int, retries: int) -> None:
    data = fetch_bytes(item.url, timeout, retries)
    if item.archive_format == "zip":
        extract_dir = output / (item.extract_to or ".")
        extract_dir.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            archive.extractall(extract_dir)
        if item.marker_path is not None:
            marker = output / item.marker_path
            marker.parent.mkdir(parents=True, exist_ok=True)
            marker.write_text(json.dumps({"version": item.marker_version}))
        return
    if item.url.endswith(".gz") and not item.install_path.endswith(".gz"):
        data = gzip.decompress(data)
    dest = output / item.install_path
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)


def main() -> int:
    args = parse_args()
    index = load_index(args.index, args.timeout, args.retries)
    features = features_from_args(args)
    packs = index["packs"]

    roots: list[str] = []
    for language in args.languages:
        try:
            roots.extend(root_pack_ids(index, language, features))
        except KeyError:
            print(f"ERROR: unknown language '{language}'", file=sys.stderr)
            return 1

    pack_ids = expand_dependencies(packs, roots)
    items = file_items_for_packs(packs, pack_ids)

    print(f"languages={' '.join(args.languages)} features={','.join(sorted(features))}")
    print(f"packs={len(pack_ids)} files={len(items)} output={args.output}")

    if args.dry_run:
        for item in items:
            print(f"{item.url} -> {args.output / item.install_path}")
        return 0

    downloaded = 0
    for index_pos, item in enumerate(items, start=1):
        if is_present(item, args.output):
            continue
        try:
            install_file(item, args.output, args.timeout, args.retries)
        except Exception as exc:  # noqa: BLE001 - surface the URL that failed
            print(f"FAILED [{index_pos}/{len(items)}]: {item.url}: {exc}", file=sys.stderr)
            return 1
        downloaded += 1
        print(f"[{index_pos}/{len(items)}] {item.install_path}")

    print(f"done downloaded={downloaded} skipped={len(items) - downloaded}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
