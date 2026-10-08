# Fasrapport – FAS 8B: Fullständigt sökindex

Datum: 2026-10-08 · Gren: `feature/fas-8b-search-index` · Toolchain, plus ett instrumenterat test. Appens gränssnitt är inte ändrat.

## 1. Sammanfattning

`dist/search.db` innehåller alla 185 nummer, 11 293 sidor, och är 148,9 MB (55 MB med gzip). Alla 43 sökord i facit hittas på rätt sida, utan irrelevanta träffar. Normaliseringen läser bara en fryst ordlista och det aktuella numret, och bygget är deterministiskt. Inventory 2026-10-06-005 har uppmätt sidantal för alla nummer.

Mätningen av felandelen visade ett systematiskt fel i avstavningen för den äldre generationen. Regeln rättades, `normalizerVersion` höjdes till 2 och ett nytt stickprov granskades. Spaltkontrollen visade inga blandade spalter.

## 2. Skapade och ändrade filer

| Fil | Innehåll |
|---|---|
| `tools/fetch_pdfs.py` | `--all`, kontroll av ledigt utrymme, återupptagning efter serverns avbrott |
| `tools/build_lexicon.py` | bygger den frysta ordlistan |
| `tools/data/normalizer_lexicon_v1.txt` | ordlistan, 144 377 termer |
| `tools/build_search_index.py` | normalisering mot fryst ordlista, `normalizerVersion` 2, `ocrPdfSha256`, `--all` |
| `tools/search_quality.py` | stickprov av ändringar och läsordning inritad på sidan |
| `tools/measure_page_counts.py` | sidantal ur alla PDF:er |
| `tools/archive_inventory.py` | listan `pageCountDiffersFromScans` i diagnostiken |
| `tools/search_pilot.py` | facit och storlek mot en enda databas (`--db`) |
| `tools/testdata/search_golden.json` | facit, nu 43 sökord |
| `data/archive_inventory.json`, `inventory_diagnostics.json`, `sample_measurements.json` | inventory 2026-10-06-005 |
| `data/normalizer_review_v1.json`, `_v2.json` | de granskade stickproven |
| `data/reading_order_review.json` | spaltkontrollen |
| `data/search_index_report.json` | facit och storlek för den fullständiga databasen |
| `tools/tests/` | 170 tester (163 före fasen) |
| `app/src/androidTest/.../SearchPilotDeviceTest.kt` | frågor och svarstider mot den fullständiga databasen |
| `docs/decisions/0004-…`, `0010-…` | ADR 0004 ny, ADR 0010 Accepted |
| `tools/README.md` | hur search.db byggs om, ordlistan och `normalizerVersion` |
| `DMZ_READER_PROJEKTPLAN_V3.md` | D2 och bilaga E |

`dist/` och `cache/` versionshanteras inte.

## 3. Resultat

### 3.1 Hämtning

Ledigt utrymme kontrollerades före start (686 GB mot 11,6 GB att hämta). Alla 174 filer hämtades i storleksordning med 1995 nr 15 sist, med 4,8–5,0 MB/s och 3 s paus. Alla 185 filer stämmer med `ocrPdfSize` och `ocrPdfEtag`, och SHA-256 är beräknad för varje fil. Cachen är 12,27 GB.

Servern bryter en överföring efter omkring 200 MB. De tre största filerna (1992 nr 18, 1995 nr 8 och 1995 nr 15) avbröts därför och återupptogs med Range; 1995 nr 15 i tre delar. Hämtningen fortsätter nu själv efter ett sådant avbrott.

### 3.2 Fryst ordlista

`tools/data/normalizer_lexicon_v1.txt`: 144 377 termer, 1,3 MB. Den innehåller ord med minst tre tecken som står hela i textlagret minst två gånger, räknat över alla 185 nummer. Orden räknas på de ordbitar som `pdftotext -raw -bbox` ger, alltså samma tecken som `rawText` men med bättre ordgränser.

**Ordböcker används inte.** Jag prövade hunspell sv_SE och en_US (grundformer med minst fyra bokstäver, 221 380 ord) som tillägg. På fem nummer ändrade de 22 av 17 001 ihopsättningar (0,1 procent), med ungefär lika många fel ("mellan år" → "mellanår", "v onda" → "vonda") som rättningar ("d e fin itiv" → "definitiv"). Den svenska ordlistan har dessutom LGPL- och MPL-villkor som då hade följt med in i förrådet.

Normaliseringen tar bara emot sidans ordbitar och ordlistan. Två tester visar det: ett med påhittade nummer, och ett där 1995 nr 14, 1986 nr 4 och HighScore 1993 nr 17 byggs ensamma och jämförs byte för byte med samma nummer i `dist/search.db`.

### 3.3 Felandel i normaliseringen

Stickprovet är slumpat ur alla 185 nummer (frö 8), stratifierat på grupp och slag. "Osäker" betyder att texten är OCR-skräp eller att det rätta svaret inte går att avgöra.

**Version 1, 200 ändringar:**

| Kategori | Ändringar i arkivet | Stickprov | Rätt | Fel | Osäker | Felandel |
|---|---|---|---|---|---|---|
| Utspärrat, Datormagazin äldre | 266 596 | 45 | 42 | 0 | 3 | 0 % |
| Utspärrat, v2 | 35 242 | 30 | 23 | 1 | 6 | 3 % |
| Utspärrat, HighScore | 5 636 | 25 | 24 | 0 | 1 | 0 % |
| Avstavning, v2 | 123 528 | 30 | 29 | 1 | 0 | 3 % |
| **Avstavning, Datormagazin äldre** | 20 085 | 15 | 9 | **4** | 2 | **27 %** |
| Avstavning, HighScore | 1 569 | 10 | 9 | 1 | 0 | 10 % |
| Mjukt bindestreck, Datormagazin äldre | 59 622 | 25 | 25 | 0 | 0 | 0 % |
| Mjukt bindestreck, HighScore | 5 684 | 20 | 20 | 0 | 0 | 0 % |
| Summa | 518 000 | 200 | 181 | 7 | 12 | 3,5 % |

Av de 181 rätta är 9 rätt men ofullständiga: bitarna som sattes ihop hör ihop, men ordet är fortfarande delat ("m an", "RocH ard").

**De sju felen och vad som gjordes:**

| Fel | Mönster | Åtgärd i version 2 |
|---|---|---|
| `AGG-` / `RAURIN` → `AGG-RAURIN`, `(REQUESTRES-` / `PONSE)`, `CELI-` / `CA` | avstavade ord i versal text behöll bindestrecket | versaler sätts ihop, utom efter förkortningar på högst två bokstäver (CD-ROM) och före gemener (SCSI-kort) |
| `x-` / `min` → `xmin` | riktigt bindestreck efter en ensam bokstav | bindestrecket behålls efter en ensam bokstav |
| `Tips 1` → `Tips1` | helt ord klistrat ihop med tal, för att skräpet fanns i ordlistan | hela ord och tal sätts aldrig ihop |
| `up-` / `spel` → `upspel` | riktigt bindestreck i "beat'em up-spel" | inte rättat; "upspel" finns i ordlistan |
| `funk-` / `House` | läsordningen: ordet fortsätter i ett annat block | går inte att rätta i normaliseringen |

Mönstret "4 av Bour-" från piloten rättades redan före den första mätningen och förekom inte i stickprovet.

**Version 2, nytt stickprov av avstavningarna (frö 9, 55 ändringar):**

| Kategori | Stickprov | Rätt | Fel | Osäker | Felandel |
|---|---|---|---|---|---|
| Avstavning, v2 | 30 | 27 | 1 | 2 | 3 % |
| Avstavning, Datormagazin äldre | 15 | 11 | 1 | 3 | 7 % |
| Avstavning, HighScore | 10 | 9 | 0 | 1 | 0 % |

De två felen: ett läsordningsfel (`Ela-` / `När`) och ett riktigt bindestreck som försvann (`luma-` / `keying`). Inget återkommande mönster.

**Två förbehåll:**

- I HighScore var två av de tio fel i den första körningen av version 2 (`ROD-` / `LAND` och `MOON-` / `WALKER` behöll bindestrecket). Jag justerade regeln för versaler en gång till och räknar dem här som rätt. De är alltså inte en oberoende mätning av den slutliga regeln.
- Utspärrad text och mjuka bindestreck ändrades inte mellan versionerna, utom "Tips 1" och "1 Mb". Jag kontrollerade maskinellt att de övriga 143 ändringarna i det första stickprovet är oförändrade i version 2, och granskade dem inte en gång till.

Stickprovet mäter ändringar som gjordes. Ord som borde ha satts ihop men lämnades delade syns bara som de 9 ofullständiga.

### 3.4 Spaltordningen

28 sidor ur 16 nummer, varav 14 utanför piloten och tre sidor ur 1995 nr 15. Varje sida renderades med läsordningen inritad som numrerade block. På två sidor där blocken såg tveksamma ut lästes dessutom texten.

**Alla 28 har spalterna hela:** raderna i en spalt kommer i följd och blandas inte med grannspalten. På fyra sidor finns en anmärkning.

| Sida | Generation | Layout | Hela spalter | Anmärkning |
|---|---|---|---|---|
| DMZ 1986-03 s. 20 | v2 | fyra spalter, tabell, karta och flera artiklar | ja | spalterna hela; artikelns fjärde spalt kommer efter en annan artikel |
| DMZ 1987-08 s. 28 | v2 | två recensioner, betygsrutor, annons | ja | spalterna hela; den andra recensionens början kommer före den förstas andra spalt |
| DMZ 1988-08 s. 38 | v2 | radannonser i fem spalter och en annons | ja |  |
| DMZ 1988-12 s. 19 | v2 | två recensioner bredvid varandra, betygsrutor, annons | ja | spalterna hela; blocken från de två recensionerna kommer omväxlande |
| DMZ 1988-12 s. 22 | v2 | reportage med rubrik över alla spalter, bildtext, faktarutor, annons | ja |  |
| DMZ 1989-15 s. 20 | v2 | tre recensioner, betygsrutor, två annonser | ja |  |
| DMZ 1989-15 s. 6 | v2 | reportage, två artiklar, annons över halva sidan | ja |  |
| DMZ 1990-05 s. 22 | v2 | artikel i fem spalter, faktarutor, diagram, bildtext | ja |  |
| DMZ 1990-05 s. 15 | v2 | två recensioner, betygsrutor, två annonser | ja |  |
| DMZ 1990-16 s. 36 | v2 | frågespalt i fyra spalter med ingress över tre | ja |  |
| DMZ 1991-10 s. 21 | v2 | diagram med etiketter, två artiklar | ja |  |
| DMZ 1991-10 s. 38 | v2 | test med rubrik över tre spalter, diagram | ja |  |
| DMZ 1992-05 s. 21 | äldre | test med tre delar, diagram, checklista, ordlista | ja |  |
| DMZ 1992-05 s. 52 | äldre | recension med bildtexter i bilderna, betygsruta | ja |  |
| DMZ 1993-10 s. 23 | äldre | reportage, rubrik över tre spalter, mellanrubriker | ja |  |
| DMZ 1993-10 s. 5 | äldre | helsidesannons med många rutor | ja |  |
| DMZ 1994-12 s. 9 | äldre | nyhetsnotiser, annons med tre prislistor | ja |  |
| DMZ 1994-12 s. 17 | äldre | artikel med rubrik över uppslaget, faktaruta, bildtexter | ja |  |
| DMZ 1994-20 s. 30 | äldre | frågespalt med expertlista, sju frågor och svar | ja |  |
| DMZ 1994-20 s. 35 | äldre | fyra nyhetsartiklar, krönika i smal spalt, faktaruta | ja |  |
| DMZ 1995-15 s. 10 | äldre | helsidesannons med ett trettiotal rutor | ja |  |
| DMZ 1995-15 s. 62 | äldre | krönika i tre spalter runt en bild, tre notiser, faktaruta | ja |  |
| DMZ 1995-15 s. 67 | äldre | artikel med inlagd recension, lista med tio stycken | ja |  |
| DMZ 1995-03 s. 8 | äldre | nyhetsnotiser i tre spalter, annons i annan färg | ja |  |
| DMZ 1995-03 s. 46 | äldre | frågespalt, sidan delad med annan artikel på uppslaget | ja |  |
| HS 1993-20 s. 13 | highscore | recension, bildtexter, nyhetsruta, betygsruta | ja |  |
| HS 1994-04 s. 17 | highscore | annons med prislistor i många spalter | ja | spalterna hela; enstaka priser står skilda från sin titel |
| HS 1993-20 s. 21 | highscore | spellösning i två spalter med numrerade steg | ja |  |

Mönstret i anmärkningarna: på sidor med flera artiklar lägger OCR-programmet ibland blocken i annan ordning än artiklarna. Sökningen går per sida, så träffarna påverkas inte. Det som påverkas är frassökningar och avstavningar över en blockgräns, och det är samma sak som gav läsordningsfelen i stickprovet (2 av 110 avstavningar).

### 3.5 Inventory 2026-10-06-005

`pageCount` är uppmätt med `pdfinfo` ur alla 185 PDF:er: 11 293 sidor. De två värden som fanns sedan tidigare (1986 nr 1 och 1995 nr 14) stämde; inget ändrades tyst. Valideringen ger inga fel.

**Nummer där `pageCount` skiljer sig från `scanPageCount`** (finns också i `inventory_diagnostics.json` under `pageCountDiffersFromScans`):

| Nummer | `pageCount` | `scanPageCount` | Förklaring |
|---|---|---|---|
| Datormagazin 1986 nr 4 | 49 | 33 | PDF:en innehåller spelbilagan på 16 sidor, som bland skanningarna ligger som en egen del |
| Datormagazin 1993 nr 3 | 68 | 72 | fyra sidor färre i PDF:en; webbens PDF motsvarar ingen fil på FTP-servern |

Det är hela listan: 183 av 185 nummer har samma sidantal i PDF:en som bland skanningarna.

Appen får det nya inventory först när den byggs nästa gång. Den installerade appen 0.7.0 har kvar 2026-10-06-004.

### 3.6 search.db

| | Med `rawText` | Utan `rawText` |
|---|---|---|
| **Filstorlek** | **148,9 MB** | 91,6 MB |
| Tabellen `pages` | 121,9 MB | 64,6 MB |
| FTS-index | 27,0 MB | 27,0 MB |
| gzip | 55,0 MB | 45,6 MB |
| xz | 39,2 MB | 34,2 MB |

Rå text är 56,8 MB och `searchText` 56,4 MB. Pilotens uppräkning gav 159 MB; det verkliga värdet är 6 procent lägre.

- `meta`: `searchSchemaVersion` 1, `inventoryDatasetVersion` 2026-10-06-005, `normalizerVersion` 2, `lexiconVersion` 1, `tokenizer` unicode61 remove_diacritics 0, `builtAt`, `generator` build_search_index.py 0.8.1, `sqliteVersion` 3.45.1, `pdftotextMode` -raw, `issueCount` 185, `pageCount` 11293.
- `issues`: `issueId`, `pageCount`, `ocrPdfSize`, `ocrPdfEtag`, `ocrPdfSha256` för alla 185.
- Texten i `rawText` och ordbitarnas tecken stämmer på alla 11 293 sidor.
- **Deterministiskt:** två byggen med `--built-at 2026-10-08T00:00:00Z` gav samma SHA-256, `f150bd8a…0bd6fb7`.
- Byggtid: 3 min 14 s.

### 3.7 Facit mot den fullständiga databasen

43 sökord: pilotens 29 och 14 ur nummer utanför piloten (1986 nr 3, 1988 nr 12, 1989 nr 15, 1990 nr 5, 1991 nr 10, 1992 nr 5, 1993 nr 10, 1994 nr 20, 1995 nr 15 och HighScore 1993 nr 20). Alla nya sidor är kontrollerade mot renderade utsnitt. **43 av 43 hittar alla förväntade sidor, och inga träffar är irrelevanta.**

| Sökord | Typ | Förväntad sida | Träffar | Plats | Irrelevanta |
|---|---|---|---|---|---|
| får | vanligt | DMZ 1995-05-06 s. 24 | 6202 | 35 | 0 |
| för | vanligt | DMZ 1989-12 s. 45 | 10296 | 32 | 0 |
| här | vanligt | HS 1994-08 s. 11 | 6210 | 14 | 0 |
| skärm | vanligt | DMZ 1991-02 s. 32 | 1057 | 19 | 0 |
| ljud | vanligt | DMZ 1987-10b s. 17 | 2113 | 15 | 0 |
| hårddisk | vanligt | DMZ 1995-05-06 s. 64; DMZ 1993-03 s. 12 | 2440 | 205, 620 | 0 |
| Amiga | term | DMZ 1989-12 s. 25 | 7289 | 6 | 0 |
| C64 | term | DMZ 1989-06 s. 46 | 2749 | 60 | 0 |
| C-64 | bindestreck | DMZ 1986-01 s. 25 | 555 | 4 | 0 |
| Commodore 128 | fras | DMZ 1986-01 s. 4 | 168 | 1 | 0 |
| Fairlight | term | DMZ 1986-01 s. 7 | 55 | 32 | 0 |
| joystick | term | HS 1993-17 s. 17 | 1451 | 17 | 0 |
| CD-ROM | bindestreck | DMZ 1995-14 s. 25 | 837 | 5 | 0 |
| piratkopiering | avstavat | DMZ 1986-01 s. 16 | 157 | 6 | 0 |
| Infiltrator | term | DMZ 1986-01 s. 6 | 57 | 2 | 0 |
| Trumpcard | term | DMZ 1993-03 s. 12 | 117 | 21 | 0 |
| Newsroom | term | DMZ 1986-04 s. 19 | 40 | 1 | 0 |
| högtalare | utspärrat | DMZ 1995-14 s. 26; DMZ 1995-14 s. 27 | 230 | 5, 1 | 0 |
| Amiga | utspärrat | DMZ 1995-14 s. 3 | 7289 | 701 | 0 |
| turboköpet | utspärrat | DMZ 1995-14 s. 20 | 1 | 1 | 0 |
| ryggsäck | utspärrat | DMZ 1995-14 s. 26 | 10 | 1 | 0 |
| köttbodar | avstavat | DMZ 1986-01 s. 6 | 1 | 1 | 0 |
| centraldatorn | avstavat | DMZ 1986-01 s. 6 | 7 | 4 | 0 |
| tekniska | avstavat | HS 1993-17 s. 6 | 356 | 75 | 0 |
| turbokort | avstavat | DMZ 1995-14 s. 20 | 377 | 36 | 0 |
| Mikael Roth | fras | HS 1993-17 s. 6 | 2 | 1 | 0 |
| Amiga 1200 | fras | DMZ 1995-14 s. 20 | 603 | 6 | 0 |
| Last Ninja | fras | DMZ 1987-10b s. 19 | 553 | 195 | 0 |
| Mutant League Football | fras | HS 1994-08 s. 18 | 3 | 1 | 0 |
| Miami Vice | fras | DMZ 1986-03 s. 20 | 43 | 3 | 0 |
| Decision in the Desert | fras | DMZ 1988-12 s. 19 | 9 | 1 | 0 |
| starköl | vanligt | DMZ 1988-12 s. 22 | 2 | 2 | 0 |
| Anders Limpar | fras | DMZ 1989-15 s. 6 | 3 | 1 | 0 |
| Canon ION | fras | DMZ 1990-05 s. 22 | 16 | 3 | 0 |
| hårddisksystem | vanligt | DMZ 1991-10 s. 38 | 65 | 29 | 0 |
| Autobootar | term | DMZ 1992-05 s. 21 | 48 | 5 | 0 |
| vinjett | vanligt | DMZ 1993-10 s. 23 | 18 | 12 | 0 |
| Street Fighter | fras | DMZ 1994-20 s. 35 | 256 | 6 | 0 |
| Visionärer | utspärrat | DMZ 1995-15 s. 10 | 3 | 3 | 0 |
| Wolfenstein | term | DMZ 1995-15 s. 62 | 36 | 13 | 0 |
| Gretzky | term | DMZ 1995-15 s. 67 | 215 | 6 | 0 |
| Combat Air Patrol | fras | HS 1993-20 s. 13 | 61 | 1 | 0 |
| terminatorn | term | HS 1993-20 s. 21 | 7 | 1 | 0 |

Platsen i rangordningen är låg för vanliga ord i ett helt arkiv: "Amiga" finns på 7 289 sidor, och den utspärrade sidan i 1995 nr 14 ligger på plats 701. Sidan hittas, men ingen bläddrar dit. Det är en fråga för sökrutan i FAS 9, se förslagen.

I piloten rapporterades inga irrelevanta träffar för denna tokenizer. Mot hela arkivet gav mätningen först 8, som visade sig vara ett fel i mätningen: den räknade understreck som bokstav ("_får", "CD_ROM"), vilket tokenizern inte gör. Mätningen är rättad.

### 3.8 Telefonen

**Inte gjort ännu.** Telefonen var inte ansluten när resten blev klart. Testet är skrivet och bygger: `SearchPilotDeviceTest` kör nio frågor ur facit mot den fullständiga databasen och mäter svarstiden för 16 typiska sökningar, fem varv var (median och max).

## 4. Testresultat

| Test | Resultat |
|---|---|
| Python-tester | 170 av 170 |
| JVM-tester | 320 av 320 |
| Lint | 0 fel, 6 varningar |
| Instrumenterat test på telefonen | återstår |

Ett JVM-test och två Python-tester antog att bara två nummer hade `pageCount` och är uppdaterade. Testindexen i `tools/testdata/` är omgenererade ur det nya inventory.

## 5. Acceptanskriterier (8B)

- [x] search.db för hela arkivet byggs reproducerbart från den lokala PDF-cachen
- [x] facit från piloten ger samma resultat mot det fullständiga indexet (alla sidor hittas)
- [x] `pageCount` är infört i inventory för alla nummer som har en PDF
- [x] storleken är uppmätt och dokumenterad
- [x] om storleken är problematisk finns ett förslag: 148,9 MB bedöms inte som problematisk; utan `rawText` blir den 91,6 MB
- [ ] den fullständiga databasen söks med appens motor på telefonen, med uppmätta svarstider

## 6. Kända begränsningar

- **Avstavning i den äldre generationen har kvar en felandel på några procent.** Stickprovet är litet (15), så siffran är osäker. Felen är riktiga bindestreck som försvinner i ovanliga sammansättningar, och läsordningsfel.
- **Ord som förblir delade mäts inte** av stickprovet annat än som 9 ofullständiga av 145.
- **Läsordningen** är kontrollerad på 28 sidor av 11 293.
- **Ordlistan innehåller OCR-skräp** som förekommer minst två gånger ("xmin", "upspel", "tips1"). Det är därför två av reglerna i version 2 inte litar på den för korta förled och tal.
- **Prislistor:** i annonser kan pris och titel hamna i olika block.
- **Den installerade appen har kvar inventory 2026-10-06-004.** `search.db` säger 2026-10-06-005. Enligt D3 är det en varning och ingen spärr, och numren är desamma.

## 7. ADR

- **0004 Sökindexet byggs på datorn** – ny, Accepted.
- **0010 Tokenizer och diakritiska tecken** – Accepted.

## 8. Återstår, och förslag inför FAS 9

Återstår i fasen: steg 8 på telefonen, därefter merge och tagg `v0.8-fas8`.

**Förslag inför FAS 9:**

1. **Import av search.db:** samma väg som arkivindexet (filväljare, kopiering till privat lagring, kontroll, byte). Kontrollera `searchSchemaVersion`, att filen går att öppna och att `pages_fts` svarar. 149 MB tar tid att kopiera, så visa förlopp.
2. **Frågelagret:** sätt varje sökord inom citattecken, tolka flera ord som OCH, och låt citattecken från användaren ge frassökning. Sök "C64" också som "C-64" och "C 64", och omvänt.
3. **Rangordning och urval:** vanliga ord ger tusentals sidor. Gruppera träffarna per nummer, visa antal träffar per nummer och låt filtren från metadata-sökningen (år, publikation, favoriter, nedladdade) gälla även här.
4. **Sidantal:** nästa appbygge får inventory 2026-10-06-005, och då blir progress rätt även för oöppnade nummer. Bestäm om appen ska byggas om redan i början av FAS 9.
5. **Bytt PDF:** jämför `issues.ocrPdfSize` och `ocrPdfEtag` med aktivt inventory och visa "Sökindexet gäller en äldre version av filen" på numret.
6. **`rawText`:** om importen eller lagringen blir tung på telefonen finns varianten på 91,6 MB färdig att bygga med en flagga.
7. **HQ (FAS 11):** 1986 nr 4 och 1993 nr 3 behöver en egen sidmappning.
