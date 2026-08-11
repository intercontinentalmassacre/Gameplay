import json
import tempfile
import unittest
from pathlib import Path

from generate_catalog import CatalogError, generate_manifest, load_and_validate_sources


class GenerateCatalogTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.catalog_dir = Path(self.temp_dir.name) / "catalog"
        self.catalog_dir.mkdir()

    def tearDown(self):
        self.temp_dir.cleanup()

    def write_source(self, entries):
        (self.catalog_dir / "dxvk.yml").write_text(
            json.dumps({"type": "dxvk", "components": entries}),
            encoding="utf-8",
        )
        index = {
            "sourceSchemaVersion": 1,
            "output": {"version": 1, "updatedAt": "2026-08-05"},
            "sources": [{"path": "dxvk.yml", "type": "dxvk"}],
        }
        index_path = self.catalog_dir / "index.yml"
        index_path.write_text(json.dumps(index), encoding="utf-8")
        return index_path

    def write_v2_source(self, entries):
        (self.catalog_dir / "fex.yml").write_text(
            json.dumps({"type": "fexcore", "components": entries}),
            encoding="utf-8",
        )
        index = {
            "sourceSchemaVersion": 2,
            "output": {
                "schemaVersion": 2,
                "catalogVersion": "2026.08.1",
                "generatedAt": "2026-08-05T00:00:00Z",
            },
            "sources": [{"path": "fex.yml", "type": "fexcore"}],
        }
        index_path = self.catalog_dir / "index.yml"
        index_path.write_text(json.dumps(index), encoding="utf-8")
        return index_path

    def test_generation_is_deterministic(self):
        index = self.write_source(
            [{"id": "dxvk-2.7", "name": "DXVK 2.7", "url": "https://example.com/dxvk.wcp"}]
        )
        first = generate_manifest(index)
        second = generate_manifest(index)
        self.assertEqual(first, second)
        self.assertEqual("dxvk-2.7", json.loads(first)["items"]["dxvk"][0]["id"])

    def test_duplicate_ids_are_rejected(self):
        entry = {"id": "same", "name": "Same", "url": "https://example.com/a.wcp"}
        index = self.write_source([entry, entry])
        with self.assertRaisesRegex(CatalogError, "duplicate component id"):
            load_and_validate_sources(index)

    def test_non_https_urls_are_rejected(self):
        index = self.write_source(
            [{"id": "dxvk", "name": "DXVK", "url": "http://example.com/dxvk.wcp"}]
        )
        with self.assertRaisesRegex(CatalogError, "must use HTTPS"):
            load_and_validate_sources(index)

    def test_source_cannot_escape_catalog_directory(self):
        outside = Path(self.temp_dir.name) / "outside.yml"
        outside.write_text("{}", encoding="utf-8")
        index = {
            "sourceSchemaVersion": 1,
            "output": {"version": 1, "updatedAt": "2026-08-05"},
            "sources": [{"path": "../outside.yml", "type": "dxvk"}],
        }
        index_path = self.catalog_dir / "index.yml"
        index_path.write_text(json.dumps(index), encoding="utf-8")
        with self.assertRaisesRegex(CatalogError, "direct child"):
            load_and_validate_sources(index_path)

    def test_v2_generation_preserves_verified_metadata(self):
        index = self.write_v2_source([self.v2_entry()])

        generated = json.loads(generate_manifest(index))

        self.assertEqual(2, generated["schemaVersion"])
        self.assertEqual("fexcore", generated["components"][0]["type"])
        self.assertEqual(123456, generated["components"][0]["sizeBytes"])

    def test_v2_rejects_missing_dependency(self):
        entry = self.v2_entry()
        entry["requires"] = ["missing-runtime"]
        index = self.write_v2_source([entry])

        with self.assertRaisesRegex(CatalogError, "missing dependency"):
            load_and_validate_sources(index)

    def test_v2_rejects_placeholder_integrity_metadata(self):
        entry = self.v2_entry()
        entry["sizeBytes"] = 0
        entry["sha256"] = "unknown"
        index = self.write_v2_source([entry])

        with self.assertRaisesRegex(CatalogError, "sizeBytes"):
            load_and_validate_sources(index)

    def test_v2_rejects_archive_format_mismatch(self):
        entry = self.v2_entry()
        entry["archiveFormat"] = "zip"
        index = self.write_v2_source([entry])

        with self.assertRaisesRegex(CatalogError, "does not match archiveFormat"):
            load_and_validate_sources(index)

    def test_v2_rejects_legacy_host_as_primary_url(self):
        entry = self.v2_entry()
        entry["urls"] = ["https://downloads.gamenative.app/fex.wcp"]
        index = self.write_v2_source([entry])

        with self.assertRaisesRegex(CatalogError, "may only be used as a fallback"):
            load_and_validate_sources(index)

    def test_v2_allows_legacy_host_as_fallback_url(self):
        entry = self.v2_entry()
        entry["urls"].append("https://downloads.gamenative.app/fex.wcp")
        index = self.write_v2_source([entry])

        load_and_validate_sources(index)

    def test_v1_output_down_converts_a_migrated_source(self):
        entry = self.v2_entry()
        (self.catalog_dir / "fex.yml").write_text(
            json.dumps({"type": "fexcore", "components": [entry]}),
            encoding="utf-8",
        )
        index = {
            "sourceSchemaVersion": 1,
            "output": {"version": 1, "updatedAt": "2026-08-05"},
            "sources": [{"path": "fex.yml", "type": "fexcore", "schemaVersion": 2}],
        }
        index_path = self.catalog_dir / "index.yml"
        index_path.write_text(json.dumps(index), encoding="utf-8")

        generated = json.loads(generate_manifest(index_path))

        self.assertEqual(
            {
                "id": entry["id"],
                "name": entry["name"],
                "url": entry["urls"][0],
                "variant": entry["variant"],
            },
            generated["items"]["fexcore"][0],
        )

    def test_migrated_source_rejects_dependency_on_legacy_metadata(self):
        migrated = self.v2_entry()
        migrated["requires"] = ["legacy-dxvk"]
        (self.catalog_dir / "fex.yml").write_text(
            json.dumps({"type": "fexcore", "components": [migrated]}),
            encoding="utf-8",
        )
        (self.catalog_dir / "dxvk.yml").write_text(
            json.dumps(
                {
                    "type": "dxvk",
                    "components": [
                        {
                            "id": "legacy-dxvk",
                            "name": "Legacy DXVK",
                            "url": "https://example.com/dxvk.wcp",
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )
        index = {
            "sourceSchemaVersion": 1,
            "output": {"version": 1, "updatedAt": "2026-08-05"},
            "sources": [
                {"path": "fex.yml", "type": "fexcore", "schemaVersion": 2},
                {"path": "dxvk.yml", "type": "dxvk"},
            ],
        }
        index_path = self.catalog_dir / "index.yml"
        index_path.write_text(json.dumps(index), encoding="utf-8")

        with self.assertRaisesRegex(CatalogError, "unverified legacy metadata"):
            load_and_validate_sources(index_path)

    @staticmethod
    def v2_entry():
        return {
            "id": "fex-2607-arm64ec",
            "name": "FEX 2607 ARM64EC",
            "version": "2607",
            "channel": "experimental",
            "variant": "bionic",
            "abi": ["arm64-v8a"],
            "archiveFormat": "wcp",
            "urls": ["https://example.com/fex.wcp"],
            "sizeBytes": 123456,
            "sha256": "a" * 64,
            "sourceRepository": "https://github.com/FEX-Emu/FEX",
            "sourceCommit": "b" * 40,
            "license": "MIT",
            "pageSizes": [4096, 16384],
            "requiredFiles": ["windows/system32/libwow64fex.dll"],
            "requires": [],
            "conflicts": ["wowbox64-*"],
        }


if __name__ == "__main__":
    unittest.main()
