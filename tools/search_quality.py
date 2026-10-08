#!/usr/bin/env python3
"""DMZ Reader – granskningsunderlag för sökindexet (FAS 8B).

Underkommandon:
  sample   slumpar ett stratifierat stickprov av normaliseringens ändringar (före → efter)
  order    ritar läsordningen på en renderad sida, block för block, för visuell kontroll

Stickprovet skrivs till cache/review/normalizer_sample_v{N}.json. Bedömningen
(rätt, fel, osäker) förs in för hand och sparas i data/normalizer_review_v{N}.json.
"""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

import build_search_index as bsi
import search_pilot

REVIEW_DIR = bsi.REPO_ROOT / "cache" / "review"
SEED = 8

# Antal ändringar per grupp och slag. Summa 200.
STRATA = {
    ("äldre", "utspärrat"): 45, ("v2", "utspärrat"): 30, ("highscore", "utspärrat"): 25,
    ("v2", "avstavning"): 30, ("äldre", "avstavning"): 15, ("highscore", "avstavning"): 10,
    ("äldre", "mjukt bindestreck"): 25, ("highscore", "mjukt bindestreck"): 20,
}


def issue_changes(issue_id: str, lexicon) -> list[dict]:
    """Alla ändringar i ett nummer, med sida och omgivande text."""
    pdf = bsi.CACHE_DIR / f"{issue_id}.pdf"
    pages = bsi.parse_bbox(bsi.run_pdftotext(pdf, bsi.PDFTOTEXT_MODE, "-bbox"))
    result = []
    for number, words in enumerate(pages, 1):
        changes: list = []
        text = bsi.assemble(bsi.page_parts(words), lexicon, changes)
        cursor = 0
        for kind, before, after in changes:
            if not any(char.isalnum() for char in after):
                continue  # bara skiljetecken; påverkar inte sökningen
            position = text.find(after.replace(bsi.SOFT_HYPHEN, ""), cursor)
            if position < 0:
                position = text.find(after.replace(bsi.SOFT_HYPHEN, ""))
            cursor = max(cursor, position)
            context = text[max(0, position - 45):position + len(after) + 45] if position >= 0 else ""
            result.append({"issueId": issue_id, "pageNumber": number,
                           "kind": "avstavning" if kind == "bindestreck behållet" else kind,
                           "detail": kind, "before": before, "after": after, "context": context})
    return result


def sample(arguments) -> int:
    inventory = search_pilot.load_inventory()
    lexicon = bsi.load_lexicon()
    pools = defaultdict(list)
    issues = [issue for issue in inventory["issues"] if (bsi.CACHE_DIR / f"{issue['issueId']}.pdf").exists()]
    for index, issue in enumerate(issues, 1):
        for change in issue_changes(issue["issueId"], lexicon):
            pools[(search_pilot.generation(issue), change["kind"])].append(change)
        if index % 25 == 0:
            print(f"  {index} av {len(issues)} nummer lästa", file=sys.stderr)
    generator = random.Random(arguments.seed)
    chosen = []
    totals = {}
    for (group, kind), count in STRATA.items():
        pool = pools[(group, kind)]
        totals[f"{group} / {kind}"] = len(pool)
        for change in generator.sample(pool, min(count, len(pool))):
            chosen.append({"id": len(chosen) + 1, "group": group, **change, "verdict": None})
    REVIEW_DIR.mkdir(parents=True, exist_ok=True)
    target = REVIEW_DIR / f"normalizer_sample_v{bsi.NORMALIZER_VERSION}.json"
    target.write_text(json.dumps({"normalizerVersion": bsi.NORMALIZER_VERSION, "seed": arguments.seed,
                                  "populations": totals, "sample": chosen}, ensure_ascii=False, indent=1) + "\n",
                      encoding="utf-8")
    print(f"{target}: {len(chosen)} ändringar")
    for name, total in totals.items():
        print(f"  {name}: {total} ändringar i arkivet")
    return 0


# ---------------------------------------------------------------- order

def reading_blocks(words: list[bsi.Word]) -> list[tuple[float, float, float, float]]:
    """Slår ihop rader som följer direkt under varandra till block, i läsordning."""
    blocks: list[list[float]] = []
    previous = None
    for line in bsi.group_lines(words):
        if not any(char.isalnum() for word in line for char in word.text):
            continue
        box = [min(w.x_min for w in line), min(w.y_min for w in line),
               max(w.x_max for w in line), max(w.y_max for w in line)]
        height = box[3] - box[1]
        if previous is not None:
            below = -0.6 * height <= box[1] - previous[3] <= 1.6 * max(height, previous[3] - previous[1])
            overlap = min(box[2], blocks[-1][2]) - max(box[0], blocks[-1][0])
            if below and overlap > 0.3 * min(box[2] - box[0], blocks[-1][2] - blocks[-1][0]):
                blocks[-1] = [min(blocks[-1][0], box[0]), blocks[-1][1], max(blocks[-1][2], box[2]), box[3]]
                previous = box
                continue
        blocks.append(box)
        previous = box
    return [tuple(block) for block in blocks]


def render_order(issue_id: str, page: int, dpi: int = 62) -> Path:
    from PIL import Image, ImageDraw
    pdf = bsi.CACHE_DIR / f"{issue_id}.pdf"
    target = REVIEW_DIR / "order" / f"{issue_id}-p{page:03d}"
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["pdftoppm", "-r", str(dpi), "-f", str(page), "-l", str(page), "-png", "-singlefile",
                    str(pdf), str(target)], check=True)
    words = bsi.parse_bbox(bsi.run_pdftotext(pdf, "-raw", "-bbox", "-f", str(page), "-l", str(page)))[0]
    image = Image.open(target.with_suffix(".png")).convert("RGB")
    draw = ImageDraw.Draw(image)
    scale = dpi / 72
    for index, (x0, y0, x1, y1) in enumerate(reading_blocks(words), 1):
        draw.rectangle([x0 * scale, y0 * scale, x1 * scale, y1 * scale], outline=(220, 0, 0), width=1)
        label = str(index)
        draw.rectangle([x0 * scale, y0 * scale, x0 * scale + 6 * len(label) + 3, y0 * scale + 11], fill=(220, 0, 0))
        draw.text((x0 * scale + 2, y0 * scale), label, fill="white")
    image.save(target.with_suffix(".png"))
    return target.with_suffix(".png")


def order(arguments) -> int:
    print(render_order(arguments.issue, arguments.page, arguments.dpi))
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    sample_parser = commands.add_parser("sample")
    sample_parser.add_argument("--seed", type=int, default=SEED)
    sample_parser.set_defaults(function=sample)
    order_parser = commands.add_parser("order")
    order_parser.add_argument("issue")
    order_parser.add_argument("page", type=int)
    order_parser.add_argument("--dpi", type=int, default=62)
    order_parser.set_defaults(function=order)
    arguments = parser.parse_args(argv)
    return arguments.function(arguments)


if __name__ == "__main__":
    sys.exit(main())
