#!/usr/bin/env python3
"""DMZ Reader – arkivinventering (FAS 0).

Steg 1: lista arkivets FTP-kataloger och spara rålistningen i
data/raw_listing.json. Rålistningen är också cachen: med --offline
rörs inte nätet alls.

Steg 2: tolka varje filnamn enligt namnreglerna nedan och koppla det
till ett nummer (issueId). Det som inte matchar en regel gissas aldrig,
utan listas som specialfall.

Steg 3: skriv data/archive_inventory.json. Läs-PDF och omslag pekar på
webbplatsen (ADR 0008); uppgifterna om dem kommer från
data/https_probe.json, som tools/https_probe.py tar fram.

Servern belastas försiktigt: en anslutning, passivt läge och en paus
på minst 0,5 sekunder mellan varje kataloganrop.
"""

from __future__ import annotations

import argparse
import ftplib
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

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
# Namnregler
# --------------------------------------------------------------------------
#
# Arkivets egen nyckel för ett nummer är ÅÅÅÅNN (198906), ÅÅÅÅNNMM för
# dubbelnummer (19881617) och ÅÅÅÅNNb för en b-utgåva (198710b).
# HighScore-bilagan har samma nyckel som det Datormagazin-nummer den hör till.
#
# Sidfiler (skanningar och omslag) heter DMZ_ÅÅÅÅ-NN-SSS.jpg. Avvikelser som
# faktiskt förekommer och därför har egna regler:
#   - sidnummer med två siffror i stället för tre (26 skanningskataloger)
#   - .png i stället för .jpg (1986 nr 4)
#   - delen "Spelbilaga" med egen sidräkning (1986 nr 4)
#   - dubbelnummer vars filer bara bär det första numret (1987 nr 4–5)

PUBLICATIONS = {
    "datormagazin": "Datormagazin",
    "highscore": "HighScore",
}

# Suffix som faktiskt förekommer i arkivet. Andra bokstäver gissas inte.
KNOWN_SUFFIXES = {"", "b"}

_KEY = r"(?P<year>\d{4})(?P<num>\d{2}|\d{4})(?P<suffix>[a-z]?)"
_FILE_KEY = r"(?P<year>\d{4})-(?P<num>\d{2}|\d{4})(?P<suffix>[a-z]?)"

RE_OCR_PDF = re.compile(rf"^(?P<hs>HighScore-)?{_KEY}\.pdf$")
RE_LARGE_PDF = re.compile(rf"^{_KEY}(?P<hs>-HighScore)?-Original\.pdf$")
RE_SCAN_DIR = re.compile(rf"^{_KEY}(?P<hs>-HighScore)?-Original$")
RE_PAGE_DMZ = re.compile(
    rf"^DMZ_{_FILE_KEY}(?:-(?P<part>Spelbilaga))?-(?P<page>\d{{2,3}})\.(?P<ext>jpg|png)$")
RE_PAGE_HS = re.compile(
    r"^HighScore_(?P<year>\d{4})-(?P<num>\d{2})(?P<suffix>)(?P<part>)_(?P<page>\d{3})\.(?P<ext>jpg)$")

# Delar av ett nummer som skannats med egen sidräkning.
MAIN_PART = ""


def _identity(match: re.Match, publication_id: str) -> dict | None:
    """Gör om en regex-träff till en nummeridentitet, eller None om den inte håller."""
    year = int(match["year"])
    num = match["num"]
    suffix = match["suffix"]
    if suffix not in KNOWN_SUFFIXES:
        return None
    first = int(num[:2])
    second = int(num[2:]) if len(num) == 4 else None
    if first < 1:
        return None
    # Ett dubbelnummer ska bestå av två nummer i följd. Annars är det något
    # annat än ett dubbelnummer, och då gissar vi inte.
    if second is not None and second != first + 1:
        return None
    return {
        "publicationId": publication_id,
        "year": year,
        "number": first,
        "numberEnd": second,
        "suffix": suffix,
    }


def make_issue_id(identity: dict) -> str:
    """Föreslaget issueId (ADR 0007): <publikation>-<år>-<nr>[-<nr2>][b]."""
    issue_id = f"{identity['publicationId']}-{identity['year']:04d}-{identity['number']:02d}"
    if identity["numberEnd"] is not None:
        issue_id += f"-{identity['numberEnd']:02d}"
    return issue_id + identity["suffix"]


def make_source_key(identity: dict) -> str:
    """Arkivets egen nyckel, så som den står i OCR PDF och på webbplatsen."""
    key = f"{identity['year']:04d}{identity['number']:02d}"
    if identity["numberEnd"] is not None:
        key += f"{identity['numberEnd']:02d}"
    key += identity["suffix"]
    return f"HighScore-{key}" if identity["publicationId"] == "highscore" else key


def make_issue_label(identity: dict) -> str:
    label = f"Nr {identity['number']}"
    if identity["numberEnd"] is not None:
        label += f"–{identity['numberEnd']}"
    return label + identity["suffix"]


def make_sort_key(identity: dict) -> str:
    return f"{identity['year']:04d}-{identity['number']:02d}{identity['suffix']}"


def _page_file(name: str) -> dict | None:
    """Tolka ett sidfilnamn (skanning eller omslag)."""
    match = RE_PAGE_DMZ.match(name)
    publication_id = "datormagazin"
    if not match:
        match = RE_PAGE_HS.match(name)
        publication_id = "highscore"
    if not match:
        return None
    identity = _identity(match, publication_id)
    if identity is None:
        return None
    return {
        "identity": identity,
        "page": int(match["page"]),
        "pageDigits": len(match["page"]),
        "part": match["part"] or MAIN_PART,
        "ext": match["ext"],
    }


def _is_first_half_of(identity: dict, double: dict) -> bool:
    """Sant om identity är första numret i dubbelnumret double."""
    return (double["numberEnd"] is not None
            and identity["numberEnd"] is None
            and all(identity[k] == double[k] for k in ("publicationId", "year", "number", "suffix")))


def classify(path: str) -> dict:
    """Tolka en arkivsökväg.

    Returnerar alltid en dict med "kind". För en fil som följer en namnregel
    finns också "identity" och "issueId". Annars är kind "special" och
    "reason" förklarar varför.
    """
    parts = path.split("/")
    root, name = parts[0], parts[-1]

    def matched(kind: str, identity: dict, **extra) -> dict:
        return {"kind": kind, "identity": identity, "issueId": make_issue_id(identity), **extra}

    def special(reason: str) -> dict:
        return {"kind": "special", "reason": reason}

    if root in ("OCR PDF", "OCR PDF v2") and len(parts) == 2:
        match = RE_OCR_PDF.match(name)
        identity = match and _identity(match, "highscore" if match["hs"] else "datormagazin")
        if not identity:
            return special("filnamnet följer inte mönstret [HighScore-]ÅÅÅÅNN[MM][b].pdf")
        return matched("ocr_pdf" if root == "OCR PDF" else "ocr_pdf_v2", identity)

    if root == "Large PDF" and len(parts) == 2:
        match = RE_LARGE_PDF.match(name)
        identity = match and _identity(match, "highscore" if match["hs"] else "datormagazin")
        if not identity:
            return special("filnamnet följer inte mönstret ÅÅÅÅNN[MM][b][-HighScore]-Original.pdf")
        return matched("large_pdf", identity)

    if root == "Covers" and len(parts) == 2:
        parsed = _page_file(name)
        if not parsed or parsed["part"] or parsed["ext"] != "jpg" or parsed["pageDigits"] != 3:
            return special("filnamnet följer inte mönstret DMZ_ÅÅÅÅ-NN[MM][b]-001.jpg "
                           "eller HighScore_ÅÅÅÅ-NN_001.jpg")
        if parsed["page"] != 1:
            return special(f"omslagsfil med sidnummer {parsed['page']}, förväntat 001")
        return matched("cover", parsed["identity"])

    if root == "Original Scans" and len(parts) == 3:
        dir_match = RE_SCAN_DIR.match(parts[1])
        dir_identity = dir_match and _identity(
            dir_match, "highscore" if dir_match["hs"] else "datormagazin")
        if not dir_identity:
            return special("katalognamnet följer inte mönstret ÅÅÅÅNN[MM][b][-HighScore]-Original")
        parsed = _page_file(name)
        if not parsed:
            return special("filnamnet följer inte mönstret DMZ_ÅÅÅÅ-NN[MM][b][-Spelbilaga]-SS[S].jpg|png "
                           "eller HighScore_ÅÅÅÅ-NN_SSS.jpg")
        identity = parsed["identity"]
        named_after_first = _is_first_half_of(identity, dir_identity)
        if identity != dir_identity and not named_after_first:
            return special(f"filen hör enligt namnet till {make_issue_id(identity)} "
                           f"men ligger i katalogen för {make_issue_id(dir_identity)}")
        if parsed["page"] < 1:
            return special("sidnummer 0")
        return matched("scan_page", dir_identity, page=parsed["page"], part=parsed["part"],
                       pageDigits=parsed["pageDigits"], ext=parsed["ext"],
                       namedAfterFirstNumber=named_after_first)

    return special("oväntad plats i katalogstrukturen")


def analyze(listing: dict, roots: list[str], extra_roots: list[str] = ()) -> dict:
    """Koppla varje fil i rötterna till ett issueId eller lista den som specialfall.

    Resultat:
      issues        nummer med sina filer, i stabil ordning
      specialCases  filer (eller kataloger) som inte följer någon regel
      resolved      avvikelser som en uttrycklig regel har löst – redovisas, göms inte
      outsideRoots  filer utanför de analyserade katalogerna
    """
    issues: dict[str, dict] = {}
    special_cases: list[dict] = []
    resolved: list[dict] = []
    outside: list[dict] = []
    wanted = set(roots) | set(extra_roots)

    def issue_for(result: dict) -> dict:
        return issues.setdefault(result["issueId"], {
            "issueId": result["issueId"],
            "identity": result["identity"],
            "files": {},
            "scanPages": {},
        })

    for path, size, modify in iter_files(listing):
        root = path.split("/")[0]
        if "/" not in path or root not in wanted:
            outside.append({"path": path, "size": size})
            continue
        result = classify(path)
        if result["kind"] == "special":
            special_cases.append({"path": path, "size": size, "reason": result["reason"]})
            continue
        issue = issue_for(result)
        if result["kind"] == "scan_page":
            key = (result["part"], result["page"])
            if key in issue["scanPages"]:
                special_cases.append({"path": path, "size": size,
                                      "reason": f"dubblett av skannad sida {result['page']}"})
                continue
            issue["scanPages"][key] = {
                "path": path, "size": size, "pageDigits": result["pageDigits"],
                "ext": result["ext"], "namedAfterFirstNumber": result["namedAfterFirstNumber"],
            }
        elif result["kind"] in issue["files"]:
            special_cases.append({"path": path, "size": size,
                                  "reason": f"en andra {result['kind']}-fil för {result['issueId']}"})
        else:
            issue["files"][result["kind"]] = {"path": path, "size": size, "modify": modify}

    # Ett omslag som bara bär första numret i ett dubbelnummer (DMZ_1987-04-001.jpg
    # för 1987 nr 4–5) hör till dubbelnumret. Regeln gäller bara när numret inte
    # finns som eget nummer, och varje tillämpning redovisas.
    for issue_id in list(issues):
        issue = issues[issue_id]
        if set(issue["files"]) != {"cover"} or issue["scanPages"]:
            continue
        doubles = [other for other in issues.values()
                   if _is_first_half_of(issue["identity"], other["identity"])]
        if len(doubles) != 1:
            continue
        target = doubles[0]
        cover = issue["files"]["cover"]
        if "cover" in target["files"]:
            special_cases.append({"path": cover["path"], "size": cover["size"],
                                  "reason": f"en andra cover-fil för {target['issueId']}"})
        else:
            target["files"]["cover"] = cover
            resolved.append({"path": cover["path"], "issueId": target["issueId"],
                             "rule": "omslaget bär bara dubbelnumrets första nummer"})
        del issues[issue_id]

    for issue in issues.values():
        pages = issue["scanPages"]
        if not pages:
            continue
        directory = next(iter(pages.values()))["path"].rsplit("/", 1)[0]
        if any(p["namedAfterFirstNumber"] for p in pages.values()):
            resolved.append({"path": directory, "issueId": issue["issueId"],
                             "rule": "skanningarna bär bara dubbelnumrets första nummer"})
        if {p["pageDigits"] for p in pages.values()} == {2}:
            resolved.append({"path": directory, "issueId": issue["issueId"],
                             "rule": "sidnummer med två siffror"})
        if {p["ext"] for p in pages.values()} != {"jpg"}:
            resolved.append({"path": directory, "issueId": issue["issueId"],
                             "rule": "skanningar i annat format än jpg: "
                                     + ", ".join(sorted({p["ext"] for p in pages.values()}))})
        parts = sorted({part for part, _ in pages})
        if parts != [MAIN_PART]:
            resolved.append({"path": directory, "issueId": issue["issueId"],
                             "rule": "skanningarna är uppdelade i delar med egen sidräkning: "
                                     + ", ".join(p or "huvuddel" for p in parts)})
        # Blandade format eller sidluckor är fel som ingen regel täcker.
        if len({(p["pageDigits"], p["ext"]) for p in pages.values()}) > 1:
            special_cases.append({"path": directory, "size": None,
                                  "reason": "blandade filformat eller sidnummerbredder i samma katalog"})
        for part in parts:
            numbers = sorted(page for prt, page in pages if prt == part)
            if numbers != list(range(1, len(numbers) + 1)):
                missing = sorted(set(range(1, numbers[-1] + 1)) - set(numbers))
                special_cases.append({
                    "path": directory, "size": None,
                    "reason": f"sidnumren är inte 1..N utan lucka ({part or 'huvuddel'}); saknas: {missing}",
                })

    # Ett "nummer" som bara består av ett omslag eller bara av skanningar är misstänkt.
    for issue in issues.values():
        if not {"ocr_pdf", "ocr_pdf_v2", "large_pdf"} & set(issue["files"]):
            special_cases.append({"path": issue["issueId"], "size": None,
                                  "reason": "numret har varken OCR-PDF eller Large PDF"})

    return {
        "issues": dict(sorted(issues.items(), key=lambda kv: (
            kv[1]["identity"]["publicationId"], make_sort_key(kv[1]["identity"])))),
        "specialCases": sorted(special_cases, key=lambda c: c["path"]),
        "resolved": sorted(resolved, key=lambda c: (c["path"], c["rule"])),
        "outsideRoots": sorted(outside, key=lambda c: c["path"]),
    }


def _count(values) -> dict:
    counts: dict = {}
    for value in values:
        counts[value] = counts.get(value, 0) + 1
    return counts


def scan_page_count(issue: dict) -> int:
    return len(issue["scanPages"])


# --------------------------------------------------------------------------
# Inventory
# --------------------------------------------------------------------------

WEB_BASE_URL = "https://dmzarkivet.se"
FTP_BASE_URL = "ftp://dmzarkivet.se"
SCHEMA_VERSION = 1

# Webbplatsens sidbilder ligger under olika prefix per publikation.
WEB_PAGE_PREFIX = {"datormagazin": "magazines", "highscore": "highscore"}


def pdf_url_path(identity: dict) -> str:
    """Läs-PDF:en på webbplatsen (den kanoniska OCR-PDF:en)."""
    return f"/pdf/{make_source_key(identity)}.pdf"


def page_url_path(issue: dict, page: int) -> str:
    """Sidbild på webbplatsen för sida `page` i numrets huvuddel.

    Webbplatsen använder samma filnamn som Original Scans (även när de har
    tvåsiffriga sidnummer eller bara dubbelnumrets första nummer), men alltid
    med ändelsen .jpg.
    """
    identity = issue["identity"]
    key = make_source_key(identity).removeprefix("HighScore-")
    scan = issue["scanPages"][(MAIN_PART, page)]
    name = scan["path"].rsplit("/", 1)[1].rsplit(".", 1)[0] + ".jpg"
    return f"/{WEB_PAGE_PREFIX[identity['publicationId']]}/{key}/{name}"


def ftp_url(path: str) -> str:
    return f"{FTP_BASE_URL}/{quote(path)}"


def scan_page_pattern(issue: dict) -> str | None:
    """Mönster för huvuddelens skanningar, med {page:0Nd} för sidnumret."""
    first = issue["scanPages"].get((MAIN_PART, 1))
    if first is None:
        return None
    directory, name = first["path"].rsplit("/", 1)
    stem, ext = name.rsplit(".", 1)
    digits = first["pageDigits"]
    pattern = f"{stem[:-digits]}{{page:0{digits}d}}.{ext}"
    return f"{FTP_BASE_URL}/{quote(directory)}/{pattern}"


def related_issue_id(identity: dict) -> str | None:
    """HighScore hör till Datormagazin-numret med samma år och nummer."""
    if identity["publicationId"] != "highscore":
        return None
    return make_issue_id({**identity, "publicationId": "datormagazin"})


def build_inventory(listing: dict, analysis: dict, probe: dict, measurements: dict) -> dict:
    """Bygg inventory enligt planens D1. Utdatan beror bara på indatafilerna."""
    results = probe["results"]
    issues_out = []
    problems = []

    for issue_id, issue in analysis["issues"].items():
        identity = issue["identity"]
        files = issue["files"]
        pages = issue["scanPages"]
        flags = set()

        pdf = results.get(pdf_url_path(identity))
        if pdf is None:
            raise SystemExit(f"{issue_id}: saknas i https_probe.json. Kör tools/https_probe.py.")
        ocr_url = ocr_size = ocr_version = ftp_generation = None
        if pdf["status"] == 200:
            ocr_url = WEB_BASE_URL + pdf_url_path(identity)
            ocr_size = int(pdf["content-length"])
            ocr_version = {"etag": pdf.get("etag"), "lastModified": pdf.get("last-modified")}
            # Vilken FTP-generation webbfilen motsvarar avgörs av storleken.
            for kind, name in (("ocr_pdf_v2", "OCR PDF v2"), ("ocr_pdf", "OCR PDF")):
                if kind in files and files[kind]["size"] == ocr_size:
                    ftp_generation = name
            if ftp_generation is None:
                flags.add("ocr_pdf_differs_from_ftp")
                problems.append(f"{issue_id}: webbens PDF ({ocr_size} byte) motsvarar ingen FTP-fil")
        else:
            flags.add("missing_ocr_pdf")
            problems.append(f"{issue_id}: ingen PDF på webbplatsen (status {pdf['status']})")

        cover_url = None
        if (MAIN_PART, 1) in pages:
            cover = results.get(page_url_path(issue, 1))
            if cover is not None and cover["status"] == 200:
                cover_url = WEB_BASE_URL + page_url_path(issue, 1)
        if cover_url is None:
            flags.add("missing_cover")

        if identity["numberEnd"] is not None:
            flags.add("double_issue")
        if identity["suffix"] == "b":
            flags.add("variant_b")
        if identity["publicationId"] == "highscore":
            flags.add("supplement")
        if "cover" not in files:
            flags.add("no_ftp_cover")
        if pages:
            if {p["pageDigits"] for p in pages.values()} == {2}:
                flags.add("scan_two_digit_pages")
            if {p["ext"] for p in pages.values()} != {"jpg"}:
                flags.add("scan_png")
            if {part for part, _ in pages} != {MAIN_PART}:
                flags.add("scan_parts")
            if any(p["namedAfterFirstNumber"] for p in pages.values()):
                flags.add("scan_named_after_first_number")

        source_names = [files[kind]["path"]
                        for kind in ("ocr_pdf", "ocr_pdf_v2", "large_pdf", "cover") if kind in files]
        if pages:
            source_names.append(next(iter(pages.values()))["path"].rsplit("/", 1)[0] + "/")

        page_count = checksum = None
        measured = measurements.get(issue_id)
        if measured is not None:
            if measured["ocrPdfSize"] == ocr_size:
                page_count = measured["pageCount"]
                checksum = measured["ocrPdfChecksum"]
                flags |= set(measured.get("flags", []))
            else:
                problems.append(f"{issue_id}: stickprovets mätvärden gäller en annan fil och används inte")

        large = files.get("large_pdf")
        issues_out.append({
            "issueId": issue_id,
            "publicationId": identity["publicationId"],
            "year": identity["year"],
            "issueLabel": make_issue_label(identity),
            "sortKey": make_sort_key(identity),
            "sourceKey": make_source_key(identity),
            "sourceNames": sorted(source_names),
            "relatedIssueId": related_issue_id(identity),
            "coverUrl": cover_url,
            "ocrPdfUrl": ocr_url,
            "ocrPdfSize": ocr_size,
            "ocrPdfVersion": ocr_version,
            "ocrPdfChecksum": checksum,
            "ocrPdfFtpSource": ftp_generation,
            "largePdfUrl": ftp_url(large["path"]) if large else None,
            "largePdfSize": large["size"] if large else None,
            "hqSource": "scans" if pages else ("large_pdf" if large else None),
            "scanPagePattern": scan_page_pattern(issue),
            "pageCount": page_count,
            "spreadOffset": None,
            "flags": sorted(flags),
        })

    used = sorted({issue["publicationId"] for issue in issues_out})
    listed_at = listing["listedAt"]
    return {
        "inventory": {
            "schemaVersion": SCHEMA_VERSION,
            "datasetVersion": f"{listed_at[:10]}-001",
            "generatedAt": listed_at,
            "generator": f"archive_inventory.py {VERSION}",
            "publications": [{"publicationId": p, "publicationTitle": PUBLICATIONS[p]} for p in used],
            "issues": issues_out,
        },
        "problems": problems,
    }


def write_inventory(path: Path, inventory: dict) -> None:
    """Stabil utdata: fast nyckelordning, så att samma indata ger samma byte."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(inventory, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    tmp.replace(path)


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
                        help="lista bara dessa kataloger (standard: roots och extraRoots "
                             "i config.json); används tillsammans med --resume")
    args = parser.parse_args(argv)

    config = load_config()
    raw_path = REPO_ROOT / config["rawListingPath"]
    roots = config["roots"]
    extra_roots = config.get("extraRoots", [])

    if args.offline:
        listing = load_listing(raw_path)
    else:
        listing = fetch_listing(config, args.roots or roots + extra_roots, raw_path,
                                resume=args.resume)

    files = list(iter_files(listing))
    print(f"Rålistning: {len(listing['directories'])} kataloger, {len(files)} filer "
          f"({'cache' if args.offline else 'server'}, listad {listing.get('listedAt', '?')})")

    analysis = analyze(listing, roots, extra_roots)
    in_roots = len(files) - len(analysis["outsideRoots"])
    matched = in_roots - sum(1 for c in analysis["specialCases"] if c["size"] is not None)
    print(f"Filer i {', '.join(roots + extra_roots)}: {in_roots}")
    print(f"  kopplade till ett issueId: {matched} ({len(analysis['issues'])} nummer)")
    print(f"  specialfall: {len(analysis['specialCases'])}")
    for case in analysis["specialCases"]:
        print(f"    {case['path']}: {case['reason']}")
    print(f"  avvikelser lösta med uttrycklig regel: {len(analysis['resolved'])}")
    for rule, count in sorted(_count(c["rule"] for c in analysis["resolved"]).items()):
        print(f"    {count:3d} × {rule}")
    print(f"Filer utanför de listade katalogerna (arkivets rot): {len(analysis['outsideRoots'])}")

    probe_path = REPO_ROOT / config["httpsProbePath"]
    if not probe_path.exists():
        raise SystemExit(f"{probe_path} saknas. Kör tools/https_probe.py först.")
    probe = load_listing(probe_path)
    measurements_path = REPO_ROOT / config["sampleMeasurementsPath"]
    measurements = load_listing(measurements_path)["measurements"] if measurements_path.exists() else {}

    built = build_inventory(listing, analysis, probe, measurements)
    inventory = built["inventory"]
    ids = [issue["issueId"] for issue in inventory["issues"]]
    if len(ids) != len(set(ids)):
        raise SystemExit("Dubbletter bland issueId – inventory skrivs inte.")
    inventory_path = REPO_ROOT / config["inventoryPath"]
    write_inventory(inventory_path, inventory)
    print(f"Inventory: {len(ids)} nummer, datasetVersion {inventory['datasetVersion']} "
          f"→ {inventory_path.relative_to(REPO_ROOT)}")
    for problem in built["problems"]:
        print(f"  OBS {problem}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
