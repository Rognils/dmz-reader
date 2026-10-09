"""Tester för tools/build_fts_testdb.py (FTS5-testindexet i FAS 1).

Kör:  python3 -m unittest discover -s tools/tests -v
"""

import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import build_fts_testdb as fts  # noqa: E402


class FtsTestDatabase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.tmp.name) / "fts_test.db"
        fts.build(cls.path)
        cls.db = sqlite3.connect(cls.path)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.tmp.cleanup()

    def test_expectations_hold_on_this_machine(self):
        self.assertTrue(fts.verify(self.path, verbose=False))

    def test_format_follows_the_plan(self):
        tables = {row[0] for row in self.db.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'pages_fts_%'")}
        self.assertEqual(tables, {"meta", "issues", "pages", "pages_fts", "selftest"})
        columns = [row[1] for row in self.db.execute("PRAGMA table_info('pages')")]
        self.assertEqual(columns, ["issueId", "pageNumber", "rawText", "searchText"])
        meta = dict(self.db.execute("SELECT key, value FROM meta"))
        self.assertEqual(set(meta), {"schemaVersion", "datasetVersion", "generatedAt",
                                     "tokenizer", "issueCount", "pageCount"})
        self.assertEqual(meta["tokenizer"], "unicode61")
        sql = self.db.execute("SELECT sql FROM sqlite_master WHERE name = 'pages_fts'").fetchone()[0]
        self.assertIn("fts5", sql.lower())

    def test_text_is_swedish_with_the_required_words(self):
        text = " ".join(row[0] for row in self.db.execute("SELECT searchText FROM pages"))
        self.assertTrue(5 <= len(fts.PAGES) <= 10)
        for word in ("Amiga", "högtalare", "Fairlight"):
            self.assertIn(word, text)
        for letter in "åäöÄ":
            self.assertIn(letter, text)

    def test_raw_text_keeps_soft_hyphens_and_spaced_text(self):
        raw = dict(((i, p), r) for i, p, r, _ in self.db.execute("SELECT * FROM pages"))
        self.assertIn(fts.SOFT_HYPHEN, raw[("datormagazin-1988-07", 14)])
        self.assertIn("h ö g t a l a r e", raw[("datormagazin-1995-14", 26)])

    def test_normalize(self):
        self.assertEqual(fts.normalize(f"uppmärk{fts.SOFT_HYPHEN}\nsamhet på"), "uppmärksamhet på")
        self.assertEqual(fts.normalize("N y a  h ö g t a l a r e  ä r"), "Nya högtalare är")
        self.assertEqual(fts.normalize("Vanlig text\nmed  radbrytning"), "Vanlig text med radbrytning")

    def test_build_is_deterministic(self):
        other = Path(self.tmp.name) / "again.db"
        fts.build(other)
        self.assertEqual(other.read_bytes(), self.path.read_bytes())

    def test_committed_database_matches_the_script(self):
        if not fts.DEFAULT_OUTPUT.exists():
            self.skipTest("app/src/debug/assets/fts_test.db saknas")
        committed = sqlite3.connect(fts.DEFAULT_OUTPUT)
        try:
            for table in ("meta", "issues", "pages", "selftest"):
                query = f"SELECT * FROM {table} ORDER BY 1, 2"
                self.assertEqual(committed.execute(query).fetchall(),
                                 self.db.execute(query).fetchall(), table)
        finally:
            committed.close()


if __name__ == "__main__":
    unittest.main()


class SearchTestDatabase(unittest.TestCase):
    """app/src/debug/assets/search_test.db: det lilla indexet i det riktiga formatet (FAS 9)."""

    def test_committed_file_matches_the_generator(self):
        import tempfile
        import build_search_testdb as st
        with tempfile.TemporaryDirectory() as directory:
            built = st.build(Path(directory) / "search_test.db")
            self.assertEqual(built.read_bytes(), st.OUTPUT.read_bytes(),
                             "kör python3 tools/build_search_testdb.py och committa filen")

    def test_content(self):
        import sqlite3
        import build_search_testdb as st
        db = sqlite3.connect(f"file:{st.OUTPUT}?mode=ro", uri=True)
        self.addCleanup(db.close)
        meta = dict(db.execute("SELECT key, value FROM meta"))
        self.assertEqual(meta["searchSchemaVersion"], "1")
        self.assertEqual(meta["tokenizer"], "unicode61 remove_diacritics 0")
        hits = db.execute("SELECT p.issueId, p.pageNumber FROM pages_fts JOIN pages p ON p.id = pages_fts.rowid "
                          "WHERE pages_fts MATCH '\"högtalare\"' ORDER BY p.issueId, p.pageNumber").fetchall()
        self.assertEqual(hits, [("datormagazin-1995-14", 26), ("datormagazin-1995-14", 27),
                                ("datormagazin-1995-14", 29), ("datormagazin-1999-01", 3)])
