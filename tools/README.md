# Toolchain för DMZ Reader

Python-verktyg som gör om DMZArkivets råa katalogstruktur till det inventory som appen läser. All arkivlogik ligger här; appen ser bara `data/archive_inventory.json`.

## Förutsättningar

- Python 3.10 eller senare
- paketet `jsonschema` (för validering mot `docs/inventory.schema.json`)

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
| `data/sample_measurements.json` | uppmätta sidantal från stickprov | för hand |
| `data/archive_inventory.json` | **inventory för appen** | `archive_inventory.py` |
| `data/inventory_diagnostics.json` | härledd data för toolchainen, ingår inte i appen | `archive_inventory.py` |
| `data/inventory_diff.json` | jämförelse mot ett annat inventory; versionshanteras inte | `archive_inventory.py --compare-to` |
| `docs/inventory.schema.json` | JSON Schema (draft 2020-12) för inventory | för hand |

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
| `coverFlags` | ersätter listan. Tillåtna värden: `rotate_cw`, `rotate_ccw`, `rotate_180` (högst en) och `crop_left_half`, `crop_right_half` (högst en). Appen vrider först och beskär sedan. |
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

Testerna kör mot cachefilerna i `data/` och gör inga nätanrop.

## Testindex för FTS5 (`build_fts_testdb.py`)

```bash
python3 tools/build_fts_testdb.py
```

Bygger `app/src/debug/assets/fts_test.db`: ett litet sökindex i samma format som det riktiga ska få (planens D2), med 8 sidor påhittad svensk text och 11 sökfrågor med förväntade träffar i tabellen `selftest`. Skriptet kontrollerar förväntningarna mot datorns SQLite innan filen skrivs, och ger samma fil varje gång. Appen kör samma frågor på telefonen, i Inställningar → Diagnostik (ADR 0006).

Kör om skriptet och committa filen om texten eller frågorna ändras.

## Namnregler och specialfall

Reglerna står i början av avsnittet "Namnregler" i `archive_inventory.py` och beskrivs i `docs/archive_notes.md`. En fil som inte matchar någon regel gissas aldrig: den listas som specialfall i utskriften och i `specialCases` i diagnostikfilen.
