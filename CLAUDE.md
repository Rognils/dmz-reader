# CLAUDE.md – DMZ Reader

Privat Android-app (Kotlin + Jetpack Compose) för att läsa DMZArkivets digitaliserade tidskrifter, plus en Python-toolchain som förbereder arkivdata.

**Hela planen:** `DMZ_READER_PROJEKTPLAN_V3.md` (status BASELINE, fryst). Läs aktuell fas där och berörda ADR:er i `docs/decisions/` innan du börjar. Planen skrivs inte om; avvikelser blir ADR och förslag i fasrapporten.

**Teknik att notera:** Appen använder **Room 3** (`androidx.room3`) med `BundledSQLiteDriver` och FTS5. Room 3 kräver en `SQLiteDriver` och har inte SupportSQLite-API:erna. Följ Room 3:s dokumentation, inte Room 2-mönster.

**Överlämning 2026-10-30:** Läs [Codex granskning och rättningar](docs/reports/codex-granskning-2026-10-30.md) och ADR 0025 före fortsatt releasearbete. Rättningarna ligger på `fix/pre-1.0-review`, efter den frysta RC4. Rapporten beskriver tester, kvarvarande enhetskontroller och varför den befintliga `release/1.0.0` inte innehåller rättningarna.

## Absoluta regler

1. **Implementera bara aktuell fas.** Bygg inget "lite i förväg" för senare faser.
2. **Användardata förstörs aldrig av katalogdata.** Import av inventory eller search.db får aldrig radera eller ändra `IssueUserState`, `Bookmark` eller `ReadingSession`.
3. **Inga främmande nycklar med kaskadradering** från användardata eller `IssueLocalState` till `Issue`.
4. **Okända arkivformat gissas aldrig tyst.** De markeras som specialfall och rapporteras.
5. **Appen förstår aldrig rå FTP-struktur.** All sådan logik ligger i `tools/`.
6. **Belasta arkivservern försiktigt.** En FTP-anslutning åt gången, pauser, cache, aldrig bulknedladdning.
7. **Sidnummer är PDF-sidindex med start på 1.** Överallt.
8. **`pageCount` kan vara null.** Ingen kod får anta motsatsen.
9. **Manuell läst/oläst ligger i `readStatusOverride`** och ändrar aldrig `lastPage` eller `maxPageReached`.
10. **Ändra inte planen på egen hand.** Föreslå ändringar i fasrapporten.
11. **Stanna och fråga** när planen och verkligheten inte stämmer.
12. **Automatiska tester och testsviter får aldrig hämta från arkivservern** (dmzarkivet.se, HTTP eller FTP). Regressionstester använder MockWebServer eller en fejkad FTP-server. Mot den riktiga servern görs bara enstaka manuella kontroller, och mängden data anges i rapporten.

## Datakategorier

| Tabell | Typ | Skrivs över vid import | I backup |
|---|---|---|---|
| `Issue` | Katalog | Ja | Nej |
| `IssueLocalState` | Telefonens egen data | Nej | Nej |
| `IssueUserState`, `Bookmark`, `ReadingSession` | Användarens data | Aldrig | Ja |

## Arbetssätt

- en funktionsgren per fas, separata commits, merge till `main` först när acceptanskriterierna är uppfyllda
- viktiga beslut dokumenteras som ADR enligt mallen i planens G3
- kör befintliga tester före och efter ändringar
- nedladdade PDF:er, PDF-cachen och `search.db` committas aldrig

## Rapport efter varje fas

1. sammanfattning
2. skapade och ändrade filer
3. testresultat
4. acceptanskriterier, avbockade
5. kända begränsningar
6. skapade ADR:er
7. vad som återstår
8. förslag på nästa fas och eventuella planändringar
