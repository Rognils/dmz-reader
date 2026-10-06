"""Tester för omslagsminiatyrerna (tools/build_covers.py, ADR 0016).

Transformlogiken testas med syntetiska bilder. Testerna mot data/covers/ kör
mot de versionshanterade miniatyrerna och kräver inte cachen med original.

Kör:  python3 -m unittest discover -s tools/tests -v
"""

import hashlib
import json
import sys
import unittest
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import archive_inventory as ai  # noqa: E402
import build_covers as bc  # noqa: E402
import fetch_covers as fc  # noqa: E402

RED, BLUE, GREEN = (255, 0, 0), (0, 0, 255), (0, 255, 0)


def upright_spread() -> Image.Image:
    """Som sidbild 1 för 1986 nr 1: ett liggande uppslag som står på högkant.

    Övre halvan är framsidan (röd), nedre baksidan (blå). Framsidans övre
    vänstra hörn i läsriktningen (grönt) ligger uppe till höger.
    """
    image = Image.new("RGB", (200, 280), BLUE)
    image.paste(RED, (0, 0, 200, 140))
    image.paste(GREEN, (180, 0, 200, 20))
    return image


class Transform(unittest.TestCase):
    def test_rotate_cw_then_crop_right_half_gives_the_front_page(self):
        result = bc.apply_cover_flags(upright_spread(), ["rotate_cw", "crop_right_half"])
        self.assertEqual(result.size, (140, 200))
        self.assertEqual(result.getpixel((70, 100)), RED)
        self.assertEqual(result.getpixel((5, 195)), RED)
        # Markeringen har hamnat nere till höger: bilden är vriden medurs.
        self.assertEqual(result.getpixel((135, 195)), GREEN)

    def test_same_result_as_the_app(self):
        """Samma mått och pixlar som CoverImageInstrumentedTest kontrollerar i appen."""
        ccw = bc.apply_cover_flags(upright_spread(), ["rotate_ccw"])
        self.assertEqual(ccw.size, (280, 200))
        self.assertEqual(ccw.getpixel((5, 5)), GREEN)
        upside_down = bc.apply_cover_flags(upright_spread(), ["rotate_180"])
        self.assertEqual(upside_down.size, (200, 280))
        self.assertEqual(upside_down.getpixel((100, 10)), BLUE)
        self.assertEqual(upside_down.getpixel((5, 275)), GREEN)
        left = bc.apply_cover_flags(upright_spread(), ["rotate_cw", "crop_left_half"])
        self.assertEqual(left.size, (140, 200))
        self.assertEqual(left.getpixel((70, 100)), BLUE)
        self.assertEqual(bc.apply_cover_flags(upright_spread(), ["crop_left_half"]).size, (100, 280))

    def test_order_of_flags_does_not_matter(self):
        a = bc.apply_cover_flags(upright_spread(), ["rotate_cw", "crop_right_half"])
        b = bc.apply_cover_flags(upright_spread(), ["crop_right_half", "rotate_cw"])
        self.assertEqual(a.tobytes(), b.tobytes())

    def test_rotation_comes_before_crop(self):
        # Beskärning före vridning hade gett en annan bild: högra halvan av
        # originalet är både röd och blå.
        wrong = upright_spread().crop((100, 0, 200, 280)).rotate(270, expand=True)
        right = bc.apply_cover_flags(upright_spread(), ["rotate_cw", "crop_right_half"])
        self.assertNotEqual(wrong.size, right.size)

    def test_no_flags_leaves_the_image_alone(self):
        self.assertEqual(bc.apply_cover_flags(upright_spread(), []).tobytes(), upright_spread().tobytes())

    def test_unknown_flag_is_an_error(self):
        with self.assertRaises(ValueError):
            bc.apply_cover_flags(upright_spread(), ["rotated"])

    def test_scaling_comes_last(self):
        wide = Image.new("RGB", (2400, 1700), RED)
        thumbnail = bc.make_thumbnail(bc.apply_cover_flags(wide, ["rotate_cw"]))
        self.assertEqual(thumbnail.size, (600, 847))

    def test_small_images_are_not_enlarged(self):
        self.assertEqual(bc.make_thumbnail(Image.new("RGB", (400, 560), RED)).size, (400, 560))

    def test_encoding_is_deterministic(self):
        image = bc.make_thumbnail(upright_spread())
        self.assertEqual(bc.encode_webp(image), bc.encode_webp(image.copy()))


class Classification(unittest.TestCase):
    def test_a_copy_in_another_resolution_is_not_processed(self):
        original = upright_spread()
        result = bc.compare_images(original.resize((800, 1120)), original)
        self.assertFalse(result["differs"])
        self.assertLess(result["difference"], 1)

    def test_a_cropped_cover_is_processed(self):
        original = upright_spread()
        front = bc.apply_cover_flags(original, ["rotate_cw", "crop_right_half"])
        self.assertTrue(bc.compare_images(front, original)["differs"])

    def test_same_format_but_other_content_is_processed(self):
        self.assertTrue(bc.compare_images(Image.new("RGB", (200, 280), GREEN), upright_spread())["differs"])

    def test_other_format_is_processed_even_if_the_content_is_similar(self):
        result = bc.compare_images(Image.new("RGB", (200, 200), RED), Image.new("RGB", (200, 280), RED))
        self.assertTrue(result["differs"])
        self.assertEqual(result["difference"], 0)


class FetchPlan(unittest.TestCase):
    def test_only_jpg_and_png_are_fetched_from_covers(self):
        listing = ai.load_listing(ai.REPO_ROOT / "data" / "raw_listing.json")
        files = fc.ftp_cover_files(listing)
        self.assertEqual(len(files), 148)
        self.assertIn("Covers/DMZ_1986-01.png", files)
        self.assertNotIn("Covers/DMZ_1986-01.psd", files)
        self.assertTrue(all(name.lower().endswith((".jpg", ".png")) for name in files))


@unittest.skipUnless(bc.MANIFEST_PATH.exists(), "data/covers_manifest.json saknas")
class CommittedThumbnails(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads(bc.MANIFEST_PATH.read_text(encoding="utf-8"))
        cls.covers = cls.manifest["covers"]
        cls.inventory = json.loads((ai.REPO_ROOT / "data" / "archive_inventory.json").read_text("utf-8"))
        cls.issues = {i["issueId"]: i for i in cls.inventory["issues"]}
        cls.diagnostics = json.loads(
            (ai.REPO_ROOT / "data" / "inventory_diagnostics.json").read_text("utf-8"))["issues"]

    def test_transform_order_is_stated(self):
        self.assertEqual(self.manifest["transformOrder"], ["rotation", "crop", "scale"])

    def test_every_issue_has_a_thumbnail(self):
        self.assertEqual(set(self.covers), set(self.issues))
        for issue_id, issue in self.issues.items():
            self.assertEqual(issue["coverAsset"], f"covers/{issue_id}.webp")
        files = {p.name for p in bc.COVERS_DIR.glob("*")}
        self.assertEqual(files, {f"{issue_id}.webp" for issue_id in self.issues})

    def test_files_match_the_manifest(self):
        for issue_id, entry in self.covers.items():
            data = (bc.COVERS_DIR / f"{issue_id}.webp").read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), entry["sha256"], issue_id)
            self.assertEqual(len(data), entry["bytes"], issue_id)

    def test_thumbnails_are_upright_webp_of_the_right_size(self):
        for issue_id, entry in self.covers.items():
            with Image.open(bc.COVERS_DIR / f"{issue_id}.webp") as image:
                self.assertEqual(image.format, "WEBP", issue_id)
                self.assertEqual(image.size, (entry["width"], entry["height"]), issue_id)
                self.assertLessEqual(image.width, bc.THUMBNAIL_WIDTH, issue_id)
                self.assertGreaterEqual(image.width, 400, issue_id)
                # Stående format, ungefär som ett tidningsomslag.
                self.assertTrue(0.6 < image.width / image.height < 0.95,
                                f"{issue_id}: {image.size}")

    def test_total_size_is_within_the_limit(self):
        total = sum(entry["bytes"] for entry in self.covers.values())
        self.assertLess(total, 25 * 1000 * 1000)

    def test_source_counts(self):
        sources = ai._count(entry["source"] for entry in self.covers.values())
        self.assertEqual(sources, {"covers_ftp": 46, "web_page1": 139})
        corrected = [i for i, e in self.covers.items() if e["appliedFlags"]]
        self.assertEqual(len(corrected), 11)
        for issue_id in corrected:
            self.assertEqual(self.covers[issue_id]["appliedFlags"], ["crop_right_half", "rotate_cw"])
            self.assertEqual(self.covers[issue_id]["source"], "web_page1")

    def test_processed_ftp_covers_win_and_unprocessed_copies_do_not(self):
        for issue_id, entry in self.covers.items():
            if entry["sourceForced"]:
                continue
            if entry["ftpCoverProcessed"]:
                self.assertEqual(entry["source"], "covers_ftp", issue_id)
            else:
                self.assertEqual(entry["source"], "web_page1", issue_id)
        self.assertEqual(sum(e["ftpCoverProcessed"] is False for e in self.covers.values()), 101)

    def test_cover_flags_are_never_applied_to_ftp_covers(self):
        # 1986 nr 1 har coverFlags för reservvägen, men miniatyren kommer från Covers.
        entry = self.covers["datormagazin-1986-01"]
        self.assertEqual(entry["source"], "covers_ftp")
        self.assertEqual(entry["appliedFlags"], [])
        self.assertEqual(self.issues["datormagazin-1986-01"]["coverFlags"], ["crop_right_half", "rotate_cw"])

    def test_diagnostics_name_the_cover_source(self):
        for issue_id, entry in self.covers.items():
            self.assertEqual(self.diagnostics[issue_id]["coverSource"], entry["source"], issue_id)
        self.assertNotIn("coverSource", json.dumps(self.inventory))

    def test_review_covers_every_issue(self):
        classes = ai._count(entry["review"]["class"] for entry in self.covers.values())
        self.assertEqual(classes, {"ok": 171, "uppslag_vridet": 12, "annat": 2})
        # Varje vridet uppslag är antingen rättat eller taget från Covers.
        for issue_id, entry in self.covers.items():
            if entry["review"]["class"] == "uppslag_vridet":
                self.assertTrue(entry["appliedFlags"] or entry["source"] == "covers_ftp", issue_id)


@unittest.skipUnless(fc.WEB_DIR.exists() and fc.FTP_DIR.exists(), "cache/covers saknas")
class RebuildFromCache(unittest.TestCase):
    """Bara där originalen finns: bygget ska ge exakt de versionshanterade miniatyrerna."""

    def test_build_reproduces_the_committed_thumbnails(self):
        manifest = bc.build(ai.load_config(), write=False)
        committed = json.loads(bc.MANIFEST_PATH.read_text(encoding="utf-8"))["covers"]
        self.assertEqual(json.loads(json.dumps(manifest)), committed)


if __name__ == "__main__":
    unittest.main()
