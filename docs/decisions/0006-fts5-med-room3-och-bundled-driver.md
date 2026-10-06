# ADR 0006: FTS5 med Room 3 och BundledSQLiteDriver

## Status
Accepted (verifierat 2026-10-06 på C64:s telefon)

## Context
Fulltextsökningen (FAS 8 och 9) bygger på ett SQLite-index som skapas på datorn och importeras till telefonen. Indexet ska ge rankade träffar (`bm25`) och textutdrag (`snippet`), vilket kräver FTS5.

Telefonens inbyggda SQLite följer med Android-versionen. Den är inte samma på alla enheter och garanterar inte FTS5. Ett index byggt på datorn kan dessutom använda funktioner som en äldre SQLite på telefonen saknar.

## Decision
- Appen använder **`BundledSQLiteDriver`** (`androidx.sqlite:sqlite-bundled` 2.7.1) för all SQLite: dels som drivrutin för Room 3, dels direkt för sökindexet. Drivrutinen har med sig sin egen SQLite (3.50.1) med FTS5.
- **Sökindexet öppnas som en egen, skrivskyddad databas**, skild från Room: `BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY)` (klassen `SearchDb`). Room känner inte till filen.
- Indexet har formatet i planens D2: `meta`, `pages (issueId, pageNumber, rawText, searchText)`, `pages_fts` (FTS5 över `searchText`, med `pages` som innehållstabell) och `issues`.
- Sökfrågan är `MATCH` med `bm25()` för rankning och `snippet()` för utdrag.
- FTS4 är en reserv som bara utvärderas om verifieringen visar ett faktiskt fel.

### Verifiering
`tools/build_fts_testdb.py` bygger ett litet index (8 sidor svensk text) med Pythons SQLite. Indexet bär sina egna förväntningar i tabellen `selftest`: 11 sökfrågor med väntade träffar, bästa träff och text i utdraget. Appen kopierar filen till `filesDir/data/`, öppnar den skrivskyddat och kör frågorna (`FtsSelfTest`). Resultatet visas i Inställningar → Diagnostik.

Frågorna täcker: enkelt ord, rankning, ord med å, ä och ö, versalt Ä, ett ord som var avstavat med mjukt bindestreck, en sida med utspärrad text, fras, prefix, två ord, sökning i ett nummer och en fråga utan träffar. Testet kontrollerar också att databasen vägrar skrivningar.

| Miljö | SQLite | Resultat |
|---|---|---|
| Datorn, Pythons `sqlite3` (bygger indexet) | 3.45.1 | 11 av 11 |
| Datorn, JVM, `BundledSQLiteDriver` (`SearchDbTest`) | 3.50.1 | 11 av 11 |
| Telefonen, `BundledSQLiteDriver`, Diagnostik i appen | 3.50.1 | 11 av 11, GODKÄNT |
| Telefonen, instrumenterade tester (`FtsInstrumentedTest`) | 3.50.1 | 2 av 2 |

Telefonen är en OnePlus 7 Pro (GM1911, arm64-v8a) med Android 16 (API 36, bygge BP4A.251205.006). Testindexet öppnades skrivskyddat från `filesDir/data/`, och SQLite-versionen var densamma som i JVM-testerna. FTS4 behöver inte utvärderas.

## Reason
- Samma SQLite på alla telefoner och i testerna. Indexet byggs en gång och beter sig likadant överallt.
- Room 3 kräver ändå en `SQLiteDriver`, så den paketerade drivrutinen tillför inget nytt beroende.
- En skrivskyddad, separat databas kan bytas ut atomiskt (planens D5) utan att Room berörs, och kan inte skadas av appen.
- Att indexet bär sina egna förväntningar gör att exakt samma kontroll körs på datorn och på telefonen.

## Consequences
- APK:n växer med SQLite för fyra processorarkitekturer (cirka 1,2 MB vardera).
- En `SearchDb`-anslutning får inte användas från flera trådar samtidigt. Det ska hanteras när sökningen byggs i FAS 9.
- Testindexet använder `unicode61` med standardinställningar, som tar bort diakritiska tecken: sökningen "far" träffar även "får" (`SearchDbTest`). Om det är önskvärt avgörs i ADR 0010.
- Testindexets normalisering av utspärrad text är en förenkling. Den riktiga görs i FAS 8.
- Testindexet följer bara med i debug-byggen (`app/src/debug/assets/fts_test.db`). Det är versionshanterat, eftersom det är litet, påhittat och byggs deterministiskt.
- JVM-testerna kör Android-varianten av drivrutinen med datorns SQLite-bibliotek hämtat ur JVM-varianten av samma version (`extractHostSqlite` i `app/build.gradle.kts`). En sak går inte att pröva så: `@Upsert` av en befintlig rad, som därför prövas i det instrumenterade testet.
