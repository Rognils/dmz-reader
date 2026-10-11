# Fasrapport – FAS 11: HQ

> **Skärmbilderna som nämns här finns inte i repot.** De ligger lokalt hos projektets ägare, se [docs/skarmbilder.md](../skarmbilder.md).

Datum: 2026-10-10 · Gren: `feature/fas-11-hq`, mergad till `main` och taggad `v0.11-fas11` · App 0.11.0 (versionCode 1100)

Före fasen: v0.10.1 (rättningar, se `docs/reports/v0.10.1/README.md`) och Gradle 9.8.1.

## 1. Sammanfattning

HQ-knappen i läsaren fungerar. Den slår på ett HQ-läge för det öppna numret, där sidan som visas hämtas som originalskanning via FTP och visas med samma gester som PDF-sidan. En sida hämtas på 0,4–1,6 sekunder över wifi, och ligger sedan i en cache som fungerar utan nät.

Mappningen mellan PDF-sida och skanning är framtagen med bildjämförelse. De två kända avvikande numren är mappade sida för sida, och stickprovet höll: 39 av 39 sidor i 13 nummer stämmer med regeln sida N = skanning N.

Bildjämförelsen fann en sak som inte var känd: 97 liggande PDF-sidor (uppslag skannade som en bild) har skanningen vriden ett kvarts varv, åt olika håll i olika nummer. Det är löst med ett fält till i inventory, `hqPageRotation`. Fältet beställdes inte, och jag vill att du tar ställning till det (avsnitt 8).

## 2. Din data

Appdatan kopierades till `~/dmz-backup-före-fas11/` innan något annat gjordes. Ingen nollställning.

| | Före fasen | Efter fasen |
|---|---|---|
| `IssueUserState` | 2 | 2 |
| `Bookmark` | 0 | 0 |
| `ReadingSession` | 2 | 2 |
| `IssueLocalState` | 2 | 2 |
| Läs-cachen | 1988 nr 1, 1991 nr 15 | samma filer, samma SHA-256 |
| HQ-cachen i din installation | finns inte | finns inte |

Appen har inte startats med din data under fasen. All verifiering gjordes i testernas isolerade lagring.

**Fingeravtryck:** 15 instrumenterade körningar gjordes med `tools/device_test.sh`, var och en med fingeravtryck före och efter. Fingeravtrycken från 14 av dem finns kvar och är alla identiska med varandra, från den första körningen till den sista. (Den femtonde är första omslagskörningen, vars filer skrevs över när den kördes om; skriptet rapporterade identiska fingeravtryck även där.) Två enstaka tester i v0.10.1-steget kördes utan eget fingeravtryck: skärmbilderna av Statistik och omkörningen av läsartestet. De ligger mellan två körningar med identiska fingeravtryck. De omfattar SHA-256 för databasen efter checkpoint, radantal per tabell och SHA-256, storlek och tid för varje fil i läs-cachen, nedladdningarna, `filesDir/data` och inställningarna.

| Körning | Tester | Fingeravtryck |
|---|---|---|
| v0.10.1, hela sviten, två gånger | 51 av 52, sedan 52 av 52 | identiska |
| Gradle 9.8.1, hela sviten | 52 av 52 | identiska |
| HQ med falsk källa | 4 av 4 | identiska |
| HQ mot arkivet, 7 scenarier (ett kört två gånger) och 2 mätkörningar | alla godkända utom första omslagskörningen, se avsnitt 5 | identiska |
| FAS 11, hela sviten | 63 av 63 | identiska |

Filerna från Gradle-körningen och slutkörningen ligger i `docs/reports/fas-11/`, och de från v0.10.1 i `docs/reports/v0.10.1/`.

**Det här händer när du öppnar appen nästa gång:** databasen går från version 7 till 8 (två nya kolumner i katalogtabellen `Issue`; användardatan rörs inte, och det är prövat i ett migreringstest), och det inbyggda arkivindexet 2026-10-06-006 läses in. Inställningarna kommer då att säga att sökindexet byggdes av en annan arkivversion (005). Sökningen fungerar som förut.

## 3. Steg före fasen

**v0.10.1** (på `main`, tre commits, taggad): sessioner delas vid lokal midnatt, årgångsraden skriver "N % sidor", och mest lästa årgång visar alla årgångar med lika många sidor. JVM-testet läser söndag 23:50 till måndag 00:20 och får 10 minuter i den ena veckan och 20 i den andra.

**Gradle 9.8.1** (egen commit på `main`). Androids kompatibilitetstabell för AGP anger bara en lägsta version: AGP 9.4 kräver minst Gradle 9.6.0. Den nämner inte 9.8 uttryckligen. Gradles egen tabell anger att 9.8.1 är prövad med AGP 9.0–9.5. Jag bedömde det som stöd nog och uppdaterade. Rent bygge, alla JVM-tester, lint, release-bygge och hela sviten på telefonen gick igenom. Om du läser villkoret strängare än jag gjorde går commiten `013d2d2` att backa utan att något annat påverkas.

## 4. Mappningsresultatet (del A)

Skanningarna hämtades via FTP med `tools/fetch_scans.py`: en anslutning i taget, paus mellan filerna, högst 5 MB/s, återupptagbart. Sammanlagt 2,8 GB i 264 filer till `cache/scans/`.

Måttet är normaliserad korskorrelation mellan nedskalade gråskalebilder, 0–1, där det bästa värdet över flera beskärningar av båda bilderna räknas. En sida räknas som säkert mappad vid minst 0,60 och minst 0,10 över närmaste andra kandidat. Poängen för varje kontrollerad sida står i `data/hq_mapping_report.json`.

### 1986 nr 4 – fullständig mappning

49 av 49 sidor mappade, poäng 0,984–0,998. Närmaste andra kandidat fick som mest 0,763.

- Sidorna 1–33 är `DMZ_1986-04-001.png` till `-033.png`.
- Sidorna 34–49 är spelbilagan, `DMZ_1986-04-Spelbilaga-001.png` till `-016.png`.

### 1993 nr 3 – fullständig mappning

66 av 68 sidor mappade, poäng 0,776–0,985.

- **PDF-sida N är skanning N+2.** PDF:en saknar skanning 01, 02, 71 och 72, det vill säga omslaget.
- **Sida 27** är skanning 29 vänd 180 grader (poäng 0,887). Ovänd fick ingen skanning över 0,385.
- **Sidorna 39 och 40 har ingen motsvarighet.** Skanning 41 och 42 visar samma uppslag, men nederkanten har annat innehåll (en beställningskupong respektive en annons som inte finns i PDF:en). Bästa poäng var 0,378 och 0,404. De har null i tabellen och visar "HQ finns inte för den här sidan".
- Tre sidor (45, 52 och 63) hade en näst bästa kandidat nära: tre annonssidor med samma uppställning fick 0,82–0,85 mot varandra, mot 0,95–0,97 för den rätta. Det var skälet till att marginalkravet sattes till 0,10 och inte 0,15. Alla tre följer mönstret N+2.

### Stickprovet – första, mellersta och sista sidan

| Nummer | Första | Mellersta | Sista |
|---|---|---|---|
| 1986 nr 1 (stående sida 1 i både PDF och skanning) | 0,996 | 0,998 | 0,997 |
| 1987 nr 4–5 (filer namngivna efter första numret) | 0,997 (vriden 90°) | 0,996 | 0,997 |
| 1988 nr 16–17 | 0,999 (vriden 90°) | 0,994 | 0,987 |
| 1989 nr 6 | 0,996 (vriden 90°) | 0,990 | 0,997 |
| 1990 nr 10 | 0,998 (vriden 90°) | 0,997 | 0,997 |
| 1991 nr 2 | 0,981 | 0,997 | 0,975 |
| 1991 nr 22 | 0,995 | 0,998 | 0,997 |
| 1992 nr 13 (tvåsiffriga sidfilnamn) | 0,997 | 0,995 | 0,998 |
| 1994 nr 9 | 0,993 | 0,994 | 0,990 |
| 1995 nr 14 | 0,998 | 0,995 | 0,996 |
| 1995 nr 15 | 0,993 | 0,998 | 0,997 |
| HighScore 1993 nr 17 | 0,992 | 0,996 | 0,987 |
| HighScore 1994 nr 8 | 0,997 | 0,996 | 0,999 |

39 av 39 sidor stämmer med sida N = skanning N. Som jämförelse fick en annan skanning ur samma nummer som mest 0,513. Regeln 1:1 håller alltså för stickprovet, och jag stannade inte.

En rättelse av din beskrivning: sida 1 i 1986 nr 1 är inte liggande i PDF:en. Den är ett uppslag som ligger på sidan, likadant i PDF:en och i skanningen, och behöver ingen vridning.

### Liggande sidor – ett fynd utöver det beställda

Fyra av stickprovets förstasidor matchade bara när skanningen vreds 90 grader. Jag kontrollerade därför alla liggande PDF-sidor i arkivet: 107 sidor i 73 nummer (omslag och uppslag skannade som en bild). Deras skanningar hämtades (772 MB) och jämfördes.

- 107 av 107 hittade sin skanning, poäng 0,923–0,999.
- 10 skanningar ligger som PDF-sidan.
- 81 ska vridas 90 grader medurs och 16 ska vridas 270 grader.

Riktningen är alltså inte densamma överallt. En fast regel i appen hade visat 16 sidor upp och ned.

### Inventory

- Nytt valfritt fält `hqPageMap` för 1986 nr 4 och 1993 nr 3.
- Nytt valfritt fält `hqPageRotation` för 68 nummer (98 sidor: 97 liggande och sida 27 i 1993 nr 3).
- Schemat och valideringen är uppdaterade. En tabell måste täcka alla PDF-sidor, en skanning får inte användas två gånger, och varje fil måste finnas i rålistningen.
- Ny datasetVersion: **2026-10-06-006**.
- Kotlin-valideringen läser fälten och avvisar ogiltiga sidnummer, filnamn och vridningar.

## 5. Appen (del B) och verifieringen (del C)

### Vad som byggdes

- **`ScanSource`** med `FtpScanSource` (Apache Commons Net 3.13.0): passivt läge, binärt, arkivets publika konto ur byggkonfigurationen. Anslutningen återanvänds och stängs efter 30 sekunder utan användning.
- **All HQ-trafik går genom `TransferCoordinator`** som en läsning: högst en överföring åt gången, och HQ går före nedladdningskön.
- **HQ-läget:** knappen i toppraden slår på eller av det för numret. Bara sidan som visas hämtas, med förlopp i en rad under toppraden. Ingen förhandshämtning.
- **Visningen:** översikten avkodas nedskalad med `inSampleSize`, och vid zoom avkodas bara det synliga utsnittet med `BitmapRegionDecoder`. Samma gestkod som PDF-läsaren, och zoom och läge behålls när man växlar mellan PDF och HQ.
- **Uppslagsläge:** HQ byter till enkelsida, och HQ av ger uppslaget tillbaka.
- **Fel och saknad skanning** ger ett meddelande och PDF-sidan. Vid fel finns "Försök igen".
- **Cachen:** `cacheDir/hq/{issueId}/{fil}` med ett tak på 500 MB, äldst använda först ut. Inställningar → Lagring visar storleken och har "Rensa HQ-cache".
- Läsposition, lästa sidor och sessioner förs av samma kod som förut.

### På telefonen, mot arkivet (isolerad lagring)

| Punkt | Scenario | Resultat |
|---|---|---|
| 9 | v2-sida: 1986 nr 1, sida 9, en småannons | HQ visar rastret i bilden och skarpare text vid samma zoom. Bild 02 och 03. |
| 9 | Äldre PDF-generation: 1993 nr 3, sida 41, innehållsrutan | Samma utsnitt i PDF och HQ. Bild 07 och 08. |
| 10 | Största skanningen: 1995 nr 15, sida 15 (36,6 MB, 4 890 × 7 000), högsta zoom och panorering | Inget `OutOfMemoryError` och inga krascher i logcat. Bild 20–22. |
| 11 | 1986 nr 4, sida 36 i spelbilagan | `DMZ_1986-04-Spelbilaga-003.png` hämtades och visar samma sida. Bild 12–15. |
| 11 | 1993 nr 3, sida 41 (efter avvikelsen) | `DMZ_1993-03-43.jpg` hämtades. Sidan är tryckt med sidnummer 43. Bild 05 och 06. |
| 11 | 1993 nr 3, sida 39 (utan motsvarighet) | "HQ finns inte för den här sidan", PDF-sidan visas och ingenting hämtades. Bild 09. |
| – | 1993 nr 3, sida 27 (vänd 180°) | HQ ligger som PDF-sidan. Bild 10 och 11. |
| – | 1987 nr 4–5, sida 1 (liggande omslag, vriden 90°) | HQ ligger som PDF-sidan, även i zoom. Bild 16–18. |

Första körningen av omslagsscenariot föll på en väntan i testskriptet (knappen heter "Fortsätt på sida 1", inte "Läs", när numret har läsdata). Det rättades och scenariot gick igenom.

### Hämtningstid över wifi (punkt 12)

| Fil | Storlek | Tid |
|---|---|---|
| 1987 nr 4–5, sida 1 | 1,2 MB | 0,59 s, ny anslutning |
| 1993 nr 3, sida 27 | 2,5 MB | 0,55 s, ny anslutning |
| 1993 nr 3, sida 41 | 3,7 MB | 0,63 s, ny anslutning |
| 1986 nr 1, sida 9 | 4,9 MB | 0,68 s, ny anslutning |
| 1986 nr 1, sida 10 | 5,2 MB | 0,43 s, samma anslutning |
| 1986 nr 4, sida 36 (PNG) | 30,4 MB | 1,17 s, ny anslutning |
| 1995 nr 15, sida 15 | 36,6 MB | 1,57 s och 1,32 s (två körningar), ny anslutning |

Från tryck på HQ till att skanningen visas gick det 1,0–2,1 sekunder. Avkodningen tar cirka 0,5 sekunder för översikten och 0,3–0,5 sekunder per utsnitt, lika för JPEG och PNG.

**FTP-anslutningar:** loggen (`docs/reports/fas-11/ftp-logg.txt`) visar en öppnad anslutning per körning, aldrig en andra medan den första var öppen. I körningen med två sidor gick båda över samma anslutning, och i den längsta körningen stängdes anslutningen efter 30 sekunder utan användning. Belägget är appens egen logg; jag har inte räknat socklar i systemet.

### Minne (punkt 10)

Mätt med `dumpsys meminfo` på 1995 nr 15, vars PDF är 563 MB. PDF-läsaren håller hela den filen i minnet, vilket står för större delen av siffrorna.

| Läge | PSS | Native heap | Grafik |
|---|---|---|---|
| PDF-sidan, ingen zoom | 903 MB | 681 MB | 92 MB |
| HQ, översikt | 939 MB | 674 MB | 131 MB |
| HQ, högsta zoom | 1 004 MB | 708 MB | 167 MB |
| HQ, högsta zoom under panorering | 1 039 MB | 726 MB | 185 MB |
| PDF, högsta zoom (som jämförelse) | 1 134 MB | 821 MB | 185 MB |

HQ lägger som mest 137 MB ovanpå PDF-sidan i grundläget, och mindre än PDF-läsarens egen zoom gör (232 MB). Minnet växte inte mellan den tredje och den sjätte panoreringen.

För 1986 nr 4 (PDF 23 MB, PNG-skanning 30 MB) var PSS 398 MB och native heap 106 MB vid zoom.

## 6. Tester

| Test | Resultat |
|---|---|
| JVM | 458 av 458 (59 nya i fasen, 10 i v0.10.1) |
| Python | 206 av 206 (34 nya) |
| Lint | 0 fel, 13 varningar |
| Instrumenterad svit, isolerad lagring | 63 av 63 |
| Omstartstestet och tvångsstoppet | godkända |

Nya JVM-tester: mappningen (mönster, tabell, null, vridning, trasig tabell, filnamn som pekar ut ur katalogen), cachen (äldst först, taket, rensning), samordningen (aldrig två överföringar samtidigt, HQ före kön, kön väntar), felhantering, FTP-källan mot MockFtpServer (passivt, binärt, återanvänd anslutning, stängning efter tomgång, REST, saknad fil, avvisad inloggning, server som startats om), utsnittsräkningen och migreringen 7 → 8.

Nya instrumenterade tester (`HqDeviceTest`, falsk källa): öppna nummer → HQ → sida visas → nästa sida → utan nät ur cachen → fel och "Försök igen"; sida utan skanning; uppslag blir enkelsida; cachen visas och rensas i inställningarna.

Tre saker att känna till om testerna:

- **Ett läsartest föll en gång i v0.10.1-körningen** (`readDownloadTurnPageAndClose`, som hämtar 1986 nr 1 från arkivet). Felmeddelandet skrevs inte ut, så orsaken är inte belagd. Det gick igenom ensamt direkt efteråt och i de tre senare körningarna av sviten. Skriptet skriver numera ut felmeddelanden.
- **Ett av mina nya JVM-tester var ostadigt** och föll en gång i slutbygget. Felet låg i testets egen ordningskontroll, inte i appen. Efter rättningen gick det igenom sju gånger i rad.
- **Lintvarningarna ökade från 8 till 13.** Två gäller en hjälpklass för TLS i Commons Net som appen inte använder, två är stilförslag i den nya koden, och en kom redan före fasen: lint påpekar att det finns en nyare version av Kotlin-pluginen.

## 7. Acceptanskriterier

- [x] en sida kan växlas till HQ och tillbaka utan att läspositionen tappas (instrumenterat test: HQ av och på igen på sida 3, och läspositionen är sparad efter stängning)
- [x] rätt skanning visas för rätt sida (bildjämförelsen ovan, och scenarierna för 1986 nr 4, 1993 nr 3 och 1987 nr 4–5 på telefonen)
- [x] minnet håller även vid maximal zoom (den största skanningen, högsta zoom och panorering, inget minnesfel)

## 8. Kända begränsningar och det du behöver ta ställning till

**Att ta ställning till:**

1. **`hqPageRotation`** är ett fält och en funktion du inte beställde. Utan det hade 97 sidor legat på sidan eller upp och ned i HQ. Alternativet är att de sidorna inte får HQ alls. Fältet står i planens bilaga E som mitt tillägg.
2. **Gradle 9.8.1** vilar på min läsning av villkoret, se avsnitt 3.
3. **Sökindexet hör till arkivversion 005**, och appen har nu 006. Inställningarna visar en upplysning om det. Ett nytt `search.db` med 006 går att bygga på några minuter ur PDF-cachen, men måste importeras av dig.

**Begränsningar:**

- **183 nummer är inte jämförda sida för sida.** De bygger på stickprovet och på att antalet skanningar är lika med PDF:ens sidantal. En förskjutning inne i ett nummer med rätt antal filer skulle inte upptäckas.
- **Mobildata är inte prövat.** Koden har inget villkor på nätverkstyp, men jag har inte stängt av wifi på telefonen.
- **Sidorna 39 och 40 i 1993 nr 3 har ingen HQ.**
- **HQ-läget sparas inte.** Det slås på för varje öppning av läsaren.
- **En HQ-sida syns 1–2 sekunder efter sidbytet,** eftersom ingenting hämtas i förväg. PDF-sidan visas under tiden.
- **Byter man sida mitt i en hämtning stängs anslutningen,** och nästa sida öppnar en ny.
- **Det finns inga skärmbilder av förloppsraden, felmeddelandet eller HQ-cachen i inställningarna.** De är prövade i de instrumenterade testerna, men hämtningarna går för fort för att fångas på bild mot arkivet.
- Release-varianten bygger (24 MB) men är inte körd på telefonen, och Android 8–13 är fortfarande oprövat.

## 9. Skapade och ändrade filer

**Toolchain:** `tools/fetch_scans.py`, `tools/map_hq_pages.py`, `tools/data/hq_page_maps.json`, `data/hq_mapping_report.json`, `tools/tests/test_hq_mapping.py` (nya); `tools/archive_inventory.py`, `tools/config.json`, `tools/requirements.txt`, `tools/device_test.sh`, `tools/README.md`, `docs/inventory.schema.json`, `data/archive_inventory.json`, `data/inventory_diagnostics.json`, testindexen i `tools/testdata/`.

**Appen, nya:** `domain/HqMapping.kt`, `data/hq/ScanSource.kt`, `FtpScanSource.kt`, `HqCache.kt`, `HqRepository.kt`, `ScanImage.kt`, databasschemat `8.json`.

**Appen, ändrade:** `ui/reader/ReaderViewModel.kt`, `ReaderScreen.kt`, `ZoomablePages.kt`, `ui/settings/SettingsScreen.kt`, `ui/DmzReaderRoot.kt`, `AppContainer.kt`, `data/db/Entities.kt`, `DmzDatabase.kt`, `data/inventory/InventoryDto.kt`, `InventoryMapper.kt`, `InventoryParser.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`.

**Tester, nya:** `HqMappingTest`, `HqCacheTest`, `HqRepositoryTest`, `FtpScanSourceTest`, `ScanRegionTest`, `InventoryHqTest`, `HqDeviceTest`, `HqRealDeviceTest`.

**Dokument:** ADR 0009 (fastställd), ADR 0022 (ny), planens B4, C2, E9, FAS 11 och bilaga E, den här rapporten, 22 skärmbilder i `docs/screenshots/fas11/`.

## 10. ADR:er

- **0009 HQ-källa:** Accepted. HQ är originalskanningar per sida via FTP, Large PDF används inte. Mappningsresultatet står där.
- **0022 HQ i läsaren:** ny. FTP-klient, cache, avkodning och HQ-läget.
- **0021:** ändrad i v0.10.1 (sessioner delas vid midnatt).

## 11. Vad som återstår innan projektet kan kallas 1.0

Planens faser 0–11 är klara. Det här återstår, i den ordning jag föreslår:

1. **Release-bygge med egen signeringsnyckel.** Appen på telefonen är ett debug-bygge med debug-nyckeln. En app signerad med en annan nyckel kan inte installeras ovanpå den, så bytet kräver avinstallation, och då försvinner appens data.
2. **Flytten av din data.** Säkerhetskopian (Inställningar → Exportera) innehåller favoriter, läsdata, bokmärken och sessioner, och importen är prövad sedan FAS 6. Ordningen blir: exportera, kontrollera filen, avinstallera, installera release-bygget, importera, importera `search.db` igen. Nedladdade nummer och cacharna följer inte med och hämtas på nytt. Jag föreslår att hela kedjan övas en gång med testdata i isolerad lagring innan den görs med din data.
3. **Release-bygget på telefonen.** R8 krymper koden, och Commons Net, Room och serialiseringen behöver prövas i den varianten: läsa, söka, HQ och backup. Det har aldrig körts.
4. **Nyckelhantering.** Var nyckeln förvaras, och en kopia utanför datorn. Tappas nyckeln går appen inte att uppdatera utan ny avinstallation.
5. **Nytt `search.db` för arkivversion 006,** så att upplysningen i inställningarna försvinner.
6. **Mobildata:** pröva HQ och Läs en gång med wifi avstängt.
7. **En längre tids vanlig användning** före 1.0, med de kända begränsningarna i fasrapporterna som checklista.

Valfritt, och inget som hindrar 1.0: äldre Android-versioner (8–13) i emulator, ett val att minnas HQ-läget, och att låta HQ-raden ta mindre plats när skanningen väl visas.
