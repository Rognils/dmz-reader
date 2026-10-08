#!/usr/bin/env python3
"""DMZ Reader – mätningar för sökindexet (FAS 8A).

Underkommandon:
  compare   skriver utdrag ur pdftotext i standardläge, -layout och -raw för några sidor
  build     bygger search.db i båda tokenizer-varianterna, plus hjälpdatabaser för storleksmätning
  render    renderar ett utsnitt av en sida kring ett ord, för visuell kontroll av facit
  evaluate  kör facit (tools/testdata/search_golden.json) mot båda varianterna
  measure   storlek och byggtid, uppräknat till hela arkivet
  pages     jämför uppmätt sidantal med inventory
  plan      underlag för fullkörningen (8B)

Allt som skrivs hamnar i cache/, som inte versionshanteras. Resultatet av
mätningarna skrivs dessutom till data/search_pilot.json.
"""

from __future__ import annotations

import argparse
import gzip
import json
import lzma
import re
import shutil
import sqlite3
import subprocess
import sys
from collections import Counter
from pathlib import Path

import build_search_index as bsi
import fetch_pdfs

REPO_ROOT = bsi.REPO_ROOT
CACHE = REPO_ROOT / "cache"
SEARCH_DIR = CACHE / "search"
GOLDEN_PATH = REPO_ROOT / "tools" / "testdata" / "search_golden.json"
RESULT_PATH = REPO_ROOT / "data" / "search_pilot.json"
FULL_RESULT_PATH = REPO_ROOT / "data" / "search_index_report.json"
PILOT_BUILT_AT = "2026-10-08T00:00:00Z"
VARIANTS = (0, 2)

# Sista numret i generationen "v2" (Acrobat Paper Capture, 150 dpi). Senare nummer och
# HighScore är den äldre generationen (JPEG 2000, 300 dpi). Se ADR 0011.
LAST_V2_SORT_KEY = "1991-21"

COMPARE_PAGES = [
    ("datormagazin-1986-01", 6, "v2"),
    ("datormagazin-1993-03", 26, "äldre"),
    ("highscore-1993-17", 6, "HighScore"),
]
MODES = [("standard", []), ("-layout", ["-layout"]), ("-raw", ["-raw"])]


def generation(issue: dict) -> str:
    """Tre grupper för uppräkningen: Datormagazin v2, Datormagazin äldre och HighScore (äldre, tunnare)."""
    if issue["publicationId"] != "datormagazin":
        return "highscore"
    return "v2" if issue["sortKey"] <= LAST_V2_SORT_KEY + "~" else "äldre"


def variant_path(diacritics: int) -> Path:
    return SEARCH_DIR / f"search-rd{diacritics}.db"


def load_inventory() -> dict:
    return json.loads(bsi.INVENTORY_PATH.read_text(encoding="utf-8"))


def save_result(section: str, value, path: Path | None = None) -> None:
    path = path or RESULT_PATH
    result = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    result[section] = value
    path.write_text(json.dumps(result, indent=1, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")


# ---------------------------------------------------------------- compare

def compare(_arguments) -> int:
    target = CACHE / "compare"
    target.mkdir(parents=True, exist_ok=True)
    for issue_id, page, label in COMPARE_PAGES:
        pdf = bsi.CACHE_DIR / f"{issue_id}.pdf"
        subprocess.run(["pdftoppm", "-r", "80", "-f", str(page), "-l", str(page), "-png", str(pdf),
                        str(target / f"{issue_id}-p{page}")], check=True)
        for name, options in MODES:
            text = bsi.run_pdftotext(pdf, *options, "-f", str(page), "-l", str(page))
            (target / f"{issue_id}-p{page}{'-standard' if name == 'standard' else name}.txt").write_text(text, encoding="utf-8")
        print(f"{issue_id} sida {page} ({label}): utdrag och rendering i {target}")
    return 0


# ---------------------------------------------------------------- build

def build(_arguments) -> int:
    inventory = load_inventory()
    by_id = {issue["issueId"]: issue for issue in inventory["issues"]}
    timings = {}
    for diacritics in VARIANTS:
        result = bsi.build(fetch_pdfs.PILOT_ISSUES, variant_path(diacritics), diacritics=diacritics,
                           built_at=PILOT_BUILT_AT)
        timings[f"rd{diacritics}"] = result["secondsPerIssue"]
        print(f"{result['path']}: {result['pages']} sidor, {Path(result['path']).stat().st_size / 1e6:.2f} MB")
        if result["mismatchedPages"]:
            print("  text och lägen skiljer sig:", result["mismatchedPages"])
    # Hjälpdatabaser för storleksmätningen: en per generation och en per nummer, i båda varianterna.
    groups = {"v2": [], "äldre": [], "highscore": []}
    for issue_id in fetch_pdfs.PILOT_ISSUES:
        groups[generation(by_id[issue_id])].append(issue_id)
    for diacritics in VARIANTS:
        for name, issue_ids in groups.items():
            bsi.build(issue_ids, SEARCH_DIR / "parts" / f"gen-{name}-rd{diacritics}.db", diacritics=diacritics,
                      built_at=PILOT_BUILT_AT, log=lambda _: None)
        for issue_id in fetch_pdfs.PILOT_ISSUES:
            bsi.build([issue_id], SEARCH_DIR / "parts" / f"{issue_id}-rd{diacritics}.db", diacritics=diacritics,
                      built_at=PILOT_BUILT_AT, log=lambda _: None)
    bsi.build(fetch_pdfs.PILOT_ISSUES, SEARCH_DIR / "parts" / "all-rd0-noraw.db", diacritics=0,
              built_at=PILOT_BUILT_AT, store_raw=False, log=lambda _: None)
    save_result("buildSecondsPerIssue", timings)
    return 0


# ---------------------------------------------------------------- render

def find_word_boxes(pdf: Path, page: int, word: str, min_fragments: int = 1) -> list[tuple[float, float, float, float]]:
    """Var på sidan ordet står, även om det är utspärrat i flera bitar. Lägen i punkter.

    Med `min_fragments` över 1 tas bara de ställen med där ordet är delat i så många bitar.
    """
    words = bsi.parse_bbox(bsi.run_pdftotext(pdf, "-raw", "-bbox", "-f", str(page), "-l", str(page)))[0]
    target = re.sub(r"\W", "", word).casefold()
    cleaned = [re.sub(r"\W", "", item.text).casefold() for item in words]
    boxes = []
    for start in range(len(words)):
        combined = ""
        for end in range(start, min(start + 14, len(words))):
            combined += cleaned[end]
            if not target.startswith(combined) and target not in combined:
                break
            if target in combined:
                span = words[start:end + 1]
                if cleaned[start] and len(span) >= min_fragments and (len(span) == 1 or target.startswith(cleaned[start])):
                    boxes.append((min(w.x_min for w in span), min(w.y_min for w in span),
                                  max(w.x_max for w in span), max(w.y_max for w in span)))
                break
    return boxes


def render_crop(issue_id: str, page: int, word: str, index: int = 0, dpi: int = 110,
                min_fragments: int = 1) -> Path | None:
    pdf = bsi.CACHE_DIR / f"{issue_id}.pdf"
    boxes = find_word_boxes(pdf, page, word, min_fragments)
    if not boxes:
        return None
    x0, y0, x1, y1 = boxes[min(index, len(boxes) - 1)]
    scale = dpi / 72
    margin_x, margin_y = 150, 45
    x, y = max(0, int(x0 * scale) - margin_x), max(0, int(y0 * scale) - margin_y)
    width, height = int((x1 - x0) * scale) + 2 * margin_x, int((y1 - y0) * scale) + 2 * margin_y
    target = CACHE / "golden" / f"{issue_id}-p{page:03d}-{re.sub(r'[^0-9A-Za-zåäöÅÄÖ]+', '_', word)}"
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["pdftoppm", "-r", str(dpi), "-f", str(page), "-l", str(page), "-x", str(x), "-y", str(y),
                    "-W", str(width), "-H", str(height), "-png", "-singlefile", str(pdf), str(target)], check=True)
    return target.with_suffix(".png")


def render(arguments) -> int:
    path = render_crop(arguments.issue, arguments.page, arguments.word, arguments.index,
                       min_fragments=arguments.min_fragments)
    print(path if path else "ordet hittades inte på sidan")
    return 0 if path else 1


# ---------------------------------------------------------------- evaluate

def fts_query(query: str) -> str:
    """Varje ord inom citattecken, så att bindestreck och siffror inte tolkas som FTS5-syntax.

    Ett sökord med mellanslag eller bindestreck blir en fras ("Commodore 128", "CD-ROM").
    """
    return '"' + query.replace('"', '""') + '"'


def search(db: sqlite3.Connection, query: str) -> list[tuple[str, int, float]]:
    return db.execute(
        "SELECT p.issueId, p.pageNumber, bm25(pages_fts) AS score FROM pages_fts "
        "JOIN pages p ON p.id = pages_fts.rowid WHERE pages_fts MATCH ? "
        "ORDER BY score, p.issueId, p.pageNumber", (fts_query(query),)).fetchall()


def exact_pattern(query: str) -> re.Pattern:
    """Sökordet som det stavas, oavsett skiftläge. Skiljetecken i sökordet får vara vilka skiljetecken som helst."""
    parts = [re.escape(part) for part in re.split(r"[^\w]+", query) if part]
    return re.compile(r"(?<!\w)" + r"[^\w]+".join(parts) + r"(?!\w)", re.I)


def evaluate_variant(db_path: Path, golden: dict) -> list[dict]:
    db = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    texts = {(issue, page): text for issue, page, text in db.execute("SELECT issueId, pageNumber, searchText FROM pages")}
    rows = []
    for entry in golden["queries"]:
        hits = search(db, entry["query"])
        order = [(issue, page) for issue, page, _ in hits]
        pattern = exact_pattern(entry["query"])
        irrelevant = sum(1 for key in order if not pattern.search(texts[key]))
        expected = [(item["issueId"], item["pageNumber"]) for item in entry["expected"]]
        ranks = [order.index(key) + 1 if key in order else None for key in expected]
        rows.append({"query": entry["query"], "kind": entry["kind"], "hits": len(order), "irrelevant": irrelevant,
                     "expected": [f"{issue} s. {page}" for issue, page in expected],
                     "ranks": ranks, "found": all(rank is not None for rank in ranks)})
    db.close()
    return rows


def evaluate_one(db_path: Path) -> int:
    """Facit mot en enda databas, till exempel den fullständiga i dist/."""
    golden = json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))
    rows = evaluate_variant(db_path, golden)
    print(f"{'sökord':<24}{'typ':<13}{'träffar':>8}  {'plats':<8}{'irrelevanta':>11}")
    for row in rows:
        ranks = ",".join("–" if rank is None else str(rank) for rank in row["ranks"])
        print(f"{row['query']:<24}{row['kind']:<13}{row['hits']:>8}  {ranks:<8}{row['irrelevant']:>11}"
              f"{'' if row['found'] else '  MISS'}")
    found = sum(row["found"] for row in rows)
    print(f"{found} av {len(rows)} sökord hittar alla förväntade sidor, "
          f"{sum(row['irrelevant'] for row in rows)} irrelevanta träffar")
    save_result("golden", rows, FULL_RESULT_PATH)
    return 0 if found == len(rows) else 1


def evaluate(arguments) -> int:
    if arguments.db:
        return evaluate_one(arguments.db)
    golden = json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))
    results = {f"rd{d}": evaluate_variant(variant_path(d), golden) for d in VARIANTS}
    print(f"{'sökord':<22}{'typ':<12}{'rd0: träffar/fel/rang':<26}{'rd2: träffar/fel/rang':<26}")
    for a, b in zip(results["rd0"], results["rd2"]):
        def cell(row):
            ranks = ",".join("–" if rank is None else str(rank) for rank in row["ranks"])
            return f"{row['hits']}/{row['irrelevant']}/{ranks}{'' if row['found'] else '  MISS'}"
        print(f"{a['query']:<22}{a['kind']:<12}{cell(a):<26}{cell(b):<26}")
    for name, rows in results.items():
        found = sum(row["found"] for row in rows)
        print(f"{name}: {found} av {len(rows)} sökord hittar alla förväntade sidor, "
              f"{sum(row['irrelevant'] for row in rows)} irrelevanta träffar totalt")
    save_result("golden", results)
    return 0 if all(row["found"] for rows in results.values() for row in rows) else 1


# ---------------------------------------------------------------- measure

def table_sizes(db_path: Path) -> dict:
    db = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    sizes = Counter()
    for name, size in db.execute("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name"):
        if name.startswith("pages_fts"):
            sizes["fts"] += size
        elif name == "pages" or name.startswith("sqlite_autoindex_pages"):
            sizes["pages"] += size
        else:
            sizes["other"] += size
    raw, search_text, pages = db.execute(
        "SELECT SUM(LENGTH(CAST(rawText AS BLOB))), SUM(LENGTH(CAST(searchText AS BLOB))), COUNT(*) FROM pages").fetchone()
    db.close()
    data = db_path.read_bytes()
    return {"file": len(data), "pagesTable": sizes["pages"], "ftsIndex": sizes["fts"], "other": sizes["other"],
            "rawTextBytes": raw, "searchTextBytes": search_text, "pages": pages,
            "gzip": len(gzip.compress(data, 9)), "xz": len(lzma.compress(data, preset=6))}


def measure(arguments) -> int:
    if arguments.db:
        sizes = table_sizes(arguments.db)
        print(json.dumps(sizes, indent=1))
        save_result("sizes" if not arguments.label else f"sizes-{arguments.label}", sizes, FULL_RESULT_PATH)
        return 0
    inventory = load_inventory()
    by_id = {issue["issueId"]: issue for issue in inventory["issues"]}
    archive = Counter(generation(issue) for issue in inventory["issues"] if issue.get("ocrPdfUrl"))
    pilot = Counter(generation(by_id[issue_id]) for issue_id in fetch_pdfs.PILOT_ISSUES)
    result = {"archiveIssues": dict(archive), "pilotIssues": dict(pilot), "variants": {}, "perIssue": {}}
    mb = 1e6
    for diacritics in VARIANTS:
        key = f"rd{diacritics}"
        whole = table_sizes(variant_path(diacritics))
        generations = {name: table_sizes(SEARCH_DIR / "parts" / f"gen-{name}-rd{diacritics}.db") for name in archive}
        estimate = {field: sum(generations[name][field] / pilot[name] * archive[name] for name in archive)
                    for field in ("file", "pagesTable", "ftsIndex", "rawTextBytes", "searchTextBytes", "gzip", "xz", "pages")}
        result["variants"][key] = {"pilot": whole, "generations": generations, "estimate185": estimate}
        print(f"\n{bsi.tokenizer_spec(diacritics)}")
        print(f"  pilot ({whole['pages']} sidor): rawText {whole['rawTextBytes'] / mb:.2f} MB, searchText "
              f"{whole['searchTextBytes'] / mb:.2f} MB, tabellen pages {whole['pagesTable'] / mb:.2f} MB, FTS-index "
              f"{whole['ftsIndex'] / mb:.2f} MB, search.db {whole['file'] / mb:.2f} MB, gzip {whole['gzip'] / mb:.2f} MB, "
              f"xz {whole['xz'] / mb:.2f} MB")
        for name in archive:
            g = generations[name]
            print(f"  {name}: {pilot[name]} nummer i piloten, {g['file'] / mb / pilot[name]:.2f} MB per nummer "
                  f"({g['pages'] / pilot[name]:.0f} sidor) → {archive[name]} nummer i arkivet: "
                  f"{g['file'] / pilot[name] * archive[name] / mb:.0f} MB")
        print(f"  uppräknat till {sum(archive.values())} nummer: search.db {estimate['file'] / mb:.0f} MB "
              f"(pages {estimate['pagesTable'] / mb:.0f}, FTS {estimate['ftsIndex'] / mb:.0f}), "
              f"gzip {estimate['gzip'] / mb:.0f} MB, xz {estimate['xz'] / mb:.0f} MB, cirka {estimate['pages']:.0f} sidor")
    for issue_id in fetch_pdfs.PILOT_ISSUES:
        result["perIssue"][issue_id] = {
            "generation": generation(by_id[issue_id]),
            **{f"rd{d}": table_sizes(SEARCH_DIR / "parts" / f"{issue_id}-rd{d}.db") for d in VARIANTS}}
    print("\nper nummer (egen databas per nummer, remove_diacritics 0 / 2):")
    for issue_id, item in result["perIssue"].items():
        a, b = item["rd0"], item["rd2"]
        print(f"  {issue_id:<26}{item['generation']:<7}{a['pages']:>3} s.  rå {a['rawTextBytes'] / 1e3:>5.0f} kB  "
              f"pages {a['pagesTable'] / 1e3:>5.0f} kB  FTS {a['ftsIndex'] / 1e3:>4.0f}/{b['ftsIndex'] / 1e3:<4.0f} kB  "
              f"db {a['file'] / 1e3:>5.0f}/{b['file'] / 1e3:<5.0f} kB  gzip {a['gzip'] / 1e3:>4.0f} kB")
    noraw = table_sizes(SEARCH_DIR / "parts" / "all-rd0-noraw.db")
    result["withoutRawText"] = noraw
    print(f"\nutan rawText (rd0): search.db {noraw['file'] / mb:.2f} MB, gzip {noraw['gzip'] / mb:.2f} MB")
    save_result("sizes", result)
    return 0


# ---------------------------------------------------------------- pages

def pages(_arguments) -> int:
    inventory = load_inventory()
    by_id = {issue["issueId"]: issue for issue in inventory["issues"]}
    rows = []
    for issue_id in fetch_pdfs.PILOT_ISSUES:
        measured = bsi.page_count(bsi.CACHE_DIR / f"{issue_id}.pdf")
        known, scans = by_id[issue_id].get("pageCount"), by_id[issue_id].get("scanPageCount")
        status = "saknas i inventory, ska föras in" if known is None else ("stämmer" if known == measured else "AVVIKER")
        rows.append({"issueId": issue_id, "measured": measured, "inventory": known, "scanPageCount": scans, "status": status})
        print(f"  {issue_id:<26} uppmätt {measured:>3}  inventory {str(known):>4}  skannade sidor {str(scans):>4}  {status}"
              + ("" if scans in (None, measured) else "  (skiljer sig från skanningarna)"))
    save_result("pageCounts", rows)
    return 0


# ---------------------------------------------------------------- plan

def plan(_arguments) -> int:
    inventory = load_inventory()
    manifest = fetch_pdfs.load_manifest(bsi.CACHE_DIR)
    remaining = [issue for issue in inventory["issues"]
                 if issue.get("ocrPdfUrl") and issue["issueId"] not in manifest["files"]]
    remaining.sort(key=lambda issue: issue["ocrPdfSize"])
    total = sum(issue["ocrPdfSize"] for issue in remaining)
    cached = sum(entry["size"] for entry in manifest["files"].values())
    free = shutil.disk_usage(REPO_ROOT).free
    seconds = total / fetch_pdfs.MAX_BYTES_PER_SECOND + len(remaining) * fetch_pdfs.PAUSE_BETWEEN_FILES
    result = {
        "freeDiskBytes": free, "cachedBytes": cached, "cachedFiles": len(manifest["files"]),
        "remainingFiles": len(remaining), "remainingBytes": total,
        "estimatedSeconds": round(seconds), "maxBytesPerSecond": fetch_pdfs.MAX_BYTES_PER_SECOND,
        "withoutPdf": [issue["issueId"] for issue in inventory["issues"] if not issue.get("ocrPdfUrl")],
        "order": [issue["issueId"] for issue in remaining],
    }
    print(f"ledigt på disken: {free / 1e9:.0f} GB")
    print(f"i cachen: {len(manifest['files'])} filer, {cached / 1e9:.2f} GB")
    print(f"kvar att hämta: {len(remaining)} filer, {total / 1e9:.2f} GB, cirka {seconds / 60:.0f} min vid "
          f"{fetch_pdfs.MAX_BYTES_PER_SECOND / 1e6:.0f} MB/s och {fetch_pdfs.PAUSE_BETWEEN_FILES:.0f} s paus")
    print("minst:", ", ".join(f"{i['issueId']} ({i['ocrPdfSize'] / 1e6:.0f} MB)" for i in remaining[:3]))
    print("störst:", ", ".join(f"{i['issueId']} ({i['ocrPdfSize'] / 1e6:.0f} MB)" for i in remaining[-3:]))
    print("utan PDF:", result["withoutPdf"])
    save_result("plan8b", result)
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    for name, function in (("compare", compare), ("build", build), ("evaluate", evaluate), ("measure", measure),
                           ("pages", pages), ("plan", plan)):
        command = commands.add_parser(name)
        command.set_defaults(function=function)
        if name in ("evaluate", "measure"):
            command.add_argument("--db", type=Path, help="en enda databas, till exempel dist/search.db")
            command.add_argument("--label", help="namn på mätningen i data/search_index_report.json")
    render_parser = commands.add_parser("render")
    render_parser.add_argument("issue")
    render_parser.add_argument("page", type=int)
    render_parser.add_argument("word")
    render_parser.add_argument("--index", type=int, default=0)
    render_parser.add_argument("--min-fragments", type=int, default=1)
    render_parser.set_defaults(function=render)
    arguments = parser.parse_args(argv)
    return arguments.function(arguments)


if __name__ == "__main__":
    sys.exit(main())
