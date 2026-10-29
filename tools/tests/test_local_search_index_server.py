"""Tester för tools/local_search_index_server.py, mot testindexet. Allt sker på 127.0.0.1."""

import http.client
import json
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import local_search_index_server as lss  # noqa: E402

TEST_DB = Path(__file__).resolve().parents[2] / "app" / "src" / "debug" / "assets" / "search_test.db"


class LocalServer(unittest.TestCase):
    def start(self, **options):
        release = lss.Release(TEST_DB, options.get("cut_after"), None, options.get("bad_checksum", False),
                              options.get("claim_size"), allow_unmasked=True)
        server = ThreadingHTTPServer(("127.0.0.1", 0), lss.handler_for(release, log=lambda _: None))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.port = server.server_address[1]
        return release

    def get(self, path, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        connection.request("GET", path, headers=headers or {})
        response = connection.getresponse()
        try:
            body = response.read()
        except http.client.IncompleteRead as cut:
            body = cut.partial
        connection.close()
        return response, body

    def file_path(self):
        response, _ = self.get(f"{lss.PREFIX}/download/{lss.TAG}/search_test.db")
        self.assertEqual(response.status, 302)
        return response.getheader("Location")

    def test_manifest_redirect_range_and_expired_addresses(self):
        data = TEST_DB.read_bytes()
        self.start()
        response, body = self.get(f"{lss.PREFIX}/latest/download/search-index.json")
        manifest = json.loads(body)
        self.assertEqual((manifest["releaseTag"], manifest["size"]), (lss.TAG, len(data)))
        first = self.file_path()
        response, body = self.get(first)
        self.assertEqual((response.status, body), (200, data))
        second = self.file_path()
        self.assertEqual(self.get(first)[0].status, 403)
        response, body = self.get(second, {"Range": "bytes=1000-", "If-Range": lss.ETAG})
        self.assertEqual(response.status, 206)
        self.assertEqual(body, data[1000:])
        response, _ = self.get(self.file_path(), {"Range": "bytes=1000-", "If-Range": '"annan"'})
        self.assertEqual(response.status, 200)

    def test_the_first_transfer_can_be_cut(self):
        self.start(cut_after=20_000)
        _, body = self.get(self.file_path())
        self.assertEqual(len(body), 20_000)
        _, body = self.get(self.file_path())
        self.assertEqual(body, TEST_DB.read_bytes())

    def test_the_manifest_can_lie_about_checksum_and_size(self):
        self.start(bad_checksum=True, claim_size=10**12)
        manifest = json.loads(self.get(f"{lss.PREFIX}/latest/download/search-index.json")[1])
        self.assertEqual(manifest["sha256"], "0" * 64)
        self.assertEqual(manifest["size"], 10**12)

    def test_an_unmasked_index_is_refused_without_the_test_option(self):
        with self.assertRaises(lss.msm.ManifestError):
            lss.Release(TEST_DB, None, None, False, None)


if __name__ == "__main__":
    unittest.main()
