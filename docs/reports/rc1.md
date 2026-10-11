# Fasrapport – FAS RC: härdning inför 1.0 (1.0.0-rc1)

> **Skärmbilderna som nämns här finns inte i repot.** De ligger lokalt hos projektets ägare, se [docs/skarmbilder.md](../skarmbilder.md).

Datum: 2026-10-10 · Gren: `release/1.0-rc`, mergad till `main` och taggad `v1.0.0-rc1` · App 1.0.0-rc1 (versionCode 9901)

**Läge: alla tio steg är klara.** Din data är flyttad till release-bygget (avsnitt 9), och en checklista inför v1.0.0 finns i avsnitt 12.

## 1. Sammanfattning

- **Minnet:** det var ingen regression. FAS 3-bygget beter sig likadant som det nuvarande på samma sidor. Men problemet är verkligt och har funnits sedan FAS 3: PDF-motorn sparade avkodade bilder för upp till fyra sidor, och i 1995 nr 15 gav det 850–940 MB. Det är rättat: efter öppning på sida 15 ligger appen på 296 MB PSS och 71 MB native heap, mot 857 och 640.
- **HQ:** en hämtning flyttas in i cachen först när storleken stämmer med serverns SIZE, och en trunkerad fil i cachen upptäcks och hämtas om.
- **Sökindexet** är byggt om för arkivversion 006. Facit 43 av 43.
- **Tre appar i stället för en:** utveckling och tester körs i `se.c64.dmzreader.debug`, provet i `se.c64.dmzreader.rc`, och din app `se.c64.dmzreader` har inte berörts.
- **Release-nyckeln** är skapad, och release och rc signeras med den.
- **RC-varianten** (krympt med R8, release-nyckeln) är provad på telefonen bredvid din app, även på mobildata. Inga krascher.
- **Flytten** är övad med testdata från debug-appen till rc-appen, och därefter gjord med din data. Allt kom över.

## 2. Din data

Appdatan kopierades till `~/dmz-backup-före-rc/` först (IssueUserState 2, Bookmark 0, ReadingSession 2, IssueLocalState 2; läs-cachen 1988 nr 1 och 1991 nr 15).

**Din installerade app har inte rörts.** Ingenting har installerats över den, och den har inte startats. Det enda som har gjorts med den är det fingeravtrycket kräver: `am force-stop` (den var inte igång) och läsning av filerna med `run-as`.

Åtta fingeravtryck togs under fasen: före den första svitkörningen, före och efter minnesmätningarna, före och efter provet av rc, efter flyttövningen, och före och efter den sista svitkörningen. Alla åtta är identiska med varandra och med fingeravtrycket från slutet av FAS 11.

Ett undantag i förloppet: den första svitkörningen (63 av 63 godkända) avbröts därefter av ett fel i skriptet, eftersom jag ändrade filen medan den kördes. Dess fingeravtryck efter togs därför inte. Nästa fingeravtryck, taget direkt därefter, var identiskt med det före.

Det här gäller fram till flytten i steg 9. Efter den är din app release-bygget, och på telefonen finns därutöver bara `DMZ Reader DEV` med sitt testpaket.

## 3. Steg 1 – minnet

### Samma metod på v0.3-fas3 och på main

FAS 3-taggen byggdes med ett eget app-ID (`se.c64.dmzreader.fas3`) och kördes med sitt eget test, det som gav siffrorna i FAS 3-rapporten: `verifyLoad199515` (sidorna 1–11, högsta zoom, panorering) och `verifyZoom199514`. Samma test kördes på main. `dumpsys meminfo` togs under hållet vid högsta zoom.

| PSS i kB, högsta zoom | 1995 nr 15, FAS 3 | 1995 nr 15, main | 1995 nr 14, FAS 3 | 1995 nr 14, main |
|---|---|---|---|---|
| Native Heap | 119 825 | 128 227 | 95 251 | 97 387 |
| Dalvik Heap | 8 009 | 8 944 | 7 800 | 8 404 |
| .so mmap | 14 346 | 15 193 | 7 171 | 7 580 |
| .apk, .dex, .oat, .art mmap | 86 762 | 89 033 | 87 600 | 93 452 |
| Other mmap | 172 | 387 | 176 | 223 |
| Gfx dev + EGL + GL (grafik) | 136 060 | 137 776 | 133 216 | 133 380 |
| **TOTAL PSS** | **384 871** | **404 311** | **349 948** | **360 577** |

FAS 3-rapportens siffror (381 MB och 115 MB för nr 15) går alltså att återskapa, och main ligger 5 % över. Det är ingen regression i den mätningen.

Filmappat minne är litet och lika i alla mätningar: `.so mmap` 5–15 MB och `Other mmap` under 1 MB. PDF-filen mappas inte in. De stora talen nedan är riktig heap.

### Var 903 MB kom ifrån

FAS 11-mätningen öppnade numret direkt på sida 15 ("Fortsätt på sida 15"). Det förloppet återskapades:

| Öppning direkt på sida 15, main före rättningen | PSS | Native heap |
|---|---|---|
| 1 s efter öppningen | 980 MB | 761 MB |
| 17 s efter öppningen, inget rört | 857 MB | 639 MB |

Då hade bara sidorna 14, 15 och 16 ritats. **PDF-motorn (PdfRenderer) sparar avkodade bilder för varje ritad sida så länge filen är öppen.** Sedan FAS 3 öppnas filen om efter fyra olika sidor, vilket släpper det sparade. I 1995 nr 15 är sidorna skannade i 600 dpi (4 890 × 7 000 pixlar), och en sida kostar 150–630 MB. Regeln om fyra sidor räcker inte där.

### Samma sak i FAS 3-bygget

Ett test som bläddrar sidorna 2–22 och mäter efter varje sida kördes på båda byggena:

| Sida | FAS 3: PSS / native | main: PSS / native |
|---|---|---|
| 2 | 847 / 628 MB | 856 / 628 MB |
| 3 | 938 / 752 MB | 940 / 752 MB |
| 4 | 861 / 629 MB | 869 / 629 MB |
| 9 | 399 / 328 MB | 395 / 328 MB |
| 15 | 442 / 250 MB | 387 / 250 MB |

Byggena följer varandra sida för sida. **Svagheten har funnits sedan FAS 3.** Mätningen då gjordes efter sida 11, där minnet råkade vara lågt, och FAS 3-rapportens "topp 265 MB" var för låg. Det jag skrev i FAS 11-rapporten, att PDF:en "ligger i minnet", var fel: det är avkodade sidbilder, inte filen.

### Rättningen

Filen öppnas nu också om när native heap har vuxit mer än 192 MB sedan den öppnades och det sparade hör till andra sidor än dem som ska ritas, och när ingenting har ritats på tre sekunder. Ett uppslags två sidor får ligga kvar tillsammans.

| | Före | Efter |
|---|---|---|
| Öppning på sida 15, efter 17 s: PSS / native heap | 857 / 639 MB | **296 / 76 MB** |
| Bläddring sidorna 2–22, högsta PSS | 940 MB (sida 3) | **398 MB** (sida 5) |
| Bläddring sidorna 2–22, högsta native heap | 752 MB | **198 MB** |
| 1995 nr 15, FAS 3-metoden: PSS / native heap | 404 / 128 MB | 396 / 128 MB |
| 1995 nr 14, FAS 3-metoden: PSS / native heap | 361 / 97 MB | 362 / 98 MB |

Renderingstiderna är oförändrade (1995 nr 15: helsida 1,8 s i median, utsnitt 0,58 s; i FAS 3 var det 1,9 och 0,59).

**Det som återstår:** medan en tung sida ritas behövs minnet ändå. Toppen 1 sekund efter öppning på sida 15 är oförändrad, 924 MB PSS under ett par sekunder. Det går inte att undvika med PdfRenderer. Efter tre sekunders stillhet är det släppt.

Mätfilerna ligger i `docs/reports/rc1/minne/`.

## 4. Steg 2–6

**Steg 2 – HQ och avbrutna hämtningar.** Hämtningen skrevs redan till `.part`, men två luckor fanns och är rättade:

- Uppgav servern ingen storlek gjordes ingen kontroll. Nu krävs SIZE: utan den vägras hämtningen. Arkivets server svarar på SIZE (prövat i rc-provet).
- En fil i cachen litades på om den fanns. Nu sparas den verifierade storleken bredvid filen, och en fil som inte har exakt den storleken tas bort och hämtas om.

En avbruten hämtning (nät av, sidbyte, processdöd) lämnar bara `.part`, som aldrig lämnas ut som en sida. Nya JVM-tester mot MockFtpServer: servern bryter mitt i med felkod, servern bryter mitt i men svarar "klart", server utan SIZE, och hela kedjan genom cachen. Dessutom: en trunkerad fil i cachen upptäcks och hämtas om, och en hämtning av fel storlek flyttas aldrig in.

**Steg 3 – nytt search.db.** Byggt för arkivversion 2026-10-06-006 med normalizerVersion 2 och ordlista 1. Facit: 43 av 43 sökord hittar alla förväntade sidor, inga falska positiva bland facits kontrollerade träffar. Sidornas text är identisk med det förra indexet (samma kontrollsumma över alla 11 293 sidor); bara `meta` skiljer.

- `dist/search.db`: 148 877 312 byte, SHA-256 `9da0dbd6…65a6ec`
- det förra ligger kvar som `dist/search-2026-10-06-005.db`

**Steg 4 – app-ID.** Se tabellen i `docs/signing.md`. `device_test.sh` kör testerna i debug och tar fingeravtrycket av din app.

**Steg 5 – signeringsnyckeln.**

- Nyckel: RSA 4096, SHA256withRSA, giltig 2026-10-10 till 2061-10-10, i `~/dmz-keys/dmz-reader-release.jks` (utanför repot, bara läsbar för dig).
- Lösenorden: i `~/.gradle/gradle.properties` (utanför repot, bara läsbar för dig). Jag har skapat dem slumpmässigt och inte skrivit ut dem någonstans.
- Kontrollerat med git: inga nyckelfiler är spårade, lösenordet finns inte i någon fil i projektkatalogen, och `.gitignore` vägrar `*.jks`, `*.keystore`, `*.p12`, `keystore.properties` och `signing.properties`.
- `docs/signing.md` beskriver var nyckeln ligger, hur release byggs och vad som händer om nyckeln tappas bort.

Certifikatets SHA-256-fingeravtryck:

```text
5A:36:79:EC:C4:CC:BF:9B:61:24:7E:35:80:B1:78:02:8F:03:E6:E4:7E:6D:6D:7F:32:5D:2A:29:54:75:F7:27
```

> **Påminnelse: du behöver själv kopiera nyckeln och lösenordet till en plats utanför datorn.** Två saker: filen `~/dmz-keys/dmz-reader-release.jks` och raderna som börjar med `dmzRelease` i `~/.gradle/gradle.properties`. Försvinner någon av dem går appen inte att uppdatera utan avinstallation och en ny flytt.

**Steg 6 – R8.** Uttryckliga keep-regler för Room 3, sqlite-bundled, kotlinx.serialization och Apache Commons Net. Release och rc bygger (24,4 MB vardera) och är signerade med release-nyckeln, kontrollerat med `apksigner`.

## 5. Steg 7 – prov av RC-varianten på telefonen

Installerad bredvid din app som `se.c64.dmzreader.rc`. Styrd med adb genom gränssnittet, som en användare.

| Prov | Resultat |
|---|---|
| Start, bibliotek med omslag | Biblioteket visas med 185 nummer. Arkivindexet lästes in (Room och serialisering fungerar krympta). |
| Läs: ladda ned, bläddra, zooma | 1986 nr 1 hämtades och öppnades, sidbyte och zoom fungerar. |
| Uppslag | Liggande läge visar sidorna 4–5. I HQ-läge visas en sida. |
| Fortsätt läsa efter tvångsstopp | "Fortsätt läsa: 1986 nr 1, sida 4 av 28". |
| Importera search.db | "185 nummer och 11293 sidor, på 1,9 sekunder". Arkivversion 006, ingen upplysning om annan version. |
| Sök "Amiga" | 7 289 sidor i 185 nummer |
| Sök "\"Commodore 128\"" | 168 sidor i 73 nummer |
| Sök "högtalare" | 230 sidor i 110 nummer |
| Öppna träff, träffnavigering | Träffen öppnade sida 4, "Nästa träff" gick till sida 12. |
| HQ på två sidor | Sida 3 (5,1 MB på 0,64 s) och sida 4 (4,7 MB på 0,42 s), samma anslutning. |
| Bokmärke och favorit | Bokmärke på sida 3 och favoritmarkering syns under Favoriter. |
| Statistik | 1 påbörjat, 6 lästa sidor, 1 min. |
| Backup-export | "1 nummer med data, 1 bokmärken och 2 lässessioner". Filen hämtades och har rätt fältnamn och innehåll. |
| Mobildata, Läs | Wifi av (LTE): 1986 nr 2, 18 MB, hämtat och öppnat på cirka 7 s. |
| Mobildata, HQ | 1986 nr 2 sida 1 (4,3 MB på 1,5 s) och 1986 nr 1 sida 13 (2,2 MB på 1,0 s). Wifi påslaget igen efteråt. |
| Logcat | Inga krascher i appen, inga ClassNotFound, NoSuchMethod, UnsatisfiedLink eller serialiseringsfel. |

De tre söksvaren är samma antal som med det förra indexet.

En krasch finns i loggen under provet, men den hör till testverktyget: `uiautomator dump` kördes två gånger samtidigt av mina egna skript.

**Minnet i rc för 1995 nr 15** (`dumpsys meminfo`, bläddring från sida 1):

| Läge | PSS | Native heap | Grafik |
|---|---|---|---|
| Sida 3 | 439 MB | 250 MB | 149 MB |
| Sida 4 | 295 MB | 106 MB | 148 MB |
| Sida 15, direkt | 326 MB | 128 MB | 157 MB |
| Sida 15, efter 8 s | 314 MB | 117 MB | 157 MB |
| Sida 15, zoomad och panorerad | 359 MB | 136 MB | 182 MB |

Zoomen här är dubbeltryck (2,5 gånger). Nyp till högsta zoom går inte att göra med adb, så den delen är mätt i debug-bygget.

**Att känna till:** nedladdningen av 1995 nr 15 (563 MB) med Läs stannade efter 36 sekunder med "Det gick inte att nå arkivet", eftersom servern bryter överföringar vid cirka 200 MB. "Försök igen" fortsatte där den slutade och blev klar. Det är samma beteende som tidigare och gäller bara de största filerna.

## 6. Steg 8 – övningen av flytten

Från debug-appen (debug-nyckeln) till rc-appen (release-nyckeln, nyinstallerad och tom). Stegen, resultaten och tiderna står i `docs/migration.md`.

- Testdata: två favoriter, läsdata i två nummer, två bokmärken varav ett med titel och anteckning, två sessioner.
- Efter importen fanns allt: Fortsätt läsa på rätt nummer och sida, båda favoriterna, båda bokmärkena med titel och anteckning, 12 lästa sidor och lästiden.
- Båda bokmärkena öppnar rätt nummer på rätt sida.
- Sökindexet importerades på 1,7 sekunder.
- Tid: 2 minuter från export till bekräftad import, knappt 4 minuter för allt.

**En lärdom som påverkar den riktiga flytten:** filväljaren minns var den senast användes. Vid exporten öppnades den i telefonens rotkatalog, inte i Download, och filen hamnade där. Appen bekräftade exporten ändå. I steg 9 väljer jag katalogen uttryckligen och kontrollerar att filen ligger där innan något avinstalleras.

## 7. Tester

| Test | Resultat |
|---|---|
| JVM | 467 av 467 (9 nya) |
| Python | 206 av 206 |
| Lint | 0 fel, 13 varningar (oförändrat) |
| Instrumenterad svit i debug-appen, isolerad lagring | 66 av 66 |
| Omstartstestet och tvångsstoppet | godkända |

Av de 66 är tre nya minnestester som bara körs på begäran, och som då kräver 1995 nr 15 på telefonen. Ett av dem underkänner nu en native heap över 300 MB efter öppning på sida 15.

Ett Python-test som jag ändrade i början av fasen föll på ett variabelnamn i testet självt. Det upptäcktes vid nästa hela körning och rättades.

## 8. Kända begränsningar

- **Toppen under renderingen av de tyngsta sidorna är kvar:** cirka 920 MB PSS under ett par sekunder på sida 15 i 1995 nr 15.
- **Efter tre sekunders stillhet på en tung sida kostar nästa zoom en ny avkodning,** cirka två sekunder innan utsnittet blir skarpt.
- **Filer över cirka 200 MB kräver "Försök igen"** vid Läs, eftersom servern bryter. Nedladdningskön fortsätter själv.
- **Efter flytten kan fingeravtryck av din data inte längre tas,** eftersom release-bygget inte går att läsa med `run-as`. Skyddet är då att testerna körs i en annan app.
- 183 nummer är inte jämförda sida för sida i HQ-mappningen (oförändrat från FAS 11).
- HQ-läget sparas inte mellan öppningar, och en HQ-sida syns först 1–2 sekunder efter sidbytet.
- Android 8–13 är oprövat.
- Högsta zoom (nyp) är inte provad i rc-varianten, bara i debug.

## 9. Steg 9 – flytten av din data

Gjord 2026-10-10 efter ditt besked. Din app är nu release 1.0.0-rc1, signerad med release-nyckeln.

### Före avinstallationen

Du hade använt appen sedan FAS 11, så läget var ett annat än vid fasens början: en tredje session, och 1991 nr 15 på sida 19.

**Inställningar som inte ingår i säkerhetskopian** (lästa ur appens DataStore och i gränssnittet):

| Inställning | Värde före | Efter flytten |
|---|---|---|
| Ladda ned endast via wifi | på (standardvärdet, aldrig ändrat) | på |
| Vald publikation i biblioteket | Datormagazin | Datormagazin (standardvärdet) |
| Frågan om notiser vid första nedladdningen | redan visad | inte visad: appen frågar igen vid första nedladdningen |
| Aktivt arkivindex | det inbyggda, 2026-10-06-006 | det inbyggda, 2026-10-06-006 |
| Senaste export | ingen | 2026-10-10, från min kontrollexport |

Appen har inget sparat sorteringsval. Sorteringen i sökningen väljs för stunden.

**Exporten** gjordes genom appens gränssnitt, med mappen Download vald uttryckligen. Appen svarade "2 nummer med data, 0 bokmärken och 3 lässessioner". Filen hämtades med `adb pull` och jämfördes fält för fält med databasen, läst med `run-as`:

| | Säkerhetskopian | Databasen |
|---|---|---|
| backupFormat | 2 | – |
| Favoriter | 0 | 0 |
| Bokmärken | 0 | 0 |
| Sessioner | 3 | 3 |
| Lästid | 124 s | 124 s |
| Nummer med läsdata | 2 | 2 |
| Lästa sidor | 9 | 9 |

Läsposition, lästa sidor och varje session stämde också post för post. En ny direktkopia av appdatan togs med `run-as tar` (201 MB). Först därefter avinstallerades appen.

### Efter importen

Appen svarade "2 nya nummer, 0 uppdaterade, 0 nya bokmärken, 0 uppdaterade och 3 nya sessioner".

| Kontroll i release-appen | Före | Efter |
|---|---|---|
| Fortsätt läsa | 1991 nr 15, sida 19 av 80, "7 av 80 lästa" | samma |
| Statistik: påbörjade nummer | 2 | 2 |
| Statistik: lästa sidor | 9 | 9 |
| Statistik: lästid | 2 min | 2 min |
| Favoriter och bokmärken | 0 och 0 | 0 och 0 |
| Fortsätt läsa öppnar | – | 1991 nr 15 på sida 19 |
| Sökindex | arkivversion 005 | arkivversion 006, 185 nummer och 11 293 sidor, ingen versionsnotis |

**Min kontroll lämnade ett spår i din data.** När jag öppnade Fortsätt läsa för att se att rätt sida visades räknades sida 19 som läst, och en session på 12 sekunder sparades. Det syns i en kontrollexport jag gjorde efteråt:

| | Före flytten | Efter flytten och min kontroll |
|---|---|---|
| Lästa sidor | 9 | 10 (sida 19 i 1991 nr 15 tillkom) |
| Sessioner | 3 | 4 (en på 12 s tillkom) |
| Lästid | 124 s | 136 s |

Alla tre ursprungliga sessioner finns kvar oförändrade, och 1988 nr 1 är orört. Inget annat skiljer. Det går inte att ta bort spåret utifrån, eftersom release-bygget inte kan läsas med `run-as`.

### Behörigheter och nätverk

- Nätverket fungerar: appen hämtade 1991 nr 15 (25 MB) direkt efter flytten. Ingen nätverksspärr finns för appen.
- Notisbehörigheten var inte beviljad efter installationen. Jag beviljade den med adb. Vad den var satt till i den gamla appen kunde jag inte se i efterhand, så om du hade nekat den får du stänga av den igen.
- Du behöver inte göra något för hand.

### Städning

- Båda säkerhetskopiorna och `search.db` är borttagna ur telefonens Download.
- `DMZ Reader RC` och det gamla testpaketet `se.c64.dmzreader.test` är avinstallerade.
- `DMZ Reader DEV` (med sitt testpaket) ligger kvar.
- På datorn, i `~/dmz-backup-före-rc/flytt/`: säkerhetskopian, kontrollexporten efter flytten, direktkopian av appdatan med kontrollsumma, inställningsfilen och jämförelserna.

### Tider

| | |
|---|---|
| Från att appen startades för export till bekräftad import | 2 min 33 s |
| Därav från avinstallation till bekräftad import | 56 s |
| Till att sökindexet var importerat | 3 min 13 s |
| Allt, med kontroller, kontrollexport och städning | 5 min 28 s |

### Det som inte följde med

- Läs-cachen: 1988 nr 1 hämtas igen när du öppnar det. 1991 nr 15 är redan hämtat igen, av min kontroll.
- Fingeravtryck av din data kan inte längre tas, eftersom release-bygget inte går att läsa med `run-as`. Testerna körs i `DMZ Reader DEV`, som är en annan app.

Skärmbilder före och efter finns i `docs/screenshots/rc1/flytt/`.

## 10. Skapade och ändrade filer

**Nya:** `docs/signing.md`, `docs/migration.md`, `docs/decisions/0023-app-id-signering-och-releasekandidat.md`, `docs/reports/rc1.md`, `docs/reports/rc1/` (mätfiler och fingeravtryck), `docs/screenshots/rc1/` (21 bilder), `app/src/debug/res/values/strings.xml`, `app/src/rc/res/values/strings.xml`.

**Ändrade:** `app/build.gradle.kts` (app-ID, rc-varianten, signering, version), `app/proguard-rules.pro`, `data/pdf/PdfDocument.kt` (minnet), `data/hq/ScanSource.kt`, `FtpScanSource.kt`, `HqCache.kt`, `HqRepository.kt` (storlekskontroll), `data/inventory/InventoryParser.kt` och `docs/inventory.schema.json` (vridning 0), `tools/device_test.sh`, `tools/README.md`, `.gitignore`, planens bilaga E, och testerna `HqCacheTest`, `HqRepositoryTest`, `FtpScanSourceTest`, `HqMappingTest`, `InventoryHqTest`, `AppVersionTest`, `ReaderDeviceTest`, `HqDeviceTest`, `test_hq_mapping.py`.

**Utanför repot:** `~/dmz-keys/dmz-reader-release.jks`, fyra rader i `~/.gradle/gradle.properties`, `dist/search.db` och `dist/search-2026-10-06-005.db`, `~/dmz-backup-före-rc/`.

## 11. ADR

- **0023** (ny): separata app-ID, egen signeringsnyckel, R8-regler, versionsnummer för kandidater och PDF-minnets gräns.

## 12. Checklista inför v1.0.0

Använd appen som vanligt i en vecka. Bocka av det här under tiden, och anteckna allt som känns fel eller långsamt.

**Läsa**
- [ ] Läs ett helt nummer från början till slut, med bläddring både med tryck och svep.
- [ ] Zooma och panorera på en sida med liten text, och i ett uppslag i liggande läge.
- [ ] Stäng appen mitt i ett nummer och öppna den nästa dag: Fortsätt läsa visar rätt nummer och sida.
- [ ] Läs ett nummer ur den äldre PDF-generationen (till exempel 1993–1995) och ett ur den nyare (1986–1991).

**Nedladdning**
- [ ] Ladda ned två eller tre nummer med "Ladda ned" och läs ett av dem utan nät (flygplansläge).
- [ ] Ta bort ett nedladdat nummer och se att utrymmet under Inställningar → Lagring minskar.
- [ ] Öppna ett stort nummer (1995 nr 15, 563 MB) med Läs och se att det laddas klart med "Försök igen" när servern bryter.
- [ ] Svara på frågan om notiser vid första nedladdningen, och se att förloppet visas som notis.

**Sökning**
- [ ] Sök på ett ord, en fras inom citattecken och ett ord med stjärna (till exempel amig*).
- [ ] Öppna en träff och bläddra mellan träffarna i numret.
- [ ] Använd "Sök i numret" inifrån läsaren.

**HQ**
- [ ] Slå på HQ på en sida med liten text och jämför med PDF-sidan vid samma zoom.
- [ ] Bläddra några sidor i HQ-läge, och gå sedan tillbaka till en av dem utan nät.
- [ ] Prova HQ på ett liggande omslag (till exempel 1987 nr 4–5, sida 1) och se att det ligger rätt.
- [ ] Prova HQ över mobildata en gång.

**Bokmärken och favoriter**
- [ ] Sätt ett bokmärke med titel och anteckning, och öppna det från Favoriter.
- [ ] Ta bort ett bokmärke och ångra.
- [ ] Markera ett par nummer som favoriter.

**Statistik**
- [ ] Se att lästiden och antalet lästa sidor verkar rimliga efter veckan.
- [ ] Läs en gång över midnatt, om det faller sig så, och se att tiden hamnar på rätt dag.

**Säkerhetskopia**
- [ ] Gör en export i slutet av veckan (välj mappen uttryckligen) och spara filen utanför telefonen.

**Minne och stabilitet**
- [ ] Bläddra snabbt genom ett tjugotal sidor i 1995 nr 15. Appen ska inte stängas av systemet.
- [ ] Notera om appen någon gång kraschar, hänger sig eller startar om, och vad du gjorde då.

**Inför taggen v1.0.0**
- [ ] Nyckeln och lösenordet finns kvar på platsen utanför datorn.
- [ ] Bestäm om `DMZ Reader DEV` ska ligga kvar på telefonen.
- [ ] Bestäm om spåret efter min kontroll (sida 19 och sessionen på 12 sekunder) ska ligga kvar.
