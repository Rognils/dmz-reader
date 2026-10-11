# Rapport – före 1.0.0: förstastartsfelet och publiceringsgranskning

> **Skärmbilderna som nämns här finns inte i repot.** De ligger lokalt hos projektets ägare, se [docs/skarmbilder.md](../skarmbilder.md).

Datum: 2026-10-10 · Gren: `release/1.0-gates` (från `main`, inte mergad) · App oförändrad, 1.0.0-rc2

**Uppdaterad 2026-10-10** med Gustavs svar (del C), det privata GitHub-repot, undantaget för testdatabaserna och den sista grindkörningen. HQ-felet från den sista grindkörningen är utrett (del E): det låg i testet. Efter rättningen gav tio rena sekvenser 10 av 10, grenen är mergad och förstastartsfrågan är stängd. Det som återstår innan v1.0.0 och de beslut du behöver ta står sist i rapporten.

Ingen appkod, ingen datamodell och inga assets är ändrade. Release-appen `se.c64.dmzreader` är inte rörd: den är 1.0.0-rc2 med samma installationstid före och efter varje körning. Inget är pushat, och ingen historik är omskriven.

---

## DEL A – Förstastartsfelet

### Slutsats: (a) felet ligger i testerna

Appen är inte trög vid första starten. Felet var en kapplöpning i testerna: de rullade till ett nummer i årgångsvyn innan numren hade lästs in från databasen. Det är rättat i testinfrastrukturen.

Efter rättningen gick 9 av 10 rena sekvenser igenom. Den som föll gjorde det på ett annat fel, som jag inte har kunnat förklara eller återskapa. Kravet "fem nya sekvenser som alla går igenom" uppfylldes alltså först i den andra omgången om fem, inte i den första. Därför har jag inte mergat grenen.

### Resultat per sekvens

Varje sekvens: avinstallera debug-appen och testpaketet, installera dem, kör `tools/device_test.sh` i sin helhet.

| Sekvens | Start | Tid | Sviten | Omstartstestet | Tvångsstoppet | Första bild | Läsaren öppen |
|---|---|---|---|---|---|---|---|
| 1 | 13:35:34 | 217 s | 69 av 69 | godkänt | godkänt | 2,01 s | 4,37 / 4,25 s |
| 2 | 13:39:16 | 215 s | 69 av 69 | godkänt | godkänt | 1,94 s | 4,29 / 4,27 s |
| 3 | 13:42:56 | 213 s | 69 av 69 | godkänt | godkänt | 1,95 s | 4,25 / 4,22 s |
| 4 | 13:46:33 | 213 s | 69 av 69 | godkänt | godkänt | 1,89 s | 4,37 / 4,29 s |
| **5** | 13:50:11 | 439 s | 69 av 69 | godkänt | **föll** | 1,98 s | 4,39 s |
| | | | | | *rättningen införs här* | | |
| 6 | 13:58:21 | 215 s | 69 av 69 | godkänt | godkänt | 1,96 s | 4,43 / 4,25 s |
| 7 | 14:02:01 | 215 s | 69 av 69 | godkänt | godkänt | 1,96 s | 4,43 / 4,31 s |
| 8 | 14:05:41 | 217 s | 69 av 69 | godkänt | godkänt | 1,98 s | 4,29 / 4,29 s |
| **9** | 14:09:23 | 212 s | **68 av 69** | godkänt | godkänt | 1,96 s | 4,33 / 4,27 s |
| 10 | 14:13:00 | 214 s | 69 av 69 | godkänt | godkänt | 1,97 s | 4,35 / 4,28 s |
| 11 | 14:23:40 | 217 s | 69 av 69 | godkänt | godkänt | 2,02 s | 4,51 / 4,26 s |
| 12 | 14:27:22 | 214 s | 69 av 69 | godkänt | godkänt | 1,98 s | 4,30 / 4,31 s |
| 13 | 14:31:01 | 215 s | 69 av 69 | godkänt | godkänt | 1,93 s | 4,52 / 4,28 s |
| 14 | 14:34:41 | 213 s | 69 av 69 | godkänt | godkänt | 2,02 s | 4,43 / 4,33 s |
| 15 | 14:38:19 | 218 s | 69 av 69 | godkänt | godkänt | 1,99 s | 4,34 / 4,30 s |

- **Första bild:** från att processen startas för första gången efter installationen till att systemet rapporterar första bildrutan.
- **Läsaren öppen:** från processtart till att PDF:en är öppnad, i de två deltester som startar en ny process med tom lagring (omstartstestet och tvångsstoppstestet). Tiden innehåller att databasen skapas, att det inbyggda arkivindexet läses in och att testet navigerar bibliotek → årgång → nummer → Läs.
- Det yttre fingeravtrycket av release-appen var identiskt före och efter i alla 15 sekvenser.

Utskrift, tidsstämplar, filtrerad logg och fingeravtryck per sekvens ligger i `docs/reports/gates/forstastart/sekvens-N/`.

### Mätningen: ingen tröghet vid första starten

Tiderna är desamma i alla 15 sekvenser, även i de två som föll:

| | Ren installation, första starten | Redan installerad |
|---|---|---|
| Processtart till första bild, i testkörningen | 1,89–2,02 s | 1,67–1,78 s (senare processer i samma sekvens) |
| Processtart till öppnad läsare, tom lagring | 4,22–4,52 s | – |
| Processtart till öppnad läsare, lagringen finns | – | 3,30–3,42 s |
| `am start -W`, vanlig start utan test (TotalTime) | 1,33 s | 1,03–1,14 s |

- Testernas tidsgränser är 10–20 sekunder. Läsaren öppnas på drygt 4 sekunder. Marginalen är stor, och ingen tidsgräns löpte ut på grund av tröghet.
- Första starten efter en ren installation är 0,2–0,3 sekunder långsammare än senare starter.
- Loggen visar ingen dexopt under körningarna. `artd` rapporterar bara att den förkompilerade koden är aktuell, före och efter.
- Inläsningen av arkivindexet, skapandet av databasen och DataStore syns inte som egna steg i loggen. De ryms i de 4 sekunderna ovan.

**Två saker jag inte mätte som du bad om:**

- **"Biblioteket ritat" i en vanlig start** har jag bara en övre gräns för. Mitt enda sätt att se innehållet utifrån är `uiautomator dump`, som tar en dryg sekund. Biblioteket fanns i UI-trädet vid första avläsningen, 3,8–4,2 sekunder efter start, varav huvuddelen är mätningens egen tid. Den precisa siffran är första bild ovan.
- **Öppnad läsare i en vanlig start** mätte jag inte. Det kräver ett nedladdat nummer i appens vanliga lagring och styrning med adb, som lägger till mer tid än det mäter. Siffrorna ovan kommer från testkörningen, som är det sammanhang där felet uppstod.

Mätningen av vanlig start finns i `forstastart/vanlig-start.txt`.

### Vad som gick fel i sekvens 5

Tvångsstoppstestets första del föll 3,5 sekunder efter processtart, med:

```text
AssertionError: No node found that matches ContentDescription = 'Datormagazin 1986 Nr 1' in scrollable container
… VerticalScrollAxisRange = 'ScrollAxisRange(value=0.0, maxValue=0.0 …)' … Has 3 children
```

Testet hade öppnat årgång 1986 och försökte rulla till nummer 1. Rutnätet fanns, men hade bara tre barn och ingenting att rulla: numren hade inte kommit från databasen än. Testet väntade på att rutnätet skulle finnas, inte på att det skulle ha innehåll.

- Normalt hinner numren fram. I en ny process med tom lagring är fönstret någon tiondels sekund, och ibland hinner testet före.
- Samma mönster fanns på nio ställen i åtta testklasser, så felet kunde dyka upp i olika tester.
- Skriptet väntade sedan i fyra minuter på en läsning som aldrig skulle komma, och skrev "Läsaren öppnades aldrig".

**Det förklarar de tidigare felen.** I rc2-arbetet föll tvångsstoppstestet två gånger på samma sätt, och omstartstestets andra del en gång (den förutsätter att den första delen har läst, och den hade då fallit på samma kapplöpning). Utskriften från de körningarna sparades inte, så det är en slutledning, inte ett belägg.

**Felet hade inget med installationen att göra.** Jag trodde först det, eftersom båda felen i rc2 kom i första körningen efter en ominstallation. Här föll sekvens 5 men inte 1–4, under identiska förhållanden. Det är en kapplöpning som inträffar ungefär en gång på tio.

### Rättningen

Bara testinfrastrukturen är ändrad:

- **`DeviceTestSupport.kt`:** en gemensam hjälpfunktion, `scrollToNodeWhenLoaded`, som gör om rullningen tills noden finns eller 15 sekunder har gått. Den används på alla 14 ställen där testerna rullar till en nod.
- **`tools/device_test.sh`:** slutar vänta på tvångsstoppstestet när det redan har avslutats. Ett fall där tar nu sekunder i stället för fyra minuter.

### Det andra felet, i sekvens 9

`BookmarkDeviceTest.createBookmarkInReaderAndOpenItFromFavorites` föll medan testet bläddrade i läsaren:

```text
IllegalArgumentException: Detected multithreaded access to SnapshotStateObserver:
previousThreadId=38, currentThread={id=2, name=main}
  at … NodeCoordinator … AndroidComposeView.dispatchDraw …
```

- Det är Compose som upptäcker att en ritning har observerats från två trådar. Undantaget kastades på huvudtråden under en vanlig bildruta.
- Det kom inte i listrullningen som jag ändrade. Den delen av testet var klar 2 sekunder tidigare.
- Jag körde samma test 25 gånger i rad efteråt: 25 av 25 godkända. Felet finns inte i någon av de övriga 14 sekvensernas loggar, och jag har inte sett det i tidigare faser.
- **Orsaken är inte belagd.** Min gissning är testramverket: testerna använder den äldre varianten av Compose-testregeln, där arbete kan köras på testtråden, och Kotlin varnar för just det vid bygget. Men jag kan inte utesluta att appens läsare gör något som bidrar.

Jag har inte ändrat något för det här felet, eftersom utredningen inte visar var det ligger.

### Läget efter del A

| | Före rättningen | Efter rättningen |
|---|---|---|
| Rena sekvenser | 5 | 10 |
| Helt godkända | 4 | 9 |
| Fall på listrullningen | 1 | 0 |
| Fall på annat | 0 | 1 (ritningsfelet ovan) |

Förslag att ta ställning till:

1. **Merga grenen.** Rättningen tar bort det fel som utreddes, och de fem senaste sekvenserna är gröna. Det kvarstående felet har inträffat en gång.
2. **Byt till den nyare Compose-testregeln** (`androidx.compose.ui.test.junit4.v2`) efter 1.0, och se om ritningsfelet försvinner. Det rör alla 11 testklasser som styr gränssnittet och hör hemma i backloggen.

---

## DEL B – Publiceringsgranskning

Granskat: alla 130 commits på 18 grenar och 18 taggar, 1 092 filversioner, plus arbetskatalogen och release-APK:n. Repot har ingen fjärradress.

### BLOCKERANDE

**Inga fynd.** Ingenting i historiken eller i trädet är sådant att det inte får bli publikt oavsett beslut.

Kontrollerat i hela historiken:

| Sökt | Fynd |
|---|---|
| `*.pdf` | 0 |
| `search.db` och andra `*.db` | 2 filer, båda testdatabaser (57 kB var). *Rättat: jag skrev "med påhittad text". Det stämmer inte helt, se "Undantaget för testdatabaserna" nedan.* |
| `*.jks`, `*.keystore`, `*.p12`, `*.pem`, `*.key` | 0 |
| `gradle.properties` med lösenord | 0. Den enda som finns är projektets egen, med fem bygginställningar. |
| Säkerhetskopior ur appen | 0 |
| `cache/`, `dist/`, `samples/`, APK-filer | 0 |
| HQ-bilder eller andra bilder från FTP | 0 utanför omslagen och skärmbilderna nedan |
| pdftotext-utdata eller OCR-text i filer | 0 hela sidor. Korta utdrag finns, se BESLUT 5. |
| Hemligheter: gitleaks 8.30.1 över hela historiken | 0 läckor |
| `dmzRelease…`, `storePassword`, `keyPassword`, `BEGIN PRIVATE KEY`, `token`, `secret`, `api_key`, `Authorization` | bara variabelnamn och dokumentation, inga värden |
| Telefonens serienummer, din hemkatalog, ditt namn | 0 |

`.gitignore` täcker allt känsligt som finns i arbetskatalogen: `cache/` (16 GB), `dist/` (418 MB), `samples/` (281 MB), `app/build/`, `.venv/`, `local.properties`. Ingenting är ospårat utan att vara ignorerat. Nyckeln och lösenorden ligger utanför katalogen.

### BESLUT – material från arkivet

*Rubriken hette "BESLUT – Gustav". Efter hans svar (del C) är punkterna 3 och 4 OK, och punkterna 1, 2, 5 och 6 är dina beslut. Han blockerar ingenting, men han äger inte materialet och hans svar är inget upphovsrättsligt tillstånd.*

**1. Omslagen.** *Ditt beslut.* 185 miniatyrer i `data/covers/` (WebP, 600 pixlar breda, 16,7 MB). De följer med i appen och är 62 % av APK:ns innehåll.

**2. Skärmbilderna.** *Ditt beslut.* 167 bilder i `docs/screenshots/`, 98 MB i trädet och 102 MB i historiken.

- 123 visar omslag eller tidningssidor. 56 av dem visar sidor i läsaren, flera som helsida eller utsnitt i hög upplösning.
- `docs/screenshots/covers/contact_sheet.png` visar alla 185 omslag i en bild.
- 44 visar bara inställningar, statistik eller dialoger.
- Sökskärmarna från FAS 9 visar korta OCR-utdrag.

Jag har sett igenom dem efter personliga uppgifter: 128 på kontaktkartor nu, och de 39 från rc1 och rc2 valde jag själv ut när de togs. Ingen visar filväljaren, andra appar eller annat än DMZ Reader. Statusraden visar klockslag och batteri, ingen operatör.

**3. Arkivets FTP-konto.** *OK efter Gustavs svar: appen får köras mot servern.* Användarnamn och lösenord (`dmz` / `dmz`) står i klartext i `tools/config.json` och `app/build.gradle.kts`, och följer med i APK:n. Planen kallar kontot publikt, men det är Gustavs server och hans sak att avgöra om det ska stå i ett öppet repo.

**4. Listningen av servern.** *OK efter Gustavs svar, med en reservation: han har godkänt att appen använder servern, inte uttryckligen att en fullständig fillista publiceras. Arkivindexet behövs av appen, men `raw_listing.json` och `https_probe.json` är arbetsfiler som kan lämnas utanför ett publikt repo utan kostnad.* `data/raw_listing.json` (1,4 MB) innehåller varje filnamn, storlek och ändringstid i arkivets 191 FTP-kataloger. `data/https_probe.json` (128 kB) innehåller ETag och storlek för varje PDF. Arkivindexet har adresser till varje PDF, omslag och skanning.

**5. Korta textutdrag ur tidningarna.** *Ditt beslut.*

| Fil | Innehåll |
|---|---|
| `data/normalizer_review_v1.json`, `_v2.json` | 266 utdrag på högst 341 tecken, sammanlagt cirka 26 000 tecken |
| `tools/testdata/search_lines.json` | ord med lägen ur ett fåtal verkliga rader, cirka 1 100 tecken |
| `tools/testdata/search_golden.json` | 43 sökord med förväntade sidor och mina anteckningar, cirka 3 700 tecken, till största delen min egen text |
| `docs/reports/fas-8a.md` med flera | ett tiotal korta citat som exempel på textuttag |

**6. Ordlistan.** *Ditt beslut.* `tools/data/normalizer_lexicon_v1.txt` är 144 377 ord som förekommer i tidningarnas text, ett per rad (1,3 MB). Ingen löpande text, men härledd ur OCR-texten.

### BESLUT – projektet

**7. E-postadressen i historiken.** Alla 130 commits har `Rognils <117741407+Rognils@users.noreply.github.com>` som författare. Den blir publik med repot.

**8. Arbetsdokumenten.** `CLAUDE.md`, projektplanen, 24 ADR:er och fasrapporterna beskriver arbetet i detalj. De innehåller telefonmodell och system (OnePlus 7 Pro, LineageOS), vilka nummer du har läst och hur länge, sökvägar som `~/dmz-backup-före-…` och `~/dmz-keys/`, och certifikatets fingeravtryck. Inget av det är hemligt, men det är mer än ett öppet projekt behöver visa.

**9. Raderna `Co-Authored-By: Claude …`** i varje commit.

**10. Repots storlek.** 126 MB, varav skärmbilderna är drygt 100 MB.

**11. LICENSE och README saknas** i repots rot. Förslag nedan.

### OK

- **Arkivindexet** (`data/archive_inventory.json`, 209 kB): bara metadata. Fälten är identitet, år, etiketter, adresser, storlekar, ETag, sidantal, flaggor och HQ-mappning. Ingen tidningstext.
- **Testdatabaserna** `fts_test.db` och `search_test.db`: bara i debug-bygget. *Rättat: de innehåller korta ordagranna utdrag ur tidningarna, inte bara påhittad text. De hör alltså till punkt 5, se "Undantaget för testdatabaserna" nedan.*
- **`data/hq_mapping_report.json`**: filnamn och poäng.
- **Typsnitten** Archivo och IBM Plex Mono har sina OFL-licenser med i appen.
- **Om-texten** nämner Gustav vid namn, som du har bestämt.
- **Release-APK:n** innehåller ingen sökdata, inga testdatabaser och inga PDF:er.

### Vad APK:n innehåller

`dist/dmz-reader-1.0.0-rc2-release.apk`, 24,4 MB (27,2 MB okomprimerat), 308 filer:

| Kategori | Storlek | Filer |
|---|---|---|
| Omslag (`assets/covers/`) | 16,73 MB | 185 |
| Maskinkod för fyra processortyper (SQLite med mera) | 4,94 MB | 12 |
| Appens kod (`classes.dex`) | 3,93 MB | 1 |
| Resurser, typsnitt och ikoner | 1,04 MB | 8 |
| Arkivindexet (`assets/archive_inventory.json`) | 0,21 MB | 1 |
| OkHttps lista över toppdomäner | 0,13 MB | 1 |
| Signatur och versionsfiler | 0,08 MB | 84 |
| Typsnittslicenser | 0,01 MB | 2 |
| Om-texten (`assets/about_sv.md`) | 1,4 kB | 1 |
| Övrigt (manifest, profiler, Kotlin-metadata) | 0,07 MB | 13 |

FTP-kontot ligger i koden. Tidningsmaterialet i APK:n är omslagen och inget annat.

### LICENSE och README

Ingen av filerna finns. Förslag, att lägga i repots rot när du har bestämt dig:

**`LICENSE`** – MIT-licensens standardtext, med raden `Copyright (c) 2026 Rognils`, följd av ett stycke som inte är en del av licensen:

```text
Scope of this license

This license covers the source code of DMZ Reader only: the Android app,
the Python toolchain, and the project's own documentation.

It does not cover, and grants no rights to:
- the magazines Datormagazin and HighScore, their texts, images and covers
- cover thumbnails bundled with the app or stored in this repository
- screenshots in this repository that show magazine covers or pages
- archive data from DMZArkivet, including file listings and indexes

That material belongs to its respective rights holders and is made
available by DMZArkivet (https://dmzarkivet.se).
```

**`README.md`** – förslag på inledning:

```markdown
# DMZ Reader

En Android-app för att läsa Datormagazin och HighScore från
[DMZArkivet](https://dmzarkivet.se). Gratis, utan reklam, konton eller spårning.

DMZArkivet byggs och underhålls av Gustav. Appen är ett fristående
projekt och hämtar tidningarna direkt från arkivet.

## Licens

Källkoden är licensierad under MIT, se [LICENSE](LICENSE).

**Licensen gäller bara källkoden.** Tidningarnas texter, bilder och omslag
tillhör sina rättighetsinnehavare och omfattas inte. Det gäller också omslagen
som följer med appen, skärmbilder som visar tidningssidor, och arkivdata från
DMZArkivet. Är du rättighetsinnehavare och vill att något tas bort: öppna ett
ärende här.

## Bygga

Se `docs/signing.md` och `tools/README.md`.
```

Tredjepartsbibliotekens licenser (Apache 2.0 för AndroidX, OkHttp, Coil, Commons Net och kotlinx) kräver ingen fil i repot, men en lista på Om-sidan står i backloggen.

### De tre lägena

Ingenting av detta är gjort. *Efter Gustavs svar är läge 3 inte aktuellt, och valet mellan läge 1 och 2 är ditt, inte hans. Följderna för hans server i läge 2 gäller fortfarande.*

**Läge 1 – Gustav godkänner allt, även omslag i appen.**

- *Bort ur träd och historik:* ingenting måste bort.
- *I appen:* ingenting.
- *Att göra:* lägga till `LICENSE` och `README.md`, och avgöra punkterna 7–10.
- *Om e-postadressen ska bytas eller skärmbilderna tunnas ut:* det kräver omskriven historik. Enklast är att publicera från en ny kopia med en enda första commit (`git checkout --orphan`), och behålla det nuvarande repot privat med hela historiken. Alternativet är `git filter-repo --mailmap` på en kopia.
- *Insats:* en till två timmar utan omskrivning, en halv dag med.

**Läge 2 – appen godkänns, men inte inbyggda omslag.**

- *Bort ur träd och historik:* `data/covers/` (185 filer) och `docs/screenshots/covers/contact_sheet.png`. Troligen också de 123 skärmbilder som visar omslag eller sidor, eftersom de återger samma material. Verktyg: `git filter-repo --invert-paths --path data/covers --path docs/screenshots` på en ny klon, eller en ny första commit som i läge 1. Taggarna flyttas då, och det gamla repot behålls privat.
- *I appen:* reservvägen finns redan och är testad (ADR 0016): saknas `coverAsset` hämtas omslaget via `coverUrl` med Coil, högst fyra förfrågningar åt gången, och sparas i en diskcache på 300 MB. Det som ändras är att toolchainen skriver arkivindexet utan `coverAsset`, att byggsteget som kopierar omslagen tas bort, och att tester som förutsätter inbyggda omslag skrivs om.
- *Tre följder som Gustav bör känna till innan han väljer det här:*
  - Webbens omslagsbilder är hela skanningar, i snitt 1,3 MB styck och 233 MB för alla 185. Varje användare hämtar dem från hans server, årgång för årgång.
  - 12 tidiga nummer har ett vridet uppslag som sidbild 1. Appen rättar det med `coverFlags`, men de 46 omslag som Gustav själv har snyggat till ligger bara på FTP och används då inte.
  - Biblioteket visar inga omslag utan nät förrän de har hämtats en gång.
- *APK:n* krymper från 24 till cirka 8 MB.
- *Insats:* en till två dagar med tester, nytt arkivindex och prov på telefonen. Det är en ändring av appens beteende och behöver en egen releasekandidat.

**Läge 3 – appen ska inte publiceras.**

- *Bort:* ingenting. Repot förblir privat och APK:n sprids inte.
- *I appen:* adressen i User-Agent pekar på ett repo som aldrig blir publikt. Den bör bytas mot något Gustav kan nå dig på, eller tas bort. Det är en liten ändring i `ClientIdentity`, och en ny version.
- *Att tänka på:* om en privat säkerhetskopia av repot läggs hos en tjänst gäller punkterna ovan mot den tjänsten.
- *Insats:* under en timme.

---

## DEL C – Gustavs svar

### Gustavs svar

Gustav, som driver DMZArkivet, har svarat. I sammanfattning:

- Appen får köras mot hans server.
- Han har inget emot att datan används. Han äger den inte själv, så svaret är inget upphovsrättsligt tillstånd.
- Servern går på hans hemmauppkoppling. Vid strul kan den vara nere upp till någon dag.
- Han erbjuder sig att föra över data om projektet vill hosta den själv. Det är inte aktuellt nu.

### Ändrad klassificering

| Fråga | Före | Efter |
|---|---|---|
| Serveranvändning: FTP-kontot, adresserna i arkivindexet, klientidentifieringen | BESLUT (Gustav) | **OK** |
| Listningen av servern (`raw_listing.json`, `https_probe.json`) | BESLUT (Gustav) | **OK**, med reservationen i punkt 4 ovan |
| Omslagen i appen och i repot | BESLUT (Gustav) | **BESLUT (ditt)** |
| Sökindexet `search.db` som publik fil | inte bedömt | **BESLUT (ditt)**, se nedan |
| Skärmbilder, textutdrag och ordlista | BESLUT (Gustav) | **BESLUT (ditt)** |
| Upphovsrätten till tidningsmaterialet | öppen | **öppen, oförändrad** |

Ingenting är BLOCKERANDE. Upphovsrätten är en egen fråga som Gustavs svar inte löser: rättigheterna ligger hos tidningarnas utgivare och upphovsmän, och ingen av dem har tillfrågats. Det som finns i dag är Om-sidans erbjudande att ta bort material på begäran. Jag kan inte bedöma den juridiska risken.

### Bedömning av search.db

Granskad: `dist/search.db`, arkivversion 2026-10-06-006, 148 877 312 byte, SHA-256 `9da0dbd60a15c15e5e0b9f81fc8549dbcfcc2b596c61faf8642901193965a6ec`. Allt nedan är räknat i filen. Ingen text är utskriven eller citerad.

| | |
|---|---|
| Nummer | 185 av 185 |
| Sidor | 11 293, lika många som arkivindexets sidantal för varje nummer |
| Text som den kom ur PDF:erna (`rawText`) | 54 985 023 tecken |
| Samma text normaliserad för sökning (`searchText`) | 54 647 855 tecken |
| Sidor med text | 11 211, det vill säga 99,3 % |
| Sidor utan text | 82 (ytterligare 65 har under 50 tecken) |

Filens tabeller:

- `pages`: en rad per sida med nummer, sidnummer och de två textkolumnerna. Cirka 113 MB av filen.
- `pages_fts` med fyra hjälptabeller: FTS5-indexet över `searchText`. Resten av filen, cirka 36 MB.
- `issues`: en rad per nummer med sidantal och PDF:ens storlek, ETag och SHA-256. Det är härkomsten: vilken fil texten kom ur.
- `meta`: elva rader. Byggdatum, verktygets namn och version, arkivversion, antal nummer och sidor, normaliserarens och ordlistans version, pdftotext-läget, schemaversion, SQLite-version och tokenizer.

**Finns något som inte är tidningstext eller ren metadata? Nej.** `meta` innehåller inga sökvägar, datornamn, användarnamn eller adresser. Jag sökte också i texten efter spår av byggmiljön: teckenföljden `/home/` finns på fyra sidor i två nummer från 1995 och mitt användarnamn på tre sidor från 1991–1994, och i båda fallen är det tidningarnas egen text.

Två saker att väga in i beslutet:

1. **Filen är hela arkivets text.** Omslagen är 185 förminskade bilder. Sökindexet är den fullständiga texten i alla 185 nummer, 55 miljoner tecken, i en form som går att kopiera och söka i. Upphovsrättsligt väger det tyngre än omslagen.
2. **Texten innehåller det tidningarna tryckte, även personuppgifter.** 150 sidor har något som ser ut som en e-postadress och 916 sidor något som ser ut som ett telefonnummer. Det är insändare, radannonser och redaktionsrutor, med privatpersoners namn och adresser från 1986 och framåt. Samma uppgifter finns redan i arkivets PDF:er, men ett sökbart index på GitHub gör dem lättare att hitta. Siffrorna är mönsterträffar, inte en genomgång.

### Förslag: hosting av search.db

- **Aldrig i git.** `dist/` är ignorerad, och kontrollen före push söker efter `*.db` i hela historiken.
- **Release-fil på GitHub**, namngiven efter arkivversionen: `search-2026-10-06-006.db`. Storleken, 149 MB, ryms med god marginal i gränsen för release-filer.
- **SHA-256 i release-texten**, tillsammans med arkivversion, antal nummer och sidor, och vilken app-version indexet passar.
- **Ett nytt index är en ny release-fil**, inte en ersättning. Äldre filer ligger kvar så att en äldre app kan få ett index som stämmer med dess arkivindex.
- **Appen behåller manuell import i 1.0.** Användaren hämtar filen i webbläsaren och väljer den under Inställningar, som i dag.
- **Automatisk hämtning läggs i backloggen för 1.1** och förutsätter publikt repo: release-filer i ett privat repo kräver inloggning. Inget token i appen, någonsin.

Så länge repot är privat finns filen bara hos dig. Den laddas inte upp i den här uppgiften.

### Förslag för 1.0.0: när arkivet är nere

Ingenting av detta är ändrat. Båda förslagen är ändringar i appen och kräver ett nytt bygge.

**a. En mening på Om-sidan**, sist i avsnittet "Tack, Gustav!", före länken:

> DMZArkivet körs på en privat uppkoppling, så ibland kan arkivet vara otillgängligt en stund. Nummer som du har laddat ned går alltid att läsa.

Den andra meningen är mitt tillägg. Den säger vad användaren kan göra under tiden.

**b. Felmeddelandena i dag.** Lästa ur koden, inte framkallade på telefonen. Anslutning vägras, tidsgräns och DNS-fel behandlas lika i båda flödena: alla är IO-fel och ger samma text.

| Flöde | När arkivet inte svarar | Text i dag |
|---|---|---|
| PDF via Läs | efter högst 15 s | "Det gick inte att nå arkivet. Kontrollera att telefonen har nät och försök igen." Knapp: Försök igen. |
| PDF via nedladdningskön | – | Inget meddelande. Numret står kvar som "Köad" och systemet försöker igen senare. |
| HQ | efter tidsgränsen | "HQ gick inte att hämta: arkivet nås inte. Kontrollera att telefonen har nät. PDF-sidan visas." Knapp: Försök igen. |
| HQ, servern svarar men avvisar | genast | "HQ gick inte att hämta: arkivet svarade med ett fel. PDF-sidan visas." |

Texterna säger alltså redan att det är arkivet som inte nås, men de pekar bara på telefonens nät. Ingen av dem säger att arkivet kan vara nere eller att det hämtade ligger kvar. Förslag:

- **PDF:** "Det gick inte att nå arkivet. Kontrollera att telefonen har nät. Arkivet kan också vara tillfälligt otillgängligt. Det som redan har hämtats ligger kvar, så nedladdningen fortsätter där den slutade när du försöker igen."
- **HQ:** "HQ gick inte att hämta: arkivet nås inte just nu. Kontrollera att telefonen har nät. Arkivet kan också vara tillfälligt otillgängligt. PDF-sidan visas."

Att nedladdningen fortsätter där den slutade stämmer med hur appen fungerar: den ofullständiga filen sparas och nästa försök begär resten.

En sak utöver det du frågade om: **kön är tyst.** Är arkivet nere ett dygn står numren som "Köad" hela tiden, utan förklaring. Ett besked efter några misslyckade försök vore en ny funktion och hör hemma i 1.1. Jag har inte lagt in det i backloggen utan att du har sagt till.

---

## DEL D – Privat GitHub-repo

*Läget när det här avsnittet skrevs. Repot skapades senare, se "Undantaget för testdatabaserna, och det privata repot".*

**Repot är inte skapat och ingenting är pushat.** Kontrollen i steg 3 slår, och uppgiften säger att jag då ska stanna. Eftersom utfallet var känt i förväg skapade jag inte heller ett tomt repo.

### Steg 1 – inloggningen

`gh auth status`: inloggad på github.com som **Rognils**, protokoll https. Tokenets behörigheter är `gist`, `read:org`, `repo` och `workflow`. `repo` räcker för att skapa privata repon och pusha.

### Steg 2 – finns repot?

`gh repo view Rognils/dmz-reader`: finns inte.

### Steg 3 – kontrollerna före push

Körda 2026-10-10 på hela historiken: 132 commits, 18 grenar, 18 taggar.

| Kontroll | Utfall |
|---|---|
| gitleaks 8.30.1, alla grenar (114 commits; de 18 sammanfogningarna räknas inte) | 0 läckor |
| Filer över 50 MB | 0. Den största är en skärmbild på 4,9 MB. |
| `*.pdf` | 0 |
| `*.jks`, `*.keystore`, `*.p12`, `*.pem`, `*.key` | 0 |
| Säkerhetskopior ur appen, APK-filer | 0 |
| **`*.db`** | **2 träffar** |

Träffarna är `app/src/debug/assets/fts_test.db` och `app/src/debug/assets/search_test.db`. Det är samma två filer som del B klassade som OK: testdatabaser på 57 kB var, som bara följer med debug-bygget. *(Del B kallade texten påhittad. Det är rättat i nästa avsnitt.)* Ingen av dem är `search.db`.

Jag bedömer dem som ofarliga, men regeln lyder "inga `*.db` någonstans i historiken, och om något slår: pusha inte". Undantaget är ditt att göra. Vill du ha bort dem krävs omskriven historik, och det avråder jag från för två testfiler.

### Steg 4 – vad som skulle pushas

- **`main` och alla 18 taggar.** Alla taggar pekar på commits som nås från `main`.
- **Grenarna:** 17 av 18 är redan mergade till `main` och tillför ingenting utöver sitt namn. Förslag: pusha inga av dem.

  `feature/fas-0-archive-analysis`, `feature/fas-0b-inventory`, `feature/fas-1-android-foundation`, `feature/fas-2-library`, `feature/fas-3-reader`, `feature/fas-4-reading-state`, `feature/fas-5-offline`, `feature/fas-6-personal-library`, `feature/fas-7-inventory-import`, `feature/fas-8a-search-pilot`, `feature/fas-8b-search-index`, `feature/fas-9-fulltext`, `feature/fas-10-statistics`, `feature/fas-11-hq`, `release/1.0-rc`, `release/1.0-rc2`
- **`release/1.0-gates`** är den enda som inte är mergad. Mergas den följer den med `main`. Annars föreslår jag att den pushas som egen gren, så att rapporten finns i repot.

Repot är 127 MB, varav skärmbilderna drygt 100 MB. Det går att pusha som det är.

### Steg 5 och 6

Inte gjorda. Inga releaser är skapade, och varken APK eller `search.db` är uppladdad.

---

## Undantaget för testdatabaserna, och det privata repot

*Tillagt 2026-10-10, efter ditt besked att fortsätta.*

### Testdatabaserna är inte helt syntetiska

Du bad mig bekräfta att ingen text i de två filerna kommer från tidningarna. **Det går inte att bekräfta.** Jag jämförde varje sida i testdatabaserna med samma sida i det riktiga sökindexet:

| Fil | Tabeller och rader | Text | Ordagrant ur tidningarna |
|---|---|---|---|
| `fts_test.db` | `pages` 8, `issues` 3, `meta` 6, `selftest` 11, FTS-tabeller | 1 986 tecken | 265 tecken på 3 sidor |
| `search_test.db` | `pages` 263 (250 tomma), `issues` 6, `meta` 11, FTS-tabeller | 1 180 tecken | 652 tecken på 8 sidor |

"Ordagrant" är sammanhängande stycken på minst 20 tecken som finns på samma sida i arkivet. Det rör sig om enstaka meningar: en mening ur en artikel, en rubrik, en rad ur en annons. Resten är skrivet för testerna. Ingen av filerna har någon e-postadress eller något telefonnummer.

Samma meningar står i källkoden till `tools/build_fts_testdb.py` och `tools/build_search_testdb.py`, som filerna byggs av. Påståendet i del B att texten var påhittad kom från skriptens egna kommentarer. Jag hade inte jämfört med arkivet.

**Ditt beslut:** filerna tillåts som korta utdrag, i samma kategori som textutdragen i testfacit (punkt 5). Historiken skrivs inte om. Att byta texten mot påhittad framåt står i backloggen.

### Uttryckligt undantag

| Sökväg | Storlek | SHA-256 | I historiken |
|---|---|---|---|
| `app/src/debug/assets/fts_test.db` | 57 344 byte | `fd2c4f406d47f6ba801362723400cfe86060fd10971907c1aa92a2fd4ee3a9b1` | en version, nuvarande |
| `app/src/debug/assets/search_test.db` | 57 344 byte | `4ca5e7676674b33c47adb70ab3e7410bdbdd2793bc3a6cb30985df128e90bf10` | nuvarande, sedan FAS 11 |
| `app/src/debug/assets/search_test.db` | 57 344 byte | `ab45b068dc56678022dc128e70595b38ae6e8d7a43ad2af777c8297f0d124c27` | äldre version från FAS 9 |

Den äldre versionen har samma text som den nuvarande. Skillnaden är arkivversionen i `meta` (005 i stället för 006).

### `tools/prepush_check.sh`

Skriptet går igenom varje fil i varje commit på alla grenar och taggar och avslutar med fel om det hittar:

- en `*.db` som inte är en av de tre raderna ovan, med exakt den sökvägen och kontrollsumman
- `*.pdf`, nyckelfiler (`*.jks`, `*.keystore`, `*.p12`, `*.pem`, `*.key`), `*.apk` eller `*.aab`
- en säkerhetskopia ur appen: filnamnet `dmz-reader-backup*`, eller en JSON-fil med fältet `backupFormat`
- en fil över 50 MB

Prövat i ett tillfälligt repo med planterade filer: det stoppade för en PDF, en främmande databas, en kopia av en tillåten testdatabas under ett annat namn, en säkerhetskopia, en nyckelfil och en fil på 53 MB, även när filerna var borttagna i en senare commit.

Hemligheter söker skriptet inte efter. Det gör gitleaks.

### Repot

Efter att skriptet och gitleaks gav rent (134 commits, 0 fynd, 0 läckor):

- `Rognils/dmz-reader` skapades som **privat**, utan README, licens eller `.gitignore` från GitHub.
- `main` och de 18 taggarna pushades. Inga andra grenar.
- Kontroll efteråt: `gh repo view` visar `PRIVATE`, och `git ls-remote` är identisk med lokalt för `main`, `HEAD` och alla 18 taggarna.
- Inga releaser är skapade. Ingen APK och inget sökindex är uppladdat.

`main` på GitHub är `aa8eadc` (1.0.0-rc2). Grenen `release/1.0-gates` är inte pushad.

---

## Den sista grindkörningen

**Utfall då: inte 10 av 10.** *Fortsättningen står i del E: HQ-felet låg i testet, och efter rättningen blev det 10 av 10.*

Tre omgångar med tio rena sekvenser var (avinstallera debug-appen, installera, hela `device_test.sh`):

| Omgång | Testkod | Gröna | Föll på |
|---|---|---|---|
| 1 | `bb15a6c` | 1 av 2, sedan avbruten | nedladdningstestet efter flygplanstestet |
| 2 | `d3a2066`, första rättningen | 8 av 10 | samma, i sekvens 9 och 10 |
| 3 | `817d35d`, andra rättningen | **9 av 10** | ett HQ-test, i sekvens 5 |

Release-appen var 1.0.0-rc2 med samma uppdateringstid före och efter varje sekvens, och det yttre fingeravtrycket var identiskt i alla 22.

### Felet i omgång 1 och 2: wifi tappar efter flygplansläget

`OfflineDeviceTest` slår på och av flygplansläge. Nästa test, `ReaderDeviceTest.readDownloadTurnPageAndClose`, hämtar 1986 nr 1 från arkivet. Loggen visar samma förlopp i alla tre fallen:

1. Flygplansläget stängs av. Wifi ansluter efter 2–3 sekunder.
2. Wifi tappar anslutningen en gång till (`onDebugErrorAlert 14` från wifi-drivrutinen) och är tillbaka cirka 20 sekunder senare.
3. Föll det andra tappet under nästa tests nedladdning bröts den, och testet väntade i 120 sekunder på en läsare som aldrig öppnades.

Det andra tappet kom i **alla 22 sekvenser**, mellan 1 och 24 sekunder efter att wifi anslutit. Det är telefonens beteende. Troligen förklarar det också läsartestet som föll en gång under v0.10.1 utan belagd orsak, men den loggen finns inte kvar.

**Rättningen, bara i testkoden:** flygplanstestet väntar efter sig tills wifi har tappats och sedan varit bekräftat i 10 sekunder (eller 45 sekunder om inget tapp kommer). Den första rättningen, 20 sekunders stabilt nät, räckte inte eftersom tappet kan komma senare än så. I omgång 3 väntade testet ut tappet i alla tio sekvenserna, och nedladdningstestet gick igenom varje gång.

### Vad appen gjorde med den avbrutna nedladdningen

Du bad mig kontrollera det. I alla tre fallen (omgång 1 sekvens 2, omgång 2 sekvens 9 och 10):

- **Nedladdningen gick till fel direkt.** Appen loggade `nedladdningen avslutades med NetworkError(Software caused connection abort)` 0,12–0,18 sekunder efter att wifi försvann. Läs-vägen visar då feltexten och knappen "Försök igen".
- **Den blev inte hängande** och stod inte kvar som pågående eller köad.
- **Den fortsatte inte av sig själv** när wifi kom tillbaka. Det är så Läs fungerar i dag, och står redan i backloggen.
- **Den återupptogs vid nästa försök.** I omgång 2 hämtade nästa test samma nummer två minuter senare, och appen loggade `nedladdad, 15105472 byte, återupptagen=true`: resten begärdes med Range, och filen blev hel.

Det är i sin ordning enligt ditt kriterium. Texten "Försök igen" på skärmen syns inte i loggen; att den visas följer av koden, där ett nätverksfel alltid ger det läget.

Underlag: `nathandelser.txt` i varje sekvens under `docs/reports/gates/forstastart/slutkorning-omgang-1/`, `slutkorning-omgang-2/` och `slutkorning/`.

### Felet i omgång 3: en sida räknades inte som läst i HQ-läge

`HqDeviceTest.hqPageNextPageAndOfflineFromCache` föll i sekvens 5 på sin näst sista kontroll: sida 3 skulle stå som läst efter 2,5 sekunder i HQ-läge, och gjorde det inte. Läspositionen var rätt (sida 3).

Vad loggen visar:

- Testet hade just slagit av HQ och på igen på sida 3. Det gör det i varje körning.
- HQ-bilden var klar 2,7 sekunder innan läsaren stängdes. Sidan ska räknas som läst efter 1,5. I de nio gröna sekvenserna var tiden 2,9–3,0 sekunder, så marginalen var något mindre här, men fortfarande över en sekund.
- Jag har jämfört renderingen av sida 3 i alla tio sekvenserna (antal förhandsbilder, antal fullständiga bilder och ordningen mellan dem). Den fallna sekvensen har samma mönster som två av de gröna. Loggen pekar alltså inte ut någon orsak.

**Jag kan inte säga om felet ligger i testet eller i appen.** Appen räknar en sida som läst när den har visats färdigrenderad i 1,5 sekunder. Om ett snabbt byte av HQ-läge under pågående rendering kan lämna sidan märkt som "inte färdig" är det en appbugg: en sida som läses i HQ-läge räknas då inte. Jag har läst koden utan att hitta en sådan väg, men jag har inte heller kunnat utesluta den.

Samma test gick igenom 25 gånger av 25 när jag körde det ensamt efteråt (`slutkorning/hq-upprepning.txt`), och felet finns inte i någon tidigare logg.

**Jag har inte rättat något för det här felet.** Appens rader under testet ligger i `slutkorning/sekvens-5/hq-testet.txt`.

### Läget

| | Antal sekvenser | Helt gröna |
|---|---|---|
| Före alla rättningar (sekvens 1–5) | 5 | 4 |
| Efter listrullningen (6–15) | 10 | 9 |
| Sista körningen, omgång 1–3 | 22 | 18 |

Tre orsaker är belagda och rättade i testkoden: listrullningen, den avbrutna väntan i skriptet och wifi-tappet. Två fel är oförklarade och har inträffat en gång var: ritningsfelet i bokmärkestestet (sekvens 9 i del A) och HQ-testet ovan.


---

## DEL E – HQ-felet: utrett, och förstastartsfrågan stängd

*Tillagt 2026-10-11.*

**Klassning: felet låg i testet, inte i appen. Appen räknar sidor rätt i HQ-läge.** Det ligger närmast (c) i uppgiften, med skillnaden att felet gick att återskapa när jag väl visste hur. Testet är rättat, och tio rena sekvenser av tio är gröna. `release/1.0-gates` och `fix/hq-seen-pages` är mergade till `main`, som är pushad.

### Hur en sida räknas som läst

- Varje sidbild rapporterar vad som visas: ingenting, förhandsbild eller färdig bild (`ZoomablePages.kt`).
- En klocka per skärm (`PageReadTimer`) går bara när alla skärmens sidor visas färdiga och appen är i förgrunden. Efter 1,5 sekunder räknas sidorna som lästa och skrivs till `IssueUserState` genom en skrivkö.
- Klockan nollställs bara när skärmen byter sidor. **Ett byte av HQ-läge nollställer den inte:** den pausar medan den nya bilden laddas och fortsätter sedan. Fall (a) i uppgiften, att tiden börjar om vid bytet, stämmer alltså inte med koden.
- `lastPage` och `maxPageReached` förs för sig och påverkas inte av HQ-läget.

### Orsaken

Under en `ComposeTestRule` ritar Compose bara om skärmen när testet gör något: letar efter en nod, väntar på ett villkor eller ber om stillhet. Sover testtråden med `Thread.sleep` står skärmen still.

HQ-testet väntade tills HQ-raden visade "HQ" och sov sedan i 2,5 sekunder. HQ-bilden blir färdig strax efter att raden ändras. Hann den inte ritas innan testet somnade fick appen aldrig veta att sidan visades färdig, klockan stod still, och sidan räknades inte.

Den tillfälliga loggningen visar det för en tappad sida (`hq-stresstest/tappad-sida-med-loggning.txt`): bilden sattes 0,2 sekunder efter det sista bytet, men rapporten att den visades kom först sex sekunder senare, när testet gick vidare.

### Stresstestet

`HqSeenStressDeviceTest`, sju varianter. Varje körning går igenom sidorna 2–28 och mäter tiden från det sista bytet av HQ-läge tills sidan står som läst i databasen. Två körningar per variant ger 54 sidor.

| Variant | Med ritning under väntan | Med sovande testtråd |
|---|---|---|
| HQ på direkt efter sidbytet | 54 lästa, längst 1,6 s | 54 lästa |
| Fem snabba byten efter sidbytet | 54 lästa, längst 1,2 s | 54 lästa |
| HQ på mitt i lästiden | 54 lästa, längst 0,3 s | 54 lästa |
| HQ av och på mitt i lästiden | 54 lästa, längst 1,4 s | **0 lästa, 54 tappade** |
| Byte medan HQ-hämtningen pågår | 54 lästa, längst 1,0 s | 54 lästa |
| Byte när HQ-hämtningen misslyckas | 54 lästa, längst 1,5 s | 54 lästa |
| Fel, nytt försök, HQ av och på (det fallna testets förlopp) | 54 lästa, längst 1,4 s | **36 lästa, 18 tappade** |

Med ritning under väntan räknades alla 378 sidorna, ingen senare än 1,6 sekunder efter bytet. Med sovande testtråd tappas sidor i de två varianter där testet somnar medan en bild laddas.

Att "HQ på mitt i lästiden" räknas efter 0,3 sekunder visar att klockan fortsätter över bytet i stället för att börja om.

### Kontroll utan testram

För att inte bara lita på testmiljön körde jag debug-appen utan instrumentering och styrde den med `adb`: åtta sidor i HQ-läge, där HQ slogs av och på 0,7 sekunder efter varje sidbyte.

- **8 av 8 sidor räknades som lästa** och skrevs till databasen.
- Färdig bild rapporterades som visad 10–40 ms efter att den sattes.
- Klockan löste ut 1,5–1,9 sekunder efter sidbytet varje gång.

Underlag: `hq-stresstest/utan-testram.txt`. Kontrollen hämtade åtta skanningar från arkivets FTP, cirka 40 MB. Det var mer än jag hade tänkt mig.

### Rättningen

Bara testkod:

- `DeviceTestSupport.letAppRun(millis)` väntar och låter appen rita under tiden.
- `HqDeviceTest` väntar på att HQ-bilden visas och använder sedan `letAppRun` i stället för `Thread.sleep`.
- `HqSeenStressDeviceTest` ligger kvar och körs på begäran (`-e dmzStress true`). Med `-e dmzPump false` återskapar det felet.

Den tillfälliga loggningen är borttagen. Ingen fil under `app/src/main` är ändrad av grenarna.

Jag gick igenom de övriga testernas sovpauser. Ingen annan väntar på att en sida ska räknas som läst direkt efter att en bild har börjat laddas, så jag har inte ändrat dem.

### De tio sekvenserna

| | |
|---|---|
| Testkod | `3d728d6` |
| Gröna | **10 av 10** |
| Per sekvens | 76 tester (69 som förut och 7 stresstester som hoppas över), omstartstestet och tvångsstoppstestet |
| Release-appen | 1.0.0-rc2 med uppdateringstiden 2026-10-10 12:37:52 före och efter varje sekvens |

Underlag: `docs/reports/gates/forstastart/slutkorning-del-e/`.

### Förstastartsfrågan

Stängd. Fyra orsaker till ostadiga tester är belagda och rättade, alla i testkoden:

| Orsak | Rättning |
|---|---|
| Testerna rullade till en nod innan listan hade innehåll | `scrollToNodeWhenLoaded` |
| Skriptet väntade i fyra minuter på ett test som redan fallit | avbruten väntan |
| Wifi tappar en gång till efter flygplansläget | flygplanstestet väntar ut tappet |
| Compose ritar inte om medan testtråden sover | `letAppRun` i HQ-testet |

Ett fel är fortfarande oförklarat: ritningsfelet i bokmärkestestet, som inträffade en gång i sekvens 9 i del A och inte sedan dess på 38 hela sviter. Det står i backloggen under ostadiga tester.

---

## Kvar innan v1.0.0 kan taggas

*Uppdaterad 2026-10-11. Läget för rc3 och sökindexet står i `docs/reports/rc3.md` och `docs/reports/search-privacy.md`.*

1. Veckans användning enligt checklistan i `rc1.md`.
2. Dina beslut före en publik release, nedan.
3. Bygga och signera 1.0.0 (versionCode 10000), och kontrollera certifikatets fingeravtryck.

## Beslut du behöver ta före en publik release

- **Omslag i appen:** behålla de 185 inbyggda omslagen (läge 1) eller hämta dem från arkivet (läge 2, kräver en ny releasekandidat och lägger 233 MB per användare på Gustavs uppkoppling).
- **search.db som publik release-fil:** hela arkivets text, med de personuppgifter tidningarna tryckte. Alternativet är att fulltextsökningen inte erbjuds publikt.
- **Skärmbilderna:** 123 av 167 visar omslag eller sidor. Behålla, tunna ut eller lämna utanför det publika repot.
- **Textutdragen och ordlistan** i `data/` och `tools/`.
- **Arbetsfilerna `raw_listing.json` och `https_probe.json`:** med eller utan.
- **E-postadressen** `117741407+Rognils@users.noreply.github.com` i alla commits.
- **Arbetsdokumenten:** plan, ADR:er och fasrapporter publika eller inte.
- **Historiken:** publicera den som den är, eller en ny första commit. Flera av punkterna ovan går bara att ändra med det senare.
- **Upphovsrätten:** om ett erbjudande att ta bort material på begäran räcker för dig. Gustavs svar ändrar inte den frågan.

---

## Ändrade filer i den här grenen

- `app/src/androidTest/java/se/c64/dmzreader/DeviceTestSupport.kt` och tio testklasser (listrullningen)
- `app/src/androidTest/java/se/c64/dmzreader/OfflineDeviceTest.kt` (väntan på nätet efter flygplansläget)
- `tools/device_test.sh` (avbruten väntan)
- `app/src/androidTest/java/se/c64/dmzreader/HqDeviceTest.kt` och `HqSeenStressDeviceTest.kt` (ritning under väntan, stresstest)
- `tools/prepush_check.sh` (ny)
- `docs/backlog-1.1.md` (tre punkter från del C)
- `docs/reports/gates.md` och `docs/reports/gates/forstastart/` (15 sekvenser, mätningen av vanlig start och den upprepade körningen av bokmärkestestet)

Ingen fil under `app/src/main` är ändrad.
