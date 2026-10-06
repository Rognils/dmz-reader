#!/usr/bin/env python3
"""DMZ Reader – HTTPS-kartläggning (FAS 0, punkt 2).

Kontrollerar med HEAD vilka filer webbplatsen dmzarkivet.se serverar för
varje nummer i rålistningen, och sparar svaren i data/https_probe.json.
Inga filer laddas ned: bara HEAD, med paus mellan anropen.

Med --offline används bara den sparade filen.
"""

from __future__ import annotations

import argparse
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

import archive_inventory as ai

BASE_URL = "https://dmzarkivet.se"
PAUSE_SECONDS = 0.6
PROBE_PATH = ai.REPO_ROOT / "data" / "https_probe.json"
KEEP_HEADERS = ("content-length", "content-type", "last-modified", "etag", "accept-ranges")

# Webbplatsens sidbilder ligger under olika prefix per publikation.
PAGE_PREFIX = {"datormagazin": "magazines", "highscore": "highscore"}


def pdf_url_path(identity: dict) -> str:
    return f"/pdf/{ai.make_source_key(identity)}.pdf"


def page_url_path(issue: dict, page: int) -> str:
    """Sidbild på webbplatsen för sida `page` i numrets huvuddel.

    Webbplatsen använder samma filnamn som Original Scans (även när de har
    tvåsiffriga sidnummer eller bara dubbelnumrets första nummer), men alltid
    med ändelsen .jpg.
    """
    identity = issue["identity"]
    key = ai.make_source_key(identity).removeprefix("HighScore-")
    scan = issue["scanPages"][(ai.MAIN_PART, page)]
    name = scan["path"].rsplit("/", 1)[1].rsplit(".", 1)[0] + ".jpg"
    return f"/{PAGE_PREFIX[identity['publicationId']]}/{key}/{name}"


def head(url_path: str) -> dict:
    time.sleep(PAUSE_SECONDS)
    request = urllib.request.Request(BASE_URL + urllib.request.quote(url_path), method="HEAD")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status, headers = response.status, response.headers
    except urllib.error.HTTPError as error:
        status, headers = error.code, error.headers
    result = {"status": status}
    for header in KEEP_HEADERS:
        if headers.get(header) is not None:
            result[header] = headers.get(header)
    return result


def probe(analysis: dict, extra_paths: list[str], known: dict) -> dict:
    """HEAD mot varje adress. Adresser som redan finns i `known` frågas inte om."""
    results = {}

    def cached_head(path: str) -> dict:
        return known[path] if path in known else head(path)

    issues = analysis["issues"]
    for index, (issue_id, issue) in enumerate(issues.items(), 1):
        identity = issue["identity"]
        last_page = len([1 for part, _ in issue["scanPages"] if part == ai.MAIN_PART])
        paths = [pdf_url_path(identity), page_url_path(issue, 1)]
        if last_page > 1:
            paths.append(page_url_path(issue, last_page))
        for path in paths:
            results[path] = cached_head(path)
        print(f"  [{index:3d}/{len(issues)}] {issue_id}: "
              + " ".join(str(results[p]["status"]) for p in paths), file=sys.stderr)
    for path in extra_paths:
        results[path] = cached_head(path)
    return {
        "baseUrl": BASE_URL,
        "method": "HEAD",
        "probedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "results": dict(sorted(results.items())),
    }


# Gissade adresser för material som webbplatsen inte länkar till. De ska ge 404,
# och finns med för att visa att materialet inte ligger på en uppenbar adress.
NEGATIVE_PROBES = [
    "/Covers/DMZ_1986-01-001.jpg",
    "/covers/DMZ_1986-01-001.jpg",
    "/Large PDF/198601-Original.pdf",
    "/OCR PDF/198601.pdf",
    "/Original Scans/198601-Original/DMZ_1986-01-001.jpg",
    "/pdf/198601-Original.pdf",
]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--offline", action="store_true", help="använd bara data/https_probe.json")
    parser.add_argument("--refresh", action="store_true",
                        help="fråga om alla adresser, även de som redan finns sparade")
    args = parser.parse_args(argv)

    config = ai.load_config()
    listing = ai.load_listing(ai.REPO_ROOT / config["rawListingPath"])
    analysis = ai.analyze(listing, config["roots"], config.get("extraRoots", []))

    if args.offline:
        data = ai.load_listing(PROBE_PATH)
    else:
        known = {}
        if PROBE_PATH.exists() and not args.refresh:
            known = ai.load_listing(PROBE_PATH)["results"]
        data = probe(analysis, NEGATIVE_PROBES, known)
        ai.write_json(PROBE_PATH, data)

    statuses = ai._count(r["status"] for r in data["results"].values())
    print(f"HTTPS-kartläggning ({data['probedAt']}): {len(data['results'])} adresser, status {statuses}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
