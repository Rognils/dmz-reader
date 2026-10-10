#!/usr/bin/env python3
"""DMZ Reader – litet sökindex i det riktiga formatet, för appens tester (FAS 9).

Filen app/src/debug/assets/search_test.db har samma tabeller och samma
tokenizer som dist/search.db (plan D2) och skrivs med samma kod, men innehåller
bara ett tiotal påhittade sidor. All text är skriven för testerna. Ingenting är
hämtat ur tidningarna, och så ska det förbli: filen versionshanteras och följer
med debug-bygget. Kontrollsumman står i tools/prepush_check.sh och måste läggas
dit på nytt när texten ändras. Den är liten, deterministisk och
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
        4: "Före jul introducerades nya Commodore 128 i butikerna. "
           "Den är tänkt som efterföljare till Commodore 64, som många redan har hemma.",
        6: "FLYGSPEL En helikopter och ett jaktplan turas om att försvara basen mot fienden.",
        25: "En handbok om C-64 för den som vill förstå maskinen. C-64 beskrivs kapitel för kapitel.",
    },
    "datormagazin-1989-06": {
        46: "C64 med bandspelare, två styrspakar och fem spel säljes. Pris 900 kr. Även en C128D med diskar.",
        47: "Säljes: C 64 med diskdrive 1541 och tio spel. Amiga 500 köpes.",
    },
    "datormagazin-1993-03": {
        12: "En tyst hårddisk för A500 som är lätt att montera själv. Passar även Amiga 1200.",
    },
    "datormagazin-1995-14": {
        20: "Fem saker att tänka på före köpet. Det finns flera turbokort att välja på till Amiga 1200.",
        26: "Nya högtalare gör stor skillnad för den som spelar ofta. "
            "Vi har lyssnat på sex par högtalare i redaktionens källare under en vecka.",
        27: "Högtalare för Amiga: ljudet är klart bättre än i de inbyggda. Bästa högtalare i testet.",
        28: "Den billigaste högtalaren i testet saknar bas. Amiga-ägare får nöja sig med mindre.",
        29: "Sammanfattning av testet: köp hellre ett par bra högtalare än ett nytt spel.",
    },
    "highscore-1993-17": {
        6: "Årets vinnare i TV-spel kommer från en liten stad i norr. Finalen hölls på ett museum i huvudstaden.",
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
