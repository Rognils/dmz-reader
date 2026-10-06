#!/usr/bin/env python3
"""DMZ Reader – arkivinventering (FAS 0).

Steg 1: lista arkivets FTP-kataloger och spara rålistningen i
data/raw_listing.json. Rålistningen är också cachen: med --offline
rörs inte nätet alls.

Servern belastas försiktigt: en anslutning, passivt läge och en paus
på minst 0,5 sekunder mellan varje kataloganrop.
"""

from __future__ import annotations

import argparse
import ftplib
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

VERSION = "0.1.0"
REPO_ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = Path(__file__).resolve().parent / "config.json"
MIN_PAUSE_SECONDS = 0.5


def load_config(path: Path = CONFIG_PATH) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


# --------------------------------------------------------------------------
# FTP-listning
# --------------------------------------------------------------------------

class Lister:
    """Rekursiv MLSD-listning över en enda FTP-anslutning."""

    def __init__(self, config: dict, log=sys.stderr):
        ftp_cfg = config["ftp"]
        self.host = ftp_cfg["host"]
        self.user = ftp_cfg["user"]
        self.password = ftp_cfg["password"]
        self.timeout = ftp_cfg.get("timeoutSeconds", 60)
        self.pause = max(MIN_PAUSE_SECONDS, ftp_cfg.get("pauseSeconds", MIN_PAUSE_SECONDS))
        self.log = log
        self.ftp: ftplib.FTP | None = None
        self.calls = 0

    def connect(self) -> None:
        self.ftp = ftplib.FTP(self.host, timeout=self.timeout)
        self.ftp.login(self.user, self.password)
        self.ftp.set_pasv(True)

    def close(self) -> None:
        if self.ftp is not None:
            try:
                self.ftp.quit()
            except ftplib.all_errors:
                pass
            self.ftp = None

    def features(self) -> list[str]:
        resp = self.ftp.sendcmd("FEAT")
        return [line.strip() for line in resp.splitlines()[1:-1]]

    def list_dir(self, path: str) -> list[dict]:
        """Ett kataloganrop. Pausen tas alltid före anropet."""
        time.sleep(self.pause)
        self.calls += 1
        entries = []
        for name, facts in self.ftp.mlsd(path):
            if name in (".", ".."):
                continue
            kind = facts.get("type", "")
            entry = {"name": name, "type": "dir" if kind == "dir" else "file"}
            if kind != "dir" and "size" in facts:
                entry["size"] = int(facts["size"])
            if "modify" in facts:
                entry["modify"] = facts["modify"]
            entries.append(entry)
        entries.sort(key=lambda e: e["name"])
        return entries

    def walk(self, root: str, directories: dict, skip_existing: bool) -> None:
        """Bredden-först över root. Resultatet läggs i directories[path]."""
        queue = [root]
        while queue:
            path = queue.pop(0)
            if skip_existing and path in directories:
                entries = directories[path]
            else:
                entries = self.list_dir(path)
                directories[path] = entries
                print(f"  [{self.calls:4d}] {path}  ({len(entries)} poster)", file=self.log)
            for entry in entries:
                if entry["type"] == "dir":
                    queue.append(f"{path}/{entry['name']}")


def fetch_listing(config: dict, roots: list[str], out_path: Path, resume: bool) -> dict:
    """Lista root-katalogen och alla angivna rötter rekursivt."""
    listing = None
    if resume and out_path.exists():
        with open(out_path, encoding="utf-8") as fh:
            listing = json.load(fh)
    if listing is None:
        listing = {"rawListingVersion": 1, "directories": {}}

    lister = Lister(config)
    lister.connect()
    try:
        listing["server"] = lister.host
        listing["method"] = "MLSD"
        listing["features"] = lister.features()
        listing["pauseSeconds"] = lister.pause
        directories = listing["directories"]
        # Arkivets rot listas alltid, så att kataloger som planen inte
        # känner till syns i rålistningen även om de inte gås igenom.
        directories[""] = lister.list_dir("/")
        for root in roots:
            print(f"Listar {root} …", file=lister.log)
            try:
                lister.walk(root, directories, skip_existing=resume)
            finally:
                # Spara det som hunnit listas, så att ett avbrott kan återupptas.
                listing["roots"] = sorted(set(listing.get("roots", [])) | {root})
                write_json(out_path, listing)
        listing["listedAt"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        listing["ftpCalls"] = lister.calls
        write_json(out_path, listing)
    finally:
        lister.close()
    return listing


def load_listing(path: Path) -> dict:
    if not path.exists():
        raise SystemExit(f"Ingen cache: {path} saknas. Kör utan --offline först.")
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=1, sort_keys=True)
        fh.write("\n")
    tmp.replace(path)


def iter_files(listing: dict):
    """Ger (sökväg, storlek, modify) för varje fil i rålistningen."""
    for directory in sorted(listing["directories"]):
        for entry in listing["directories"][directory]:
            if entry["type"] == "file":
                path = f"{directory}/{entry['name']}" if directory else entry["name"]
                yield path, entry.get("size"), entry.get("modify")


# --------------------------------------------------------------------------
# Kommandorad
# --------------------------------------------------------------------------

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--offline", action="store_true",
                        help="använd bara cachen (data/raw_listing.json), inget nät")
    parser.add_argument("--resume", action="store_true",
                        help="återuppta en avbruten listning; redan listade kataloger hoppas över")
    parser.add_argument("--roots", nargs="+", metavar="KATALOG",
                        help="kataloger att lista (standard: enligt config.json)")
    args = parser.parse_args(argv)

    config = load_config()
    raw_path = REPO_ROOT / config["rawListingPath"]
    roots = args.roots or config["roots"]

    if args.offline:
        listing = load_listing(raw_path)
    else:
        listing = fetch_listing(config, roots, raw_path, resume=args.resume)

    files = list(iter_files(listing))
    print(f"Rålistning: {len(listing['directories'])} kataloger, {len(files)} filer "
          f"({'cache' if args.offline else 'server'}, listad {listing.get('listedAt', '?')})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
