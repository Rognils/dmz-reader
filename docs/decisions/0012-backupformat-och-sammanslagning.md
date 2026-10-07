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
  "backupFormat": 2,
  "exportedAt": "2026-10-07T19:04:50.642Z",
  "appVersion": "0.6.1",
  "issueUserStates": [],
  "bookmarks": [],
  "readingSessions": []
}
```

- **`issueUserStates`:** `issueId`, `isFavorite`, `lastPage`, `maxPageReached`, `seenPages` (Base64 enligt ADR 0018), `firstOpenedAt`, `lastOpenedAt`, `completedAt`, `readStatusOverride` ("READ", "UNREAD" eller null) och `updatedAt`.
- **`bookmarks`:** `bookmarkId` (UUID), `issueId`, `pageNumber`, `title`, `note`, `createdAt`, `updatedAt`, `deletedAt` samt `pdfSize`, `pdfEtag` och `pdfLastModified` (PDF-versionen när bokmärket skapades, för planens C7). Databasens rad-id ingår inte.
- **`appVersion`** är appens `versionName`, läst från `BuildConfig`.
- **`readingSessions`:** `issueId`, `startedAt`, `endedAt`, `startPage`, `endPage`, `pagesViewed` och `durationSeconds`.

PDF:er, cache, omslag, katalogen och `IssueLocalState` ingår inte.

Formatet är skilt från databasens tabeller (egna klasser i `data/backup/BackupFormat.kt`), så att databasen kan ändras utan att gamla backuper slutar gå att läsa.

### Validering
- `backupFormat` måste finnas och vara 1 eller 2. Ett annat värde avvisas med besked om vilket format filen har.
- I format 2 måste varje bokmärke ha ett `bookmarkId`.
- Okända fält hoppas över, så att en senare version kan lägga till fält inom samma format.
- Ogiltiga värden avvisar hela filen: okänd lässtatus, sidnummer under 1, tomt `issueId`.

### Sammanslagning
En import tar aldrig bort något.

- **`IssueUserState`:** raden med senast `updatedAt` vinner för `lastPage`, `readStatusOverride` och `isFavorite`. Vid lika tid gäller telefonens. `seenPages` slås ihop med bitvis OR och `maxPageReached` räknas om. `firstOpenedAt` blir det tidigaste, `lastOpenedAt` och `completedAt` det senaste.
- **Bokmärken (format 2):** matchas på `bookmarkId`. Raden med senast `updatedAt` vinner för titel och anteckning; vid lika tid gäller telefonens. Är `deletedAt` satt på någon av sidorna förblir bokmärket borttaget.
- **Bokmärken (format 1):** saknar identitet och får nya UUID. Ett bokmärke med samma `issueId`, `pageNumber` och `title` som ett befintligt, även ett borttaget, hoppas över. Den regeln används bara för format 1.
- **Sessioner:** en session med samma `issueId` och `startedAt` som en befintlig hoppas över.
- Dubbletter inom själva filen räknas en gång.

Före importen visas en sammanfattning: vad filen innehåller och vad som skulle läggas till eller uppdateras.

### Genomförande
Planen för importen räknas fram som ren logik (`planImport`) och skrivs sedan i en enda Room-transaktion. Sist i transaktionen kontrolleras att antalet rader är det gamla plus det nya. Ett fel rullar tillbaka allt, och användaren får beskedet att ingenting har ändrats.

### Format 2 (v0.6.1)
Format 1 identifierade bokmärken med `issueId`, `pageNumber` och `title`. Det gav två fel: ett bokmärke som fått ny titel blev en dubblett när en äldre backup importerades, och ett borttaget bokmärke kom tillbaka.

Format 2 löser båda:

- **`bookmarkId`** är ett UUID som skapas en gång när bokmärket skapas och sedan aldrig ändras. Befintliga bokmärken fick var sitt vid migreringen till databasversion 7.
- **`deletedAt`** gör raderingen mjuk. Att ta bort ett bokmärke sätter `deletedAt`, bokmärket visas aldrig, men raden finns kvar och följer med i backupen. "Ångra" i snackbaren nollställer `deletedAt`.
- En borttagning vinner alltid vid import, oavsett tider. Ett bokmärke som ångrats på telefonen men är borttaget i en backup blir därför borttaget igen om den backupen importeras.

Exporten skrivs alltid i format 2.

## Reason
- JSON går att läsa och rätta för hand, och filen är liten (några kB).
- Bitvis OR för lästa sidor gör att två telefoners läsning läggs ihop utan konflikter, och att en import aldrig kan minska det lästa.
- "Senast ändrad vinner" behövs bara för de tre fält där två värden inte kan gälla samtidigt.
- Dubblettreglerna gör att samma fil kan importeras hur många gånger som helst.

## Consequences
- **Borttagna bokmärken ligger kvar som dolda rader** och rensas aldrig. De är små, men de blir fler med tiden.
- **En backup i format 1 har de gamla svagheterna kvar:** ett bokmärke som har fått ny titel sedan backupen gjordes blir en dubblett när den importeras.
- **Borttagna favoriter och läsdata har ingen motsvarande spärr.** För dem gäller "senast ändrad vinner".
- `isFavorite`, `lastPage` och `readStatusOverride` följs åt: den nyaste raden bestämmer alla tre.
- Data för nummer som inte finns i arkivindexet exporteras och importeras som allt annat (planens D6).
- Ett nytt fält i databasen som ska följa med i backupen måste läggas till i formatklasserna. Ändras betydelsen av ett befintligt fält krävs `backupFormat` 2.
