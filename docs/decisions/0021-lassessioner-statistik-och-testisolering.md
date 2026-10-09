# ADR 0021: Lässessioner med aktiv tid, statistikens definitioner och isolerade tester

## Status
Accepted (FAS 10, 2026-10-09)

## Context
Tre saker behövde beslut inför statistiken (planens E12).

**Sessionerna gick att förlora och mätte fel sak.** En lässession skrevs först när läsaren stängdes eller gick i bakgrunden. Dog appen dessförinnan fanns ingen rad (noterat i ADR 0018). Dessutom var `durationSeconds` väggklockstid: en telefon som låg tänd med läsaren öppen i en timme gav en timmes lästid.

**Statistiken saknade definitioner.** Planen sade "antal lästa sidor (summa av pagesViewed)", men sedan FAS 4 är det `seenPages` som avgör vilka sidor som är lästa. Två mått på samma sak hade gett olika svar i olika vyer.

**Testerna skrev i användarens data.** De instrumenterade testerna körde i den riktiga appens databas, läs-cache och nedladdningar. Så länge telefonen bara hade testdata gick det bra. I FAS 9 hade användaren börjat läsa, och en testkörning lämnade då en lässession efter sig.

## Decision

### Lässessioner
- En session **skapas när läsaren öppnas** och skrivs direkt. Samma rad uppdateras var 30:e sekund, två sekunder efter ett sidbyte, när appen går i bakgrunden och när läsaren stängs. Vid en krasch eller ett tvångsstopp gäller den senaste skrivningen.
- `durationSeconds` är **aktiv tid**. Klockan går bara när läsaren är i förgrunden och det har förekommit en interaktion under de senaste 5 minuterna. Interaktion är ett sidbyte eller en beröring av sidan (tryck, zoom, panorering, bläddring). Att öppna läsaren och att komma tillbaka till den räknas också.
- `endedAt` är den sista stunden som räknades som aktiv, inte stunden då raden skrevs.
- Längden mäts med en monoton klocka, så att den inte påverkas av att telefonens tid ställs om. `startedAt` är klocktid.
- **Rotation** avslutar inte sessionen. Går läsaren i bakgrunden och kommer tillbaka inom 5 minuter fortsätter samma session, annars avslutas den och en ny börjar.
- En session som slutar med **under 5 sekunder** aktiv tid tas bort. Korta sessioner som blivit kvar efter en krasch städas när appen startar.
- Tabellen `ReadingSession` är oförändrad. Ingen migrering behövdes, och befintliga sessioner ligger kvar. Deras `durationSeconds` är mätt på det äldre sättet.

### Statistikens definitioner
- **Lästa sidor:** summan av antalet satta bitar i `seenPages`, för all användardata.
- **Lästid:** summan av `durationSeconds`.
- **Lästa, påbörjade och olästa nummer:** lässtatus enligt C10, med `readStatusOverride`. Användardata för nummer som saknas i katalogen (D6) räknas som läst (bara genom en manuell markering, eftersom sidantalet är okänt) eller påbörjat.
- **Årgångsprogress:** andel lästa nummer och andel lästa sidor per år och publikation. Lästa sidor över numrets sidantal räknas inte, och nummer med okänt sidantal ingår inte i sidsumman.
- **Aktivitet:** lästid per vecka, måndag–söndag i telefonens tidszon, för de senaste 12 veckorna. En session räknas till den vecka den började.
- **Mest lästa årgång:** den årgång, i någon publikation, där flest sidor är lästa. Vid lika vinner den tidigaste.
- Statistiken **räknas fram varje gång** ur användardatan. Ingenting lagras, och ingen sökstatistik förs.
- Diagrammen ritas med Compose Canvas, utan diagrambibliotek.

### Isolerade tester
- Testpaketet har en egen testkörare, `IsolatedTestRunner`, som startar appen som `TestDmzReaderApp`. Den ger `AppContainer` en kontext där `filesDir`, `cacheDir`, databassökvägen och övriga lagringskataloger pekar in i en katalog för testkörningen, under `app_dmz_test/`.
- Katalogen töms före och tas bort efter körningen, när bakgrundsskrivningarna har hunnit bli klara. Rester efter en avbruten körning tas bort vid nästa.
- Den vanliga testköraren finns inte i testpaketets manifest, och testernas hjälpfunktioner vägrar arbeta om appen inte är `TestDmzReaderApp`.
- `tools/device_test.sh` kör hela sviten och jämför ett fingeravtryck av användarens data före och efter.

## Reason
- Löpande sparning gör förlusten vid en krasch begränsad och känd: högst 30 sekunder. Att skriva en rad i halvminuten kostar ingenting märkbart.
- Aktiv tid är det statistiken ska visa. Gränsen 5 minuter är lång nog för att läsa en tät sida utan att röra skärmen och kort nog för att en bortglömd telefon inte ska ge timmar.
- `seenPages` är redan sanningen om vad som är läst (ADR 0018) och ingår i backupen med OR-sammanslagning. `pagesViewed` är per session och dubbelräknar sidor som läses två gånger.
- Att byta kontext i stället för att göra om varje lagringsklass krävde en enda ändring i appens kod: `DmzReaderApp.storageContext`. Room, DataStore, läs-cachen, nedladdningarna, sökindexet och bildcachen följer alla kontextens kataloger.
- Ett fingeravtryck som jämförs maskinellt är ett bevis som går att upprepa, till skillnad från en försäkran om att testerna städar efter sig.

## Consequences
- Äldre sessioner har väggklockstid i `durationSeconds`. Lästiden före FAS 10 kan därför vara något för hög. Det går inte att räkna om.
- Den som läser en sida i mer än 5 minuter utan att röra skärmen får inte den överskjutande tiden räknad.
- Aktiviteten per vecka lägger hela sessionen i den vecka den började, även om den går över midnatt till måndag.
- En testkörning börjar alltid med en tom databas. Tester som läser i ett nummer får PDF:en från datorn genom `device_test.sh`, eller hämtar den från arkivet.
- Tester som behöver appens riktiga data går inte längre att skriva. Verifiering mot riktiga data görs för hand, och bara genom att läsa.
- Inaktivitetsgränsen och sparintervallet går att ändra i `AppContainer`, och det används av testerna. I appen finns ingen inställning för dem.
