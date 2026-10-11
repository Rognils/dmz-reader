"""Tester för tools/privacy_filter.py och för maskeringen i build_search_index.py.

Alla nummer och adresser här är påhittade. Riktiga nummer ur tidningarna får
aldrig läggas i testerna.
"""

import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import build_search_index as bsi  # noqa: E402
import privacy_filter as pf  # noqa: E402
from test_search_index import LEXICON, fake_issue  # noqa: E402

PHONE = EMAIL = GIRO = PNR = pf.MARK
NOTHING = dict.fromkeys(pf.CATEGORIES, 0)


class PhoneNumbersAreMasked(unittest.TestCase):
    CASES = {
        # 80- och 90-talets vanliga skrivsätt
        "Tel 08-123 45 67.": f"Tel {PHONE}.",
        "Tel: 08-12 34 56, Anders": f"Tel: {PHONE}, Anders",
        "tel: 031-12 34 56": f"tel: {PHONE}",
        "Fax 0303/123 45": f"Fax {PHONE}",
        "Ring 0303-12345.": f"Ring {PHONE}.",
        "Tel:040-123456": f"Tel:{PHONE}",
        "Tel:0123/456 78 C64 säljes": f"Tel:{PHONE} C64 säljes",
        "031 - 12 34 56": PHONE,
        "0123 -456 78": PHONE,
        "(08) 12 34 56": PHONE,
        # mobil, personsökare och frisamtal följer samma mönster
        "070-123 45 67": PHONE,
        "010-234 56 78": PHONE,
        "020-12 34 56": PHONE,
        # åtta siffror i Stockholm
        "08-123 456 78": PHONE,
        "08-12345678 16800/DS": f"{PHONE} 16800/DS",
        # landsnummer
        "+46 8 123 45 67": PHONE,
        "+46-(0)31-12 34 56": PHONE,
        "+46(0)40-123 45": PHONE,
        "+46-0123-45678 14400": f"{PHONE} 14400",
        # texttolkningen har spärrat ut eller bytt tecken
        "tel 031-12 3 4 56 Hjälp": f"tel {PHONE} Hjälp",
        "0 3 1 - 1 2 3 4 5 6": PHONE,
        "T e l: 0 1 2 3 -4 5 6 78": f"T e l: {PHONE}",
        "Tel: 08·12 34 56": f"Tel: {PHONE}",
        "054-1 23456": PHONE,
        # utan skiljetecken, efter ett ord som säger att det är ett nummer
        "Fax 031 123456": f"Fax {PHONE}",
        "tel. 08 123 45 67": f"tel. {PHONE}",
        "BBS 0812345678 2400": f"BBS {PHONE} 2400",
        "BBS 08123456 14.4": f"BBS {PHONE} 14.4",
        "Tel:01234567 89 C64": f"Tel:{PHONE} C64",
        "Tel:0123456 78, Anna": f"Tel:{PHONE}, Anna",
        "TEL031-123456. FAX031-12 34 57": f"TEL{PHONE}. FAX{PHONE}",
        "Telefon: 031*12 34 56": f"Telefon: {PHONE}",
        "Vard 8-17 0123-456 78": f"Vard 8-17 {PHONE}",
        # hopskrivet med ordet före
        "STORSTAD: Datacorner021-12 34 56,Domus": f"STORSTAD: Datacorner{PHONE},Domus",
        "Numret är0123-456 78. Både": f"Numret är{PHONE}. Både",
        "på031-123456. Omg. lev.": f"på{PHONE}. Omg. lev.",
        "STORSTADÖ0123-45678 vx": f"STORSTADÖ{PHONE} vx",
        # ett tankstreck efter numret hör inte till det
        "T e l : 0 1 2 3 - 4 5 6 7 0 — 4 F a x": f"T e l : {PHONE} — 4 F a x",
    }

    def test_cases(self):
        for text, expected in self.CASES.items():
            with self.subTest(text=text):
                self.assertEqual(pf.mask(text)[0], expected)

    def test_only_the_number_is_taken(self):
        # Det som följer på numret är ett pris, en hastighet eller nästa nummer.
        self.assertEqual(pf.mask("0123-456 78 1895:-")[0], f"{PHONE} 1895:-")
        self.assertEqual(pf.mask("031-12 34 56 99 kr")[0], f"{PHONE} 99 kr")
        self.assertEqual(pf.mask("BBS 0123-45678 14400/v32bis")[0], f"BBS {PHONE} 14400/v32bis")
        self.assertEqual(pf.mask("031-12 34 56 0123-456 78 040-12 34 56")[0], f"{PHONE} {PHONE} {PHONE}")
        self.assertEqual(pf.mask("08-12 34 56/57")[0], f"{PHONE}/57")

    def test_count(self):
        text, found = pf.mask("Tel 08-12 34 56, fax 08-12 34 57. Skriv till anna@example.se.")
        self.assertEqual(found, {**NOTHING, "email": 1, "phone": 2})
        self.assertEqual(text, f"Tel {PHONE}, fax {PHONE}. Skriv till {EMAIL}.")


class EmailAddressesAreMasked(unittest.TestCase):
    def test_cases(self):
        cases = {
            "anna.andersson@example.se": EMAIL,
            "Skriv till kalle@host.example.com.": f"Skriv till {EMAIL}.",
            "E-post: info@example.se, tel 08-12 34 56": f"E-post: {EMAIL}, tel {PHONE}",
            "(dmz-brev@red.example.se)": f"({EMAIL})",
            "A_B+c@sub-domain.example.org": EMAIL,
            "anna@d.example.se": EMAIL,
            "anna@ab.se": EMAIL,
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                self.assertEqual(pf.mask(text)[0], expected)

    def test_addresses_pulled_apart_by_the_text_recognition(self):
        cases = {
            "e-mail: order@ example.se Samtliga": f"e-mail: {EMAIL} Samtliga",
            "Anna Andersson anna @ red.example.se Ny": f"Anna Andersson {EMAIL} Ny",
            "info@exam ple. se] WWW": f"{EMAIL}] WWW",
            "kalle@ ex am ple.exam ple.com (Kalle)": f"{EMAIL} (Kalle)",
            # En utspärrad namndel maskeras från sin sista bit.
            "o k @ red.example.se om": f"o {EMAIL} om",
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                self.assertEqual(pf.mask(text)[0], expected)

    def test_not_an_address(self):
        for text in ["user@ host", "pris @ 5 kr", "namn@dator", "@example.se", "10@20", "X@D.MH", "x@d.se",
                     "10 st @ 5 kr per styck", "Skriv @ och sedan namnet på datorn, till exempel sune"]:
            with self.subTest(text=text):
                self.assertEqual(pf.mask(text)[0], text)


class OtherNumbersAreLeftAlone(unittest.TestCase):
    CASES = [
        "1989", "år 1986-1996", "499 kr", "1 995:-", "1.995 kr", "Pris 0,50-1,00 kr",
        "Commodore 128", "68000", "MC68030-25 MHz", "68030/68882", "512 KB", "C-64", "A500/2000", "Nr 12", "nr 3/95",
        "123 45 Storstad", "Box 12, 123 45 Storstad",
        "1993-04-12", "12/4 1993", "93-04-12", "04-93 18-91",
        "Poäng: 08-10", "9 av 10", "85%", "1-0", "0-24",
        "Art.nr 4012-345", "best.nr 0123-4", "v2.04", "v2.04-12345", "Kickstart 1.3/2.0", "version 3.1-40",
        "0900-1700", "kl 08-17", "ISSN: 0283-3379", "ISBN 0-07-882023-5", "ISBN: 0 -0 7 -8 8 2 0 2 3 -5",
        "$0314-$0315", "0281-0282 MEMSTR", "2:201/123.4", "640x512", "14400/v32bis", "300-2400 bps",
        "DATA 0 12 - 34 56 7", "10 0 5 4 - 110 2 15",
        "ring 020 gånger", "Ring 08 10 12", "Bbs 030 2400 bps", "A1200 030-50 MHz",
        "ring 0123 gånger", "fax 2400 bps",
        "68EC030-25 5495:-", "68EC030/68882", "($D025/53285)", "$DD00 - 56576",
    ]

    def test_cases(self):
        for text in self.CASES:
            with self.subTest(text=text):
                self.assertEqual(pf.mask(text), (text, NOTHING))


class GiroNumbersAreMasked(unittest.TestCase):
    CASES = {
        "Sätt in 20 kr på Pg 123 45 67-8, Anna.": f"Sätt in 20 kr på Pg {GIRO}, Anna.",
        "PG: 12 34 56-7": f"PG: {GIRO}",
        "pg :1234567-8.": f"pg :{GIRO}.",
        "Postgiro 12 34 56-7": f"Postgiro {GIRO}",
        "postgirot 1234-5": f"postgirot {GIRO}",
        "Betala till postgiro nr 41 41 01-6": f"Betala till postgiro nr {GIRO}",
        "Postgironummer: 12 34 56-7 Betalning": f"Postgironummer: {GIRO} Betalning",
        "pg-nr 123 45 67-8 eller": f"pg-nr {GIRO} eller",
        "PG nr: 123 45 67-8": f"PG nr: {GIRO}",
        # texttolkningen har spärrat ut ordet eller siffrorna
        "P O S T G I R O 12 34 56-7": f"P O S T G I R O {GIRO}",
        "P G 123 4 5 67-8 Skriv": f"P G {GIRO} Skriv",
        "Pg 12 34 56 - 7": f"Pg {GIRO}",
        # andra skiljetecken mellan ordet och numret
        "PG-1234567-8.": f"PG-{GIRO}.",
        "Pg; 123 45 67-8": f"Pg; {GIRO}",
        "postgiro · 12 34 56-7": f"postgiro · {GIRO}",
        # texttolkningen har skrivit ihop ordet med ordet före
        "sättsinpåPG1234567-8.1": f"sättsinpåPG{GIRO}.1",
        "betalaviapostgiro1234567-8.": f"betalaviapostgiro{GIRO}.",
        # ett nummer med personnummers form efter pg tas som personnummer
        "Pg 123 45 67-8, tel 0123-456 78": f"Pg {GIRO}, tel {PHONE}",
    }

    def test_cases(self):
        for text, expected in self.CASES.items():
            with self.subTest(text=text):
                self.assertEqual(pf.mask(text)[0], expected)

    def test_only_next_to_the_word(self):
        for text in ["123 45 67-8", "Bankgiro 123-4567", "konto 123 45 67-8", "pg 1-2", "LC24-10 pg",
                     "sida 12-3", "Pg, sedan 123 45 67-8", "Pg Anna 123 45 67-8",
                     "på sid 4-5 i pg", "PG 68000", "pg 1993"]:
            with self.subTest(text=text):
                self.assertEqual(pf.find(text)["giro"], 0)


class BankgiroNumbersAreMasked(unittest.TestCase):
    CASES = {
        "Bankgiro 123-4567": f"Bankgiro {GIRO}",
        "bankgirot: 5123-4567.": f"bankgirot: {GIRO}.",
        "BG 123-4567, Anna": f"BG {GIRO}, Anna",
        "bg-nr 1234-5678": f"bg-nr {GIRO}",
        "Bankgironummer 123-45 67.": f"Bankgironummer {GIRO}.",
        "B a n k g i r o 1 2 3-4 5 6 7": f"B a n k g i r o {GIRO}",
        "Bankgiro nr 123-4567, postgiro 12 34 56-7": f"Bankgiro nr {GIRO}, postgiro {GIRO}",
        "betalaviabankgiro123-4567.": f"betalaviabankgiro{GIRO}.",
    }

    def test_cases(self):
        for text, expected in self.CASES.items():
            with self.subTest(text=text):
                self.assertEqual(pf.mask(text)[0], expected)

    def test_only_next_to_the_word_and_only_in_the_format(self):
        for text in ["123-4567", "konto 123-4567", "Sbg 123-4567", "Göteborg 123-4567", "bg 12-34", "Bankgiro 12345-6",
                     "Bankgiro 12-3456", "BG 123-456", "bg 1993-1994-95", "Bankgiro, sedan 123-4567",
                     "BG 123-45678", "bg-färg 640-4096"]:
            with self.subTest(text=text):
                self.assertEqual(pf.find(text)["bankgiro"], 0)


class LocalPhoneNumbersAreMasked(unittest.TestCase):
    CASES = {
        "Tel 12 34 56": f"Tel {PHONE}",
        "Tel: 123 45, Anna": f"Tel: {PHONE}, Anna",
        "tel. 123456.": f"tel. {PHONE}.",
        "Telefon 12345": f"Telefon {PHONE}",
        "tfn 123 45 67": f"tfn {PHONE}",
        "Fax: 12 34 56": f"Fax: {PHONE}",
        "Telefax 123 456": f"Telefax {PHONE}",
        "BBS 123456 5 X X": f"BBS {PHONE} 5 X X",
        "BBS 12 34 56 2400 bps": f"BBS {PHONE} 2400 bps",
        "modem 123456": f"modem {PHONE}",
        "Tel 12-34-56": f"Tel {PHONE}",
        "TEL: 1234567": f"TEL: {PHONE}",
        # med riktnummer tas numret av den vanliga regeln, och ett andra nummer efter "eller" lämnas
        "Tel 0123-456 78 eller 123 45": f"Tel {PHONE} eller 123 45",
    }

    def test_cases(self):
        for text, expected in self.CASES.items():
            with self.subTest(text=text):
                self.assertEqual(pf.mask(text)[0], expected)

    def test_counted_as_its_own_category(self):
        self.assertEqual(pf.mask("Tel 12 34 56, fax 0123-456 78")[1], {**NOTHING, "localPhone": 1, "phone": 1})

    def test_numbers_near_the_words_that_are_not_phone_numbers(self):
        for text in [
            # ordet står inte för sig
            "telefonmodem 2400", "BBS-program 1.2", "Telefonen 12 34 56", "faxmodem 14400", "modemet 28800",
            "BBS-lista 123456", "telefonkatalog 123456", "Minitel 123456",
            # hastigheter, priser, mått och versioner
            "fax 9600 baud", "modem 14400", "Modem 28800 bps", "fax 14400/v32", "modem 57600", "bbs 12345 bps",
            "modem 2400/1200", "modem 1200 75", "fax 640x480", "tel 12.345", "BBS 1.200 kr", "Telefon 100-200 kr",
            "modem 12345 kr", "fax 12 34 56:-", "BBS 12 345 st", "modem 16800 baud",
            # för få eller för många siffror, årtal och intervall
            "Tel 1234", "fax 123", "BBS 24 timmar", "tel 1993-94", "Tel 123456789", "fax 1990-1995",
            # inte direkt efter ordet
            "Tel Anna 12 34 56", "Telefon, kvällstid 12 34 56", "ring 12 34 56", "nummer 12 34 56", "12 34 56",
        ]:
            with self.subTest(text=text):
                self.assertEqual(pf.mask(text), (text, NOTHING))


class PersonalNumberShapesAreMasked(unittest.TestCase):
    def test_cases(self):
        cases = {
            "född 640823-3234 i": f"född {PNR} i",
            "P-nr: 19640823-3234": f"P-nr: {PNR}",
            "Pg 640823-3234": f"Pg {PNR}",
            "640823 - 3234": PNR,
            "091231+1234": PNR,
            "20001231-1234.": f"{PNR}.",
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                self.assertEqual(pf.mask(text), (expected, {**NOTHING, "personalNumber": 1}))

    def test_other_numbers_with_a_hyphen_are_left_alone(self):
        # Omöjliga datum, fel längd, och nummer som fortsätter.
        for text in ["641323-3234", "640832-3234", "640023-3234", "640800-3234", "Org.nr 556123-4567",
                     "640823-323", "640823-32345", "1640823-3234", "640823 3234", "640823/3234",
                     "21001231-1234", "Art 123456-7890", "991332-1234", "1986-1996", "ISBN 91-636-0347-0",
                     "640823-3234567"]:
            with self.subTest(text=text):
                self.assertEqual(pf.find(text)["personalNumber"], 0)


class TheMark(unittest.TestCase):
    def test_same_mark_for_every_category_and_no_letters_or_digits(self):
        self.assertEqual({pf.EMAIL_MARK, pf.PHONE_MARK, pf.GIRO_MARK, pf.PERSONAL_NUMBER_MARK}, {"•••"})
        self.assertFalse(any(character.isalnum() for character in pf.MARK))

    def test_the_tokenizer_makes_no_token_of_the_mark(self):
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.execute("CREATE VIRTUAL TABLE t USING fts5(x, tokenize='unicode61 remove_diacritics 0')")
        db.execute("CREATE VIRTUAL TABLE v USING fts5vocab(t, 'row')")
        db.execute("INSERT INTO t VALUES (?)", (f"Tel: {pf.MARK}, fax {pf.MARK}. {pf.MARK}",))
        self.assertEqual([row[0] for row in db.execute("SELECT term FROM v ORDER BY term")], ["fax", "tel"])
        # Bara markeringar: sidan har inga ord alls.
        db.execute("DELETE FROM t")
        db.execute("INSERT INTO t VALUES (?)", (f"{pf.MARK} {pf.MARK}",))
        self.assertEqual(db.execute("SELECT COUNT(*) FROM v").fetchone()[0], 0)

    def test_the_normalizer_leaves_the_mark_alone_and_adds_no_words(self):
        words = [bsi.Word(10.0 + 30 * index, 10.0, 35.0 + 30 * index, 20.0, text)
                 for index, text in enumerate(["Tel:", pf.MARK, "eller", pf.MARK])]
        self.assertEqual(bsi.normalize_page(words, LEXICON), f"Tel: {pf.MARK} eller {pf.MARK}")


class Properties(unittest.TestCase):
    TEXTS = list(PhoneNumbersAreMasked.CASES) + OtherNumbersAreLeftAlone.CASES + list(GiroNumbersAreMasked.CASES) + [
        *BankgiroNumbersAreMasked.CASES, *LocalPhoneNumbersAreMasked.CASES,
        "Tel 12 34 56 78 90 12", "Bankgiro 123-4567-8", "fax 123 45 67 89",
        "Tel 08-123 45 031-12 34 56", "08-12345 6031-12345", "a@b.se08-123456", "",
        # Ett nummer som bara maskeras till en del får inte ge en ny träff på resten.
        "postgiro 123 45 67-8 40-9", "Pg 12 34 56-7 12-3",
    ]

    def test_masked_text_has_nothing_left_to_mask(self):
        for text in self.TEXTS:
            with self.subTest(text=text):
                self.assertTrue(pf.is_clean(pf.mask(text)[0]))

    def test_masking_is_deterministic(self):
        joined = " | ".join(self.TEXTS)
        self.assertEqual(pf.mask(joined), pf.mask(joined))

    def test_version(self):
        self.assertEqual(pf.PRIVACY_FILTER_VERSION, 2)


class MaskedDatabase(unittest.TestCase):
    PAGES = {
        "datormagazin-1989-06": ["Amiga 500 säljes, 1 995:-. Tel: 0123-456 78, Anna. Pg 123 45 67-8.",
                                 "Commodore 128 och en C-64."],
        "highscore-1993-17": ["Skriv till red@example.se eller ring 08-12 34 56. P-nr 640823-3234.",
                              "Nr 12 1993, 499 kr."],
    }

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def build(self, name: str, privacy: bool) -> tuple[Path, dict]:
        path = Path(self.directory.name) / name
        issues = [fake_issue(issue_id, pages) for issue_id, pages in self.PAGES.items()]
        result = bsi.build_database(path, issues, diacritics=0, dataset_version="2026-10-06-006",
                                    built_at="2026-10-10T00:00:00Z", lexicon=LEXICON, privacy=privacy)
        return path, result

    def test_search_text_is_masked_and_raw_text_is_empty(self):
        path, result = self.build("masked.db", privacy=True)
        db = sqlite3.connect(path)
        self.addCleanup(db.close)
        rows = db.execute("SELECT rawText, searchText FROM pages ORDER BY id").fetchall()
        self.assertEqual(rows[0][1], f"Amiga 500 säljes, 1 995:-. Tel: {PHONE}, Anna. Pg {GIRO}.")
        self.assertEqual(rows[2][1], f"Skriv till {EMAIL} eller ring {PHONE}. P-nr {PNR}.")
        for raw, search in rows:
            self.assertEqual(raw, "")
            self.assertTrue(pf.is_clean(search))
        expected = {**NOTHING, "email": 1, "phone": 2, "giro": 1, "personalNumber": 1}
        self.assertEqual(result["masked"], expected)
        self.assertEqual(result["maskedPages"], expected)
        self.assertEqual(result["maskedAnyPages"], 2)
        # Kolumnen finns kvar: schemat är detsamma som i det omaskerade indexet.
        self.assertIn("rawText", [row[1] for row in db.execute("PRAGMA table_info(pages)")])

    def test_meta_has_the_filter_version(self):
        path, _ = self.build("masked.db", privacy=True)
        db = sqlite3.connect(path)
        self.addCleanup(db.close)
        meta = dict(db.execute("SELECT key, value FROM meta"))
        self.assertEqual(meta["privacyFilterVersion"], "2")
        self.assertEqual(meta["normalizerVersion"], "2")
        self.assertEqual(meta["inventoryDatasetVersion"], "2026-10-06-006")

    def test_unmasked_build_is_unchanged_and_says_nothing_about_a_filter(self):
        path, result = self.build("plain.db", privacy=False)
        db = sqlite3.connect(path)
        self.addCleanup(db.close)
        self.assertNotIn("privacyFilterVersion", dict(db.execute("SELECT key, value FROM meta")))
        self.assertIn("0123-456 78", db.execute("SELECT searchText FROM pages ORDER BY id").fetchone()[0])
        self.assertIsNone(result["masked"])

    def test_the_numbers_are_nowhere_in_the_file(self):
        path, _ = self.build("masked.db", privacy=True)
        data = path.read_bytes()
        for secret in (b"456 78", b"12 34 56", b"red@example", b"example.se", b"45 67-8", b"640823", b"3234"):
            self.assertNotIn(secret, data)

    def test_search_still_works_and_snippets_show_the_mark(self):
        path, _ = self.build("masked.db", privacy=True)
        db = sqlite3.connect(path)
        self.addCleanup(db.close)
        snippet = db.execute("SELECT snippet(pages_fts, 0, '[', ']', '…', 12) FROM pages_fts "
                             "WHERE pages_fts MATCH 'säljes'").fetchone()[0]
        self.assertIn(pf.MARK, snippet)
        # Markeringen ger inga ord: bara textens egna ord finns i indexet.
        db.execute("CREATE VIRTUAL TABLE vocabulary USING fts5vocab(pages_fts, 'row')")
        terms = {row[0] for row in db.execute("SELECT term FROM vocabulary")}
        self.assertNotIn("telefon", terms)
        self.assertNotIn("post", terms)
        self.assertIn("tel", terms)
        self.assertEqual(db.execute("SELECT COUNT(*) FROM pages_fts WHERE pages_fts MATCH '\"Commodore 128\"'")
                         .fetchone()[0], 1)

    def test_masked_build_is_reproducible(self):
        first, _ = self.build("a.db", privacy=True)
        second, _ = self.build("b.db", privacy=True)
        self.assertEqual(first.read_bytes(), second.read_bytes())


if __name__ == "__main__":
    unittest.main()
