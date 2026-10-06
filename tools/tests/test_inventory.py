"""Tester för inventory-genereringen (FAS 0B).

Täcker: fälten i inventory, manuella rättelser, schemavalidering, övrig
validering, jämförelse mot föregående inventory och determinism.

Kör:  python3 -m unittest discover -s tools/tests -v
Kräver paketet jsonschema (tools/requirements.txt).
"""

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import archive_inventory as ai  # noqa: E402

DATA = ai.REPO_ROOT / "data"
TOOL = ai.REPO_ROOT / "tools" / "archive_inventory.py"


class Generated(unittest.TestCase):
    """Bas: genererar inventory en gång från de versionshanterade indatafilerna."""

    @classmethod
    def setUpClass(cls):
        cls.config = ai.load_config()
        cls.listing = ai.load_listing(DATA / "raw_listing.json")
        cls.schema = ai.load_schema(ai.REPO_ROOT / cls.config["schemaPath"])
        cls.result = ai.generate(cls.config, cls.listing)
        cls.inventory = cls.result["inventory"]
        cls.diagnostics = cls.result["diagnostics"]
        cls.issues = {i["issueId"]: i for i in cls.inventory["issues"]}

    def fresh(self) -> dict:
        return copy.deepcopy(self.inventory)

    def errors(self, inventory: dict) -> list[str]:
        return ai.validate_inventory(inventory, self.schema)


class InventoryContent(Generated):
    FIELDS = {
        "issueId", "publicationId", "publicationTitle", "year", "issueLabel", "sortKey",
        "sourceKey", "sourceNames", "relatedIssueId", "coverUrl", "coverFlags", "ocrPdfUrl",
        "ocrPdfSize", "ocrPdfEtag", "ocrPdfLastModified", "largePdfUrl", "largePdfSize",
        "hqSource", "scanPagePattern", "scanPageCount", "pageCount", "spreadOffset", "flags",
    }

    def test_generated_inventory_is_valid(self):
        self.assertEqual(self.result["errors"], [])

    def test_header(self):
        self.assertEqual(self.inventory["schemaVersion"], 1)
        self.assertRegex(self.inventory["datasetVersion"], r"^\d{4}-\d{2}-\d{2}-\d{3}$")
        self.assertEqual(self.inventory["generatedAt"],
                         self.inventory["datasetVersion"][:10] + "T00:00:00Z")
        self.assertEqual(
            self.inventory["publications"],
            [{"publicationId": "datormagazin", "publicationTitle": "Datormagazin"},
             {"publicationId": "highscore", "publicationTitle": "HighScore"}])

    def test_exactly_the_planned_fields(self):
        for issue_id, issue in self.issues.items():
            self.assertEqual(set(issue), self.FIELDS, issue_id)

    def test_toolchain_only_data_is_not_in_inventory(self):
        text = json.dumps(self.inventory)
        for word in ("ocrPdfFtpSource", "ocrPdfChecksum", "ocrPdfVersion", "sha256",
                     "no_ftp_cover", "ocr_pdf_differs_from_ftp"):
            self.assertNotIn(word, text)

    def test_counts(self):
        self.assertEqual(len(self.issues), 185)
        self.assertEqual(ai._count(i["publicationId"] for i in self.issues.values()),
                         {"datormagazin": 171, "highscore": 14})

    def test_every_issue_has_web_pdf_and_cover(self):
        for issue_id, issue in self.issues.items():
            self.assertTrue(issue["ocrPdfUrl"].startswith("https://dmzarkivet.se/pdf/"), issue_id)
            self.assertTrue(issue["coverUrl"].startswith("https://dmzarkivet.se/"), issue_id)
            self.assertGreater(issue["ocrPdfSize"], 0, issue_id)
            self.assertTrue(issue["ocrPdfEtag"], issue_id)
            self.assertTrue(issue["ocrPdfLastModified"], issue_id)

    def test_scan_page_count_for_every_issue(self):
        for issue_id, issue in self.issues.items():
            self.assertIsInstance(issue["scanPageCount"], int, issue_id)
        self.assertEqual(self.issues["datormagazin-1986-01"]["scanPageCount"], 28)  # utan test.jpg
        self.assertEqual(self.issues["datormagazin-1995-14"]["scanPageCount"], 84)
        self.assertEqual(sum(i["scanPageCount"] for i in self.issues.values()), 11297 - 16)

    def test_supplement_pages_are_not_counted(self):
        issue = self.issues["datormagazin-1986-04"]
        self.assertEqual(issue["scanPageCount"], 33)
        self.assertIn("scan_supplement", issue["flags"])
        self.assertEqual(self.diagnostics["issues"]["datormagazin-1986-04"]["scanParts"],
                         {"main": 33, "Spelbilaga": 16})
        with_flag = [i for i, issue in self.issues.items() if "scan_supplement" in issue["flags"]]
        self.assertEqual(with_flag, ["datormagazin-1986-04"])

    def test_page_count_only_for_samples(self):
        known = {i: issue["pageCount"] for i, issue in self.issues.items()
                 if issue["pageCount"] is not None}
        self.assertEqual(known, {"datormagazin-1986-01": 28, "datormagazin-1995-14": 84})

    def test_related_issue_id(self):
        self.assertEqual(self.issues["highscore-1993-17"]["relatedIssueId"], "datormagazin-1993-17")
        self.assertIsNone(self.issues["datormagazin-1993-17"]["relatedIssueId"])
        related = [i for i in self.issues.values() if i["relatedIssueId"] is not None]
        self.assertEqual({i["publicationId"] for i in related}, {"highscore"})
        self.assertEqual(len(related), 14)

    def test_cover_url_follows_scan_file_name(self):
        self.assertEqual(self.issues["datormagazin-1993-03"]["coverUrl"],
                         "https://dmzarkivet.se/magazines/199303/DMZ_1993-03-01.jpg")
        self.assertEqual(self.issues["datormagazin-1987-04-05"]["coverUrl"],
                         "https://dmzarkivet.se/magazines/19870405/DMZ_1987-04-001.jpg")
        self.assertEqual(self.issues["highscore-1994-08"]["coverUrl"],
                         "https://dmzarkivet.se/highscore/199408/HighScore_1994-08_001.jpg")

    def test_scan_page_pattern(self):
        self.assertEqual(
            self.issues["datormagazin-1994-16"]["scanPagePattern"],
            "ftp://dmzarkivet.se/Original%20Scans/199416-Original/DMZ_1994-16-{page:02d}.jpg")
        self.assertTrue(self.issues["datormagazin-1986-04"]["scanPagePattern"].endswith("{page:03d}.png"))

    def test_only_known_flags(self):
        known = {"double_issue", "variant_b", "supplement", "scan_two_digit_pages", "scan_png",
                 "scan_supplement", "scan_named_after_first_number",
                 "missing_cover", "missing_ocr_pdf"}
        used = {flag for issue in self.issues.values() for flag in issue["flags"]}
        self.assertLessEqual(used, known)

    def test_diagnostics_hold_the_ftp_comparison(self):
        diag = self.diagnostics["issues"]
        self.assertEqual(diag["datormagazin-1986-01"]["ocrPdfFtpSource"], "OCR PDF v2")
        self.assertEqual(diag["datormagazin-1995-14"]["ocrPdfFtpSource"], "OCR PDF")
        self.assertIsNone(diag["datormagazin-1993-03"]["ocrPdfFtpSource"])
        self.assertFalse(diag["datormagazin-1993-03"]["webPdfMatchesFtp"])
        self.assertEqual(ai._count(d["ocrPdfFtpSource"] for d in diag.values()),
                         {"OCR PDF v2": 90, "OCR PDF": 94, None: 1})
        self.assertEqual(sum(d["ftpCoverPath"] is None for d in diag.values()), 38)
        self.assertEqual(len(self.diagnostics["specialCases"]), 3)


class MissingMaterial(Generated):
    """Ett nummer utan läs-PDF eller omslag exporteras ändå, med flagga och varning."""

    PDF = "/pdf/198906.pdf"
    COVER = "/magazines/198906/DMZ_1989-06-001.jpg"
    ISSUE = "datormagazin-1989-06"

    def build(self, **status_by_path):
        analysis = ai.analyze(self.listing, self.config["roots"], self.config["extraRoots"])
        probe = copy.deepcopy(ai._read_json(DATA / "https_probe.json"))
        for path, status in status_by_path.items():
            self.assertIn(path, probe["results"])
            probe["results"][path] = {"status": status}
        inventory, diagnostics = ai.build_inventory(
            self.listing, analysis, probe, {}, self.inventory["datasetVersion"])
        issue = next(i for i in inventory["issues"] if i["issueId"] == self.ISSUE)
        return inventory, diagnostics, issue

    def test_nothing_is_missing_in_the_real_archive(self):
        self.assertEqual(self.diagnostics["warnings"], [])
        for issue in self.issues.values():
            self.assertNotIn("missing_cover", issue["flags"])
            self.assertNotIn("missing_ocr_pdf", issue["flags"])

    def test_missing_cover(self):
        inventory, diagnostics, issue = self.build(**{self.COVER: 404})
        self.assertIn("coverUrl", issue)
        self.assertIsNone(issue["coverUrl"])
        self.assertEqual(issue["flags"], ["missing_cover"])
        self.assertIsNotNone(issue["ocrPdfUrl"])
        self.assertEqual(len(inventory["issues"]), 185)
        self.assertEqual(len(diagnostics["warnings"]), 1)
        self.assertIn(f"{self.ISSUE}: ingen sidbild 1", diagnostics["warnings"][0])
        self.assertEqual(self.errors(inventory), [])

    def test_missing_ocr_pdf(self):
        inventory, diagnostics, issue = self.build(**{self.PDF: 500})
        self.assertIn("ocrPdfUrl", issue)
        self.assertIsNone(issue["ocrPdfUrl"])
        self.assertIsNone(issue["ocrPdfSize"])
        self.assertIsNone(issue["ocrPdfEtag"])
        self.assertIsNone(issue["ocrPdfLastModified"])
        self.assertEqual(issue["flags"], ["missing_ocr_pdf"])
        self.assertIsNotNone(issue["coverUrl"])
        self.assertEqual(len(inventory["issues"]), 185)
        self.assertEqual(len(diagnostics["warnings"]), 1)
        self.assertIn(f"{self.ISSUE}: ingen läs-PDF", diagnostics["warnings"][0])
        self.assertIsNone(diagnostics["issues"][self.ISSUE]["ocrPdfFtpSource"])
        self.assertEqual(self.errors(inventory), [])

    def test_both_missing(self):
        inventory, diagnostics, issue = self.build(**{self.PDF: 404, self.COVER: 404})
        self.assertEqual(issue["flags"], ["missing_cover", "missing_ocr_pdf"])
        self.assertEqual(len(diagnostics["warnings"]), 2)
        self.assertEqual(self.errors(inventory), [])

    def test_measured_page_count_is_not_used_without_pdf(self):
        analysis = ai.analyze(self.listing, self.config["roots"], self.config["extraRoots"])
        probe = copy.deepcopy(ai._read_json(DATA / "https_probe.json"))
        probe["results"]["/pdf/198601.pdf"] = {"status": 404}
        measurements = ai._read_json(DATA / "sample_measurements.json")["measurements"]
        inventory, _ = ai.build_inventory(self.listing, analysis, probe, measurements,
                                          self.inventory["datasetVersion"])
        self.assertIsNone(inventory["issues"][0]["pageCount"])

    def test_warning_is_printed_by_the_tool(self):
        """Varningen ska synas på stdout, och exporten ska gå igenom."""
        import io
        from contextlib import redirect_stdout
        from unittest import mock

        real_read = ai._read_json

        def fake_read(path):
            data = real_read(path)
            if Path(path).name == "https_probe.json":
                data["results"][self.PDF] = {"status": 404}
            return data

        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(ai, "_read_json", fake_read):
            out = io.StringIO()
            with redirect_stdout(out):
                code = ai.main(["--offline", "--output-dir", tmp])
            self.assertEqual(code, 0)
            self.assertIn(f"VARNING {self.ISSUE}: ingen läs-PDF", out.getvalue())
            written = json.loads((Path(tmp) / "archive_inventory.json").read_text(encoding="utf-8"))
            issue = next(i for i in written["issues"] if i["issueId"] == self.ISSUE)
            self.assertIsNone(issue["ocrPdfUrl"])
            diagnostics = json.loads(
                (Path(tmp) / "inventory_diagnostics.json").read_text(encoding="utf-8"))
            self.assertEqual(len(diagnostics["warnings"]), 1)


class Overrides(Generated):
    def test_committed_override_sets_cover_transform(self):
        self.assertEqual(self.issues["datormagazin-1986-01"]["coverFlags"],
                         ["crop_right_half", "rotate_cw"])
        others = [i for i, issue in self.issues.items()
                  if issue["coverFlags"] and i != "datormagazin-1986-01"]
        self.assertEqual(others, [])
        self.assertEqual(self.diagnostics["issues"]["datormagazin-1986-01"]["appliedOverrides"],
                         ["coverFlags"])

    def test_without_overrides_no_cover_flags(self):
        result = ai.generate(self.config, self.listing, overrides={})
        self.assertTrue(all(i["coverFlags"] == [] for i in result["inventory"]["issues"]))

    def test_all_supported_fields(self):
        inventory = self.fresh()
        ai.apply_overrides(inventory, {"datormagazin-1989-06": {
            "coverFlags": ["rotate_180"],
            "spreadOffset": 1,
            "issueLabel": "Nr 6 (sommarnummer)",
            "flags": ["special_issue"],
            "reason": "test",
        }})
        issue = next(i for i in inventory["issues"] if i["issueId"] == "datormagazin-1989-06")
        self.assertEqual(issue["coverFlags"], ["rotate_180"])
        self.assertEqual(issue["spreadOffset"], 1)
        self.assertEqual(issue["issueLabel"], "Nr 6 (sommarnummer)")
        self.assertEqual(issue["flags"], ["special_issue"])
        self.assertNotIn("reason", issue)
        self.assertEqual(self.errors(inventory), [])

    def test_flags_are_added_not_replaced(self):
        inventory = self.fresh()
        ai.apply_overrides(inventory, {"datormagazin-1988-16-17": {"flags": ["special_issue"]}})
        issue = next(i for i in inventory["issues"] if i["issueId"] == "datormagazin-1988-16-17")
        self.assertEqual(issue["flags"], ["double_issue", "special_issue"])

    def test_spread_offset_can_be_reset_to_null(self):
        inventory = self.fresh()
        ai.apply_overrides(inventory, {"datormagazin-1989-06": {"spreadOffset": None}})

    def test_unknown_issue_id_is_an_error(self):
        with self.assertRaisesRegex(ai.OverrideError, "okänt issueId: datormagazin-1999-01"):
            ai.apply_overrides(self.fresh(), {"datormagazin-1999-01": {"coverFlags": ["rotate_cw"]}})

    def test_unknown_field_is_an_error(self):
        with self.assertRaisesRegex(ai.OverrideError, "okänt fält: pageCount"):
            ai.apply_overrides(self.fresh(), {"datormagazin-1989-06": {"pageCount": 48}})

    def test_wrong_type_is_an_error(self):
        for fields in ({"coverFlags": "rotate_cw"}, {"spreadOffset": "1"}, {"spreadOffset": True},
                       {"issueLabel": ""}, {"flags": [1]}):
            with self.assertRaises(ai.OverrideError, msg=fields):
                ai.apply_overrides(self.fresh(), {"datormagazin-1989-06": fields})

    def test_nothing_is_applied_when_any_override_is_wrong(self):
        inventory = self.fresh()
        with self.assertRaises(ai.OverrideError):
            ai.apply_overrides(inventory, {
                "datormagazin-1989-06": {"issueLabel": "Ändrad"},
                "finns-inte-1989-06": {"issueLabel": "X"},
            })
        self.assertEqual(inventory, self.inventory)

    def test_order_of_overrides_does_not_matter(self):
        a, b = self.fresh(), self.fresh()
        one = {"datormagazin-1989-06": {"flags": ["x_flag"], "issueLabel": "A"},
               "datormagazin-1989-07": {"coverFlags": ["rotate_cw"]}}
        two = dict(reversed(list(one.items())))
        ai.apply_overrides(a, one)
        ai.apply_overrides(b, two)
        self.assertEqual(ai.dump_json(a), ai.dump_json(b))

    def test_malformed_overrides_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "overrides.json"
            path.write_text('{"datormagazin-1989-06": {"coverFlags": ["rotate_cw"]}}', encoding="utf-8")
            with self.assertRaises(ai.OverrideError):
                ai.load_overrides(path)
            self.assertEqual(ai.load_overrides(Path(tmp) / "saknas.json"), {})


class SchemaValidation(Generated):
    def issue(self, inventory, issue_id="datormagazin-1989-06"):
        return next(i for i in inventory["issues"] if i["issueId"] == issue_id)

    def test_schema_itself_is_valid_draft_2020_12(self):
        import jsonschema
        self.assertEqual(self.schema["$schema"], "https://json-schema.org/draft/2020-12/schema")
        jsonschema.Draft202012Validator.check_schema(self.schema)

    def test_missing_required_field(self):
        for field in ("issueId", "publicationTitle", "sortKey", "coverUrl", "coverFlags",
                      "ocrPdfUrl", "sourceNames", "flags"):
            inventory = self.fresh()
            del self.issue(inventory)[field]
            errors = self.errors(inventory)
            self.assertTrue(any(f"'{field}' is a required property" in e for e in errors), field)

    def test_missing_top_level_field(self):
        inventory = self.fresh()
        del inventory["datasetVersion"]
        self.assertTrue(self.errors(inventory))

    def test_optional_fields_may_be_absent_or_null(self):
        inventory = self.fresh()
        issue = self.issue(inventory)
        for field in ("relatedIssueId", "ocrPdfSize", "ocrPdfEtag", "ocrPdfLastModified",
                      "largePdfUrl", "largePdfSize", "hqSource", "scanPagePattern",
                      "scanPageCount", "pageCount", "spreadOffset"):
            issue[field] = None
        self.assertEqual(self.errors(inventory), [])
        for field in ("relatedIssueId", "ocrPdfSize", "pageCount", "spreadOffset"):
            del issue[field]
        self.assertEqual(self.errors(inventory), [])

    def test_urls_may_be_null_but_must_be_present(self):
        for field in ("coverUrl", "ocrPdfUrl"):
            inventory = self.fresh()
            self.issue(inventory)[field] = None
            self.assertEqual(self.errors(inventory), [], field)
            del self.issue(inventory)[field]
            self.assertTrue(any(f"'{field}' is a required property" in e
                                for e in self.errors(inventory)), field)

    def test_unknown_field_is_rejected(self):
        inventory = self.fresh()
        self.issue(inventory)["ocrPdfChecksum"] = "sha256:abc"
        self.assertTrue(any("ocrPdfChecksum" in e for e in self.errors(inventory)))

    def test_wrong_types_and_formats(self):
        cases = [("year", "1989"), ("ocrPdfSize", "39845120"), ("pageCount", 0),
                 ("issueId", "Datormagazin 1989/6"), ("sortKey", "1989-6"),
                 ("coverUrl", "ftp://dmzarkivet.se/x.jpg"), ("hqSource", "raw"),
                 ("flags", "double_issue"), ("coverFlags", ["rotated"]),
                 ("coverFlags", ["rotate_cw", "rotate_180"]),
                 ("coverFlags", ["crop_left_half", "crop_right_half"]),
                 ("scanPagePattern", "ftp://dmzarkivet.se/x/DMZ_1989-06-001.jpg")]
        for field, value in cases:
            inventory = self.fresh()
            self.issue(inventory)[field] = value
            self.assertTrue(self.errors(inventory), (field, value))

    def test_allowed_cover_flags(self):
        for value in ([], ["rotate_cw"], ["rotate_ccw"], ["rotate_180"], ["crop_left_half"],
                      ["crop_right_half", "rotate_cw"], ["crop_left_half", "rotate_180"]):
            inventory = self.fresh()
            self.issue(inventory)["coverFlags"] = value
            self.assertEqual(self.errors(inventory), [], value)

    def test_override_with_unknown_cover_flag_fails_validation(self):
        result = ai.generate(self.config, self.listing,
                             overrides={"datormagazin-1989-06": {"coverFlags": ["rotated"]}})
        self.assertTrue(any("coverFlags" in e for e in result["errors"]))

    def test_wrong_schema_version_and_dataset_version(self):
        for field, value in (("schemaVersion", 2), ("datasetVersion", "2026-10-06"),
                             ("generatedAt", "igår")):
            inventory = self.fresh()
            inventory[field] = value
            self.assertTrue(self.errors(inventory), field)

    def test_bad_dataset_version_is_refused_by_the_generator(self):
        with self.assertRaises(SystemExit):
            ai.generate(self.config, self.listing, dataset_version="2026-10-06")


class SemanticValidation(Generated):
    def test_duplicate_issue_id(self):
        inventory = self.fresh()
        inventory["issues"].insert(1, copy.deepcopy(inventory["issues"][0]))
        self.assertTrue(any("issueId är inte unikt: datormagazin-1986-01" in e
                            for e in self.errors(inventory)))

    def test_related_issue_id_must_exist(self):
        inventory = self.fresh()
        issue = next(i for i in inventory["issues"] if i["issueId"] == "highscore-1993-17")
        issue["relatedIssueId"] = "datormagazin-1993-99"
        self.assertTrue(any("relatedIssueId pekar på datormagazin-1993-99" in e
                            for e in self.errors(inventory)))

    def test_related_issue_id_disappears_with_its_target(self):
        inventory = self.fresh()
        inventory["issues"] = [i for i in inventory["issues"] if i["issueId"] != "datormagazin-1993-17"]
        self.assertTrue(any("highscore-1993-17: relatedIssueId" in e for e in self.errors(inventory)))

    def test_sort_key_must_be_unique_within_publication_and_year(self):
        inventory = self.fresh()
        next(i for i in inventory["issues"] if i["issueId"] == "datormagazin-1987-10b")["sortKey"] = "1987-10"
        self.assertTrue(any("datormagazin 1987: sortKey är inte unik: 1987-10" in e
                            for e in self.errors(inventory)))

    def test_issues_must_be_in_sort_key_order(self):
        inventory = self.fresh()
        inventory["issues"][0], inventory["issues"][1] = inventory["issues"][1], inventory["issues"][0]
        self.assertTrue(any("datormagazin 1986: numren ligger inte i sortKey-ordning" in e
                            for e in self.errors(inventory)))

    def test_sort_key_must_match_year(self):
        inventory = self.fresh()
        inventory["issues"][0]["sortKey"] = "1985-01"
        self.assertTrue(any("börjar inte med året 1986" in e for e in self.errors(inventory)))

    def test_sort_key_order_matches_issue_numbers(self):
        # b-utgåvan direkt efter sitt nummer, dubbelnumret på sitt första nummer.
        keys = [i["sortKey"] for i in self.inventory["issues"]
                if i["publicationId"] == "datormagazin" and i["year"] == 1987]
        self.assertEqual(keys, ["1987-01", "1987-02", "1987-03", "1987-04", "1987-06", "1987-07",
                                "1987-08", "1987-09", "1987-10", "1987-10b"])

    def test_publication_must_be_declared(self):
        inventory = self.fresh()
        inventory["publications"] = inventory["publications"][:1]
        self.assertTrue(any("publicationId highscore saknas i publications" in e
                            for e in self.errors(inventory)))

    def test_publication_title_must_match(self):
        inventory = self.fresh()
        inventory["issues"][0]["publicationTitle"] = "DMZ"
        self.assertTrue(any("publicationTitle stämmer inte" in e for e in self.errors(inventory)))


class Diff(Generated):
    def test_identical_inventories(self):
        diff = ai.diff_inventories(self.inventory, self.fresh())
        self.assertEqual((diff["added"], diff["removed"], diff["changed"]), ([], [], []))
        self.assertEqual(diff["unchangedCount"], 185)
        self.assertEqual(diff["header"], {})

    def test_manipulated_inventory(self):
        """Acceptanskriterium: ett tillagt, ett borttaget och ett ändrat nummer rapporteras rätt."""
        previous = self.fresh()
        previous["datasetVersion"] = "2026-09-01-001"
        # Borttaget i det nya: finns bara i det gamla.
        gone = copy.deepcopy(previous["issues"][0])
        gone["issueId"] = "datormagazin-1985-01"
        previous["issues"].append(gone)
        # Nytt i det nya: saknas i det gamla.
        previous["issues"] = [i for i in previous["issues"] if i["issueId"] != "datormagazin-1995-15"]
        # Ändrat: filen har bytts i arkivet, och ett fält har tillkommit.
        changed = next(i for i in previous["issues"] if i["issueId"] == "datormagazin-1993-03")
        changed["ocrPdfSize"] = 78922903
        changed["ocrPdfEtag"] = '"gammal"'
        del changed["scanPageCount"]

        diff = ai.diff_inventories(previous, self.inventory)
        self.assertEqual(diff["added"], ["datormagazin-1995-15"])
        self.assertEqual(diff["removed"], ["datormagazin-1985-01"])
        self.assertEqual([c["issueId"] for c in diff["changed"]], ["datormagazin-1993-03"])
        fields = diff["changed"][0]["fields"]
        self.assertEqual(sorted(fields), ["ocrPdfEtag", "ocrPdfSize", "scanPageCount"])
        self.assertEqual(fields["ocrPdfSize"], {"old": 78922903, "new": 89792864})
        self.assertEqual(fields["scanPageCount"], {"new": 72})
        self.assertEqual(diff["unchangedCount"], 183)
        self.assertEqual(diff["previousDatasetVersion"], "2026-09-01-001")
        self.assertIn("datasetVersion", diff["header"])

        report = ai.format_diff(diff)
        self.assertIn("+ datormagazin-1995-15", report)
        self.assertIn("- datormagazin-1985-01", report)
        self.assertIn("fält ocrPdfSize: 1 nummer (ändrat)", report)
        self.assertIn("datormagazin-1993-03: 78922903 → 89792864", report)
        self.assertIn("fält scanPageCount: 1 nummer (tillagt)", report)

    def test_removed_field_is_reported(self):
        previous = self.fresh()
        previous["issues"][0]["ocrPdfChecksum"] = "sha256:abc"
        diff = ai.diff_inventories(previous, self.inventory)
        self.assertEqual(diff["changed"][0]["fields"], {"ocrPdfChecksum": {"old": "sha256:abc"}})
        self.assertIn("(borttaget)", ai.format_diff(diff))

    def test_diff_names_both_dataset_versions(self):
        previous = self.fresh()
        previous["datasetVersion"] = "2026-09-01-001"
        diff = ai.diff_inventories(previous, self.inventory)
        self.assertEqual(diff["previousDatasetVersion"], "2026-09-01-001")
        self.assertEqual(diff["currentDatasetVersion"], self.inventory["datasetVersion"])
        self.assertIn("2026-09-01-001 → " + self.inventory["datasetVersion"], ai.format_diff(diff))


class Determinism(Generated):
    def test_same_input_gives_identical_text(self):
        again = ai.generate(self.config, self.listing)
        self.assertEqual(ai.dump_json(again["inventory"]), ai.dump_json(self.inventory))
        self.assertEqual(ai.dump_json(again["diagnostics"]), ai.dump_json(self.diagnostics))

    def test_committed_files_match_the_generator(self):
        for key, data in (("inventoryPath", self.inventory), ("diagnosticsPath", self.diagnostics)):
            path = ai.REPO_ROOT / self.config[key]
            if not path.exists():
                self.skipTest(f"{path.name} saknas")
            self.assertEqual(path.read_text(encoding="utf-8"), ai.dump_json(data), path.name)

    def test_generated_at_flag(self):
        result = ai.generate(self.config, self.listing, generated_at="2026-10-06T12:00:00Z")
        self.assertEqual(result["inventory"]["generatedAt"], "2026-10-06T12:00:00Z")
        self.assertEqual(result["errors"], [])

    def run_tool(self, *args) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(TOOL), "--offline", *args],
                              capture_output=True, text=True, check=False)

    def test_two_offline_runs_give_byte_identical_files(self):
        """Acceptanskriterium: två körningar med --offline ger identiska filer."""
        names = ("archive_inventory.json", "inventory_diagnostics.json")
        with tempfile.TemporaryDirectory() as one, tempfile.TemporaryDirectory() as two:
            first = self.run_tool("--output-dir", one)
            second = self.run_tool("--output-dir", two)
            self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
            self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
            self.assertEqual(first.stdout, second.stdout)
            for name in names:
                self.assertEqual((Path(one) / name).read_bytes(),
                                 (Path(two) / name).read_bytes(), name)
            # Körning över befintliga filer ändrar dem inte heller.
            third = self.run_tool("--output-dir", one)
            self.assertEqual(third.returncode, 0)
            for name in names:
                self.assertEqual((Path(one) / name).read_bytes(),
                                 (Path(two) / name).read_bytes(), name)

    def test_no_diff_without_compare_to(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(self.run_tool("--output-dir", tmp).returncode, 0)
            self.assertEqual(self.run_tool("--output-dir", tmp).returncode, 0)
            self.assertFalse((Path(tmp) / "inventory_diff.json").exists())

    def test_compare_to_writes_diff_with_both_dataset_versions(self):
        with tempfile.TemporaryDirectory() as tmp:
            previous = Path(tmp) / "previous.json"
            old = self.fresh()
            old["datasetVersion"] = "2026-09-01-001"
            old["issues"] = old["issues"][:-1]
            previous.write_text(ai.dump_json(old), encoding="utf-8")

            first = self.run_tool("--output-dir", tmp, "--compare-to", str(previous))
            self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
            self.assertIn("nya nummer: 1", first.stdout)
            diff_path = Path(tmp) / "inventory_diff.json"
            diff = json.loads(diff_path.read_text(encoding="utf-8"))
            self.assertEqual(diff["previousDatasetVersion"], "2026-09-01-001")
            self.assertEqual(diff["currentDatasetVersion"], self.inventory["datasetVersion"])
            self.assertEqual(diff["added"], [self.inventory["issues"][-1]["issueId"]])

            snapshot = diff_path.read_bytes()
            again = self.run_tool("--output-dir", tmp, "--compare-to", str(previous))
            self.assertEqual(again.stdout, first.stdout)
            self.assertEqual(diff_path.read_bytes(), snapshot)

    def test_validate_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            good = Path(tmp) / "good.json"
            good.write_text(ai.dump_json(self.inventory), encoding="utf-8")
            bad_inventory = self.fresh()
            bad_inventory["issues"].append(copy.deepcopy(bad_inventory["issues"][0]))
            del bad_inventory["issues"][3]["ocrPdfUrl"]
            bad = Path(tmp) / "bad.json"
            bad.write_text(ai.dump_json(bad_inventory), encoding="utf-8")

            ok = subprocess.run([sys.executable, str(TOOL), "--validate", str(good)],
                                capture_output=True, text=True, check=False)
            self.assertEqual(ok.returncode, 0, ok.stdout)
            failed = subprocess.run([sys.executable, str(TOOL), "--validate", str(bad)],
                                    capture_output=True, text=True, check=False)
            self.assertEqual(failed.returncode, 1)
            self.assertIn("'ocrPdfUrl' is a required property", failed.stdout)


if __name__ == "__main__":
    unittest.main()
