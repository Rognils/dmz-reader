# Fasrapport: FAS 3 – Läsare

> **Skärmbilderna som nämns här finns inte i repot.** De ligger lokalt hos projektets ägare, se [docs/skarmbilder.md](../skarmbilder.md).

Avslutad 2026-10-06. Verifierad på OnePlus 7 Pro (GM1911), Android 16 (API 36, LineageOS 23.2), skärm 1 440 × 3 120 px, debug-bygge.

## 1. Sammanfattning

- **Nedladdning:** läs-PDF:en hämtas till `cacheDir/pdf/` med egen HTTP-klient, återupptagning (Range och If-Range), kontroll av ledigt utrymme och möjlighet att avbryta. En `.pdf` är alltid komplett.
- **Läs-cache:** tak på 1,5 GB där de minst nyligen öppnade filerna rensas först.
- **Rendering:** `PdfRenderer` på en egen tråd. Förhandsbild, grundbild i skärmupplösning och, över 1,5 gångers zoom, ett högupplöst utsnitt av det som syns.
- **Läsaren:** helskärm, svep, tryckzoner, dubbeltryck och nypzoom upp till 8 gånger, panorering, topprad, miniatyrremsa ur PDF:en och reglage för snabbhopp. Uppslag i liggande läge med förskjutning som sparas.
- **Nummervyn:** knappen "Läs" öppnar läsaren direkt om filen finns i cachen, annars via en nedladdningsvy.

## 2. Testresultat

| Test | Hur | Resultat |
|---|---|---|
| JVM-tester | `./gradlew :app:testDebugUnitTest` | 130 av 130 |
| Instrumenterade tester på telefonen | `adb shell am instrument -w se.c64.dmzreader.test/androidx.test.runner.AndroidJUnitRunner` | 21 av 21 (plus 6 verifieringskörningar som hoppas över) |
| Verifieringskörningar | samma kommando med `-e dmzVerify true -e class se.c64.dmzreader.ReaderDeviceTest#<test>` | 6 av 6 |
| Python-tester | `.venv/bin/python -m unittest discover -s tools/tests` | 125 av 125 |
| Lint | `./gradlew :app:lintDebug` | 0 fel, 6 varningar |

JVM-testerna för nedladdningen kör mot en lokal testserver (MockWebServer): hel nedladdning, återupptagning med 206, server som svarar 200 på Range, fel längd, avbrott, för lite utrymme och rensning av cachen. Uppslagsparning och sidindex vid rotation testas för alla sidor i dokument med udda och jämnt sidantal.

Det instrumenterade testet `readDownloadTurnPageAndClose` går hela vägen: Läs → nedladdning av 1986 nr 1 → läsare → sidbyte med tryckzon och svep → stäng → öppna igen ur cachen.

## 3. Acceptanskriterier

- [x] **Ett helt nummer kan läsas från första till sista sidan.** 1986 nr 1 (28 sidor) och 1995 nr 14 (84 sidor) är genomlästa sida för sida på telefonen, där varje sida väntades in i full skärmupplösning.
- [x] **Zoom och panorering fungerar**, med skarp text från det högupplösta utsnittet. Bildfrekvensen under gesterna är inte uppmätt; se begränsningar.
- [x] **Rotation tappar inte sidan.** Stående sida 7 → liggande uppslag 6–7 → stående sida 7, verifierat på telefonen.
- [x] **Uppslagsläget visar rätt par.** 1986 nr 1: 6–7 med standardparningen, 7–8 efter förskjutning. Parningen 42–43 för 1995 nr 14 är testad i logiken men inte sedd på telefonen.
- [x] **Den största OCR-PDF:en går att läsa utan krasch eller minnesbrist.** 1995 nr 15 (563 MB): öppnad, 15 sidor bläddrade, maximal zoom med panorering. Alla 92 sidor är inte genomlästa.
- [x] **En avbruten nedladdning lämnar ingen trasig fil.** Avbrott vid 369 av 563 MB lämnade bara `.part`-filen.

## 4. Belastningstestet: 1995 nr 15 (563 MB)

1. Nedladdningen startades från nummervyn och avbröts efter cirka 16 s, vid 369 MB (66 %).
2. Kvar i cachen fanns `datormagazin-1995-15.pdf.part` (369 333 916 byte) och dess validerare. Ingen `.pdf`.
3. "Läs" igen: nedladdningen fortsatte från 74 % och blev klar. Den färdiga filen var exakt 563 123 977 byte.
4. Numret öppnades (92 sidor), 15 sidor bläddrades, och sidan zoomades till 8 gånger och panorerades.
5. Inga krascher och inget `OutOfMemoryError` i logcat.

Hastigheten var cirka 17–28 MB/s över wifi.

## 5. Mätningar

**Renderingstid** (median / 90:e percentilen / max, i ms):

| | 1986 nr 1 (v2, 15 MB) | 1995 nr 14 (äldre, 116 MB) | 1995 nr 15 (äldre, 563 MB) |
|---|---|---|---|
| Förhandsbild (visas först) | 69 / 103 / 119 | 209 / 325 / 439 | 374 / 695 / 720 |
| Grundbild i skärmupplösning | 107 / 121 / 226 | 828 / 1 003 / 1 420 | 1 856 / 2 004 / 2 639 |
| Högupplöst utsnitt vid zoom | 79 / 165 / 165 | 435 / 457 / 457 | 591 / 600 / 600 |
| Antal sidor mätta | 28 | 84 | 13 |

**Minne vid maximal zoom och panorering** (`dumpsys meminfo`):

| | 1986 nr 1 | 1995 nr 14 | 1995 nr 15 |
|---|---|---|---|
| PSS totalt | 412 MB | 378 MB | 381 MB |
| Native heap | 107 MB | 107 MB | 115 MB |
| Java heap | 21 MB | 31 MB | 24 MB |
| Grafik | 178 MB | 134 MB | 138 MB |
| Topp för native heap | 108 MB | 117 MB | 265 MB |
| Topp för Java heap | 31 MB | 29 MB | 30 MB |

Topparna är de högsta värden som mättes inifrån testet under körningen. Toppen på 265 MB för 1995 nr 15 uppmättes medan en sida renderades.

## 6. Problem som hittades och rättades under verifieringen

- **Minnet växte utan gräns.** Första mätningen visade 300 MB native heap för 1986 nr 1 och 660–815 MB (PSS cirka 1 GB) för 1995 nr 15, växande för varje sida. Tre orsaker:
  1. Bitmaps frigjordes inte förrän skräpsamlaren körde. Nu frigörs de uttryckligen, med räkning av vem som använder varje bild.
  2. En avbruten rendering lämnade kvar sin bild. Nu frigörs den.
  3. PDF-motorn sparar avkodade bilder för varje ritad sida. Nu öppnas filen om var fjärde sida (11–116 ms).
- **Utsnitt ur den äldre generationen blev aldrig klara vid maximal zoom.** Rendering i 8 gångers upplösning är mer än skanningarna innehåller. Nu renderas högst 3 gånger och förstoras därifrån.
- **Tryck kunde gå förlorade** medan en långsam sida laddades, eftersom gesthanteringen startades om när sidans storlek blev känd. Rättat.
- **Kön av renderingar fylldes** av sidor som redan hade bläddrats förbi. Nu hoppas de över, och sidor går före miniatyrer.

## 7. Skärmbilder

I `docs/screenshots/fas3/` (halv upplösning):

| Fil | Visar |
|---|---|
| `00-botten-*.png` | biblioteket, 1987 och 1993 rullade längst ned: sista raden ligger helt ovanför bottenfältet |
| `01-nedladdningsvy.png` | nedladdning av 1995 nr 15, "101,6 av 563,1 MB · 18 %" |
| `02-staende-sida.png` | stående sida i helskärm |
| `03-zoomad-sida-dubbeltryck.png` | 2,5 gångers zoom med skarp text |
| `03-zoomad-sida-maximal.png`, `03-zoomad-sida-199514.png` | 8 gångers zoom i båda generationerna |
| `04-kontroller-med-miniatyrremsa.png` | topprad, miniatyrremsa och reglage |
| `05-liggande-uppslag*.png` | uppslag 6–7, med kontroller, och förskjutet till 7–8 |
| `06-utan-nat.png` | meddelandet utan nät med "Försök igen" |
| `07-belastning-199515-*.png` | 1995 nr 15: sida 1 och maximal zoom |

Kontrollen av rutnätens nederkant (steg 1 d) visade att sista raden redan gick att rulla fram helt ovanför bottenfältet. Ingen kodändring gjordes.

## 8. Kända begränsningar

- **Sidbyten i den äldre generationen är långsamma:** 0,8 s i median för grundbilden och 1,9 s för 1995 nr 15. Förhandsbilden kommer efter 0,2–0,4 s. Planens mål "sidbyte känns omedelbart" (G7) nås bara för v2-generationen.
- **Bildfrekvensen under zoom och panorering är inte uppmätt.** Gesterna kördes av testverktyget, inte för hand.
- **Sida 1 i tolv tidiga nummer är ett vridet uppslag** i själva PDF:en. Läsaren visar PDF-sidan som den är.
- **Vid 8 gångers zoom syns skanningens upplösning**, tydligast i v2-generationen (150 dpi).
- **Att öppna ett nummer ur den äldre generationen tar 0,5–1,1 s extra**, för att läsa in alla sidors storlek.
- **Läspositionen finns bara i minnet** (FAS 4). Den överlever rotation men inte att läsaren stängs.
- **Mätningarna är gjorda i ett debug-bygge** på en telefon.
- **Toppraden har platshållare** för bokmärke och HQ, inaktiverade.

## 9. ADR:er

- 0011 PDF-rendering med PdfRenderer och högupplösta utsnitt: Accepted
- 0017 PDF-nedladdning, läs-cache och återupptagning: Accepted

## 10. Cachen på telefonen efteråt

563 MB-filen är borttagen. Kvar ligger tre nummer i läs-cachen (1986 nr 1, 1989 nr 6 och 1995 nr 14, 149 MB) och 1,5 MB miniatyrer.

## 11. Förslag inför FAS 4

1. **Spara läspositionen där `ReaderViewModel.onScreenShown` redan anropas.** `ReaderPosition` har sidan, och `IssueUserState` har fälten.
2. **Ladda nästa sida i förväg mer aggressivt för den äldre generationen**, till exempel ±2 sidor, om minnet tillåter. Det döljer mer av renderingstiden.
3. **Överväg ett release-bygge för mätning.** Debug-bygget är långsammare, och siffrorna ovan är därför en övre gräns.
4. **`observedPageCount` finns nu för öppnade nummer** och kan användas för progress enligt C10.
5. **Planens G7** bör justeras eller förses med en not om den äldre generationen.
