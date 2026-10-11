# Fasrapport: FAS 1 – Android-grund och FTS5-verifiering

> **Skärmbilderna som nämns här finns inte i repot.** De ligger lokalt hos projektets ägare, se [docs/skarmbilder.md](../skarmbilder.md).

Avslutad 2026-10-06. Verifierad på OnePlus 7 Pro (GM1911), Android 16 (API 36).

## 1. Sammanfattning

- Gradle-projekt med en app-modul, `applicationId` `se.c64.dmzreader`, `minSdk` 26, `compileSdk` och `targetSdk` 37.
- Datamodell i Room 3 med `BundledSQLiteDriver`: `Issue`, `IssueLocalState`, `IssueUserState`, `Bookmark`, `ReadingSession`, utan främmande nycklar.
- Inventory kopieras till assets av ett byggsteg och läses in vid start när `datasetVersion` har ändrats.
- FTS5-verifiering: ett förgenererat testindex öppnas skrivskyddat, skilt från Room, och söks med `bm25` och `snippet`.
- Tema enligt planens E3 med Archivo och IBM Plex Mono paketerade, fyra flikar, och Inställningar → Diagnostik.
- Androids backup avstängd (ADR 0014).

## 2. Versioner

| Del | Version |
|---|---|
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.6.0 |
| JDK | 17 (Temurin 17.0.20) |
| compileSdk / targetSdk | 37 |
| Kotlin | 2.4.20 |
| KSP | 2.3.12 |
| Room 3 | 3.0.3 |
| sqlite-bundled | 2.7.1 (SQLite 3.50.1) |
| Compose BOM | 2026.09.00 |
| DataStore | 1.2.1 |
| Navigation Compose | 2.10.2 |
| kotlinx.serialization | 1.11.0 |

Källor för varje version står i ADR 0001.

## 3. Testresultat

| Test | Hur | Resultat |
|---|---|---|
| JVM-tester | `./gradlew :app:testDebugUnitTest` | 38 av 38 |
| Instrumenterade tester på telefonen | `./gradlew :app:connectedDebugAndroidTest` | 7 av 7 |
| FTS5-självtest i appen | Inställningar → Diagnostik | GODKÄNT, 11 av 11 |
| Python-tester | `.venv/bin/python -m unittest discover -s tools/tests` | 97 av 97 |
| Lint | `./gradlew :app:lintDebug` | 0 fel, 2 varningar |

De två lintvarningarna är väntade: en nyare Gradle finns (Gradle följer AGP:s krav, ADR 0001), och ikonmappen `mipmap-anydpi-v26` (utan `-v26` hittar resurslänkningen inte ikonen).

## 4. Acceptanskriterier

- [x] **Appen startar och visar antalet nummer från inventory.** Biblioteket visar "185 nummer i arkivet": Datormagazin 171 (1986: 4, 1987: 10, 1988: 16, 1989: 19, 1990: 20, 1991–1994: 22 per år, 1995: 14) och HighScore 14 (1993: 6, 1994: 8).
- [x] **Ett test bekräftar att import av ett nytt inventory inte påverkar `IssueUserState`.** `InventoryImporterTest` på JVM och `InventoryImportInstrumentedTest` på telefonen. Även `Bookmark`, `ReadingSession` och `IssueLocalState` är orörda, också när numret försvinner ur inventory.
- [x] **En förgenererad FTS5-databas öppnas och söks korrekt på den riktiga telefonen, och resultatet är dokumenterat.** GODKÄNT, 11 av 11, SQLite 3.50.1, öppnad skrivskyddat. Dokumenterat i ADR 0006.
- [x] **En debug-APK går att installera.** `app/build/outputs/apk/debug/app-debug.apk`, installerad med `adb install -r`.

## 5. Verifiering på telefonen

- Start: `am start` gav status ok, cirka 1,1 s.
- Diagnostik: `datasetVersion` 2026-10-06-002, `schemaVersion` 1, SQLite 3.50.1 både för Room och för testindexet.
- logcat: inga krascher och inga fel från appens kod. De rader som loggas på varningsnivå kommer från systemet (`libbinder`, `ashmem`, `userfaultfd`).
- Skärmbilder i `docs/screenshots/fas1/`.

Granskning av skärmbilderna:

| Kontroll | Resultat |
|---|---|
| Archivo och IBM Plex Mono har laddats | Ja. Rubriker i Archivo 800, siffror och etiketter i Plex Mono. |
| READY. är diskret | Ja, liten etikett i accentfärg ovanför appnamnet |
| Färgerna stämmer med E3 | Ja, uppmätt i bilden: bakgrund `#0E0F12`, ytor `#16181D`, accent `#F5B53D` |
| Statusfält och navigationsfält utan ljusa ytor | Ja, statusfältet har bakgrundsfärgen och navigationsfältet ytfärgen |
| Alla fyra flikar får plats | Ja |
| Ingen text kapas | Nej, ingen kapad text. Långa utdrag radbryts. |

## 6. Kända begränsningar

- Statistik-fliken har en platshållarikon (info-symbol).
- Appens validering av inventory är grundare än toolchainens. Full schemavalidering hör till FAS 7.
- `@Upsert` av en befintlig rad går inte att pröva på JVM och prövas därför i det instrumenterade testet.
- Fram till FAS 6 finns ingen backup av användardata (ADR 0014).
- Testindexet viker bort diakritiska tecken ("far" träffar "får"). Det avgörs i ADR 0010.

## 7. ADR:er

0001, 0002, 0003, 0005, 0006 och 0014, alla Accepted.

## 8. Återstår

Ingenting i FAS 1. Nästa fas är FAS 2 – Bibliotek.
