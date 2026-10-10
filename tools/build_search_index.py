#!/usr/bin/env python3
"""DMZ Reader – bygger search.db ur den lokala PDF-cachen (FAS 8).

För varje nummer som finns i cache/pdf/ och stämmer med inventory:

  1. `pdftotext -raw` ger sidans text i den ordning OCR-programmet skrev den.
     Den sparas orörd som `rawText`. Sidorna skiljs åt med \\f, och
     `pageNumber` är PDF-sidindex med start på 1 (plan C7).
  2. `pdftotext -raw -bbox` ger samma tecken i samma ordning, men med varje
     ordbits läge på sidan. Av lägena byggs `searchText`:
       - utspärrad text sätts ihop ("h ö g ta la re" → "högtalare")
       - mjuka bindestreck (U+00AD) tas bort
       - avstavning vid radbrytning sätts ihop ("data-" + "program")
       - blanksteg normaliseras till ett mellanslag
     Riktiga bindestreck (CD-ROM, C-64) behålls.
  3. Sidorna läggs i SQLite med ett FTS5-index över `searchText` (plan D2).

Normaliseringen läser bara det aktuella numret och den frysta ordlistan
tools/data/normalizer_lexicon_v{LEXICON_VERSION}.txt. Samma PDF och samma
NORMALIZER_VERSION ger därför samma searchText, oavsett vilka andra nummer som
byggs. Ändras en regel eller ordlistan ska NORMALIZER_VERSION höjas.

Bygget är reproducerbart: samma PDF:er och samma `--built-at` ger en
identisk fil.

Med `--privacy-filter` maskeras e-postadresser, telefonnummer, postgironummer och
personnummerformer i searchText innan texten läggs i databasen (tools/privacy_filter.py),
rawText lämnas tom, och `privacyFilterVersion` skrivs i meta. Appen läser inte rawText;
kolumnen finns kvar så att schemat är detsamma. Det är den varianten som delas ut. Indexet
byggs alltid från PDF:erna: en färdig databas maskeras aldrig i efterhand,
eftersom den gamla texten då kan ligga kvar i filens lediga sidor.

Kör:  python3 tools/build_search_index.py --pilot --diacritics 0 -o cache/search/search-rd0.db
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sqlite3
import subprocess
import sys
import time
import unicodedata
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Container, Iterable, NamedTuple

import fetch_pdfs
import privacy_filter

REPO_ROOT = Path(__file__).resolve().parent.parent
INVENTORY_PATH = REPO_ROOT / "data" / "archive_inventory.json"
CACHE_DIR = REPO_ROOT / "cache" / "pdf"

GENERATOR = "build_search_index.py 0.8.1"
SEARCH_SCHEMA_VERSION = 1
# Höjs när en regel i normaliseringen eller ordlistan ändras, eftersom searchText då blir en annan.
#   1: första frysta versionen
#   2: bindestreck vid radslut i versaler och efter korta förled; tal och hela ord klistras inte ihop
NORMALIZER_VERSION = 2
LEXICON_VERSION = 1
LEXICON_PATH = REPO_ROOT / "tools" / "data" / f"normalizer_lexicon_v{LEXICON_VERSION}.txt"
PDFTOTEXT_MODE = "-raw"
SOFT_HYPHEN = "­"

# Avstånd mellan två ordbitar mäts i andelar av radens höjd.
JOIN_FRACTION = 0.5     # en lucka är "inne i ett ord" om den är mindre än hälften av radens största lucka
MAX_JOIN_GAP = 0.3      # ... och mindre än så här
MAX_WORD_GAP = 1.5      # större luckor är spaltmellanrum eller tabeller och räknas inte som ordmellanrum
SHORT_FRAGMENT = 2      # en bit med högst så här många tecken talar för utspärrad text
MIN_VOCABULARY_COUNT = 2  # används bara när ordlistan byggs (tools/build_lexicon.py)

# Korta ord som står för sig själva i vanlig text. De saknas i ordlistan, som bara har ord
# med minst tre tecken, men ska ändå räknas som hela ord.
SHORT_WORDS = frozenset("""
    i å ö av en er du vi ni de är på om så se ge nu ju ut in få gå må då ta ha bo be le tv pc cd st kr nr
    mb kb hz ex dr jo ja nä ur åt ty an ej sa to at of is it on or we he me my no so up by be do go if as
    """.split())

SENTENCE_END = ".,:;!?"
KEEP_HYPHEN_BEFORE = {"och", "eller", "samt", "resp", "respektive"}


class Word(NamedTuple):
    """En ordbit ur `pdftotext -bbox`, med läge i punkter."""
    x_min: float
    y_min: float
    x_max: float
    y_max: float
    text: str


# ---------------------------------------------------------------- textutvinning

def run_pdftotext(pdf: Path, *options: str) -> str:
    result = subprocess.run(["pdftotext", *options, str(pdf), "-"], capture_output=True, check=True)
    return result.stdout.decode("utf-8", errors="replace")


def split_pages(text: str) -> list[str]:
    """Delar pdftotext-utdata på \\f. Efter sista sidan står ett avslutande \\f."""
    pages = text.split("\f")
    if pages and pages[-1].strip() == "":
        pages.pop()
    return pages


_PAGE = re.compile(r"<page [^>]*>(.*?)</page>", re.S)
_WORD = re.compile(r'<word xMin="([-\d.]+)" yMin="([-\d.]+)" xMax="([-\d.]+)" yMax="([-\d.]+)">(.*?)</word>', re.S)


def parse_bbox(document: str) -> list[list[Word]]:
    """Ordbitarna per sida ur `pdftotext -bbox`."""
    return [
        [Word(float(a), float(b), float(c), float(d), html.unescape(text))
         for a, b, c, d, text in _WORD.findall(page)]
        for page in _PAGE.findall(document)
    ]


def page_count(pdf: Path) -> int:
    output = subprocess.run(["pdfinfo", str(pdf)], capture_output=True, check=True).stdout.decode("utf-8", "replace")
    return int(re.search(r"^Pages:\s+(\d+)", output, re.M).group(1))


# ---------------------------------------------------------------- normalisering

def group_lines(words: Iterable[Word]) -> list[list[Word]]:
    """Delar en sidas ordbitar i rader.

    Bitarna kommer i läsordning. En bit hör till samma rad som den föregående
    om de överlappar i höjdled och den nya ligger till höger.
    """
    lines: list[list[Word]] = []
    for word in words:
        if lines:
            previous = lines[-1][-1]
            height = min(previous.y_max - previous.y_min, word.y_max - word.y_min)
            overlap = min(previous.y_max, word.y_max) - max(previous.y_min, word.y_min)
            if height > 0 and overlap > 0.5 * height and word.x_min >= previous.x_max - 0.3 * height:
                lines[-1].append(word)
                continue
        lines.append([word])
    return lines


def bare(token: str) -> str:
    """Ordet utan skiljetecken i kanterna, med små bokstäver. Används mot ordlistan."""
    return token.strip("\"'”“’‘«».,:;!?()[]{}-–—/").lower()


def _can_join(left: str, right: str) -> bool:
    if not left or not right:
        return False
    if left[-1] in SENTENCE_END and right[0].isalnum():
        return False  # nytt ord efter skiljetecken, även om luckan är liten
    return (left[-1].isalnum() or left[-1] in SENTENCE_END) and (right[0].isalnum() or right[0] in SENTENCE_END + "-")


@dataclass
class Run:
    """Ordbitar på en rad som ligger så tätt att de kan vara ett utspärrat ord."""
    fragments: list[str]
    uniform: bool = False  # hela raden har jämna, små luckor; inget riktigt ordmellanrum att jämföra med

    @property
    def joined(self) -> str:
        return "".join(self.fragments)

    def is_strong(self) -> bool:
        """Tre bitar eller fler, de flesta med ett eller två tecken: kan inte vara vanlig text."""
        short = sum(1 for fragment in self.fragments if len(fragment) <= SHORT_FRAGMENT)
        return len(self.fragments) >= 3 and short >= 0.6 * len(self.fragments)


def split_line(line: list[Word]) -> list[str | Run]:
    """Delar en rad i vanliga ord och i följder av bitar som kan vara ett utspärrat ord."""
    texts = [word.text.replace(SOFT_HYPHEN, "") if index < len(line) - 1 else word.text
             for index, word in enumerate(line)]
    if len(line) < 2:
        return list(texts)
    heights = sorted(word.y_max - word.y_min for word in line)
    height = heights[len(heights) // 2] or 1.0
    gaps = [(line[i + 1].x_min - line[i].x_max) / height for i in range(len(line) - 1)]
    word_gaps = [gap for gap in gaps if 0 < gap <= MAX_WORD_GAP]
    if not word_gaps:
        return list(texts)
    reference = max(word_gaps)

    if reference < MAX_JOIN_GAP and len(line) >= 3 and all(len(text) <= SHORT_FRAGMENT for text in texts) \
            and all(_can_join(texts[i], texts[i + 1]) for i in range(len(texts) - 1)):
        return [Run(list(texts), uniform=True)]

    parts: list[str | Run] = []
    current = [texts[0]]
    for index, gap in enumerate(gaps):
        tight = gap < JOIN_FRACTION * reference and gap < MAX_JOIN_GAP
        if tight and _can_join(texts[index], texts[index + 1]):
            current.append(texts[index + 1])
            continue
        parts.append(current[0] if len(current) == 1 else Run(current))
        current = [texts[index + 1]]
    parts.append(current[0] if len(current) == 1 else Run(current))
    return parts


def load_lexicon(path: Path = LEXICON_PATH) -> frozenset[str]:
    """Den frysta ordlistan: en term per rad, små bokstäver."""
    return frozenset(line for line in path.read_text(encoding="utf-8").split("\n") if line)


def _is_word(fragment: str, lexicon: Container[str]) -> bool:
    word = bare(fragment)
    return word in SHORT_WORDS or (len(word) >= 3 and word in lexicon)


def _is_number(fragment: str) -> bool:
    return any(char.isdigit() for char in fragment) and not any(char.isalpha() for char in fragment)


def resolve_run(run: Run, lexicon: Container[str]) -> list[str]:
    """Sätter ihop bitarna om det är säkert nog, annars lämnas de som de är.

    Tätt satt vanlig text får aldrig klistras ihop. Därför:
      - en följd där varje bit redan är ett helt ord eller ett tal lämnas orörd
        ("I den", "Last Ninja", "Tips 1")
      - annars sätts den ihop om resultatet är ett känt ord ("m ycket", "a t t")
      - ett okänt resultat godtas bara om följden är tydligt utspärrad, inte innehåller
        något helt ord och inte blandar tal med bokstäver ("a n v ä n d n in g s rå d",
        men inte "4 av Bour-")
    """
    words = [fragment for fragment in run.fragments if bare(fragment)]
    whole = [_is_word(fragment, lexicon) for fragment in words]
    if words and any(whole) and all(is_whole or _is_number(fragment) for is_whole, fragment in zip(whole, words)):
        return list(run.fragments)  # hela ord, eventuellt med tal emellan ("Tips 1", "1 Mb")
    joined = bare(run.joined)
    if joined in SHORT_WORDS or joined in lexicon:
        return [run.joined]
    has_long_word = any(len(bare(fragment)) >= 3 and _is_word(fragment, lexicon) for fragment in words)
    mixes_numbers = any(_is_number(fragment) for fragment in words) and \
        any(fragment[0].isalpha() for fragment in words)
    if run.is_strong() and not run.uniform and not has_long_word and not mixes_numbers:
        return [run.joined]
    return list(run.fragments)


def _join_line_break(last: str, first: str, lexicon: Container[str]) -> str | None:
    """Sätter ihop ett ord som delats vid radbrytning. None betyder att orden inte hör ihop."""
    if last.endswith(SOFT_HYPHEN):
        return last[:-1] + first
    if not last.endswith("-") or len(last) < 2 or not last[-2].isalnum() or not first or not first[0].isalnum():
        return None
    left = last[:-1]
    if bare(first) in KEEP_HYPHEN_BEFORE:
        return None  # "hård- och mjukvara"
    left_core = left.strip("\"'”“’‘«»([{")
    joined, hyphenated = bare(left + first), bare(last + first)
    if not left_core or left_core[-1].isdigit() or first[0].isdigit():
        return last + first  # C-64, Amiga-500, Formel 1-stjärnan
    if len(left_core) == 1:
        return last + first  # en ensam bokstav är inget avstavat led: x-minne, C-kod
    if left_core.isupper() and len(left_core) <= 2:
        return last + first  # kort förkortning: CD-ROM, PC-spel
    if hyphenated in lexicon and joined not in lexicon:
        return last + first  # ordet skrivs med bindestreck på andra ställen i arkivet
    if joined in lexicon:
        return left + first
    if left_core.isupper():
        if first[0].islower():
            return last + first  # förkortning med svenskt efterled: SCSI-kort, RAM-minne
        if _is_word(left_core, lexicon) and _is_word(first, lexicon):
            return last + first  # två egna ord i versaler: SUPERCARS-THE
        return left + first  # avstavat ord i versal text: AGG-RAVAIN, CELI-CA
    if not first[0].islower():
        return last + first  # Commodore-Knappen
    if len(left_core) <= 2 and _is_word(first, lexicon):
        return last + first  # x-minne, up-spel
    return left + first


def page_parts(words: Iterable[Word]) -> list[list[str | Run]]:
    return [split_line(line) for line in group_lines(words)]


def assemble(lines: list[list[str | Run]], lexicon: Container[str], changes: list | None = None) -> str:
    """Bygger sidans searchText av raderna.

    Om `changes` ges fylls den med (slag, före, efter) för varje ändring, för granskning.
    """
    tokens: list[str] = []
    for line in lines:
        resolved: list[str] = []
        for part in line:
            if isinstance(part, Run):
                result = resolve_run(part, lexicon)
                if changes is not None and len(result) == 1:
                    changes.append(("utspärrat", " ".join(part.fragments), result[0]))
                resolved.extend(result)
            else:
                resolved.append(part)
        resolved = [token for token in resolved if token]
        if not resolved:
            continue
        if tokens:
            joined = _join_line_break(tokens[-1], resolved[0], lexicon)
            if joined is not None:
                if changes is not None:
                    kind = "mjukt bindestreck" if tokens[-1].endswith(SOFT_HYPHEN) else \
                        ("bindestreck behållet" if joined == tokens[-1] + resolved[0] else "avstavning")
                    changes.append((kind, f"{tokens[-1]} / {resolved[0]}", joined))
                tokens[-1] = joined
                resolved = resolved[1:]
        tokens.extend(resolved)
    text = " ".join(token.replace(SOFT_HYPHEN, "") for token in tokens)
    return unicodedata.normalize("NFC", " ".join(text.split()))


def count_vocabulary(pages: Iterable[list[list[str | Run]]], vocabulary: Counter | None = None) -> Counter:
    """Ordlista över ord som stod hela i texten, utan ihopsättning.

    Hyphenerade ord räknas både hela ("cd-rom") och utan skiljetecken i kanterna.
    """
    vocabulary = Counter() if vocabulary is None else vocabulary
    for lines in pages:
        for line in lines:
            for part in line[:-1] if line and isinstance(line[-1], str) and line[-1].endswith(("-", SOFT_HYPHEN)) else line:
                if isinstance(part, str):
                    word = bare(part)
                    if len(word) >= 3:
                        vocabulary[word] += 1
    return vocabulary


def normalize_page(words: Iterable[Word], lexicon: Container[str] = frozenset()) -> str:
    """searchText för en sida. Ordlistan avgör tveksamma fall; utan den sätts bara säkra fall ihop."""
    return assemble(page_parts(words), lexicon)


# ---------------------------------------------------------------- nummer och databas

@dataclass
class ExtractedIssue:
    issue_id: str
    raw_pages: list[str]
    parts: list[list[list[str | Run]]]
    page_count: int
    seconds: float
    mismatched_pages: list[int]


def extract_issue(issue_id: str, pdf: Path) -> ExtractedIssue:
    started = time.monotonic()
    raw_pages = split_pages(run_pdftotext(pdf, PDFTOTEXT_MODE))
    word_pages = parse_bbox(run_pdftotext(pdf, PDFTOTEXT_MODE, "-bbox"))
    pages = page_count(pdf)
    if not (len(raw_pages) == len(word_pages) == pages):
        raise ValueError(f"{issue_id}: sidantalen går isär (text {len(raw_pages)}, lägen {len(word_pages)}, "
                         f"pdfinfo {pages})")
    # Samma tecken i samma ordning i båda utdata, annars går searchText inte att lita på.
    mismatched = [number for number, (raw, words) in enumerate(zip(raw_pages, word_pages), 1)
                  if "".join(raw.split()) != "".join("".join(word.text.split()) for word in words)]
    parts = [page_parts(words) for words in word_pages]
    return ExtractedIssue(issue_id, raw_pages, parts, pages, time.monotonic() - started, mismatched)


SCHEMA = """
PRAGMA page_size = 4096;
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE issues (
    issueId TEXT PRIMARY KEY,
    pageCount INTEGER NOT NULL,
    ocrPdfSize INTEGER,
    ocrPdfEtag TEXT,
    ocrPdfSha256 TEXT
) WITHOUT ROWID;
CREATE TABLE pages (
    id INTEGER PRIMARY KEY,
    issueId TEXT NOT NULL,
    pageNumber INTEGER NOT NULL,
    rawText TEXT NOT NULL,
    searchText TEXT NOT NULL,
    UNIQUE (issueId, pageNumber)
);
"""


def tokenizer_spec(diacritics: int) -> str:
    return f"unicode61 remove_diacritics {diacritics}"


def build_database(path: Path, issues: list[tuple[dict, dict, ExtractedIssue]], *, diacritics: int,
                   dataset_version: str, built_at: str, lexicon: Container[str],
                   store_raw: bool = True, privacy: bool = False) -> dict:
    """Skriver search.db. `issues` är (inventorypost, manifestpost, utvunnen text) i den ordning de ska in.

    Med `privacy` maskeras kontaktuppgifter i searchText före skrivningen, och rawText lämnas tom.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    path.unlink(missing_ok=True)
    tokenizer = tokenizer_spec(diacritics)
    db = sqlite3.connect(path)
    db.executescript(SCHEMA)
    db.execute(f"""CREATE VIRTUAL TABLE pages_fts USING fts5(
        searchText, content='pages', content_rowid='id', tokenize='{tokenizer}')""")
    seconds: dict[str, float] = {}
    total_pages = 0
    masked = dict.fromkeys(privacy_filter.CATEGORIES, 0)
    masked_pages = dict.fromkeys(privacy_filter.CATEGORIES, 0)
    masked_any_pages = 0
    for issue, file, extracted in sorted(issues, key=lambda item: item[0]["issueId"]):
        started = time.monotonic()
        db.execute("INSERT INTO issues (issueId, pageCount, ocrPdfSize, ocrPdfEtag, ocrPdfSha256) "
                   "VALUES (?, ?, ?, ?, ?)",
                   (extracted.issue_id, extracted.page_count, file["size"], file["etag"], file.get("sha256")))
        for number, (raw, parts) in enumerate(zip(extracted.raw_pages, extracted.parts), 1):
            search_text = assemble(parts, lexicon)
            if not store_raw:
                raw = ""
            if privacy:
                # Den omaskerade texten skrivs aldrig till filen: rawText är tom och searchText maskerad.
                raw = ""
                search_text, found = privacy_filter.mask(search_text)
                for kind, count in found.items():
                    masked[kind] += count
                    masked_pages[kind] += 1 if count else 0
                masked_any_pages += 1 if any(found.values()) else 0
            cursor = db.execute(
                "INSERT INTO pages (issueId, pageNumber, rawText, searchText) VALUES (?, ?, ?, ?)",
                (extracted.issue_id, number, raw, search_text))
            db.execute("INSERT INTO pages_fts (rowid, searchText) VALUES (?, ?)", (cursor.lastrowid, search_text))
        total_pages += extracted.page_count
        seconds[extracted.issue_id] = extracted.seconds + time.monotonic() - started
    meta = {
        "searchSchemaVersion": str(SEARCH_SCHEMA_VERSION),
        "inventoryDatasetVersion": dataset_version,
        "normalizerVersion": str(NORMALIZER_VERSION),
        "lexiconVersion": str(LEXICON_VERSION),
        "tokenizer": tokenizer,
        "builtAt": built_at,
        "generator": GENERATOR,
        "sqliteVersion": sqlite3.sqlite_version,
        "pdftotextMode": PDFTOTEXT_MODE,
        "issueCount": str(len(issues)),
        "pageCount": str(total_pages),
    }
    if privacy:
        meta["privacyFilterVersion"] = str(privacy_filter.PRIVACY_FILTER_VERSION)
    db.executemany("INSERT INTO meta (key, value) VALUES (?, ?)", sorted(meta.items()))
    db.execute("INSERT INTO pages_fts (pages_fts) VALUES ('optimize')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    return {"path": str(path), "tokenizer": tokenizer, "issues": len(issues), "pages": total_pages,
            "secondsPerIssue": seconds, "masked": masked if privacy else None,
            "maskedPages": masked_pages if privacy else None,
            "maskedAnyPages": masked_any_pages if privacy else None}


def usable_issues(wanted: list[str], inventory: dict, manifest: dict, cache_dir: Path, log=print) -> list[tuple[dict, dict, Path]]:
    """De nummer som finns i cachen och stämmer med inventory. Övriga rapporteras och hoppas över."""
    by_id = {issue["issueId"]: issue for issue in inventory["issues"]}
    usable = []
    for issue_id in wanted:
        issue, file, pdf = by_id.get(issue_id), manifest["files"].get(issue_id), cache_dir / f"{issue_id}.pdf"
        if issue is None:
            log(f"  {issue_id}: finns inte i inventory, hoppas över")
        elif file is None or not pdf.exists():
            log(f"  {issue_id}: finns inte i PDF-cachen, hoppas över")
        elif file.get("problems") or pdf.stat().st_size != file["size"]:
            log(f"  {issue_id}: filen stämmer inte med inventory, hoppas över")
        else:
            usable.append((issue, file, pdf))
    return usable


def build(wanted: list[str], output: Path, *, diacritics: int, built_at: str, cache_dir: Path = CACHE_DIR,
          inventory_path: Path = INVENTORY_PATH, store_raw: bool = True, privacy: bool = False,
          log=print) -> dict:
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    manifest = fetch_pdfs.load_manifest(cache_dir)
    extracted = []
    for issue, file, pdf in usable_issues(wanted, inventory, manifest, cache_dir, log):
        item = extract_issue(issue["issueId"], pdf)
        if item.mismatched_pages:
            log(f"  {item.issue_id}: text och lägen skiljer sig på sidorna {item.mismatched_pages}")
        extracted.append((issue, file, item))
    result = build_database(output, extracted, diacritics=diacritics, dataset_version=inventory["datasetVersion"],
                            built_at=built_at, lexicon=load_lexicon(), store_raw=store_raw, privacy=privacy)
    result["pageCounts"] = {item.issue_id: item.page_count for _, _, item in extracted}
    result["mismatchedPages"] = {item.issue_id: item.mismatched_pages for _, _, item in extracted
                                 if item.mismatched_pages}
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("issues", nargs="*", help="issueId att ta med")
    parser.add_argument("--pilot", action="store_true", help="urvalet för FAS 8A")
    parser.add_argument("--all", action="store_true", help="alla nummer i inventory som har en PDF")
    parser.add_argument("--inventory", type=Path, default=INVENTORY_PATH)
    parser.add_argument("--diacritics", type=int, choices=(0, 1, 2), default=0,
                        help="värdet på remove_diacritics i tokenizern unicode61")
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--built-at", help="tidsstämpel i meta (standard: nu). Ange för ett reproducerbart bygge.")
    parser.add_argument("--without-raw-text", action="store_true", help="lämna rawText tom (för storleksmätning)")
    parser.add_argument("--privacy-filter", action="store_true",
                        help="maskera kontaktuppgifter i searchText och lämna rawText tom (varianten som delas ut)")
    arguments = parser.parse_args(argv)
    wanted = list(arguments.issues) + (fetch_pdfs.PILOT_ISSUES if arguments.pilot else [])
    if arguments.all:
        inventory = json.loads(arguments.inventory.read_text(encoding="utf-8"))
        wanted = [issue["issueId"] for issue in inventory["issues"] if issue.get("ocrPdfUrl")]
    if not wanted:
        parser.error("ange issueId, --pilot eller --all")
    built_at = arguments.built_at or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    result = build(wanted, arguments.output, diacritics=arguments.diacritics, built_at=built_at,
                   inventory_path=arguments.inventory, store_raw=not arguments.without_raw_text,
                   privacy=arguments.privacy_filter)
    if len(result["pageCounts"]) != len(wanted):
        print(f"VARNING: {len(wanted) - len(result['pageCounts'])} nummer hoppades över", file=sys.stderr)
    print(f"{result['path']}: {result['issues']} nummer, {result['pages']} sidor, {result['tokenizer']}, "
          f"{Path(result['path']).stat().st_size / 1e6:.2f} MB")
    if result["masked"] is not None:
        names = {"email": "e-postadresser", "phone": "telefonnummer", "giro": "postgironummer",
                 "personalNumber": "personnummerformer"}
        print(f"maskerat i searchText (privacyFilterVersion {privacy_filter.PRIVACY_FILTER_VERSION}), "
              f"{result['maskedAnyPages']} sidor berörda: "
              + ", ".join(f"{result['masked'][kind]} {names[kind]} på {result['maskedPages'][kind]} sidor"
                          for kind in privacy_filter.CATEGORIES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
