#!/usr/bin/env python3
"""DMZ Reader – manifestet search-index.json för en GitHub-release.

Appen hämtar manifestet från
  https://github.com/Rognils/dmz-reader/releases/latest/download/search-index.json
och själva sökindexet från den versionsspecifika adressen
  https://github.com/Rognils/dmz-reader/releases/download/<releaseTag>/<fileName>

Skriptet läser storlek, SHA-256 och versionerna ur en färdig .db-fil och skriver
manifestet. Filnamnet i manifestet är .db-filens namn, och filen ska laddas upp
till releasen under exakt det namnet, tillsammans med manifestet.

Ett sökindex utan privacyFilterVersion i meta är inte maskerat och vägras: ett
omaskerat sökindex laddas aldrig upp någonstans.

Kör:  python3 tools/make_search_index_manifest.py dist/search-2026-10-06-006-p3.db v1.0.0
      python3 tools/make_search_index_manifest.py FIL.db TAGG --output dist/search-index.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import sys
from pathlib import Path

MANIFEST_VERSION = 1
MANIFEST_NAME = "search-index.json"
# Lägsta versionCode som kan hämta sökindexet från GitHub: 1.0.0-rc4.
DEFAULT_MIN_APP_VERSION_CODE = 9904

SAFE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
REQUIRED_META = ("searchSchemaVersion", "inventoryDatasetVersion", "normalizerVersion", "privacyFilterVersion")


class ManifestError(Exception):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_meta(path: Path) -> dict[str, str]:
    try:
        db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        try:
            return {key: str(value) for key, value in db.execute("SELECT key, value FROM meta")}
        finally:
            db.close()
    except sqlite3.Error as error:
        raise ManifestError(f"{path} går inte att läsa som ett sökindex: {error}") from error


def build_manifest(path: Path, release_tag: str, min_app_version_code: int = DEFAULT_MIN_APP_VERSION_CODE) -> dict:
    if not SAFE_NAME.fullmatch(release_tag):
        raise ManifestError(f"Releasetaggen {release_tag!r} innehåller tecken som inte hör hemma i en adress.")
    if not SAFE_NAME.fullmatch(path.name) or not path.name.endswith(".db"):
        raise ManifestError(f"Filnamnet {path.name!r} ska sluta på .db och bara innehålla bokstäver, siffror, punkt, - och _.")
    if path.name == "search.db":
        raise ManifestError("search.db är det omaskerade arbetsindexet. Bara ett maskerat, namngivet index publiceras.")
    meta = read_meta(path)
    missing = [key for key in REQUIRED_META if not meta.get(key)]
    if "privacyFilterVersion" in missing:
        raise ManifestError(f"{path} saknar privacyFilterVersion: det är inte maskerat och får inte publiceras.")
    if missing:
        raise ManifestError(f"{path} saknar {', '.join(missing)} i meta.")
    return {
        "manifestVersion": MANIFEST_VERSION,
        "releaseTag": release_tag,
        "fileName": path.name,
        "size": path.stat().st_size,
        "sha256": sha256(path),
        "searchSchemaVersion": meta["searchSchemaVersion"],
        "inventoryDatasetVersion": meta["inventoryDatasetVersion"],
        "normalizerVersion": meta["normalizerVersion"],
        "privacyFilterVersion": meta["privacyFilterVersion"],
        "minAppVersionCode": min_app_version_code,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Skriver search-index.json för en GitHub-release.")
    parser.add_argument("database", type=Path, help="det färdiga, maskerade sökindexet (.db)")
    parser.add_argument("release_tag", help="releasens tagg, till exempel v1.0.0")
    parser.add_argument("--output", type=Path, help=f"var manifestet skrivs (standard: {MANIFEST_NAME} bredvid .db-filen)")
    parser.add_argument("--min-app-version-code", type=int, default=DEFAULT_MIN_APP_VERSION_CODE)
    args = parser.parse_args(argv)
    try:
        manifest = build_manifest(args.database, args.release_tag, args.min_app_version_code)
    except ManifestError as error:
        print(f"FEL: {error}", file=sys.stderr)
        return 1
    output = args.output or args.database.with_name(MANIFEST_NAME)
    output.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{output}: {manifest['fileName']} i {manifest['releaseTag']}, {manifest['size']} byte, SHA-256 {manifest['sha256']}")
    print(f"Ladda upp både {manifest['fileName']} och {output.name} till releasen {manifest['releaseTag']}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
