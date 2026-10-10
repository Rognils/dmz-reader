# Rapport – före 1.0.0: förstastartsfelet och publiceringsgranskning

Datum: 2026-10-10 · Gren: `release/1.0-gates` (från `main`, inte mergad) · App oförändrad, 1.0.0-rc2

**Vad som återstår innan v1.0.0 kan taggas:** Gustavs svar om omslag, skärmbilder, FTP-kontot och serverlistningen; dina beslut om e-postadressen i historiken och om arbetsdokumenten ska bli publika; LICENSE och README; ditt beslut om den här grenen ska mergas trots ett kvarstående, sällsynt testfel; och veckans användning enligt checklistan i `rc1.md`.

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
| `search.db` och andra `*.db` | 2 filer, båda testdatabaser med påhittad text (57 kB var) |
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

### BESLUT – Gustav

**1. Omslagen.** 185 miniatyrer i `data/covers/` (WebP, 600 pixlar breda, 16,7 MB). De följer med i appen och är 62 % av APK:ns innehåll.

**2. Skärmbilderna.** 167 bilder i `docs/screenshots/`, 98 MB i trädet och 102 MB i historiken.

- 123 visar omslag eller tidningssidor. 56 av dem visar sidor i läsaren, flera som helsida eller utsnitt i hög upplösning.
- `docs/screenshots/covers/contact_sheet.png` visar alla 185 omslag i en bild.
- 44 visar bara inställningar, statistik eller dialoger.
- Sökskärmarna från FAS 9 visar korta OCR-utdrag.

Jag har sett igenom dem efter personliga uppgifter: 128 på kontaktkartor nu, och de 39 från rc1 och rc2 valde jag själv ut när de togs. Ingen visar filväljaren, andra appar eller annat än DMZ Reader. Statusraden visar klockslag och batteri, ingen operatör.

**3. Arkivets FTP-konto.** Användarnamn och lösenord (`dmz` / `dmz`) står i klartext i `tools/config.json` och `app/build.gradle.kts`, och följer med i APK:n. Planen kallar kontot publikt, men det är Gustavs server och hans sak att avgöra om det ska stå i ett öppet repo.

**4. Listningen av servern.** `data/raw_listing.json` (1,4 MB) innehåller varje filnamn, storlek och ändringstid i arkivets 191 FTP-kataloger. `data/https_probe.json` (128 kB) innehåller ETag och storlek för varje PDF. Arkivindexet har adresser till varje PDF, omslag och skanning.

**5. Korta textutdrag ur tidningarna.**

| Fil | Innehåll |
|---|---|
| `data/normalizer_review_v1.json`, `_v2.json` | 266 utdrag på högst 341 tecken, sammanlagt cirka 26 000 tecken |
| `tools/testdata/search_lines.json` | ord med lägen ur ett fåtal verkliga rader, cirka 1 100 tecken |
| `tools/testdata/search_golden.json` | 43 sökord med förväntade sidor och mina anteckningar, cirka 3 700 tecken, till största delen min egen text |
| `docs/reports/fas-8a.md` med flera | ett tiotal korta citat som exempel på textuttag |

**6. Ordlistan.** `tools/data/normalizer_lexicon_v1.txt` är 144 377 ord som förekommer i tidningarnas text, ett per rad (1,3 MB). Ingen löpande text, men härledd ur OCR-texten.

### BESLUT – ditt

**7. E-postadressen i historiken.** Alla 130 commits har `Rognils <117741407+Rognils@users.noreply.github.com>` som författare. Den blir publik med repot.

**8. Arbetsdokumenten.** `CLAUDE.md`, projektplanen, 24 ADR:er och fasrapporterna beskriver arbetet i detalj. De innehåller telefonmodell och system (OnePlus 7 Pro, LineageOS), vilka nummer du har läst och hur länge, sökvägar som `~/dmz-backup-före-…` och `~/dmz-keys/`, och certifikatets fingeravtryck. Inget av det är hemligt, men det är mer än ett öppet projekt behöver visa.

**9. Raderna `Co-Authored-By: Claude …`** i varje commit.

**10. Repots storlek.** 126 MB, varav skärmbilderna är drygt 100 MB.

**11. LICENSE och README saknas** i repots rot. Förslag nedan.

### OK

- **Arkivindexet** (`data/archive_inventory.json`, 209 kB): bara metadata. Fälten är identitet, år, etiketter, adresser, storlekar, ETag, sidantal, flaggor och HQ-mappning. Ingen tidningstext.
- **Testdatabaserna** `fts_test.db` och `search_test.db`: påhittad text, och bara i debug-bygget.
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

DMZArkivet byggs och underhålls av Gustav Almström. Appen är ett fristående
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

Ingenting av detta är gjort.

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

## Ändrade filer i den här grenen

- `app/src/androidTest/java/se/c64/dmzreader/DeviceTestSupport.kt` och tio testklasser (listrullningen)
- `tools/device_test.sh` (avbruten väntan)
- `docs/reports/gates.md` och `docs/reports/gates/forstastart/` (15 sekvenser, mätningen av vanlig start och den upprepade körningen av bokmärkestestet)

Ingen fil under `app/src/main` är ändrad.
