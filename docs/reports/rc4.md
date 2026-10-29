# 1.0.0-rc4: sökindexet hämtas i appen från GitHub

2026-10-29. Gren `release/1.0-rc4` från `release/1.0.0`. Ett medvetet undantag från releasefrysningen: bara den här funktionen. Repot är privat. Ingenting hämtades från arkivservern, och ingenting från GitHub.

## Sammanfattning

Sök-fliken utan sökindex visar ett kort med knappen **Ladda ned sökindex**, och samma knapp finns under Inställningar → Sökindex bredvid den manuella importen. Appen läser manifestet `search-index.json` i den senaste GitHub-releasen och hämtar filen från den release manifestet anger. Hämtningen går genom samma nedladdningsjobb och överföringskö som PDF:erna, kontrolleras mot storlek och SHA-256, och importeras med den vanliga importen. Ingenting hämtas automatiskt.

Din app är uppdaterad till 1.0.0-rc4 och din data är oförändrad. **Den har inget sökindex importerat**, se nedan.

## Så fungerar det

**Adresserna.** Basen `https://github.com/Rognils/dmz-reader/releases` finns på ett ställe (`SearchIndexSource`):

| Vad | Adress |
|---|---|
| Manifestet | `…/releases/latest/download/search-index.json` |
| Filen | `…/releases/download/<releaseTag>/<fileName>`, ur manifestet, aldrig via latest |

GitHub omdirigerar filen till en signerad adress på sin filserver. Den sparas aldrig: varje start och varje återupptagning börjar på GitHub-adressen och följer omdirigeringen på nytt. Inget GitHub-API och inget token.

I debug-bygget kan basen bytas mot en lokal testserver, och manifestet kan hämtas från en valfri adress (till exempel en prerelease). Filen hämtas då från samma bas som manifestet:

```bash
adb shell am start -S -n se.c64.dmzreader.debug/se.c64.dmzreader.MainActivity --es dmzSearchIndexManifest ADRESS
adb shell "am start -S -n se.c64.dmzreader.debug/se.c64.dmzreader.MainActivity --es dmzSearchIndexManifest ''"   # tillbaka
```

**Manifestet** görs av `tools/make_search_index_manifest.py`. Det vägrar ett index utan `privacyFilterVersion` och filen `search.db`, så ett omaskerat index kan inte publiceras den vägen. Appen ger ett besked och hämtar ingenting när `manifestVersion` är okänd, `searchSchemaVersion` inte stöds eller `minAppVersionCode` är högre än appens. Ett manifest som inte går att läsa, eller med namn som inte hör hemma i en adress, behandlas likadant.

**Återupptagning med Range och If-Range:**

| Svar | Vad som händer |
|---|---|
| 206 med rätt Content-Range | fortsätter |
| 200 | den påbörjade filen kastas, börjar om från byte 0 |
| 206 med ändrad ETag eller storlek | börjar om från 0 |
| 416 eller annat oväntat svar | börjar om från 0 en gång, sedan fel |

**Före start** kontrolleras ledigt utrymme: det som återstår av filen, plus importens kopia och marginal (filens storlek + 50 MB). **Efter hämtning** kontrolleras storlek och SHA-256. Stämmer de inte tas filen bort och ingenting importeras. Därefter körs den vanliga importen med sin validering, och den hämtade filen tas bort. Ett misslyckat försök rör aldrig ett importerat index, eftersom importen bara byter in en fil som har gått igenom. Användardata rörs aldrig.

**Överföringskön.** Hämtningen körs i samma UIDT-jobb som PDF-kön (Android 14 och senare), efter PDF:erna, genom samma `TransferCoordinator`. En överföring åt gången: "Läs" på en PDF pausar sökindexet, som sedan fortsätter med Range. Nedladdningsnotisen visar "Laddar ned sökindexet" med förlopp och Avbryt. User-Agent är densamma som för PDF:erna.

**Wifi-regeln** gäller på två sätt. Jobbet kräver wifi när "Ladda ned endast via wifi" är på, som för PDF:erna. Dessutom kontrollerar hämtningen själv nätet, och kortet säger direkt "Väntar på wifi…". PDF-kön har bara jobbets krav. Manifestet, under 1 kB, hämtas också på mobilnät, så att kortet kan visa storleken.

## Gränssnittet

| Läge | Sök-kortet |
|---|---|
| Manifest hämtat | "Fulltextsökning kräver ett sökindex på cirka 91 MB. Allt annat i appen fungerar utan det." **Ladda ned sökindex** |
| Manifest inte nåbart | "Fulltextsökning kräver ett separat sökindex. Anslut till internet för att hämta det, eller importera en fil manuellt." **Försök igen**, **Importera manuellt** |
| Under hämtning | förlopp i MB och procent, **Avbryt** |
| Avbruten | "Hämtningen av sökindexet avbröts. Den fortsätter där den slutade." **Försök igen**, **Avbryt** |
| Fel | feltexten, **Försök igen** |

Om GitHub inte går att nå står det "Det gick inte att hämta sökindexet från GitHub. Försök igen senare." Arkivet nämns inte. Manifestet hämtas först när kortet visas, aldrig när appen startar. Sökningen på titel, år och nummer fungerar utan manifest.

Under Inställningar → Sökindex frågar appen först, om ett index redan finns: "Sökindexet i GitHub-releasen v1.0.0 är cirka 91 MB (arkivversion …). Det ersätter det nuvarande sökindexet först när hämtningen och kontrollen har lyckats." Det är så du i punkt f på publiceringsdagen kan se att knappen hittar manifestet, utan att hämta om.

### Skärmbilder

Sparade lokalt i `docs/screenshots/rc4/`, som inte versionshanteras. De är tagna i debug-bygget mot den lokala testservern. "Före" och "under" visar ett manifest med p3:s storlek, och "under" avbröts innan något kontrollerades.

| Fil | Läge |
|---|---|
| `1-fore.png` | manifest hämtat, cirka 91 MB, **Ladda ned sökindex** |
| `2-under.png` | "2 av 91 MB · 2 %", **Avbryt** |
| `3-efter.png` | kortet borta, sökning på "Commodore" med träff |
| `4-utan-manifest.png` | texten utan manifest, **Försök igen** och **Importera manuellt** |
| `lokal-avbruten.png` | debug-bygget med p3, avbrutet efter 30 MB |
| `installningar-fraga.png`, `installningar-fel-kontrollsumma.png` | frågan i Inställningar och beskedet vid fel kontrollsumma |

## Tester

| Svit | Utfall |
|---|---|
| JVM | 519 av 519. Nya: 19 i `SearchIndexDownloadTest` |
| Python | 260 av 260, varav 5 överhoppade som förut. Nya: 6 för manifestskriptet, 4 för den lokala servern |
| Instrumenterat, `device_test.sh` med det slutliga debug-bygget | 82 av 82 (varav skärmbildstestet överhoppat), omstartstestet 2 av 2, tvångsstoppet godkänt. Samma utfall vid alla tre körningarna i kväll |
| Arkivet och GitHub under sviten | 0 förfrågningar till dmzarkivet.se, 0 till GitHub, 0 spärrade försök, 0 byte över wifi och mobilnät |
| Lint | inga nya fel. En ny varning av samma slag som PDF-hämtningen redan har (`UsableSpace`) |

JVM-testerna, mot en lokal server i GitHubs ställe:

- manifestet tolkas, och hämtas via latest medan filen kommer från releasen det anger
- okänd `manifestVersion`, `searchSchemaVersion` som inte stöds, för hög `minAppVersionCode`, oläsligt manifest och sökvägar i filnamnet ger besked, och ingenting hämtas
- fel kontrollsumma ger ingen import, och filen tas bort
- återupptagning med 206, med 200 (börjar om från 0) och med ändrad ETag (börjar om från 0)
- 416 börjar om från 0 en gång och ger sedan fel
- omdirigeringen följs på nytt vid återupptagning, och den gamla filserveradressen ger 403
- wifi-regeln, både när hämtningen beställs och när den körs
- för lite utrymme ger beskedet utan att något hämtas
- ett befintligt index är orört efter fel kontrollsumma och efter en fil som inte går att importera
- en PDF-läsning pausar sökindexet, som sedan fortsätter med Range
- Avbryt tar bort den påbörjade filen
- debug-adressen flyttar också filens bas

De instrumenterade testerna (`SearchIndexDownloadDeviceTest`), mot en lokal server på telefonen med testindexet ur debug-bygget:

- hela vägen från knappen i Sök-kortet till en fungerande sökning
- avbrott mitt i, följt av **Försök igen** som fortsätter med Range från en ny omdirigering
- **Avbryt**
- kortet när manifestet inte går att nå, sökning på år och nummer, och **Försök igen** när det går

**Alla tester når aldrig GitHub.** Spärren i testbygget stoppar nu även github.com och githubusercontent.com. Testköraren pekar manifestet på en lokal adress där ingen svarar. Annars hade varje test som visar Sök-fliken utan index frågat GitHub. Den första körningen av de nya testerna visade just det: spärren stoppade två försök, när testets städning tog bort indexet medan Sök-fliken syntes. `device_test.sh` räknar nu också förfrågningar till GitHub.

### Prövat för hand i debug-bygget

Med `tools/local_search_index_server.py` på datorn och `adb reverse`, med det riktiga p3-indexet:

| Prövning | Utfall |
|---|---|
| Kortet | "cirka 91 MB" |
| Hämtning, avbruten efter 30 MB | "avbröts", "30 av 91 MB · 32 %" |
| **Försök igen** | ny omdirigering, 206, fortsatte vid 30 MB, importerat |
| Sökning på "Commodore" | 2 770 sidor i 181 nummer, samma som i din app tidigare |
| Fel kontrollsumma med p3 importerat | frågan först, sedan beskedet. Den hämtade filen borttagen, p3 orört (185 nummer, 11 293 sidor, samma fil) |
| Manifestet anger 500 GB | "Det finns inte plats för sökindexet. Det behövs cirka 1000050 MB ledigt, och det finns 204248 MB." Ingen filförfrågan |

Debug-byggets data är rensad efteråt, och adressen och `adb reverse` är borttagna.

## Version och din app

| Kontroll | Utfall |
|---|---|
| versionCode / versionName | 9904 / 1.0.0-rc4 |
| Certifikatets SHA-256 | `5A:36:79:EC:C4:CC:BF:9B:61:24:7E:35:80:B1:78:02:8F:03:E6:E4:7E:6D:6D:7F:32:5D:2A:29:54:75:F7:27`, stämmer |
| APK | `dist/dmz-reader-1.0.0-rc4-release.apk`, 24 427 276 byte, SHA-256 `77ba62c0a54e064fe9c76a3b658836d93641b57bcc5d153aafefbd75d9326a6c` |
| Databaser i APK:n | inga |

**Tre installationer.** Jag installerade rc4 kl. 21:13 och ändrade sedan koden två gånger. Den första ändringen gällde filens bas i debug-bygget och wifi-beskedet. Den andra var rimlighetsgränsen för storleken, eftersom punkt 4 i checklistan annars inte hade gått att pröva. Varje gång motsvarade den installerade APK:n alltså inte längre koden, så jag byggde om och installerade igen, kl. 21:21 och 21:29. Före och efter varje installation gjordes en export. Alla tre gångerna: `adb install -r`, Success. Det som ligger på telefonen nu är det slutliga bygget ovan.

| Export | Före | Efter |
|---|---|---|
| 21:13 | 4 nummer med data, 0 bokmärken, 6 lässessioner | samma, identiskt utom appversion och exporttid |
| 21:21 | samma | samma, identiskt utom exporttid |
| 21:29 | samma | samma, identiskt utom exporttid |

Inställningarna före och efter skiljer sig bara i det väntade: tiden för senaste export, de nya texterna om arkivindexet och sökindexet, och den nya knappen. "Ladda ned endast via wifi" är på, som förut. Fortsätt läsa visar 1991 nr 4, sida 13 av 88. Exporterna och inställningarnas text ligger i `~/dmz-backup-före-rc4/`, och exportfilerna är borttagna ur telefonens Download.

**Ditt p3-index fanns inte.** Uppgiften förutsätter att p3 är importerat, men appen säger "Inget sökindex är importerat". Den installerades om 2026-10-12 23:51, efter min rc3-installation den 11 oktober, och p3 har inte importerats sedan dess. Filen `search-2026-10-06-006-p3.db` ligger kvar i Download. Jag har inte importerat den.

## Godkännandekriterier

rc4 godkänns när alla åtta fungerar, inte efter ett visst antal dagar. Punkterna 1–7 görs i debug-bygget (DMZ Reader DEV), mot den lokala servern på datorn. Den behövs eftersom repot är privat: appen når inte releaserna förrän repot är publikt.

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
- [ ] **8. Den manuella importen fungerar fortfarande.** I din app (rc4): Inställningar → **Importera sökindex**, och välj `search-2026-10-06-006-p3.db` i Download. Raderna ska visa 185 nummer och 11 293 sidor. Det ger dig också p3 tillbaka.

Efteråt: `adb reverse --remove tcp:8765`, och adressen tillbaka med `adb shell "am start -S -n se.c64.dmzreader.debug/se.c64.dmzreader.MainActivity --es dmzSearchIndexManifest ''"`.

## Skapade och ändrade filer

| Fil | Vad |
|---|---|
| `data/search/SearchIndexRelease.kt` | manifestet, prövningen och adresserna |
| `data/search/SearchIndexDownloader.kt` | hämtning, återupptagning, kontroll, och spärren för GitHub i testerna |
| `data/search/SearchIndexDownloads.kt` | beställning, körning i jobbet, kön, wifi, import och lägen |
| `ui/search/SearchIndexCard.kt` | kortet i Sök och innehållet under Inställningar |
| `SearchScreen.kt`, `SearchIndexSection.kt`, `SettingsScreen.kt`, `DmzReaderRoot.kt` | kortet, knappen, frågan och manuell import från Sök |
| `AppContainer.kt`, `MainActivity.kt`, `DownloadJobService.kt`, `DownloadScheduler.kt` | kopplingen till jobbet, notisen och debug-adressen |
| `LiveArchiveGuard.kt` | spärren stoppar även GitHub i testerna |
| `SearchIndexManager.kt` | importens marginal är synlig för hämtningen |
| `tools/make_search_index_manifest.py`, `tools/local_search_index_server.py` med tester | manifestet och den lokala servern |
| `tools/device_test.sh` | räknar GitHub, och tål en app utan sparade användningshändelser |
| `README.md`, `docs/release-notes-1.0.0.md`, `tools/README.md`, `docs/reports/publiceringsbeslut.md`, `docs/backlog-1.1.md` | dokumentationen |

## Utöver uppgiften, och avvikelser

- **`device_test.sh` hade ett fel.** Skriptet avbröts tyst när systemet inte hade någon sparad användningshändelse för din app, vilket blev fallet efter ominstallationen den 12 oktober. Raden är nu tålig.
- **Wifi-kontrollen i hämtningen,** utöver jobbets krav. Annars hade kortet stått på "när det blir dess tur" på mobilnät, och på Android 8–13 hade regeln inte gällt alls. PDF-kön har inte den kontrollen.
- **Frågan före ersättning** under Inställningar, när ett index redan finns.
- **"Importera manuellt" i Sök-kortet** öppnar filväljaren direkt, med samma import som under Inställningar.
- **`tools/local_search_index_server.py`,** så att punkterna 1–7 går att pröva på telefonen medan repot är privat.
- **Backloggpunkten** är kortad till det som återstår: en notis om ett nyare index.

## Kända begränsningar

- Appen minns inte vilket manifest det importerade indexet kom från. Frågan under Inställningar visar därför alltid releasens index, även om det är samma som det importerade.
- På Android 8–13 hämtas sökindexet bara medan appen är öppen, som PDF:erna.
- Återupptagningen mot GitHubs riktiga filserver är prövad först på publiceringsdagen (punkt d i checklistan). Här är den prövad mot servrar som beter sig som GitHub.

## Merge och tagg

`release/1.0-rc4` är mergad till `main` och taggad `v1.0.0-rc4`. `release/1.0.0` är `main` plus en commit med versionen 1.0.0 (versionCode 10000), så att frysningskontrollen i punkt a bara ska visa den. Allt är pushat till det privata repot.
