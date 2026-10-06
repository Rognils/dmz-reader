"""Tester för namnreglerna i archive_inventory.py.

Alla filnamn är verkliga och hämtade ur data/raw_listing.json.

Kör:  python3 -m unittest discover -s tools/tests -v
(fungerar även med pytest)
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import archive_inventory as ai  # noqa: E402

RAW_LISTING = ai.REPO_ROOT / "data" / "raw_listing.json"


def issue_id(path: str) -> str:
    result = ai.classify(path)
    assert result["kind"] != "special", f"{path}: {result.get('reason')}"
    return result["issueId"]


class OrdinaryIssues(unittest.TestCase):
    """Samma nummer ska få samma issueId i alla kataloger."""

    def test_same_identity_in_all_directories(self):
        for path in (
            "OCR PDF/198906.pdf",
            "OCR PDF v2/198906.pdf",
            "Large PDF/198906-Original.pdf",
            "Covers/DMZ_1989-06-001.jpg",
            "Original Scans/198906-Original/DMZ_1989-06-001.jpg",
            "Original Scans/198906-Original/DMZ_1989-06-052.jpg",
        ):
            self.assertEqual(issue_id(path), "datormagazin-1989-06", path)

    def test_kinds(self):
        self.assertEqual(ai.classify("OCR PDF/198601.pdf")["kind"], "ocr_pdf")
        self.assertEqual(ai.classify("OCR PDF v2/198601.pdf")["kind"], "ocr_pdf_v2")
        self.assertEqual(ai.classify("Large PDF/198601-Original.pdf")["kind"], "large_pdf")
        self.assertEqual(ai.classify("Covers/DMZ_1986-01-001.jpg")["kind"], "cover")
        scan = ai.classify("Original Scans/198601-Original/DMZ_1986-01-028.jpg")
        self.assertEqual(scan["kind"], "scan_page")
        self.assertEqual(scan["page"], 28)

    def test_first_and_last_issue(self):
        self.assertEqual(issue_id("OCR PDF/198601.pdf"), "datormagazin-1986-01")
        self.assertEqual(issue_id("OCR PDF/199515.pdf"), "datormagazin-1995-15")

    def test_number_above_twelve_is_not_a_double_issue(self):
        self.assertEqual(issue_id("OCR PDF/199122.pdf"), "datormagazin-1991-22")


class DoubleIssues(unittest.TestCase):
    def test_all_three_double_issues(self):
        self.assertEqual(issue_id("OCR PDF/19870405.pdf"), "datormagazin-1987-04-05")
        self.assertEqual(issue_id("OCR PDF/19881617.pdf"), "datormagazin-1988-16-17")
        self.assertEqual(issue_id("OCR PDF/19950506.pdf"), "datormagazin-1995-05-06")

    def test_double_issue_in_other_directories(self):
        for path in (
            "Large PDF/19881617-Original.pdf",
            "Covers/DMZ_1988-1617-001.jpg",
            "Original Scans/19881617-Original/DMZ_1988-1617-001.jpg",
        ):
            self.assertEqual(issue_id(path), "datormagazin-1988-16-17", path)
        self.assertEqual(issue_id("Covers/DMZ_1995-0506-001.jpg"), "datormagazin-1995-05-06")

    def test_label_sort_key_and_source_key(self):
        identity = ai.classify("OCR PDF/19881617.pdf")["identity"]
        self.assertEqual(ai.make_issue_label(identity), "Nr 16–17")
        self.assertEqual(ai.make_sort_key(identity), "1988-16")
        self.assertEqual(ai.make_source_key(identity), "19881617")

    def test_scans_named_after_first_number_only(self):
        # 1987 nr 4–5: katalogen heter 19870405 men filerna DMZ_1987-04-NNN.jpg.
        result = ai.classify("Original Scans/19870405-Original/DMZ_1987-04-001.jpg")
        self.assertEqual(result["issueId"], "datormagazin-1987-04-05")
        self.assertTrue(result["namedAfterFirstNumber"])

    def test_non_consecutive_eight_digits_is_not_guessed(self):
        self.assertEqual(ai.classify("OCR PDF/19881619.pdf")["kind"], "special")


class BVariants(unittest.TestCase):
    """198710b och 198916b är egna nummer vid sidan av 198710 och 198916."""

    def test_b_variant_is_its_own_issue(self):
        self.assertEqual(issue_id("Large PDF/198710-Original.pdf"), "datormagazin-1987-10")
        self.assertEqual(issue_id("Large PDF/198710b-Original.pdf"), "datormagazin-1987-10b")
        self.assertEqual(issue_id("OCR PDF v2/198916b.pdf"), "datormagazin-1989-16b")
        self.assertEqual(
            issue_id("Original Scans/198916b-Original/DMZ_1989-16b-001.jpg"),
            "datormagazin-1989-16b")

    def test_b_variant_sorts_directly_after_its_number(self):
        keys = [ai.make_sort_key(ai.classify(f"Large PDF/{name}-Original.pdf")["identity"])
                for name in ("198711", "198710b", "198710", "198709")]
        self.assertEqual(sorted(keys), ["1987-09", "1987-10", "1987-10b", "1987-11"])

    def test_other_suffix_letters_are_not_guessed(self):
        self.assertEqual(ai.classify("Large PDF/198710c-Original.pdf")["kind"], "special")


class HighScore(unittest.TestCase):
    """HighScore har samma arkivnyckel som DMZ-numret men är en egen publikation."""

    def test_highscore_in_all_directories(self):
        for path in (
            "OCR PDF/HighScore-199317.pdf",
            "Large PDF/199317-HighScore-Original.pdf",
            "Covers/HighScore_1993-17_001.jpg",
            "Original Scans/199317-HighScore-Original/HighScore_1993-17_032.jpg",
        ):
            self.assertEqual(issue_id(path), "highscore-1993-17", path)

    def test_highscore_does_not_collide_with_datormagazin(self):
        self.assertEqual(issue_id("OCR PDF/199317.pdf"), "datormagazin-1993-17")
        self.assertEqual(issue_id("Large PDF/199317-Original.pdf"), "datormagazin-1993-17")

    def test_source_key(self):
        identity = ai.classify("OCR PDF/HighScore-199408.pdf")["identity"]
        self.assertEqual(ai.make_source_key(identity), "HighScore-199408")

    def test_highscore_page_in_datormagazin_directory_is_special(self):
        result = ai.classify("Original Scans/199317-Original/HighScore_1993-17_001.jpg")
        self.assertEqual(result["kind"], "special")


class ScanFileVariants(unittest.TestCase):
    def test_two_digit_page_numbers(self):
        result = ai.classify("Original Scans/199416-Original/DMZ_1994-16-64.jpg")
        self.assertEqual(result["issueId"], "datormagazin-1994-16")
        self.assertEqual(result["page"], 64)
        self.assertEqual(result["pageDigits"], 2)

    def test_png_scans(self):
        result = ai.classify("Original Scans/198604-Original/DMZ_1986-04-033.png")
        self.assertEqual(result["issueId"], "datormagazin-1986-04")
        self.assertEqual((result["page"], result["ext"], result["part"]), (33, "png", ""))

    def test_spelbilaga_has_its_own_page_series(self):
        result = ai.classify("Original Scans/198604-Original/DMZ_1986-04-Spelbilaga-016.png")
        self.assertEqual(result["issueId"], "datormagazin-1986-04")
        self.assertEqual((result["page"], result["part"]), (16, "Spelbilaga"))

    def test_page_in_wrong_directory_is_special(self):
        result = ai.classify("Original Scans/198906-Original/DMZ_1989-07-001.jpg")
        self.assertEqual(result["kind"], "special")


class SpecialCases(unittest.TestCase):
    """Det som inte följer en regel ska bli specialfall, aldrig en gissning."""

    def test_real_special_cases(self):
        for path in (
            "Covers/DMZ_1986-01.png",
            "Covers/DMZ_1986-01.psd",
            "Original Scans/198601-Original/test.jpg",
        ):
            self.assertEqual(ai.classify(path)["kind"], "special", path)

    def test_cover_must_be_page_one(self):
        self.assertEqual(ai.classify("Covers/DMZ_1986-01-002.jpg")["kind"], "special")

    def test_files_outside_known_structure(self):
        for path in (
            "OCR PDF/sub/198601.pdf",
            "Large PDF/198601.pdf",
            "OCR PDF/198601-Original.pdf",
            "Original Scans/198601-Original.jpg",
        ):
            self.assertEqual(ai.classify(path)["kind"], "special", path)

    def test_issue_number_zero_is_special(self):
        self.assertEqual(ai.classify("OCR PDF/198600.pdf")["kind"], "special")


@unittest.skipUnless(RAW_LISTING.exists(), "data/raw_listing.json saknas")
class WholeArchive(unittest.TestCase):
    """Acceptanskriterium: varje fil är kopplad till ett issueId eller listad som specialfall."""

    @classmethod
    def setUpClass(cls):
        cls.config = ai.load_config()
        cls.listing = ai.load_listing(RAW_LISTING)
        cls.analysis = ai.analyze(cls.listing, cls.config["roots"], cls.config["extraRoots"])

    def test_every_file_is_matched_or_special(self):
        roots = set(self.config["roots"]) | set(self.config["extraRoots"])
        in_roots = {path for path, _, _ in ai.iter_files(self.listing)
                    if "/" in path and path.split("/")[0] in roots}
        matched = set()
        for issue in self.analysis["issues"].values():
            matched |= {f["path"] for f in issue["files"].values()}
            matched |= {p["path"] for p in issue["scanPages"].values()}
        special = {c["path"] for c in self.analysis["specialCases"] if c["size"] is not None}
        self.assertEqual(matched & special, set())
        self.assertEqual(matched | special, in_roots)

    def test_exactly_the_known_special_cases(self):
        self.assertEqual(
            [c["path"] for c in self.analysis["specialCases"]],
            ["Covers/DMZ_1986-01.png",
             "Covers/DMZ_1986-01.psd",
             "Original Scans/198601-Original/test.jpg"])

    def test_issue_counts(self):
        by_publication = ai._count(
            issue["identity"]["publicationId"] for issue in self.analysis["issues"].values())
        self.assertEqual(by_publication, {"datormagazin": 171, "highscore": 14})

    def test_cover_of_double_issue_named_after_first_number(self):
        issue = self.analysis["issues"]["datormagazin-1987-04-05"]
        self.assertEqual(issue["files"]["cover"]["path"], "Covers/DMZ_1987-04-001.jpg")
        self.assertNotIn("datormagazin-1987-04", self.analysis["issues"])

    def test_every_issue_has_large_pdf_and_scans(self):
        for issue_id_, issue in self.analysis["issues"].items():
            self.assertIn("large_pdf", issue["files"], issue_id_)
            self.assertTrue(issue["scanPages"], issue_id_)

    def test_analysis_is_deterministic(self):
        again = ai.analyze(self.listing, self.config["roots"], self.config["extraRoots"])
        self.assertEqual(list(again["issues"]), list(self.analysis["issues"]))
        self.assertEqual(again["specialCases"], self.analysis["specialCases"])


if __name__ == "__main__":
    unittest.main()
