#!/usr/bin/env python3
"""DMZ Reader – bygger testindex för import av arkivindex i appen (FAS 7).

Utgår från det riktiga indexet (data/archive_inventory.json) och skriver varianter
till tools/testdata/. Filerna används för verifiering på telefonen och är inte
riktiga arkivindex: importera dem aldrig för vanlig användning.

    inventory_test_abc.json           giltigt index med tre ändringar:
        A  ett nytt, påhittat nummer (datormagazin-1996-01) med en miniatyr som
           inte finns i appen, omslag via webben och en befintlig PDF
        B  1986 nr 4 borttaget
        C  1995 nr 14 med ändrad ocrPdfSize och ETag, som om filen hade bytts
    inventory_broken_schema.json      schemaVersion 2
    inventory_broken_duplicate.json   samma issueId två gånger
    inventory_broken_related.json     relatedIssueId som pekar på ett nummer som inte finns
    inventory_broken_truncated.json   avbruten JSON

Kör:  python3 tools/make_test_inventories.py
"""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

import archive_inventory as ai

OUTPUT_DIR = ai.REPO_ROOT / "tools" / "testdata"
TEST_DATASET_VERSION = "2026-10-08-901"
NEW_ISSUE_ID = "datormagazin-1996-01"
REMOVED_ISSUE_ID = "datormagazin-1986-04"
CHANGED_ISSUE_ID = "datormagazin-1995-14"
# Det nya numret lånar omslag och PDF av riktiga nummer, så att de går att hämta.
COVER_FROM = "datormagazin-1995-15"
PDF_FROM = "datormagazin-1986-01"


def find(inventory: dict, issue_id: str) -> dict:
    return next(issue for issue in inventory["issues"] if issue["issueId"] == issue_id)


def build_abc(real: dict) -> dict:
    inventory = copy.deepcopy(real)
    inventory["datasetVersion"] = TEST_DATASET_VERSION
    inventory["generator"] = "tools/make_test_inventories.py (testindex, inte ett riktigt arkivindex)"

    # A: ett nytt nummer.
    cover = find(real, COVER_FROM)
    pdf = find(real, PDF_FROM)
    new_issue = copy.deepcopy(pdf)
    new_issue.update({
        "issueId": NEW_ISSUE_ID,
        "year": 1996,
        "issueLabel": "Nr 1",
        "sortKey": "1996-01",
        "sourceKey": "199601",
        "sourceNames": [],
        "relatedIssueId": None,
        "coverUrl": cover["coverUrl"],
        "coverFlags": [],
        # Finns inte i appens assets: appen ska falla tillbaka på coverUrl.
        "coverAsset": f"covers/{NEW_ISSUE_ID}.webp",
        "largePdfUrl": None,
        "largePdfSize": None,
        "hqSource": None,
        "scanPagePattern": None,
        "scanPageCount": None,
        "flags": [],
    })
    inventory["issues"].append(new_issue)

    # B: ett nummer försvinner.
    inventory["issues"] = [issue for issue in inventory["issues"] if issue["issueId"] != REMOVED_ISSUE_ID]

    # C: arkivfilen för ett nummer har bytts.
    changed = find(inventory, CHANGED_ISSUE_ID)
    changed["ocrPdfSize"] = changed["ocrPdfSize"] + 1_000
    changed["ocrPdfEtag"] = '"1791400000.0-%d-123456789"' % changed["ocrPdfSize"]

    inventory["issues"].sort(key=lambda issue: (issue["publicationId"], issue["sortKey"]))
    return inventory


def dump(inventory: dict) -> str:
    return json.dumps(inventory, ensure_ascii=False, indent=2) + "\n"


def build_all(real: dict) -> dict[str, str]:
    abc = build_abc(real)

    wrong_schema = copy.deepcopy(abc)
    wrong_schema["schemaVersion"] = 2

    duplicate = copy.deepcopy(abc)
    duplicate["issues"].insert(1, copy.deepcopy(duplicate["issues"][0]))

    related = copy.deepcopy(abc)
    find(related, NEW_ISSUE_ID)["relatedIssueId"] = "datormagazin-1999-99"

    text = dump(abc)
    return {
        "inventory_test_abc.json": text,
        "inventory_broken_schema.json": dump(wrong_schema),
        "inventory_broken_duplicate.json": dump(duplicate),
        "inventory_broken_related.json": dump(related),
        "inventory_broken_truncated.json": text[: len(text) // 2],
    }


def main() -> int:
    config = ai.load_config()
    with open(ai.REPO_ROOT / config["inventoryPath"], encoding="utf-8") as fh:
        real = json.load(fh)
    files = build_all(real)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, text in files.items():
        (OUTPUT_DIR / name).write_text(text, encoding="utf-8")
        print(f"{name}: {len(text) / 1000:.0f} kB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
