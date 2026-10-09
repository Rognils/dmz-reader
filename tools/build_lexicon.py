#!/usr/bin/env python3
"""DMZ Reader – bygger normaliseringens frysta ordlista (FAS 8B).

Ordlistan avgör tveksamma fall när searchText byggs: om bitarna "m ycket" är
ett utspärrat ord, och om "data-" + "program" ska bli "dataprogram". Den byggs
EN gång, ur alla nummer i PDF-cachen, och versionshanteras. Indexbygget läser
sedan bara filen och det aktuella numret.

Innehåll:
  - ord med minst tre tecken som står hela (utan ihopsättning) minst två
    gånger i arkivets textlager, räknat över alla nummer
  - med --dictionaries: grundformer med minst fyra bokstäver ur hunspell-
    ordlistorna sv_SE och en_US

En ny ordlista ändrar searchText. Den ska därför sparas under ett nytt
versionsnummer, tillsammans med att NORMALIZER_VERSION höjs i
build_search_index.py.

Kör:  python3 tools/build_lexicon.py            (kräver alla nummer i cache/pdf/)
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

import build_search_index as bsi
import fetch_pdfs

HUNSPELL = {"sv_SE": Path("/usr/share/hunspell/sv_SE.dic"), "en_US": Path("/usr/share/hunspell/en_US.dic")}
MIN_DICTIONARY_LENGTH = 4


def corpus_words(issue_ids: list[str], cache_dir: Path = bsi.CACHE_DIR, log=print) -> Counter:
    """Räknar ord som står hela i textlagret. Bygger på ordbitarnas lägen, inte på någon ordlista."""
    vocabulary: Counter = Counter()
    for index, issue_id in enumerate(issue_ids, 1):
        words = bsi.parse_bbox(bsi.run_pdftotext(cache_dir / f"{issue_id}.pdf", bsi.PDFTOTEXT_MODE, "-bbox"))
        bsi.count_vocabulary((bsi.page_parts(page) for page in words), vocabulary)
        if index % 25 == 0:
            log(f"  {index} av {len(issue_ids)} nummer lästa")
    return vocabulary


def dictionary_words(path: Path) -> set[str]:
    """Grundformerna i en hunspell-ordlista (raden före '/'), utan böjningar."""
    words = set()
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines()[1:]:
        word = line.split("/")[0].split("\t")[0].strip().lower()
        if len(word) >= MIN_DICTIONARY_LENGTH and word.isalpha():
            words.add(word)
    return words


def build_lexicon(vocabulary: Counter, dictionaries: list[set[str]]) -> list[str]:
    words = {word for word, count in vocabulary.items()
             if count >= bsi.MIN_VOCABULARY_COUNT and len(word) >= 3 and "\n" not in word}
    for dictionary in dictionaries:
        words |= dictionary
    return sorted(words)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("-o", "--output", type=Path, default=bsi.LEXICON_PATH)
    parser.add_argument("--dictionaries", action="store_true", help="ta med hunspell sv_SE och en_US")
    parser.add_argument("--allow-partial", action="store_true", help="bygg även om nummer saknas i cachen (bara för prov)")
    arguments = parser.parse_args(argv)
    inventory = json.loads(bsi.INVENTORY_PATH.read_text(encoding="utf-8"))
    manifest = fetch_pdfs.load_manifest(bsi.CACHE_DIR)
    wanted = [issue["issueId"] for issue in inventory["issues"] if issue.get("ocrPdfUrl")]
    usable = [issue["issueId"] for issue, _, _ in bsi.usable_issues(wanted, inventory, manifest, bsi.CACHE_DIR, log=lambda _: None)]
    if len(usable) != len(wanted) and not arguments.allow_partial:
        print(f"{len(wanted) - len(usable)} nummer saknas i cachen eller stämmer inte med inventory", file=sys.stderr)
        return 1
    if arguments.output.exists() and arguments.output == bsi.LEXICON_PATH:
        print(f"{arguments.output} finns redan. En ny ordlista ska ha ett nytt versionsnummer.", file=sys.stderr)
        return 1
    vocabulary = corpus_words(usable)
    dictionaries = [dictionary_words(path) for path in HUNSPELL.values()] if arguments.dictionaries else []
    lexicon = build_lexicon(vocabulary, dictionaries)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text("\n".join(lexicon) + "\n", encoding="utf-8")
    print(f"{arguments.output}: {len(lexicon)} termer ur {len(usable)} nummer"
          + (f" och {sum(map(len, dictionaries))} ordboksord" if dictionaries else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
