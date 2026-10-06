#!/usr/bin/env python3
"""DMZ Reader – hämtar omslagsbilder i original till cache/covers/.

Två källor hämtas för varje nummer:

- webbplatsens sidbild 1 (coverUrl i inventory), via HTTPS
- filen i FTP-katalogen Covers, om numret har en (.jpg och .png)

Servern belastas försiktigt: en anslutning åt gången, en paus mellan filerna,
och filer som redan ligger i cachen med rätt storlek hämtas inte igen. En
avbruten körning kan därför startas om.

Cachen versionshanteras inte. tools/build_covers.py gör miniatyrer av den.
"""

from __future__ import annotations

import argparse
import ftplib
import json
import sys
import time
import urllib.request
from pathlib import Path

import archive_inventory as ai

PAUSE_SECONDS = 0.6
CACHE_DIR = ai.REPO_ROOT / "cache" / "covers"
WEB_DIR = CACHE_DIR / "web"
FTP_DIR = CACHE_DIR / "ftp"
FTP_EXTENSIONS = (".jpg", ".png")


def web_cover_path(issue_id: str) -> Path:
    return WEB_DIR / f"{issue_id}.jpg"


def ftp_cover_path(ftp_path: str) -> Path:
    return FTP_DIR / ftp_path.rsplit("/", 1)[1]


def ftp_cover_files(listing: dict) -> dict[str, int]:
    """Alla .jpg och .png i Covers, med storlek. Övriga filtyper hämtas inte."""
    return {f"Covers/{entry['name']}": entry["size"]
            for entry in listing["directories"]["Covers"]
            if entry["type"] == "file" and entry["name"].lower().endswith(FTP_EXTENSIONS)}


def plan(inventory: dict, listing: dict, probe: dict) -> tuple[list, list]:
    """Vad som ska hämtas: (webb, ftp) som listor av (källa, målfil, väntad storlek)."""
    web = []
    for issue in inventory["issues"]:
        url = issue["coverUrl"]
        if url is None:
            continue
        expected = probe["results"].get(url.removeprefix(ai.WEB_BASE_URL), {}).get("content-length")
        web.append((url, web_cover_path(issue["issueId"]), int(expected) if expected else None))
    ftp = [(path, ftp_cover_path(path), size) for path, size in sorted(ftp_cover_files(listing).items())]
    return web, ftp


def is_cached(target: Path, expected_size: int | None) -> bool:
    if not target.is_file() or target.stat().st_size == 0:
        return False
    return expected_size is None or target.stat().st_size == expected_size


def fetch_web(items: list, log=sys.stderr) -> int:
    fetched = 0
    for index, (url, target, expected) in enumerate(items, 1):
        if is_cached(target, expected):
            continue
        time.sleep(PAUSE_SECONDS)
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(target.suffix + ".part")
        with urllib.request.urlopen(url, timeout=60) as response, open(temporary, "wb") as out:
            while chunk := response.read(1 << 16):
                out.write(chunk)
        if expected is not None and temporary.stat().st_size != expected:
            temporary.unlink()
            raise SystemExit(f"{url}: fick fel storlek, väntade {expected} byte")
        temporary.replace(target)
        fetched += 1
        print(f"  webb [{index:3d}/{len(items)}] {target.name}", file=log)
    return fetched


def fetch_ftp(items: list, config: dict, log=sys.stderr) -> int:
    pending = [(path, target, size) for path, target, size in items if not is_cached(target, size)]
    if not pending:
        return 0
    ftp_config = config["ftp"]
    ftp = ftplib.FTP(ftp_config["host"], timeout=ftp_config.get("timeoutSeconds", 60))
    ftp.login(ftp_config["user"], ftp_config["password"])
    ftp.set_pasv(True)
    fetched = 0
    try:
        for index, (path, target, size) in enumerate(pending, 1):
            time.sleep(PAUSE_SECONDS)
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary = target.with_suffix(target.suffix + ".part")
            with open(temporary, "wb") as out:
                ftp.retrbinary(f"RETR {path}", out.write, blocksize=1 << 18)
            if temporary.stat().st_size != size:
                temporary.unlink()
                raise SystemExit(f"{path}: fick fel storlek, väntade {size} byte")
            temporary.replace(target)
            fetched += 1
            print(f"  ftp  [{index:3d}/{len(pending)}] {target.name} ({size / 1e6:.1f} MB)", file=log)
    finally:
        try:
            ftp.quit()
        except ftplib.all_errors:
            pass
    return fetched


def summary(items: list) -> tuple[int, int]:
    present = [target for _, target, expected in items if is_cached(target, expected)]
    return len(present), sum(target.stat().st_size for target in present)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--offline", action="store_true", help="hämta ingenting, visa bara vad som finns i cachen")
    args = parser.parse_args(argv)

    config = ai.load_config()
    listing = ai.load_listing(ai.REPO_ROOT / config["rawListingPath"])
    with open(ai.REPO_ROOT / config["inventoryPath"], encoding="utf-8") as fh:
        inventory = json.load(fh)
    with open(ai.REPO_ROOT / config["httpsProbePath"], encoding="utf-8") as fh:
        probe = json.load(fh)
    web, ftp = plan(inventory, listing, probe)

    if not args.offline:
        fetched_web = fetch_web(web)
        fetched_ftp = fetch_ftp(ftp, config)
        print(f"Hämtade nu: {fetched_web} webbsidbilder, {fetched_ftp} filer från FTP Covers")

    web_count, web_bytes = summary(web)
    ftp_count, ftp_bytes = summary(ftp)
    print(f"Webbsidbild 1: {web_count} av {len(web)} filer, {web_bytes / 1e6:.0f} MB")
    print(f"FTP Covers:    {ftp_count} av {len(ftp)} filer, {ftp_bytes / 1e6:.0f} MB")
    print(f"Cache: {CACHE_DIR.relative_to(ai.REPO_ROOT)}")
    return 0 if (web_count, ftp_count) == (len(web), len(ftp)) else 1


if __name__ == "__main__":
    sys.exit(main())
