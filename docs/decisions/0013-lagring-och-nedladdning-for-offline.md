# ADR 0013: Lagringsplats och nedladdning för offline-läsning

## Status
Accepted

## Context
"Ladda ned" (planens FAS 5) sparar ett nummers läs-PDF permanent, så att det går att läsa utan nät. Filerna är 15–563 MB (median 52 MB), och hela arkivet är cirka 12 GB. Planen lämnade två frågor öppna: var filerna ska ligga, och hur nedladdningen ska överleva att appen lämnas.

Appen har ingen server att fråga och ingen egen enhet för äldre Android att testa på. Testtelefonen kör Android 16.

## Decision

### Lagringsplats
Permanenta PDF:er ligger i **`filesDir/issues/{issueId}.pdf`**.

- På telefoner utan minneskort är `filesDir` och appens externa lagring samma fysiska minne. Extern lagring ger alltså inte mer plats.
- `filesDir` finns alltid. Extern lagring kan saknas eller vara urkopplad.
- `filesDir` ligger på samma filsystem som `cacheDir`. Ett nummer som redan finns i läs-cachen kan därför flyttas med `renameTo`, omedelbart och utan kopiering.
- Ingen lagringsbehörighet behövs.

En pågående nedladdning heter `{issueId}.pdf.part` och döps om till `.pdf` först när storleken stämmer. En `.pdf` är alltid komplett. Samma nedladdningsmotor som läs-cachen används (ADR 0017): Range, If-Range med ETag, kontroll av storlek och av ledigt utrymme.

### Nedladdning i bakgrunden
- **Android 14 och senare (API 34+):** ett användarinitierat dataöverföringsjobb i JobScheduler (UIDT). Det fortsätter när appen lämnas och visar en notis med förlopp i MB och procent och en knapp för att avbryta.
- **Android 8–13 (API 26–33):** ingen bakgrundsnedladdning. Kön betas av medan appen är öppen, och nummervyn visar texten "Håll appen öppen tills nedladdningen är klar".
- **Ingen WorkManager-reserv byggs.** Den hade inte kunnat testas på någon tillgänglig enhet.

### En nedladdning åt gången
Numren ställs i en kö (`downloadState = QUEUED`, ordnade efter `downloadQueuedAt`). Ett enda jobb betar av kön, en fil i taget, så att arkivservern aldrig får mer än en nedladdning åt gången härifrån.

### Nätverkskrav
Inställningen "Ladda ned endast via wifi" (standard på) gör att jobbet kräver ett nät som inte debiteras per mängd. Avstängd tillåter den valfritt nät. "Läs" påverkas inte.

Försvinner nätet stoppar systemet jobbet. Numret står kvar i kön med sin `.part`-fil, och jobbet körs igen när nätet är tillbaka. Systemet väntar allt längre mellan försöken (30 s, 60 s, 120 s …). När appen öppnas startas jobbet därför om direkt.

### Från läs-cachen
Finns numret redan komplett i `cacheDir/pdf/` flyttas filen i stället för att hämtas igen. Filen räknas som verifierad när storleken stämmer med den som mättes vid nedladdningen, eller med inventory om ingen mätning finns. Misslyckas `renameTo` kopieras filen till `.part` i målkatalogen, storleken kontrolleras, kopian döps om och cachefilen tas bort. Ingen nätverkstrafik sker.

Filer i `cacheDir` tillhör en särskild grupp som systemet använder för att räkna appens cache. En flyttad fil får appens vanliga grupp, så att den inte fortsätter räknas som cache.

### Status mot filsystemet
`IssueLocalState` är sanningen om vad som är begärt, och filsystemet om vad som finns. De stäms av:

- **När appen startar:** `DOWNLOADED` utan fil eller med fel storlek nollställs. `DOWNLOADING` (appen stoppades mitt i) ställs tillbaka i kön och fortsätter på sin `.part`. `.part`-filer utan köpost tas bort.
- **När ett nummer öppnas:** samma kontroll för det numret.

En permanent fil används alltid före läs-cachen och före nätet.

### Ett nytt fält
`IssueLocalState` har fått `downloadQueuedAt`, som bestämmer ordningen i kön (databasversion 5, automatisk migrering). Fältet finns inte i planens C3.

## Reason
- Flytt med `renameTo` gör "Ladda ned" omedelbar för ett nummer man just har läst, och sparar servern en nedladdning.
- UIDT är systemets avsedda väg för stora, användarbegärda överföringar på Android 14+. Det har ingen tidsgräns, till skillnad från vanliga jobb.
- En kö i databasen överlever att appen stoppas, och gör "en åt gången" till en egenskap hos datan i stället för hos en tråd.

## Consequences
- **Vägen för Android 8–13 är inte körd på en riktig enhet.** Köhanteringen är densamma och testas på JVM, men själva beteendet (nedladdning bara medan appen är öppen) är overifierat.
- App-specifik lagring raderas när appen avinstalleras. Nedladdningarna går att hämta igen; läsdata skyddas av backup i FAS 6.
- Ett UIDT-jobb får bara startas medan appen syns. Kön startas därför från nummervyn och när appen öppnas, aldrig från bakgrunden.
- "Läs" och en bakgrundsnedladdning kan pågå samtidigt, och då har servern två anslutningar härifrån. "En åt gången" gäller kön.
- Utan notisbehörighet körs nedladdningen ändå, men utan notis. Den syns då bara i appen och i systemets lista över aktiva appar.
- Efter nätbortfall kan det dröja några minuter innan systemet kör jobbet igen, om appen inte öppnas.
