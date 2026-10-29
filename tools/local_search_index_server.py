#!/usr/bin/env python3
"""DMZ Reader – en lokal server i GitHubs ställe, för att pröva hämtningen av sökindexet för hand.

Servern har samma sökvägar som en GitHub-release:

  /Rognils/dmz-reader/releases/latest/download/search-index.json   manifestet
  /Rognils/dmz-reader/releases/download/<tagg>/<fil>               302 till /cdn/<n>/<fil>
  /cdn/<n>/<fil>                                                    filen, med ETag, Range och If-Range

Varje omdirigering går till en ny adress under /cdn/. De äldre ger 403, som GitHubs
signerade adresser som har gått ut. Servern lyssnar bara på 127.0.0.1 och når telefonen
genom USB-kabeln med adb reverse. Den kontaktar ingenting själv.

Kör på datorn:
  python3 tools/local_search_index_server.py FIL.db [--cut-after BYTE] [--rate KB_PER_S]
                                                    [--bad-checksum] [--claim-size BYTE]
  adb reverse tcp:8765 tcp:8765

och peka debug-bygget på manifestet:
  adb shell am start -S -n se.c64.dmzreader.debug/se.c64.dmzreader.MainActivity \\
      --es dmzSearchIndexManifest http://127.0.0.1:8765/Rognils/dmz-reader/releases/latest/download/search-index.json

Tillbaka till GitHub: adb shell "am start -S -n se.c64.dmzreader.debug/se.c64.dmzreader.MainActivity
--es dmzSearchIndexManifest ''" (-S startar om appen, som läser adressen när den startar). Ta bort
omdirigeringen med adb reverse --remove tcp:8765.

  --cut-after BYTE   bryt den första överföringen efter så många byte (avbrott och återupptagning)
  --rate KB_PER_S    skicka långsamt, så att förloppet och Avbryt hinner synas
  --bad-checksum     manifestet anger fel SHA-256: filen ska kastas och aldrig importeras
  --claim-size BYTE  manifestet anger en annan storlek, till exempel större än det lediga utrymmet
"""

from __future__ import annotations

import argparse
import itertools
import json
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import make_search_index_manifest as msm  # noqa: E402

PREFIX = "/Rognils/dmz-reader/releases"
TAG = "v-lokal"
ETAG = '"lokal-1"'


class Release:
    """Det servern delar ut: filen, manifestet och vilka omdirigeringar som gäller."""

    def __init__(self, path: Path, cut_after: int | None, rate: int | None, bad_checksum: bool, claim_size: int | None,
                 allow_unmasked: bool = False):
        self.data = path.read_bytes()
        self.name = path.name
        if allow_unmasked:
            manifest = {
                "manifestVersion": msm.MANIFEST_VERSION, "releaseTag": TAG, "fileName": path.name, "size": len(self.data),
                "sha256": msm.sha256(path), "searchSchemaVersion": "1", "inventoryDatasetVersion": "test",
                "normalizerVersion": "test", "privacyFilterVersion": "test", "minAppVersionCode": msm.DEFAULT_MIN_APP_VERSION_CODE,
            }
        else:
            manifest = msm.build_manifest(path, TAG)
        if bad_checksum:
            manifest["sha256"] = "0" * 64
        if claim_size is not None:
            manifest["size"] = claim_size
        self.manifest = json.dumps(manifest, indent=2).encode()
        self.cut_after = cut_after
        self.rate = rate
        self.lock = threading.Lock()
        self.counter = itertools.count(1)
        self.current = 0

    def next_cdn(self) -> int:
        with self.lock:
            self.current = next(self.counter)
            return self.current

    def take_cut(self) -> int | None:
        with self.lock:
            cut, self.cut_after = self.cut_after, None
            return cut


def handler_for(release: Release, log=print):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            log(f"{self.command} {self.path} {fmt % args}")

        def send_plain(self, code: int, body: bytes = b"", headers: dict | None = None):
            self.send_response(code)
            for key, value in (headers or {}).items():
                self.send_header(key, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if body and self.command != "HEAD":
                self.wfile.write(body)

        def do_GET(self):
            path = self.path.split("?")[0]
            if path == f"{PREFIX}/latest/download/search-index.json":
                return self.send_plain(200, release.manifest, {"Content-Type": "application/json"})
            if path == f"{PREFIX}/download/{TAG}/{release.name}":
                return self.send_plain(302, headers={"Location": f"/cdn/{release.next_cdn()}/{release.name}"})
            match = re.fullmatch(r"/cdn/(\d+)/(.+)", path)
            if match and match.group(2) == release.name:
                if int(match.group(1)) != release.current:
                    return self.send_plain(403, b"adressen har gatt ut")
                return self.send_file()
            return self.send_plain(404)

        def send_file(self):
            data = release.data
            start = 0
            ranged = re.fullmatch(r"bytes=(\d+)-", self.headers.get("Range", ""))
            if ranged and self.headers.get("If-Range", ETAG) == ETAG:
                start = int(ranged.group(1))
                if start >= len(data):
                    return self.send_plain(416, headers={"Content-Range": f"bytes */{len(data)}"})
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {start}-{len(data) - 1}/{len(data)}")
            else:
                self.send_response(200)
            self.send_header("ETag", ETAG)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(len(data) - start))
            self.end_headers()
            cut = release.take_cut()
            chunk = 64 * 1024
            sent = 0
            for offset in range(start, len(data), chunk):
                piece = data[offset:offset + chunk]
                if cut is not None and sent + len(piece) > cut:
                    self.wfile.write(piece[:max(0, cut - sent)])
                    self.wfile.flush()
                    log(f"överföringen bröts efter {cut} byte, som när nätet försvinner")
                    self.close_connection = True
                    self.connection.shutdown(2)
                    return
                self.wfile.write(piece)
                sent += len(piece)
                if release.rate:
                    time.sleep(len(piece) / (release.rate * 1024))

    return Handler


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="En lokal server i GitHubs ställe för sökindexet.")
    parser.add_argument("database", type=Path)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--cut-after", type=int)
    parser.add_argument("--rate", type=int, help="kB per sekund")
    parser.add_argument("--bad-checksum", action="store_true")
    parser.add_argument("--claim-size", type=int)
    args = parser.parse_args(argv)
    try:
        release = Release(args.database, args.cut_after, args.rate, args.bad_checksum, args.claim_size)
    except msm.ManifestError as error:
        print(f"FEL: {error}", file=sys.stderr)
        return 1
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler_for(release))
    url = f"http://127.0.0.1:{args.port}{PREFIX}/latest/download/search-index.json"
    print(f"Serverar {args.database.name} ({len(release.data)} byte) som releasen {TAG}.")
    print(f"Manifest: {url}")
    print(f"Telefonen: adb reverse tcp:{args.port} tcp:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
