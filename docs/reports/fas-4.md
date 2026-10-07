# Fasrapport: FAS 4 – Lässtatus

Avslutad 2026-10-07. Verifierad på OnePlus 7 Pro (GM1911), Android 16 (API 36, LineageOS 23.2), skärm 1 440 × 3 120 px.

## 1. Sammanfattning

- **Läsposition:** läsaren öppnas där man senast var, via "Fortsätt läsa", "Läs" eller nummervyn. Positionen skrivs till databasen ungefär en gång per sekund vid bläddring och direkt när läsaren stängs eller appen går i bakgrunden.
- **Lästa sidor:** en sida räknas som läst efter 1,5 sekunder i följd i full skärmupplösning. De lagras som bitmängd i `seenPages`, och progress är antalet lästa sidor delat med sidantalet.
- **Manuell status:** "Markera som läst", "Markera som oläst" och "Återställ" i nummervyn.
- **Sessioner:** sparas när läsaren stängs eller appen går i bakgrunden, om de är minst 5 sekunder.
- **Biblioteket:** "Fortsätt läsa" och "Senast lästa" visas när de har data, och årgångskorten har ett progress-streck.
- **Årgångsvyn:** "X / Y lästa", filterchips, progress-streck under omslagen och lästa nummer tonade till 60 %.
- **Renderingskön:** aktuell sida går först, sedan nästa, sedan föregående.
- **Databasen:** version 4, med automatisk migrering.

## 2. Skapade och ändrade filer

**Nya**

- `domain/SeenPages.kt`, `domain/ReadingStatus.kt`, `domain/ReadingTracking.kt`
- `data/repository/ReadingRepository.kt`, `data/repository/ReadingRecorder.kt`
- `app/schemas/…/4.json`, `app/proguard-rules.pro`
- Tester: `SeenPagesTest`, `ReadingStatusTest`, `ReadingTrackingTest`, `ReadingRecorderTest` (med `ReadingRepositoryTest`), `ReadingStateDeviceTest`, `DeviceTestSupport.kt`
- `tools/device_restart_test.sh`
- `docs/decisions/0018-lasta-sidor-och-lasstatus.md`, `docs/screenshots/fas4/`

**Ändrade**

- `data/db/` (entitet, DAO:er, version 4), `data/pdf/PdfDocument.kt` (prioritetskö), `data/repository/LibraryRepository.kt`, `AppContainer.kt`
- `ui/reader/` (vymodell, skärm, zoomkomponent), `ui/library/`, `ui/year/`, `ui/issue/`, `ui/components/Components.kt`, `ui/DmzReaderRoot.kt`
- `app/build.gradle.kts` (release-variant med R8)
- `MigrationTest`, `SpreadsTest`, `ReaderLogicTest`, `ReaderDeviceTest`, `LibraryNavigationTest`, `CoverFallbackNetworkTest`
- `docs/decisions/0011-pdf-rendering-och-utsnitt.md`
- `DMZ_READER_PROJEKTPLAN_V3.md` (G7, C4, C10, E11 och bilaga E, enligt beslut)

## 3. Testresultat

| Test | Hur | Resultat |
|---|---|---|
| JVM-tester | `./gradlew :app:testDebugUnitTest` | 187 av 187 |
| Instrumenterade tester på telefonen | `adb shell am instrument -w se.c64.dmzreader.test/androidx.test.runner.AndroidJUnitRunner` | 22 av 22 (plus 9 som bara körs på begäran) |
| Omstartstest med tvångsstopp | `tools/device_restart_test.sh` | 2 av 2 delar |
| Mätning av förrenderingen | `-e dmzVerify true -e class …ReadingStateDeviceTest#verifyPrerender199514` | genomförd |
| Python-tester | `.venv/bin/python -m unittest discover -s tools/tests` | 125 av 125 |
| Lint | `./gradlew :app:lintDebug` | 0 fel, 6 varningar |

JVM-testerna täcker det som begärdes:

- **seenPages:** bitordning, Base64, avslutande nollbyte, gränser (sida 0, negativa, över 4 096), trasig text.
- **Progress och status:** 90 %-gränsen, okänt sidantal, override åt båda håll, nollställning av UNREAD, `completedAt`.
- **Tidtagning:** 1,5 s, uppslag, snabbläddring genom 20 sidor räknar ingenting, paus i bakgrunden, ingen tid före rendering.
- **Ankarsida i uppslag**, **sammanslagning med OR**, **sessioner** och **renderingsordning**.
- **Fördröjd skrivning:** inget skrivs före 1 s, en skrivning för många sidbyten, tvingad skrivning med `flush` och vid stängning, ordning mellan skrivningar, och att favorit och override inte rörs.
- **Migrering:** från version 1 och från version 3, med kontroll att all befintlig data finns kvar.

De instrumenterade testerna:

- `progressCountsReadPagesAndManualStatusKeepsHistory` läser tre sidor, snabbläddrar förbi fem och läser en till. Den kontrollerar att exakt sidorna 1, 2, 3 och 9 är lästa, och prövar sedan alla tre statusknapparna mot databasen.
- Omstartstestet läser fram till sida 4 och kontrollerar att positionen är skriven medan läsaren fortfarande är öppen. Efter `am force-stop` öppnar "Fortsätt läsa" sida 4 med 14 % läst i en ny process.

Ett äldre test, `coverWithoutThumbnailIsLoadedFromItsUrl`, misslyckades först. Bilden låg kvar i telefonens diskcache från en tidigare körning, så inget nätanrop gjordes. Testet tar nu bort bilden ur cachen innan det börjar. Felet låg i testet, inte i appen.

## 4. Acceptanskriterier

- [x] **Rätt sida återställs efter att appen stängts och startats om.** Tvångsstopp mitt i läsningen på sida 26, omstart, "Fortsätt läsa" öppnade sida 26.
- [x] **Att hoppa tillbaka sänker inte progressen.** Progress räknar lästa sidor, och `maxPageReached` sänks aldrig (JVM-test).
- [x] **"Markera som oläst" ändrar status utan att läshistoriken försvinner, och nollställs när numret läses igen.** Verifierat på telefonen mot databasen.
- [x] **Nummer med okänt sidantal visar status utan procent.** Testat i logiken ("Påbörjad"). Inte sett på telefonen, eftersom sidantalet blir känt så fort ett nummer har öppnats.
- [x] **Sessioner sparas med rimlig längd.** Till exempel 78 s med 7 lästa sidor, och 282 s med 84.

## 5. Verifiering på telefonen

**Sex sidor och ett hopp (1986 nr 1, 28 sidor)**

| Steg | Sidangivelse | `seenPages` |
|---|---|---|
| Sex sidor lästa, cirka 3 s per sida | Sida 6 av 28 · 21 % läst | sidorna 1–6 |
| Hopp med reglaget till sida 25, stannar där | Sida 25 av 28 · 25 % läst | sidorna 1–6 och 25 (`PwAAAQ==`) |

Sju av 28 sidor är 25 %. Med den gamla regeln hade det stått 89 %. Sida 25 räknas eftersom läsaren stannade där.

**Markera som oläst**

- Nummervyn visade "Oläst · 25 % (markerad)", och knappen var fortfarande "Fortsätt på sida 25".
- I databasen: `readStatusOverride = UNREAD`, medan `seenPages`, `lastPage` och `maxPageReached` var oförändrade.
- Numret försvann ur "Fortsätt läsa", där 1989 nr 6 visades i stället.
- Tre sekunder på sida 25 i läsaren ändrade ingenting. Efter sidbyte till sida 26 var markeringen borta.

**Tvångsstopp**

- `am force-stop` 2,8 s efter sidbytet till sida 26. Databasen hade då `lastPage = 26` och sida 26 som läst.
- Efter omstart visade biblioteket "Sida 26 av 28", och läsaren öppnade "Sida 26 av 28 · 29 % läst".

**Helskärm**

Status- och navigationsfälten är dolda vid vanlig läsning. `dumpsys window` visar `visible=false` för båda, också efter att appen legat i bakgrunden. Ingen rättning behövdes; läsaren döljer nu ändå fälten på nytt varje gång den kommer tillbaka.

Logcat: inga krascher och inget `OutOfMemoryError`.

## 6. Mätning av förrenderingen

20 sidbyten framåt i 1995 nr 14 med cirka 3 s mellanrum, debug-bygge:

| Nästa sida när sidbytet började | Antal |
|---|---|
| Färdigrenderad i full skärmupplösning | 20 |
| Bara förhandsbild | 0 |
| Ingenting | 0 |

Nästa sida blir klar 1,1–1,3 s efter ett sidbyte (förhandsbild 0,1–0,4 s, grundbild 0,7–1,2 s). Den som bläddrar fortare än så får en förhandsbild först. Djupet är kvar på ±1.

Tidpunkten mäts när sidbytet börjar, inte när animationen är klar.

## 7. Release mot debug

Release-varianten har R8 och resurskrympning och är signerad med debug-nyckeln. APK:n är 23,9 MB mot 36,9 MB för debug.

Renderingstid i ms, mätt genom att läsa hela numret på telefonen med samma styrning för båda byggena:

| | Debug median | Debug max | Release median | Release max |
|---|---|---|---|---|
| 1986 nr 1, sida i skärmupplösning | 105 | 109 | 105 | 110 |
| 1986 nr 1, förhandsbild | 94 | 101 | 95 | 103 |
| 1995 nr 14, sida i skärmupplösning | 832 | 1 444 | 830 | 1 431 |
| 1995 nr 14, förhandsbild | 241 | 452 | 241 | 464 |

Det är ingen skillnad. Renderingen sker i telefonens egen PDF-motor, inte i appens kod, så R8 påverkar den inte. Siffrorna från FAS 3 gäller alltså även för release.

- 1995 nr 15 mättes inte, eftersom filen inte finns i cachen.
- Metoden skiljer sig från FAS 3 på en punkt: numren lästes med tryck via `adb` i stället för ett instrumenterat test, eftersom testpaketet inte går att köra mot en R8-krympt app utan mer arbete. Det som mäts är samma tidtagning inne i renderaren.

## 8. Skärmbilder

I `docs/screenshots/fas4/`:

| Fil | Visar |
|---|---|
| `01-bibliotek-fortsatt-lasa.png` | Biblioteket med "Fortsätt läsa" och progress-streck på årgångarna |
| `01-bibliotek-senast-lasta.png` | "Senast lästa" |
| `02-argang-1986-alla.png` | Årgång med "0 / 4 lästa", filterchips och progress under Nr 1 |
| `02-argang-1986-filter-paborjade.png` | Filtret "Påbörjade" |
| `02-argang-1995-last-nummer-tonat.png` | Läst nummer (Nr 14) tonat, med fullt streck |
| `03-nummervy-paborjad.png` | "Fortsätt på sida 25", "Påbörjad · 25 %" |
| `03-nummervy-markerad-som-olast.png` | "Oläst · 25 % (markerad)" |
| `04-lasaren-helskarm.png` | Läsaren utan systemfält |
| `05-lasaren-sex-sidor-lasta.png` | "Sida 6 av 28 · 21 % läst" |
| `06-lasaren-efter-hopp-till-sida-25.png` | "Sida 25 av 28 · 25 % läst" |
| `07-lasaren-efter-omstart-sida-26.png` | Efter tvångsstopp och omstart |

## 9. Kända begränsningar och avvikelser

- **En session går förlorad om appen tvångsstoppas eller kraschar medan läsaren är öppen.** Läspositionen och de lästa sidorna är sparade; bara sessionsraden saknas.
- **Tolkning av "UNREAD nollställs när en ny sida räknas som läst":** markeringen tas bort när en sida räknas som läst som inte var läst förut, eller som är en annan än den läsaren öppnades på. Annars kunde ett helt genomläst nummer aldrig lämna "oläst". Se ADR 0018.
- **Procenttalet i läsarens topprad betyder nu andelen lästa sidor** ("25 % läst"), inte läget i numret.
- **"Fortsätt läsa" visar ett nummer**, det senast öppnade som är påbörjat, enligt planens E4. "Senast lästa" visar upp till tolv andra.
- **Filterchipsen är Alla, Olästa, Påbörjade och Lästa**, enligt beslutet. Planens E5 nämner inte "Lästa".
- **Nummervyn visar det uppmätta sidantalet** när numret har öppnats ("48 sidor" i stället för "≈ 48 sidor").
- **Release-varianten är signerad med debug-nyckeln.** En riktig nyckel behövs först om appen ska distribueras.
- **De instrumenterade testerna nollställer läsdata** för 1986 nr 1, 1995 nr 14 och 1995 nr 15 på telefonen. Favoritmarkeringen behålls.
- **Tidtagningen är testad med styrda tryck**, inte med en människas läsning.

## 10. ADR:er

- **0018** (ny): lästa sidor som bitmängd, lässtatus och sparad läsposition.
- **0011** (uppdaterad): renderingsköns ordning, mätningen av förrenderingen, G7 och jämförelsen med release.

## 11. Läget på telefonen

- Debug-bygget är installerat igen efter mätningen av release.
- Läsdata från verifieringen ligger kvar: 1986 nr 1 (påbörjad, sida 26), 1989 nr 6 (3 sidor) och 1995 nr 14 (läst), samt 10 sessioner. Allt kommer från testkörningarna.
- PDF-cachen är 151 MB (tre nummer) och miniatyrerna 1,5 MB.

Läsdata för enskilda nummer kan nollställas med:

```
adb shell am instrument -w -e class se.c64.dmzreader.ReadingStateDeviceTest#resetReadingStateOnRequest \
  -e dmzReset datormagazin-1986-01,datormagazin-1989-06,datormagazin-1995-14 \
  se.c64.dmzreader.test/androidx.test.runner.AndroidJUnitRunner
```

Sessionerna tas inte bort av det.

## 12. Vad som återstår

Ingenting i FAS 4. FAS 5 är inte påbörjad.

## 13. Förslag inför FAS 5

1. **Lagringsplats (ADR 0013):** filerna är 15–563 MB, median 52 MB, och hela arkivet är cirka 12 GB. Förslag: `filesDir/pdf/` för permanenta nedladdningar, med tydlig visning av använt utrymme, och läs-cachen kvar i `cacheDir`.
2. **Flytta i stället för att hämta igen:** ett nummer som redan ligger i läs-cachen bör kunna göras permanent genom att filen flyttas.
3. **Nedladdning i bakgrunden:** dagens nedladdning lever bara så länge läsaren är öppen. En 563 MB-fil behöver en förgrundstjänst eller WorkManager för att överleva att appen lämnas. Det är fasens största beslut.
4. **Planens E5:** lägg till "Lästa" bland filterchipsen, så att planen stämmer med appen.
5. **Sessioner vid tvångsstopp:** om statistiken i FAS 10 ska vara tillförlitlig kan en pågående session sparas löpande, till exempel en gång i minuten.
6. **Testdata på telefonen:** bestäm om läsdata och sessioner från verifieringen ska rensas innan du börjar använda appen på riktigt. Det enklaste är att rensa appens data en gång när FAS 6 (backup) är klar, eller att jag lägger till en rensning av sessioner i testverktyget.
