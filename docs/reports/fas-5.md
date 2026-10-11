# Fasrapport: FAS 5 – Offline

> **Skärmbilderna som nämns här finns inte i repot.** De ligger lokalt hos projektets ägare, se [docs/skarmbilder.md](../skarmbilder.md).

Avslutad 2026-10-07. Verifierad på OnePlus 7 Pro (GM1911), Android 16 (API 36, LineageOS 23.2), debug-bygge. Det här är MVP-milstolpen.

## 1. Sammanfattning

- **Ladda ned:** ett nummer sparas permanent i `filesDir/issues/` och kan läsas helt utan nät.
- **Bakgrund:** på Android 14 och senare körs nedladdningen som ett UIDT-jobb med notis (förlopp i MB och procent, knappen Avbryt). Den fortsätter när appen lämnas och när skärmen släcks.
- **Kö:** nummer hämtas ett i taget, i den ordning de begärdes.
- **Från läs-cachen:** ett nummer som redan är hämtat med Läs flyttas, utan nätverkstrafik.
- **Wifi:** inställningen "Ladda ned endast via wifi" är på som standard.
- **Gränssnitt:** nedladdningsdel i nummervyn, märke på omslagen, filtret Offline, sektionen Nedladdat och avsnittet Lagring i inställningarna.
- **Databasen:** version 5, med automatisk migrering.

## 2. Utgångsläge (steg 0)

Hela testsviten kördes på `main` innan någon ny kod skrevs:

| Test | Resultat |
|---|---|
| JVM-tester | 187 av 187 |
| Python-tester | 125 av 125 |
| Instrumenterade tester | 21 av 22, sedan 22 av 22 |
| Omstartstest med tvångsstopp | 2 av 2 delar |

Det enda felet var `libraryToYearToIssueAndBack`. Testet förutsatte att 1989 nr 6 var oläst, men numret hade läsdata kvar från verifieringen av FAS 4, så knappen hette "Fortsätt på sida 3". Testet godtar nu båda knapptexterna. Felet låg i testet, inte i appen.

## 3. Skapade och ändrade filer

**Nya**

- `data/offline/OfflineRepository.kt`, `LocalStateStore.kt`, `FileMover.kt`
- `offline/DownloadScheduler.kt`, `offline/DownloadJobService.kt` (tjänst, notis och mottagare för Avbryt)
- `app/schemas/…/5.json`
- Tester: `OfflineRepositoryTest` (JVM), `OfflineDeviceTest`, `DownloadNotificationDeviceTest`, `PhoneCleanupDeviceTest`
- `docs/decisions/0013-lagring-och-nedladdning-for-offline.md`, `docs/screenshots/fas5/`

**Ändrade**

- `data/pdf/PdfCache.kt` (gemensam `PdfFiles`), `PdfDownloader.kt`, `PdfRepository.kt`
- `data/db/` (fältet `downloadQueuedAt`, DAO, version 5), `data/settings/` (`DownloadPreferences`), `data/repository/LibraryRepository.kt`
- `domain/ReadingStatus.kt` (filtret Offline, Nedladdat, text för lästa sidor), `domain/IssueText.kt`
- `ui/issue/`, `ui/year/`, `ui/library/`, `ui/settings/`, `ui/reader/ReaderViewModel.kt`, `ui/components/Components.kt`, `ui/DmzReaderRoot.kt`
- `AppContainer.kt`, `DmzReaderApp.kt`, `MainActivity.kt`, `AndroidManifest.xml`
- `MigrationTest`, `DatabaseSchemaTest`, `ReadingStatusTest`, `ReaderLogicTest`, `LibraryNavigationTest`, `ReaderDeviceTest`, `ReadingStateDeviceTest`, `DeviceTestSupport.kt`
- `docs/decisions/0017-…`, `DMZ_READER_PROJEKTPLAN_V3.md` (E5, A5, E8, FAS 5, FAS 10 och bilaga E, enligt beslut)

## 4. Testresultat

| Test | Hur | Resultat |
|---|---|---|
| JVM-tester | `./gradlew :app:testDebugUnitTest` | 207 av 207 |
| Instrumenterade tester på telefonen | `adb shell am instrument -w se.c64.dmzreader.test/androidx.test.runner.AndroidJUnitRunner` | 23 av 23 (plus 12 som bara körs på begäran) |
| Omstartstest med tvångsstopp | `tools/device_restart_test.sh` | 2 av 2 delar |
| Python-tester | `.venv/bin/python -m unittest discover -s tools/tests` | 125 av 125 |
| Lint | `./gradlew :app:lintDebug` | 0 fel, 6 varningar |

Den instrumenterade sviten kördes sist innan telefonen låstes för skärmtestet. Efter det har bara rensningsverktyget och dokument tillkommit.

**JVM-testerna** (`OfflineRepositoryTest`) kör mot en lokal testserver och den riktiga databasen:

- **Kö:** tre nummer hämtas i den ordning de begärdes, aldrig mer än en anslutning åt gången. En andra körning gör ingenting medan kön betas av.
- **Från cachen:** filen flyttas och servern får noll anrop. Misslyckas `renameTo` kopieras filen, storleken kontrolleras och cachekopian tas bort. Misslyckas allt ligger cachefilen kvar orörd och numret ställs i kön. En cachefil med fel storlek litas inte på.
- **Status mot filsystemet:** nedladdad utan fil, nedladdad med fel storlek, avbruten mitt i, kvarlämnad `.part` och `.part` som hör till kön.
- **Borttagning:** en och alla. Läsarens uppgifter om numret (sidantal, uppslag) finns kvar.
- **Avbrott:** köat nummer, pågående nummer (kön fortsätter med nästa), och samma nummer kan laddas ned igen efteråt.
- **Nätbortfall:** `.part` ligger kvar, numret står kvar i kön och fortsätter med Range och If-Range.
- **Serverfel** ger "misslyckades" och kön går vidare. **Stoppat jobb** ställer numret tillbaka i kön.
- **Migrering** från version 4 till 5 med befintlig data kvar.

**Det instrumenterade testet** `downloadThenReadInAirplaneModeThenRemove` hämtar 1986 nr 1 från arkivet, slår på flygplansläge, öppnar läsaren ur den nedladdade filen, slår av flygplansläget och tar bort nedladdningen.

## 5. Acceptanskriterier

- [x] **Ett nedladdat nummer kan läsas i flygplansläge.** 1995 nr 14 läst sida 1–3 i flygplansläge, och 1986 nr 1 i det instrumenterade testet.
- [x] **En nedladdning överlever att appen stängs (API 34+).** Den fortsatte efter Hem, och efter tvångsstopp fortsatte den från `.part` när appen startades igen.
- [x] **En avbruten eller misslyckad nedladdning kan startas om och lämnar inga trasiga filer.** Avbruten via appen och via notisen: `.part` togs bort och status nollställdes. Nätbortfall: `.part` låg kvar och nedladdningen återupptogs.
- [x] **Borttagning frigör utrymmet.** Filen och statusen försvann, och lagringsöversikten uppdaterades.

## 6. Verifiering på telefonen

1995 nr 14 är 115,6 MB och hämtas på 3–5 sekunder över telefonens wifi. Alla steg som ska ske "mitt i" en nedladdning hade därför en eller ett par sekunders marginal och är körda med skriptade tryck.

| Steg | Resultat |
|---|---|
| Starta nedladdning → Hem | Fortsatte. Notisen visade "Laddar ned Datormagazin 1995 Nr 14" och till exempel "79,6 av 115,6 MB · 68 %". Filen blev exakt 115 605 773 byte. |
| Släck skärmen | Skärmen var av (`mScreenState=OFF`, `Dozing`) när nedladdningen stod på 87 %, och den blev klar med skärmen av. Se begränsningar: testet varade bara ett par sekunder. |
| Avbryt via appen | `.part` (49 MB) togs bort, status `NONE`, knappen "Ladda ned" tillbaka. |
| Avbryt via notisen | Samma resultat. Loggen visar "Avbryt från notisen". |
| Slå av wifi mitt i | Systemet stoppade jobbet. `.part` (41 MB) låg kvar, status `QUEUED`, nummervyn visade "Köad". |
| Slå på wifi | Nedladdningen fortsatte från 41 MB (`återupptagen=true`) och blev komplett, men först efter cirka tre minuter. Se nedan. |
| Nummer i läs-cachen → Ladda ned | "flyttad från läs-cachen, 25672804 byte, ingen nätverkstrafik". Inget jobb schemalades. |
| Flygplansläge | 1995 nr 14 gick att läsa. Offline-filtret i 1995 visade bara Nr 14. Ett nummer som inte var nedladdat gav "Det gick inte att nå arkivet". |
| Ta bort nedladdning | Bekräftelse, sedan var filen borta och status `NONE`. |
| Tvångsstopp under nedladdning | `.part` (47 MB) låg kvar med status `DOWNLOADING`. Vid omstart ställdes numret i kön och fortsatte från 47 MB till komplett fil. |

Logcat: inga krascher och inget `OutOfMemoryError`.

### Fel som hittades och rättades

- **En avbruten nedladdning förstörde nästa.** Efter ett avbrott låg en markering kvar, så nästa nedladdning av samma nummer togs bort i samma stund som den blev klar. Rättat, med ett JVM-test som misslyckas utan rättningen.
- **Tre minuters väntan efter nätbortfall.** Jobbet startade två gånger medan wifi höll på att ansluta och stoppades direkt, och systemet väntade sedan allt längre mellan försöken. Appen startar nu om jobbet direkt när den öppnas. Ligger appen stängd gäller fortfarande systemets väntetid.
- **Flyttade filer räknades som cache.** Filer i `cacheDir` tillhör en särskild grupp. En flyttad fil får nu appens vanliga grupp.

## 7. Skärmbilder

I `docs/screenshots/fas5/`:

| Fil | Visar |
|---|---|
| `01-nummervy-ladda-ned.png` | Ladda ned |
| `01-nummervy-koad.png` | Köad (wifi avstängt) |
| `01-nummervy-laddar-ned.png` | Laddar ned X %, med Avbryt |
| `01-nummervy-nedladdad.png` | Nedladdad · 115,6 MB, Ta bort nedladdning |
| `01-nummervy-bekrafta-borttagning.png` | Bekräftelsen |
| `02-fraga-om-notiser.png` | Förklaringen före frågan om notisbehörighet |
| `03-argang-offline-filter.png` | Filtret Offline |
| `03-argang-offline-ikon.png` | Märket på ett nedladdat omslag |
| `04-lasning-i-flygplanslage.png` | Läsaren i flygplansläge |
| `05-notis-med-forlopp-och-avbryt.png` | Notisen, expanderad |
| `06-installningar-lagring.png` | Lagring och wifi-inställningen |
| `07-bibliotek-nedladdat.png` | Sektionen Nedladdat |
| `07-bibliotek-fortsatt-lasa.png` | Fortsätt läsa-kortet med "X av Y lästa · Z %" |

Notisbilden är beskuren till appens egen notis och är tagen med ett testverktyg som visar notisen stillastående; en riktig nedladdning är över på några sekunder. Läget "Nedladdningen misslyckades" saknar skärmbild, eftersom det kräver ett serverfel.

## 8. Rensning av telefonen

Efter verifieringen är telefonen tömd, kontrollerat mot databasen och filsystemet:

| | Före | Efter |
|---|---|---|
| `IssueUserState` | 3 | 0 |
| `ReadingSession` | 18 | 0 |
| `Bookmark` | 0 | 0 |
| `IssueLocalState` | 4 | 0 |
| `Issue` (katalogen) | 185 | 185 |
| Nedladdningar (`files/issues`) | 135 MB | tom |
| Läs-cache (`cache/pdf`) | 14 MB | tom |
| Miniatyrer (`cache/thumbs`) | 0,3 MB | borta |

Appen är tom och redo att användas på riktigt. Den ligger kvar installerad.

Det här ligger kvar:

- **Inställningar:** notisbehörigheten är beviljad (jag svarade "Allow" i systemets dialog under verifieringen), frågan om notiser ställs inte igen, och "Ladda ned endast via wifi" är på.
- **Omslagscachen** (1,6 MB), som inte är användardata.
- **Testpaketet** `se.c64.dmzreader.test`. Det behövs för verktygen ovan och kan avinstalleras med `adb uninstall se.c64.dmzreader.test`.
- **Telefonen är låst.** Skärmtestet låste den med din PIN, så rensningen är kontrollerad via adb och inte med en skärmbild av den tomma appen.
- Flygplansläget är av och wifi är på.

## 9. Kända begränsningar och avvikelser

- **Vägen för Android 8–13 är inte körd på en riktig enhet.** Kön och motorn är desamma och testas på JVM, men beteendet "bara medan appen är öppen" och texten om det är overifierade.
- **Skärmtestet var kort.** Skärmen var av under de sista sekunderna av en nedladdning på fyra sekunder. Att ett UIDT-jobb överlever en längre stund med släckt skärm (Doze) är inte prövat. Det går bara att pröva med en större fil eller ett långsammare nät.
- **Efter nätbortfall kan det dröja minuter** innan systemet kör jobbet igen, om appen inte öppnas.
- **Nytt fält utanför planens C3:** `IssueLocalState.downloadQueuedAt`, för köordningen.
- **"Läs" och en bakgrundsnedladdning kan pågå samtidigt.** Då har servern två anslutningar från appen. "En åt gången" gäller kön.
- **"Läs" på ett nummer som just laddas ned** hämtar det en gång till, till läs-cachen.
- **Utan notisbehörighet** körs nedladdningen ändå, men utan notis.
- **Avbryt-knappen kan hamna utanför skärmen** i en full notispanel. Den finns där när man rullar.
- **Release-varianten** bygger (23,9 MB) men är inte körd på telefonen i den här fasen.
- **De instrumenterade testerna tar bort nedladdningar och läsdata** för 1986 nr 1, 1995 nr 14 och 1995 nr 15. `OfflineDeviceTest` slår på flygplansläget en kort stund.

## 10. ADR:er

- **0013** (ny): lagringsplats och nedladdning för offline-läsning.
- **0017** (uppdaterad): nedladdningsmotorn delas med de permanenta nedladdningarna.

## 11. Vad som återstår

Ingenting i FAS 5. FAS 6 är inte påbörjad.

## 12. Förslag inför FAS 6

1. **Backupformatet (ADR 0012):** `seenPages` ingår som Base64-text enligt ADR 0018. Lägg till `seenPages` i exemplet i planens E11, och bestäm om `backupFormat` ska börja på 1 med det fältet.
2. **Planens C3:** lägg till `downloadQueuedAt`, så att planen stämmer med appen.
3. **Favoriter:** `isFavorite` finns redan och rörs aldrig av läsaren eller nedladdningarna. Sektionen Favoriter i biblioteket och favoritmarkeringen på omslagen saknar bara gränssnitt.
4. **Bokmärken:** knappen i läsarens topprad är en avstängd platshållare. Bestäm om ett bokmärke ska skapas med ett tryck och få titel i efterhand, eller alltid fråga efter titel.
5. **Import av backup och testdata:** bygg importens sammanslagning mot JVM-tester först, eftersom reglerna i E11 (senaste `updatedAt` vinner, `seenPages` med OR) är lätta att få fel.
6. **Äldre Android:** om appen ska användas på en telefon med Android 8–13 bör vägen utan bakgrundsnedladdning prövas på en sådan enhet innan dess.
7. **Skärmtestet:** ladda gärna ned ett stort nummer själv med släckt skärm någon gång, och säg till om det stannar.
