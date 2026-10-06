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
| `data/inventory_diff.json` | senaste jämförelsen mot föregående inventory | `archive_inventory.py` |
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
5. jämför mot det befintliga inventoryt
6. skriver de tre filerna i `data/`

Om valideringen ger fel skrivs inga filer och kommandot avslutas med kod 1.

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
| `--previous FIL` | jämför mot detta inventory i stället för den befintliga filen |
| `--output-dir KATALOG` | skriv de tre utdatafilerna dit i stället för till `data/` |
| `--validate FIL` | validera bara ett befintligt inventory |

## Manuella rättelser (`tools/overrides.json`)

Det som inte går att härleda ur filnamnen rättas här, inte i koden.

```json
{
  "overrides": {
    "datormagazin-1986-01": {
      "coverFlags": ["rotated"],
      "reason": "Omslaget är skannat liggande."
    }
  }
}
```

| Fält | Verkan |
|---|---|
| `coverFlags` | ersätter listan (känt värde: `"rotated"`) |
| `spreadOffset` | ersätter värdet; heltal eller `null` |
| `issueLabel` | ersätter visningstexten |
| `flags` | **läggs till** de genererade flaggorna |
| `reason` | fritext som förklarar rättelsen; följer inte med till inventory |

Rättelserna slås ihop efter normaliseringen och före valideringen, i sorterad ordning. Ett okänt `issueId`, ett okänt fält eller ett värde av fel typ ger ett fel, och då tillämpas ingen rättelse alls. Vilka fält som rättats per nummer står i `appliedOverrides` i `data/inventory_diagnostics.json`.

## Validering

Körs alltid före export. Kontrollerar:

- strukturen mot `docs/inventory.schema.json` (obligatoriska fält, typer, format, inga okända fält)
- att `issueId` är unika
- att `relatedIssueId` pekar på ett nummer som finns
- att `sortKey` är unik och ligger i stigande ordning inom varje publikation och år, och börjar med numrets år
- att `publicationId` och `publicationTitle` stämmer med listan `publications`

Validera en fil för sig:

```bash
python3 tools/archive_inventory.py --validate data/archive_inventory.json
```

## Jämförelse mot föregående inventory

Varje körning jämför det nya inventoryt med den fil som redan ligger på plats. Rapporten skrivs till stdout och till `data/inventory_diff.json`:

- `added`, `removed`: nya och borttagna `issueId`
- `changed`: ändrade nummer, med gammalt och nytt värde per fält
- `header`: ändringar i `schemaVersion`, `datasetVersion` med mera

Om inventoryt är oförändrat lämnas `inventory_diff.json` orörd. Filen visar alltså alltid den senaste verkliga ändringen. Jämför mot en annan version med `--previous`:

```bash
git show v0.0-fas0:data/archive_inventory.json > /tmp/gammalt.json
python3 tools/archive_inventory.py --offline --previous /tmp/gammalt.json
```

## Tester

```bash
python3 -m unittest discover -s tools/tests -v
```

- `test_normalize.py`: namnreglerna, med verkliga filnamn och alla specialfall
- `test_inventory.py`: fälten i inventory, rättelser, schemavalidering, felfall, jämförelse och determinism

Testerna kör mot cachefilerna i `data/` och gör inga nätanrop.

## Namnregler och specialfall

Reglerna står i början av avsnittet "Namnregler" i `archive_inventory.py` och beskrivs i `docs/archive_notes.md`. En fil som inte matchar någon regel gissas aldrig: den listas som specialfall i utskriften och i `specialCases` i diagnostikfilen.
