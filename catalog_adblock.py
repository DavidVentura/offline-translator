from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

import catalog_mirror


ADBLOCK_KIND = "adblock"
ADBLOCK_VERSION = 1
ADBLOCK_ZIP_NAME = f"adblock-lists-v{ADBLOCK_VERSION}.zip"
ADBLOCK_ZIP_INSTALL_PATH = f"adblock/{ADBLOCK_ZIP_NAME}"
ADBLOCK_INSTALL_MARKER_PATH = "adblock/.install-info.json"


def adblock_source_files(source_catalog: dict, bucket_dir: Path) -> list[tuple[str, Path]]:
    files = []
    for pack in source_catalog.get("packs", {}).values():
        if pack.get("kind") != ADBLOCK_KIND:
            continue
        for file_info in pack.get("files", []):
            install_path = file_info.get("installPath", "")
            if not install_path.startswith("adblock/") or not install_path.endswith(".txt"):
                continue
            mirror_path = catalog_mirror.mirror_path_for_file(pack, file_info)
            files.append((install_path, catalog_mirror.bucket_path(bucket_dir, mirror_path)))
    return sorted(files)


def adblock_bundle_mirror_path() -> str:
    return catalog_mirror.mirror_path_for_file(
        {
            "feature": "support",
            "kind": ADBLOCK_KIND,
        },
        {
            "name": ADBLOCK_ZIP_NAME,
            "installPath": ADBLOCK_ZIP_INSTALL_PATH,
        },
    )


def adblock_bundle_path(bucket_dir: Path) -> Path:
    return catalog_mirror.bucket_path(bucket_dir, adblock_bundle_mirror_path())


def build_adblock_zip(source_catalog: dict, bucket_dir: Path) -> int:
    source_files = adblock_source_files(source_catalog, bucket_dir)
    if not source_files:
        return 0

    missing = [str(path) for _, path in source_files if not path.exists()]
    if missing:
        sample = "\n".join(missing[:20])
        raise FileNotFoundError(f"Missing {len(missing)} adblock source files.\n{sample}")

    output_path = adblock_bundle_path(bucket_dir)
    if output_path.exists():
        output_mtime = output_path.stat().st_mtime
        if all(path.stat().st_mtime <= output_mtime for _, path in source_files):
            return 0

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = output_path.with_name(output_path.name + ".tmp")
    with ZipFile(temp_path, "w", compression=ZIP_DEFLATED) as archive:
        for install_path, path in source_files:
            archive.write(path, arcname=install_path)
    temp_path.replace(output_path)
    return output_path.stat().st_size


def publish_adblock_pack(published: dict, bucket_dir: Path, base_url: str) -> None:
    bundle_path = adblock_bundle_path(bucket_dir)
    if not bundle_path.exists():
        raise FileNotFoundError(f"Missing adblock bundle: {bundle_path}")

    original_packs = published.get("packs", {})
    adblock_metadata = []
    for pack_id, pack in sorted(original_packs.items()):
        if pack.get("kind") == ADBLOCK_KIND:
            metadata = pack.get("metadata") or {}
            adblock_metadata.append(
                {
                    "packId": pack_id,
                    "listId": metadata.get("listId"),
                    "description": metadata.get("description"),
                },
            )

    published["packs"] = {
        pack_id: pack
        for pack_id, pack in original_packs.items()
        if pack.get("kind") != ADBLOCK_KIND
    }
    published["packs"][f"support-adblock-lists-v{ADBLOCK_VERSION}"] = {
        "feature": "support",
        "kind": ADBLOCK_KIND,
        "files": [
            {
                "name": ADBLOCK_ZIP_NAME,
                "sizeBytes": bundle_path.stat().st_size,
                "installPath": ADBLOCK_ZIP_INSTALL_PATH,
                "url": catalog_mirror.mirror_url(base_url, adblock_bundle_mirror_path()),
                "archiveFormat": "zip",
                "extractTo": ".",
                "deleteAfterExtract": True,
                "installMarkerPath": ADBLOCK_INSTALL_MARKER_PATH,
                "installMarkerVersion": ADBLOCK_VERSION,
            },
        ],
        "dependsOn": [],
        "metadata": {
            "version": ADBLOCK_VERSION,
            "lists": adblock_metadata,
        },
    }
