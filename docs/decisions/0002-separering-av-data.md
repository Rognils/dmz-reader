# ADR 0002: Katalogdata, lokal data och användardata hålls isär

## Status
Accepted

## Context
Appen har tre slags data med olika livslängd (planens C1):

- **Katalogdata** kommer från arkivet via inventory och byts ut helt när ett nytt inventory läses in.
- **Lokal data** tar telefonen fram själv: uppmätt sidantal, nedladdningsstatus.
- **Användardata** är det användaren skapat: läsposition, favoriter, bokmärken, lässessioner.

Om de blandas kan en import av ny katalogdata förstöra användarens data. Det är projektets allvarligaste tänkbara fel, eftersom datan inte går att återskapa.

## Decision
Fem tabeller i Room, i tre kategorier:

| Tabell | Kategori | Skrivs av import | I backup |
|---|---|---|---|
| `Issue` (`IssueEntity`) | Katalog | Ja, ersätts helt | Nej |
| `IssueLocalState` | Lokal | Nej | Nej |
| `IssueUserState`, `Bookmark`, `ReadingSession` | Användare | Aldrig | Ja |

Regler som koden följer:

1. **Inga främmande nycklar** mellan tabellerna. De hör ihop enbart genom värdet i `issueId`. Därmed finns ingen kaskadradering som kan utlösas när `Issue` töms.
2. **Importen skriver bara till `Issue`**, i en enda transaktion (`IssueDao.replaceAll`), och till uppgiften om aktiv `datasetVersion`. `InventoryImporter` har ingen åtkomst till de andra tabellernas DAO:er.
3. **DTO skild från entitet.** `InventoryIssueDto` (kotlinx.serialization) speglar `docs/inventory.schema.json` fullt ut. `IssueEntity` är en delmängd: allt utom `sourceNames`, som bara finns för spårbarhet i toolchainen. Översättningen sker i `InventoryIssueDto.toEntity()`.
4. Listfälten `flags` och `coverFlags` lagras som JSON-text via en `@ColumnTypeConverter`.
5. Användardata för ett nummer som försvunnit ur inventory ligger kvar och kopplas ihop igen om numret kommer tillbaka (planens D6).

## Reason
- Utan främmande nycklar finns det ingen mekanism i databasen som kan radera användardata som följd av en katalogändring. Skyddet ligger i strukturen och hänger inte på att varje fråga skrivs rätt.
- Att DTO och entitet är olika klasser gör att filformatet och databasschemat kan ändras var för sig. Ett nytt valfritt fält i inventory kräver ingen databasmigrering förrän appen vill använda det.
- `sourceNames` är den största delen av varje post och används aldrig av appen.

## Consequences
- Databasen garanterar inte att ett `issueId` i användardata finns i `Issue`. Appen måste hantera poster utan katalogpost (visas i FAS 6 och 7 som "Saknas i aktuellt arkivindex").
- Frågor som kombinerar katalog och användardata får skrivas som vanliga JOIN utan stöd av relationer.
- Reglerna prövas av tester: `InventoryImporterTest` (import lämnar användardata orörd, även när numret försvinner), `DatabaseSchemaTest` (ingen tabell har en främmande nyckel) och motsvarande instrumenterade tester.
- `IssueLocalState` har inget fält för genererat omslag. Omslag kommer från `coverUrl` (ADR 0008).
