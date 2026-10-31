# Codex: granskning, rättningar och överlämning till Claude Code

Datum: 2026-10-30 (Europe/Stockholm).

## 1. Sammanfattning och läs detta först

Användaren bad först om en granskning utan kodändringar, och godkände därefter att de hittade felen rättas och dokumenteras för Claude Code. Rättningarna ligger på **`fix/pre-1.0-review`**, utgående från `30c428c` på `main` (`Merge release/1.0-rc4`). Arbetsträdet var rent vid start.

Alla sex felområden från granskningen har rättats i koden. Två oanvända funktioner har tagits bort och det tidigare oanvända tillståndet Verifying har kopplats in. Under arbetet rättades också blockerande HTTP-avbrott, som behövdes för att förgrunds- och nätverkspauser ska slå igenom snabbt.

**Detta är en lokal rättningsgren efter den frysta RC4, inte en publicerad release.** Inget har installerats på telefon, pushats till GitHub, mergats till `main`, taggats eller publicerats. `release/1.0.0` är orörd och innehåller inte rättningarna. Appens versionsnummer är fortfarande `1.0.0-rc4`/9904: byggfilerna på denna gren är arbetsbyggen och ska inte förväxlas med den tidigare taggade RC4.

Läs även [ADR 0025](../decisions/0025-avbrott-natverksvillkor-och-transaktioner.md). Projektplanen, användardatans format, databasschema 8 och befintliga releasechecklistors godkännandepunkter har inte ändrats.

Lokala kodcommits på grenen:

| Commit | Innehåll |
|---|---|
| `ff4b7d6` | Skydda backup och läsdata med gemensamma Room-transaktioner |
| `501bb52` | Rätta nätverksvillkor, förgrundskö och avbrott vid indeximport |
| `19a5527` | Bind sökresultat till rätt index och ta bort oanvänd kod |

Överlämningsrapporten, ADR 0025 och länken i `CLAUDE.md` sparas i en separat dokumentationscommit efter dessa.

## 2. Rättningar

### F1. Systemstopp under import av hämtat sökindex

Tidigare rensade `importDownloaded` beställningen och den hämtade filen även vid `CancellationException`. UI kunde stå kvar på Importing. Nu behålls filen och beställningen vid systemavbrott och UI återgår till Interrupted. Nästa körning återanvänder den kompletta filen efter ny kontroll av storlek och SHA-256. Ett tidigare importerat index påverkas inte av ett avbrott under kopieringen.

`SearchIndexDownloader.download` har fått en callback för Verifying. Kontrolltillståndet finns därmed faktiskt i flödet. Den verifierade filen återanvänds också efter omstart; inget nät behövs för att hämta samma fil igen. Den befintliga nätverkspolicyn kontrolleras fortfarande före körningen.

Regressionstestet stoppar körningen medan importen har börjat, kontrollerar det tidigare indexet och de bevarade filerna, återskapar beställningshanteraren och genomför importen igen utan fler filförfrågningar.

### F2. Wifi-regeln för PDF-kön på Android 8–13

Den äldre kön körde direkt, utan nätverkskontroll. Nu följer den `DownloadPreferences.observeWifiOnly()` tillsammans med `ConnectivityManager`-callbacks. Både PDF-kö och sökindex väntar på ett tillåtet nät. Byte till ett otillåtet nät avbryter körningen; byte tillbaka återupptar den.

Wifi betyder, precis som i ADR 0013, ett nät som inte är markerat som debiterat. Frånkoppling behandlas aldrig som ett tillåtet nät. Vid byte av standardnät används inte det föregående nätets kostnadsuppgifter.

### F3. Förgrundskörning på Android 8–13

Den äldre kön startades i applikationens scope och kunde fortsätta efter att aktiviteten försvunnit. `MainActivity` meddelar nu `setForeground` vid start/stopp. Rotationen undantas. `InAppDownloadScheduler` avbryter körningen när appen inte längre syns och väntar på avslutad städning innan den återupptas. Köposter och `.part` bevaras genom den befintliga köhanteringen.

Android 14+ använder fortfarande UIDT; dess `setForeground` är avsiktligt en no-op. Ingen WorkManager-reserv eller annan ny bakgrundslösning har införts.

### F4. Sökning efter byte av sökindex

Sökningens nyckel är nu frågan och det öppnade indexet, inte bara frågan och om ett index finns. Ett indexbyte startar en ny sökning även om texten är oförändrad och StateFlow hoppar över det korta null-läget under filbytet.

Varje rangordning kopplas till sitt index. Gamla rad-ID:n grupperas inte mot ett nytt index. Utdrag kontrolleras både innan frågan körs och innan svaret läggs in i vyn. Motsvarande skydd har lagts i Sök i numret. Det onödiga återanvändningsvillkoret för samma fråga är borta; det kunde även lämna searching=true om en annan sökning avbröts på väg tillbaka till den gamla frågan.

Ett nytt vymodelltest genomför ett verkligt indexbyte på samma tråd som sökvymodellen, med oförändrad söktext. Det nya indexet saknar de gamla FTS-träffarna, och testet kräver att både resultat och utdrag uppdateras.

### F5. Backup och samtidiga skrivningar

Backupimportens snapshot, sammanslagning, skrivning och slutkontroll sker nu i en gemensam Room 3-transaktion. Även exportens snapshot av tre tabeller är transaktionell.

Läsrepositoryts läs–ändra–skriv har flyttats till en transaktionell DAO-metod, så att en lässkrivning inte kan läsa före importen och sedan skriva tillbaka en gammal rad efter importen. Det befintliga lokala låset finns kvar för bland annat de enkla testdubblarna. Bokmärkesredigering har fått samma transaktionsskydd, så att titeländringar inte skriver tillbaka en gammal kopia av andra bokmärkesfält.

Sammanslagningsreglerna är oförändrade: bland annat OR för sedda sidor, senaste updatedAt för användarval och bevarad mjuk radering. Ingen migrering behövs.

Tre nya tester med riktig Room 3 verifierar import medan läsning väntar, läsning medan import väntar och två samtidiga importer. Slutresultatet måste bevara unionen av lästa sidor och rätt senaste användarval. Befintligt test för återställning efter skrivfel passerar också.

### F6. Misslyckat namnbyte av en uppdaterad PDF

Den gamla målfilen raderas inte längre när `renameTo` misslyckas. Nedladdaren ger ett fel och lämnar den tidigare PDF:en samt den kompletta `.part`-filen kvar. Ett lyckat namnbyte ersätter fortfarande målfilen i ett steg.

Regressionstestet injicerar ett misslyckat namnbyte och kontrollerar byte för byte att både den gamla kopian och den färdiga ersättningen bevaras.

### F7. Ytterligare fynd: avbrott under blockerande HTTP-väntan

Den gamla `invokeOnCompletion` kördes först när korutinens blockerande kropp var färdig. Ett avbrott kunde därför invänta lästimeout eller nästa byte från servern. En liten underordnad korutin anropar nu `Call.cancel()` vid förälderns avbrott, även medan HTTP-koden väntar på headers eller data. Bevakningen avslutas i finally. Lösningen används av PDF- och sökindexklienterna.

Två nya MockWebServer-tester låter servern förbli tyst och kräver att avbrottet slutförs inom två sekunder, före klientens femsekunders testtimeout.

### Död kod

- `PlaceholderScreen` har tagits bort tillsammans med överflödiga importer. Alla fyra flikar är redan implementerade.
- `PageBitmapCache.liveBytes` saknade anrop, även i tester, och har tagits bort.
- `IndexDownloadState.Verifying` behålls och aktiveras nu under kontrollen av den hämtade filen.
- FTS-självtest och testdatabashantering är kvar: de används i diagnostik och tester.

## 3. Skapade och ändrade filer

Sökvägarna i appkolumnerna nedan är relativa till `app/src/main/java/se/c64/dmzreader/` respektive `app/src/test/java/se/c64/dmzreader/`.

| Produktionsfil | Ändring |
|---|---|
| `AppContainer.kt` | Kopplar ihop nätverkspolicy, kö och livscykel |
| `MainActivity.kt` | Meddelar synlighet till äldre Androids kö |
| `offline/DownloadScheduler.kt` | Gemensam livscykelkrok; äldre implementation flyttad |
| `offline/InAppDownloadScheduler.kt` (ny) | Förgrunds- och nätverksstyrd körning |
| `offline/DownloadNetwork.kt` (ny) | Standardnätets tillgänglighet och kostnad |
| `data/network/CallCancellation.kt` (ny) | Avbryter blockerande HTTP-anrop |
| `data/pdf/PdfDownloader.kt` | Säkert misslyckat filbyte och HTTP-avbrott |
| `data/search/SearchIndexDownloader.kt` | Återanvänder verifierad fil; kontrollcallback och HTTP-avbrott |
| `data/search/SearchIndexDownloads.kt` | Bevarar avbruten import och återställer UI-läge |
| `data/search/SearchIndexManager.kt` | Indexidentitet för rank och snippet |
| `ui/search/SearchScreen.kt` | Ny sökning efter indexbyte; inga gamla utdrag; injicerbar logg för JVM-test |
| `ui/search/IssueSearch.kt` | Indexbundna frågor och utdrag i numret |
| `data/db/Daos.kt` | Transaktioner för snapshot, import och läs–ändra–skriv |
| `data/backup/BackupRepository.kt` | Gemensam transaktion för hela importen och för snapshot |
| `data/repository/ReadingRepository.kt` | Använder transaktionell ändring av läsdata |
| `data/repository/BookmarkRepository.kt` | Transaktionell bokmärkesredigering |
| `ui/components/Components.kt` | Oanvänd platshållare borttagen |
| `ui/reader/PageBitmapCache.kt` | Oanvänt mätanrop borttaget |

| Testfil | Ändring |
|---|---|
| `offline/InAppDownloadSchedulerTest.kt` (ny) | Fyra tester för wifi, nätbyte, förgrund och en körning åt gången |
| `ui/SearchViewModelTest.kt` (ny) | Verkligt indexbyte med samma söktext |
| `data/backup/BackupRepositoryTest.kt` | Tre samtidighetstester mot Room 3 |
| `data/pdf/PdfDownloaderTest.kt` | Misslyckat namnbyte samt tyst HTTP-server |
| `data/search/SearchIndexDownloadTest.kt` | Avbruten import/återanvändning samt tyst manifestserver |
| `data/search/SearchIndexManagerTest.kt` | Gamla indexidentiteter avvisas |
| `data/repository/ReadingRecorderTest.kt` | Testdubbeln för skrivfel anpassad till DAO:s nya transaktionsingång |

Dessutom: `CLAUDE.md` länkar hit; ADR 0025 och denna rapport är nya. Inga PDF:er, arkivcacher, sökindex, nycklar eller APK:er läggs i Git.

## 4. Tester och verifiering

Baslinje före ändringarna: **519 JVM-tester och 260 Python-tester passerade**.

Efter ändringarna: **532 JVM-tester passerade, 0 fel och 0 överhoppade**. Det är 13 nya regressionstester. Pythonverktygen har inte ändrats; deras 260 tester passerade under denna arbetsomgång.

Följande slutkontroll passerade:

```bash
./gradlew --offline :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRc
.venv/bin/python -m unittest discover -s tools/tests
git diff --check
```

- Lint: **0 fel, 21 varningar**, samma antal som före rättningarna.
- Debug-APK, instrumenterat testpaket och R8-optimerad RC-APK byggdes.
- RC-APK kontrollerades: inga `.db`-filer ingår.
- Room-genererad kod kontrollerades: de nya metoderna omsluts av `performInTransactionSuspending`, alltså Room 3:s transaktionsväg. Inga Room 2/SupportSQLite-API:er har använts.
- Arkivspärrens JVM-logg skapades inte: inga spärrade arkiv- eller GitHub-försök noterades.
- **Arkivtrafik i denna arbetsomgång: 0 byte, 0 manuella arkivkontroller.** HTTP-testerna använde MockWebServer och lokala servrar.
- `adb devices -l` visade ingen ansluten enhet. Ingen instrumenterad svit eller manuell telefonkontroll har därför körts. Ett byggt testpaket är inte ett genomfört enhetsprov.

Tidiga mellanbyggen hade ett borttaget importnamn som fortfarande behövdes, en testdubbel som behövde gå genom nya modify-metoden samt två testantaganden kring återanvändning/vänteläge. Dessa rättades innan den gröna slutkontrollen. Rapportens resultat gäller slutläget.

## 5. Acceptanskriterier för denna rättningsomgång

- [x] Alla sex felområden från granskningen har en kodrättning.
- [x] Relevanta regressionstester för avbrott, filbyte, indexbyte, samtidighet och äldre Androids körlogik passerar.
- [x] De två oanvända funktionerna är borttagna; Verifying är inkopplat.
- [x] Befintliga JVM- och Python-tester passerar.
- [x] Debug-, test- och R8-optimerad RC-variant byggs; lint saknar fel.
- [x] Rapport och ADR gör ändringarna spårbara för Claude Code.
- [ ] Android 8–13: faktisk nätverksväxling, skärmsläckning, hemknapp och rotation verifierade på enhet/emulator.
- [ ] Android 14+: systemstopp under indeximport och fortsatt UIDT-körning verifierade på enhet.
- [ ] Releasekandidaten granskad och godkänd för merge/publicering.

De sista punkterna är kvarvarande releasekontroller. De är inte avbockade av JVM-testerna.

## 6. Andra iakttagelser och kända begränsningar

- PDF-motorns höga minnestoppar på de tyngsta sidorna är oförändrade (ADR 0023). Ny PDF-motor är ett större beslut och har inte införts.
- Den tidigare dokumenterade instabiliteten i liggande läge är inte utredd på telefon här. Denna omgång ska inte tolkas som att den är löst.
- Lint rapporterar bland annat versionsförslag, `UsableSpace`, KTX-förslag och två trust-manager-varningar i Commons Net-biblioteket. Appens HQ-kod använder `FTPClient`; varningen visar inte att appens HTTPS-klient accepterar godtyckliga certifikat. Inga beroenden eller TLS-inställningar har ändrats. Versionsförslagen har inte kontrollerats mot nätet.
- Gradle rapporterar konfigurationsupplösning av `hostSqlite` under konfiguration och föråldrade funktioner inför Gradle 10. Detta är kvar som underhållsarbete.
- Compose-testerna använder äldre, deprecated testregel. Migrering till v2 är ett separat testarbete.
- Stora UI-/ViewModel-filer är fortfarande ett underhållsområde; någon bred uppdelning gjordes inte inom buggrättningarna.
- Flera äldre ADR-/rapporttexter beskriver historiskt beteende. Exempelvis backloggens idé om påhittad testtext är redan genomförd i de nuvarande testdatabaserna enligt RC3 och verktygens källkod; anta inte att varje gammal backloggrad fortfarande kräver kodändring.
- Appen saknar fortfarande information om vilket release-manifest det importerade indexet kom från, enligt RC4:s kända begränsningar. Ingen notifiering om nyare index har byggts.

## 7. Överlämning och återstående steg för Claude Code

1. Börja i denna rapport, ADR 0025 och Git-diffen mot `main`. Grenen är `fix/pre-1.0-review`. Använd `git log --oneline main..HEAD` och `git diff main...HEAD` för de separata lokala ändringscommittarna.
2. Granska särskilt Room-transaktionerna, nätverkscallback/livscykel på API 26–33 och indexidentiteten i sökflödet.
3. Kör testerna ovan och instrumenterade tester enligt `tools/device_test.sh` när en separat testenhet finns tillgänglig. Bevara isoleringen från användarens app.
4. Prova den äldre nedladdningsvägen mot en lokal server: wifi-regeln på/av, wifi → mobilnät → wifi under överföring, app i bakgrund/förgrund, skärmsläckning och rotation. Den gamla offlinefilen ska finnas kvar efter misslyckad uppdatering.
5. Prova UIDT-stopp under indeximport. Den tidigare sökningen ska fortsätta fungera, gränssnittet ska kunna försöka igen och den färdiga filen ska återanvändas.
6. Genomför RC4:s åtta manuella punkter på det nya kandidatunderlaget utan att skriva över gamla bevis med antaganden.
7. Ta ställning till en ny kandidat, förslagsvis RC5. Uppdatera version, kandidattagg och publiceringsunderlag inom releasearbetet. **Den nuvarande frysningskontrollen mot v1.0.0-rc4 ska inte passera om dessa kodändringar införs**: ett nytt godkänt frysningsunderlag behövs före 1.0.0.
8. Merga och pusha först efter granskning och de kvarvarande godkännandekontrollerna. Ingen sådan GitHub-åtgärd har utförts av Codex i denna omgång.

## 8. Förslag om fortsatt planering

Behåll arbetet inom kvalitetssäkring inför 1.0. En ny kandidat efter dessa rättningar är lämpligare än nya 1.1-funktioner. Projektplanens frysta baseline har inte skrivits om. En ny kandidat och nytt frysningsunderlag är ett förslag till releasebeslut, inte något som redan har genomförts.
