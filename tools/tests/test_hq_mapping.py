"""Tester för HQ-mappningen (FAS 11): tools/fetch_scans.py, tools/map_hq_pages.py och
fälten hqPageMap och hqPageRotation i inventory. Inget hämtas från arkivet."""

import copy
import ftplib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import archive_inventory as ai  # noqa: E402
import fetch_scans  # noqa: E402

try:
    import numpy  # noqa: F401
    from PIL import Image, ImageDraw
    import map_hq_pages as mapper
except ImportError:  # pragma: no cover
    mapper = None

REPO = Path(__file__).resolve().parent.parent.parent
DATA = REPO / "data"


def load(name: str) -> dict:
    return json.loads((DATA / name).read_text(encoding="utf-8"))


class ScanFiles(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.listing = load("raw_listing.json")
        cls.issues = {issue["issueId"]: issue for issue in load("archive_inventory.json")["issues"]}

    def files(self, issue_id):
        return [entry["name"] for entry in fetch_scans.scan_files(self.issues[issue_id], self.listing)]

    def test_test_file_is_not_a_scan(self):
        names = self.files("datormagazin-1986-01")
        self.assertEqual(len(names), 28)
        self.assertNotIn("test.jpg", names)
        self.assertEqual(names[0], "DMZ_1986-01-001.jpg")

    def test_supplement_files_come_after_the_main_part(self):
        names = self.files("datormagazin-1986-04")
        self.assertEqual(len(names), 49)
        self.assertEqual(names[32], "DMZ_1986-04-033.png")
        self.assertEqual(names[33], "DMZ_1986-04-Spelbilaga-001.png")

    def test_every_issue_has_as_many_scans_as_pdf_pages_except_1993_03(self):
        differing = [issue_id for issue_id, issue in self.issues.items()
                     if len(self.files(issue_id)) != issue["pageCount"]]
        self.assertEqual(differing, ["datormagazin-1993-03"])

    def test_positions(self):
        files = [{"name": str(n)} for n in range(1, 29)]
        picked = fetch_scans.pick_positions(files, ["first", "middle", "last"])
        self.assertEqual([entry["name"] for entry in picked], ["1", "14", "28"])
        one = fetch_scans.pick_positions([{"name": "1"}], ["first", "middle", "last"])
        self.assertEqual(len(one), 1)


class FakeFtp:
    """En FTP-server i minnet. Kan bryta en överföring efter ett visst antal byte, en gång."""

    instances = []
    files = {}
    cut_after = None

    def __init__(self, host, timeout=None):
        self.commands = []
        FakeFtp.instances.append(self)

    def login(self, user, password):
        self.commands.append(f"LOGIN {user}")

    def set_pasv(self, value):
        self.commands.append(f"PASV {value}")

    def voidcmd(self, command):
        self.commands.append(command)

    def retrbinary(self, command, callback, blocksize=8192, rest=None):
        self.commands.append(f"{command} rest={rest}")
        data = FakeFtp.files[command.removeprefix("RETR ")][rest or 0:]
        if FakeFtp.cut_after is not None:
            cut, FakeFtp.cut_after = FakeFtp.cut_after, None
            callback(data[:cut])
            raise ftplib.error_temp("426 Connection closed")
        for start in range(0, len(data), blocksize):
            callback(data[start:start + blocksize])

    def quit(self):
        self.commands.append("QUIT")

    def close(self):
        self.commands.append("CLOSE")


class Fetching(unittest.TestCase):
    CONFIG = {"ftp": {"host": "arkiv.test", "user": "dmz", "password": "dmz"}}
    CONTENT = bytes(range(256)) * 2000

    def setUp(self):
        FakeFtp.instances = []
        FakeFtp.files = {"Original Scans/x/a.jpg": self.CONTENT, "Original Scans/x/b.jpg": self.CONTENT[:1000]}
        FakeFtp.cut_after = None
        self.directory = tempfile.TemporaryDirectory()
        self.target = Path(self.directory.name)
        self.pauses = []
        self.fetcher = fetch_scans.ScanFetcher(self.CONFIG, connect=FakeFtp, sleep=self.pauses.append, log=lambda *_: None)

    def tearDown(self):
        self.directory.cleanup()

    def test_files_are_fetched_over_one_connection_in_binary_passive_mode(self):
        self.assertTrue(self.fetcher.fetch("Original Scans/x/a.jpg", self.target / "a.jpg", len(self.CONTENT)))
        self.assertTrue(self.fetcher.fetch("Original Scans/x/b.jpg", self.target / "b.jpg", 1000))
        self.fetcher.close()
        self.assertEqual((self.target / "a.jpg").read_bytes(), self.CONTENT)
        self.assertEqual(len(FakeFtp.instances), 1)
        self.assertEqual(self.fetcher.connections, 1)
        commands = FakeFtp.instances[0].commands
        self.assertEqual(commands[:3], ["LOGIN dmz", "PASV True", "TYPE I"])
        self.assertEqual(commands[-1], "QUIT")
        # En paus mellan filerna, ingen före den första.
        self.assertEqual(self.pauses, [fetch_scans.PAUSE_BETWEEN_FILES])

    def test_a_complete_file_is_not_fetched_again(self):
        (self.target / "b.jpg").write_bytes(self.CONTENT[:1000])
        self.assertFalse(self.fetcher.fetch("Original Scans/x/b.jpg", self.target / "b.jpg", 1000))
        self.assertEqual(FakeFtp.instances, [])

    def test_an_interrupted_transfer_is_resumed(self):
        FakeFtp.cut_after = 100_000
        self.assertTrue(self.fetcher.fetch("Original Scans/x/a.jpg", self.target / "a.jpg", len(self.CONTENT)))
        self.assertEqual((self.target / "a.jpg").read_bytes(), self.CONTENT)
        self.assertFalse((self.target / "a.jpg.part").exists())
        retrieves = [c for ftp in FakeFtp.instances for c in ftp.commands if c.startswith("RETR")]
        self.assertEqual(retrieves, ["RETR Original Scans/x/a.jpg rest=None", "RETR Original Scans/x/a.jpg rest=100000"])

    def test_a_part_file_from_an_earlier_run_is_continued(self):
        (self.target / "a.jpg.part").write_bytes(self.CONTENT[:300_000])
        self.fetcher.fetch("Original Scans/x/a.jpg", self.target / "a.jpg", len(self.CONTENT))
        self.assertEqual((self.target / "a.jpg").read_bytes(), self.CONTENT)
        self.assertIn("RETR Original Scans/x/a.jpg rest=300000", FakeFtp.instances[0].commands)

    def test_a_file_of_the_wrong_size_is_an_error(self):
        with self.assertRaises(fetch_scans.ScanFetchError):
            self.fetcher.fetch("Original Scans/x/b.jpg", self.target / "b.jpg", 2000)
        self.assertFalse((self.target / "b.jpg").exists())


def page_image(seed: int, size=(600, 850)) -> "Image.Image":
    """En påhittad tidningssida: rubrik, spalter och bildrutor som beror på [seed]."""
    import random
    generator = random.Random(seed)
    image = Image.new("L", size, 235)
    draw = ImageDraw.Draw(image)
    draw.rectangle([30, 30, size[0] - 30, 30 + generator.randint(40, 110)], fill=generator.randint(20, 90))
    for _ in range(6):
        left = generator.randint(30, size[0] - 220)
        top = generator.randint(160, size[1] - 220)
        draw.rectangle([left, top, left + generator.randint(80, 190), top + generator.randint(60, 190)],
                       fill=generator.randint(30, 200))
    for row in range(170, size[1] - 40, 14):
        if generator.random() < 0.7:
            draw.line([40, row, generator.randint(200, size[0] - 40), row], fill=90, width=3)
    return image


def with_margin(image: "Image.Image", left: int, top: int, right: int, bottom: int) -> "Image.Image":
    """Samma sida, skannad med en marginal runt om."""
    framed = Image.new("L", (image.width + left + right, image.height + top + bottom), 250)
    framed.paste(image, (left, top))
    return framed


@unittest.skipIf(mapper is None, "numpy och Pillow krävs")
class Scoring(unittest.TestCase):
    def test_the_same_page_scores_high_and_another_page_low(self):
        page = page_image(1)
        self.assertGreater(mapper.score(page, page.resize((300, 425))), 0.95)
        self.assertLess(mapper.score(page, page_image(2)), mapper.MIN_SCORE)

    def test_a_scan_with_another_crop_and_margin_still_matches(self):
        page = page_image(3)
        scan = with_margin(page, 30, 20, 10, 45)
        self.assertGreater(mapper.score(page, scan), 0.9)
        # Och åt andra hållet: PDF-sidan har marginalen, skanningen är tätare beskuren.
        self.assertGreater(mapper.score(scan, page), 0.9)
        # Utan sökning över beskärningar hade poängen blivit klart lägre.
        direct = float(mapper._vector(page, (0, 0, 0, 0), mapper.COMPARE_SIZE)
                       @ mapper._vector(scan, (0, 0, 0, 0), mapper.COMPARE_SIZE))
        self.assertLess(direct, mapper.score(page, scan))

    def test_a_turned_scan_is_found_with_its_rotation(self):
        upright = page_image(4, size=(850, 600))             # PDF-sidan ligger ned
        turned_left = upright.rotate(90, expand=True)        # skanningen står, vriden 90 grader moturs
        value, rotation = mapper.score_with_rotation(upright, turned_left)
        self.assertGreater(value, 0.9)
        self.assertEqual(rotation, 90)                        # vrids 90 grader medurs för att ligga rätt
        value, rotation = mapper.score_with_rotation(upright, upright.rotate(270, expand=True))
        self.assertGreater(value, 0.9)
        self.assertEqual(rotation, 270)
        self.assertEqual(mapper.score_with_rotation(upright, upright)[1], 0)

    def test_decision_needs_both_a_high_score_and_a_margin(self):
        self.assertEqual(mapper.decide({"a": 0.95, "b": 0.40})[0], "a")
        self.assertIsNone(mapper.decide({"a": 0.40, "b": 0.39})[0])     # ingen liknar sidan
        self.assertIsNone(mapper.decide({"a": 0.95, "b": 0.90})[0])     # två kandidater är för lika
        self.assertEqual(mapper.decide({"a": 0.97, "b": 0.85})[0], "a")
        self.assertEqual(mapper.decide({"a": 0.95})[0], "a")

    def test_sample_positions(self):
        self.assertEqual(mapper.sample_positions(28), {"first": 1, "middle": 14, "last": 28})
        self.assertEqual(mapper.sample_positions(49), {"first": 1, "middle": 25, "last": 49})


@unittest.skipIf(mapper is None, "numpy och Pillow krävs")
class Export(unittest.TestCase):
    def setUp(self):
        self.listing = load("raw_listing.json")
        self.inventory = {issue["issueId"]: issue for issue in load("archive_inventory.json")["issues"]}

    def test_full_mappings_and_rotations_are_exported(self):
        report = {
            "full": {"datormagazin-1993-03": {"hqPageMap": {"1": "DMZ_1993-03-03.jpg", "2": None},
                                               "hqPageRotation": {"1": 180}}},
            "rotation": {"datormagazin-1989-06": {"pages": [
                {"pdfPage": 1, "ok": True, "rotation": 90}, {"pdfPage": 5, "ok": True, "rotation": 0}]}},
        }
        issues = mapper.export_maps(report, self.inventory, self.listing)["issues"]
        self.assertEqual(issues["datormagazin-1993-03"],
                         {"hqPageMap": {"1": "DMZ_1993-03-03.jpg", "2": None}, "hqPageRotation": {"1": 180}})
        # En sida som inte behöver vridas nämns inte.
        self.assertEqual(issues["datormagazin-1989-06"], {"hqPageRotation": {"1": 90}})

    def test_a_page_with_unknown_rotation_gets_no_hq(self):
        report = {"full": {}, "rotation": {"datormagazin-1986-01": {"pages": [
            {"pdfPage": 1, "ok": False, "rotation": 90}]}}}
        entry = mapper.export_maps(report, self.inventory, self.listing)["issues"]["datormagazin-1986-01"]
        self.assertIsNone(entry["hqPageMap"]["1"])
        self.assertEqual(entry["hqPageMap"]["2"], "DMZ_1986-01-002.jpg")
        self.assertEqual(len(entry["hqPageMap"]), 28)
        self.assertNotIn("hqPageRotation", entry)

    def test_nothing_to_export_for_an_issue_without_rotated_pages(self):
        report = {"full": {}, "rotation": {"datormagazin-1989-06": {"pages": [{"pdfPage": 1, "ok": True, "rotation": 0}]}}}
        self.assertEqual(mapper.export_maps(report, self.inventory, self.listing)["issues"], {})


class InventoryFields(unittest.TestCase):
    """hqPageMap och hqPageRotation i det genererade inventoryt och i valideringen."""

    @classmethod
    def setUpClass(cls):
        cls.config = ai.load_config()
        cls.listing = ai.load_listing(DATA / "raw_listing.json")
        cls.schema = ai.load_schema(ai.REPO_ROOT / cls.config["schemaPath"])
        cls.result = ai.generate(cls.config, cls.listing)
        cls.issues = {issue["issueId"]: issue for issue in cls.result["inventory"]["issues"]}

    def errors(self, inventory):
        return ai.validate_inventory(inventory, self.schema)

    def changed(self, issue_id, **fields):
        inventory = copy.deepcopy(self.result["inventory"])
        issue = next(i for i in inventory["issues"] if i["issueId"] == issue_id)
        for name, value in fields.items():
            if value is ...:
                issue.pop(name, None)
            else:
                issue[name] = value
        return inventory

    def test_generated_inventory_is_valid(self):
        self.assertEqual(self.result["errors"], [])

    def test_only_the_two_deviating_issues_have_a_page_map(self):
        mapped = sorted(issue_id for issue_id, issue in self.issues.items() if "hqPageMap" in issue)
        self.assertEqual(mapped, ["datormagazin-1986-04", "datormagazin-1993-03"])

    def test_1986_04_maps_the_supplement(self):
        page_map = self.issues["datormagazin-1986-04"]["hqPageMap"]
        self.assertEqual(len(page_map), 49)
        self.assertEqual(page_map["33"], "DMZ_1986-04-033.png")
        self.assertEqual(page_map["34"], "DMZ_1986-04-Spelbilaga-001.png")
        self.assertEqual(page_map["49"], "DMZ_1986-04-Spelbilaga-016.png")
        self.assertNotIn(None, page_map.values())

    def test_1993_03_is_shifted_two_scans_and_two_pages_have_no_scan(self):
        issue = self.issues["datormagazin-1993-03"]
        page_map = issue["hqPageMap"]
        self.assertEqual(len(page_map), 68)
        self.assertEqual(page_map["1"], "DMZ_1993-03-03.jpg")
        self.assertEqual(page_map["68"], "DMZ_1993-03-70.jpg")
        self.assertEqual(sorted(int(page) for page, name in page_map.items() if name is None), [39, 40])
        self.assertEqual(issue["hqPageRotation"], {"27": 180})

    def test_every_mapped_file_exists_in_the_listing(self):
        for issue_id in ("datormagazin-1986-04", "datormagazin-1993-03"):
            issue = self.issues[issue_id]
            names = {entry["name"] for entry in fetch_scans.scan_files(issue, self.listing)}
            self.assertTrue(set(filter(None, issue["hqPageMap"].values())) <= names, issue_id)

    def test_rotations_are_only_on_landscape_first_pages_and_the_like(self):
        rotated = {issue_id: issue["hqPageRotation"] for issue_id, issue in self.issues.items() if "hqPageRotation" in issue}
        self.assertEqual(rotated["datormagazin-1987-04-05"], {"1": 90})
        self.assertNotIn("datormagazin-1986-01", rotated)
        for issue_id, rotation in rotated.items():
            self.assertTrue(set(rotation.values()) <= {90, 180, 270}, issue_id)
            self.assertTrue(all(int(page) <= self.issues[issue_id]["pageCount"] for page in rotation), issue_id)

    def test_diagnostics_summarise_the_mapping(self):
        entry = self.result["diagnostics"]["issues"]["datormagazin-1993-03"]["hqMapping"]
        self.assertEqual(entry, {"mappedPages": 66, "pagesWithoutHq": [39, 40], "rotatedPages": [27]})

    def test_schema_refuses_bad_maps(self):
        good = self.issues["datormagazin-1993-03"]["hqPageMap"]
        for bad in ({"0": "a.jpg"}, {"x": "a.jpg"}, {"1": "../a.jpg"}, {"1": "a/b.jpg"}, {"1": "a.gif"}, {"1": 5}, {}, []):
            self.assertTrue(self.errors(self.changed("datormagazin-1993-03", hqPageMap=bad)), bad)
        # 0, 90, 180 och 270 är tillåtna. 0 är standard för en sida som inte nämns.
        for good in ({"1": 0}, {"1": 90}, {"1": 180}, {"1": 270}):
            self.assertEqual(self.errors(self.changed("datormagazin-1993-03", hqPageRotation=good)), [], good)
        for bad in ({"1": 45}, {"1": 360}, {"1": -90}, {"1": "90"}, {"0": 90}, {}):
            self.assertTrue(self.errors(self.changed("datormagazin-1993-03", hqPageRotation=bad)), bad)
        self.assertEqual(self.errors(self.changed("datormagazin-1993-03", hqPageMap=good)), [])
        self.assertEqual(self.errors(self.changed("datormagazin-1993-03", hqPageMap=None, hqPageRotation=None)), [])

    def test_a_map_must_cover_every_pdf_page(self):
        page_map = dict(self.issues["datormagazin-1993-03"]["hqPageMap"])
        del page_map["5"]
        errors = self.errors(self.changed("datormagazin-1993-03", hqPageMap=page_map))
        self.assertTrue(any("en post för var och en av sidorna 1–68" in e for e in errors), errors)
        page_map = dict(self.issues["datormagazin-1993-03"]["hqPageMap"], **{"69": "DMZ_1993-03-71.jpg"})
        self.assertTrue(self.errors(self.changed("datormagazin-1993-03", hqPageMap=page_map)))

    def test_a_scan_can_not_be_used_for_two_pages(self):
        page_map = dict(self.issues["datormagazin-1993-03"]["hqPageMap"], **{"2": "DMZ_1993-03-03.jpg"})
        errors = self.errors(self.changed("datormagazin-1993-03", hqPageMap=page_map))
        self.assertTrue(any("samma skanning för flera sidor" in e for e in errors), errors)

    def test_rotation_must_point_at_existing_pages_with_a_scan(self):
        errors = self.errors(self.changed("datormagazin-1993-03", hqPageRotation={"39": 90}))
        self.assertTrue(any("sidor utan skanning" in e for e in errors), errors)
        errors = self.errors(self.changed("datormagazin-1989-06", hqPageRotation={"99": 90}))
        self.assertTrue(any("sidor som inte finns" in e for e in errors), errors)

    def test_a_mapping_needs_scans(self):
        errors = self.errors(self.changed("datormagazin-1989-06", hqSource=None))
        self.assertTrue(any("HQ-mappning utan hqSource" in e for e in errors), errors)

    def test_unknown_issue_or_missing_file_stops_the_generator(self):
        for maps in ({"datormagazin-1999-01": {"hqPageRotation": {"1": 90}}},
                     {"datormagazin-1989-06": {"hqPageMap": {"1": "finns-inte.jpg"}}},
                     {"datormagazin-1989-06": {"annat": 1}},
                     {"datormagazin-1989-06": {}}):
            with self.assertRaises(ai.HqPageMapError, msg=maps):
                ai.generate(self.config, self.listing, hq_page_maps=maps)

    def test_without_maps_no_issue_has_the_fields(self):
        result = ai.generate(self.config, self.listing, hq_page_maps={})
        self.assertEqual(result["errors"], [])
        self.assertFalse(any("hqPageMap" in issue or "hqPageRotation" in issue for issue in result["inventory"]["issues"]))


class Report(unittest.TestCase):
    """Det versionshanterade underlaget (data/hq_mapping_report.json) stämmer med inventory."""

    @classmethod
    def setUpClass(cls):
        cls.report = load("hq_mapping_report.json")
        cls.issues = {issue["issueId"]: issue for issue in load("archive_inventory.json")["issues"]}

    def test_full_mappings_are_the_ones_in_inventory(self):
        for issue_id, result in self.report["full"].items():
            self.assertEqual(result["hqPageMap"], self.issues[issue_id]["hqPageMap"], issue_id)

    def test_every_sampled_page_matched_its_scan(self):
        pages = [page for result in self.report["sample"].values() for page in result["pages"]]
        self.assertEqual(len(pages), 39)
        self.assertTrue(all(page["ok"] for page in pages))
        self.assertGreater(min(page["score"] for page in pages), 0.95)

    def test_every_landscape_page_got_a_rotation(self):
        pages = [page for result in self.report["rotation"].values() for page in result["pages"]]
        self.assertEqual(len(pages), 107)
        self.assertTrue(all(page["ok"] for page in pages))


if __name__ == "__main__":
    unittest.main()
