"""Tester för tools/privacy_audit.py. Alla nummer och adresser är påhittade."""

import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import build_search_index as bsi  # noqa: E402
import privacy_audit as audit  # noqa: E402
from test_search_index import LEXICON, fake_issue  # noqa: E402

PAGES = {
    "datormagazin-1989-06": ["Amiga 500 säljes, 1 995:-. Tel: 0123-456 78, Anna.", "Commodore 128 och en C-64."],
    "highscore-1993-17": ["Skriv till red@example.se eller ring 08-12 34 56.", "Nr 12 1993, 499 kr."],
}


class Audit(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def build(self, name: str, privacy: bool) -> Path:
        path = Path(self.directory.name) / name
        issues = [fake_issue(issue_id, pages) for issue_id, pages in PAGES.items()]
        bsi.build_database(path, issues, diacritics=0, dataset_version="2026-10-06-006",
                           built_at="2026-10-10T00:00:00Z", lexicon=LEXICON, privacy=privacy)
        return path

    def test_measure_counts_both_columns(self):
        result = audit.measure(self.build("plain.db", privacy=False))
        self.assertEqual(result["totalPages"], 4)
        for column in ("rawText", "searchText"):
            self.assertEqual((result[column]["email"], result[column]["phone"], result[column]["pages"]), (1, 2, 2))

    def test_masked_file_is_clean_and_untouched_by_the_check(self):
        path = self.build("masked.db", privacy=True)
        before = hashlib.sha256(path.read_bytes()).hexdigest()
        self.assertEqual(audit.verify(path), [])
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), before)

    def test_unmasked_file_fails_every_check(self):
        problems = audit.verify(self.build("plain.db", privacy=False))
        self.assertTrue(any(problem.startswith("meta:") for problem in problems))
        self.assertTrue(any(problem.startswith("SQL: searchText") for problem in problems))
        self.assertTrue(any(problem.startswith("SQL: rawText") for problem in problems))
        self.assertTrue(any(problem.startswith("strings:") for problem in problems))
        self.assertTrue(any(problem.startswith("råa bytes:") for problem in problems))

    def test_bytes_inside_the_fts_index_that_look_like_an_address_are_a_note_not_an_error(self):
        import sqlite3
        path = self.build("masked.db", privacy=True)
        db = sqlite3.connect(path)
        row, block = db.execute("SELECT id, block FROM pages_fts_data ORDER BY id DESC LIMIT 1").fetchone()
        db.execute("UPDATE pages_fts_data SET block = ? WHERE id = ?", (block + b"\x01\x02X@D.MH\x03", row))
        db.commit()
        db.close()
        notes = []
        problems = [problem for problem in audit.verify(path, notes=notes) if not problem.startswith("FTS:")]
        self.assertEqual(problems, [])
        self.assertTrue(any("sammanträffande" in note for note in notes))

    def test_text_updated_after_the_build_is_found_in_the_file(self):
        # En databas som maskeras i efterhand kan ha den gamla texten kvar i lediga sidor.
        # Därför byggs den utdelade filen alltid från grunden, och därför söks hela filen igenom.
        import sqlite3
        path = self.build("late.db", privacy=False)
        db = sqlite3.connect(path)
        db.execute("PRAGMA secure_delete = OFF")
        db.execute("UPDATE pages SET rawText = '', searchText = 'borta'")
        db.execute("INSERT INTO meta (key, value) VALUES ('privacyFilterVersion', '1')")
        db.commit()
        db.close()
        problems = audit.verify(path)
        self.assertFalse(any(problem.startswith("SQL:") for problem in problems))
        self.assertTrue(any(problem.startswith(("strings:", "råa bytes:", "FTS:")) for problem in problems), problems)

    def test_sample_refuses_to_write_inside_the_repository(self):
        with self.assertRaises(SystemExit):
            audit.sample(self.build("plain.db", privacy=False), audit.REPO_ROOT / "docs" / "stickprov", 5, 5, 5)

    def test_sample_writes_outside(self):
        out = Path(self.directory.name) / "stickprov"
        sizes = audit.sample(self.build("plain.db", privacy=False), out, 5, 5, 5)
        self.assertEqual(sizes["phone"], {"population": 2, "sample": 2})
        self.assertEqual(sizes["email"], {"population": 1, "sample": 1})
        self.assertTrue((out / "phone.json").is_file())


if __name__ == "__main__":
    unittest.main()
