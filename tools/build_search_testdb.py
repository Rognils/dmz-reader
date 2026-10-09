#!/usr/bin/env python3
"""DMZ Reader – litet sökindex i det riktiga formatet, för appens tester (FAS 9).

Filen app/src/debug/assets/search_test.db har samma tabeller och samma
tokenizer som dist/search.db (plan D2) och skrivs med samma kod, men innehåller
bara ett tiotal påhittade sidor. Den är liten, deterministisk och
versionshanteras, till skillnad från det riktiga indexet.

Innehållet är valt för testerna:
  - sökord som finns på flera sidor och i flera nummer (Amiga, högtalare)
  - en fras ("Commodore 128") och skrivsätten C64, C-64 och C 64
  - ett nummer vars PDF-storlek avviker från inventory (bytt PDF)
  - ett nummer som inte finns i inventory (ska döljas i appen)

Kör:  python3 tools/build_search_testdb.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import build_search_index as bsi

OUTPUT = bsi.REPO_ROOT / "app" / "src" / "debug" / "assets" / "search_test.db"
BUILT_AT = "2026-10-09T00:00:00Z"

# issueId → {sida: text}
PAGES = {
    "datormagazin-1986-01": {
        4: "Lagom till julhandeln förra året introducerades Commodore 128 personal computer. "
           "En uppföljare till den gamla Commodore 64, som funnits fyra år i marknaden.",
        6: "INFILTRATOR Slå ihop helikoptersimulatorn Super Huey med luftstriderna i Sky Fox.",
        25: "The Anatomy of the C-64. For two years a best seller. C-64 internals with ROM listings.",
    },
    "datormagazin-1989-06": {
        46: "C64, bandsp, joyst, org spel. Pris 1500 kr. C128D, band 1530, joyst, tidn, disk.",
        47: "Säljes: C 64 med diskdrive 1541 och tio spel. Amiga 500 köpes.",
    },
    "datormagazin-1993-03": {
        12: "Trumpcard 500 AT. Sveriges mest prisvärda hårddisk för A500, snabb och tyst. Amiga 1200.",
    },
    "datormagazin-1995-14": {
        20: "Sju tips inför turboköpet. Till Amiga 1200 finns ett antal olika turbokort.",
        26: "Nya högtalare är ett bra sätt att få mer inlevelse när du spelar datorspel. "
            "Vi har testat sju högtalare och en ryggsäck för att se om reklamen stämmer.",
        27: "Högtalare för Amiga: ljudet är klart bättre än i de inbyggda. Bästa högtalare i testet.",
        28: "Den billigaste högtalaren i testet saknar bas. Amiga-ägare får nöja sig med mindre.",
        29: "Sammanfattning av testet: köp hellre ett par bra högtalare än ett nytt spel.",
    },
    "highscore-1993-17": {
        6: "Mikael Roth från Stockholm är europeisk mästare på TV-spel. Finalen hölls i Wiens tekniska museum.",
    },
    # Finns inte i inventory. Träffar här ska döljas i appen (plan D3).
    "datormagazin-1999-01": {
        3: "Amiga lever vidare. Högtalare och hårddisk i samma låda.",
    },
}

# Numret där sökindexet uppger en annan PDF än inventory.
OUTDATED_ISSUE = "datormagazin-1993-03"


def build(output: Path = OUTPUT) -> Path:
    inventory = json.loads(bsi.INVENTORY_PATH.read_text(encoding="utf-8"))
    by_id = {issue["issueId"]: issue for issue in inventory["issues"]}
    issues = []
    for issue_id, pages in PAGES.items():
        known = by_id.get(issue_id, {})
        count = known.get("pageCount") or max(pages)
        texts = [pages.get(number, "") for number in range(1, count + 1)]
        size = known.get("ocrPdfSize")
        if issue_id == OUTDATED_ISSUE:
            size -= 1000
        file = {"size": size, "etag": known.get("ocrPdfEtag"), "sha256": None}
        parts = [[text.split()] for text in texts]
        issues.append(({"issueId": issue_id}, file,
                       bsi.ExtractedIssue(issue_id, [text + "\n" if text else "" for text in texts], parts, count, 0.0, [])))
    bsi.build_database(output, issues, diacritics=0, dataset_version=inventory["datasetVersion"],
                       built_at=BUILT_AT, lexicon=frozenset())
    return output


def main() -> int:
    path = build()
    print(f"{path}: {path.stat().st_size} byte")
    return 0


if __name__ == "__main__":
    sys.exit(main())
