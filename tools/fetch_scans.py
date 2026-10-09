#!/usr/bin/env python3
"""DMZ Reader – hämtar originalskanningar till den lokala cachen (FAS 11).

Skanningarna finns bara på FTP. De hämtas till cache/scans/{issueId}/{filnamn}
och används av tools/map_hq_pages.py för att kontrollera mappningen mellan
PDF-sida och skanningsfil. Cachen versionshanteras inte.

Servern belastas försiktigt:
  - en enda FTP-anslutning, en fil i taget, med paus mellan filerna
  - högst MAX_BYTES_PER_SECOND
  - en avbruten hämtning återupptas med REST i stället för att börja om
  - en fil som redan är komplett hämtas inte igen

Filnamn och storlekar tas ur rålistningen (data/raw_listing.json). En hämtad
fil ska ha samma storlek som där.

Kör:  python3 tools/fetch_scans.py datormagazin-1993-03 --all
      python3 tools/fetch_scans.py datormagazin-1989-06 --positions first middle last
      python3 tools/fetch_scans.py datormagazin-1995-15 --files DMZ_1995-15-015.jpg
      python3 tools/fetch_scans.py datormagazin-1989-14 --pages 1 28
"""

from __future__ import annotations

import argparse
import ftplib
import json
import sys
import time
from pathlib import Path

from fetch_pdfs import Throttle

REPO_ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = REPO_ROOT / "tools" / "config.json"
INVENTORY_PATH = REPO_ROOT / "data" / "archive_inventory.json"
RAW_LISTING_PATH = REPO_ROOT / "data" / "raw_listing.json"
CACHE_DIR = REPO_ROOT / "cache" / "scans"

MAX_BYTES_PER_SECOND = 5_000_000
PAUSE_BETWEEN_FILES = 1.5
MAX_RESUMES = 5
CHUNK_SIZE = 256 * 1024
IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png")


class ScanFetchError(Exception):
    pass


def scan_directory(issue: dict) -> str:
    """Katalogen med numrets skanningar, som den heter i rålistningen."""
    for name in issue["sourceNames"]:
        if name.startswith("Original Scans/"):
            return name.rstrip("/")
    raise ScanFetchError(f"{issue['issueId']}: inga skanningar i inventory")


def scan_files(issue: dict, listing: dict) -> list[dict]:
    """Alla bildfiler i numrets skanningskatalog, i namnordning. test.jpg och liknande ingår inte."""
    directory = scan_directory(issue)
    entries = [entry for entry in listing["directories"][directory]
               if entry["type"] == "file" and entry["name"].lower().endswith(IMAGE_SUFFIXES)
               and not entry["name"].lower().startswith("test")]
    return sorted(entries, key=lambda entry: entry["name"])


def pick_positions(files: list[dict], positions: list[str]) -> list[dict]:
    """Första, mellersta och sista filen. Mellersta är fil (n + 1) // 2, räknat från 1."""
    index = {"first": 0, "middle": (len(files) + 1) // 2 - 1, "last": len(files) - 1}
    chosen = []
    for position in positions:
        entry = files[index[position]]
        if entry not in chosen:
            chosen.append(entry)
    return chosen


class ScanFetcher:
    """En FTP-anslutning som återanvänds för alla filer."""

    def __init__(self, config: dict, *, rate: float = MAX_BYTES_PER_SECOND, pause: float = PAUSE_BETWEEN_FILES,
                 connect=ftplib.FTP, sleep=time.sleep, log=print):
        self.config = config["ftp"]
        self.rate = rate
        self.pause = pause
        self.connect = connect
        self.sleep = sleep
        self.log = log
        self.ftp = None
        self.connections = 0
        self.fetched = 0

    def _open(self):
        if self.ftp is None:
            self.ftp = self.connect(self.config["host"], timeout=self.config.get("timeoutSeconds", 60))
            self.ftp.login(self.config["user"], self.config["password"])
            self.ftp.set_pasv(True)
            self.ftp.voidcmd("TYPE I")
            self.connections += 1
        return self.ftp

    def close(self) -> None:
        if self.ftp is not None:
            try:
                self.ftp.quit()
            except ftplib.all_errors:
                pass
            self.ftp = None

    def fetch(self, remote: str, target: Path, size: int) -> bool:
        """Hämtar en fil. Returnerar falskt om den redan fanns. Kastar ScanFetchError om den inte blir hel."""
        if target.is_file() and target.stat().st_size == size:
            return False
        target.parent.mkdir(parents=True, exist_ok=True)
        part = target.with_name(target.name + ".part")
        if part.exists() and part.stat().st_size > size:
            part.unlink()
        if self.fetched:
            self.sleep(self.pause)
        self.fetched += 1
        for attempt in range(MAX_RESUMES + 1):
            offset = part.stat().st_size if part.exists() else 0
            if offset == size:
                break
            throttle = Throttle(self.rate)
            try:
                ftp = self._open()
                with part.open("ab") as output:
                    def write(chunk: bytes) -> None:
                        output.write(chunk)
                        throttle.add(len(chunk))
                    ftp.retrbinary(f"RETR {remote}", write, blocksize=CHUNK_SIZE, rest=offset or None)
            except ftplib.all_errors as error:
                self.log(f"  avbrott efter {part.stat().st_size if part.exists() else 0} byte ({error}); återupptar")
                # Anslutningen kan vara obrukbar efter ett avbrott. Den stängs, och en ny öppnas.
                try:
                    if self.ftp is not None:
                        self.ftp.close()
                finally:
                    self.ftp = None
                self.sleep(self.pause)
        actual = part.stat().st_size if part.exists() else 0
        if actual != size:
            raise ScanFetchError(f"{remote}: {actual} byte hämtade, {size} väntade")
        part.replace(target)
        return True


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("issues", nargs="+")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true", help="alla skanningar i numret, även bilagor")
    group.add_argument("--positions", nargs="+", choices=["first", "middle", "last"])
    group.add_argument("--files", nargs="+", help="namngivna filer")
    group.add_argument("--pages", nargs="+", type=int, help="skanningarna med dessa ordningsnummer, räknat från 1")
    arguments = parser.parse_args(argv)

    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    inventory = {issue["issueId"]: issue for issue in json.loads(INVENTORY_PATH.read_text(encoding="utf-8"))["issues"]}
    listing = json.loads(RAW_LISTING_PATH.read_text(encoding="utf-8"))
    fetcher = ScanFetcher(config)
    total = 0
    started = time.monotonic()
    try:
        for issue_id in arguments.issues:
            issue = inventory[issue_id]
            files = scan_files(issue, listing)
            if arguments.positions:
                files = pick_positions(files, arguments.positions)
            elif arguments.files:
                files = [entry for entry in files if entry["name"] in arguments.files]
            elif arguments.pages:
                files = [files[page - 1] for page in arguments.pages if 1 <= page <= len(files)]
            for entry in files:
                target = CACHE_DIR / issue_id / entry["name"]
                if fetcher.fetch(f"{scan_directory(issue)}/{entry['name']}", target, entry["size"]):
                    total += entry["size"]
                    print(f"{issue_id}/{entry['name']}: {entry['size']} byte", flush=True)
    except ScanFetchError as error:
        print(f"FEL: {error}", file=sys.stderr)
        return 1
    finally:
        fetcher.close()
    print(f"Klart: {total / 1e6:.1f} MB på {time.monotonic() - started:.0f} s, {fetcher.connections} anslutning(ar)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
