# Rapport – före 1.0.0, del 2: testerna bort från arkivet, filter p3, slutliga texter och ren historik

Datum: 2026-10-11 · Repot `Rognils/dmz-reader` är nytt och privat · Ingenting är byggt eller taggat som 1.0.0 · Release-appen på telefonen är inte rörd

## 1. Sammanfattning

| Del | Vad | Läge |
|---|---|---|
| M | Inga automatiska tester mot arkivservern | Klart. Tre rena sekvenser gröna, noll förfrågningar till arkivet. |
| N | Filter version 3 | Klart. `search-2026-10-06-006-p3.db` är byggd, kontrollerad och upplagd som prerelease i det nya repot. |
| O | Slutliga texter | Klart, på `main` och `release/1.0.0`. |
| P | Omskriven historik och nytt repo | Klart. Verifieringen stämde på alla punkter. Arbetskatalogen står på den nya historiken. |

Från arkivservern hämtades ingenting under den här uppgiften: 0 byte.

## 2. Del M – inga automatiska tester mot arkivservern

### De tre testerna

Alla tre går nu mot en lokal server på telefonen (`LocalArchive`, en MockWebServer i testpaketet) i stället för dmzarkivet.se.

| Test | Förut | Nu |
|---|---|---|
| `CoverFallbackNetworkTest` (2 tester) | 12–13 omslagsskanningar ur arkivet, 103–108 MB | Samma tolv adresser ur arkivindexet, men förfrågningarna tas emot lokalt och besvaras med en påhittad bild |
| `OfflineDeviceTest` (flygplanstestet) | PDF:en för 1986 nr 1, 15 MB | Ett påhittat nummer på sex sidor, 0,3 MB |
| `ReaderDeviceTest.readDownloadTurnPageAndClose` | samma PDF, 15 MB | samma påhittade nummer |
| `ReaderDeviceTest.interruptedDownloadShowsTheMessageAndResumesWithRange` | fanns inte | nytt |

Täckningen, punkt för punkt:

| Det som skulle täckas | Hur det prövas nu |
|---|---|
| Reservvägen för omslag | Tolv nummer utan inbyggd miniatyr hämtar sina omslag. Servern ska få en förfrågan per omslag, till exakt de sökvägar som står i arkivindexet. En miniatyr som inte finns ger först ett fel och sedan en hämtning från adressen. |
| Högst fyra samtidiga förfrågningar | Servern dröjer 0,3 sekunder per bild, så att alla tolv står i kö. Toppen ska vara fyra. |
| Nedladdning och läsaren på en nedladdad PDF | Läs hämtar numret, läsaren öppnas, sidor byts med tryck och svep, läspositionen sparas, och nästa öppning görs ur cachen utan ny förfrågan. |
| Läsning utan nät | Numret laddas ned permanent, flygplansläget slås på, och läsaren öppnas ur filen. |
| Nätverksavbrott | Servern bryter det första svaret efter halva filen. Läsaren ska visa feltexten och "Försök igen", och den hämtade delen ska ligga kvar. |
| Range och återupptagning | Efter "Försök igen" ska den andra förfrågan ha `Range: bytes=N-`, där N är exakt det som hann hämtas, och `If-Range` med filens ETag. Filen ska bli byte-identisk med serverns. |
| User-Agent | Servern kontrollerar att varje förfrågan har appens User-Agent. |

Det nya avbrottstestet prövar också texten från rc3: "Det gick inte att nå DMZArkivet." och "Nedladdningen kan återupptas där den slutade."

Flygplanstestets väntan på att wifi ska stabilisera sig efteråt är borttagen. Den behövdes för att nästa test hämtade från arkivet. Det gör inget test längre.

### Testmaterialet

Gjort av `tools/make_test_media.py`, och committat tillsammans med skriptet:

| Fil | Innehåll | Storlek |
|---|---|---|
| `app/src/androidTest/assets/test_issue.pdf` | "Testmagasinet", sex sidor med påhittad text. Skriven för hand utan komprimering, och utfylld med ett block som ingen sida använder, så att en nedladdning går att avbryta mitt i. | 303 983 byte |
| `app/src/androidTest/assets/test_cover.jpg` | Ett ritat omslag med ränder och rutor, 950 × 1320 pixlar, utan text | 68 382 byte |

Ingenting i dem kommer ur tidningarna. Samma skript ger samma PDF varje gång, och ett test kontrollerar det. `tools/prepush_check.sh` tillåter just den PDF:en, med sökväg och SHA-256, och stoppar alla andra.

Testerna gör tillfälligt om 1986 nr 2 i testkörningens egen databas till testnumret (storlek, ETag och sidantal) och ställer tillbaka raden efteråt. Inget annat test använder det numret.

### Skyddsnätet

`LiveArchiveGuard` i appens kod, verksam bara i debug-bygget:

- **HTTP:** en interceptor ligger först i båda HTTP-klienterna (PDF och omslag). När spärren är på kastar den ett fel för varje förfrågan till dmzarkivet.se, innan någon anslutning görs.
- **FTP:** `FtpScanSource` gör samma kontroll före anslutningen.
- **Testflaggan:** testköraren slår på spärren för varje körning. Den släpps bara med `-e dmzLiveArchive true`, för manuella kontroller.
- **Lokal server:** ett test som vill pröva nätvägen pekar om arkivets adresser till den lokala servern, med samma sökväg.
- **JVM-testerna** körs med spärren på från start (`dmz.blockLiveArchive`), och med namnuppslag som bara känner den egna datorn (`jdk.net.hosts.file`).

I release-bygget går spärren inte att slå på, och interceptorn släpper igenom allt orört. Debug-bygget tillåter okrypterad HTTP till telefonen själv (`127.0.0.1` och `localhost`), för den lokala servern. Release-byggets manifest har ingen sådan inställning: jag kontrollerade det i den byggda APK:n.

Spärren har egna tester (`LiveArchiveGuardTest`, 8 stycken), som försöker nå arkivet med flit och ska bli stoppade.

### Verifieringen: noll anslutningar

Tre sätt som inte beror av varandra:

| Kontroll | Hur | Utfall |
|---|---|---|
| Appens logg | Varje HTTP-förfrågan och varje FTP-anslutning loggas i debug-bygget. `device_test.sh` räknar rader med dmzarkivet.se. | 0 i alla tre sekvenserna |
| Spärren | Varje stoppat försök räknas och loggas. | 0 i alla tre |
| Systemets nätstatistik | `dumpsys netstats` redovisar per app hur många byte som har tagits emot över wifi och mobilnät. Trafiken till den lokala servern går inte över något nät och finns inte där. | 0 byte i alla tre |
| Hela systemloggen | Den råa loggen från alla processer, sökt efter dmzarkivet.se. | 0 rader i alla tre |
| JVM-testerna | Filen där spärrade försök skrivs finns inte efter körningen, och ett test visar att namnet dmzarkivet.se inte går att slå upp. | 0 försök, 500 av 500 tester |

`device_test.sh` gör de tre första kontrollerna själv efter varje körning och avslutar med fel om någon slår. Raden som börjar med `ARKIVET:` visar utfallet.

Att nätstatistiken verkligen ser trafik vet jag: för testappens tidigare installation stod där 169 MB, från hämtningarna i del E.

En mätning jag förkastade: testköraren läste först appens mottagna byte med `TrafficStats`, men det värdet räknar med trafiken till den lokala servern.

### Tre rena sekvenser

Avinstallera debug-appen, installera, hela `device_test.sh`:

| Sekvens | Svit | Omstart och tvångsstopp | Förfrågningar till arkivet | Spärrade | Mottaget över nätet | Tid |
|---|---|---|---|---|---|---|
| 1 | 77 av 77 | gröna | 0 | 0 | 0 byte | 3 min 37 s |
| 2 | 77 av 77 | gröna | 0 | 0 | 0 byte | 3 min 37 s |
| 3 | 77 av 77 | gröna | 0 | 0 | 0 byte | 3 min 37 s |

Sviten har ett test mer än förut (avbrottstestet). Sju av de 77 är stresstesterna, som hoppas över. Release-appens yttre fingeravtryck var identiskt före och efter varje sekvens.

Underlag: `docs/reports/pre-1.0-del2/svit-utan-arkiv/`.

### Det manuella kontrollskriptet

`tools/live_archive_smoke_test.sh`:

- skriver "LIVE TEST – kontaktar dmzarkivet.se" och kräver att man skriver ja
- gör tre förfrågningar för 1986 nr 1: de första 16 kB av omslaget, 4 kB ur mitten av PDF:en med Range, och de första 16 kB av en HQ-sida över FTP
- hämtar aldrig en hel fil, sammanlagt under 40 kB
- skriver ut antalet hämtade byte

**Skriptet är inte kört mot servern.** Jag kontrollerade syntaxen och att det avbryter utan ett ja. Inget annat skript anropar det.

En tolkning: du skrev "högst ett omslag och en HQ-sida". Jag lät skriptet hämta bara början av dem också, eftersom en hel HQ-sida är 5 MB och syftet är att se att adresserna och inloggningen fungerar.

### Övrigt i del M

- `device_test.sh` avbryter nu om PDF:en för 1986 nr 1 saknas i datorns cache. Förut skrev den att testerna skulle hämta den från arkivet.
- Testerna som körs på begäran mot riktig server (`HqRealDeviceTest`, `ReaderDeviceTest.verify…`, `RealIndexDeviceTest`, `DownloadNotificationDeviceTest`) stoppas av spärren om något saknas på telefonen. Kommentarerna säger att de behöver `-e dmzLiveArchive true`.
- **De övriga testerna i sviten läser fortfarande den riktiga PDF:en för 1986 nr 1,** som skriptet lägger på telefonen från datorns cache. Den hämtas inte från nätet och finns inte i git, men den som klonar repot måste hämta den en gång för hand. Att låta hela sviten gå på testnumret är ett större arbete och hör hemma i 1.1.

## 3. Del N – filter version 3

Detaljerna står i `docs/reports/search-privacy.md`, avsnitt 12.

| | |
|---|---|
| Fil | `search-2026-10-06-006-p3.db`, 90 943 488 byte |
| SHA-256 | `11033d29553b59083dea94fde90c55bcf7a0cb23ef5452a4083f923903682dfc` |
| `privacyFilterVersion` | 3 |
| Maskerat | 50 539 uppgifter på 5 135 av 11 293 sidor |
| Nummer efter tel, fax och liknande ord | 2 081, mot 833 i p2 |

**Stoppregeln:**

| Villkor | Utfall |
|---|---|
| De 25 utländska fallen från p2 | 24 maskeras hela. I ett har texttolkningen satt en bokstav mitt i numret, och siffrorna efter den står kvar. |
| Högst 3 % falska bland nya träffar | 2 av 130 (1,5 %). Båda var modemhastigheter och är rättade. |
| Sökfacit tappar ingen förväntad sida | 43 av 43 före och efter |

**Kontrollerna:**

| Kontroll | Utfall |
|---|---|
| Sökfacit | 43 av 43 och 0 irrelevanta, omaskerat och maskerat |
| "telefon", "bankgiro", "postgiro" | 1 253 och 1 253, 67 och 67, 294 och 295 |
| SQL, `strings` och råa bytes | 0 träffar i alla tre |
| Två byggen | byte-identiska |
| Import i debug-appen | "185 nummer och 11293 sidor, på 1,6 sekunder". Utdrag med utländska nummer visar `Tel: •••` utan siffror efter. |

`strings`-kontrollen av det första p3-bygget hittade ett utländskt nummer som inte var maskerat alls (en parentes tätt intill siffrorna). Regeln rättades, 79 nummer till maskeras, och filen byggdes om.

**Telefonen:** p2-filen är borttagen ur Download, och `search-2026-10-06-006-p3.db` ligger där. Jag har inte importerat den i din app. Telefonen var frånkopplad en stund under arbetet, och du anslöt den igen på min fråga.

## 4. Del O – slutliga texter

**README på `main`:** "OCR-skador kan innebära att en del uppgifter inte känns igen."

**På `release/1.0.0`,** som är rebasad på `main`:

| Plats | Förut | Nu |
|---|---|---|
| Inställningar, under sökindexet | "Sökindexet gör att du kan söka i tidningarnas text. Det byggs på datorn (search.db, cirka 90 MB) och importeras här. Favoriter, bokmärken och läsdata påverkas inte." | "Sökindexet gör det möjligt att söka i tidningarnas text. Det importeras separat och påverkar inte favoriter, bokmärken eller läsdata." |
| Sök-fliken utan index | "Sökindexet byggs på datorn och importeras under Inställningar → Sökindex." | "Sökindexet hämtas separat och importeras under Inställningar → Sökindex." |

Den andra ändringen bad du inte om uttryckligen. Den säger samma sak som den första och var lika fel.

**Genomgången av övriga texter.** Jag sökte i appens gränssnittstexter efter dator, bygga, skript och filnamn. En formulering till förutsätter en dator:

| Plats | Text | Ändrad? |
|---|---|---|
| Inställningar, under arkivindexet | "Arkivindexet är listan över nummer i arkivet. Ett nyare index görs på datorn och importeras här, utan att appen behöver uppdateras." | Nej |

Den är inte fel: ett nyare arkivindex görs med projektets verktyg på en dator. Men en vanlig användare har ingen fil att importera, och funktionen är i praktiken till för dig. Vill du ha en annan formulering, eller dölja funktionen, är det ett beslut för dig.

Storleken "cirka 90 MB" står inte längre någonstans i appen, eftersom meningen den stod i är omskriven. Den står i README.

**Tester på `release/1.0.0`:** JVM 500 av 500, Python 247 av 247. De instrumenterade testerna kompilerar. Ingenting är byggt.

## 5. Del P – omskriven historik och nytt repo

### Förberedelsen

| Steg | Utfall |
|---|---|
| Säkerhetskopia | `~/dmz-backup-historik/dmz-reader-före-omskrivning.bundle`, 129 178 268 byte, alla 47 refs. `git bundle verify`: "The bundle records a complete history." SHA-256 börjar på `cb8b1e4a`. |
| Skärmbilderna | 172 filer, 109 466 857 byte, flyttade till `~/dmz-skarmbilder-arkiv/`. Kopian kontrollerad fil för fil med SHA-256 innan något togs bort. |
| Granskningsfilerna | `normalizer_review_v1.json` och `_v2.json`, flyttade till `~/dmz-granskningsfiler-arkiv/` och kontrollerade byte för byte. |
| `.gitignore` | `docs/screenshots/` och `data/normalizer_review_v*.json` |
| `git-filter-repo` | Version 2.47.0, i en egen virtuell miljö under `~/.local/share/dmz-filter-repo/`. `pip install --user` vägras av systemets Python (PEP 668). |

Granskningsfilerna fick en egen arkivkatalog i stället för att läggas bland skärmbilderna.

### Omskrivningen

Gjord i en separat klon, `~/dmz-reader-omskrivning`, aldrig i arbetskatalogen. Två körningar av `git filter-repo`:

1. `--mailmap`, `--replace-text` för adressen, och `--invert-paths` för `docs/screenshots` och de två granskningsfilerna.
2. En `--replace-text` till, för adressens namndel.

**Två saker utöver det du skrev, som jag bedömde hörde till avsikten:**

- **Adressen i filinnehåll.** Mailmap byter bara författare, committer och taggare. Adressen stod också i klartext i `gates.md` och `publiceringsbeslut.md`. Utan en ersättning i filinnehållet hade den följt med in i det publika repot. Jag bytte den mot noreply-adressen, i hela historiken.
- **Namndelen.** Verifieringen efter första körningen hittade adressens namndel på två kommandorader i `publiceringsbeslut.md`. Den är bytt mot texten "den-gamla-adressen".

Följden är att de två rapporterna har ändrat innehåll i historiken, och att proceduren i `publiceringsbeslut.md` inte längre går att läsa bokstavligt på de raderna. Den är redan körd.

**Taggen `search-index-2026-10-06-006-p2` följde inte med.** Den hörde till prereleasen i det gamla repot, och i det nya finns p3. De 19 versions- och fastaggarna följde med.

### Verifieringen

| Kontroll | Utfall |
|---|---|
| Författaradress och committeradress, alla commits | 340 fält, alla `117741407+Rognils@users.noreply.github.com` |
| Taggare, alla 19 annoterade taggar | samma adress |
| Den gamla adressen eller dess namndel i objektfält, commit-meddelanden, taggobjekt och filinnehåll | 0, 0, 0 och 0 |
| `Co-Authored-By`-raderna | 170, orörda |
| Sökvägar under `docs/screenshots` i någon commit | 0 |
| Granskningsfilerna i någon commit | 0 |
| Objekt kvar med de sökvägarna | 0 |
| Taggar | 19 av 19, på commits med samma meddelande och datum som förut |
| Träd | För `main`, `release/1.0.0` och alla 19 taggar jämfördes varje fils innehåll före och efter, utan de borttagna sökvägarna. 18 taggar är identiska. Tre träd skiljer i precis de filer där adressen byttes, se nedan. |
| `prepush_check.sh`, alla grenar och taggar | ren |
| gitleaks, alla grenar och taggar | inga läckor |
| `git fsck` | inga fel |

**De tre träden som skiljer:**

| Ref | Filer med ändrat innehåll |
|---|---|
| `main` | `docs/reports/gates.md`, `docs/reports/publiceringsbeslut.md` |
| `release/1.0.0` | samma två |
| `v1.0.0-rc3` | `docs/reports/gates.md` |

Jag jämförde de två filerna rad för rad med den gamla versionen efter samma ersättning: 0 rader skiljer därutöver. Skillnaden är alltså adressen och ingenting annat.

**Antalet commits:**

| | Före | Efter |
|---|---|---|
| Commits som nås från `main`, `release/1.0.0` och taggarna | 170 | 170 |
| Varav sammanfogningar | 25 | 25 |

Inga commits försvann. Ingen commit rörde enbart skärmbilder eller granskningsfiler: varje sådan commit ändrade också en rapport eller kod, och blev därför inte tom.

**Storleken på `.git`:**

| | Byte |
|---|---|
| Arbetskatalogen före | 133 021 995 |
| Klonen före omskrivningen | 129 424 509 |
| Efter omskrivningen | 22 219 489 |

### Det nya repot

| Steg | Utfall |
|---|---|
| Det gamla repot | Heter nu `Rognils/dmz-reader-private-history`. Privat. Det har kvar den gamla historiken, prereleasen p2 och den gamla adressen, och jag har inte rört det efter namnbytet. |
| Det nya repot | `Rognils/dmz-reader`, skapat tomt och privat |
| Push | `main`, `release/1.0.0` och 19 taggar, från den omskrivna klonen |
| `git ls-remote` mot klonen | 22 rader, identiska |
| Synlighet | `PRIVATE` |
| GitHubs bild av senaste commit | författare och committer med noreply-adressen, knuten till kontot Rognils |

`main` är `9448bd1` vid pushen.

**Prereleasen:** `search-index-2026-10-06-006-p3` med `search-2026-10-06-006-p3.db`. Release-texten har SHA-256 och storlek, arkivversion, `normalizerVersion` 2 och `privacyFilterVersion` 3, antal nummer och sidor, README:s formulering om att en del uppgifter kan missas, alla kategorier, och raden om rättigheterna. Nedladdad igen: SHA-256 stämmer.

### Arbetskatalogen

Bytt till den nya historiken så här:

1. Kontrollerade att arbetskatalogen var ren, på `main`, utan extra arbetskataloger och utan undanlagda ändringar.
2. Sparade listan över ignorerade filer, och storlek och antal filer för `dist/`, `cache/`, `samples/`, `.venv/`, `local.properties` och byggkatalogerna.
3. Flyttade den gamla `.git` till `~/dmz-backup-historik/DMZ_Reader.git-fore-omskrivning` (133 MB, med alla 25 grenarna).
4. Kopierade in den omskrivna klonens `.git`.
5. `git status` visade då två ändrade filer: `gates.md` och `publiceringsbeslut.md`, med just adresskillnaden. De återställdes till den nya historikens version.

| Kontroll efteråt | Utfall |
|---|---|
| `git status` | rent, `main` i takt med `origin/main` |
| `origin` | `https://github.com/Rognils/dmz-reader.git`, det nya repot |
| Ignorerade filer | samma 13 poster som före |
| `dist/` | 9 filer, 735 423 732 byte, som före |
| `cache/` | 951 filer, 16 828 527 942 byte, som före |
| `samples/`, `.venv/`, `local.properties`, byggkatalogerna | samma storlek och antal som före |
| Stickproven och arkivkatalogerna utanför repot | orörda |
| Python- och JVM-testerna i arbetskatalogen | 247 av 247 och 500 av 500 |

Lokala grenar i arbetskatalogen är nu `main` och `release/1.0.0`. De äldre fasgrenarna finns i säkerhetskopian och i det gamla repot.

**`user.email`** i arbetskatalogen är `117741407+Rognils@users.noreply.github.com`. Den här rapportens commit är gjord med den.

Två saker jag inte har ändrat, och som du bör göra själv:

- **Den globala inställningen** (`git config --global user.email`) är fortfarande den gamla adressen. Den gäller för andra repon på datorn. Ett nytt repo, eller en ny klon av det här, får den gamla adressen om den inte ändras.
- **På GitHub,** under Settings → Emails: "Keep my email addresses private" och "Block command line pushes that expose my email". Den andra gör att en push med den gamla adressen vägras.

### Hänvisningarna till skärmbilderna

`docs/skarmbilder.md` förklarar att bilderna och granskningsfilerna ligger lokalt och varför. De 18 rapporter och ADR:er som hänvisar till dem har fått en upplysning överst med en länk dit. `tools/README.md` säger detsamma på de två rader som nämner filerna.

Jag skrev inte om de enskilda hänvisningarna i texten. De pekar på samma katalognamn som bilderna har i arkivet.

## 6. Vad som finns var

| Plats | Innehåll |
|---|---|
| `Rognils/dmz-reader` (privat) | den omskrivna historien, `main`, `release/1.0.0`, 19 taggar, prerelease p3 |
| `Rognils/dmz-reader-private-history` (privat) | den gamla historien som den var, med prerelease p2 |
| `~/claude/Projects/DMZ_Reader` | arbetskatalogen, på den nya historien |
| `~/dmz-backup-historik/` | bundle med alla gamla refs, och den gamla `.git`-katalogen |
| `~/dmz-reader-omskrivning` | klonen som omskrivningen gjordes i. Kan tas bort. |
| `~/dmz-skarmbilder-arkiv/` | 172 skärmbilder |
| `~/dmz-granskningsfiler-arkiv/` | de två granskningsfilerna |
| `~/dmz-privacy-stickprov/` | stickproven för filtret, med personuppgifter. Aldrig i git. |

## 7. Kvar till publiceringsdagen

Checklistan i `docs/reports/publiceringsbeslut.md` gäller, med steg 3–6 och 12 redan gjorda och steg 7 gjort till hälften (adressen är satt i arbetskatalogen, men inte globalt och inte på GitHub). Det som återstår:

1. **Importera p3 i din app** från Download, och använd det under veckan.
2. **Veckans användning** enligt checklistan i `rc1.md`.
3. **Dina kvarvarande beslut:** omslagen i appen, sökindexet som publik fil, och upphovsrätten. Skärmbilderna, granskningsfilerna, adressen och testerna mot arkivet är avgjorda och genomförda.
4. **Sätt den globala `user.email` och e-postskyddet på GitHub,** se ovan.
5. **Bestäm om texten om arkivindexet** i Inställningar ska stå kvar.
6. **Publiceringsdagen:** kontroller före push, merga `release/1.0.0`, gör repot publikt, bygg 1.0.0, tagga, lägg APK:n och sökindexet som releaser, och kontrollera länkarna.

En sak att ha i minnet på publiceringsdagen: prereleasen p3 ligger på en tagg i det privata repot. När repot görs publikt blir den synlig som den är. Den bör då antingen göras om till en riktig release eller tas bort och skapas på nytt, enligt steg 24 i checklistan.
