#!/usr/bin/env python3
"""DMZ Reader – arkivinventering (FAS 0 och 0B).

Steg 1: lista arkivets FTP-kataloger och spara rålistningen i
data/raw_listing.json. Rålistningen är också cachen: med --offline
rörs inte nätet alls.

Steg 2: tolka varje filnamn enligt namnreglerna nedan och koppla det
till ett nummer (issueId). Det som inte matchar en regel gissas aldrig,
utan listas som specialfall.

Steg 3: bygg inventory. Läs-PDF och omslag pekar på webbplatsen (ADR 0008);
uppgifterna om dem kommer från data/https_probe.json, som
tools/https_probe.py tar fram. Manuella rättelser läses från
tools/overrides.json.

Steg 4: validera mot docs/inventory.schema.json och skriv
data/archive_inventory.json och data/inventory_diagnostics.json. Samma indata
ger byte-identiska filer. Med --compare-to jämförs resultatet mot ett annat
inventory, och skillnaden skrivs till data/inventory_diff.json.

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

VERSION = "0.2.1"
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
RE_DATASET_VERSION = re.compile(r"^\d{4}-\d{2}-\d{2}-\d{3}$")

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


def make_dataset_version(listing: dict, sequence: int) -> str:
    """ÅÅÅÅ-MM-DD-NNN: dagen då arkivet listades plus ett löpnummer."""
    return f"{listing['listedAt'][:10]}-{sequence:03d}"


def build_inventory(listing: dict, analysis: dict, probe: dict, measurements: dict,
                    dataset_version: str, generated_at: str | None = None) -> tuple[dict, dict]:
    """Bygg inventory (för appen) och diagnostik (bara för toolchainen).

    Utdatan beror bara på indata: ingen klocka, ingen ordning från filsystemet.
    """
    if not RE_DATASET_VERSION.match(dataset_version):
        raise SystemExit(f"datasetVersion ska ha formatet ÅÅÅÅ-MM-DD-NNN, inte {dataset_version!r}")
    results = probe["results"]
    issues_out = []
    diagnostics_issues = {}
    problems = []
    warnings = []

    for issue_id, issue in analysis["issues"].items():
        identity = issue["identity"]
        files = issue["files"]
        pages = issue["scanPages"]
        main_pages = {page: p for (part, page), p in pages.items() if part == MAIN_PART}
        flags = set()

        pdf = results.get(pdf_url_path(identity))
        if pdf is None:
            raise SystemExit(f"{issue_id}: saknas i https_probe.json. Kör tools/https_probe.py.")
        # Ett nummer utan läs-PDF eller omslag exporteras ändå, med flagga och varning.
        has_pdf = pdf["status"] == 200
        ocr_size = int(pdf["content-length"]) if has_pdf and "content-length" in pdf else None
        if not has_pdf:
            flags.add("missing_ocr_pdf")
            warnings.append(f"{issue_id}: ingen läs-PDF på webbplatsen "
                            f"(status {pdf['status']}); ocrPdfUrl är null")

        cover = None
        if 1 in main_pages:
            cover = results.get(page_url_path(issue, 1))
            if cover is None:
                raise SystemExit(f"{issue_id}: sidbild 1 saknas i https_probe.json. "
                                 "Kör tools/https_probe.py.")
        has_cover = cover is not None and cover["status"] == 200
        if not has_cover:
            flags.add("missing_cover")
            reason = f"status {cover['status']}" if cover else "numret har ingen skannad sida 1"
            warnings.append(f"{issue_id}: ingen sidbild 1 på webbplatsen ({reason}); coverUrl är null")

        # Vilken FTP-generation webbfilen motsvarar avgörs av storleken.
        ftp_generation = None
        if ocr_size is not None:
            for kind, name in (("ocr_pdf_v2", "OCR PDF v2"), ("ocr_pdf", "OCR PDF")):
                if kind in files and files[kind]["size"] == ocr_size:
                    ftp_generation = name
            if ftp_generation is None:
                problems.append(f"{issue_id}: webbens PDF ({ocr_size} byte) motsvarar ingen FTP-fil")

        if identity["numberEnd"] is not None:
            flags.add("double_issue")
        if identity["suffix"] == "b":
            flags.add("variant_b")
        if identity["publicationId"] == "highscore":
            flags.add("supplement")
        if pages:
            if {p["pageDigits"] for p in pages.values()} == {2}:
                flags.add("scan_two_digit_pages")
            if {p["ext"] for p in pages.values()} != {"jpg"}:
                flags.add("scan_png")
            if {part for part, _ in pages} != {MAIN_PART}:
                flags.add("scan_supplement")
            if any(p["namedAfterFirstNumber"] for p in pages.values()):
                flags.add("scan_named_after_first_number")

        source_names = [files[kind]["path"]
                        for kind in ("ocr_pdf", "ocr_pdf_v2", "large_pdf", "cover") if kind in files]
        if pages:
            source_names.append(next(iter(pages.values()))["path"].rsplit("/", 1)[0] + "/")

        page_count = None
        measured = measurements.get(issue_id)
        measurement_used = False
        if measured is not None:
            if has_pdf and measured["ocrPdfSize"] == ocr_size:
                page_count = measured["pageCount"]
                measurement_used = True
            else:
                problems.append(f"{issue_id}: stickprovets mätvärden gäller en annan fil och används inte")

        large = files.get("large_pdf")
        issues_out.append({
            "issueId": issue_id,
            "publicationId": identity["publicationId"],
            "publicationTitle": PUBLICATIONS[identity["publicationId"]],
            "year": identity["year"],
            "issueLabel": make_issue_label(identity),
            "sortKey": make_sort_key(identity),
            "sourceKey": make_source_key(identity),
            "sourceNames": sorted(source_names),
            "relatedIssueId": related_issue_id(identity),
            "coverUrl": WEB_BASE_URL + page_url_path(issue, 1) if has_cover else None,
            "coverFlags": [],
            "ocrPdfUrl": WEB_BASE_URL + pdf_url_path(identity) if has_pdf else None,
            "ocrPdfSize": ocr_size,
            "ocrPdfEtag": pdf.get("etag") if has_pdf else None,
            "ocrPdfLastModified": pdf.get("last-modified") if has_pdf else None,
            "largePdfUrl": ftp_url(large["path"]) if large else None,
            "largePdfSize": large["size"] if large else None,
            "hqSource": "scans" if main_pages else ("large_pdf" if large else None),
            "scanPagePattern": scan_page_pattern(issue),
            # Bara huvuddelen räknas. En bilaga med egen sidräkning ingår inte.
            "scanPageCount": len(main_pages) or None,
            "pageCount": page_count,
            "spreadOffset": None,
            "flags": sorted(flags),
        })

        scan_page_one = main_pages.get(1)
        diagnostics_issues[issue_id] = {
            "ocrPdfFtpSource": ftp_generation,
            "webPdfSize": ocr_size,
            "ftpOcrPdfSize": files["ocr_pdf"]["size"] if "ocr_pdf" in files else None,
            "ftpOcrPdfV2Size": files["ocr_pdf_v2"]["size"] if "ocr_pdf_v2" in files else None,
            "webPdfMatchesFtp": ftp_generation is not None,
            "ftpCoverPath": files["cover"]["path"] if "cover" in files else None,
            "ftpCoverSize": files["cover"]["size"] if "cover" in files else None,
            "ftpCoverSameSizeAsScanPage1": (
                files["cover"]["size"] == scan_page_one["size"]
                if "cover" in files and scan_page_one else None),
            "scanParts": {part or "main": sum(1 for prt, _ in pages if prt == part)
                          for part in sorted({part for part, _ in pages})},
            "scanTotalBytes": sum(p["size"] for p in pages.values()),
            "measurement": ({**measured, "used": measurement_used} if measured is not None else None),
            "appliedOverrides": [],
        }

    used = sorted({issue["publicationId"] for issue in issues_out})
    inventory = {
        "schemaVersion": SCHEMA_VERSION,
        "datasetVersion": dataset_version,
        "generatedAt": generated_at or f"{dataset_version[:10]}T00:00:00Z",
        "generator": f"archive_inventory.py {VERSION}",
        "publications": [{"publicationId": p, "publicationTitle": PUBLICATIONS[p]} for p in used],
        "issues": issues_out,
    }
    diagnostics = {
        "datasetVersion": dataset_version,
        "generator": inventory["generator"],
        "rawListingListedAt": listing.get("listedAt"),
        "httpsProbedAt": probe.get("probedAt"),
        "issues": diagnostics_issues,
        "specialCases": analysis["specialCases"],
        "resolvedByRule": analysis["resolved"],
        "outsideRoots": analysis["outsideRoots"],
        "problems": problems,
        "warnings": warnings,
    }
    return inventory, diagnostics


# --------------------------------------------------------------------------
# Manuella rättelser (overrides)
# --------------------------------------------------------------------------

def _is_string_list(value) -> bool:
    return isinstance(value, list) and all(isinstance(v, str) and v for v in value)


# Fält som får rättas för hand, med kontroll av värdet.
OVERRIDE_FIELDS = {
    "coverFlags": _is_string_list,
    "flags": _is_string_list,
    "spreadOffset": lambda v: v is None or (isinstance(v, int) and not isinstance(v, bool)),
    "issueLabel": lambda v: isinstance(v, str) and bool(v.strip()),
    # Fritext som förklarar rättelsen. Följer inte med till inventory.
    "reason": lambda v: isinstance(v, str),
}


class OverrideError(ValueError):
    """Fel i overrides-filen. Ingenting ignoreras tyst."""


def load_overrides(path: Path) -> dict:
    if not path.exists():
        return {}
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    if not isinstance(data, dict) or set(data) - {"comment", "overrides"} or "overrides" not in data:
        raise OverrideError(f"{path.name}: förväntade ett objekt med nyckeln \"overrides\"")
    if not isinstance(data["overrides"], dict):
        raise OverrideError(f"{path.name}: \"overrides\" ska vara ett objekt per issueId")
    return data["overrides"]


def apply_overrides(inventory: dict, overrides: dict, diagnostics: dict | None = None) -> None:
    """Slå ihop manuella rättelser med inventory, på plats.

    coverFlags, spreadOffset och issueLabel ersätter det genererade värdet.
    flags läggs till de genererade flaggorna. Okänt issueId, okänt fält eller
    fel typ ger OverrideError.
    """
    by_id = {issue["issueId"]: issue for issue in inventory["issues"]}
    errors = []
    for issue_id in sorted(overrides):
        fields = overrides[issue_id]
        if issue_id not in by_id:
            errors.append(f"okänt issueId: {issue_id}")
            continue
        if not isinstance(fields, dict):
            errors.append(f"{issue_id}: rättelsen ska vara ett objekt")
            continue
        for field in sorted(fields):
            if field not in OVERRIDE_FIELDS:
                errors.append(f"{issue_id}: okänt fält: {field}")
            elif not OVERRIDE_FIELDS[field](fields[field]):
                errors.append(f"{issue_id}: ogiltigt värde för {field}: {fields[field]!r}")
    if errors:
        raise OverrideError("Fel i overrides: " + "; ".join(errors))

    for issue_id in sorted(overrides):
        issue = by_id[issue_id]
        applied = []
        for field in sorted(overrides[issue_id]):
            value = overrides[issue_id][field]
            if field == "reason":
                continue
            if field == "flags":
                issue["flags"] = sorted(set(issue["flags"]) | set(value))
            elif field == "coverFlags":
                issue["coverFlags"] = sorted(set(value))
            else:
                issue[field] = value
            applied.append(field)
        if diagnostics is not None:
            diagnostics["issues"][issue_id]["appliedOverrides"] = applied


# --------------------------------------------------------------------------
# Validering
# --------------------------------------------------------------------------

def load_schema(path: Path) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def validate_inventory(inventory: dict, schema: dict) -> list[str]:
    """Returnerar en lista med fel. Tom lista betyder giltigt inventory."""
    try:
        import jsonschema
    except ImportError as error:
        raise SystemExit("Paketet jsonschema saknas. Installera med: "
                         "python3 -m pip install -r tools/requirements.txt") from error

    validator = jsonschema.Draft202012Validator(schema)
    errors = []
    for error in sorted(validator.iter_errors(inventory), key=lambda e: list(map(str, e.absolute_path))):
        where = "/".join(str(part) for part in error.absolute_path) or "(roten)"
        errors.append(f"schema: {where}: {error.message}")
    if errors:
        # Kontrollerna nedan förutsätter att strukturen är rätt.
        return errors

    issues = inventory["issues"]
    titles = {p["publicationId"]: p["publicationTitle"] for p in inventory["publications"]}
    if len(titles) != len(inventory["publications"]):
        errors.append("publications: samma publicationId förekommer flera gånger")

    seen = {}
    for index, issue in enumerate(issues):
        if issue["issueId"] in seen:
            errors.append(f"issueId är inte unikt: {issue['issueId']} "
                          f"(post {seen[issue['issueId']]} och {index})")
        seen.setdefault(issue["issueId"], index)

    for issue in issues:
        issue_id = issue["issueId"]
        related = issue.get("relatedIssueId")
        if related is not None and related not in seen:
            errors.append(f"{issue_id}: relatedIssueId pekar på {related}, som inte finns")
        if related == issue_id:
            errors.append(f"{issue_id}: relatedIssueId pekar på numret självt")
        if issue["publicationId"] not in titles:
            errors.append(f"{issue_id}: publicationId {issue['publicationId']} saknas i publications")
        elif titles[issue["publicationId"]] != issue["publicationTitle"]:
            errors.append(f"{issue_id}: publicationTitle stämmer inte med publications")
        if not issue["issueId"].startswith(issue["publicationId"] + "-"):
            errors.append(f"{issue_id}: issueId börjar inte med publicationId")
        if not issue["sortKey"].startswith(f"{issue['year']:04d}-"):
            errors.append(f"{issue_id}: sortKey {issue['sortKey']} börjar inte med året {issue['year']}")

    # sortKey ska ge en entydig ordning inom varje publikation och år,
    # och numren ska ligga i den ordningen i filen.
    groups: dict = {}
    for issue in issues:
        groups.setdefault((issue["publicationId"], issue["year"]), []).append(issue)
    for (publication_id, year), group in sorted(groups.items()):
        keys = [issue["sortKey"] for issue in group]
        if len(set(keys)) != len(keys):
            duplicates = sorted({key for key in keys if keys.count(key) > 1})
            errors.append(f"{publication_id} {year}: sortKey är inte unik: {', '.join(duplicates)}")
        elif keys != sorted(keys):
            errors.append(f"{publication_id} {year}: numren ligger inte i sortKey-ordning")
    order = [(issue["publicationId"], issue["sortKey"]) for issue in issues]
    if order != sorted(order) and not any("sortKey-ordning" in e or "inte unik" in e for e in errors):
        errors.append("numren ligger inte sorterade på publicationId och sortKey")
    return errors


# --------------------------------------------------------------------------
# Jämförelse mot föregående inventory
# --------------------------------------------------------------------------

_MISSING = object()


def diff_inventories(previous: dict, current: dict) -> dict:
    """Nya, borttagna och ändrade nummer, med vilka fält som ändrats."""
    old = {issue["issueId"]: issue for issue in previous.get("issues", [])}
    new = {issue["issueId"]: issue for issue in current.get("issues", [])}

    changed = []
    for issue_id in sorted(set(old) & set(new)):
        fields = {}
        for field in sorted(set(old[issue_id]) | set(new[issue_id])):
            before = old[issue_id].get(field, _MISSING)
            after = new[issue_id].get(field, _MISSING)
            if before == after:
                continue
            change = {}
            if before is not _MISSING:
                change["old"] = before
            if after is not _MISSING:
                change["new"] = after
            fields[field] = change
        if fields:
            changed.append({"issueId": issue_id, "fields": fields})

    header = {}
    for field in ("schemaVersion", "datasetVersion", "generatedAt", "generator", "publications"):
        if previous.get(field) != current.get(field):
            header[field] = {"old": previous.get(field), "new": current.get(field)}

    return {
        "previousDatasetVersion": previous.get("datasetVersion"),
        "currentDatasetVersion": current.get("datasetVersion"),
        "header": header,
        "added": sorted(set(new) - set(old)),
        "removed": sorted(set(old) - set(new)),
        "changed": changed,
        "unchangedCount": len(set(old) & set(new)) - len(changed),
    }


def format_diff(diff: dict, max_examples: int = 5) -> str:
    """Läsbar rapport över en jämförelse."""
    lines = [f"Jämförelse: datasetVersion {diff['previousDatasetVersion']} → "
             f"{diff['currentDatasetVersion']}"]
    for field, change in diff["header"].items():
        if field != "publications":
            lines.append(f"  {field}: {change['old']} → {change['new']}")
        else:
            lines.append("  publications: ändrad")
    lines.append(f"  nya nummer: {len(diff['added'])}")
    lines += [f"    + {issue_id}" for issue_id in diff["added"]]
    lines.append(f"  borttagna nummer: {len(diff['removed'])}")
    lines += [f"    - {issue_id}" for issue_id in diff["removed"]]
    lines.append(f"  ändrade nummer: {len(diff['changed'])}")
    lines.append(f"  oförändrade nummer: {diff['unchangedCount']}")

    per_field: dict = {}
    for entry in diff["changed"]:
        for field in entry["fields"]:
            per_field.setdefault(field, []).append(entry)
    for field in sorted(per_field):
        entries = per_field[field]
        kinds = {("tillagt" if "old" not in e["fields"][field] else
                  "borttaget" if "new" not in e["fields"][field] else "ändrat") for e in entries}
        lines.append(f"    fält {field}: {len(entries)} nummer ({', '.join(sorted(kinds))})")
        for entry in entries[:max_examples]:
            change = entry["fields"][field]
            old = json.dumps(change["old"], ensure_ascii=False) if "old" in change else "(saknades)"
            new = json.dumps(change["new"], ensure_ascii=False) if "new" in change else "(borttaget)"
            lines.append(f"      {entry['issueId']}: {old} → {new}")
        if len(entries) > max_examples:
            lines.append(f"      … och {len(entries) - max_examples} till")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# Kommandorad
# --------------------------------------------------------------------------

def dump_json(data) -> str:
    """Stabil text: sorterade nycklar, fast indrag, avslutande radbrytning."""
    return json.dumps(data, ensure_ascii=False, indent=1, sort_keys=True) + "\n"


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)


def _read_json(path: Path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def generate(config: dict, listing: dict, dataset_version: str | None = None,
             generated_at: str | None = None, overrides: dict | None = None) -> dict:
    """Hela kedjan från rålistning till validerat inventory, utan att skriva filer."""
    analysis = analyze(listing, config["roots"], config.get("extraRoots", []))
    probe_path = REPO_ROOT / config["httpsProbePath"]
    if not probe_path.exists():
        raise SystemExit(f"{probe_path} saknas. Kör tools/https_probe.py först.")
    probe = _read_json(probe_path)
    measurements_path = REPO_ROOT / config["sampleMeasurementsPath"]
    measurements = _read_json(measurements_path)["measurements"] if measurements_path.exists() else {}
    if overrides is None:
        overrides = load_overrides(REPO_ROOT / config["overridesPath"])
    if dataset_version is None:
        dataset_version = make_dataset_version(listing, config["datasetSequence"])

    inventory, diagnostics = build_inventory(
        listing, analysis, probe, measurements, dataset_version, generated_at)
    apply_overrides(inventory, overrides, diagnostics)
    errors = validate_inventory(inventory, load_schema(REPO_ROOT / config["schemaPath"]))
    return {"analysis": analysis, "inventory": inventory, "diagnostics": diagnostics, "errors": errors}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--offline", action="store_true",
                        help="använd bara cachen (data/raw_listing.json), inget nät")
    parser.add_argument("--resume", action="store_true",
                        help="återuppta en avbruten listning; redan listade kataloger hoppas över")
    parser.add_argument("--roots", nargs="+", metavar="KATALOG",
                        help="lista bara dessa kataloger (standard: roots och extraRoots "
                             "i config.json); används tillsammans med --resume")
    parser.add_argument("--dataset-version", metavar="ÅÅÅÅ-MM-DD-NNN",
                        help="standard: listningens datum plus datasetSequence i config.json")
    parser.add_argument("--generated-at", metavar="TID",
                        help="värde för generatedAt (standard: datasetVersions datum, kl. 00:00:00Z)")
    parser.add_argument("--compare-to", metavar="FIL", type=Path,
                        help="jämför det nya inventoryt mot denna fil och skriv "
                             "data/inventory_diff.json (utan flaggan görs ingen jämförelse)")
    parser.add_argument("--output-dir", metavar="KATALOG", type=Path,
                        help="skriv utdatafilerna hit i stället för till data/")
    parser.add_argument("--validate", metavar="FIL", type=Path,
                        help="validera bara ett befintligt inventory och avsluta")
    args = parser.parse_args(argv)

    config = load_config()

    if args.validate:
        errors = validate_inventory(_read_json(args.validate), load_schema(REPO_ROOT / config["schemaPath"]))
        for error in errors:
            print(f"FEL {error}")
        print(f"{args.validate}: {'ogiltigt, ' + str(len(errors)) + ' fel' if errors else 'giltigt'}")
        return 1 if errors else 0

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

    try:
        result = generate(config, listing, args.dataset_version, args.generated_at)
    except OverrideError as error:
        print(f"FEL {error}")
        return 1
    analysis, inventory, diagnostics = result["analysis"], result["inventory"], result["diagnostics"]

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
    for problem in diagnostics["problems"]:
        print(f"  OBS {problem}")
    for warning in diagnostics["warnings"]:
        print(f"VARNING {warning}")
    overridden = sorted(i for i, d in diagnostics["issues"].items() if d["appliedOverrides"])
    print(f"Manuella rättelser: {len(overridden)} nummer" + (f" ({', '.join(overridden)})" if overridden else ""))

    if result["errors"]:
        for error in result["errors"]:
            print(f"FEL {error}")
        print(f"Valideringen gav {len(result['errors'])} fel. Inga filer skrivs.")
        return 1
    print(f"Validering: {len(inventory['issues'])} nummer, inga fel")

    def target(key: str) -> Path:
        path = REPO_ROOT / config[key]
        return args.output_dir / path.name if args.output_dir else path

    inventory_path = target("inventoryPath")
    inventory_text = dump_json(inventory)

    if args.compare_to is not None:
        diff = diff_inventories(_read_json(args.compare_to), inventory)
        print(format_diff(diff))
        write_text(target("diffPath"), dump_json(diff))

    write_text(inventory_path, inventory_text)
    write_text(target("diagnosticsPath"), dump_json(diagnostics))
    print(f"Inventory: {len(inventory['issues'])} nummer, datasetVersion {inventory['datasetVersion']} "
          f"→ {inventory_path.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
