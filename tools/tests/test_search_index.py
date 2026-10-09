"""Tester för tools/build_search_index.py och tools/search_pilot.py.

Normaliseringen prövas mot verkliga rader ur pilotens PDF:er, sparade med
ordbitarnas lägen i tools/testdata/search_lines.json, och mot den frysta
ordlistan. Inga PDF:er behövs. Testklasserna sist i filen körs bara när
PDF-cachen respektive den färdiga databasen finns på datorn.
"""

import json
import sqlite3
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import build_search_index as bsi  # noqa: E402
import search_pilot as sp  # noqa: E402

LINES = json.loads((bsi.REPO_ROOT / "tools" / "testdata" / "search_lines.json").read_text(encoding="utf-8"))["examples"]


LEXICON = bsi.load_lexicon()


def example(name: str) -> list[bsi.Word]:
    return [bsi.Word(*word) for word in LINES[name]["words"]]


def normalized(name: str, lexicon=LEXICON) -> str:
    return bsi.normalize_page(example(name), lexicon)


def raw(name: str) -> str:
    return " ".join(word.text for word in example(name))


class SpacedText(unittest.TestCase):
    """Utspärrad text i 1995 nr 14: textlagret har orden delade i bitar."""

    def test_intro_on_page_26(self):
        self.assertTrue(raw("spaced_intro").startswith("N y a h ö g ta la re ä r e tt b ra s ä tt a tt få m e r"))
        self.assertEqual(
            normalized("spaced_intro"),
            "Nya högtalare är ett bra sätt att få mer inlevelse när du spelar datorspel. "
            "Dessutom låter musikskivor mycket bättre, det är åtminstone vad reklamen utlovar. "
            "Vi har testat sju högtalare och en ryggsäck för att se om reklamen stämmer.")

    def test_headline_on_page_20(self):
        self.assertTrue(raw("spaced_headline").startswith("S ju tip s in fö r tu r b o k ö p e t Till Amiga 1200"))
        self.assertTrue(normalized("spaced_headline").startswith("Sju tips inför turboköpet Till Amiga 1200 finns"))

    def test_unknown_word_is_joined_when_clearly_spaced(self):
        self.assertIn("turboköpet", normalized("spaced_headline", LEXICON - {"turboköpet"}))

    def test_tightly_set_ordinary_text_is_left_alone(self):
        # 1987 nr 10b: luckorna är små, men varje bit är redan ett helt ord.
        self.assertEqual(normalized("tight_normal"), "runt om i världen får gratis. I den står det")
        self.assertEqual(normalized("tight_normal_b"), "Last Ninja 149:- 199:-")

    def test_run_of_whole_words_is_never_joined(self):
        lexicon = {"last", "ninja", "lastninja"}
        self.assertEqual(bsi.resolve_run(bsi.Run(["Last", "Ninja"]), lexicon), ["Last", "Ninja"])
        self.assertEqual(bsi.resolve_run(bsi.Run(["I", "den"]), {"den", "iden"}), ["I", "den"])

    def test_split_word_is_joined_when_the_result_is_known(self):
        lexicon = {"mycket", "reklamen"}
        self.assertEqual(bsi.resolve_run(bsi.Run(["m", "ycket"]), lexicon), ["mycket"])
        self.assertEqual(bsi.resolve_run(bsi.Run(["reklam", "en"]), lexicon), ["reklamen"])
        self.assertEqual(bsi.resolve_run(bsi.Run(["p", "roffsen"]), lexicon), ["p", "roffsen"])

    def test_numbers_and_letters_are_not_merged_into_an_unknown_word(self):
        # Fel i piloten: "4 av Bour-" blev ett ord.
        self.assertEqual(bsi.resolve_run(bsi.Run(["4", "av", "Bour-"]), set()), ["4", "av", "Bour-"])
        self.assertEqual(bsi.resolve_run(bsi.Run(["1", "6", "M", "B"]), set()), ["1", "6", "M", "B"])
        self.assertEqual(bsi.resolve_run(bsi.Run(["6", "8", "0", "3", "0"]), set()), ["68030"])
        self.assertEqual(bsi.resolve_run(bsi.Run(["A", "1", "2", "0", "0"]), {"a1200"}), ["A1200"])
        self.assertEqual(bsi.resolve_run(bsi.Run(["Tips", "1"]), {"tips", "tips1"}), ["Tips", "1"])
        self.assertEqual(bsi.resolve_run(bsi.Run(["1", "Mb"]), {"1mb"}), ["1", "Mb"])
        self.assertEqual(bsi.resolve_run(bsi.Run(["1", "7", "5"]), {"175"}), ["175"])


class Hyphens(unittest.TestCase):
    def test_soft_hyphen_at_line_break_is_removed(self):
        self.assertIn("teknis­ ka", raw("soft_hyphen"))
        self.assertEqual(normalized("soft_hyphen"),
                         "deltog i finalen, som hölls i Wiens tekniska museum lördagen den 4 september. I")
        self.assertIn("olika turbokort. Tyvärr", normalized("spaced_headline"))
        self.assertIn("två fabrikat i olika", normalized("spaced_headline"))
        self.assertNotIn("­", normalized("spaced_headline"))

    def test_hyphenation_at_line_break_is_joined(self):
        self.assertEqual(raw("hard_hyphen_v2"), "att stjäla vatten och fylla sina kött- bodar.")
        self.assertEqual(normalized("hard_hyphen_v2"), "att stjäla vatten och fylla sina köttbodar.")
        self.assertTrue(normalized("hard_hyphen_v2_b").startswith(
            "luftreningsanläggningen, centraldatorn., kärnreaktorn samt i hangaret."))

    def test_real_hyphen_is_kept(self):
        self.assertIn("CD- ROM", raw("real_hyphen"))
        self.assertIn("köpa hem en CD-ROM med över 4400 spel", normalized("real_hyphen"))
        self.assertIn("kommer pa CD-ROM till PC", normalized("real_hyphen_lower"))
        self.assertIn("Formel 1-stjärnan och", normalized("digit_hyphen"))

    def test_line_break_rules(self):
        empty = frozenset()
        join = bsi._join_line_break
        self.assertEqual(join("data-", "program", empty), "dataprogram")
        self.assertEqual(join("CD-", "ROM", empty), "CD-ROM")
        self.assertEqual(join("C-", "64", empty), "C-64")
        self.assertEqual(join("CD-", "skivor", empty), "CD-skivor")
        self.assertEqual(join("A1200-", "ägare", empty), "A1200-ägare")
        self.assertIsNone(join("hård-", "och", empty))        # "hård- och mjukvara"
        self.assertIsNone(join("-", "Idag", empty))           # tankstreck
        self.assertIsNone(join("spel", "som", empty))
        # Fel i stickprovet för normalizerVersion 1:
        self.assertEqual(join("AGG-", "RAURIN,", empty), "AGGRAURIN,")
        self.assertEqual(join("(REQUESTRES-", "PONSE)", empty), "(REQUESTRESPONSE)")
        self.assertEqual(join("CELI-", "CA", empty), "CELICA")
        self.assertEqual(join("SUPERCARS-", "THE", {"supercars", "the"}), "SUPERCARS-THE")
        self.assertEqual(join("ROD-", "LAND,", {"rod", "land", "rodland"}), "RODLAND,")
        self.assertEqual(join("SCSI-", "kort", {"scsi"}), "SCSI-kort")
        self.assertEqual(join("CD-", "ROM", {"cdrom", "cd-rom"}), "CD-ROM")
        self.assertEqual(join("x-", "min", {"min", "xmin"}), "x-min")
        self.assertEqual(join("up-", "spel.", {"spel"}), "up-spel.")
        self.assertEqual(join("an-", "nonstext", {"text"}), "annonstext")
        self.assertEqual(join("in-", "te", {"inte"}), "inte")
        self.assertEqual(join("Amiga-", "ägare", {"amiga-ägare"}), "Amiga-ägare")
        self.assertEqual(join("Amiga-", "ägare", {"amiga-ägare", "amigaägare"}), "Amigaägare")

    def test_hyphens_inside_a_line_are_untouched(self):
        words = [bsi.Word(0, 0, 30, 10, "CD-ROM"), bsi.Word(34, 0, 60, 10, "och"), bsi.Word(64, 0, 90, 10, "C-64")]
        self.assertEqual(bsi.normalize_page(words), "CD-ROM och C-64")


class Whitespace(unittest.TestCase):
    def test_single_spaces_and_no_line_breaks(self):
        for name in LINES:
            text = normalized(name)
            self.assertNotRegex(text, r"\s{2,}|\n|\t", name)
            self.assertEqual(text, text.strip(), name)

    def test_lines_are_split_on_vertical_position(self):
        words = [bsi.Word(0, 0, 20, 10, "ett"), bsi.Word(24, 0, 44, 10, "två"), bsi.Word(0, 14, 20, 24, "tre")]
        self.assertEqual([[w.text for w in line] for line in bsi.group_lines(words)], [["ett", "två"], ["tre"]])

    def test_split_pages_uses_form_feed_and_pdf_page_index(self):
        self.assertEqual(bsi.split_pages("ett\n\ftvå\n\f\ffyra\n\f"), ["ett\n", "två\n", "", "fyra\n"])


def fake_issue(issue_id: str, pages: list[str]) -> tuple[dict, dict, bsi.ExtractedIssue]:
    parts = [[[word for word in page.split()]] for page in pages]
    return ({"issueId": issue_id}, {"size": 1000 + len(pages), "etag": f'"etag-{issue_id}"', "sha256": "ab" * 32},
            bsi.ExtractedIssue(issue_id, [page + "\n" for page in pages], parts, len(pages), 0.0, []))


class Database(unittest.TestCase):
    PAGES = {
        "datormagazin-1989-06": ["Du får en joystick till din C64.", "Min far har en Amiga. Här är den."],
        "highscore-1993-17": ["Commodore 128 och CD-ROM", "Han har en idé för skärmen."],
    }

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def build(self, diacritics: int, name: str = "search.db", only: str | None = None) -> Path:
        path = Path(self.directory.name) / name
        issues = [fake_issue(issue_id, pages) for issue_id, pages in self.PAGES.items() if only in (None, issue_id)]
        bsi.build_database(path, issues, diacritics=diacritics, dataset_version="2026-10-06-004",
                           built_at="2026-10-08T00:00:00Z", lexicon=LEXICON)
        return path

    def hits(self, path: Path, query: str) -> list[str]:
        db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        self.addCleanup(db.close)
        return [f"{issue}:{page}" for issue, page, _ in sp.search(db, query)]

    def test_schema_and_meta(self):
        db = sqlite3.connect(self.build(0))
        self.addCleanup(db.close)
        meta = dict(db.execute("SELECT key, value FROM meta"))
        self.assertEqual(meta["searchSchemaVersion"], "1")
        self.assertEqual(meta["inventoryDatasetVersion"], "2026-10-06-004")
        self.assertEqual(meta["normalizerVersion"], str(bsi.NORMALIZER_VERSION))
        self.assertEqual(meta["tokenizer"], "unicode61 remove_diacritics 0")
        self.assertEqual(meta["builtAt"], "2026-10-08T00:00:00Z")
        self.assertEqual(meta["generator"], bsi.GENERATOR)
        self.assertEqual(meta["sqliteVersion"], sqlite3.sqlite_version)
        self.assertEqual(db.execute("SELECT * FROM issues ORDER BY issueId").fetchall(), [
            ("datormagazin-1989-06", 2, 1002, '"etag-datormagazin-1989-06"', "ab" * 32),
            ("highscore-1993-17", 2, 1002, '"etag-highscore-1993-17"', "ab" * 32)])
        self.assertEqual(
            db.execute("SELECT issueId, pageNumber, rawText, searchText FROM pages ORDER BY id LIMIT 1").fetchall(),
            [("datormagazin-1989-06", 1, "Du får en joystick till din C64.\n", "Du får en joystick till din C64.")])
        self.assertEqual(db.execute("SELECT MIN(pageNumber) FROM pages").fetchone()[0], 1)

    def test_diacritics_0_keeps_swedish_letters_apart(self):
        path = self.build(0)
        self.assertEqual(self.hits(path, "får"), ["datormagazin-1989-06:1"])
        self.assertEqual(self.hits(path, "far"), ["datormagazin-1989-06:2"])
        self.assertEqual(self.hits(path, "här"), ["datormagazin-1989-06:2"])
        self.assertEqual(self.hits(path, "ide"), [])
        self.assertEqual(self.hits(path, "HÄR"), ["datormagazin-1989-06:2"])  # skiftläge spelar ingen roll

    def test_diacritics_2_merges_them(self):
        path = self.build(2)
        self.assertEqual(sorted(self.hits(path, "får")), ["datormagazin-1989-06:1", "datormagazin-1989-06:2"])
        self.assertEqual(sorted(self.hits(path, "här")), ["datormagazin-1989-06:2", "highscore-1993-17:2"])
        self.assertEqual(self.hits(path, "ide"), ["highscore-1993-17:2"])

    def test_phrases_and_hyphens(self):
        path = self.build(0)
        self.assertEqual(self.hits(path, "Commodore 128"), ["highscore-1993-17:1"])
        self.assertEqual(self.hits(path, "CD-ROM"), ["highscore-1993-17:1"])
        self.assertEqual(self.hits(path, "C64"), ["datormagazin-1989-06:1"])

    def test_build_is_reproducible(self):
        self.assertEqual(self.build(0, "a.db").read_bytes(), self.build(0, "b.db").read_bytes())

    def test_sizes_are_measured(self):
        sizes = sp.table_sizes(self.build(0))
        self.assertEqual(sizes["pages"], 4)
        self.assertEqual(sizes["file"], sizes["pagesTable"] + sizes["ftsIndex"] + sizes["other"])
        self.assertGreater(sizes["ftsIndex"], 0)


class Helpers(unittest.TestCase):
    def test_exact_pattern(self):
        self.assertTrue(sp.exact_pattern("får").search("Du FÅR en"))
        self.assertFalse(sp.exact_pattern("får").search("min far får-skalle".replace("får-skalle", "farfar")))
        self.assertTrue(sp.exact_pattern("CD-ROM").search("en cd rom-skiva"))
        self.assertFalse(sp.exact_pattern("här").search("han har"))

    def test_generation(self):
        issue = lambda publication, key: {"publicationId": publication, "sortKey": key}
        self.assertEqual(sp.generation(issue("datormagazin", "1986-01")), "v2")
        self.assertEqual(sp.generation(issue("datormagazin", "1991-21")), "v2")
        self.assertEqual(sp.generation(issue("datormagazin", "1991-22")), "äldre")
        self.assertEqual(sp.generation(issue("highscore", "1993-17")), "highscore")


class FrozenLexicon(unittest.TestCase):
    """Normaliseringen läser bara ordlistan och det aktuella numret."""

    def test_lexicon_file_is_sorted_unique_and_lower_case(self):
        self.assertEqual(bsi.LEXICON_PATH.name, f"normalizer_lexicon_v{bsi.LEXICON_VERSION}.txt")
        lines = bsi.LEXICON_PATH.read_text(encoding="utf-8").split("\n")
        self.assertEqual(lines[-1], "")
        terms = lines[:-1]
        self.assertEqual(terms, sorted(set(terms)))
        self.assertTrue(all(term == term.lower() and len(term) >= 3 and " " not in term for term in terms))
        self.assertGreater(len(terms), 50_000)

    def test_normalization_takes_no_statistics_from_other_issues(self):
        # Ingen funktion i kedjan tar emot något annat än sidans ordbitar och ordlistan.
        import inspect
        for function in (bsi.normalize_page, bsi.assemble, bsi.resolve_run, bsi._join_line_break):
            self.assertIn("lexicon", inspect.signature(function).parameters, function.__name__)
        self.assertNotIn("count_vocabulary", inspect.getsource(bsi.build))
        self.assertNotIn("count_vocabulary", inspect.getsource(bsi.build_database))

    def test_same_search_text_alone_and_together(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        words = {name: example(name) for name in ("spaced_intro", "hard_hyphen_v2", "soft_hyphen")}

        def texts(names):
            path = Path(directory.name) / ("-".join(names) + ".db")
            issues = [({"issueId": name}, {"size": 1, "etag": None, "sha256": None},
                       bsi.ExtractedIssue(name, ["x"], [bsi.page_parts(words[name])], 1, 0.0, [])) for name in names]
            bsi.build_database(path, issues, diacritics=0, dataset_version="x", built_at="x", lexicon=LEXICON)
            db = sqlite3.connect(path)
            self.addCleanup(db.close)
            return dict(db.execute("SELECT issueId, CAST(searchText AS BLOB) FROM pages"))

        together = texts(list(words))
        for name in words:
            self.assertEqual(texts([name])[name], together[name], name)


ALL_PDFS = all((bsi.CACHE_DIR / f"{issue_id}.pdf").exists() for issue_id in sp.fetch_pdfs.PILOT_ISSUES)
FULL_DB = bsi.REPO_ROOT / "dist" / "search.db"


@unittest.skipUnless(ALL_PDFS and FULL_DB.exists(), "PDF-cachen eller dist/search.db saknas")
class RealIssueAloneAndInFullBuild(unittest.TestCase):
    """Ett nummer som byggs ensamt får byte-identisk searchText med samma nummer i hela bygget."""

    def test_byte_identical_search_text(self):
        full = sqlite3.connect(f"file:{FULL_DB}?mode=ro", uri=True)
        self.addCleanup(full.close)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        for issue_id in ("datormagazin-1995-14", "datormagazin-1986-04", "highscore-1993-17"):
            alone = Path(directory.name) / f"{issue_id}.db"
            bsi.build([issue_id], alone, diacritics=0, built_at="x", log=lambda _: None)
            db = sqlite3.connect(alone)
            self.addCleanup(db.close)
            query = "SELECT pageNumber, CAST(searchText AS BLOB) FROM pages WHERE issueId = ? ORDER BY pageNumber"
            ours, theirs = db.execute(query, (issue_id,)).fetchall(), full.execute(query, (issue_id,)).fetchall()
            self.assertGreater(len(ours), 20)
            self.assertEqual(ours, theirs, issue_id)


GOLDEN_DB = FULL_DB
PILOT_BUILT = GOLDEN_DB.exists()


@unittest.skipUnless(PILOT_BUILT, "dist/search.db saknas")
class Golden(unittest.TestCase):
    """Facit mot den fullständiga search.db. Alla förväntade sidor ska hittas."""

    @classmethod
    def setUpClass(cls):
        cls.golden = json.loads(sp.GOLDEN_PATH.read_text(encoding="utf-8"))
        cls.rows = sp.evaluate_variant(GOLDEN_DB, cls.golden)

    def test_every_expected_page_is_found(self):
        self.assertEqual([row["query"] for row in self.rows if not row["found"]], [])

    def test_spaced_words_in_1995_14(self):
        rows = {(row["query"], row["kind"]): row for row in self.rows}
        self.assertEqual(rows[("högtalare", "utspärrat")]["expected"],
                         ["datormagazin-1995-14 s. 26", "datormagazin-1995-14 s. 27"])
        self.assertTrue(rows[("högtalare", "utspärrat")]["found"])
        self.assertTrue(rows[("Amiga", "utspärrat")]["found"])

    def test_no_irrelevant_hits(self):
        self.assertEqual(sum(row["irrelevant"] for row in self.rows), 0)


class GoldenFile(unittest.TestCase):
    def test_golden_file_is_well_formed(self):
        golden = json.loads(sp.GOLDEN_PATH.read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(golden["queries"]), 38)
        for entry in golden["queries"]:
            self.assertIn(entry["kind"], golden["kinds"])
            self.assertTrue(entry["expected"])
            for expected in entry["expected"]:
                self.assertGreaterEqual(expected["pageNumber"], 1)


if __name__ == "__main__":
    unittest.main()
