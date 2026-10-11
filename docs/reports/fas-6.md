# Fasrapport: v0.5.1 och FAS 6 – Personligt bibliotek och backup

> **Skärmbilderna som nämns här finns inte i repot.** De ligger lokalt hos projektets ägare, se [docs/skarmbilder.md](../skarmbilder.md).

Avslutad 2026-10-07. Verifierad på OnePlus 7 Pro (GM1911), Android 16 (API 36, LineageOS 23.2), debug-bygge.

## 1. Sammanfattning

- **v0.5.1:** en PDF-överföring åt gången i hela appen (`TransferCoordinator`), och det valda filterchipet rullas in i bild.
- **Favoritnummer:** hjärta i nummervyn, märke på omslagen i årgångsvyn och raden Favoriter i biblioteket.
- **Bokmärken:** stjärnan i läsaren skapar eller tar bort ett bokmärke på ankarsidan, med snackbar ("Lägg till titel" respektive "Ångra"). Bokmärken redigeras och tas bort från listorna.
- **Fliken Favoriter:** favoritnummer som omslagsrutnät och bokmärken grupperade per nummer. Ett tryck öppnar läsaren på exakt den sidan.
- **Markeringar:** "Arkivfilen har uppdaterats – bokmärken kan peka fel" (C7) och "Saknas i aktuellt arkivindex" (D6).
- **Säkerhetskopia:** export och import via systemets filväljare, med sammanfattning före import och sammanslagning som aldrig tar bort något.
- **Databasen:** version 6 (PDF-versionen i `Bookmark`), med automatisk migrering.

## 2. Steg 0: din data

Innan något annat kopierades `databases/` och `files/datastore/` till `~/dmz-backup-före-fas6/appdata.tar` (SHA-256 i filen bredvid). Appen kördes inte då.

| | Före fasen | Efter återställningen |
|---|---|---|
| `IssueUserState` | 0 | 0 |
| `Bookmark` | 0 | 0 |
| `ReadingSession` | 0 | 0 |
| Nedladdningar | inga | inga |

- Du hade alltså inte börjat använda appen: tabellerna var tomma och inga nummer var nedladdade.
- Vid fasens slut togs all testdata bort och kopian lades tillbaka. Alla fem filerna är byte för byte identiska med kopian (jämfört med SHA-256).
- Databasen i kopian har version 5. Appen som nu är installerad har version 6 och lägger till de nya kolumnerna första gången den startas. Jag har inte startat appen efter återställningen.
- Kopian ligger kvar på datorn. Den kan tas bort när du har öppnat appen och sett att allt stämmer.

## 3. Steg 1: v0.5.1

### En PDF-överföring åt gången
All PDF-trafik går genom `TransferCoordinator`.

- **Läs på ett annat nummer** medan kön laddar ned: köns förfrågan avbryts, `.part` ligger kvar, läsningen körs och kön fortsätter sedan med Range.
- **Läs på samma nummer** som laddas ned: nedladdningsvyn visar den pågående nedladdningens förlopp, och läsaren öppnar den permanenta filen när den är klar.
- **Läs på ett nummer längre bak i kön** hämtar det till läs-cachen. När kön kommer fram flyttas filen därifrån, utan en andra hämtning.

JVM-testet `TransferCoordinationTest` mäter antalet samtidiga förfrågningar i HTTP-klienten. Det är aldrig fler än en, också med två läsningar och en kö samtidigt.

På telefonen (1995 nr 14 i kön, Fortsätt läsa på 1986 nr 1 mitt i):

```
20:39:40.312 UIDT-jobbet startar
20:39:42.640 datormagazin-1995-14: pausad för en läsning, fortsätter efteråt
20:39:44.200 datormagazin-1986-01: nedladdad, 15105472 byte
20:39:51.747 datormagazin-1995-14: nedladdad, 115605773 byte, återupptagen=true
```

Läs på samma nummer gav en enda hämtning, och nedladdningsvyn visade "62,3 av 115,6 MB · 53 %".

### Filterchipet
Chipet Offline låg delvis utanför skärmen (texten vid x = 1273–1370 av 1440). Efter ett tryck rullas raden så att hela chipet syns (1167–1314).

### Kontroller på telefonen (ingen rättning behövdes)

| Kontroll | Resultat |
|---|---|
| Stoppa en aktiv nedladdning som aktivitetshanteraren gör | Jobbet stoppades med användarskälet, processen avslutades, `.part` (46 MB) låg kvar och numret stod som `QUEUED`. Ingenting hände medan appen var stängd. När appen öppnades fortsatte nedladdningen från `.part` till komplett fil. Inget hängde i "Laddar ned". |
| "Utan notis" med återkallad notisbehörighet | Frågan visades, jag valde "Utan notis", tryckte Hem, och nedladdningen blev klar utan notis. Behörigheten är återställd. |

Stoppet gjordes med de kommandon knappen använder (`cmd jobscheduler stop` med användarskälet och `am stop-app`), inte med själva knappen. En nedladdning tar fyra sekunder, och det räcker inte för att hinna navigera dit.

### Planen
`downloadQueuedAt` är tillagt i C3 och regeln om en överföring åt gången i E9, loggat i bilaga E.

## 4. Skapade och ändrade filer

**Nya**

- `data/pdf/TransferCoordinator.kt`
- `data/backup/BackupFormat.kt`, `BackupMerge.kt`, `BackupRepository.kt`
- `data/repository/BookmarkRepository.kt`
- `domain/PdfVersion.kt`, `domain/PersonalLibrary.kt`
- `ui/components/Bookmarks.kt`
- `app/schemas/…/6.json`
- Tester: `TransferCoordinationTest`, `BackupMergeTest`, `BackupRepositoryTest`, `BookmarkRepositoryTest`, `PersonalLibraryTest`, `PdfVersionTest`, `SettingsTextTest`, `BookmarkDeviceTest`
- `docs/decisions/0012-backupformat-och-sammanslagning.md`, `docs/screenshots/fas6/`

**Ändrade**

- `data/offline/OfflineRepository.kt`, `data/pdf/PdfRepository.kt`
- `data/db/` (kolumner i `Bookmark`, DAO:er, `BackupDao`, version 6), `data/settings/` (`BackupPreferences`), `data/repository/ReadingRepository.kt`, `LibraryRepository.kt`
- `ui/reader/`, `ui/issue/`, `ui/favorites/`, `ui/library/`, `ui/year/`, `ui/settings/`, `ui/DmzReaderRoot.kt`, `AppContainer.kt`
- `MigrationTest`
- `docs/decisions/0013-…`, `DMZ_READER_PROJEKTPLAN_V3.md` (C3, E9 och bilaga E, enligt beslut)

## 5. Testresultat

| Test | Hur | Resultat |
|---|---|---|
| JVM-tester | `./gradlew :app:testDebugUnitTest` | 258 av 258 |
| Instrumenterade tester på telefonen | `adb shell am instrument -w se.c64.dmzreader.test/androidx.test.runner.AndroidJUnitRunner` | 24 av 24 (plus 14 som bara körs på begäran) |
| Omstartstest med tvångsstopp | `tools/device_restart_test.sh` | 2 av 2 delar |
| Python-tester | `.venv/bin/python -m unittest discover -s tools/tests` | 125 av 125 |
| Lint | `./gradlew :app:lintDebug` | 0 fel, 6 varningar |

Sammanslagningen byggdes som ren logik med JVM-tester innan gränssnittet och databaskopplingen skrevs. Testerna skrevs i samma steg som logiken, inte strikt före den.

**JVM-testerna täcker:**

- **Sammanslagning:** senaste `updatedAt` vinner åt båda håll, lika tid behåller telefonens värden, OR för `seenPages`, omräknad `maxPageReached`, tidigaste och senaste tider.
- **En import minskar aldrig `seenPages`,** inte heller när backupen är nyare och nästan tom.
- **Dubbletter:** bokmärken (även utan titel och inom filen) och sessioner.
- **Samma backup två gånger:** andra gången ändras ingenting, både i planen och mot databasen.
- **Import tar aldrig bort något:** tom backup och backup för andra nummer.
- **Validering av `backupFormat`:** fel version, saknat, text i stället för tal, inte JSON, och en inventory-fil av misstag.
- **Ogiltiga värden** och okända fält.
- **Rollback:** ett fel inne i transaktionen efter att alla rader är skrivna lämnar databasen exakt som före.
- **Växling av bokmärke:** skapa, ta bort, ångra (samma id, titel och tider), redigera.
- **D6:** favorit och bokmärken för ett påhittat `issueId` visas som saknade och kopplas ihop igen om numret kommer tillbaka.
- **C7:** storlek, ETag eller datum som skiljer sig ger markering; okända värden gör det inte.
- **Migrering** från version 5 till 6.

**Det instrumenterade testet** `createBookmarkInReaderAndOpenItFromFavorites` sätter ett bokmärke på sida 5, ger det titel och anteckning via snackbaren, bläddrar vidare till sida 7 och stänger. Bokmärket öppnas sedan från Favoriter, och läsaren visar sida 5. Till sist tas det bort och ångras.

## 6. Acceptanskriterier

- [x] **Ett bokmärke öppnar exakt rätt sida.** Sida 7 och sida 10 (satt i uppslaget 10–11) öppnade "Sida 7 av 28" respektive "Sida 10 av 28".
- [x] **Export, avinstallation, ominstallation och import återställer allt.** Alla rader i de tre tabellerna var identiska före avinstallationen och efter importen.
- [x] **Att importera samma backup två gånger skapar inga dubbletter.**

## 7. Verifiering på telefonen

**Favoriter och bokmärken**

- Favoriter: 1986 nr 1 och 1995 nr 14.
- Bokmärken i 1986 nr 1: sida 3 (standardtitel), sida 7 ("Joysticktest" med anteckningen "Kolla pris och betyg") och sida 10, satt i liggande läge på uppslaget 10–11. Ett i 1995 nr 14, sida 4.
- Bokmärkena sparade PDF-versionen: storlek 15 105 472 och arkivets ETag.

**D6:** en favorit och två bokmärken för `datormagazin-1999-99` lades in med ett testverktyg. De visades som "Saknas i aktuellt arkivindex" i Favoriter, följde med genom export och import, och gick att ta bort.

**C7:** den sparade storleken för bokmärket i 1995 nr 14 ändrades med ett testverktyg. Nummervyn visade då "Arkivfilen har uppdaterats – bokmärken kan peka fel".

**Export → avinstallation → installation → import**

| Steg | Resultat |
|---|---|
| Export till Download | "Säkerhetskopian är sparad: 3 nummer med data, 6 bokmärken och 7 lässessioner." Filen var 5,5 kB. |
| Avinstallation och installation | Appen var tom: 0 rader i alla tre tabellerna. |
| Sammanfattning före import | 3 favoritnummer, 6 bokmärken, 7 lässessioner, 3 nummer med läsdata. "Importen lägger till 3 nya nummer och uppdaterar 0, och lägger till 6 bokmärken och 7 sessioner. Ingenting tas bort." |
| Import | Alla rader identiska med före avinstallationen (3, 6 och 7 rader, jämförda fält för fält). |
| Samma fil igen | "Allt i filen finns redan på telefonen. Ingenting ändras." Fortfarande 3, 6 och 7 rader. |
| Öppna bokmärke | Numret hämtades (15 MB) och läsaren öppnade sida 7 med fylld stjärna. |

**Nätverket efter avinstallationen:** appen kom åt nätet direkt efter ominstallationen, så jag behövde inte be dig återställa något. Notisbehörigheten nollställdes av avinstallationen och är beviljad igen med `pm grant`.

Logcat: inga krascher.

## 8. Skärmbilder

I `docs/screenshots/fas6/`:

| Fil | Visar |
|---|---|
| `01-favoriter-favoritnummer-och-bokmarken.png` | Fliken Favoriter med båda avsnitten och ett saknat nummer |
| `01-favoriter-bokmarken.png` | Bokmärken per nummer, med anteckning |
| `02-snackbar-bokmarke-sparat.png` | Snackbaren med "Lägg till titel" |
| `02-snackbar-i-uppslag.png` | Bokmärke satt i uppslagsläge |
| `03-dialog-titel-och-anteckning.png` | Redigeringsdialogen |
| `04-nummervy-med-bokmarken.png` | Nummervyn med hjärta och bokmärken |
| `05-nummervy-arkivfilen-har-uppdaterats.png` | Markeringen enligt C7 |
| `06-bibliotek-favoriter.png` | Biblioteket |
| `07-installningar-sakerhetskopia.png`, `07-export-klar.png` | Backupvyn |
| `08-sammanfattning-fore-import.png`, `08-import-klar.png`, `08-sammanfattning-andra-importen.png` | Importen |
| `09-bokmarke-oppnat-efter-import.png` | Läsaren på bokmärkets sida efter ominstallation |

## 9. Kända begränsningar och avvikelser

- **Borttagningar följer inte med i en backup.** Ett borttaget bokmärke kommer tillbaka om en äldre backup importeras.
- **Ett bokmärke med ändrad titel räknas som nytt** vid import av en backup med den gamla titeln, eftersom titeln ingår i dubblettregeln.
- **Stopp via aktivitetshanteraren testades med motsvarande kommandon, inte med knappen** (`cmd jobscheduler stop` med användarskälet och `am stop-app`). Nedladdningstiderna är för korta, cirka fyra sekunder, för att hinna navigera till knappen medan nedladdningen pågår.
- **C7 och D6 är visade med påhittad data.** Ett riktigt filbyte i arkivet eller ett riktigt försvunnet nummer kan först uppstå med import av inventory i FAS 7.
- **Titlar skrevs utan å, ä och ö vid verifieringen på telefonen,** eftersom `adb` inte kan skriva dem. JVM-testerna använder dem.
- **Stjärnan gäller ankarsidan.** I ett uppslag är det sidan man kom dit med, eller vänstersidan efter ett sidbyte.
- **Bokmärkestestet rör sida 5 i 1986 nr 1.** Finns det redan ett bokmärke där hoppas testet över, så att ditt eget inte rörs.
- **Release-varianten** är inte körd på telefonen i den här fasen.
- **Äldre Android (8–13)** är fortfarande overifierat, som i FAS 5.

## 10. ADR:er

- **0012** (ny): backupformat och regler för sammanslagning.
- **0013** (uppdaterad): en överföring åt gången i hela appen.

## 11. Läget på telefonen

- Din data är återställd enligt avsnitt 2. Inga nedladdningar, ingen läs-cache.
- Appen (debug-bygget) och testpaketet är installerade.
- Notisbehörigheten är beviljad, flygplansläget är av och skärmrotationen står som förut (låst).
- Backupfilen från verifieringen och mina tillfälliga skärmbilder är borttagna från telefonens delade lagring. Där låg elva filer i roten sedan FAS 5 och den här fasen, som jag hade glömt att ta bort.

## 12. Vad som återstår

Ingenting i FAS 6. FAS 7 är inte påbörjad.

## 13. Förslag inför FAS 7

1. **Planens E11:** lägg till `seenPages` och bokmärkenas tre PDF-fält i exemplet, så att planen stämmer med ADR 0012.
2. **Planens C5:** lägg till `pdfSize`, `pdfEtag` och `pdfLastModified` i `Bookmark`.
3. **Import av inventory:** validera mot `docs/inventory.schema.json` (redan beslutat). Schemat finns bara som fil för Python i dag, så appen behöver antingen en egen validering i Kotlin eller ett litet schemabibliotek. Jag föreslår egen validering av de fält appen använder, med samma regler som schemat och tester mot samma exempel.
4. **C7 och D6 får sin riktiga prövning i FAS 7.** Bygg ett testinventory där ett nummer saknas och ett har ny storlek, och kontrollera markeringarna mot riktig data.
5. **`coverAsset` vid import** är bara en ledtråd (redan beslutat): kontrollera att filen finns i assets.
6. **Metadata-sökning** behöver ingen `search.db`. Sök i `Issue` (publikation, år, nummer) och i bokmärkenas titlar och anteckningar, som nu finns.
7. **Borttagningar i backup:** om borttagna bokmärken ska förbli borttagna efter en import behövs gravstenar. Det kräver `backupFormat` 2 och är värt ett eget beslut.
