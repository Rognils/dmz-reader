#!/usr/bin/env python3
"""DMZ Reader – hämtar OCR-PDF:er till den lokala cachen (FAS 8).

PDF:erna hämtas via HTTPS från `ocrPdfUrl` i inventory och läggs i
cache/pdf/{issueId}.pdf. Cachen versionshanteras inte.

Servern belastas försiktigt:
  - en fil och en anslutning i taget, med paus mellan filerna
  - högst MAX_BYTES_PER_SECOND
  - en avbruten hämtning återupptas med Range i stället för att börja om
  - en fil som redan är komplett hämtas inte igen

Varje fil kontrolleras mot `ocrPdfSize` och `ocrPdfEtag` i inventory. Storlek,
ETag, Last-Modified och SHA-256 sparas i cache/pdf/manifest.json. En fil som
inte stämmer med inventory behålls men markeras, och hoppas över av indexbygget.

Kör:  python3 tools/fetch_pdfs.py --pilot
      python3 tools/fetch_pdfs.py datormagazin-1989-06 highscore-1993-17
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
INVENTORY_PATH = REPO_ROOT / "data" / "archive_inventory.json"
CACHE_DIR = REPO_ROOT / "cache" / "pdf"
MANIFEST_NAME = "manifest.json"

MAX_BYTES_PER_SECOND = 5_000_000
PAUSE_BETWEEN_FILES = 3.0
MIN_FREE_BYTES_AFTER = 20 * 10**9  # så här mycket ska vara ledigt även efter hämtningen
CHUNK_SIZE = 256 * 1024
TIMEOUT_SECONDS = 60
USER_AGENT = "dmz-reader-toolchain/0.8 (privat sökindex; en anslutning, max 5 MB/s)"

# Urvalet för piloten i FAS 8A. 1995 nr 15 (563 MB) ingår inte.
PILOT_ISSUES = [
    "datormagazin-1986-01",
    "datormagazin-1986-04",
    "datormagazin-1987-10b",
    "datormagazin-1989-06",
    "datormagazin-1989-12",
    "datormagazin-1991-02",
    "datormagazin-1993-03",
    "datormagazin-1995-05-06",
    "datormagazin-1995-14",
    "highscore-1993-17",
    "highscore-1994-08",
]


class FetchError(Exception):
    pass


class Throttle:
    """Håller genomsnittshastigheten under en gräns genom att sova."""

    def __init__(self, bytes_per_second: float, clock=time.monotonic, sleep=time.sleep):
        self.rate = bytes_per_second
        self.clock = clock
        self.sleep = sleep
        self.started = clock()
        self.sent = 0

    def add(self, count: int) -> None:
        self.sent += count
        ahead = self.sent / self.rate - (self.clock() - self.started)
        if ahead > 0:
            self.sleep(ahead)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_manifest(cache_dir: Path) -> dict:
    path = cache_dir / MANIFEST_NAME
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"files": {}}


def save_manifest(cache_dir: Path, manifest: dict) -> None:
    path = cache_dir / MANIFEST_NAME
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(manifest, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
                         encoding="utf-8")
    temporary.replace(path)


def verify(issue: dict, size: int, etag: str | None) -> list[str]:
    """Jämför en hämtad fil med inventory. Tom lista betyder att allt stämmer."""
    problems = []
    if issue.get("ocrPdfSize") is not None and size != issue["ocrPdfSize"]:
        problems.append(f"storlek {size} ≠ inventory {issue['ocrPdfSize']}")
    if issue.get("ocrPdfEtag") is not None and etag != issue["ocrPdfEtag"]:
        problems.append(f"ETag {etag} ≠ inventory {issue['ocrPdfEtag']}")
    return problems


def download(url: str, target: Path, *, rate: float = MAX_BYTES_PER_SECOND,
             progress=None) -> dict:
    """Hämtar `url` till `target`. En befintlig `.part`-fil återupptas.

    Returnerar serverns storlek, ETag och Last-Modified. Återupptagningen görs
    med If-Range, så att en fil som bytts på servern hämtas om från början i
    stället för att sättas ihop av två olika versioner.
    """
    part = target.with_name(target.name + ".part")
    etag_file = target.with_name(target.name + ".part.etag")
    offset = part.stat().st_size if part.exists() else 0
    part_etag = etag_file.read_text(encoding="utf-8") if etag_file.exists() else None
    if offset and not part_etag:
        offset = 0  # utan ETag går det inte att veta att resten hör till samma fil

    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    if offset:
        request.add_header("Range", f"bytes={offset}-")
        request.add_header("If-Range", part_etag)
    try:
        response = urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS)
    except urllib.error.HTTPError as error:
        if error.code == 416 and offset:
            # Delfilen är redan lika stor som filen, eller större. Börja om.
            part.unlink()
            etag_file.unlink(missing_ok=True)
            return download(url, target, rate=rate, progress=progress)
        raise FetchError(f"HTTP {error.code} för {url}") from error

    with response:
        etag = response.headers.get("ETag")
        last_modified = response.headers.get("Last-Modified")
        if response.status == 206:
            total = int(response.headers["Content-Range"].rsplit("/", 1)[1])
            mode = "ab"
        else:
            total = int(response.headers["Content-Length"])
            offset = 0
            mode = "wb"
        if etag:
            etag_file.write_text(etag, encoding="utf-8")
        throttle = Throttle(rate)
        received = offset
        with open(part, mode) as handle:
            while True:
                block = response.read(CHUNK_SIZE)
                if not block:
                    break
                handle.write(block)
                received += len(block)
                throttle.add(len(block))
                if progress:
                    progress(received, total)
    if received != total:
        raise FetchError(f"avbruten vid {received} av {total} byte (körs om för att återuppta)")
    part.replace(target)
    etag_file.unlink(missing_ok=True)
    return {"size": total, "etag": etag, "lastModified": last_modified, "resumedFrom": offset}


def fetch_issue(issue: dict, cache_dir: Path, manifest: dict, *, rate: float = MAX_BYTES_PER_SECOND,
                log=print) -> dict:
    """Ser till att numrets PDF finns i cachen och är kontrollerad. Returnerar manifestposten."""
    issue_id = issue["issueId"]
    target = cache_dir / f"{issue_id}.pdf"
    entry = manifest["files"].get(issue_id)
    if entry and target.exists() and target.stat().st_size == entry["size"] and not entry.get("problems"):
        if not verify(issue, entry["size"], entry.get("etag")):
            log(f"  {issue_id}: finns redan ({entry['size'] / 1e6:.1f} MB)")
            return entry

    started = time.monotonic()
    result = download(issue["ocrPdfUrl"], target, rate=rate)
    seconds = time.monotonic() - started
    entry = {
        "size": result["size"],
        "etag": result["etag"],
        "lastModified": result["lastModified"],
        "sha256": sha256_of(target),
        "url": issue["ocrPdfUrl"],
        "source": "https",
        "fetchedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "seconds": round(seconds, 1),
        "problems": verify(issue, result["size"], result["etag"]),
    }
    manifest["files"][issue_id] = entry
    save_manifest(cache_dir, manifest)
    fetched = result["size"] - result["resumedFrom"]
    speed = fetched / 1e6 / seconds if seconds > 0 else 0
    note = f", återupptagen från {result['resumedFrom'] / 1e6:.1f} MB" if result["resumedFrom"] else ""
    log(f"  {issue_id}: {result['size'] / 1e6:.1f} MB på {seconds:.0f} s ({speed:.1f} MB/s){note}"
        + (f"  AVVIKER: {'; '.join(entry['problems'])}" if entry["problems"] else ""))
    return entry


def adopt_local_copy(issue: dict, source: Path, cache_dir: Path, manifest: dict, log=print) -> bool:
    """Tar in en PDF som redan finns på datorn, om den stämmer med inventory.

    Filen kontrolleras mot `ocrPdfSize`, och servern tillfrågas med HEAD om
    ETag fortfarande är den som inventory anger. Då behöver filen inte hämtas igen.
    """
    issue_id = issue["issueId"]
    if not source.exists() or source.stat().st_size != issue.get("ocrPdfSize"):
        return False
    request = urllib.request.Request(issue["ocrPdfUrl"], method="HEAD", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        size = int(response.headers["Content-Length"])
        etag = response.headers.get("ETag")
        last_modified = response.headers.get("Last-Modified")
    if verify(issue, size, etag):
        return False
    target = cache_dir / f"{issue_id}.pdf"
    target.write_bytes(source.read_bytes())
    manifest["files"][issue_id] = {
        "size": size,
        "etag": etag,
        "lastModified": last_modified,
        "sha256": sha256_of(target),
        "url": issue["ocrPdfUrl"],
        "source": f"lokal kopia ({source.name}), storlek och ETag kontrollerade med HEAD",
        "fetchedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "seconds": 0,
        "problems": [],
    }
    save_manifest(cache_dir, manifest)
    log(f"  {issue_id}: lokal kopia {source.name} ({size / 1e6:.1f} MB), ETag stämmer")
    return True


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("issues", nargs="*", help="issueId att hämta")
    parser.add_argument("--pilot", action="store_true", help="hämta urvalet för FAS 8A")
    parser.add_argument("--all", action="store_true",
                        help="hämta alla nummer som har en PDF, de minsta filerna först")
    parser.add_argument("--adopt", action="append", default=[], metavar="ISSUEID=FIL",
                        help="använd en PDF som redan finns på datorn om den stämmer med inventory")
    arguments = parser.parse_args(argv)

    inventory = json.loads(INVENTORY_PATH.read_text(encoding="utf-8"))
    by_id = {issue["issueId"]: issue for issue in inventory["issues"]}
    wanted = list(arguments.issues) + (PILOT_ISSUES if arguments.pilot else [])
    if arguments.all:
        wanted = [issue["issueId"] for issue in sorted(
            (issue for issue in inventory["issues"] if issue.get("ocrPdfUrl")),
            key=lambda issue: (issue.get("ocrPdfSize") or 0, issue["issueId"]))]
    if not wanted:
        parser.error("ange issueId, --pilot eller --all")
    unknown = [issue_id for issue_id in wanted if issue_id not in by_id]
    if unknown:
        parser.error(f"finns inte i inventory: {', '.join(unknown)}")

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    manifest = load_manifest(CACHE_DIR)
    missing = sum(by_id[i].get("ocrPdfSize") or 0 for i in wanted if i not in manifest["files"])
    free = shutil.disk_usage(CACHE_DIR).free
    print(f"att hämta: {missing / 1e9:.2f} GB, ledigt på disken: {free / 1e9:.0f} GB")
    if free < missing + MIN_FREE_BYTES_AFTER:
        print("för lite ledigt utrymme, avbryter", file=sys.stderr)
        return 2
    adopt = dict(item.split("=", 1) for item in arguments.adopt)
    failures = 0
    first = True
    for issue_id in wanted:
        issue = by_id[issue_id]
        if not issue.get("ocrPdfUrl"):
            print(f"  {issue_id}: saknar ocrPdfUrl, hoppas över")
            continue
        if issue_id in adopt and issue_id not in manifest["files"]:
            if adopt_local_copy(issue, Path(adopt[issue_id]), CACHE_DIR, manifest):
                time.sleep(1.0)
                continue
        cached = issue_id in manifest["files"] and (CACHE_DIR / f"{issue_id}.pdf").exists()
        if not cached and not first:
            time.sleep(PAUSE_BETWEEN_FILES)
        try:
            entry = fetch_issue(issue, CACHE_DIR, manifest)
        except (FetchError, OSError) as error:
            print(f"  {issue_id}: MISSLYCKADES: {error}", file=sys.stderr)
            failures += 1
            continue
        finally:
            first = first and cached
        if entry["problems"]:
            failures += 1
    total = sum(manifest["files"][i]["size"] for i in wanted if i in manifest["files"])
    print(f"{len([i for i in wanted if i in manifest['files']])} av {len(wanted)} filer i cachen, "
          f"{total / 1e6:.0f} MB. Avvikelser eller fel: {failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
