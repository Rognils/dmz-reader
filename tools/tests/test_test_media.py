"""Tester för tools/make_test_media.py: testernas egengjorda PDF och omslag."""

import hashlib
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import make_test_media as media  # noqa: E402


class TestMedia(unittest.TestCase):
    def test_the_committed_pdf_is_what_the_script_builds(self):
        self.assertEqual(media.PDF_PATH.read_bytes(), media.build_pdf(), "kör python3 tools/make_test_media.py och committa filen")

    def test_the_pdf_is_the_one_the_prepush_check_allows(self):
        digest = hashlib.sha256(media.PDF_PATH.read_bytes()).hexdigest()
        script = (media.REPO_ROOT / "tools" / "prepush_check.sh").read_text(encoding="utf-8")
        self.assertIn(f"app/src/androidTest/assets/test_issue.pdf {digest}", script)

    def test_the_pdf_has_28_pages_of_invented_text(self):
        pdf = media.build_pdf()
        self.assertTrue(pdf.startswith(b"%PDF-1.4"))
        self.assertEqual(pdf.count(b"/Type /Page /Parent"), len(media.PAGES))
        # Lika många sidor som 1986 nr 1 har i arkivindexet: testerna läser filen under det numrets namn.
        self.assertEqual(len(media.PAGES), 28)
        self.assertIn("Testmagasinet · sida 4 av 28".encode("cp1252"), pdf)
        self.assertIn("Testmagasinet · sida 28 av 28".encode("cp1252"), pdf)
        # Stor nog för att en nedladdning ska gå att avbryta mitt i.
        self.assertGreater(len(pdf), 250_000)

    def test_the_cross_reference_table_is_last(self):
        # Testköraren fyller ut filen med blanktecken före tabellen. Då måste allt efter "xref" vara tabellen och slutet.
        pdf = media.build_pdf()
        table = pdf.rindex(b"\nxref\n") + 1
        self.assertEqual(pdf.count(b"\nxref\n"), 1)
        self.assertRegex(pdf[table:], rb"(?s)^xref\n.*trailer\n<< [^>]+ >>\nstartxref\n%d\n%%%%EOF\n$" % table)

    def test_pdfinfo_reads_it(self):
        try:
            output = subprocess.run(["pdfinfo", str(media.PDF_PATH)], capture_output=True, text=True, check=True).stdout
        except FileNotFoundError:
            self.skipTest("pdfinfo finns inte")
        self.assertRegex(output, r"Pages:\s+28")

    def test_the_cover_is_a_jpeg_in_the_size_of_a_scan(self):
        data = media.COVER_PATH.read_bytes()
        self.assertTrue(data.startswith(b"\xff\xd8\xff"))
        from PIL import Image
        with Image.open(media.COVER_PATH) as image:
            self.assertEqual(image.size, media.COVER_SIZE)


if __name__ == "__main__":
    unittest.main()
