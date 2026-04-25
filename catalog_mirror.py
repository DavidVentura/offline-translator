from pathlib import Path


def ensure_version_prefix(relative_path: str) -> str:
    normalized = relative_path.strip("/")
    if not normalized:
        return "1"
    first_segment = normalized.split("/", 1)[0]
    if first_segment.isdigit() or first_segment == "extra":
        return normalized
    return f"1/{normalized}"


def relative_path_from_install_path(install_path: str) -> str:
    normalized = install_path.strip("/")
    if normalized.startswith("bin/"):
        parts = normalized.split("/", 2)
        if len(parts) == 3:
            return ensure_version_prefix(parts[2])
        if len(parts) == 2:
            return ensure_version_prefix(parts[1])
    return ensure_version_prefix(normalized)


def tail_after_bin(install_path: str) -> str:
    normalized = install_path.strip("/")
    if normalized.startswith("bin/"):
        return ensure_version_prefix(normalized[4:])
    return ensure_version_prefix(normalized)


def mirror_path_for_file(pack_or_feature, file_info: dict) -> str:
    existing = file_info.get("mirrorPath")
    if existing:
        return existing.strip("/")

    if isinstance(pack_or_feature, dict):
        pack = pack_or_feature
        feature = pack.get("feature", "misc")
        kind = pack.get("kind")
    else:
        pack = {}
        feature = pack_or_feature
        kind = None

    source_path = file_info.get("sourcePath")
    install_path = file_info.get("installPath")
    name = file_info["name"]
    url = file_info.get("url")

    if feature == "tts":
        if not install_path:
            raise ValueError(f"TTS file is missing installPath: {file_info}")
        return f"tts/{relative_path_from_install_path(install_path)}"

    if feature == "ocr":
        if install_path:
            return f"ocr/{ensure_version_prefix(install_path)}"
        return f"ocr/{ensure_version_prefix(name)}"

    if feature == "dictionary":
        if source_path:
            return f"dictionaries/{ensure_version_prefix(source_path)}"
        return f"dictionaries/{ensure_version_prefix(name)}"

    if feature == "support":
        if kind in {"tts-espeak-core", "tts-espeak-dict", "tts-kokoro-core"} and install_path:
            return f"tts/{tail_after_bin(install_path)}"
        if kind == "mucab" and source_path:
            return f"dictionaries/{source_path.strip('/')}"
        if source_path:
            return f"support/{ensure_version_prefix(source_path)}"
        if install_path:
            return f"support/{tail_after_bin(install_path)}"
        return f"support/{ensure_version_prefix(name)}"

    if feature == "translation":
        if source_path:
            return f"translation/{ensure_version_prefix(source_path)}"
        if install_path:
            return f"translation/{relative_path_from_install_path(install_path)}"
        return f"translation/{ensure_version_prefix(name)}"

    if source_path:
        return f"{feature}/{ensure_version_prefix(source_path)}"
    if install_path:
        return f"{feature}/{relative_path_from_install_path(install_path)}"
    return f"{feature}/{ensure_version_prefix(name)}"


def apply_mirror_paths(catalog: dict) -> dict:
    for pack in catalog.get("packs", {}).values():
        if pack.get("kind") == "adblock":
            continue
        for file_info in pack.get("files", []):
            file_info["mirrorPath"] = mirror_path_for_file(pack, file_info)
    return catalog


def bucket_path(bucket_dir: Path, mirror_path: str) -> Path:
    return bucket_dir / mirror_path.strip("/")


def mirror_url(base_url: str, mirror_path: str) -> str:
    return f"{base_url.rstrip('/')}/{mirror_path.strip('/')}"
