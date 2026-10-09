#!/usr/bin/env python3
"""DMZ Reader – mäter sidantalet i varje PDF i cachen (FAS 8B).

Sidantalet läses ur PDF:en med pdfinfo och skrivs, tillsammans med filens
storlek och SHA-256 ur cache/pdf/manifest.json, till
data/sample_measurements.json. Därifrån hämtar archive_inventory.py
`pageCount`, och bara om `ocrPdfSize` fortfarande stämmer.

Ett värde som redan finns ändras aldrig tyst: om en ny mätning ger ett annat
sidantal för samma fil rapporteras det, och ingenting skrivs.

Kör:  python3 tools/measure_page_counts.py
"""

from __future__ import annotations

import json
import sys

import build_search_index as bsi
import fetch_pdfs

MEASUREMENTS_PATH = bsi.REPO_ROOT / "data" / "sample_measurements.json"
COMMENT = ("Uppmätta sidantal ur OCR-PDF:erna i cache/pdf/ (pdfinfo), skrivna av tools/measure_page_counts.py. "
           "pageCount används bara om ocrPdfSize fortfarande stämmer med webbplatsens fil. "
           "sha256 sparas för spårbarhet och ingår inte i inventory.")


def measure(cache_dir=bsi.CACHE_DIR, existing: dict | None = None, log=print) -> tuple[dict, list[str]]:
    """Returnerar (mätvärden per issueId, avvikelser mot befintliga värden)."""
    existing = existing or {}
    manifest = fetch_pdfs.load_manifest(cache_dir)
    measurements, conflicts = {}, []
    for issue_id, file in sorted(manifest["files"].items()):
        if file.get("problems"):
            log(f"  {issue_id}: filen stämmer inte med inventory, mäts inte")
            continue
        pages = bsi.page_count(cache_dir / f"{issue_id}.pdf")
        old = existing.get(issue_id)
        if old and old["ocrPdfSize"] == file["size"] and old["pageCount"] != pages:
            conflicts.append(f"{issue_id}: tidigare uppmätt {old['pageCount']} sidor, nu {pages}")
        measurements[issue_id] = {"ocrPdfSize": file["size"], "pageCount": pages, "sha256": file["sha256"]}
    return measurements, conflicts


def main() -> int:
    current = json.loads(MEASUREMENTS_PATH.read_text(encoding="utf-8")) if MEASUREMENTS_PATH.exists() else {}
    measurements, conflicts = measure(existing=current.get("measurements"))
    if conflicts:
        print("AVVIKELSER, ingenting skrivet:\n  " + "\n  ".join(conflicts), file=sys.stderr)
        return 1
    MEASUREMENTS_PATH.write_text(json.dumps({"comment": COMMENT, "measurements": measurements},
                                            indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    print(f"{MEASUREMENTS_PATH}: {len(measurements)} nummer, {sum(m['pageCount'] for m in measurements.values())} sidor")
    return 0


if __name__ == "__main__":
    sys.exit(main())
