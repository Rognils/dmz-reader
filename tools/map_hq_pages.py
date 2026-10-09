#!/usr/bin/env python3
"""DMZ Reader – kontrollerar mappningen mellan PDF-sida och originalskanning (FAS 11).

PDF-sidan renderas nedskalad och jämförs med skanningen nedskalad. Måttet är
normaliserad korskorrelation mellan gråskalebilderna, 0–1. Skanningen kan vara
annorlunda beskuren än PDF-sidan, så jämförelsen görs för flera beskärningar av
båda bilderna och den bästa räknas.

Underkommandon:
  full ISSUE     jämför varje PDF-sida med varje skanning i numret och föreslår en
                 fullständig mappning (för nummer där sida N inte är skanning N)
  sample ISSUE…  kontrollerar första, mellersta och sista sidan mot skanningen med
                 samma ordningsnummer, och mot grannarna som jämförelse
  landscape      listar de PDF-sidor som visas liggande (uppslag skannade som en bild)
  rotation       kontrollerar hur skanningen ska vridas för varje liggande PDF-sida
  export         skriver tools/data/hq_page_maps.json, som archive_inventory.py läser

Skanningarna hämtas först med tools/fetch_scans.py till cache/scans/.
Resultaten skrivs till data/hq_mapping_report.json.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

import fetch_scans

REPO_ROOT = Path(__file__).resolve().parent.parent
PDF_DIR = REPO_ROOT / "cache" / "pdf"
SCAN_DIR = fetch_scans.CACHE_DIR
REPORT_PATH = REPO_ROOT / "data" / "hq_mapping_report.json"
EXPORT_PATH = REPO_ROOT / "tools" / "data" / "hq_page_maps.json"

Image.MAX_IMAGE_PIXELS = None  # skanningarna är stora, och de kommer från en känd källa

# Storleken som jämförs, och storleken som beskärningarna görs ur.
COMPARE_SIZE = (48, 64)
WORK_LONG_SIDE = 320
# Andel av bredden eller höjden som skärs bort på en kant, i sökningen efter bästa passning.
INSETS = (0.0, 0.03, 0.06, 0.10)

# En sida räknas som säkert mappad när poängen är minst MIN_SCORE och
# näst bästa kandidat ligger minst MIN_MARGIN lägre.
MIN_SCORE = 0.60
MIN_MARGIN = 0.10


def render_pdf_page(pdf: Path, page: int) -> Image.Image:
    """PDF-sidan i gråskala med längsta sidan WORK_LONG_SIDE."""
    with tempfile.TemporaryDirectory() as work:
        target = Path(work) / "page"
        subprocess.run(["pdftoppm", "-f", str(page), "-l", str(page), "-scale-to", str(WORK_LONG_SIDE),
                        "-gray", "-singlefile", str(pdf), str(target)], check=True, capture_output=True)
        with Image.open(target.with_suffix(".pgm")) as image:
            return image.convert("L").copy()


def load_scan(path: Path) -> Image.Image:
    """Skanningen i gråskala med längsta sidan WORK_LONG_SIDE."""
    with Image.open(path) as image:
        # JPEG kan avkodas nedskalad direkt, vilket sparar tid och minne.
        image.draft("L", (WORK_LONG_SIDE * 2, WORK_LONG_SIDE * 2))
        image = image.convert("L")
        image.thumbnail((WORK_LONG_SIDE, WORK_LONG_SIDE), Image.BILINEAR)
        return image.copy()


def is_landscape(image: Image.Image) -> bool:
    return image.width > image.height


def _vector(image: Image.Image, box: tuple[float, float, float, float], size: tuple[int, int]) -> np.ndarray:
    left, top, right, bottom = box
    width, height = image.size
    crop = image.resize(size, Image.BILINEAR,
                        box=(left * width, top * height, (1 - right) * width, (1 - bottom) * height))
    values = np.asarray(crop, dtype=np.float32).ravel()
    values = values - values.mean()
    norm = float(np.linalg.norm(values))
    return values / norm if norm > 0 else values


def _boxes() -> list[tuple[float, float, float, float]]:
    return [(left, top, right, bottom) for left in INSETS for top in INSETS for right in INSETS for bottom in INSETS]


def variants(image: Image.Image, size: tuple[int, int]) -> np.ndarray:
    """Alla beskärningar av bilden som enhetsvektorer, en per rad."""
    return np.stack([_vector(image, box, size) for box in _boxes()])


def compare_size(image: Image.Image) -> tuple[int, int]:
    return (COMPARE_SIZE[1], COMPARE_SIZE[0]) if is_landscape(image) else COMPARE_SIZE


def score_with_rotation(pdf_image: Image.Image, scan_image: Image.Image) -> tuple[float, int]:
    """
    Likheten mellan en PDF-sida och en skanning, 0–1, och hur skanningen ska vridas
    (grader medurs) för att ligga som PDF-sidan.

    Hela PDF-sidan jämförs med varje beskärning av skanningen, och hela skanningen
    med varje beskärning av PDF-sidan. Den bästa korrelationen räknas. Är den ena
    bilden liggande och den andra stående provas skanningen vriden åt båda håll.
    """
    size = compare_size(pdf_image)
    # PIL vrider moturs: rotate(270) är 90 grader medurs.
    candidates = {0: scan_image}
    if is_landscape(pdf_image) != is_landscape(scan_image):
        candidates = {90: scan_image.rotate(270, expand=True), 270: scan_image.rotate(90, expand=True)}
    whole = (0.0, 0.0, 0.0, 0.0)
    pdf_whole = _vector(pdf_image, whole, size)
    pdf_variants = variants(pdf_image, size)
    best, rotation = 0.0, 0
    for degrees, candidate in candidates.items():
        value = max(float((variants(candidate, size) @ pdf_whole).max()),
                    float((pdf_variants @ _vector(candidate, whole, size)).max()))
        if value > best:
            best, rotation = value, degrees
    return round(max(0.0, best), 3), rotation


def score(pdf_image: Image.Image, scan_image: Image.Image) -> float:
    return score_with_rotation(pdf_image, scan_image)[0]


class Scorer:
    """Som [score], men med bildernas beskärningar sparade, för jämförelser av alla sidor mot alla skanningar."""

    def __init__(self):
        self.cache: dict[tuple[str, tuple[int, int]], tuple[np.ndarray, np.ndarray]] = {}

    def _prepared(self, key: str, image: Image.Image, size: tuple[int, int]):
        if (key, size) not in self.cache:
            self.cache[(key, size)] = (_vector(image, (0.0, 0.0, 0.0, 0.0), size), variants(image, size))
        return self.cache[(key, size)]

    def score(self, pdf_key: str, pdf_image: Image.Image, scan_key: str, scan_image: Image.Image) -> float:
        if is_landscape(pdf_image) != is_landscape(scan_image):
            return score(pdf_image, scan_image)
        size = compare_size(pdf_image)
        pdf_whole, pdf_variants = self._prepared(pdf_key, pdf_image, size)
        scan_whole, scan_variants = self._prepared(scan_key, scan_image, size)
        return round(max(0.0, float((scan_variants @ pdf_whole).max()), float((pdf_variants @ scan_whole).max())), 3)


def pdf_page_count(pdf: Path) -> int:
    output = subprocess.run(["pdfinfo", str(pdf)], check=True, capture_output=True, text=True).stdout
    return int(next(line.split()[1] for line in output.splitlines() if line.startswith("Pages:")))


def decide(scores: dict[str, float]) -> tuple[str | None, float, float]:
    """Bästa kandidat, dess poäng och marginalen till näst bästa. Kandidaten är None när den inte är säker."""
    ranked = sorted(scores.items(), key=lambda item: -item[1])
    best_name, best = ranked[0]
    second = ranked[1][1] if len(ranked) > 1 else 0.0
    margin = round(best - second, 3)
    return (best_name if best >= MIN_SCORE and margin >= MIN_MARGIN else None), best, margin


def full_mapping(issue: dict, listing: dict, log=print) -> dict:
    """Jämför varje PDF-sida med varje skanning och föreslår hqPageMap."""
    issue_id = issue["issueId"]
    pdf = PDF_DIR / f"{issue_id}.pdf"
    names = [entry["name"] for entry in fetch_scans.scan_files(issue, listing)]
    scans = {name: load_scan(SCAN_DIR / issue_id / name) for name in names}
    scorer = Scorer()
    pages = []
    for page in range(1, pdf_page_count(pdf) + 1):
        image = render_pdf_page(pdf, page)
        scores = {name: scorer.score(f"pdf-{page}", image, name, scan) for name, scan in scans.items()}
        chosen, best, margin = decide(scores)
        ranked = sorted(scores.items(), key=lambda item: -item[1])
        pages.append({"pdfPage": page, "scan": chosen, "bestCandidate": ranked[0][0], "score": best, "margin": margin,
                      "secondCandidate": ranked[1][0], "secondScore": ranked[1][1]})
        log(f"  sida {page:3d}: {ranked[0][0]} {best:.3f} (marginal {margin:.3f}){'' if chosen else '  OSÄKER'}")
    # Sidor utan säker träff provas mot de oanvända skanningarna vända upp och ned.
    taken = {page["scan"] for page in pages if page["scan"]}
    rotated = {name: scan.rotate(180) for name, scan in scans.items() if name not in taken}
    for page in pages:
        page["rotation"] = 0
        if page["scan"] or not rotated:
            continue
        image = render_pdf_page(pdf, page["pdfPage"])
        scores = {name: score(image, scan) for name, scan in rotated.items()}
        chosen, best, margin = decide(scores)
        if chosen:
            page.update({"scan": chosen, "bestCandidate": chosen, "score": best, "margin": margin, "rotation": 180})
            log(f"  sida {page['pdfPage']:3d}: {chosen} vänd 180 grader {best:.3f} (marginal {margin:.3f})")
    used = [page["scan"] for page in pages if page["scan"]]
    duplicates = sorted({name for name in used if used.count(name) > 1})
    for page in pages:
        if page["scan"] in duplicates:
            page["scan"] = None  # två PDF-sidor kan inte ha samma skanning; ingen av dem är säker
    return {
        "issueId": issue_id,
        "pdfPageCount": len(pages),
        "scanFileCount": len(names),
        "pages": pages,
        "unusedScans": [name for name in names if name not in used],
        "duplicateScans": duplicates,
        "hqPageMap": {str(page["pdfPage"]): page["scan"] for page in pages},
        "hqPageRotation": {str(page["pdfPage"]): page["rotation"] for page in pages if page["scan"] and page["rotation"]},
    }


def sample_positions(count: int) -> dict[str, int]:
    return {"first": 1, "middle": (count + 1) // 2, "last": count}


def sample_issue(issue: dict, listing: dict, log=print) -> dict:
    """Första, mellersta och sista sidan mot skanningen med samma ordningsnummer."""
    issue_id = issue["issueId"]
    pdf = PDF_DIR / f"{issue_id}.pdf"
    files = [entry["name"] for entry in fetch_scans.scan_files(issue, listing)]
    count = pdf_page_count(pdf)
    result = {"issueId": issue_id, "pdfPageCount": count, "scanFileCount": len(files), "pages": []}
    available = {position: files[page - 1] for position, page in sample_positions(len(files)).items()}
    images = {name: load_scan(SCAN_DIR / issue_id / name) for name in set(available.values())}
    for position, page in sample_positions(count).items():
        image = render_pdf_page(pdf, page)
        expected = files[page - 1] if page <= len(files) else None
        own, rotation = score_with_rotation(image, images[expected]) if expected in images else (None, 0)
        # Som jämförelse: samma PDF-sida mot de andra hämtade skanningarna i numret.
        others = {name: score(image, scan) for name, scan in images.items() if name != expected}
        entry = {"position": position, "pdfPage": page, "scan": expected, "score": own,
                 "bestOtherScan": max(others, key=others.get) if others else None,
                 "bestOtherScore": max(others.values()) if others else None,
                 "pdfLandscape": is_landscape(image), "scanRotation": rotation}
        entry["ok"] = own is not None and own >= MIN_SCORE and own - (entry["bestOtherScore"] or 0.0) >= MIN_MARGIN
        result["pages"].append(entry)
        log(f"  {issue_id} sida {page:3d} ({position}): {expected} {own} · vridning {rotation} · bästa andra {entry['bestOtherScore']}"
            f"{'' if entry['ok'] else '  AVVIKER'}")
    return result


def pdf_page_sizes(pdf: Path) -> dict[int, tuple[float, float, int]]:
    """Bredd, höjd (som sidan visas) och vridning för varje sida."""
    import re
    output = subprocess.run(["pdfinfo", "-f", "1", "-l", "9999", str(pdf)], check=True, capture_output=True, text=True).stdout
    sizes = {int(m[1]): (float(m[2]), float(m[3])) for m in re.finditer(r"Page\s+(\d+) size:\s+([\d.]+) x ([\d.]+)", output)}
    rotations = {int(m[1]): int(m[2]) for m in re.finditer(r"Page\s+(\d+) rot:\s+(\d+)", output)}
    result = {}
    for page, (width, height) in sizes.items():
        rotation = rotations.get(page, 0)
        if rotation in (90, 270):
            width, height = height, width
        result[page] = (width, height, rotation)
    return result


def landscape_pages(pdf: Path) -> list[int]:
    """Sidorna som visas liggande i PDF:en."""
    return [page for page, (width, height, _) in sorted(pdf_page_sizes(pdf).items()) if width > height]


def rotation_check(issue: dict, listing: dict, log=print) -> dict | None:
    """
    För varje liggande PDF-sida: hur skanningen med samma ordningsnummer ska vridas.
    Returnerar None om numret inte har några liggande sidor.
    """
    issue_id = issue["issueId"]
    pdf = PDF_DIR / f"{issue_id}.pdf"
    pages = landscape_pages(pdf)
    if not pages:
        return None
    files = [entry["name"] for entry in fetch_scans.scan_files(issue, listing)]
    result = {"issueId": issue_id, "pages": []}
    for page in pages:
        name = files[page - 1] if page <= len(files) else None
        path = SCAN_DIR / issue_id / name if name else None
        if path is None or not path.exists():
            result["pages"].append({"pdfPage": page, "scan": name, "score": None, "rotation": None, "ok": False})
            log(f"  {issue_id} sida {page:3d}: skanningen {name} är inte hämtad")
            continue
        scan = load_scan(path)
        value, rotation = score_with_rotation(render_pdf_page(pdf, page), scan)
        ok = value >= MIN_SCORE
        result["pages"].append({"pdfPage": page, "scan": name, "score": value, "rotation": rotation,
                                "scanLandscape": is_landscape(scan), "ok": ok})
        log(f"  {issue_id} sida {page:3d}: {name} {value} · vridning {rotation}{'' if ok else '  OSÄKER'}")
    return result


def export_maps(report: dict, inventory: dict, listing: dict) -> dict:
    """
    Det som ska in i inventory: hqPageMap för de fullständigt mappade numren, och
    hqPageRotation för liggande sidor där skanningen ligger vriden.

    En liggande sida vars vridning inte gick att fastställa får ingen HQ. Numret får då
    ett hqPageMap enligt huvudregeln (sida N är skanning N), med null för den sidan.
    """
    issues: dict[str, dict] = {}
    for issue_id, result in sorted(report.get("full", {}).items()):
        entry = {"hqPageMap": result["hqPageMap"]}
        if result.get("hqPageRotation"):
            entry["hqPageRotation"] = result["hqPageRotation"]
        issues[issue_id] = entry
    for issue_id, result in sorted(report.get("rotation", {}).items()):
        if issue_id in issues:
            raise SystemExit(f"{issue_id}: både fullständig mappning och vridningskontroll; slå ihop för hand")
        rotation = {str(page["pdfPage"]): page["rotation"] for page in result["pages"] if page["ok"] and page["rotation"]}
        uncertain = [page["pdfPage"] for page in result["pages"] if not page["ok"]]
        entry: dict = {}
        if uncertain:
            files = [item["name"] for item in fetch_scans.scan_files(inventory[issue_id], listing)]
            entry["hqPageMap"] = {str(number): (None if number in uncertain else name)
                                  for number, name in enumerate(files, 1)}
        if rotation:
            entry["hqPageRotation"] = rotation
        if entry:
            issues[issue_id] = entry
    return {
        "comment": "HQ-mappningar framtagna med bildjämförelse av tools/map_hq_pages.py. Underlaget finns i "
                   "data/hq_mapping_report.json. Slås ihop med inventory av tools/archive_inventory.py.",
        "issues": issues,
    }


def load_report() -> dict:
    if REPORT_PATH.exists():
        return json.loads(REPORT_PATH.read_text(encoding="utf-8"))
    return {"method": {}, "full": {}, "sample": {}, "rotation": {}}


def save_report(report: dict) -> None:
    report["method"] = {
        "measure": "normaliserad korskorrelation mellan gråskalebilder, bästa värdet över beskärningar av båda bilderna",
        "compareSize": list(COMPARE_SIZE), "insets": list(INSETS), "minScore": MIN_SCORE, "minMargin": MIN_MARGIN,
    }
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("full", "sample"):
        commands.add_parser(name).add_argument("issues", nargs="+")
    commands.add_parser("landscape", help="listar liggande PDF-sidor i alla nummer, som argument till fetch_scans.py")
    commands.add_parser("rotation", help="kontrollerar vridningen för alla liggande PDF-sidor")
    commands.add_parser("export", help="skriver tools/data/hq_page_maps.json ur rapporten")
    arguments = parser.parse_args(argv)
    inventory = {issue["issueId"]: issue
                 for issue in json.loads(fetch_scans.INVENTORY_PATH.read_text(encoding="utf-8"))["issues"]}
    listing = json.loads(fetch_scans.RAW_LISTING_PATH.read_text(encoding="utf-8"))
    report = load_report()
    if arguments.command == "landscape":
        for issue_id in sorted(inventory):
            pages = landscape_pages(PDF_DIR / f"{issue_id}.pdf")
            if pages:
                print(issue_id, *pages)
        return 0
    if arguments.command == "export":
        maps = export_maps(report, inventory, listing)
        EXPORT_PATH.write_text(json.dumps(maps, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")
        print(f"{EXPORT_PATH}: {len(maps['issues'])} nummer")
        return 0
    if arguments.command == "rotation":
        report["rotation"] = {}
        for issue_id in sorted(inventory):
            result = rotation_check(inventory[issue_id], listing)
            if result:
                report["rotation"][issue_id] = result
        save_report(report)
        return 0
    for issue_id in arguments.issues:
        print(issue_id)
        if arguments.command == "full":
            report["full"][issue_id] = full_mapping(inventory[issue_id], listing)
        else:
            report["sample"][issue_id] = sample_issue(inventory[issue_id], listing)
        save_report(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
