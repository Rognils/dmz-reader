# ADR 0019: Import av arkivindex, ändrad arkivfil och metadata-sökning

## Status
Accepted

## Context
Arkivindexet (`archive_inventory.json`) följer med appen i assets. När arkivet får nya nummer, eller en fil byts, ska katalogen kunna uppdateras utan en ny APK (planens D4, D5 och FAS 7). Samtidigt får katalogdata aldrig förstöra användardata, och en trasig fil får aldrig göra appen oanvändbar.

Tre följdfrågor behövde svar: hur filen valideras i appen, vad som händer med en nedladdad PDF när indexet säger att arkivfilen har bytts, och hur sökning ska fungera innan sökindexet finns.

## Decision

### Två källor, en aktiv
Finns ett giltigt importerat index i `filesDir/data/inventory.json` används det. Annars används det inbyggda. Det importerade går före även när appen uppdateras med ett nyare inbyggt index, tills användaren väljer "Återgå till inbyggt index".

### Importflödet
1. Filen väljs i systemets filväljare och kopieras till `inventory.json.tmp` i privat lagring.
2. Den valideras. Ingenting i appen har ändrats, och användaren får se hur många nummer som är nya, borttagna och ändrade.
3. Vid bekräftelse flyttas den aktiva filen undan (`.previous`), den nya läggs på plats, och `Issue`-tabellen ersätts i en enda transaktion.
4. Lyckas det tas den tidigare filen bort. Misslyckas det läggs den tillbaka, och tabellen är orörd.

En import som avbryts mellan filbytet och databasen upptäcks vid nästa start, och den tidigare filen återställs.

`IssueUserState`, `Bookmark`, `ReadingSession` och `IssueLocalState` rörs aldrig.

### Validering i Kotlin
Appen har ingen JSON Schema-motor. Reglerna i `docs/inventory.schema.json` och toolchainens kontroller är i stället skrivna i `InventoryParser`, för de fält appen använder:

- `schemaVersion` är 1, och `datasetVersion` har formen ÅÅÅÅ-MM-DD-NNN
- obligatoriska fält finns och har rätt typ (ett tal inom citattecken är text, inte ett tal)
- `issueId` är unikt och har rätt form
- `relatedIssueId` pekar på ett nummer som finns, och inte på numret självt
- publikationen är deklarerad, `sortKey` stämmer med året, adresser är https, `coverFlags` är kända
- fält som får vara null får också saknas, och okända fält hoppas över

Felen är på svenska och säger vad som är fel och i vilket nummer. Samma exempel som i toolchainens tester prövas i `InventoryValidationTest`, och testindexen i `tools/testdata/` prövas av både Python- och Kotlin-testerna.

### coverAsset är en ledtråd
Ett importerat index kan peka på en miniatyr som inte följer med den installerade appen. `coverAsset` används därför bara om filen finns i assets. Annars används `coverUrl`, och sist platshållaren.

### Ändrad arkivfil och nedladdade nummer
En nedladdad PDF raderas aldrig automatiskt.

- Skiljer sig nedladdningens storlek, ETag eller Last-Modified från indexet visar nummervyn "Arkivfilen har uppdaterats. Din nedladdade kopia är äldre." och knappen "Uppdatera nedladdning". Kopian går att läsa som vanligt.
- Uppdateringen ställs i nedladdningskön och går genom `TransferCoordinator`. Den nya filen hämtas till `.part` och ersätter den gamla med en enda `rename`, först när storleken är kontrollerad.
- Misslyckas eller avbryts uppdateringen finns den gamla kopian kvar, och status är fortfarande nedladdad.
- I databasen betyder `downloadState = DOWNLOADED` tillsammans med `downloadQueuedAt` att en uppdatering väntar. Inget nytt fält behövdes.

Läs-cachen är tillfällig och behandlas annorlunda: en cachefil vars storlek inte stämmer med indexet kastas och hämtas om, en gång per körning av appen.

### Metadata-sökning
Fliken Sök söker i katalogen och i bokmärkena, utan sökindex:

- "1989" är ett år. "1989 12", "1989/12" och "89/12" är år och nummer. "nr 12" är ett nummer i alla årgångar.
- Övriga ord söks i publikationens namn och i bokmärkenas titlar och anteckningar.
- Snabbfiltren Favoriter och Nedladdade begränsar träffarna, och listar allt när sökfältet är tomt.
- En träff på ett nummer öppnar nummervyn. En träff på ett bokmärke öppnar läsaren på sidan.

Sökning i tidningarnas text kommer med `search.db` i FAS 9.

## Reason
- En egen validering är liten och ger felmeddelanden som går att förstå. En schemamotor hade varit ett stort beroende för en fil på 0,2 MB.
- Filbytet före databasen, med den gamla filen kvar tills allt är klart, gör att ett avbrott i vilket steg som helst går att reda ut vid nästa start.
- Att aldrig radera en nedladdning automatiskt följer av att användaren kan sitta utan nät: en äldre kopia är bättre än ingen.

## Consequences
- **Två uppsättningar regler ska hållas lika:** schemat och toolchainen i Python, och `InventoryParser` i Kotlin. De gemensamma testindexen fångar skillnader i de fall som finns där, men inte i alla.
- **Säger indexet fel om en fil** står markeringen "Din nedladdade kopia är äldre" kvar även efter en uppdatering, eftersom den hämtade filen fortfarande skiljer sig från indexet.
- Nya nummer i ett importerat index saknar inbyggd miniatyr och visar omslaget från webben, tills nästa appversion.
- Ett nummer som försvinner ur indexet visas som "Saknas i aktuellt arkivindex" i Favoriter, med favoritmarkering, läsdata och bokmärken, och kopplas ihop med sin data igen om det kommer tillbaka (planens D6).
- Sökningen känner inte till artiklar eller innehåll. En sökning på ett ämne ger bara träff om ett bokmärke nämner det.
