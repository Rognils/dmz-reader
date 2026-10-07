# ADR 0018: Lästa sidor som bitmängd, lässtatus och sparad läsposition

## Status
Accepted

## Context
Planens ursprungliga C10 räknade läsprogress som `maxPageReached / sidantal`. Det ger fel bild så fort man hoppar: den som läser sex sidor och sedan drar reglaget till sida 25 av 28 skulle stå på 89 %. C64 beslutade före FAS 4 att progress ska räkna de sidor som faktiskt har lästs (planens C4, C10 och E11, bilaga E 2026-10-07).

Samtidigt ska appen minnas läspositionen, även om den stängs hårt, utan att databasen skrivs från renderingens eller gesternas kod.

## Decision

### seenPages
`IssueUserState` har fältet `seenPages`: en bitmängd med en bit per PDF-sidindex, lagrad som text.

- Sida p (start på 1) är bit (p − 1) mod 8 i byte (p − 1) div 8, minst signifikanta biten först.
- Bytevektorn lagras som Base64 med standardalfabetet och utfyllnad. Avslutande nollbyte tas bort, så att samma mängd alltid ger samma text. En tom mängd lagras som `null`.
- Exempel: sidorna 1–6 och 25 är byten `3F 00 00 01`, som blir `PwAAAQ==`.
- Högsta sidnummer är 4 096. Trasig text läses som en tom mängd i stället för att krascha appen.
- `maxPageReached` är den högsta sidan i `seenPages`. Ett äldre värde utan `seenPages` sänks aldrig.
- Två mängder slås ihop med bitvis OR (import av backup i FAS 6).

Formatet ingår i backupformatet från FAS 6 och får inte ändras utan ny formatversion.

### När en sida räknas som läst
En sida räknas som läst när den har visats i 1,5 sekunder i följd. I uppslag räknas båda sidorna.

- Klockan startar först när sidan visas i full skärmupplösning, inte på förhandsbilden.
- Klockan pausas när appen ligger i bakgrunden eller skärmen är släckt, och fortsätter där den var.
- Byter skärmen sidor börjar tiden om från noll. Snabbläddring med svep, reglage eller miniatyrer räknas därför inte, men sidan man stannar på räknas.

### Status och progress
`progress = lästa sidor bland 1..sidantal / sidantal`, med sidantalet `Issue.pageCount ?: IssueLocalState.observedPageCount`. Status är oläst (inga lästa sidor), påbörjad eller läst (minst 90 %). Med okänt sidantal visas "Påbörjad" utan procent. `readStatusOverride` går före den automatiska statusen och ändrar aldrig `seenPages`, `lastPage` eller `maxPageReached`. `completedAt` sätts första gången numret blir läst.

**Tolkning av "UNREAD nollställs när en ny sida räknas som läst":** markeringen tas bort när en sida räknas som läst som antingen inte var läst förut, eller är en annan sida än den läsaren öppnades på. Annars skulle ett helt genomläst nummer som markerats som oläst aldrig kunna bli påbörjat igen, och ett nummer skulle bli påbörjat bara av att öppnas och stängas.

### Läsposition
`lastPage` är läsarens ankarsida, samma som gör att stående sida 7 blir uppslag 6–7 och åter sida 7.

- Positionen gäller i minnet direkt.
- `ReadingRecorder` skriver den till Room 1 sekund efter den första ändring som väntar. Fler ändringar under väntan skjuter inte upp skrivningen, så även oavbruten bläddring skrivs ungefär en gång per sekund.
- Den skrivs omedelbart när läsaren stängs och vid `onStop` (bakgrund, släckt skärm).
- Skrivningarna köas och utförs i tur och ordning i en coroutine som lever lika länge som appen, så att en skrivning vid stängning hinner bli klar.
- Varje skrivning läser raden, ändrar sina fält och skriver tillbaka under ett lås (`ReadingRepository.update`). Läsaren och nummervyn kan därför inte skriva över varandras fält, och `isFavorite` rörs aldrig.

### Sessioner
En `ReadingSession` börjar när läsaren visas och slutar när den stängs eller appen går i bakgrunden. Rotation avslutar den inte. `pagesViewed` är antalet olika sidor som räknats som lästa under sessionen. Sessioner kortare än 5 sekunder sparas inte.

### Databasen
Version 4 lägger till kolumnen `seenPages` med en automatisk migrering. Befintliga rader ändras inte.

## Reason
- En bitmängd är liten (ett nummer på 116 sidor ryms i 20 tecken), går att slå ihop utan konflikter och ger både progress och `maxPageReached`.
- Text i en kolumn kräver ingen ny tabell och följer med i backupens JSON utan omvandling.
- Fördröjd skrivning håller databasen borta från gesterna, och skrivningen var sekund gör att ett tvångsstopp som mest tappar den sista sekundens bläddring.

## Consequences
- Progress i läsarens topprad visar andelen lästa sidor ("25 % läst"), inte läget i numret.
- Den som bläddrar igenom ett nummer snabbare än 1,5 s per sida får inga lästa sidor. Då finns "Markera som läst".
- **En session går förlorad om appen tvångsstoppas eller kraschar medan läsaren är öppen.** Läspositionen och de lästa sidorna är redan sparade; bara sessionsraden saknas.
- Läsdata för ett nummer som inte längre finns i katalogen ligger kvar orörd (planens D6).
- `IssueUserStateDao` har `insert` och `update` vid sidan av `upsert`, eftersom konfliktgrenen i `@Upsert` inte går att köra i JVM-testerna.
