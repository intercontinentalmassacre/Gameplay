#!/usr/bin/env python3
"""Build the checked-in component manifest from deterministic catalog sources."""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INDEX = ROOT / "catalog" / "index.yml"
DEFAULT_OUTPUT = ROOT / "manifest.json"
SOURCE_LAYOUT = (
    ("drivers.yml", "driver"),
    ("dxvk.yml", "dxvk"),
    ("proton.yml", "proton"),
    ("fex.yml", "fexcore"),
    ("wowbox64.yml", "wowbox64"),
    ("vkd3d.yml", "vkd3d"),
    ("wine.yml", "wine"),
    ("box64.yml", "box64"),
    ("audio.yml", "audio"),
)
LEGACY_TYPES = {group for _, group in SOURCE_LAYOUT if group != "audio"}
ENTRY_KEYS = {"id", "name", "url", "variant", "arch"}
V2_REQUIRED_KEYS = {
    "id",
    "name",
    "version",
    "channel",
    "abi",
    "archiveFormat",
    "urls",
    "sizeBytes",
    "sha256",
    "sourceRepository",
    "sourceCommit",
    "license",
    "pageSizes",
    "requiredFiles",
    "requires",
    "conflicts",
}
V2_OPTIONAL_KEYS = {"variant"}
CHANNELS = {"stable", "beta", "experimental"}
PAGE_SIZES = {4096, 16384}
ARCHIVE_SUFFIXES = (".zip", ".wcp", ".tzst", ".tar.xz", ".adpkg")
ARCHIVE_FORMAT_SUFFIXES = {
    "zip": ".zip",
    "wcp": ".wcp",
    "tzst": ".tzst",
    "tar.xz": ".tar.xz",
    "adpkg": ".adpkg",
}
LEGACY_DOWNLOAD_HOST = "downloads.gamenative.app"
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-fA-F]{7,64}$")


class CatalogError(ValueError):
    pass


def load_yaml_subset(path: Path) -> Any:
    """Load JSON, which is intentionally the dependency-free YAML 1.2 subset used here."""
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise CatalogError(f"missing catalog source: {path}") from error
    except json.JSONDecodeError as error:
        raise CatalogError(f"invalid JSON-compatible YAML in {path}: {error}") from error


def dump_yaml_subset(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def _safe_source_path(catalog_dir: Path, relative: str) -> Path:
    candidate = (catalog_dir / relative).resolve()
    if candidate.parent != catalog_dir.resolve():
        raise CatalogError(f"catalog source must be a direct child of catalog/: {relative}")
    return candidate


def _validate_https_url(url: Any, label: str, *, require_archive: bool = False) -> None:
    if not isinstance(url, str) or not url.strip():
        raise CatalogError(f"{label}: URL must be a non-empty string")
    parsed = urlparse(url)
    if parsed.scheme.lower() != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise CatalogError(f"{label}: URL must use HTTPS without embedded credentials")
    if require_archive and not parsed.path.lower().endswith(ARCHIVE_SUFFIXES):
        raise CatalogError(f"{label}: URL must point to a supported archive")


def _safe_archive_path(path: Any) -> bool:
    if not isinstance(path, str) or not path or path.startswith(("/", "\\")):
        return False
    if re.match(r"^[A-Za-z]:", path):
        return False
    return all(part not in {"", ".."} for part in path.replace("\\", "/").split("/"))


def _validate_v2_entry(entry: dict[str, Any], label: str) -> None:
    unknown = set(entry) - V2_REQUIRED_KEYS - V2_OPTIONAL_KEYS
    missing = V2_REQUIRED_KEYS - set(entry)
    if unknown or missing:
        raise CatalogError(f"{label}: unknown={sorted(unknown)} missing={sorted(missing)}")
    for key in ("id", "name", "version", "archiveFormat", "license"):
        if not isinstance(entry[key], str) or not entry[key].strip():
            raise CatalogError(f"{label}: {key} must be a non-empty string")
    expected_suffix = ARCHIVE_FORMAT_SUFFIXES.get(entry["archiveFormat"])
    if expected_suffix is None:
        raise CatalogError(f"{label}: unsupported archiveFormat {entry['archiveFormat']}")
    if entry["channel"] not in CHANNELS:
        raise CatalogError(f"{label}: unsupported channel {entry['channel']}")
    if not isinstance(entry["abi"], list) or not entry["abi"] or any(not isinstance(value, str) or not value for value in entry["abi"]):
        raise CatalogError(f"{label}: abi must be a non-empty string list")
    if not isinstance(entry["urls"], list) or not entry["urls"]:
        raise CatalogError(f"{label}: urls must not be empty")
    for url in entry["urls"]:
        _validate_https_url(url, label, require_archive=True)
        if not urlparse(url).path.lower().endswith(expected_suffix):
            raise CatalogError(f"{label}: URL does not match archiveFormat {entry['archiveFormat']}")
    if urlparse(entry["urls"][0]).hostname.lower() == LEGACY_DOWNLOAD_HOST:
        raise CatalogError(f"{label}: legacy GameNative host may only be used as a fallback URL")
    if not isinstance(entry["sizeBytes"], int) or isinstance(entry["sizeBytes"], bool) or entry["sizeBytes"] <= 0:
        raise CatalogError(f"{label}: sizeBytes must be a positive integer")
    if not isinstance(entry["sha256"], str) or not SHA256_RE.fullmatch(entry["sha256"]):
        raise CatalogError(f"{label}: sha256 must contain 64 hexadecimal characters")
    _validate_https_url(entry["sourceRepository"], f"{label} sourceRepository")
    if not isinstance(entry["sourceCommit"], str) or not COMMIT_RE.fullmatch(entry["sourceCommit"]):
        raise CatalogError(f"{label}: sourceCommit must be a commit hash")
    if not isinstance(entry["pageSizes"], list) or not entry["pageSizes"] or any(value not in PAGE_SIZES for value in entry["pageSizes"]):
        raise CatalogError(f"{label}: pageSizes contains an unsupported value")
    if not isinstance(entry["requiredFiles"], list) or not entry["requiredFiles"]:
        raise CatalogError(f"{label}: requiredFiles must not be empty")
    if any(not _safe_archive_path(path) for path in entry["requiredFiles"]):
        raise CatalogError(f"{label}: requiredFiles contains an unsafe path")
    for key in ("requires", "conflicts"):
        if not isinstance(entry[key], list) or any(not isinstance(value, str) or not value for value in entry[key]):
            raise CatalogError(f"{label}: {key} must be a string list")


def load_and_validate_sources(index_path: Path = DEFAULT_INDEX) -> tuple[dict[str, Any], dict[str, list[dict[str, Any]]]]:
    index = load_yaml_subset(index_path)
    if not isinstance(index, dict):
        raise CatalogError("catalog/index.yml must contain an object")
    source_schema = index.get("sourceSchemaVersion")
    if source_schema not in {1, 2}:
        raise CatalogError("sourceSchemaVersion must be 1 or 2")
    output = index.get("output")
    if not isinstance(output, dict):
        raise CatalogError("index output must be an object")
    if source_schema == 1:
        if output.get("version") != 1 or not output.get("updatedAt"):
            raise CatalogError("v1 index output must contain version=1 and updatedAt")
    elif set(output) != {"schemaVersion", "catalogVersion", "generatedAt"} or output.get("schemaVersion") != 2:
        raise CatalogError("v2 index output must contain schemaVersion=2, catalogVersion and generatedAt")
    else:
        if not isinstance(output["catalogVersion"], str) or not output["catalogVersion"].strip():
            raise CatalogError("v2 catalogVersion must not be blank")
        try:
            generated_at = datetime.fromisoformat(output["generatedAt"].replace("Z", "+00:00"))
            if generated_at.tzinfo is None:
                raise ValueError("timezone required")
        except (AttributeError, ValueError) as error:
            raise CatalogError("v2 generatedAt must be an ISO-8601 timestamp with timezone") from error
    sources = index.get("sources")
    if not isinstance(sources, list) or not sources:
        raise CatalogError("index sources must be a non-empty list")

    groups: dict[str, list[dict[str, Any]]] = {}
    group_schemas: dict[str, int] = {}
    ids: set[str] = set()
    for source in sources:
        if not isinstance(source, dict) or not {"path", "type"}.issubset(source) or set(source) - {"path", "type", "schemaVersion"}:
            raise CatalogError("each index source must contain path, type and optional schemaVersion")
        relative = source["path"]
        expected_type = source["type"]
        entry_schema = source.get("schemaVersion", source_schema)
        if not isinstance(relative, str) or not isinstance(expected_type, str):
            raise CatalogError("source path and type must be strings")
        if expected_type in groups:
            raise CatalogError(f"duplicate catalog type: {expected_type}")
        if entry_schema not in {1, 2}:
            raise CatalogError(f"{relative}: schemaVersion must be 1 or 2")
        if source_schema == 2 and entry_schema != 2:
            raise CatalogError(f"{relative}: schema v2 output cannot contain legacy source entries")
        document = load_yaml_subset(_safe_source_path(index_path.parent, relative))
        if not isinstance(document, dict) or set(document) != {"type", "components"}:
            raise CatalogError(f"{relative} must contain exactly type and components")
        if document["type"] != expected_type:
            raise CatalogError(f"{relative}: expected type {expected_type}, found {document['type']}")
        components = document["components"]
        if not isinstance(components, list):
            raise CatalogError(f"{relative}: components must be a list")

        validated: list[dict[str, Any]] = []
        for position, entry in enumerate(components):
            label = f"{relative} components[{position}]"
            if not isinstance(entry, dict):
                raise CatalogError(f"{label}: entry must be an object")
            if entry_schema == 1:
                unknown = set(entry) - ENTRY_KEYS
                missing = {"id", "name", "url"} - set(entry)
                if unknown or missing:
                    raise CatalogError(f"{label}: unknown={sorted(unknown)} missing={sorted(missing)}")
                if any(not isinstance(entry[key], str) or not entry[key].strip() for key in ("id", "name", "url")):
                    raise CatalogError(f"{label}: id, name and url must be non-empty strings")
                _validate_https_url(entry["url"], label, require_archive=True)
            else:
                _validate_v2_entry(entry, label)
            if entry["id"] in ids:
                raise CatalogError(f"duplicate component id: {entry['id']}")
            ids.add(entry["id"])
            validated.append(entry)
        groups[expected_type] = validated
        group_schemas[expected_type] = entry_schema

    if 2 in group_schemas.values():
        entries_by_id = {entry["id"]: entry for entries in groups.values() for entry in entries}
        entry_schemas = {
            entry["id"]: group_schemas[group]
            for group, entries in groups.items()
            for entry in entries
        }
        stable_keys: set[tuple[Any, ...]] = set()
        for group, entries in groups.items():
            if group_schemas[group] != 2:
                continue
            for entry in entries:
                label = entry["id"]
                if group not in LEGACY_TYPES:
                    raise CatalogError(f"{label}: unsupported install type {group}")
                if entry["channel"] == "stable":
                    stable_key = (group, entry["version"], entry.get("variant"), tuple(entry["abi"]))
                    if stable_key in stable_keys:
                        raise CatalogError(f"duplicate stable version key: {stable_key}")
                    stable_keys.add(stable_key)
                for dependency in entry["requires"]:
                    target = entries_by_id.get(dependency)
                    if target is None:
                        raise CatalogError(f"{label}: missing dependency {dependency}")
                    if entry_schemas[dependency] != 2:
                        raise CatalogError(f"{label}: dependency {dependency} still has unverified legacy metadata")
                    if entry["channel"] == "stable" and target["channel"] == "experimental":
                        raise CatalogError(f"{label}: stable component depends on experimental component {dependency}")
    return output, groups


def generate_manifest(index_path: Path = DEFAULT_INDEX) -> str:
    output, groups = load_and_validate_sources(index_path)
    index = load_yaml_subset(index_path)
    group_schemas = {
        source["type"]: source.get("schemaVersion", index["sourceSchemaVersion"])
        for source in index["sources"]
    }
    if output.get("schemaVersion") == 2:
        components = [dict(entry, type=group) for group, entries in groups.items() for entry in entries]
        return dump_yaml_subset(
            {
                "schemaVersion": 2,
                "catalogVersion": output["catalogVersion"],
                "generatedAt": output["generatedAt"],
                "components": components,
            }
        )
    items: dict[str, list[dict[str, Any]]] = {}
    for group, entries in groups.items():
        if not entries:
            continue
        if group not in LEGACY_TYPES:
            raise CatalogError(f"legacy manifest cannot contain non-empty type: {group}")
        if group_schemas[group] == 2:
            items[group] = [
                {
                    "id": entry["id"],
                    "name": entry["name"],
                    "url": entry["urls"][0],
                    **({"variant": entry["variant"]} if "variant" in entry else {}),
                }
                for entry in entries
            ]
        else:
            items[group] = entries
    return dump_yaml_subset({"version": 1, "updatedAt": output["updatedAt"], "items": items})


def bootstrap_from_legacy(output_path: Path = DEFAULT_OUTPUT, index_path: Path = DEFAULT_INDEX) -> None:
    legacy = load_yaml_subset(output_path)
    if not isinstance(legacy, dict) or legacy.get("version") != 1 or not isinstance(legacy.get("items"), dict):
        raise CatalogError("bootstrap input must be a legacy version 1 manifest")
    catalog_dir = index_path.parent
    targets = [index_path, *(catalog_dir / filename for filename, _ in SOURCE_LAYOUT)]
    existing = [str(path) for path in targets if path.exists()]
    if existing:
        raise CatalogError("refusing to overwrite existing catalog sources: " + ", ".join(existing))
    catalog_dir.mkdir(parents=True, exist_ok=True)
    sources = []
    for filename, group in SOURCE_LAYOUT:
        (catalog_dir / filename).write_text(
            dump_yaml_subset({"type": group, "components": legacy["items"].get(group, [])}),
            encoding="utf-8",
        )
        sources.append({"path": filename, "type": group})
    index_path.write_text(
        dump_yaml_subset(
            {
                "sourceSchemaVersion": 1,
                "output": {"version": 1, "updatedAt": legacy.get("updatedAt")},
                "sources": sources,
            }
        ),
        encoding="utf-8",
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if manifest.json is stale")
    parser.add_argument("--bootstrap-from-legacy", action="store_true", help="split the current v1 manifest once")
    parser.add_argument("--index", type=Path, default=DEFAULT_INDEX)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)
    try:
        if args.bootstrap_from_legacy:
            bootstrap_from_legacy(args.output, args.index)
        generated = generate_manifest(args.index)
        if args.check:
            current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
            if current != generated:
                raise CatalogError(f"{args.output} is stale; run scripts/catalog/generate_catalog.py")
            print(f"catalog ok: {args.output}")
        else:
            args.output.write_text(generated, encoding="utf-8")
            print(f"generated: {args.output}")
        return 0
    except CatalogError as error:
        print(f"catalog error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
