# ADR 0012: Backupformat och regler för sammanslagning

## Status
Accepted

## Context
Appens lagring raderas när appen avinstalleras, och Androids egen backup är avstängd (ADR 0014). Användarens data, det vill säga favoriter, bokmärken, läspositioner, lästa sidor och lässessioner, behöver därför kunna sparas i en fil och läsas in igen (planens E11).

En import ska också gå att göra till en app som redan har data, till exempel efter ett telefonbyte där man har hunnit läsa på båda.

## Decision

### Filen
En JSON-fil, `dmz-reader-backup-ÅÅÅÅ-MM-DD.json`, som användaren sparar och väljer med systemets filväljare (Storage Access Framework). Appen behöver ingen lagringsbehörighet.

```json
{
  "backupFormat": 1,
  "exportedAt": "2026-10-07T19:04:50.642Z",
  "appVersion": "0.1.0",
  "issueUserStates": [],
  "bookmarks": [],
  "readingSessions": []
}
```

- **`issueUserStates`:** `issueId`, `isFavorite`, `lastPage`, `maxPageReached`, `seenPages` (Base64 enligt ADR 0018), `firstOpenedAt`, `lastOpenedAt`, `completedAt`, `readStatusOverride` ("READ", "UNREAD" eller null) och `updatedAt`.
- **`bookmarks`:** `issueId`, `pageNumber`, `title`, `note`, `createdAt`, `updatedAt` samt `pdfSize`, `pdfEtag` och `pdfLastModified` (PDF-versionen när bokmärket skapades, för planens C7). Databasens rad-id ingår inte.
- **`readingSessions`:** `issueId`, `startedAt`, `endedAt`, `startPage`, `endPage`, `pagesViewed` och `durationSeconds`.

PDF:er, cache, omslag, katalogen och `IssueLocalState` ingår inte.

Formatet är skilt från databasens tabeller (egna klasser i `data/backup/BackupFormat.kt`), så att databasen kan ändras utan att gamla backuper slutar gå att läsa.

### Validering
- `backupFormat` måste finnas och vara 1. Ett annat värde avvisas med besked om vilket format filen har.
- Okända fält hoppas över, så att en senare version kan lägga till fält inom samma format.
- Ogiltiga värden avvisar hela filen: okänd lässtatus, sidnummer under 1, tomt `issueId`.

### Sammanslagning
En import tar aldrig bort något.

- **`IssueUserState`:** raden med senast `updatedAt` vinner för `lastPage`, `readStatusOverride` och `isFavorite`. Vid lika tid gäller telefonens. `seenPages` slås ihop med bitvis OR och `maxPageReached` räknas om. `firstOpenedAt` blir det tidigaste, `lastOpenedAt` och `completedAt` det senaste.
- **Bokmärken:** ett bokmärke med samma `issueId`, `pageNumber` och `title` som ett befintligt hoppas över.
- **Sessioner:** en session med samma `issueId` och `startedAt` som en befintlig hoppas över.
- Dubbletter inom själva filen räknas en gång.

Före importen visas en sammanfattning: vad filen innehåller och vad som skulle läggas till eller uppdateras.

### Genomförande
Planen för importen räknas fram som ren logik (`planImport`) och skrivs sedan i en enda Room-transaktion. Sist i transaktionen kontrolleras att antalet rader är det gamla plus det nya. Ett fel rullar tillbaka allt, och användaren får beskedet att ingenting har ändrats.

## Reason
- JSON går att läsa och rätta för hand, och filen är liten (några kB).
- Bitvis OR för lästa sidor gör att två telefoners läsning läggs ihop utan konflikter, och att en import aldrig kan minska det lästa.
- "Senast ändrad vinner" behövs bara för de tre fält där två värden inte kan gälla samtidigt.
- Dubblettreglerna gör att samma fil kan importeras hur många gånger som helst.

## Consequences
- **Borttagningar följer inte med.** Ett bokmärke som tagits bort på telefonen kommer tillbaka om en äldre backup med bokmärket importeras.
- **Ett bokmärke vars titel har ändrats räknas som ett nytt** vid import av en backup med den gamla titeln, eftersom titeln ingår i dubblettregeln.
- `isFavorite`, `lastPage` och `readStatusOverride` följs åt: den nyaste raden bestämmer alla tre.
- Data för nummer som inte finns i arkivindexet exporteras och importeras som allt annat (planens D6).
- Ett nytt fält i databasen som ska följa med i backupen måste läggas till i formatklasserna. Ändras betydelsen av ett befintligt fält krävs `backupFormat` 2.
