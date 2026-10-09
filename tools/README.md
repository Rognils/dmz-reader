# Toolchain för DMZ Reader

Python-verktyg som gör om DMZArkivets råa katalogstruktur till det inventory som appen läser. All arkivlogik ligger här; appen ser bara `data/archive_inventory.json`.

## Förutsättningar

- Python 3.10 eller senare
- paketet `jsonschema` (för validering mot `docs/inventory.schema.json`)
- paketet `Pillow` med WebP-stöd (för omslagsminiatyrerna)

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r tools/requirements.txt
```

Exemplen nedan skriver `python3`. Använd `.venv/bin/python` om du installerat i en virtuell miljö. Alla kommandon körs från repo-roten.

## Filer

| Fil | Roll | Skrivs av |
|---|---|---|
| `tools/config.json` | FTP-konto, kataloger, sökvägar, `datasetSequence` | för hand |
| `tools/overrides.json` | manuella rättelser | för hand |
| `data/raw_listing.json` | FTP-listning, cache | `archive_inventory.py` (online) |
| `data/https_probe.json` | HEAD-svar från webbplatsen, cache | `https_probe.py` (online) |
| `data/sample_measurements.json` | uppmätta sidantal och SHA-256 för alla PDF:er | `measure_page_counts.py` |
| `data/archive_inventory.json` | **inventory för appen** | `archive_inventory.py` |
| `data/inventory_diagnostics.json` | härledd data för toolchainen, ingår inte i appen | `archive_inventory.py` |
| `data/inventory_diff.json` | jämförelse mot ett annat inventory; versionshanteras inte | `archive_inventory.py --compare-to` |
| `docs/inventory.schema.json` | JSON Schema (draft 2020-12) för inventory | för hand |
| `cache/covers/` | omslag i original, cirka 1,5 GB; versionshanteras inte | `fetch_covers.py` |
| `data/covers/` | **omslagsminiatyrer som följer med appen** | `build_covers.py` |
| `data/covers_manifest.json` | källa, storlek och kontrollsumma per miniatyr | `build_covers.py` |
| `data/cover_review.json` | visuell granskning av kontaktkartan | för hand |
| `docs/screenshots/covers/contact_sheet.png` | alla valda omslag som små bilder | `build_covers.py` |
| `cache/pdf/` | OCR-PDF:er och `manifest.json` (storlek, ETag, SHA-256); versionshanteras inte | `fetch_pdfs.py` |
| `cache/search/` | pilotens databaser (FAS 8A); versionshanteras inte | `search_pilot.py` |
| `dist/search.db` | **det färdiga sökindexet**, cirka 149 MB; versionshanteras inte | `build_search_index.py` |
| `tools/data/normalizer_lexicon_v1.txt` | normaliseringens frysta ordlista | `build_lexicon.py` (en gång) |
| `data/normalizer_review_v1.json`, `_v2.json` | granskade stickprov av normaliseringens ändringar | `search_quality.py sample`, bedömning för hand |
| `data/reading_order_review.json` | visuell kontroll av läsordningen på 28 sidor | för hand |
| `data/search_index_report.json` | facit och storlek för den fullständiga databasen | `search_pilot.py --db` |
| `app/src/debug/assets/search_test.db` | litet sökindex i det riktiga formatet, för appens tester | `build_search_testdb.py` |
| `tools/testdata/search_golden.json` | facit för sökindexet, visuellt kontrollerat | för hand |
| `tools/testdata/search_lines.json` | verkliga rader med ordlägen, för normaliseringstesterna | för hand (ur pilotens PDF:er) |
| `data/search_pilot.json` | pilotens mätresultat (FAS 8A) | `search_pilot.py` |

## Köra inventeringen

### Offline (det vanliga)

```bash
python3 tools/archive_inventory.py --offline
```

Läser bara cachefilerna i `data/`. Inga anrop görs mot arkivet. Kommandot:

1. tolkar varje filnamn i rålistningen och kopplar det till ett `issueId`, eller listar det som specialfall
2. bygger inventory med adresser och storlekar från `data/https_probe.json`
3. slår ihop rättelserna i `tools/overrides.json`
4. validerar resultatet
5. skriver `data/archive_inventory.json` och `data/inventory_diagnostics.json`

Om valideringen ger fel skrivs inga filer och kommandot avslutas med kod 1.

**Saknat material stoppar inte exporten.** Om webbplatsen saknar läs-PDF eller sidbild 1 för ett nummer blir `ocrPdfUrl` respektive `coverUrl` `null`, numret får flaggan `missing_ocr_pdf` respektive `missing_cover`, och en rad som börjar med `VARNING` skrivs till stdout och till `warnings` i diagnostikfilen.

**Determinism:** samma indata ger byte-identiska filer. Nycklarna är sorterade, ordningen är stabil och ingen klocka läses. `generatedAt` tas från datumet i `datasetVersion`.

### Online (ny listning av arkivet)

```bash
python3 tools/archive_inventory.py          # FTP-listning, cirka 190 kataloganrop
python3 tools/https_probe.py                # HEAD mot webbplatsen för det som saknas i cachen
python3 tools/archive_inventory.py --offline
```

Belasta servern försiktigt. Verktygen använder en FTP-anslutning, passivt läge och minst 0,5 s paus mellan anropen, och laddar aldrig ned filer. Gör bara en ny listning när arkivet faktiskt har ändrats.

- `--resume` återupptar en avbruten listning utan att fråga om kataloger som redan finns i cachen.
- `python3 tools/https_probe.py --refresh` frågar om alla adresser, inte bara de som saknas.

Höj `datasetSequence` i `tools/config.json` när ett nytt inventory ska ges ut från samma listning. Efter en ny listning en annan dag börjar löpnumret om på 1.

### Flaggor

| Flagga | Betydelse |
|---|---|
| `--offline` | använd bara cachen |
| `--dataset-version ÅÅÅÅ-MM-DD-NNN` | sätt `datasetVersion` för hand (standard: listningens datum plus `datasetSequence`) |
| `--generated-at TID` | sätt `generatedAt` för hand, t.ex. `2026-10-06T12:00:00Z` |
| `--compare-to FIL` | jämför det nya inventoryt mot denna fil och skriv `data/inventory_diff.json` |
| `--output-dir KATALOG` | skriv utdatafilerna dit i stället för till `data/` |
| `--validate FIL` | validera bara ett befintligt inventory |

## Manuella rättelser (`tools/overrides.json`)

Det som inte går att härleda ur filnamnen rättas här, inte i koden.

```json
{
  "overrides": {
    "datormagazin-1986-01": {
      "coverFlags": ["rotate_cw", "crop_right_half"],
      "reason": "Sidbild 1 är ett liggande uppslag."
    }
  }
}
```

| Fält | Verkan |
|---|---|
| `coverFlags` | ersätter listan. Rättar webbens sidbild 1. Tillåtna värden: `rotate_cw`, `rotate_ccw`, `rotate_180` (högst en) och `crop_left_half`, `crop_right_half` (högst en). Ordningen är alltid vridning, beskärning, skalning. |
| `coverSource` | tvingar källan för miniatyren: `covers_ftp` eller `web_page1`. Skrivs inte till inventory. |
| `spreadOffset` | ersätter värdet; heltal eller `null` |
| `issueLabel` | ersätter visningstexten |
| `flags` | **läggs till** de genererade flaggorna |
| `reason` | fritext som förklarar rättelsen; följer inte med till inventory |

Rättelserna slås ihop efter normaliseringen och före valideringen, i sorterad ordning. Ett okänt `issueId`, ett okänt fält eller ett värde av fel typ ger ett fel, och då tillämpas ingen rättelse alls. Vilka fält som rättats per nummer står i `appliedOverrides` i `data/inventory_diagnostics.json`.

## Validering

Körs alltid före export. Kontrollerar:

- strukturen mot `docs/inventory.schema.json` (obligatoriska fält, typer, format, inga okända fält). `coverUrl` och `ocrPdfUrl` måste finnas men får vara `null`.
- att `issueId` är unika
- att `relatedIssueId` pekar på ett nummer som finns
- att `sortKey` är unik och ligger i stigande ordning inom varje publikation och år, och börjar med numrets år
- att `publicationId` och `publicationTitle` stämmer med listan `publications`

Validera en fil för sig:

```bash
python3 tools/archive_inventory.py --validate data/archive_inventory.json
```

## Jämförelse mot ett annat inventory

En jämförelse görs bara när du ber om den, med `--compare-to`. Utan flaggan skrivs ingen jämförelse.

Jämför mot den version som ligger i git:

```bash
git show HEAD:data/archive_inventory.json > /tmp/prev.json
python3 tools/archive_inventory.py --offline --compare-to /tmp/prev.json
```

Byt `HEAD` mot en tagg för att jämföra mot en äldre utgåva, till exempel `v0.0-fas0`.

Rapporten skrivs till stdout och till `data/inventory_diff.json`:

- `previousDatasetVersion`, `currentDatasetVersion`: vilka två versioner som jämförts
- `added`, `removed`: nya och borttagna `issueId`
- `changed`: ändrade nummer, med gammalt och nytt värde per fält
- `header`: ändringar i `schemaVersion`, `datasetVersion` med mera

`data/inventory_diff.json` är en arbetsfil och ligger i `.gitignore`.

## Tester

```bash
python3 -m unittest discover -s tools/tests -v
```

- `test_normalize.py`: namnreglerna, med verkliga filnamn och alla specialfall
- `test_inventory.py`: fälten i inventory, rättelser, schemavalidering, felfall, jämförelse och determinism
- `test_fts_testdb.py`: testindexets format, innehåll och determinism
- `test_covers.py`: transformordningen, klassningen och de versionshanterade miniatyrerna

Testerna kör mot cachefilerna i `data/` och gör inga nätanrop.

## Omslag (`fetch_covers.py` och `build_covers.py`)

Omslagen tas fram av toolchainen och följer med i appen som miniatyrer (ADR 0016).

```bash
python3 tools/fetch_covers.py             # hämtar original till cache/covers/ (en gång)
python3 tools/build_covers.py --review    # bygger data/covers/ och kontaktkartan
python3 tools/archive_inventory.py --offline
```

**Hämtning.** `fetch_covers.py` hämtar webbplatsens sidbild 1 för alla nummer (HTTPS) och alla `.jpg` och `.png` i FTP-katalogen `Covers`. En anslutning, 0,6 s paus mellan filerna, och filer som redan finns i cachen med rätt storlek hoppas över, så en avbruten körning kan startas om. `--offline` visar bara vad som finns.

**Källa per nummer**, i den här ordningen:

1. bearbetat omslag från `Covers`. En fil räknas som bearbetad om den skiljer sig från sidbild 1 vid en nedskalad pixeljämförelse (inte bara i filstorlek). De flesta filerna i `Covers` är obearbetade kopior av skanningen.
2. webbens sidbild 1, rättad enligt `coverFlags` i `tools/overrides.json`
3. ingen bild; appen visar en platshållare

**Transformordningen** är alltid: vridning, beskärning, skalning. `coverFlags` tillämpas bara på webbens sidbild, aldrig på en fil från `Covers`.

**Miniatyrer.** Högst 600 px breda, WebP med kvalitet 80. Utdatan är deterministisk för en given version av Pillow: samma original ger samma filer. `data/covers/` versionshanteras, eftersom miniatyrerna är små och avgör appens utseende. Appens byggsteg kopierar dem till `assets/covers/`.

**Granskning.** `--review` skriver större kontaktkartor per år till `cache/covers/review/`. Titta igenom dem efter en ny hämtning. Ett omslag som är ett uppslag eller står på sidan rättas med `coverFlags` i `tools/overrides.json`, och granskningen förs in i `data/cover_review.json` (klasserna `ok`, `uppslag_vridet`, `annat`). Kör sedan om `build_covers.py` och `archive_inventory.py`.

Efter en ändring av miniatyrerna: höj `datasetSequence` i `tools/config.json`, så att appen läser in det nya inventoryt.

## Testindex för FTS5 (`build_fts_testdb.py`)

```bash
python3 tools/build_fts_testdb.py
```

Bygger `app/src/debug/assets/fts_test.db`: ett litet sökindex i samma format som det riktiga ska få (planens D2), med 8 sidor påhittad svensk text och 11 sökfrågor med förväntade träffar i tabellen `selftest`. Skriptet kontrollerar förväntningarna mot datorns SQLite innan filen skrivs, och ger samma fil varje gång. Appen kör samma frågor på telefonen, i Inställningar → Diagnostik (ADR 0006).

Kör om skriptet och committa filen om texten eller frågorna ändras.

## Sökindex (`fetch_pdfs.py`, `build_search_index.py`)

Fulltextindexet `search.db` byggs på datorn ur OCR-PDF:erna (plan D2 och FAS 8). Det kräver `pdftotext`, `pdftoppm` och `pdfinfo` (poppler-utils). PDF:erna ligger i `cache/pdf/` (12,3 GB) och den färdiga databasen i `dist/`. Ingetdera versionshanteras.

### Bygga om search.db

```bash
# 1. Hämta det som saknas i cache/pdf/ (en fil i taget, högst 5 MB/s, 3 s paus, återupptagbart)
python3 tools/fetch_pdfs.py --all

# 2. Bara om arkivet har ändrats: mät sidantal och bygg nytt inventory
python3 tools/measure_page_counts.py
python3 tools/archive_inventory.py --offline        # efter att datasetSequence höjts i tools/config.json

# 3. Bygg databasen (drygt 3 minuter)
python3 tools/build_search_index.py --all --built-at 2026-10-08T00:00:00Z -o dist/search.db

# 4. Kontrollera mot facit
python3 tools/search_pilot.py evaluate --db dist/search.db
python3 -m unittest discover -s tools/tests
```

Samma PDF:er, samma inventory och samma `--built-at` ger en fil med samma SHA-256. Utan `--built-at` används klockslaget, och då skiljer sig filen i `meta.builtAt`.

**Hämtningen** kontrollerar varje fil mot `ocrPdfSize` och `ocrPdfEtag` i inventory och sparar storlek, ETag, Last-Modified och SHA-256 i `cache/pdf/manifest.json`. En fil som inte stämmer behålls men markeras, och indexbygget hoppar över den. Servern bryter överföringar efter omkring 200 MB; hämtningen fortsätter då själv med Range. Före start kontrolleras att disken rymmer det som ska hämtas.

**Texten** tas ut med `pdftotext -raw`, som följer den ordning OCR-programmet skrev texten i och därför håller ihop spalterna. `rawText` är den texten orörd. `searchText` byggs av samma ordbitar med deras lägen på sidan (`pdftotext -raw -bbox`):

- utspärrad text sätts ihop: bitar som ligger tätare än radens ordmellanrum hör till samma ord
- mjuka bindestreck tas bort, och avstavning vid radbrytning sätts ihop
- riktiga bindestreck behålls (CD-ROM, C-64, x-minne, Formel 1-stjärnan)
- blanksteg blir ett mellanslag

**Databasen** (plan D2) har tabellerna `meta`, `issues` (sidantal, storlek, ETag och SHA-256 för den PDF som indexerades), `pages` och FTS5-tabellen `pages_fts` över `searchText`, med tokenizern `unicode61 remove_diacritics 0` (ADR 0010).

### Ordlistan och normalizerVersion

Tveksamma fall i normaliseringen avgörs mot en **fryst ordlista**, `tools/data/normalizer_lexicon_v1.txt` (144 377 termer, sorterad, en per rad). Den byggdes en gång med `tools/build_lexicon.py` ur alla 185 nummer: ord med minst tre tecken som står hela i textlagret minst två gånger. Inga ordböcker ingår; ett prov med hunspell sv_SE och en_US ändrade 0,1 procent av fallen och gav lika många fel som rättningar.

Normaliseringen läser bara ordlistan och det aktuella numret. Ett nummer får därför samma `searchText` vare sig det byggs ensamt eller tillsammans med alla andra (testat).

`NORMALIZER_VERSION` i `build_search_index.py` skrivs i `meta.normalizerVersion`. **Höj den** när

- en regel i normaliseringen ändras (konstanterna för avstånd, `resolve_run`, `_join_line_break`, `SHORT_WORDS`)
- ordlistan byts. Spara då den nya som `normalizer_lexicon_v2.txt`, höj `LEXICON_VERSION` och låt den gamla ligga kvar.
- `pdftotext`-läget ändras

Den ska inte höjas när bara nya nummer läggs till. Ordlistan byggs inte om för det.

Efter en ändring: dra ett nytt stickprov och granska det.

```bash
python3 tools/search_quality.py sample --check data/normalizer_review_v1.json   # 200 ändringar, och vilka tidigare bedömda som ändrats
python3 tools/search_quality.py order datormagazin-1995-15 62                   # läsordningen inritad på sidan
python3 tools/search_pilot.py render datormagazin-1995-14 26 högtalare          # utsnitt för visuell kontroll av facit
```

**Facit** (`tools/testdata/search_golden.json`) får bara ändras efter att sidan har kontrollerats mot en rendering, inte mot OCR-texten.

`tools/search_pilot.py` har också kvar pilotens mätningar från FAS 8A (`build`, `measure`, `compare`, `pages`, `plan`).

## Uppdatera sökindexet på telefonen

Sökindexet följer inte med appen. Det byggs på datorn och importeras i appen (plan D2 och D5).

1. **Bygg** `dist/search.db` enligt "Bygga om search.db" ovan, och kontrollera den mot facit.
2. **Kopiera** filen till telefonens Download:

   ```bash
   adb push dist/search.db /sdcard/Download/search.db
   ```

   Det går lika bra med en USB-kabel och filhanteraren, eller via en molntjänst. Filen är cirka 149 MB.
3. **Importera** i appen: Inställningar → Sökindex → "Importera sökindex", och välj filen i filväljaren.
   Appen kopierar filen till sin privata lagring med förlopp i MB och procent, kontrollerar den och byter in den. Det tar ett par sekunder.
4. **Kontrollera** raderna under Sökindex: arkivversion, normalisering, antal nummer och sidor och importdatum.
5. **Ta bort** filen i Download. Appen har sin egen kopia.

Vad appen kontrollerar vid import: att filen är en hel SQLite-databas (`PRAGMA quick_check`), att tabellerna `meta`, `issues`, `pages` och `pages_fts` finns, att `searchSchemaVersion` är 1, att indexet går att söka i och att det inte är tomt. Går något fel visas vad som var fel, och det tidigare sökindexet gäller fortfarande.

Om sökindexet byggdes av ett annat arkivindex än det som är aktivt i appen fungerar sökningen ändå. Sökvyn visar då "Sökindexet är från en annan arkivversion." Ett nummer vars PDF har bytts sedan indexet byggdes får markeringen "Sökindexet gäller en äldre version av filen".

"Ta bort sökindex" under samma rubrik tar bort appens kopia. Sökningen gäller då år, nummer, publikation och bokmärken, som före importen.

### Testindex för appens tester (`build_search_testdb.py`)

`app/src/debug/assets/search_test.db` är ett litet index i samma format, med ett tiotal påhittade sidor. Det används av appens JVM-tester och av det instrumenterade testet, och byggs om med `python3 tools/build_search_testdb.py` när formatet ändras.

## Uppdatera arkivet på telefonen

När arkivet har fått nya nummer, eller en fil har bytts, behöver appen inte byggas om. Arkivindexet görs på datorn och importeras i appen.

1. **Kör toolchainen.** Gör en ny listning och bygg indexet, som i avsnittet "Online" ovan:

   ```
   python3 tools/archive_inventory.py          # ny FTP-listning
   python3 tools/https_probe.py                # HEAD mot webbplatsen för det som är nytt
   python3 tools/archive_inventory.py --offline
   ```

   Kontrollera utskriften: antalet nummer, eventuella specialfall och att valideringen går igenom. Vill du se vad som har ändrats sedan förra indexet, lägg till `--compare-to <den gamla filen>` på sista raden.

2. **Kopiera `data/archive_inventory.json` till telefonen.** Till exempel med USB:

   ```
   adb push data/archive_inventory.json /sdcard/Download/
   ```

   Vilken väg som helst går bra, bara filen hamnar där telefonens filväljare når den.

3. **Importera i appen.** Inställningar → Arkivindex → Importera arkivindex, och välj filen.

   Appen kopierar filen, validerar den och visar hur många nummer som är nya, borttagna och ändrade jämfört med det index som gäller. Ingenting ändras förrän du trycker Importera.

Bra att veta:

- **Din data rörs inte.** Favoriter, bokmärken, läsdata och nedladdade nummer finns kvar. Ett nummer som har försvunnit ur indexet visas som "Saknas i aktuellt arkivindex" och kopplas ihop med sin data igen om det kommer tillbaka.
- **En trasig fil avvisas** med en förklaring, och det tidigare indexet gäller.
- **Nya nummer saknar inbyggd miniatyr.** Omslaget hämtas då från webben. Miniatyrerna följer med först i nästa appversion (`tools/build_covers.py`).
- **En nedladdad PDF som har bytts i arkivet** raderas aldrig automatiskt. Nummervyn visar "Din nedladdade kopia är äldre" med knappen Uppdatera nedladdning.
- **Ångra:** Inställningar → Arkivindex → Återgå till inbyggt index.

### Testindex (`make_test_inventories.py`)

`tools/testdata/` innehåller index för att pröva importen: ett giltigt med ett nytt, ett borttaget och ett ändrat nummer, och fyra trasiga. De byggs från det riktiga indexet med `python3 tools/make_test_inventories.py` och är inte riktiga arkivindex. Importera dem inte för vanlig användning.

## Namnregler och specialfall

Reglerna står i början av avsnittet "Namnregler" i `archive_inventory.py` och beskrivs i `docs/archive_notes.md`. En fil som inte matchar någon regel gissas aldrig: den listas som specialfall i utskriften och i `specialCases` i diagnostikfilen.
