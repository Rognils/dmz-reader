#!/usr/bin/env python3
"""DMZ Reader – mätning och kontroll av maskeringen i ett sökindex.

  measure DB            räknar vad tools/privacy_filter.py hittar i varje textkolumn
  sample DB --out DIR   skriver stickprov för granskning: träffar per kategori, och
                        siffersträngar som liknar nummer men inte träffades
  verify DB             kontrollerar att en maskerad fil inte har något kvar

Stickproven innehåller personuppgifter ur tidningarna. De ska ligga utanför repot
och får aldrig committas; `sample` vägrar skriva i repot.

`verify` kontrollerar:
  1. meta har rätt privacyFilterVersion, och rawText är tom på varje rad
  2. filtret hittar ingenting i någon textkolumn (SQL)
  3. filtret hittar ingenting i filens läsbara teckenföljder, som verktyget strings ger dem
  4. varje träff när filtret körs på filens alla bytes är binärdata utanför textkolumnerna
  5. FTS-indexet stämmer med texten
En träff i punkt 4 är ett sammanträffande mellan bytes i FTS-indexets packade data. Den
godtas bara om teckenföljden finns i ett FTS-block och inte i någon textkolumn, och den
redovisas med läge i filen och omgivande bytes i hex, aldrig som text.

Kör:  python3 tools/privacy_audit.py verify dist/search-2026-10-06-006-p1.db
"""

from __future__ import annotations

import argparse
import json
import random
import re
import sqlite3
import sys
from pathlib import Path

import privacy_filter as pf

REPO_ROOT = Path(__file__).resolve().parent.parent
SEED = 20261010
PAGE_SIZE = 4096
# Siffersträngar med skiljetecken som kan vara nummer filtret inte tog.
NEAR_MISS = re.compile(r"(?<![0-9])[0-9][0-9 /()+·*-]{5,16}[0-9](?![0-9])")
# Det verktyget strings räknar som läsbart: minst fyra ASCII-tecken i följd som går att skriva ut.
# Svenska bokstäver och andra tecken utanför ASCII delar alltså raden, precis som i strings.
PRINTABLE = re.compile(rb"[\x20-\x7e]{4,}")


def open_readonly(path: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{path}?mode=ro", uri=True)


def text_columns(db: sqlite3.Connection) -> list[str]:
    """Alla kolumner i pages som har text. En ny kolumn ska inte kunna slinka förbi kontrollen."""
    columns = [row[1] for row in db.execute("PRAGMA table_info(pages)") if row[2].upper() == "TEXT"]
    return [column for column in columns if column != "issueId"]


def measure(path: Path) -> dict:
    db = open_readonly(path)
    result = {}
    for column in text_columns(db):
        counts = {kind: 0 for kind in pf.CATEGORIES}
        counts.update({f"{kind}Pages": 0 for kind in pf.CATEGORIES})
        counts.update({"pages": 0, "characters": 0})
        for (text,) in db.execute(f"SELECT {column} FROM pages"):
            found = pf.find(text)
            for kind, count in found.items():
                counts[kind] += count
                counts[f"{kind}Pages"] += 1 if count else 0
            counts["pages"] += 1 if any(found.values()) else 0
            counts["characters"] += len(text)
        result[column] = counts
    result["totalPages"] = db.execute("SELECT COUNT(*) FROM pages").fetchone()[0]
    db.close()
    return result


def sample(path: Path, out: Path, sizes: dict[str, int]) -> dict:
    """Stickprov per kategori ur searchText. En storlek på 0 betyder alla träffar."""
    out = out.resolve()
    if out == REPO_ROOT or REPO_ROOT in out.parents:
        raise SystemExit("Stickproven innehåller personuppgifter och får inte ligga i repot. Välj en katalog utanför.")
    db = open_readonly(path)
    found: dict[str, list[dict]] = {kind: [] for kind in (*pf.CATEGORIES, "near")}
    for issue_id, page, text in db.execute("SELECT issueId, pageNumber, searchText FROM pages ORDER BY id"):
        for kind, start, end in pf.all_spans(text):
            found[kind].append({"issueId": issue_id, "page": page, "match": text[start:end],
                                "before": text[max(0, start - 60):start], "after": text[end:end + 40]})
        masked = pf.mask(text)[0]
        for match in NEAR_MISS.finditer(masked):
            candidate = match.group()
            if sum(character.isdigit() for character in candidate) >= 6 and re.search(r"[ /·*-]", candidate):
                found["near"].append({"issueId": issue_id, "page": page, "match": candidate,
                                      "before": masked[max(0, match.start() - 60):match.start()],
                                      "after": masked[match.end():match.end() + 40]})
    db.close()
    generator = random.Random(SEED)
    out.mkdir(parents=True, exist_ok=True)
    result = {}
    for kind, items in found.items():
        wanted = sizes.get(kind, 0)
        chosen = items if wanted == 0 else generator.sample(items, min(wanted, len(items)))
        for item in chosen:
            item["verdict"] = ""  # fylls i vid granskningen
        (out / f"{kind}.json").write_text(json.dumps(chosen, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        result[kind] = {"population": len(items), "sample": len(chosen)}
    return result


def _in_fts_index_only(db: sqlite3.Connection, columns: list[str], needle: bytes) -> bool:
    """Sant om bytesföljden finns i ett FTS-block och inte i någon textkolumn.

    Blocken innehåller ordlistan och packade positioner, byggda av den maskerade texten. Ord delas
    vid @, bindestreck och mellanslag, så en adress eller ett nummer kan inte stå där som text.
    """
    in_index = db.execute("SELECT COUNT(*) FROM pages_fts_data WHERE instr(block, ?) > 0", (needle,)).fetchone()[0]
    in_text = sum(db.execute(f"SELECT COUNT(*) FROM pages WHERE instr(CAST({column} AS BLOB), ?) > 0",
                             (needle,)).fetchone()[0] for column in columns)
    return in_index > 0 and in_text == 0


def verify(path: Path, expected_version: int = pf.PRIVACY_FILTER_VERSION, notes: list[str] | None = None) -> list[str]:
    """Felen i en maskerad fil. En tom lista betyder att filen är ren. Upplysningar läggs i `notes`."""
    problems = []
    db = open_readonly(path)
    meta = dict(db.execute("SELECT key, value FROM meta"))
    if meta.get("privacyFilterVersion") != str(expected_version):
        problems.append(f"meta: privacyFilterVersion är {meta.get('privacyFilterVersion')!r}, väntat {expected_version}")
    columns = text_columns(db)
    if "rawText" in columns:
        filled = db.execute("SELECT COUNT(*) FROM pages WHERE rawText <> ''").fetchone()[0]
        if filled:
            problems.append(f"rawText: {filled} sidor har text, kolumnen ska vara tom")
    for column in columns:
        rows = hits = 0
        for (text,) in db.execute(f"SELECT {column} FROM pages"):
            count = sum(pf.find(text).values())
            if count:
                rows += 1
                hits += count
        if hits:
            problems.append(f"SQL: {column} har {hits} träffar på {rows} sidor")
    db.close()
    # FTS-indexet ska vara byggt av texten som står i tabellen. Kontrollen skriver inget, men kräver en skrivbar öppning.
    check = sqlite3.connect(path)
    try:
        check.execute("INSERT INTO pages_fts (pages_fts, rank) VALUES ('integrity-check', 1)")
    except sqlite3.DatabaseError as error:
        problems.append(f"FTS: indexet stämmer inte med texten ({error})")
    check.rollback()
    check.close()

    data = path.read_bytes()
    strings = "\n".join(match.group().decode("ascii") for match in PRINTABLE.finditer(data))
    in_strings = pf.all_spans(strings)
    # Råa bytes: varje byte som inte är giltig UTF-8 behålls som ett eget tecken, så att läget i filen går att räkna ut.
    raw = data.decode("utf-8", errors="surrogateescape")
    in_raw = pf.all_spans(raw)
    db = open_readonly(path)
    binary = 0
    for kind, start, end in in_raw:
        needle = raw[start:end].encode("utf-8", errors="surrogateescape")
        offset = len(raw[:start].encode("utf-8", errors="surrogateescape"))
        context = data[max(0, offset - 12):offset + len(needle) + 12].hex(" ")
        where = f"byte {offset} (sida {offset // PAGE_SIZE + 1} i filen, {len(needle)} byte)"
        if _in_fts_index_only(db, columns, needle):
            binary += 1
            if notes is not None:
                notes.append(f"råa bytes: binärdata i FTS-indexet vid {where}, hex: {context}")
        else:
            problems.append(f"råa bytes: träff utanför FTS-indexets binärdata vid {where}, hex: {context}")
    # En träff i strings som är samma binära sammanträffande räknas inte som text.
    text_hits = 0
    for kind, start, end in in_strings:
        if not _in_fts_index_only(db, columns, strings[start:end].encode("utf-8", errors="replace")):
            text_hits += 1
    db.close()
    if text_hits:
        problems.append(f"strings: {text_hits} träffar i filens läsbara text")
    if notes is not None:
        notes.append(f"strings: {len(in_strings)} träffar, varav {len(in_strings) - text_hits} är binärdata i FTS-indexet")
        notes.append(f"råa bytes: {len(in_raw)} träffar, varav {binary} är binärdata i FTS-indexet")
    return problems


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("measure", "verify"):
        commands.add_parser(name).add_argument("db", type=Path)
    sample_parser = commands.add_parser("sample")
    sample_parser.add_argument("db", type=Path)
    sample_parser.add_argument("--out", type=Path, required=True)
    sample_parser.add_argument("--phones", type=int, default=150)
    sample_parser.add_argument("--emails", type=int, default=60)
    sample_parser.add_argument("--giro", type=int, default=120)
    sample_parser.add_argument("--personal-numbers", type=int, default=0, help="0 betyder alla")
    sample_parser.add_argument("--near", type=int, default=200)
    arguments = parser.parse_args(argv)
    if arguments.command == "measure":
        print(json.dumps(measure(arguments.db), indent=2))
        return 0
    if arguments.command == "sample":
        sizes = {"phone": arguments.phones, "email": arguments.emails, "giro": arguments.giro,
                 "personalNumber": arguments.personal_numbers, "near": arguments.near}
        print(json.dumps(sample(arguments.db, arguments.out, sizes), indent=2))
        return 0
    notes: list[str] = []
    problems = verify(arguments.db, notes=notes)
    for note in notes:
        print(f"Upplysning: {note}")
    for problem in problems:
        print(f"FEL: {problem}")
    print("Filen är ren: ingen text som filtret känner igen finns kvar." if not problems
          else f"{len(problems)} fel. Filen får inte delas ut.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
