"""Tester för tools/make_search_index_manifest.py."""

import hashlib
import json
import shutil
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import make_search_index_manifest as msm  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
TEST_DB = REPO_ROOT / "app" / "src" / "debug" / "assets" / "search_test.db"


class Manifest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def index(self, name="search-2026-10-06-006-p3.db", privacy="3"):
        """Testindexet, med privacyFilterVersion som i ett maskerat index."""
        path = self.root / name
        shutil.copy(TEST_DB, path)
        db = sqlite3.connect(path)
        if privacy is not None:
            db.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('privacyFilterVersion', ?)", (privacy,))
        db.commit()
        db.close()
        return path

    def test_the_manifest_describes_the_file(self):
        path = self.index()
        manifest = msm.build_manifest(path, "v1.0.0")
        self.assertEqual(manifest, {
            "manifestVersion": 1,
            "releaseTag": "v1.0.0",
            "fileName": "search-2026-10-06-006-p3.db",
            "size": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "searchSchemaVersion": "1",
            "inventoryDatasetVersion": "2026-10-06-006",
            "normalizerVersion": "2",
            "privacyFilterVersion": "3",
            "minAppVersionCode": 9904,
        })

    def test_the_command_writes_the_manifest_next_to_the_file(self):
        path = self.index()
        self.assertEqual(msm.main([str(path), "v1.0.0", "--min-app-version-code", "10000"]), 0)
        written = json.loads((self.root / "search-index.json").read_text(encoding="utf-8"))
        self.assertEqual(written["minAppVersionCode"], 10000)
        self.assertEqual(written["fileName"], path.name)

    def test_an_unmasked_index_is_refused(self):
        path = self.index(privacy=None)
        with self.assertRaisesRegex(msm.ManifestError, "inte maskerat"):
            msm.build_manifest(path, "v1.0.0")
        self.assertEqual(msm.main([str(path), "v1.0.0"]), 1)
        self.assertFalse((self.root / "search-index.json").exists())

    def test_the_unmasked_working_index_is_refused_by_name(self):
        path = self.index(name="search.db")
        with self.assertRaisesRegex(msm.ManifestError, "omaskerade"):
            msm.build_manifest(path, "v1.0.0")

    def test_names_that_do_not_belong_in_an_address_are_refused(self):
        path = self.index()
        for tag in ("v1.0.0/../x", "", "v 1"):
            with self.assertRaises(msm.ManifestError):
                msm.build_manifest(path, tag)
        odd = self.index(name="search index.db")
        with self.assertRaises(msm.ManifestError):
            msm.build_manifest(odd, "v1.0.0")

    def test_a_file_that_is_not_a_database_is_refused(self):
        path = self.root / "search-x.db"
        path.write_bytes(b"inte en databas")
        with self.assertRaises(msm.ManifestError):
            msm.build_manifest(path, "v1.0.0")


if __name__ == "__main__":
    unittest.main()
