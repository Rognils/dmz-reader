# Fasrapport – FAS 8A: Pilot för sökindex

Datum: 2026-10-08 · Gren: `feature/fas-8a-search-pilot` (inte mergad) · Endast toolchain, plus ett instrumenterat test.

## 1. Sammanfattning

Hela kedjan från PDF till sökbar `search.db` fungerar på 11 nummer (585 sidor, 656 MB PDF). Båda tokenizer-varianterna hittar alla 29 sökord i facit på rätt sida, även de utspärrade orden i 1995 nr 14. Varianten `remove_diacritics 0` ger inga irrelevanta träffar, varianten med 2 ger 155. Hela arkivet beräknas ge en `search.db` på cirka 160 MB (60 MB komprimerad). Pilotens databas öppnades och söktes med appens motor på telefonen.

**Slutkontroll av telefonen (steg 0):** appen 0.7.0 startades en gång. Databasen gick från version 6 till 7 utan fel i logcat, `Bookmark` fick kolumnerna `bookmarkId` och `deletedAt`, biblioteket visar 171 + 14 = 185 nummer och inställningarna visar index 2026-10-06-004 (ingen importerad fil finns). Användardata: 0 rader före och efter.

## 2. Skapade och ändrade filer

| Fil | Innehåll |
|---|---|
| `tools/fetch_pdfs.py` | hämtning till `cache/pdf/`, med manifest |
| `tools/build_search_index.py` | textutvinning, normalisering och `search.db` |
| `tools/search_pilot.py` | lägesjämförelse, facit, storlek, sidantal, underlag för 8B |
| `tools/testdata/search_golden.json` | facit, 29 sökord |
| `tools/testdata/search_lines.json` | verkliga rader med ordlägen, för testerna |
| `tools/tests/test_fetch_pdfs.py`, `test_search_index.py` | 34 nya tester |
| `data/search_pilot.json` | alla mätresultat |
| `app/src/androidTest/.../SearchPilotDeviceTest.kt` | pilotens databas i appens motor (körs bara med `-e dmzSearchPilot true`) |
| `docs/decisions/0010-tokenizer-och-diakritiska-tecken.md` | ADR, Proposed |
| `tools/README.md` | nytt avsnitt "Sökindex" |
| `DMZ_READER_PROJEKTPLAN_V3.md` | C5, C7, D4, FAS 8 delad i 8A och 8B, bilaga E |

## 3. Resultat

### 3.1 Hämtning

11 filer, 656 MB. Två fanns redan på datorn sedan FAS 0 (1986 nr 1 och 1995 nr 14); de togs in efter kontroll av storlek och en HEAD-fråga om ETag, så bara 9 filer (525 MB) hämtades. Alla stämmer med `ocrPdfSize` och `ocrPdfEtag` i inventory. 1995 nr 15 hämtades inte.

Hastigheten låg på 5,1–5,2 MB/s, eftersom gränsen var satt till 5 MiB/s. Den är nu 5 000 000 byte/s.

### 3.2 Textutvinningsläge: `-raw`

| Sida | Standard | `-layout` | `-raw` |
|---|---|---|---|
| 1986 nr 1 s. 6 (v2) | `nätter och utsett det till hotar Jorden. Din uppgift i "V"` / `att som Mike Donovan, hjälten` / `Screen Star! De ger är` | `nätter och utsett det till hotar Jorden. Din uppgift i "V" styck~n tidsinställda sprängladd- mellan olika våningsplan med` | `nätter och utsett det till` / `Screen Star! De ger` / `också en snabplektion i` / `konsten att spela "V - the` |
| 1993 nr 3 s. 26 (äldre) | `CLI-baserad` / `Fast` / `Optimizer` / `för` / `AmigaDOS-disketter. Kan optimera en` | `CLI-baserad Fast Optimizer för ‘ .f - -ii från början, av ett skrivas ut, v` | `CLI-baserad Fast Optimizer för` / `AmigaDOS-disketter. Kan optimera en` / `disk på mindre än 2min 30 sek. Kan` |
| HighScore 1993 nr 17 s. 6 | `första pris, en Mega CD II samt Formel 1stjärnan och prisutdelaren Damon Hills` | `första pris, en Mega CD II samt Formel 1-` / `stjärnan och prisutdelaren Damon Hills` (bildtexten i grannspalten hamnar på samma rader längre upp) | `första pris, en Mega CD II samt Formel 1-` / `stjärnan och prisutdelaren Damon Hills` |

- **Standardläget** blandar spalter i v2-filerna ("utsett det till hotar Jorden"), delar marginaljusterade rader i ett ord per rad i den äldre generationen och tar själv bort bindestreck vid radslut, även riktiga ("Formel 1stjärnan").
- **`-layout`** lägger grannspalternas rader bredvid varandra, så att ord från två eller tre spalter hamnar i samma mening.
- **`-raw`** följer den ordning OCR-programmet skrev texten i. Spalterna hålls ihop på alla tre sidorna.

`-raw` har dessutom en egenskap som normaliseringen bygger på: `pdftotext -raw -bbox` ger exakt samma tecken i samma ordning, med varje ordbits läge. Bygget kontrollerar det för varje sida; i piloten stämde 585 av 585.

### 3.3 Normalisering, före och efter

| | rawText (som det står i PDF:ens textlager) | searchText |
|---|---|---|
| Utspärrat, 1995 nr 14 s. 26 | `N y a h ö g ta la re ä r e tt b ra s ä tt a tt få m e r in le v e ls e n ä r d u s p e la r d a to rs p e l.` | `Nya högtalare är ett bra sätt att få mer inlevelse när du spelar datorspel.` |
| Utspärrat, 1995 nr 14 s. 20 | `S ju tip s in fö r tu r b o k ö p e t` | `Sju tips inför turboköpet` |
| Mjukt bindestreck, samma sida | `olika turbo­` / `kort. Tyvärr` | `olika turbokort. Tyvärr` |
| Mjukt bindestreck, HighScore 1993 nr 17 s. 6 | `i Wiens teknis­` / `ka museum` | `i Wiens tekniska museum` |
| Avstavning, 1986 nr 1 s. 6 | `fylla sina kött-` / `bodar.` | `fylla sina köttbodar.` |
| Riktigt bindestreck, 1995 nr 14 s. 12 | `köpa hem en CD-` / `ROM med över 4400 spel` | `köpa hem en CD-ROM med över 4400 spel` |
| Riktigt bindestreck, HighScore 1993 nr 17 s. 6 | `Formel 1-` / `stjärnan` | `Formel 1-stjärnan` |
| Tätt satt vanlig text, 1987 nr 10b | `I den står det`, `Last Ninja 149:-` | oförändrat |

Utspärrad text avgörs med ordbitarnas lägen: bitar som ligger tätare än hälften av radens största ordmellanrum hör ihop. En följd sätts ihop om resultatet är ett känt ord, eller om den är tydligt utspärrad (minst tre bitar, de flesta på ett eller två tecken). En följd där varje bit redan är ett helt ord lämnas alltid orörd. Ordlistan byggs av de ord som stod hela i samma körning.

Antal ihopsatta följder per nummer: 149–672 i v2-numren, 2 134–6 470 i Datormagazin 1993–1995 och 201–1 162 i HighScore. Utspärrningen finns alltså i hela den äldre generationen, inte bara på enstaka sidor i 1995 nr 14.

### 3.4 Facit, båda varianterna

Kolumnerna är antal träffar (sidor), den förväntade sidans plats i rangordningen och antal irrelevanta träffar (sidor där ordet inte står som det stavas). DMZ = Datormagazin, HS = HighScore.

| Sökord | Typ | Förväntad sida | rd 0: träffar | plats | irrel. | rd 2: träffar | plats | irrel. |
|---|---|---|---|---|---|---|---|---|
| får | vanligt | DMZ 1995-05-06 s. 24 | 349 | 1 | 0 | 356 | 3 | 7 |
| för | vanligt | DMZ 1989-12 s. 45 | 542 | 1 | 0 | 549 | 2 | 7 |
| här | vanligt | HS 1994-08 s. 11 | 357 | 3 | 0 | 495 | 5 | 138 |
| skärm | vanligt | DMZ 1991-02 s. 32 | 68 | 1 | 0 | 68 | 1 | 0 |
| ljud | vanligt | DMZ 1987-10b s. 17 | 140 | 1 | 0 | 140 | 1 | 0 |
| hårddisk | vanligt | DMZ 1995-05-06 s. 64; DMZ 1993-03 s. 12 | 105 | 6, 24 | 0 | 107 | 6, 24 | 2 |
| Amiga | term | DMZ 1989-12 s. 25 | 336 | 1 | 0 | 336 | 1 | 0 |
| C64 | term | DMZ 1989-06 s. 46 | 149 | 2 | 0 | 149 | 2 | 0 |
| C-64 | bindestreck | DMZ 1986-01 s. 25 | 39 | 1 | 0 | 39 | 1 | 0 |
| Commodore 128 | fras | DMZ 1986-01 s. 4 | 19 | 1 | 0 | 19 | 1 | 0 |
| Fairlight | term | DMZ 1986-01 s. 7 | 5 | 2 | 0 | 5 | 2 | 0 |
| joystick | term | HS 1993-17 s. 17 | 85 | 1 | 0 | 85 | 1 | 0 |
| CD-ROM | bindestreck | DMZ 1995-14 s. 25 | 63 | 1 | 0 | 63 | 1 | 0 |
| piratkopiering | avstavat | DMZ 1986-01 s. 16 | 9 | 1 | 0 | 9 | 1 | 0 |
| Infiltrator | term | DMZ 1986-01 s. 6 | 5 | 1 | 0 | 5 | 1 | 0 |
| Trumpcard | term | DMZ 1993-03 s. 12 | 3 | 1 | 0 | 3 | 1 | 0 |
| Newsroom | term | DMZ 1986-04 s. 19 | 5 | 1 | 0 | 5 | 1 | 0 |
| högtalare | utspärrat | DMZ 1995-14 s. 26; s. 27 | 20 | 4, 1 | 0 | 20 | 4, 1 | 0 |
| Amiga | utspärrat | DMZ 1995-14 s. 3 | 336 | 23 | 0 | 336 | 23 | 0 |
| turboköpet | utspärrat | DMZ 1995-14 s. 20 | 1 | 1 | 0 | 1 | 1 | 0 |
| ryggsäck | utspärrat | DMZ 1995-14 s. 26 | 4 | 1 | 0 | 4 | 1 | 0 |
| köttbodar | avstavat | DMZ 1986-01 s. 6 | 1 | 1 | 0 | 1 | 1 | 0 |
| centraldatorn | avstavat | DMZ 1986-01 s. 6 | 1 | 1 | 0 | 1 | 1 | 0 |
| tekniska | avstavat | HS 1993-17 s. 6 | 19 | 3 | 0 | 19 | 3 | 0 |
| turbokort | avstavat | DMZ 1995-14 s. 20 | 21 | 6 | 0 | 21 | 6 | 0 |
| Mikael Roth | fras | HS 1993-17 s. 6 | 1 | 1 | 0 | 1 | 1 | 0 |
| Amiga 1200 | fras | DMZ 1995-14 s. 20 | 34 | 1 | 0 | 34 | 1 | 0 |
| Last Ninja | fras | DMZ 1987-10b s. 19 | 27 | 6 | 0 | 28 | 6 | 1 |
| Mutant League Football | fras | HS 1994-08 s. 18 | 3 | 1 | 0 | 3 | 1 | 0 |
| **Summa** | | 29 av 29 hittade i båda | | | **0** | | | **155** |

Varje förväntad sida är kontrollerad mot ett renderat utsnitt av PDF-sidan (30 utsnitt, i `cache/golden/`). På 1995 nr 14 s. 26 är trycket inte utspärrat; det är bara PDF:ens textlager som har ordet delat i bitar.

### 3.5 Storlek

| | Piloten (585 sidor) | Uppräknat till 185 nummer (cirka 10 900 sidor) |
|---|---|---|
| Rå text (`rawText`) | 3,22 MB | 58 MB |
| `searchText` | 3,20 MB | 58 MB |
| Tabellen `pages` (båda texterna) | 6,88 MB | 125 MB |
| FTS-index | 1,76 MB (rd 0), 1,74 MB (rd 2) | 34 MB, 33 MB |
| **`search.db` totalt** | **8,65 MB**, 8,63 MB | **159 MB**, 159 MB |
| Komprimerad med gzip | 3,25 MB | 60 MB |
| Komprimerad med xz | 2,57 MB | 48 MB |
| Utan `rawText` | 5,39 MB (gzip 2,72 MB) | cirka 99 MB (gzip cirka 50 MB) |

Uppräkningen är gjord per grupp, med en egen databas per grupp:

| Grupp | I piloten | I arkivet | `search.db` per nummer | Sidor per nummer | Uppräknat |
|---|---|---|---|---|---|
| Datormagazin v2 (1986 – 1991 nr 21) | 6 | 90 | 0,86 MB | 51 | 78 MB |
| Datormagazin äldre (1991 nr 22 – 1995) | 3 | 81 | 0,93 MB | 72 | 76 MB |
| HighScore | 2 | 14 | 0,41 MB | 32 | 6 MB |

Per nummer (egen databas per nummer, rd 0): mellan 0,42 MB (HighScore 1994 nr 8) och 1,14 MB (1989 nr 6); FTS-indexet är 115–270 kB per nummer. Alla värden finns i `data/search_pilot.json`.

Osäkerheten ligger i sidantalet: gruppen "äldre" bygger på tre nummer. Räkna med 130–190 MB.

### 3.6 Byggtid

0,25–0,55 s per v2-nummer, 1,7–2,2 s per äldre Datormagazin och 0,35–0,53 s per HighScore. Hela piloten tar 9 s. Hela arkivet bör ta 3–4 minuter när PDF:erna finns i cachen. Samma PDF:er och samma `--built-at` ger en identisk fil (kontrollerat med SHA-256).

### 3.7 Sidantal

| Nummer | Uppmätt | Inventory | Skannade sidor | |
|---|---|---|---|---|
| 1986 nr 1 | 28 | 28 | 28 | stämmer |
| 1986 nr 4 | 49 | saknas | 33 | **49 ska föras in; skiljer sig från skanningarna** |
| 1987 nr 10b | 44 | saknas | 44 | 44 ska föras in |
| 1989 nr 6 | 48 | saknas | 48 | 48 ska föras in |
| 1989 nr 12 | 48 | saknas | 48 | 48 ska föras in |
| 1991 nr 2 | 88 | saknas | 88 | 88 ska föras in |
| 1993 nr 3 | 68 | saknas | 72 | **68 ska föras in; skiljer sig från skanningarna** |
| 1995 nr 5/6 | 64 | saknas | 64 | 64 ska föras in |
| 1995 nr 14 | 84 | 84 | 84 | stämmer |
| HighScore 1993 nr 17 | 32 | saknas | 32 | 32 ska föras in |
| HighScore 1994 nr 8 | 32 | saknas | 32 | 32 ska föras in |

Inget befintligt värde avviker. Inventory är inte ändrat. Två nummer har olika antal sidor i PDF:en och bland originalskanningarna. Det påverkar inte sökningen, men HQ-mappningen i FAS 11 kan inte anta att sidorna motsvarar varandra ett till ett.

### 3.8 Appens motor på telefonen

Pilotens `search-rd0.db` (8,65 MB) kopierades till appens cache, öppnades skrivskyddat med `SearchDb` (BundledSQLiteDriver, SQLite 3.50.1; filen är byggd med 3.45.1) och togs bort efteråt.

| Fråga | Svar på telefonen | Som på datorn |
|---|---|---|
| högtalare | 1995 nr 14 s. 27 först; s. 26 med utdraget "Nya [högtalare] är ett bra sätt att få mer inlevelse när du…" | ja |
| "Commodore 128" | 1986 nr 1 s. 4 först | ja |
| "CD-ROM" | 1995 nr 14 s. 25 först | ja |
| tekniska, turboköpet | HighScore 1993 nr 17 s. 6 finns med; turboköpet ger bara 1995 nr 14 s. 20 | ja |
| får / far | 349 sidor / 24 sidor, 1995 nr 5/6 s. 24 först | ja |

`meta` och `issues` (sidantal, storlek och ETag per nummer) lästes också. Appens gränssnitt är inte ändrat.

### 3.9 Underlag för 8B

- **Ledigt på datorn:** 686 GB.
- **Kvar att hämta:** 174 filer, 11,61 GB. I cachen finns 11 filer, 0,66 GB.
- **Tid:** cirka 47 minuter vid 5 MB/s med 3 s paus mellan filerna.
- **Ordning:** minsta filen först. Början: 1986 nr 2 och nr 3 (18 MB), 1990 nr 1 (19 MB). Slutet: 1992 nr 18 (292 MB), 1995 nr 8 (359 MB), 1995 nr 15 (563 MB) sist. Hela listan finns i `data/search_pilot.json` under `plan8b.order`.
- Alla 185 nummer har en PDF-adress.

## 4. Testresultat

| Test | Resultat |
|---|---|
| Python-tester | 163 av 163 (129 före fasen, 34 nya) |
| JVM-tester (appen är oförändrad) | 320 av 320 |
| Lint | 0 fel |
| Instrumenterat test på telefonen, `SearchPilotDeviceTest` | 1 av 1 |

Den övriga instrumenterade sviten kördes inte, eftersom ingen appkod är ändrad. Fyra av de nya Python-testerna (facit) körs bara när piloten är byggd i `cache/search/`.

## 5. Acceptanskriterier (8A)

- [x] search.db byggs reproducerbart från den lokala PDF-cachen, i båda varianterna
- [x] facit finns, med visuellt kontrollerade sidor
- [x] 1995 nr 14: sökningen hittar "Amiga" och "högtalare" på de sidor där texten är utspärrad
- [x] träffsäkerhet, storlek och byggtid är uppmätta för båda varianterna
- [x] pilotens search.db går att öppna och söka i med appens motor på telefonen
- [x] ADR 0010 finns som Proposed, med mätdata och rekommendation

## 6. Kända begränsningar

- **Normaliseringen är inte felfri.** Delade ord som inte finns i ordlistan och bara har två bitar lämnas delade ("p roffsen", "m anualskydd"). Enstaka följder sätts ihop fel när korta ord står tätt intill skräptecken ("4 av Bour-" blev ett ord). Jag har granskat stickprov men inte mätt felandelen.
- **Ordlistan beror på vilka nummer som ingår.** `searchText` för en sida kan därför skilja sig något mellan piloten och fullkörningen. Med samma PDF:er blir resultatet alltid detsamma.
- **Läsordningen i `-raw` är OCR-programmets.** Den är kontrollerad på tre sidor och i stickprov, inte på alla.
- **Avstavning över sid- eller spaltgräns** sätts inte ihop, och inte heller när OCR:en har lagt skräp emellan ("sprängladd-" / ".ningar").
- **"C64" och "C-64" är olika sökningar** (149 respektive 39 sidor). Det är en fråga för sökrutan i FAS 9.
- **Facit gäller pilotens 11 nummer.** Rangordningen ändras när hela arkivet är indexerat; att sidan hittas ska inte ändras.
- **Testpaketet på telefonen är utbytt** mot ett som innehåller det nya testet. Appen (0.7.0) och dess data är orörda.
- Planens D2 beskriver fortfarande den äldre tabellformen; se förslagen.

## 7. ADR

- **0010 Tokenizer och diakritiska tecken** – Proposed. Rekommendation: `unicode61 remove_diacritics 0`.

## 8. Återstår, och förslag

Återstår i FAS 8: fullkörningen (8B). Den är inte påbörjad, och grenen är inte mergad.

**Rekommendation om tokenizer:** `remove_diacritics 0`. Din hypotes håller: varianten med 2 hittar inget mer i facit, ger 155 irrelevanta träffar och sänker rätt sida i rangordningen för "får", "för" och "här".

**Beslut jag behöver inför 8B:**

1. **Tokenizer:** fastställ ADR 0010 med `remove_diacritics 0`.
2. **`rawText`:** behåll den (159 MB) eller ta bort den (cirka 99 MB). Jag föreslår att behålla den tills FAS 9 visar om 159 MB är ett problem på telefonen; då går den att ta bort med en flagga utan ny hämtning.
3. **D2 i planen:** uppdatera till det som piloten bygger: `meta` med `searchSchemaVersion`, `inventoryDatasetVersion`, `tokenizer`, `builtAt`, `generator`, `sqliteVersion`, `pdftotextMode`, `issueCount`, `pageCount`; `issues(issueId, pageCount, ocrPdfSize, ocrPdfEtag)`; `pages` med en egen heltalsnyckel `id` som FTS-tabellen pekar på. Nyckeln behövs för att `VACUUM` inte ska kunna numrera om raderna under FTS-indexet.
4. **Sidantal:** för in uppmätt `pageCount` för alla nummer i 8B, med ny `datasetVersion`. För 1986 nr 4 och 1993 nr 3 föreslår jag PDF:ens sidantal, eftersom C7 utgår från PDF-sidindex.
5. **Facit i 8B:** kör samma facit mot det fullständiga indexet och lägg till 5–10 sökord ur nummer utanför piloten.
