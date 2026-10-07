# Fasrapport: v0.6.1 och FAS 7 – Import av arkivindex och metadata-sökning

Avslutad 2026-10-08. Verifierad på OnePlus 7 Pro (GM1911), Android 16 (API 36, LineageOS 23.2), debug-bygge. Det här är milstolpen Privat 1.0.

## 1. Sammanfattning

- **v0.6.1:** appens version följer taggarna, och `backupFormat` 2 ger bokmärken en fast identitet och mjuk radering.
- **Import av arkivindex:** en fil väljs i filväljaren, valideras, jämförs med det aktiva indexet och byts in atomiskt. Användardata rörs aldrig.
- **Validering i Kotlin** med felmeddelanden på svenska.
- **Arkivindex i inställningarna:** version, källa, import och återgång till det inbyggda.
- **Ändrad arkivfil:** en nedladdad kopia raderas aldrig automatiskt. "Uppdatera nedladdning" ersätter den först när den nya filen är komplett.
- **Metadata-sökning** i fliken Sök: år, nummer, publikation, bokmärken och två snabbfilter.
- **Testindex** i `tools/testdata/` och avsnittet "Uppdatera arkivet på telefonen" i `tools/README.md`.
- **Version:** 0.7.0 (700). Databasen är version 7.

## 2. Steg 0: utgångsläge och din data

**Migreringen.** Appen startades en gång på den återställda kopian från FAS 6 (databasversion 5). Den migrerade till version 6 utan fel i logcat, och diagnostiken visade 185 nummer.

**Kopian.** `databases/` och `files/datastore/` kopierades till `~/dmz-backup-före-fas7/appdata.tar`.

| | Före fasen | Efter återställningen |
|---|---|---|
| `IssueUserState` | 0 | 0 |
| `Bookmark` | 0 | 0 |
| `ReadingSession` | 0 | 0 |
| Nedladdningar | inga | inga |

Du hade inte använt appen sedan förra fasen. Vid fasens slut togs testdatan bort och kopian lades tillbaka; alla fem filerna är identiska med kopian (SHA-256). Databasen i kopian har version 6, och appen lägger till det som hör till version 7 första gången den startas. Jag har inte startat appen efter återställningen.

**Appen står på det inbyggda indexet:** `filesDir/data/inventory.json` finns inte, och den återställda inställningsfilen anger version 2026-10-06-004.

**Testsviten som utgångsläge**

| Test | Resultat |
|---|---|
| JVM-tester | 258 av 258 |
| Python-tester | 125 av 125 |
| Omstartstest | 2 av 2 delar |
| Instrumenterade tester | ett fel i första körningen, därefter 38 av 38 tre gånger |

Felet återkom senare och gick då att fånga: `readDownloadTurnPageAndClose` väntade förgäves på en nedladdning. Testet före det, `OfflineDeviceTest`, slår på och av flygplansläget, och nätet hann tappas igen efter att det först hade kommit tillbaka. Offlinetestet väntar nu tills nätet har varit bekräftat i fem sekunder i följd. Omstartstestets första del hoppades dessutom över tyst när PDF:en saknades, så att den andra delen misslyckades utan förklaring; den hämtar nu numret själv. Efter det har sviten gått igenom i varje körning.

## 3. Steg 1: v0.6.1

### Version
`versionName` är 0.6.1 och `versionCode` 601, satta på ett ställe i `app/build.gradle.kts`. Backupens `appVersion` läses från `BuildConfig.VERSION_NAME`.

- JVM-testet `AppVersionTest` kontrollerar att `versionCode` är uträknad ur `versionName`.
- Det instrumenterade testet `BackupVersionDeviceTest` exporterar en backup på telefonen och jämför `appVersion` med både `BuildConfig` och det systemet uppger för det installerade paketet.
- Regeln står i planens G4.

### backupFormat 2
- `Bookmark` har `bookmarkId` (UUID, skapas en gång) och `deletedAt`. Databasversion 7; migreringen ger varje befintligt bokmärke ett eget UUID.
- Att ta bort ett bokmärke sätter `deletedAt`. Borttagna bokmärken visas aldrig. "Ångra" nollställer `deletedAt`.
- Import matchar på `bookmarkId`: senaste `updatedAt` vinner för titel och anteckning, och ett bokmärke som är borttaget på någon sida förblir borttaget.
- Format 1 går fortfarande att importera. De bokmärkena får nya UUID, och dubblettregeln (issueId, pageNumber, title) används bara för dem.
- Exporten skrivs alltid i format 2.

Testerna visar att en titeländring följd av import av en gammal backup inte ger en dubblett, att ett borttaget bokmärke inte återuppstår (varken ur format 2 eller format 1), att en borttagning följer med till en annan telefon, och att format 1 importeras och ger noll ändringar andra gången.

### Planen och rapporten
C5, E11 och G4 är uppdaterade och loggade i bilaga E. FAS 6-rapporten säger nu att stopp via aktivitetshanteraren testades med motsvarande kommandon, inte med knappen, på grund av korta nedladdningstider.

## 4. Skapade och ändrade filer

**Nya**

- `data/inventory/InventoryManager.kt`
- `domain/MetadataSearch.kt`
- `ui/settings/ArchiveIndexSection.kt`
- `tools/make_test_inventories.py`, `tools/testdata/` (fem filer), `tools/tests/test_testdata.py`
- `app/schemas/…/7.json`
- Tester: `InventoryValidationTest`, `InventoryManagerTest`, `TestInventoriesTest`, `OutdatedDownloadTest`, `MetadataSearchTest`, `AppVersionTest`, `ArchiveIndexDeviceTest`, `BackupVersionDeviceTest`
- `docs/decisions/0019-import-av-arkivindex.md`, `docs/screenshots/fas7/`

**Ändrade**

- `data/inventory/InventoryParser.kt`, `data/settings/ActiveInventoryStore.kt`
- `data/backup/` (format 2), `data/db/` (kolumner i `Bookmark`, migrering 6→7, DAO:er), `data/repository/BookmarkRepository.kt`
- `data/offline/OfflineRepository.kt`, `data/pdf/PdfDownloader.kt`, `PdfRepository.kt`
- `domain/CoverSource.kt`, `ReadingStatus.kt`, `PersonalLibrary.kt`
- `ui/search/`, `ui/settings/`, `ui/issue/`, `ui/favorites/`, `ui/components/CoverImage.kt`, `ui/DmzReaderRoot.kt`, `AppContainer.kt`, `MainActivity.kt`
- `app/build.gradle.kts`, `tools/README.md`
- Befintliga tester för backup, bokmärken, migrering, navigering och import
- `docs/decisions/0012-…`, `docs/reports/fas-6.md`, `DMZ_READER_PROJEKTPLAN_V3.md` (C5, E11, G4 och bilaga E, enligt beslut)

## 5. Testresultat

| Test | Hur | Resultat |
|---|---|---|
| JVM-tester | `./gradlew :app:testDebugUnitTest` | 320 av 320 |
| Instrumenterade tester på telefonen | `adb shell am instrument -w se.c64.dmzreader.test/androidx.test.runner.AndroidJUnitRunner` | 26 av 26 (plus 14 som bara körs på begäran) |
| Omstartstest med tvångsstopp | `tools/device_restart_test.sh` | 2 av 2 delar |
| Python-tester | `.venv/bin/python -m unittest discover -s tools/tests` | 129 av 129 |
| Lint | `./gradlew :app:lintDebug` | 0 fel, 6 varningar |

**JVM-testerna för FAS 7 täcker:**

- **Validering:** fel `schemaVersion`, fel form på `datasetVersion`, saknade fält (namngivna), fel typer, dubblerat `issueId`, `relatedIssueId` som pekar fel eller på sig självt, odeklarerad publikation, fel adresser och omslagsdata, avbruten JSON, tom fil, och en backupfil av misstag. Fält som får vara null får också saknas.
- **Testindexen:** alla fem filerna i `tools/testdata/` prövas mot appens validering, med exakta felmeddelanden.
- **Sammanfattningen före import:** nya, borttagna och ändrade nummer.
- **Atomiskt byte och rollback:** import, fel i databasen (med och utan tidigare importerat index), avbrott mellan filbyte och databas, skadad importerad fil vid start, och återgång.
- **Användardata:** oförändrad genom import och återgång, och ihopkopplad med numret igen.
- **coverAsset-reserven,** **föråldrad nedladdning,** **uppdatering som misslyckas, avbryts eller tappar nätet** (gammal kopia kvar i alla tre fallen) och **föråldrad cachefil**.
- **Sökningens tolkning:** "1989", "1989 12", "1989/12", "89/12", "89 12", "nr 12", "89", publikation och bokmärken.

**Valideringen hittade ett eget fel under arbetet:** tolkningen godtog ett år skrivet inom citattecken (`"year": "1989"`). Typerna kontrolleras nu uttryckligen.

**Det instrumenterade testet** `importThenRevert` importerar ett ändrat index genom appens egen hanterare, databas och lagring, kontrollerar katalogen och att användardatan är orörd, avvisar en trasig fil, och återgår till det inbyggda.

## 6. Acceptanskriterier

- [x] **Ett nytt index med ett tillagt och ett borttaget nummer importeras korrekt.**
- [x] **Användardata för det borttagna numret finns kvar och visas som saknad.**
- [x] **När numret läggs tillbaka kopplas datan ihop igen.**
- [x] **En fil med fel schema avvisas, och den tidigare filen gäller fortfarande.**
- [x] **Metadata-sökning hittar ett nummer på år eller nummer.**

## 7. Verifiering på telefonen

Testindexen kopierades till telefonens Download med `adb push` och valdes i filväljaren.

**Före importen:** 1986 nr 4 var favorit, hade tre lästa sidor och bokmärket "Spelbilaga" på sida 3. 1995 nr 14 var nedladdat.

**Import av A+B+C**

Sammanfattningen visade: "Aktivt index: 2026-10-06-004 · Nytt index: 2026-10-08-901, 185 nummer · 1 nya nummer: datormagazin-1996-01 · 1 borttagna nummer: datormagazin-1986-04 · 1 ändrade nummer: datormagazin-1995-14".

| Kontroll | Resultat |
|---|---|
| Det nya numret | Årgången 1996 fanns i biblioteket. Omslaget hämtades från webben (`/magazines/199515/DMZ_1995-15-001.jpg` i logcat), utan försök med den miniatyr som inte finns. |
| 1986 nr 4 | Borta ur biblioteket: årgången visade "3 nummer". I Favoriter stod `datormagazin-1986-04` med "Saknas i aktuellt arkivindex" och "Favorit · läsdata sparad: sida 3, 3 lästa sidor", och bokmärket "Sida 3 · Spelbilaga" under samma markering. |
| 1995 nr 14 | "Arkivfilen har uppdaterats. Din nedladdade kopia är äldre." Filen (115 605 773 byte) låg kvar, och läsaren öppnade den. |
| Uppdatera nedladdning | Den gamla filen låg kvar medan `.part` växte (39, 55, 73 MB) och ersattes när den nya var komplett. |
| Inställningarna | Version 2026-10-08-901, "Importerat 8 oktober 2026 00:16". |

**De trasiga varianterna.** Alla fyra avvisades, versionen stod kvar på 2026-10-08-901 med 185 nummer, och inga temporära filer låg kvar:

- "Arkivindexet har schemaVersion 2. Den här appversionen stöder bara schemaVersion 1."
- "Arkivindexet går inte att läsa: issueId är inte unikt: datormagazin-1986-01"
- "Arkivindexet går inte att läsa: datormagazin-1996-01: relatedIssueId pekar på datormagazin-1999-99, som inte finns"
- "Arkivindexet går inte att läsa: filen är avbruten eller skadad (JSON-texten tar slut mitt i)"

Varje meddelande följdes av "Det aktiva arkivindexet är oförändrat."

**Återgå till inbyggt index.** Version 2026-10-06-004, "Inbyggt i appen". 1986 nr 4 var tillbaka i biblioteket och i Favoriter med omslag, nummervyn visade "Fortsätt på sida 3" och bokmärket, och 1995 nr 14 var inte längre markerat som äldre.

**Sökning**

| Sökning | Resultat |
|---|---|
| "1989" | 19 nummer, alla från 1989 |
| "89/12" | Datormagazin 1989 Nr 12 |
| "spelbilaga" | Bokmärket "Sida 3 · Spelbilaga" i 1986 nr 4. Ett tryck öppnade läsaren på sida 3. |
| Snabbfiltret Nedladdade | Datormagazin 1995 Nr 14 |
| Snabbfiltret Favoriter | Datormagazin 1986 Nr 4 |

Logcat: inga krascher.

## 8. Skärmbilder

I `docs/screenshots/fas7/`:

| Fil | Visar |
|---|---|
| `01-installningar-arkivindex-inbyggt.png` | Arkivindex i inställningarna, inbyggt |
| `02-sammanfattning-fore-import.png` | Sammanfattningen före import |
| `03-installningar-arkivindex-importerat.png` | Version och källa efter import |
| `04-nytt-nummer-omslag-via-natet.png` | Det nya numret med omslag från webben |
| `05-argang-1986-utan-nr-4.png` | Årgången utan det borttagna numret |
| `06-favoriter-saknas-i-arkivindex.png` | Favorit, läsdata och bokmärke som saknade |
| `07-nummervy-nedladdad-kopia-aldre.png` | Markeringen och knappen Uppdatera nedladdning |
| `08-uppdatering-pagar.png` | Uppdateringen pågår |
| `09-trasigt-index-avvisas.png`, `09-avbruten-fil-avvisas.png` | Felmeddelanden |
| `10-efter-atergang-numret-tillbaka.png` | Efter återgång |
| `11-sok-tomt-lage.png` | Sökfliken med förklaring |
| `12-sok-1989.png`, `12-sok-89-12.png`, `12-sok-bokmarkestitel.png` | Sökningar |
| `13-sok-snabbfilter-favoriter.png` | Snabbfilter |

## 9. Kända begränsningar och avvikelser

- **Markeringen "Din nedladdade kopia är äldre" stod kvar efter uppdateringen i verifieringen.** Testindexet uppger en påhittad storlek för 1995 nr 14, så den hämtade filen skiljer sig fortfarande från indexet. Med ett riktigt index försvinner markeringen; det visas av JVM-testet. Om ett index någon gång säger fel om en fil står markeringen kvar.
- **Reglerna för ett giltigt index finns på två ställen,** i Python och i Kotlin. De gemensamma testindexen fångar skillnader i de fall som finns där.
- **Läsdata för ett saknat nummer går inte att ta bort i appen.** Favoritmarkeringen och bokmärkena går att ta bort; läsdatan visas och ligger kvar.
- **Borttagna bokmärken ligger kvar som dolda rader** och rensas aldrig.
- **En backup i format 1 har sina gamla svagheter kvar:** en titel som ändrats sedan backupen gjordes ger en dubblett när den importeras.
- **Sökningen känner inte till tidningarnas innehåll.** Ett ord ger bara träff om det står i ett bokmärke eller är en publikation.
- **Ett ensamt tal mellan 1 och 31 tolkas som ett nummer,** och ett större tvåsiffrigt tal som ett år.
- **Titlar skrevs utan å, ä och ö vid verifieringen,** eftersom `adb` inte kan skriva dem.
- **Release-varianten** bygger (24,2 MB) men är inte körd på telefonen.
- **Äldre Android (8–13)** är fortfarande overifierat.

## 10. ADR:er

- **0019** (ny): import av arkivindex, ändrad arkivfil och metadata-sökning.
- **0012** (uppdaterad): `backupFormat` 2.

## 11. Läget på telefonen

- Din data är återställd enligt avsnitt 2, och appen står på det inbyggda indexet.
- Appen (debug-bygget, version 0.7.0) och testpaketet är installerade.
- Testindexen är borttagna från telefonens Download. Inga nedladdningar, ingen läs-cache.
- Notisbehörigheten är beviljad, flygplansläget är av och skärmrotationen är som förut.
- I roten av telefonens delade lagring ligger ett femtiotal skärmbilder och XML-filer från augusti (`f6_*.png`, `screen*.png`, `ui*.xml` med flera). De är inte mina, och jag har inte rört dem.

## 12. Vad som återstår

Ingenting i FAS 7. FAS 8 är inte påbörjad.

## 13. Förslag inför FAS 8

1. **Sökindexet byggs från exakt de PDF:er appen läser** (redan beslutat). Det betyder cirka 12 GB nedladdning till datorn, en fil i taget. Bestäm om alla 185 nummer ska hämtas direkt, eller en årgång först för att pröva kedjan.
2. **Tokenizer (ADR 0010):** pröva `unicode61` med och utan `remove_diacritics` på riktig text från båda PDF-generationerna innan hela indexet byggs. FAS 1 visade att FTS5 fungerar på telefonen; frågan som återstår är "får" mot "far".
3. **Normaliseringen** av utspärrade ord och mjuka bindestreck (beslutad i FAS 0B) bör prövas mot 1995 nr 14 först, som planen säger.
4. **Storleken på `search.db`** avgör hur den kommer till telefonen. Mät den för en årgång och räkna upp, innan importen i FAS 9 utformas.
5. **Sidantalet:** `pageCount` saknas i indexet för de flesta nummer. När alla PDF:er ändå läses i FAS 8 kan det mätas och föras in, så att progress och status blir rätt även för nummer som aldrig har öppnats.
6. **Planen:** lägg till `source` och `importedAt` för det aktiva indexet under D4, och "Uppdatera nedladdning" under C7, så att planen stämmer med appen.
7. **Rensning av dolda bokmärken** kan göras vid export: tombstones äldre än ett år behövs sällan.
