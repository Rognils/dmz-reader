#!/usr/bin/env python3
"""DMZ Reader – bygger omslagsminiatyrer till data/covers/ (ADR 0016).

För varje nummer väljs en källa i cachen från tools/fetch_covers.py:

1. bearbetat omslag från FTP-katalogen Covers
2. webbplatsens sidbild 1, rättad enligt coverFlags i tools/overrides.json
3. ingen bild (appen visar en platshållare)

Ett omslag i Covers räknas som bearbetat om bilden skiljer sig från sidbild 1.
Många filer i Covers är obearbetade kopior av skanningen, och då är sidbild 1
samma bild i mindre storlek.

Transformordningen är alltid: först vridning, sedan beskärning, sist skalning.

Utdata är deterministisk för en given version av Pillow: samma källfiler ger
samma miniatyrer. Skriptet gör inga nätanrop.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import archive_inventory as ai
import fetch_covers

THUMBNAIL_WIDTH = 600
WEBP_QUALITY = 80
ASSET_DIRECTORY = "covers"
COVERS_DIR = ai.REPO_ROOT / "data" / "covers"
MANIFEST_PATH = ai.REPO_ROOT / "data" / "covers_manifest.json"
# Resultatet av den visuella granskningen av kontaktkartan, skrivet för hand.
REVIEW_PATH = ai.REPO_ROOT / "data" / "cover_review.json"
REVIEW_CLASSES = {"ok", "uppslag_vridet", "annat"}
CONTACT_SHEET_PATH = ai.REPO_ROOT / "docs" / "screenshots" / "covers" / "contact_sheet.png"

SOURCE_FTP = "covers_ftp"
SOURCE_WEB = "web_page1"

# Jämförelsen mellan Covers-filen och sidbild 1 görs på nedskalade gråskalebilder.
COMPARE_SIZE = 32
# Medelskillnad per pixel (0–255) över vilken bilderna räknas som olika. Uppmätt på
# arkivet: obearbetade kopior ger högst 0,74 och bearbetade omslag minst 5,5.
DIFFERENCE_THRESHOLD = 3.0
# Skillnad i bildformat (bredd/höjd) över vilken bilderna räknas som olika.
ASPECT_TOLERANCE = 0.03

Image.MAX_IMAGE_PIXELS = None  # Skanningarna i Covers är stora men betrodda.


# --------------------------------------------------------------------------
# Transform enligt coverFlags
# --------------------------------------------------------------------------

ROTATIONS = {"rotate_cw": 270, "rotate_180": 180, "rotate_ccw": 90}  # PIL vrider moturs
CROPS = ("crop_left_half", "crop_right_half")
KNOWN_COVER_FLAGS = set(ROTATIONS) | set(CROPS)


def apply_cover_flags(image: Image.Image, flags: list[str]) -> Image.Image:
    """Vrid först, beskär sedan. Samma ordning som appen använder för coverUrl."""
    unknown = set(flags) - KNOWN_COVER_FLAGS
    if unknown:
        raise ValueError(f"okända coverFlags: {sorted(unknown)}")
    for flag, degrees in ROTATIONS.items():
        if flag in flags:
            image = image.rotate(degrees, expand=True)
    half = image.width // 2
    if "crop_left_half" in flags:
        image = image.crop((0, 0, half, image.height))
    elif "crop_right_half" in flags:
        image = image.crop((image.width - half, 0, image.width, image.height))
    return image


def make_thumbnail(image: Image.Image) -> Image.Image:
    """Skalar till THUMBNAIL_WIDTH. En smalare bild förstoras inte."""
    image = image.convert("RGB")
    if image.width > THUMBNAIL_WIDTH:
        height = round(image.height * THUMBNAIL_WIDTH / image.width)
        image = image.resize((THUMBNAIL_WIDTH, height), Image.LANCZOS)
    return image


def encode_webp(image: Image.Image) -> bytes:
    import io
    buffer = io.BytesIO()
    image.save(buffer, "WEBP", quality=WEBP_QUALITY, method=6)
    return buffer.getvalue()


# --------------------------------------------------------------------------
# Klassning: är omslaget i Covers bearbetat?
# --------------------------------------------------------------------------

def _fingerprint(image: Image.Image) -> list[int]:
    small = image.convert("L").resize((COMPARE_SIZE, COMPARE_SIZE), Image.BILINEAR)
    return list(small.tobytes())


def compare_images(a: Image.Image, b: Image.Image) -> dict:
    """Jämför två bilder oberoende av upplösning."""
    first, second = _fingerprint(a), _fingerprint(b)
    difference = sum(abs(x - y) for x, y in zip(first, second)) / len(first)
    aspect_a, aspect_b = a.width / a.height, b.width / b.height
    aspect_change = abs(aspect_a - aspect_b) / aspect_b
    return {
        "difference": round(difference, 2),
        "aspectChange": round(aspect_change, 4),
        "differs": difference > DIFFERENCE_THRESHOLD or aspect_change > ASPECT_TOLERANCE,
    }


# --------------------------------------------------------------------------
# Bygge
# --------------------------------------------------------------------------

def asset_name(issue_id: str) -> str:
    return f"{ASSET_DIRECTORY}/{issue_id}.webp"


def choose_source(issue_id: str, ftp_path: str | None, forced: str | None, flags: list[str]) -> dict:
    """Välj källa för ett nummer och ta fram den färdiga bilden."""
    web_file = fetch_covers.web_cover_path(issue_id)
    ftp_file = fetch_covers.ftp_cover_path(ftp_path) if ftp_path else None
    web = Image.open(web_file) if web_file.is_file() else None
    ftp = Image.open(ftp_file) if ftp_file is not None and ftp_file.is_file() else None

    comparison = compare_images(ftp, web) if ftp is not None and web is not None else None
    processed = comparison["differs"] if comparison is not None else (True if ftp is not None else None)

    if forced is not None:
        source = forced
    elif ftp is not None and processed:
        source = SOURCE_FTP
    elif web is not None:
        source = SOURCE_WEB
    elif ftp is not None:
        source = SOURCE_FTP
    else:
        source = None

    result = {
        "source": source,
        "ftpCover": ftp_path,
        "ftpCoverProcessed": processed,
        "comparison": comparison,
        "appliedFlags": [],
        "image": None,
        "sourceFile": None,
    }
    if source == SOURCE_FTP:
        if ftp is None:
            raise SystemExit(f"{issue_id}: källan covers_ftp är vald men filen saknas i cachen")
        result["image"] = ftp
        result["sourceFile"] = str(ftp_file.relative_to(ai.REPO_ROOT))
    elif source == SOURCE_WEB:
        if web is None:
            raise SystemExit(f"{issue_id}: källan web_page1 är vald men filen saknas i cachen")
        # coverFlags beskriver webbens sidbild och tillämpas bara på den.
        result["image"] = apply_cover_flags(web, flags)
        result["appliedFlags"] = sorted(flags)
        result["sourceFile"] = str(web_file.relative_to(ai.REPO_ROOT))
    return result


def load_review(issue_ids: set) -> dict:
    """Granskningen av kontaktkartan. Okända nummer eller klasser ger fel."""
    if not REVIEW_PATH.exists():
        return {"default": "ok", "exceptions": {}}
    with open(REVIEW_PATH, encoding="utf-8") as fh:
        review = json.load(fh)
    for issue_id, entry in review["exceptions"].items():
        if issue_id not in issue_ids:
            raise SystemExit(f"cover_review.json: okänt issueId {issue_id}")
        if entry.get("class") not in REVIEW_CLASSES:
            raise SystemExit(f"cover_review.json: okänd klass för {issue_id}: {entry.get('class')}")
    return review


def build(config: dict, write: bool = True, log=sys.stderr) -> dict:
    listing = ai.load_listing(ai.REPO_ROOT / config["rawListingPath"])
    generated = ai.generate(config, listing)
    overrides = ai.load_overrides(ai.REPO_ROOT / config["overridesPath"])
    manifest = {}
    wanted_files = set()
    review = load_review({issue["issueId"] for issue in generated["inventory"]["issues"]})

    if write:
        COVERS_DIR.mkdir(parents=True, exist_ok=True)
    for issue in generated["inventory"]["issues"]:
        issue_id = issue["issueId"]
        diagnostics = generated["diagnostics"]["issues"][issue_id]
        forced = overrides.get(issue_id, {}).get("coverSource")
        chosen = choose_source(issue_id, diagnostics["ftpCoverPath"], forced, issue["coverFlags"])
        entry = {
            "source": chosen["source"],
            "sourceFile": chosen["sourceFile"],
            "sourceForced": forced is not None,
            "ftpCover": chosen["ftpCover"],
            "ftpCoverProcessed": chosen["ftpCoverProcessed"],
            "comparison": chosen["comparison"],
            "appliedFlags": chosen["appliedFlags"],
            "review": review["exceptions"].get(issue_id, {"class": review["default"]}),
            "asset": None,
        }
        if chosen["image"] is not None:
            thumbnail = make_thumbnail(chosen["image"])
            data = encode_webp(thumbnail)
            entry.update({
                "asset": asset_name(issue_id),
                "width": thumbnail.width,
                "height": thumbnail.height,
                "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            })
            target = COVERS_DIR / f"{issue_id}.webp"
            wanted_files.add(target.name)
            if write and (not target.is_file() or target.read_bytes() != data):
                target.write_bytes(data)
        manifest[issue_id] = entry

    if write:
        for stale in sorted(COVERS_DIR.glob("*.webp")):
            if stale.name not in wanted_files:
                stale.unlink()
                print(f"  tog bort {stale.name}", file=log)
        ai.write_text(MANIFEST_PATH, ai.dump_json({
            "thumbnailWidth": THUMBNAIL_WIDTH,
            "webpQuality": WEBP_QUALITY,
            "transformOrder": ["rotation", "crop", "scale"],
            "covers": manifest,
        }))
    return manifest


# --------------------------------------------------------------------------
# Kontaktkarta
# --------------------------------------------------------------------------

def short_label(issue_id: str) -> str:
    publication, rest = issue_id.split("-", 1)
    return ("DMZ " if publication == "datormagazin" else "HS ") + rest


def contact_sheet(manifest: dict, issue_ids: list[str], path: Path, columns: int = 15,
                  cell_width: int = 150) -> None:
    """Alla valda omslag som små bilder med issueId och källa."""
    cell_height = round(cell_width / 0.72)
    label_height = 30
    padding = 8
    rows = -(-len(issue_ids) // columns)
    sheet = Image.new("RGB", (columns * (cell_width + padding) + padding,
                              rows * (cell_height + label_height + padding) + padding), "#0E0F12")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default(size=12)
    for index, issue_id in enumerate(issue_ids):
        entry = manifest[issue_id]
        x = padding + (index % columns) * (cell_width + padding)
        y = padding + (index // columns) * (cell_height + label_height + padding)
        draw.rectangle((x, y, x + cell_width - 1, y + cell_height - 1), fill="#16181D", outline="#2A2C33")
        if entry["asset"] is not None:
            image = Image.open(COVERS_DIR / f"{issue_id}.webp")
            image.thumbnail((cell_width, cell_height), Image.LANCZOS)
            sheet.paste(image, (x + (cell_width - image.width) // 2, y + (cell_height - image.height) // 2))
        marker = {SOURCE_FTP: "C", SOURCE_WEB: "W", None: "-"}[entry["source"]]
        if entry["appliedFlags"]:
            marker += "*"
        draw.text((x, y + cell_height + 2), short_label(issue_id), fill="#ECE8DF", font=font)
        draw.text((x, y + cell_height + 15), marker, fill="#F5B53D", font=font)
    path.parent.mkdir(parents=True, exist_ok=True)
    # En palett med 256 färger håller filen liten nog att versionshantera.
    sheet.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG) \
        .save(path, optimize=True)


def summarize(manifest: dict) -> dict:
    values = list(manifest.values())
    return {
        "nummer": len(values),
        "bearbetat omslag från Covers": sum(v["source"] == SOURCE_FTP for v in values),
        "webbens sidbild 1, orörd": sum(v["source"] == SOURCE_WEB and not v["appliedFlags"] for v in values),
        "webbens sidbild 1, rättad": sum(v["source"] == SOURCE_WEB and bool(v["appliedFlags"]) for v in values),
        "utan bild": sum(v["source"] is None for v in values),
        "filer i Covers": sum(v["ftpCover"] is not None for v in values),
        "varav obearbetade kopior": sum(v["ftpCoverProcessed"] is False for v in values),
        "granskning: ok": sum(v["review"]["class"] == "ok" for v in values),
        "granskning: vridet uppslag": sum(v["review"]["class"] == "uppslag_vridet" for v in values),
        "granskning: annat": sum(v["review"]["class"] == "annat" for v in values),
        "miniatyrer, byte": sum(v.get("bytes", 0) for v in values),
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--review", action="store_true",
                        help="skriv även större kontaktkartor per år till cache/covers/review/")
    args = parser.parse_args(argv)

    config = ai.load_config()
    manifest = build(config)
    issue_ids = list(manifest)
    contact_sheet(manifest, issue_ids, CONTACT_SHEET_PATH)
    if args.review:
        by_year: dict = {}
        for issue_id in issue_ids:
            parts = issue_id.split("-")
            by_year.setdefault(f"{parts[0]}-{parts[1]}", []).append(issue_id)
        for key, ids in by_year.items():
            contact_sheet(manifest, ids, fetch_covers.CACHE_DIR / "review" / f"{key}.png",
                          columns=8, cell_width=230)

    for label, value in summarize(manifest).items():
        if label.endswith("byte"):
            print(f"{label[:-6]}: {value / 1e6:.1f} MB")
        else:
            print(f"{label}: {value}")
    print(f"Miniatyrer: {COVERS_DIR.relative_to(ai.REPO_ROOT)}/, "
          f"kontaktkarta: {CONTACT_SHEET_PATH.relative_to(ai.REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
