#!/usr/bin/env python3
"""DMZ Reader – bygger en liten test-search.db för FTS5-verifieringen i FAS 1.

Databasen har samma format som det riktiga sökindexet ska få (planens D2):
meta, pages (rawText och searchText), pages_fts (FTS5 över searchText) och
issues. Texten är påhittad svensk text med å, ä och ö.

Tabellen selftest innehåller sökfrågor med förväntade träffar. Appen kör dem
mot databasen på telefonen och jämför (ADR 0006). Skriptet kontrollerar samma
förväntningar mot datorns SQLite innan filen skrivs.

Utdatan är deterministisk: samma skript ger samma innehåll.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT = REPO_ROOT / "app" / "src" / "debug" / "assets" / "fts_test.db"
TOKENIZER = "unicode61"
SOFT_HYPHEN = "­"

# All text här är påhittad för testerna. Ingenting är hämtat ur tidningarna, och så ska det förbli:
# filen versionshanteras och följer med debug-bygget.
#
# (issueId, pageNumber, rawText). rawText efterliknar det pdftotext ger:
# radbrytningar, mjuka bindestreck vid avstavning och, på en sida, utspärrad text.
PAGES = [
    ("datormagazin-1988-07", 14,
     "Demoscenen växer\n\nDen svenska demoscenen har vuxit snabbt under året. "
     "Demogruppen Fairlight släppte i maj ett nytt demo som fick stor "
     f"uppmärk{SOFT_HYPHEN}\nsamhet på årets största träff i Göteborg. Fairlight har "
     "länge hört till de mest kända grupperna i Norden, och det nya demot "
     "visar varför."),
    ("datormagazin-1988-07", 15,
     "Intervju: så arbetar en demogrupp\n\nVi träffade två kodare och en "
     "musiker för att höra hur ett demo blir till. Det mesta görs på nätterna, "
     "säger de. Maskinkod, rasterbalkar och scrolltexter är grunden, men "
     "musiken är minst lika viktig. Gruppen Fairlight nämns ofta som förebild."),
    ("datormagazin-1988-07", 22,
     "Test: tre skrivare för hemmet\n\nÄr en matrisskrivare fortfarande det "
     "bästa köpet? Vi har provat tre modeller i olika prisklasser. Den "
     f"billi{SOFT_HYPHEN}\ngaste får godkänt för brev och listningar, men den som "
     "vill ha snygga utskrifter måste betala mer."),
    ("datormagazin-1995-14", 9,
     "Turbokort till Amiga 1200\n\nAmiga 1200 är en trevlig dator, men "
     "processorn börjar bli gammal. Med ett turbokort får din Amiga nytt liv. "
     "Vi har testat sex kort till Amiga, från det enklaste med 68030 till "
     "det snabbaste med 68060. Skillnaden märks direkt när Amiga startar."),
    ("datormagazin-1995-14", 26,
     "B r a  h ö g t a l a r e  g ö r  s t o r  s k i l l n a d  f ö r\n"
     "d e n  s o m  s p e l a r  o f t a .\n\n"
     "Vi har lyssnat på sex par högtalare i redaktionens källare under en "
     "vecka. Bästa högtalare i testet låter förvånansvärt bra."),
    ("datormagazin-1995-14", 27,
     "Så testade vi\n\nVarje par högtalare har fått spela samma musik och "
     "samma spel i ett vanligt arbetsrum. Vi har lyssnat efter bas, "
     f"dis{SOFT_HYPHEN}\nkant och hur ljudet håller ihop på hög volym. Priset "
     "avgör inte allt: det näst billigaste paret hörde till de bästa."),
    ("datormagazin-1995-14", 43,
     "Månadens läsardiskett\n\nTre små spel för regniga dagar, alla skrivna "
     "av läsare. Det första är ett pussel med fallande klossar. Min far får "
     "säkert svårt att sluta spela det andra, som går ut på att parkera en "
     "lastbil på en trång gård."),
    ("highscore-1993-17", 3,
     "Nyheter från spelvärlden\n\nHösten bjuder på flera stora titlar. Äventyrsspel "
     "är fortfarande populärast bland våra läsare, tätt följt av plattformsspel. "
     "Till Amiga kommer tre nya spel före jul, och ryktet säger att ett "
     "av dem blir årets bästa."),
]


def normalize(raw: str) -> str:
    """En förenklad version av den normalisering som FAS 8 ska göra.

    Tar bort mjuka bindestreck (med efterföljande radbrytning), sätter ihop
    utspärrade ord och slår ihop blanktecken. Här räcker det att sidan blir
    sökbar; den riktiga normaliseringen bygger på teckenpositioner.
    """
    text = raw.replace(SOFT_HYPHEN + "\n", "").replace(SOFT_HYPHEN, "")
    lines = []
    for line in text.split("\n"):
        tokens = line.split(" ")
        letters = [t for t in tokens if t]
        if letters and sum(len(t) == 1 for t in letters) / len(letters) > 0.7:
            # Utspärrad rad: dubbla mellanslag skiljer orden åt.
            line = " ".join("".join(word.split(" ")) for word in line.split("  "))
        lines.append(line)
    return " ".join(" ".join(lines).split())


# (namn, FTS5-fråga, nummerfilter, förväntade träffar som "issueId:sida",
#  bästa träff eller None, text som ska finnas i bästa träffens utdrag eller None)
SELFTEST = [
    ("Enkelt ord", "Fairlight", None,
     ["datormagazin-1988-07:14", "datormagazin-1988-07:15"],
     "datormagazin-1988-07:14", "[Fairlight]"),
    ("Rankning med bm25", "Amiga", None,
     ["datormagazin-1995-14:9", "highscore-1993-17:3"],
     "datormagazin-1995-14:9", "[Amiga]"),
    ("Ord med ö, även i utspärrad text", "högtalare", None,
     ["datormagazin-1995-14:26", "datormagazin-1995-14:27"],
     "datormagazin-1995-14:26", "[högtalare]"),
    ("Ord med å och ä", "förvånansvärt", None,
     ["datormagazin-1995-14:26"], "datormagazin-1995-14:26", "[förvånansvärt]"),
    ("Versal med Ä", "Äventyrsspel", None,
     ["highscore-1993-17:3"], "highscore-1993-17:3", "[Äventyrsspel]"),
    ("Ord som var avstavat med mjukt bindestreck", "uppmärksamhet", None,
     ["datormagazin-1988-07:14"], "datormagazin-1988-07:14", "[uppmärksamhet]"),
    ("Fras", '"största träff"', None,
     ["datormagazin-1988-07:14"], "datormagazin-1988-07:14", "[största träff]"),
    ("Prefix", "demo*", None,
     ["datormagazin-1988-07:14", "datormagazin-1988-07:15"], None, None),
    ("Två ord (OCH)", "Amiga turbokort", None,
     ["datormagazin-1995-14:9"], "datormagazin-1995-14:9", None),
    ("Sök i ett nummer", "Amiga", "highscore-1993-17",
     ["highscore-1993-17:3"], "highscore-1993-17:3", "[Amiga]"),
    ("Inga träffar", "Atari", None, [], None, None),
]


def build(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        path.unlink()
    db = sqlite3.connect(path)
    db.executescript(f"""
        PRAGMA page_size = 4096;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE issues (issueId TEXT PRIMARY KEY, pageCount INTEGER);
        CREATE TABLE pages (
            issueId TEXT NOT NULL,
            pageNumber INTEGER NOT NULL,
            rawText TEXT NOT NULL,
            searchText TEXT NOT NULL,
            PRIMARY KEY (issueId, pageNumber)
        );
        CREATE VIRTUAL TABLE pages_fts USING fts5(
            searchText, content='pages', content_rowid='rowid', tokenize='{TOKENIZER}'
        );
        CREATE TABLE selftest (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            query TEXT NOT NULL,
            issueFilter TEXT,
            expected TEXT NOT NULL
        );
    """)
    for issue_id, page_number, raw in PAGES:
        cursor = db.execute(
            "INSERT INTO pages (issueId, pageNumber, rawText, searchText) VALUES (?, ?, ?, ?)",
            (issue_id, page_number, raw, normalize(raw)))
        db.execute("INSERT INTO pages_fts (rowid, searchText) VALUES (?, ?)",
                   (cursor.lastrowid, normalize(raw)))
    issue_ids = sorted({issue_id for issue_id, _, _ in PAGES})
    # pageCount är numrets hela sidantal, som testdatabasen inte känner till.
    db.executemany("INSERT INTO issues (issueId, pageCount) VALUES (?, NULL)",
                   [(issue_id,) for issue_id in issue_ids])
    db.executemany("INSERT INTO meta (key, value) VALUES (?, ?)", sorted({
        "schemaVersion": "1",
        "datasetVersion": "fts-test-001",
        "generatedAt": "2026-10-06T00:00:00Z",
        "tokenizer": TOKENIZER,
        "issueCount": str(len(issue_ids)),
        "pageCount": str(len(PAGES)),
    }.items()))
    for index, (name, query, issue_filter, hits, top, snippet) in enumerate(SELFTEST, 1):
        expected = {"hits": hits, "top": top, "snippetContains": snippet}
        db.execute("INSERT INTO selftest (id, name, query, issueFilter, expected) VALUES (?, ?, ?, ?, ?)",
                   (index, name, query, issue_filter, json.dumps(expected, ensure_ascii=False, sort_keys=True)))
    db.execute("INSERT INTO pages_fts (pages_fts) VALUES ('optimize')")
    db.commit()
    db.execute("VACUUM")
    db.close()


def search(db: sqlite3.Connection, query: str, issue_filter: str | None) -> list[tuple]:
    """Samma fråga som appen ställer (SearchDb.search)."""
    sql = ("SELECT p.issueId, p.pageNumber, bm25(pages_fts) AS score, "
           "snippet(pages_fts, 0, '[', ']', '…', 12) "
           "FROM pages_fts JOIN pages p ON p.rowid = pages_fts.rowid "
           "WHERE pages_fts MATCH ? ")
    params: list = [query]
    if issue_filter is not None:
        sql += "AND p.issueId = ? "
        params.append(issue_filter)
    sql += "ORDER BY score, p.issueId, p.pageNumber LIMIT 50"
    return db.execute(sql, params).fetchall()


def verify(path: Path, verbose: bool = True) -> bool:
    """Kör självtestet mot datorns SQLite. Returnerar True om allt stämmer."""
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    ok = True
    for name, query, issue_filter, expected_text in db.execute(
            "SELECT name, query, issueFilter, expected FROM selftest ORDER BY id"):
        expected = json.loads(expected_text)
        rows = search(db, query, issue_filter)
        actual = [f"{issue_id}:{page}" for issue_id, page, _, _ in rows]
        problems = []
        if sorted(actual) != sorted(expected["hits"]):
            problems.append(f"träffar {actual}, väntat {expected['hits']}")
        if expected["top"] is not None and (not actual or actual[0] != expected["top"]):
            problems.append(f"bäst {actual[:1]}, väntat {expected['top']}")
        if expected["snippetContains"] and (not rows or expected["snippetContains"] not in rows[0][3]):
            problems.append(f"utdraget saknar {expected['snippetContains']}")
        ok = ok and not problems
        if verbose:
            status = "OK  " if not problems else "FEL "
            snippet = f"  {rows[0][3]!r}" if rows else ""
            print(f"  {status}{name}: {query} → {len(actual)} träffar{snippet}")
            for problem in problems:
                print(f"       {problem}")
    db.close()
    return ok


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)

    build(args.output)
    print(f"SQLite {sqlite3.sqlite_version} (datorn), tokenizer {TOKENIZER}")
    ok = verify(args.output)
    if not ok:
        args.output.unlink()
        print("Förväntningarna stämmer inte. Ingen fil skrevs.")
        return 1
    print(f"{args.output.relative_to(REPO_ROOT)}: {len(PAGES)} sidor, "
          f"{len(SELFTEST)} sökfrågor, {args.output.stat().st_size} byte")
    return 0


if __name__ == "__main__":
    sys.exit(main())
