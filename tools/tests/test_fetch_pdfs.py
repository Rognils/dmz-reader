"""Tester för tools/fetch_pdfs.py mot en lokal HTTP-server. Inget hämtas från arkivet."""

import hashlib
import http.server
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import fetch_pdfs as fp  # noqa: E402

CONTENT = bytes(range(256)) * 4000  # ~1 MB
ETAG = '"v1-etag"'


class Handler(http.server.BaseHTTPRequestHandler):
    """Serverar en fil med stöd för Range och If-Range. Kan bryta mitt i svaret."""

    content = CONTENT
    etag = ETAG
    cut_after = None  # antal byte innan anslutningen bryts, en gång
    requests = []

    def log_message(self, *args):
        pass

    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(self.content)))
        self.send_header("ETag", self.etag)
        self.end_headers()

    def do_GET(self):
        cls = type(self)
        cls.requests.append(dict(self.headers))
        start = 0
        range_header = self.headers.get("Range")
        if range_header and self.headers.get("If-Range") == cls.etag:
            start = int(range_header.split("=")[1].rstrip("-"))
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{len(cls.content) - 1}/{len(cls.content)}")
        else:
            self.send_response(200)
        body = cls.content[start:]
        self.send_header("Content-Length", str(len(body)))
        self.send_header("ETag", cls.etag)
        self.send_header("Last-Modified", "Sat, 09 Sep 2017 19:49:26 GMT")
        self.end_headers()
        if cls.cut_after is not None:
            body, cls.cut_after = body[:cls.cut_after], None
            self.wfile.write(body)
            self.wfile.flush()
            self.connection.close()
            return
        self.wfile.write(body)


class FetchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.server.server_address[1]}/a.pdf"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        Handler.content, Handler.etag, Handler.cut_after, Handler.requests = CONTENT, ETAG, None, []
        self.directory = tempfile.TemporaryDirectory()
        self.cache = Path(self.directory.name)
        self.issue = {"issueId": "test-1", "ocrPdfUrl": self.url,
                      "ocrPdfSize": len(CONTENT), "ocrPdfEtag": ETAG}

    def tearDown(self):
        self.directory.cleanup()

    def fast(self, **kwargs):
        return fp.fetch_issue(self.issue, self.cache, kwargs.pop("manifest"), rate=1e12,
                              log=lambda _: None, **kwargs)

    def test_download_records_size_etag_and_hash(self):
        manifest = {"files": {}}
        entry = self.fast(manifest=manifest)
        self.assertEqual(entry["size"], len(CONTENT))
        self.assertEqual(entry["etag"], ETAG)
        self.assertEqual(entry["sha256"], hashlib.sha256(CONTENT).hexdigest())
        self.assertEqual(entry["problems"], [])
        self.assertEqual((self.cache / "test-1.pdf").read_bytes(), CONTENT)
        self.assertEqual(fp.load_manifest(self.cache)["files"]["test-1"], entry)

    def test_complete_file_is_not_fetched_again(self):
        manifest = {"files": {}}
        self.fast(manifest=manifest)
        self.fast(manifest=manifest)
        self.assertEqual(len(Handler.requests), 1)

    def test_interrupted_download_is_resumed_with_range(self):
        Handler.cut_after = 300_000
        manifest = {"files": {}}
        with self.assertRaises((fp.FetchError, OSError)):
            self.fast(manifest=manifest)
        part = self.cache / "test-1.pdf.part"
        self.assertFalse((self.cache / "test-1.pdf").exists())
        kept = part.stat().st_size
        self.assertGreater(kept, 0)
        entry = self.fast(manifest=manifest)
        self.assertEqual(Handler.requests[-1]["Range"], f"bytes={kept}-")
        self.assertEqual(Handler.requests[-1]["If-Range"], ETAG)
        self.assertEqual((self.cache / "test-1.pdf").read_bytes(), CONTENT)
        self.assertEqual(entry["problems"], [])
        self.assertFalse(part.exists())

    def test_file_changed_on_server_during_resume_starts_over(self):
        Handler.cut_after = 300_000
        manifest = {"files": {}}
        with self.assertRaises((fp.FetchError, OSError)):
            self.fast(manifest=manifest)
        Handler.content, Handler.etag = CONTENT[::-1], '"v2-etag"'
        entry = self.fast(manifest=manifest)
        # Filen är den nya, hel. Den stämmer inte med inventory, och det syns.
        self.assertEqual((self.cache / "test-1.pdf").read_bytes(), CONTENT[::-1])
        self.assertEqual(len(entry["problems"]), 1)
        self.assertIn("ETag", entry["problems"][0])

    def test_wrong_size_is_reported(self):
        self.issue["ocrPdfSize"] = 123
        entry = self.fast(manifest={"files": {}})
        self.assertIn("storlek", entry["problems"][0])

    def test_adopts_local_copy_only_when_it_matches(self):
        local = self.cache / "local.pdf"
        local.write_bytes(CONTENT)
        manifest = {"files": {}}
        self.assertTrue(fp.adopt_local_copy(self.issue, local, self.cache, manifest, log=lambda _: None))
        self.assertEqual(Handler.requests, [])  # bara HEAD
        self.assertEqual(manifest["files"]["test-1"]["etag"], ETAG)

        local.write_bytes(CONTENT[:-1])
        self.assertFalse(fp.adopt_local_copy(self.issue, local, self.cache, {"files": {}}, log=lambda _: None))


class ThrottleTest(unittest.TestCase):
    def test_sleeps_to_stay_under_the_limit(self):
        now = [0.0]
        slept = []

        def sleep(seconds):
            slept.append(seconds)
            now[0] += seconds

        throttle = fp.Throttle(1000, clock=lambda: now[0], sleep=sleep)
        for _ in range(5):
            throttle.add(1000)
        self.assertAlmostEqual(now[0], 5.0)

    def test_does_not_sleep_when_the_network_is_slower(self):
        now = [0.0]
        slept = []
        throttle = fp.Throttle(1000, clock=lambda: now[0], sleep=slept.append)
        for _ in range(5):
            now[0] += 2.0
            throttle.add(1000)
        self.assertEqual(slept, [])


if __name__ == "__main__":
    unittest.main()
