#!/usr/bin/env python3
"""DMZ Reader – mätning och kontroll av maskeringen i ett sökindex.

  measure DB            räknar vad tools/privacy_filter.py hittar i varje textkolumn
  sample DB --out DIR   skriver stickprov för granskning: träffar per kategori, och
                        siffersträngar som liknar nummer men inte träffades
  verify DB             kontrollerar att en maskerad fil inte har något kvar

Stickproven innehåller personuppgifter ur tidningarna. De ska ligga utanför repot
och får aldrig committas; `sample` vägrar skriva i repot.

`verify` söker på tre sätt, och alla ska ge noll:
  1. filtret körs på varje rad i varje textkolumn (SQL)
  2. filtret körs på filens läsbara teckenföljder, som verktyget strings ger dem
  3. filtret körs på filens alla bytes, lästa som en enda text
En träff i 2 eller 3 som bara finns inne i FTS-indexets binära block är ett
sammanträffande mellan bytes och inte text. Den redovisas som upplysning.
Dessutom ska meta ha rätt privacyFilterVersion, och FTS-indexet ska stämma med texten.

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
TEXT_COLUMNS = ("searchText", "rawText")
SEED = 20261010
# Siffersträngar med skiljetecken som kan vara nummer filtret inte tog.
NEAR_MISS = re.compile(r"(?<![0-9])[0-9][0-9 /()+·*-]{5,16}[0-9](?![0-9])")
PRINTABLE = re.compile(rb"[\x20-\x7e\x80-\xff]{4,}")


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
        counts = {"email": 0, "phone": 0, "emailPages": 0, "phonePages": 0, "pages": 0, "characters": 0}
        for (text,) in db.execute(f"SELECT {column} FROM pages"):
            found = pf.find(text)
            counts["email"] += found["email"]
            counts["phone"] += found["phone"]
            counts["emailPages"] += 1 if found["email"] else 0
            counts["phonePages"] += 1 if found["phone"] else 0
            counts["pages"] += 1 if found["email"] or found["phone"] else 0
            counts["characters"] += len(text)
        result[column] = counts
    result["totalPages"] = db.execute("SELECT COUNT(*) FROM pages").fetchone()[0]
    db.close()
    return result


def _hits(text: str) -> list[tuple[str, int, int]]:
    """Träffarna i en text med lägen. Ett pass, som räcker för ett stickprov."""
    marked = [("email", start, end) for start, end in pf.email_spans(text)]
    marked += [("phone", start, end) for start, end in pf.phone_spans(text)]
    return marked


def sample(path: Path, out: Path, phones: int, emails: int, near: int) -> dict:
    out = out.resolve()
    if out == REPO_ROOT or REPO_ROOT in out.parents:
        raise SystemExit("Stickproven innehåller personuppgifter och får inte ligga i repot. Välj en katalog utanför.")
    db = open_readonly(path)
    found: dict[str, list[dict]] = {"phone": [], "email": [], "near": []}
    for issue_id, page, text in db.execute("SELECT issueId, pageNumber, searchText FROM pages ORDER BY id"):
        for kind, start, end in _hits(text):
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
    sizes = {}
    for kind, wanted in (("phone", phones), ("email", emails), ("near", near)):
        chosen = generator.sample(found[kind], min(wanted, len(found[kind])))
        for item in chosen:
            item["verdict"] = ""  # fylls i vid granskningen
        (out / f"{kind}.json").write_text(json.dumps(chosen, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        sizes[kind] = {"population": len(found[kind]), "sample": len(chosen)}
    return sizes


def verify(path: Path, expected_version: int = pf.PRIVACY_FILTER_VERSION, notes: list[str] | None = None) -> list[str]:
    """Felen i en maskerad fil. En tom lista betyder att filen är ren. Upplysningar läggs i `notes`."""
    problems = []
    db = open_readonly(path)
    meta = dict(db.execute("SELECT key, value FROM meta"))
    if meta.get("privacyFilterVersion") != str(expected_version):
        problems.append(f"meta: privacyFilterVersion är {meta.get('privacyFilterVersion')!r}, väntat {expected_version}")
    for column in text_columns(db):
        rows = hits = 0
        for (text,) in db.execute(f"SELECT {column} FROM pages"):
            found = pf.find(text)
            if found["email"] or found["phone"]:
                rows += 1
                hits += found["email"] + found["phone"]
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
    # Texten ligger som UTF-8 i filen. Bytes som inte är text blir ersättningstecken och stör inte.
    strings = "\n".join(match.group().decode("utf-8", errors="replace") for match in PRINTABLE.finditer(data))
    for name, text in (("strings", strings), ("råa bytes", data.decode("utf-8", errors="replace"))):
        hits = [text[start:end] for start, end in pf.email_spans(text) + pf.phone_spans(text)]
        real = [hit for hit in hits if not _only_in_fts_index(path, hit)]
        if real:
            problems.append(f"{name}: {len(real)} träffar i filen utanför textkolumnerna")
        if notes is not None and len(hits) > len(real):
            notes.append(f"{name}: {len(hits) - len(real)} sammanträffande i FTS-indexets binärdata, inte text")
    return problems


def _only_in_fts_index(path: Path, hit: str) -> bool:
    """Sant om teckenföljden bara finns inne i FTS-indexets binära block.

    Blocken innehåller ordlistan och packade positioner, byggda av den maskerade texten. Ord delas
    vid @, bindestreck och mellanslag, så en adress eller ett nummer kan inte stå där som text. Några
    bytes som råkar se ut som en adress är ett sammanträffande. Textkolumnerna är redan sökta med SQL.
    """
    needle = hit.encode("utf-8")
    db = open_readonly(path)
    try:
        in_index = db.execute("SELECT COUNT(*) FROM pages_fts_data WHERE instr(block, ?) > 0", (needle,)).fetchone()[0]
        columns = text_columns(db)
        in_text = sum(db.execute(f"SELECT COUNT(*) FROM pages WHERE instr({column}, ?) > 0", (hit,)).fetchone()[0]
                      for column in columns)
    finally:
        db.close()
    return in_index > 0 and in_text == 0


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
    sample_parser.add_argument("--near", type=int, default=200)
    arguments = parser.parse_args(argv)
    if arguments.command == "measure":
        print(json.dumps(measure(arguments.db), indent=2))
        return 0
    if arguments.command == "sample":
        print(json.dumps(sample(arguments.db, arguments.out, arguments.phones, arguments.emails, arguments.near), indent=2))
        return 0
    notes: list[str] = []
    problems = verify(arguments.db, notes=notes)
    for note in notes:
        print(f"Upplysning: {note}")
    for problem in problems:
        print(f"FEL: {problem}")
    print("Filen är ren: inga e-postadresser eller telefonnummer hittades." if not problems
          else f"{len(problems)} fel. Filen får inte delas ut.")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
