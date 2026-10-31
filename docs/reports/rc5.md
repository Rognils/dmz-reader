# 1.0.0-rc5: Codex rättningar och två regressionsrättningar

Datum: 2026-10-31 · Tagg `v1.0.0-rc5` på `0fc1a95` · versionCode 9905

## Sammanfattning

rc5 är rc4 plus Codex rättningar F1–F7 ([codex-granskning-2026-10-30.md](codex-granskning-2026-10-30.md), ADR 0025) och fyra rättningar av mina egna efter granskningen av dem. Ingen ny funktionalitet.

- **Två regressioner i Codex gren** är rättade, med regressionstester: hämtningen sa "Väntar på wifi" också utan nät, och sökträffarna försvann medan man skrev.
- **Två fynd från enhetsproven** är rättade efter ditt godkännande: väntebeskedet följer nätet medan hämtningen väntar, och testkörarens kontext på API 28.
- **Proven:** JVM, Python och lint gröna. Den instrumenterade sviten 83 av 83 på Android 9, 13 och 16, utan en enda förfrågan till arkivet eller GitHub. Den äldre nedladdningskön är prövad under störningar på Android 9 och 13, och systemstopp under indeximport på Android 16.
- **Din app** har rc5, med samma data och inställningar som före. Du importerade p3 själv på rc5.

## Rättningarna

### Utan nät: eget besked (1a)

Codex lät hämtningen av sökindexet vänta på ett tillåtet nät, men beskedet var alltid "Väntar på wifi …", också i flygplansläge och med wifi-regeln avslagen. Nu ger nätet ett av tre utslag (`NetworkVerdict`): tillåtet, inget nät, eller mobilnät med wifi-regeln på. Utan nät säger kortet:

> Ingen internetanslutning. Hämtningen startar när telefonen är ansluten.

Fem JVM-tester täcker kombinationerna av nät och regel.

**Efter enhetsproven:** beskedet sattes bara när hämtningen beställdes. Slogs nätet av medan kortet stod på "Väntar på wifi", stod det kvar. Nu följer det nätet och regeln så länge hämtningen väntar, åt båda hållen, och hämtningen startar av sig själv när nätet blir tillåtet. Tre JVM-tester för övergångarna. Beskedet när en pågående hämtning avbryts ("Hämtningen av sökindexet avbröts …") är oförändrat, som du bad om.

### Sökträffarna står kvar medan man skriver (1b)

Codex band sökningen till det öppnade indexet (F4), men tömde då träffarna vid varje tecken: "am" → "ami" gav en tom lista och "Söker…" tills den nya sökningen svarade. Nu:

- **Samma index:** de tidigare träffarna står kvar tills den nya sökningen svarar.
- **Nytt index med samma fråga:** de gamla träffarna och utdragen tas bort direkt, så att träffar och utdrag från det gamla indexet aldrig visas. Det är F4:s skydd, och det är kvar.

Samma sak i Sök i numret. Fyra JVM-tester. De två om skrivandet misslyckas på Codex version.

### Testkörarens kontext på API 28

Den isolerade kontexten i testköraren skickade appens minnesvarningar till sig själv, och på Android 9 blev det en oändlig rekursion (`StackOverflowError`). Följden var att fem omslag hämtades från arkivet i stället för ur appen, och att spärren fick stoppa dem. Nu går varningarna till den riktiga appen. Bara testverktyget är ändrat.

## Prov per Android-version

Alla prov mot servrar på datorn eller i testet. Ingen förfrågan till dmzarkivet.se eller GitHub i någon av dem.

| Prov | Android 9 (API 28, emulator) | Android 13 (API 33, emulator) | Android 16 (din telefon, debug-appen) |
|---|---|---|---|
| JVM 544, Python 260, lint 0 fel, `prepush_check.sh` | grönt (datorn) | | |
| Instrumenterade sviten | 83/83, 0 till arkivet, 0 till GitHub, 0 spärrade | 83/83, 0 / 0 / 0 | 83/83, 0 / 0 / 0 (`device_test.sh`) |
| Den äldre kön under störningar (PDF och index) | rätt | rätt | inte tillämpligt, UIDT |
| Kortets text vid nätbyten | rätt | rätt | rätt |
| Systemstopp under indeximport | inte tillämpligt | inte tillämpligt | rätt |
| Indexbyte med samma fråga | | | rätt |
| Träffarna står kvar medan man skriver | JVM | JVM | rätt, skärminspelning |

Sviten på API 28 kördes först med 5 spärrade försök mot arkivet (rekursionen ovan). Efter rättningen: 83 av 83 och noll försök.

### Den äldre kön, Android 9 och 13

`DownloadScenarioDeviceTest`, som körs bara på begäran, hämtar en strypt PDF och ett strypt testindex samtidigt från lokala servrar. Störningarna gjordes från datorn.

| Störning | PDF | Sökindex |
|---|---|---|
| wifi → mobilnät, regel på | pausar | pausar |
| regel av | fortsätter | fortsätter |
| regel på igen | pausar | pausar |
| mobilnät → wifi | fortsätter | fortsätter |
| inget nät, regel på eller av | pausar | pausar |
| nätet tillbaka | fortsätter | fortsätter |
| hemknappen | pausar | pausar |
| tillbaka i appen | fortsätter | fortsätter |
| skärm av | pausar | pausar |
| skärm på | fortsätter | fortsätter |
| rotation, båda hållen | pausar inte | pausar inte |

Sökindexet återupptogs med `Range` varje gång, sex gånger på varje version. PDF:ens SHA-256 stämde med testnumret, och indexet importerades (6 nummer, 263 sidor).

**Det här prövades i två separata körningar,** på båda versionerna:

- **Pauser och återupptagning,** inklusive hemknappen, skärmen och rotationen, prövades i den första körningen ovan. Den gjordes före rättningen av väntebeskedet.
- **Kortets text** prövades i en egen körning efter rättningen, utan hemknappen och rotationen. Med dem i samma körning öppnade `am start` en ny aktivitet på Bibliotek, och testtråden fastnade efter rotationen. Det är fel i mätningen, inte i appen.

Kortets text i den andra körningen, samma på båda:

| Läge | Kortet |
|---|---|
| wifi, regel på | Laddar ned sökindexet från GitHub. |
| mobilnät, regel på | Hämtningen av sökindexet avbröts. Den fortsätter där den slutade. |
| samma, **Försök igen** | Väntar på wifi. … |
| inget nät, regel på | Ingen internetanslutning. Hämtningen startar när telefonen är ansluten. |
| inget nät, regel av | samma |
| mobilnät, regel av | Laddar ned sökindexet från GitHub. |
| wifi, regel på | Laddar ned sökindexet från GitHub. |

### Din telefon, Android 16, debug-appen

- **Systemstopp under import** (`cmd jobscheduler stop`, skäl 13): kortet visar att hämtningen avbröts, med **Försök igen**. Den hämtade filen och beställningen är kvar, och det tidigare indexet svarar fortfarande på sökningar. **Försök igen** importerade p3 utan någon ny filförfrågan (2 före, 2 efter), "återupptaget=true", 185 nummer och 11 293 sidor.
- **Indexbyte med samma fråga:** "amiga" gav 1 sida i 1 nummer i ett litet testindex. Efter bytet till p3, utan att frågan ändrades: 7 289 sidor i 185 nummer, och bara utdrag ur p3.
- **Träffarna medan man skriver:** skärminspelningen visar att träffarna står kvar från "am" till "amiga".
- **Flygplansläge:** "Ingen internetanslutning …".
- **Nätbeskedet medan hämtningen väntar:** 4G med regeln på gav "Väntar på wifi …". Flygplansläge och regeln av gav "Ingen internetanslutning …". Wifi startade hämtningen av sig själv. Ingenting trycktes i kortet.

## Din release-app

### Installationen 2026-10-29 21:45

Din app hade `lastUpdateTime` 21:45:27, efter min senaste installation. Utredningen:

| | |
|---|---|
| versionName / versionCode | 1.0.0-rc4 / 9904 |
| Certifikatets SHA-256 | `5A:36:79:EC:…:F7:27`, rätt |
| firstInstallTime, lastUpdateTime | båda 2026-10-29 21:45:27, alltså en ny installation |
| Installerad av | App Manager (`io.github.muntashirakon.AppManager`), från en fil hämtad i Firefox |
| APK:n | identisk med `dist/dmz-reader-1.0.0-rc4-release.apk` |

Min sista installation var 21:29. Ingen av mina kommandon installerade något efter det. Det var din egen installation av rc4.

### Uppdateringen till rc5

`adb install -r` 2026-10-31 15:21:43, signerad med rätt certifikat. Exporten före och efter, i `~/dmz-backup-före-rc5/`, är lika utom `appVersion` och `exportedAt`. Inställningarna är lika utom "Senaste export". `firstInstallTime` är oförändrad.

Du importerade sedan p3 själv från Download: 185 nummer, 11 293 sidor, 91 MB. **Det är RC4-punkt 8, gjord av dig på rc5.**

### Röktest

- **Söka a → am → ami → amiga.** Textträffarna står kvar från "am" till "amiga" ("400 sidor …" tills "7 289 sidor i 185 nummer"). Mellan "a" och "am" försvinner nummerträffen "Datormagazin 1986 Nr 1" i ungefär en halv sekund innan textträffarna kommer. Se kända begränsningar. Du har godkänt beteendet.
- **Ladda ned sökindex i flygplansläge.** Under Inställningar visar release-appen "Fulltextsökning kräver ett separat sökindex. Anslut till internet för att hämta det, eller importera en fil manuellt." med **Försök igen**. Wifi-regeln nämns inte. Vänteläget med "Ingen internetanslutning …" nås först när appen kan läsa manifestet från GitHub, vilket den inte kan medan repot är privat. Det är prövat i debug-appen ovan, och mot den riktiga releasen i publiceringschecklistans steg 14b.
- p3 är oförändrat före och efter röktestet.

## Godkännandekriterier

RC4:s åtta punkter, på rc5. Punkterna 1–7 görs i debug-bygget mot den lokala servern, som för rc4. Avbockningarna för rc4 gäller inte rc5.

Förberedelse, en gång:

```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:8765 tcp:8765
adb shell am start -S -n se.c64.dmzreader.debug/se.c64.dmzreader.MainActivity \
    --es dmzSearchIndexManifest http://127.0.0.1:8765/Rognils/dmz-reader/releases/latest/download/search-index.json
```

Servern startas för varje punkt med `python3 tools/local_search_index_server.py dist/search-2026-10-06-006-p3.db` och tillvalen nedan, och stoppas med Ctrl-C.

- [ ] **1. Ingen indexfil → ladda ned → sökningen fungerar.** Utan tillval. Sök-fliken: kortet visar cirka 91 MB. Tryck **Ladda ned sökindex**. Kortet försvinner, och en sökning ger träffar i tidningarnas text.
- [ ] **2. Avbrott mitt i → fortsätt → fungerar.** Ta bort indexet under Inställningar. Starta servern med `--cut-after 30000000`. Ladda ned: kortet säger att hämtningen avbröts vid 30 MB. Tryck **Försök igen**: förloppet fortsätter från 30 MB, inte från 0.
- [ ] **3. Wifi-regeln stoppar på mobilnät.** "Ladda ned endast via wifi" på, wifi av och mobildata på. Kortet ska säga "Väntar på wifi…", och ingenting ska hämtas. Slå på wifi igen: hämtningen startar av sig själv.
- [ ] **4. För lite utrymme ger ett begripligt fel.** Starta servern med `--claim-size 500000000000`, mer än telefonens lediga utrymme. Ladda ned: "Det finns inte plats för sökindexet. Det behövs cirka … MB ledigt, och det finns … MB." Ingenting hämtas.
- [ ] **5. Fel kontrollsumma importeras aldrig.** Starta servern med `--bad-checksum`. Ladda ned: hela filen hämtas, sedan "Den hämtade filen stämmer inte med uppgifterna i GitHub-releasen. Den har tagits bort, och ingenting har importerats."
- [ ] **6. Ett befintligt p3-index påverkas inte av ett misslyckat försök.** Med p3 importerat (efter punkt 1 eller 2): servern med `--bad-checksum`, och Inställningar → Sökindex → **Ladda ned sökindex** → **Ladda ned**. Efter felet visar Inställningar samma rader som före (185 nummer, 11 293 sidor, samma importtid), och sökningen fungerar.
- [ ] **7. PDF-hämtning och indexhämtning turas om.** Ta bort indexet. Starta servern med `--rate 500`, så att hämtningen tar ett par minuter. Starta hämtningen, öppna sedan ett nummer som inte är hämtat och tryck **Läs**. Kortet ska säga att sökindexet laddas ned när det blir dess tur, och numret öppnas. Stäng läsaren: sökindexet fortsätter där det var. Det här hämtar ett nummer från arkivet, 15–25 MB.
- [x] **8. Den manuella importen fungerar fortfarande.** I din app (rc5): Inställningar → **Importera sökindex**, och välj `search-2026-10-06-006-p3.db` i Download. Raderna ska visa 185 nummer och 11 293 sidor. **Gjord av dig på rc5 2026-10-31:** 185 nummer, 11 293 sidor.

Efteråt: `adb reverse --remove tcp:8765`, och adressen tillbaka med `adb shell "am start -S -n se.c64.dmzreader.debug/se.c64.dmzreader.MainActivity --es dmzSearchIndexManifest ''"`.

## Codex punkter

| Punkt | Läge |
|---|---|
| F1 Systemstopp under indeximport | stängd: prövad på Android 16 |
| F2 Wifi-regeln för PDF-kön på Android 8–13 | stängd: prövad på Android 9 och 13 |
| F3 Förgrundskörning på Android 8–13 | stängd: hemknapp, skärm och rotation prövade på Android 9 och 13 |
| F4 Sökning efter indexbyte | stängd: prövad på Android 16, och regressionen den gav (1b) är rättad |
| F5 Backup och samtidiga skrivningar | stängd: JVM-testerna mot Room 3, och exporterna före och efter rc5 |
| F6 Misslyckat namnbyte av en PDF | stängd: JVM-testet. Ett misslyckat namnbyte går inte att framkalla på telefonen. |
| F7 Avbrott under blockerande HTTP-väntan | stängd: JVM-testerna, och pauserna inom sekunder på emulatorerna |
| Releasekandidaten granskad och godkänd | stängd: du har godkänt rc5 |
| PDF-motorns minnestoppar (ADR 0023) | öppen, oförändrad |
| Instabiliteten i liggande läge | öppen. Utredd tidigare: felet var i provet, inte i appen. |
| Lint-varningarna, Gradle 10, Compose-testregeln v2, stora UI-filer | öppna, underhåll efter 1.0 |
| Appen minns inte vilket manifest indexet kom från | öppen, kvar från rc4 |

## Skapade och ändrade filer

| Fil | Vad |
|---|---|
| `offline/DownloadNetwork.kt` | nätets tre utslag |
| `AppContainer.kt` | utslaget till sökindexets hämtning; den äldre kön får bara tillåtet eller inte |
| `data/search/SearchIndexDownloads.kt` | läget Väntar på nät, och beskedet följer nätet medan hämtningen väntar |
| `ui/search/SearchIndexCard.kt` | beskedet utan nät |
| `offline/DownloadJobService.kt` | jobbet schemaläggs om också när nätet saknas |
| `ui/search/SearchScreen.kt`, `ui/search/IssueSearch.kt` | träffarna står kvar på samma index, och tas bort direkt vid nytt index |
| `SearchIndexDownloadTest.kt`, `SearchViewModelTest.kt` | 12 nya JVM-tester |
| `androidTest/IsolatedTestRunner.kt` | minnesvarningarna till den riktiga appen |
| `androidTest/DownloadScenarioDeviceTest.kt` | ny, provet av den äldre kön under störningar, bara på begäran |
| `tools/device_test.sh` | tål en enhet där din app inte är installerad |
| `app/build.gradle.kts` | 1.0.0-rc5 (9905) |
| `docs/reports/publiceringsbeslut.md` | frysningskontroll mot `v1.0.0-rc5`, och steg 14b |

## Kända begränsningar

- **En nummerträff kan försvinna i ungefär en halv sekund medan man skriver.** Sökningen bland numren räknas om direkt för varje tecken, som i rc4. Ett enda tecken söker bara bland numren, så "a" kan ge en nummerträff som försvinner vid "am" innan textträffarna kommer. Textträffarna står kvar som avsett.
- **Vänteläget utan nät går inte att se i release-appen** förrän repot är publikt. Prövat i debug-appen och i checklistans steg 14b.
- På Android 8–13 hämtas sökindexet och PDF:erna bara medan appen är öppen.
- Återupptagningen mot GitHubs riktiga filserver prövas först på publiceringsdagen (checklistans punkt d).

## Merge och tagg

`fix/pre-1.0-review` är mergad till `main` med `--no-ff` och taggad `v1.0.0-rc5` på `0fc1a95`. `release/1.0.0` är `main` plus en commit med versionen 1.0.0 (versionCode 10000). Allt är pushat till det privata repot. Den signerade rc5-APK:n, `dist/dmz-reader-1.0.0-rc5-release.apk`, har SHA-256 `0285489719abd131ada95f16bf97554fa87dfd4b27dbfa01a70d476d63e24b0c`.
