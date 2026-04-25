#!/usr/bin/env python3

import argparse
import asyncio
import json
import shutil
import sys
import time
import urllib.error
import urllib.request

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable
from urllib.parse import quote, urlparse, urlsplit, urlunsplit

SCRIPT_DIR = Path(__file__).resolve().parent
TRANSLATOR_DIR = SCRIPT_DIR
if str(TRANSLATOR_DIR) not in sys.path:
    sys.path.insert(0, str(TRANSLATOR_DIR))

import catalog_mirror
import catalog_adblock


DEFAULT_MANIFEST = SCRIPT_DIR / "catalog_sources/source_catalog.json"
DEFAULT_OUTPUT_DIR = Path("../bucket")
USER_AGENT = "download_bucket/1.0"


@dataclass(frozen=True)
class DownloadEntry:
    pack_id: str
    feature: str
    url: str
    dest: Path
    size_bytes: int | None
    refresh_always: bool


def known_size(size_bytes: int | None) -> int | None:
    if size_bytes is None:
        return None
    if size_bytes <= 0:
        return None
    return size_bytes


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Download all files referenced by the Translator app manifest into a local bucket directory.",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=DEFAULT_MANIFEST,
        help=f"Path to the source catalog or app index.json. Default: {DEFAULT_MANIFEST}",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help="Directory to write the mirrored files into. Default: ./bucket",
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=8,
        help="Maximum number of concurrent downloads. Default: 8",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=120,
        help="Per-request timeout in seconds. Default: 120",
    )
    parser.add_argument(
        "--retries",
        type=int,
        default=2,
        help="Retry count per file after the first attempt. Default: 2",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Resolve all destination paths and print a summary without downloading anything.",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Print every resolved file path in dry-run mode and every completed file in download mode.",
    )
    parser.add_argument(
        "--refresh-adblock",
        action="store_true",
        help="Re-download adblock source lists even when they already exist.",
    )
    return parser.parse_args()


def load_manifest(path: Path) -> dict:
    with path.open() as fh:
        return json.load(fh)


def relative_path_from_url(parsed_url, fallback_name: str) -> str:
    path = parsed_url.path or ""

    if "/resolve/main/" in path:
        return catalog_mirror.ensure_version_prefix(path.split("/resolve/main/", 1)[1])

    if "/resolve/" in path:
        return catalog_mirror.ensure_version_prefix(path.split("/resolve/", 1)[1])

    if "/releases/download/" in path:
        return catalog_mirror.ensure_version_prefix(path.split("/releases/download/", 1)[1])

    filename = Path(path).name or fallback_name
    return catalog_mirror.ensure_version_prefix(filename)


def normalized_request_url(url: str) -> str:
    parts = urlsplit(url)
    path = quote(parts.path, safe="/%:@!$&'()*+,;=-._~")
    query = quote(parts.query, safe="=&%:@!$'()*+,;/-._~")
    fragment = quote(parts.fragment, safe="%:@!$&'()*+,;=/-._~")
    return urlunsplit((parts.scheme, parts.netloc, path, query, fragment))


def resolve_destination(pack: dict, file_info: dict, output_dir: Path) -> Path:
    feature = pack.get("feature", "misc")
    mirror_path = file_info.get("mirrorPath")
    if mirror_path:
        return output_dir / mirror_path.strip("/")

    url = file_info["url"]
    parsed = urlparse(url)
    source_path = file_info.get("sourcePath")
    install_path = file_info.get("installPath")

    if install_path:
        return output_dir / catalog_mirror.mirror_path_for_file(pack, file_info)

    if source_path:
        relative = catalog_mirror.ensure_version_prefix(source_path)
    else:
        relative = relative_path_from_url(parsed, file_info["name"])
    return output_dir / feature / relative


def build_download_entries(manifest: dict, output_dir: Path, refresh_adblock: bool) -> list[DownloadEntry]:
    entries_by_dest: dict[Path, DownloadEntry] = {}

    for pack_id, pack in manifest.get("packs", {}).items():
        feature = pack.get("feature", "misc")
        for file_info in pack.get("files", []):
            dest = resolve_destination(pack, file_info, output_dir)
            size_bytes = known_size(file_info.get("sizeBytes"))
            entry = DownloadEntry(
                pack_id=pack_id,
                feature=feature,
                url=file_info["url"],
                dest=dest,
                size_bytes=size_bytes,
                refresh_always=refresh_adblock and pack.get("kind") == catalog_adblock.ADBLOCK_KIND,
            )

            existing = entries_by_dest.get(dest)
            if existing is None:
                entries_by_dest[dest] = entry
                continue
            if existing.url != entry.url:
                raise ValueError(
                    f"Destination collision for {dest}: {existing.url} vs {entry.url}"
                )

    return sorted(entries_by_dest.values(), key=lambda entry: str(entry.dest))


def summarize(entries: Iterable[DownloadEntry], output_dir: Path) -> tuple[int, int, int, int]:
    total_files = 0
    total_bytes = 0
    present_files = 0
    missing_files = 0
    present_bytes = 0
    missing_bytes = 0
    for entry in entries:
        total_files += 1
        total_bytes += entry.size_bytes or 0
        if should_skip(entry):
            present_files += 1
            present_bytes += entry.size_bytes or 0
        else:
            missing_files += 1
            missing_bytes += entry.size_bytes or 0
    print(f"output={output_dir}")
    print(f"files={total_files}")
    print(f"bytes={total_bytes}")
    print(f"present_files={present_files}")
    print(f"missing_files={missing_files}")
    print(f"present_bytes={present_bytes}")
    print(f"bytes_to_download={missing_bytes}")
    return total_files, total_bytes, missing_files, missing_bytes


def format_size(num_bytes: int) -> str:
    value = float(num_bytes)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if value < 1024 or unit == "TiB":
            return f"{value:.1f}{unit}"
        value /= 1024
    return f"{num_bytes}B"


def should_skip(entry: DownloadEntry) -> bool:
    return entry.dest.exists() and not entry.refresh_always


def update_manifest_sizes(manifest: dict, output_dir: Path, manifest_path: Path) -> None:
    changed = False
    for pack in manifest.get("packs", {}).values():
        refresh_pack = pack.get("kind") == catalog_adblock.ADBLOCK_KIND
        for file_info in pack.get("files", []):
            dest = resolve_destination(pack, file_info, output_dir)
            if not dest.exists():
                continue
            current_size = file_info.get("sizeBytes")
            actual_size = dest.stat().st_size
            if not isinstance(current_size, int) or current_size <= 0:
                if current_size != actual_size:
                    file_info["sizeBytes"] = actual_size
                    changed = True
    if changed:
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"updated_manifest_sizes={manifest_path}")


def download_once(entry: DownloadEntry, timeout: int) -> str:
    entry.dest.parent.mkdir(parents=True, exist_ok=True)
    temp_path = entry.dest.with_name(entry.dest.name + ".part")
    request = urllib.request.Request(
        normalized_request_url(entry.url),
        headers={"User-Agent": USER_AGENT},
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            with temp_path.open("wb") as fh:
                shutil.copyfileobj(response, fh, length=1024 * 1024)
        temp_path.replace(entry.dest)
    except Exception:
        temp_path.unlink(missing_ok=True)
        raise

    return "downloaded"


def download_with_retries(entry: DownloadEntry, timeout: int, retries: int) -> str:
    if should_skip(entry):
        return "skipped"

    last_error: Exception | None = None
    for attempt in range(retries + 1):
        try:
            return download_once(entry, timeout)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as exc:
            last_error = exc
            if attempt == retries:
                break
            time.sleep(min(2**attempt, 5))

    assert last_error is not None
    raise last_error


async def fetch_all(entries: list[DownloadEntry], concurrency: int, timeout: int, retries: int, verbose: bool) -> int:
    semaphore = asyncio.Semaphore(concurrency)
    completed = 0
    failures: list[tuple[DownloadEntry, Exception]] = []

    async def worker(entry: DownloadEntry) -> None:
        nonlocal completed
        async with semaphore:
            try:
                status = await asyncio.to_thread(download_with_retries, entry, timeout, retries)
                completed += 1
                if verbose or status != "skipped":
                    print(f"[{completed}/{len(entries)}] {status}: {entry.dest}")
            except Exception as exc:
                failures.append((entry, exc))
                print(f"FAILED: {entry.url} -> {entry.dest}: {exc}", file=sys.stderr)

    await asyncio.gather(*(worker(entry) for entry in entries))

    if failures:
        print(f"failures={len(failures)}", file=sys.stderr)
        return 1

    print(f"completed={completed}")
    return 0


async def main() -> int:
    args = parse_args()
    manifest = load_manifest(args.manifest)
    entries = build_download_entries(manifest, args.output, args.refresh_adblock)
    total_files, total_bytes, missing_files, missing_bytes = summarize(entries, args.output)
    print(f"size_pretty={format_size(total_bytes)}")
    print(f"bytes_to_download_pretty={format_size(missing_bytes)}")

    if args.dry_run:
        if args.verbose:
            for entry in entries:
                print(f"{entry.url} -> {entry.dest}")
        return 0

    if total_files == 0:
        print("No files found in manifest.")
        return 0
    if missing_files == 0:
        print("All files already present.")
    else:
        result = await fetch_all(entries, args.concurrency, args.timeout, args.retries, args.verbose)
        if result != 0:
            return result

    update_manifest_sizes(manifest, args.output, args.manifest)
    adblock_size = await asyncio.to_thread(catalog_adblock.build_adblock_zip, manifest, args.output)
    if adblock_size > 0:
        print(f"adblock_bundle={catalog_adblock.adblock_bundle_path(args.output)}")
        print(f"adblock_bundle_size={adblock_size}")
        print(f"adblock_bundle_size_pretty={format_size(adblock_size)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
