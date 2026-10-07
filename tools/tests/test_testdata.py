"""Tester för testindexen till appens import av arkivindex (tools/make_test_inventories.py)."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import archive_inventory as ai  # noqa: E402
import make_test_inventories as mti  # noqa: E402


class TestInventories(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        config = ai.load_config()
        with open(ai.REPO_ROOT / config["inventoryPath"], encoding="utf-8") as fh:
            cls.real = json.load(fh)
        cls.schema = ai.load_schema(ai.REPO_ROOT / config["schemaPath"])
        cls.files = mti.build_all(cls.real)

    def test_abc_is_a_valid_inventory_with_the_three_changes(self):
        abc = json.loads(self.files["inventory_test_abc.json"])
        self.assertEqual(ai.validate_inventory(abc, self.schema), [])
        ids = {issue["issueId"] for issue in abc["issues"]}
        real_ids = {issue["issueId"] for issue in self.real["issues"]}
        self.assertEqual(ids - real_ids, {mti.NEW_ISSUE_ID})
        self.assertEqual(real_ids - ids, {mti.REMOVED_ISSUE_ID})
        changed = mti.find(abc, mti.CHANGED_ISSUE_ID)
        original = mti.find(self.real, mti.CHANGED_ISSUE_ID)
        self.assertNotEqual(changed["ocrPdfSize"], original["ocrPdfSize"])
        self.assertNotEqual(changed["ocrPdfEtag"], original["ocrPdfEtag"])
        self.assertEqual(changed["ocrPdfUrl"], original["ocrPdfUrl"])
        self.assertNotEqual(abc["datasetVersion"], self.real["datasetVersion"])

    def test_the_new_issue_points_at_material_that_exists(self):
        abc = json.loads(self.files["inventory_test_abc.json"])
        new = mti.find(abc, mti.NEW_ISSUE_ID)
        self.assertEqual(new["coverUrl"], mti.find(self.real, mti.COVER_FROM)["coverUrl"])
        self.assertEqual(new["ocrPdfUrl"], mti.find(self.real, mti.PDF_FROM)["ocrPdfUrl"])
        # Miniatyren finns inte bland dem som följer med appen.
        self.assertFalse((ai.REPO_ROOT / "data" / new["coverAsset"]).exists())

    def test_broken_variants_fail_the_way_they_are_meant_to(self):
        schema_errors = ai.validate_inventory(json.loads(self.files["inventory_broken_schema.json"]), self.schema)
        self.assertTrue(any("schemaVersion" in error for error in schema_errors), schema_errors)
        duplicate_errors = ai.validate_inventory(json.loads(self.files["inventory_broken_duplicate.json"]), self.schema)
        self.assertTrue(any("inte unikt" in error for error in duplicate_errors), duplicate_errors)
        related_errors = ai.validate_inventory(json.loads(self.files["inventory_broken_related.json"]), self.schema)
        self.assertTrue(any("relatedIssueId" in error for error in related_errors), related_errors)
        with self.assertRaises(json.JSONDecodeError):
            json.loads(self.files["inventory_broken_truncated.json"])

    def test_committed_files_match_the_generator(self):
        for name, text in self.files.items():
            path = mti.OUTPUT_DIR / name
            self.assertTrue(path.is_file(), f"{name} saknas. Kör: python3 tools/make_test_inventories.py")
            self.assertEqual(path.read_text(encoding="utf-8"), text, name)


if __name__ == "__main__":
    unittest.main()
