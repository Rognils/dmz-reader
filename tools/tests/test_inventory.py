"""Tester för inventory-genereringen mot de versionshanterade indatafilerna.

Kör:  python3 -m unittest discover -s tools/tests -v
"""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import archive_inventory as ai  # noqa: E402

DATA = ai.REPO_ROOT / "data"


@unittest.skipUnless((DATA / "raw_listing.json").exists() and (DATA / "https_probe.json").exists(),
                     "indatafiler saknas")
class Inventory(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        config = ai.load_config()
        cls.listing = ai.load_listing(DATA / "raw_listing.json")
        cls.analysis = ai.analyze(cls.listing, config["roots"], config["extraRoots"])
        cls.probe = ai.load_listing(DATA / "https_probe.json")
        cls.measurements = ai.load_listing(DATA / "sample_measurements.json")["measurements"]
        cls.built = ai.build_inventory(cls.listing, cls.analysis, cls.probe, cls.measurements)
        cls.issues = {i["issueId"]: i for i in cls.built["inventory"]["issues"]}

    def test_header(self):
        inventory = self.built["inventory"]
        self.assertEqual(inventory["schemaVersion"], 1)
        self.assertEqual(
            inventory["publications"],
            [{"publicationId": "datormagazin", "publicationTitle": "Datormagazin"},
             {"publicationId": "highscore", "publicationTitle": "HighScore"}])

    def test_unique_issue_ids(self):
        self.assertEqual(len(self.issues), len(self.built["inventory"]["issues"]))
        self.assertEqual(len(self.issues), 185)

    def test_every_issue_has_web_pdf_and_cover(self):
        for issue_id, issue in self.issues.items():
            self.assertTrue(issue["ocrPdfUrl"].startswith("https://dmzarkivet.se/pdf/"), issue_id)
            self.assertTrue(issue["coverUrl"].startswith("https://dmzarkivet.se/"), issue_id)
            self.assertGreater(issue["ocrPdfSize"], 0, issue_id)
            self.assertTrue(issue["ocrPdfVersion"]["etag"], issue_id)

    def test_ftp_generation_from_size(self):
        self.assertEqual(self.issues["datormagazin-1986-01"]["ocrPdfFtpSource"], "OCR PDF v2")
        self.assertEqual(self.issues["datormagazin-1995-14"]["ocrPdfFtpSource"], "OCR PDF")
        # 1993 nr 3: webbens fil finns inte på FTP. Webbversionen gäller.
        issue = self.issues["datormagazin-1993-03"]
        self.assertIsNone(issue["ocrPdfFtpSource"])
        self.assertIn("ocr_pdf_differs_from_ftp", issue["flags"])
        self.assertIn("OCR PDF/199303.pdf", issue["sourceNames"])

    def test_page_count_only_for_samples(self):
        known = {i: issue["pageCount"] for i, issue in self.issues.items()
                 if issue["pageCount"] is not None}
        self.assertEqual(known, {"datormagazin-1986-01": 28, "datormagazin-1995-14": 84})

    def test_related_issue_id(self):
        self.assertEqual(self.issues["highscore-1993-17"]["relatedIssueId"], "datormagazin-1993-17")
        self.assertIsNone(self.issues["datormagazin-1993-17"]["relatedIssueId"])
        for issue in self.issues.values():
            if issue["relatedIssueId"] is not None:
                self.assertIn(issue["relatedIssueId"], self.issues)

    def test_cover_url_follows_scan_file_name(self):
        self.assertEqual(self.issues["datormagazin-1993-03"]["coverUrl"],
                         "https://dmzarkivet.se/magazines/199303/DMZ_1993-03-01.jpg")
        self.assertEqual(self.issues["datormagazin-1987-04-05"]["coverUrl"],
                         "https://dmzarkivet.se/magazines/19870405/DMZ_1987-04-001.jpg")
        self.assertEqual(self.issues["highscore-1994-08"]["coverUrl"],
                         "https://dmzarkivet.se/highscore/199408/HighScore_1994-08_001.jpg")

    def test_scan_page_pattern(self):
        pattern = self.issues["datormagazin-1994-16"]["scanPagePattern"]
        self.assertEqual(
            pattern, "ftp://dmzarkivet.se/Original%20Scans/199416-Original/DMZ_1994-16-{page:02d}.jpg")
        self.assertTrue(self.issues["datormagazin-1986-04"]["scanPagePattern"].endswith("{page:03d}.png"))

    def test_sort_order_within_year(self):
        keys = [i["sortKey"] for i in self.built["inventory"]["issues"]
                if i["publicationId"] == "datormagazin"]
        self.assertEqual(keys, sorted(keys))

    def test_output_is_deterministic(self):
        again = ai.build_inventory(self.listing, self.analysis, self.probe, self.measurements)
        self.assertEqual(json.dumps(again["inventory"]), json.dumps(self.built["inventory"]))

    def test_committed_inventory_matches_generator(self):
        path = DATA / "archive_inventory.json"
        if not path.exists():
            self.skipTest("data/archive_inventory.json saknas")
        with open(path, encoding="utf-8") as fh:
            self.assertEqual(json.load(fh), self.built["inventory"])


if __name__ == "__main__":
    unittest.main()
