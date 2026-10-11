#!/usr/bin/env python3
"""DMZ Reader – egengjort testmaterial för de instrumenterade testerna.

Testerna får aldrig hämta från arkivservern (CLAUDE.md, regel 12). I stället
serverar en lokal server på telefonen de här två filerna:

  app/src/androidTest/assets/test_issue.pdf   ett påhittat nummer på 28 sidor
  app/src/androidTest/assets/test_cover.jpg   ett påhittat omslag, 950 × 1320 pixlar

Ingenting här kommer ur tidningarna. Texten är skriven för testerna, och bilden
är ritad av skriptet.

PDF:en skrivs för hand, utan komprimering, så att samma skript alltid ger samma
fil. Den fylls ut till cirka 300 kB med ett block som ingen sida använder, så att
en nedladdning går att avbryta mitt i och återuppta med Range. Kontrollsumman
står i tools/prepush_check.sh, som annars stoppar alla PDF:er.

Numret har 28 sidor, lika många som 1986 nr 1 har i arkivindexet. Testerna som
läser, bläddrar, sätter bokmärken och öppnar sökträffar i 1986 nr 1 gör det i den
här filen: testköraren (IsolatedTestRunner) lägger den i testkörningens läs-cache
under det numrets namn. Ingen arkivfil behövs för testsviten.

Kör:  python3 tools/make_test_media.py
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS = REPO_ROOT / "app" / "src" / "androidTest" / "assets"
PDF_PATH = ASSETS / "test_issue.pdf"
COVER_PATH = ASSETS / "test_cover.jpg"

PAGE_WIDTH, PAGE_HEIGHT = 595, 842
PADDING_BYTES = 300_000
COVER_SIZE = (950, 1320)

# En sida per post: rubrik och brödtext. Allt är påhittat.
PAGES = [
    ("TESTMAGASINET", ["Nummer 1 · Påhittat år", "", "Ett nummer som bara finns för appens tester.",
                       "Ingenting här är hämtat ur någon tidning."]),
    ("Innehåll", ["Sidan 3: Så byggde vi en testserver", "Sidan 4: Tjugoåtta sidor räcker långt",
                  "Sidan 5: Läsarnas frågor", "Sidorna 6–27: Rutor och ränder",
                  "Sidan 28: Nästa nummer"]),
    ("Så byggde vi en testserver", ["En liten server på telefonen svarar i arkivets ställe.",
                                    "Den skickar den här filen, bit för bit, och kan avbryta mitt i.",
                                    "Då får appen visa att nedladdningen går att återuppta."]),
    ("Tjugoåtta sidor räcker långt", ["Läsaren behöver en första sida, en sista sida och några däremellan.",
                                      "Med 28 sidor går det att bläddra framåt, bakåt och hoppa långt.",
                                "Ordet kvittenstest står bara på den här sidan."]),
    ("Läsarnas frågor", ["Fråga: Varför är texten så tråkig?", "Svar: För att den ska vara lätt att känna igen.",
                         "Fråga: Finns det fler nummer?", "Svar: Nej, bara det här."]),
]
# Sidorna 6–27 har samma korta text och var sitt mönster av rutor, så att de går att skilja åt.
PAGES += [(f"Rutor och ränder {number}", [f"Det här är sida {number} i testmagasinet.",
                                           "Rutorna nedanför är ritade av skriptet.",
                                           f"Mönster nummer {number - 5} av 22."])
          for number in range(6, 28)]
PAGES.append(("Nästa nummer", ["Det kommer inget nästa nummer.", "", "Slut på testmagasinet."]))


def _text(value: str) -> bytes:
    """En PDF-sträng i WinAnsi, med parenteser och bakstreck skyddade."""
    raw = value.encode("cp1252")
    return b"(" + raw.replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)") + b")"


def _page_stream(number: int, title: str, lines: list[str]) -> bytes:
    parts = [b"BT", b"/F1 30 Tf", b"60 760 Td", _text(title) + b" Tj", b"ET"]
    y = 700
    for line in lines:
        parts += [b"BT", b"/F1 14 Tf", b"60 %d Td" % y, _text(line) + b" Tj", b"ET"]
        y -= 26
    parts += [b"BT", b"/F1 11 Tf", b"60 50 Td", _text(f"Testmagasinet · sida {number} av {len(PAGES)}") + b" Tj", b"ET"]
    # En ram, så att sidan syns mot bakgrunden även som miniatyr.
    parts += [b"1.5 w", b"40 30 %d %d re S" % (PAGE_WIDTH - 80, PAGE_HEIGHT - 60)]
    if title.startswith("Rutor och ränder"):
        # Fyllda rutor i ett mönster som beror på sidnumret.
        parts.append(b"0.25 0.35 0.55 rg")
        for index in range(12):
            if (number * 7 + index * 5) % 3:
                parts.append(b"%d %d 100 100 re f" % (70 + (index % 4) * 115, 440 - (index // 4) * 125))
        parts.append(b"0 g")
    return b"\n".join(parts) + b"\n"


def _padding(size: int) -> bytes:
    """Bytes som inte upprepar sig, från en fast talföljd."""
    state = 20261011
    out = bytearray()
    while len(out) < size:
        state = (state * 1103515245 + 12345) & 0x7FFFFFFF
        out += state.to_bytes(4, "big")
    return bytes(out[:size])


def build_pdf() -> bytes:
    objects: list[bytes] = []

    def add(body: bytes) -> int:
        objects.append(body)
        return len(objects)

    catalog = add(b"")  # fylls i sist
    pages = add(b"")
    font = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    kids = []
    for number, (title, lines) in enumerate(PAGES, 1):
        stream = _page_stream(number, title, lines)
        content = add(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"endstream")
        kids.append(add(b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 %d %d] /Contents %d 0 R "
                        b"/Resources << /Font << /F1 %d 0 R >> >> >>" % (pages, PAGE_WIDTH, PAGE_HEIGHT, content, font)))
    padding = _padding(PADDING_BYTES)
    add(b"<< /Length %d >>\nstream\n" % len(padding) + padding + b"\nendstream")
    objects[catalog - 1] = b"<< /Type /Catalog /Pages %d 0 R >>" % pages
    objects[pages - 1] = (b"<< /Type /Pages /Count %d /Kids [" % len(kids)
                          + b" ".join(b"%d 0 R" % kid for kid in kids) + b"] >>")

    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for index, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % index + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n" % (len(objects) + 1)
    out += b"0000000000 65535 f \n"
    for offset in offsets:
        out += b"%010d 00000 n \n" % offset
    out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, catalog, xref)
    return bytes(out)


def build_cover(path: Path) -> None:
    from PIL import Image, ImageDraw

    width, height = COVER_SIZE
    image = Image.new("RGB", COVER_SIZE, (236, 226, 196))
    draw = ImageDraw.Draw(image)
    draw.rectangle([40, 40, width - 40, 300], fill=(28, 60, 110))
    draw.rectangle([40, 340, width - 40, height - 40], outline=(28, 60, 110), width=8)
    # Ränder och rutor i stället för text: inget typsnitt behövs, och bilden blir densamma överallt.
    for row in range(8):
        top = 400 + row * 100
        draw.rectangle([90, top, width - 90 - (row % 3) * 140, top + 36], fill=(200, 80, 50) if row % 2 else (60, 60, 60))
    for column in range(6):
        left = 90 + column * 130
        draw.rectangle([left, 110, left + 90, 230], fill=(236, 226, 196))
    image.save(path, "JPEG", quality=85, optimize=False, subsampling=2)


def main() -> int:
    ASSETS.mkdir(parents=True, exist_ok=True)
    pdf = build_pdf()
    PDF_PATH.write_bytes(pdf)
    build_cover(COVER_PATH)
    for path in (PDF_PATH, COVER_PATH):
        data = path.read_bytes()
        print(f"{path.relative_to(REPO_ROOT)}: {len(data)} byte, SHA-256 {hashlib.sha256(data).hexdigest()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
