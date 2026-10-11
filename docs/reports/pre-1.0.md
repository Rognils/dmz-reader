# Rapport – före 1.0.0: filter p2, förberedd version och beslutsunderlag

Datum: 2026-10-11 · `main` är pushad till det privata repot · Ingenting är byggt eller taggat som 1.0.0 · Release-appen på telefonen är inte rörd

## 1. Sammanfattning

| Del | Vad | Läge |
|---|---|---|
| H | Filter version 2: bankgiro och telefonnummer utan riktnummer | Klart. `search-2026-10-06-006-p2.db` är byggd, kontrollerad och upplagd. p1 är borttagen. |
| I | README-texten om sökindexet | Klart, på `main` |
| J | Regel om tester mot arkivservern, och genomgång | Klart. **Tre tester hämtar cirka 138 MB per svit i dag.** |
| K | Förberedd 1.0.0 | Klart på grenen `release/1.0.0`. Inte byggd, inte taggad. |
| L | Beslutsunderlag | `docs/reports/publiceringsbeslut.md` |

Det viktigaste att veta är fyndet i del J. Varje gång hela den instrumenterade sviten har körts har den hämtat cirka 138 MB från Gustavs server, inte 30 MB som jag har skrivit i tidigare rapporter. Under grindkörningarna sedan den 10 oktober blev det i storleksordningen 7 GB.

## 2. Del H – filter version 2

Detaljerna står i `docs/reports/search-privacy.md`, avsnitt 11. Här är utfallet.

| | |
|---|---|
| Fil | `search-2026-10-06-006-p2.db`, 90 968 064 byte (91 MB) |
| SHA-256 | `7710b864cff9c9d1bcdbdd2a2b885466e112401abaa6306a7ed054b347796b90` |
| `privacyFilterVersion` | 2 |
| Nya träffar | 74 bankgironummer och 833 telefonnummer utan riktnummer |
| Maskerat sammanlagt | 49 496 uppgifter på 5 074 av 11 293 sidor |

**Stoppregeln:**

| Villkor | Utfall |
|---|---|
| Högst 3 % falska bankgiroträffar | 0 av 74 (alla granskade, eftersom de är färre än 100) |
| Högst 3 % falska telefonträffar utan riktnummer | 1 av 150 (0,7 %) |
| Sökfacit tappar ingen förväntad sida | 43 av 43 före och efter |

**Kontrollerna, samma som för p1:**

| Kontroll | Utfall |
|---|---|
| Sökfacit | 43 av 43 och 0 irrelevanta, omaskerat och maskerat |
| "telefon" | 1 253 sidor före, 1 253 efter |
| "bankgiro" | 67 före, 67 efter |
| "postgiro" | 294 före, 295 efter |
| SQL i textkolumnerna | 0 träffar |
| `strings`-utdata | 0 träffar |
| Råa bytes | 0 träffar |
| Två byggen | byte-identiska |
| Import i debug-appen | "185 nummer och 11293 sidor, på 1,8 sekunder", och sökningarna ger samma antal som ovan |

**Två saker om den nya telefonkategorin:**

- **Den fångar mer än nummer utan riktnummer.** Av 149 rätta träffar i stickprovet var 57 sådana nummer. 67 var nummer med riktnummer som texttolkningen hade skadat, alltså en del av de 3 % som p1 missade. 25 var början på utländska nummer.
- **De utländska numren maskeras dåligt.** Regeln tar prefixet och landsnumret, och resten står kvar. De var helt omaskerade förut, så det är ingen försämring.

**Releaserna i det privata repot:**

- Prerelease `search-index-2026-10-06-006-p2` är skapad. Jag laddade ned filen igen, och kontrollsumman stämmer.
- Prerelease `search-index-2026-10-06-006-p1` och dess tagg är raderade, på GitHub och lokalt.
- Filen `dist/search-2026-10-06-006-p1.db` ligger kvar på datorn. Säg till om den ska bort.

**Telefonen:** `search-2026-10-06-006-p2.db` ligger i Download, 90 968 064 byte. Jag har inte importerat den i din app. Importen görs under Inställningar → Importera nytt sökindex.

## 3. Del I – README

Stycket om sökindexet lyder nu:

> Sökindexet tillämpar automatisk maskering av igenkända telefon- och faxnummer, e-postadresser, post- och bankgironummer och personnummerliknande mönster. OCR-skador kan innebära att enstaka uppgifter inte känns igen. I sökutdrag kan `•••` markera en maskerad uppgift. Tidningarna i läsaren är oförändrade.

Storleken anges som cirka 90 MB.

En invändning mot ordet "enstaka": uppskattningen är att några procent av telefonnumren inte känns igen, vilket är i storleksordningen tusen nummer. Dessutom maskeras inte utländska nummer, namn och postadresser alls. Texten är din, och jag har lagt in den som du skrev den, men "enstaka" lovar mer än filtret håller. "En del" vore närmare.

## 4. Del J – tester mot arkivservern

`CLAUDE.md` har en ny absolut regel, nummer 12:

> Automatiska tester och testsviter får aldrig hämta från arkivservern (dmzarkivet.se, HTTP eller FTP). Regressionstester använder MockWebServer eller en fejkad FTP-server. Mot den riktiga servern görs bara enstaka manuella kontroller, och mängden data anges i rapporten.

Ingen testkod är ändrad.

### Vad som når servern i dag

Mätt i loggen från en hel svit (`slutkorning-del-e`, sekvens 3), där varje förfrågan mot arkivet loggas med testets namn.

**I varje körning av hela sviten:**

| Test | Vad det hämtar | Data per körning |
|---|---|---|
| `CoverFallbackNetworkTest.atMostFourCoverRequestsAtOnce` | 12 hela omslagsskanningar ur 1993, över HTTP | 102,9 MB |
| `CoverFallbackNetworkTest.coverWithoutThumbnailIsLoadedFromItsUrl` | 1 omslagsskanning (samma som den första ovan) | 5,1 MB, om den inte kommer ur cachen |
| `OfflineDeviceTest.downloadThenReadInAirplaneModeThenRemove` | PDF:en för 1986 nr 1 | 15,1 MB |
| `ReaderDeviceTest.readDownloadTurnPageAndClose` | PDF:en för 1986 nr 1 | 15,1 MB |
| **Summa** | 15 förfrågningar | **133–138 MB** |

Omslagens storlek kommer ur `data/raw_listing.json`: de tolv filerna är tillsammans 102 858 888 byte.

**Jag har underskattat det här i varje tidigare rapport.** `device_test.sh` säger i sin kommentar att två tester hämtar 15 MB var, och det har jag upprepat. Omslagstestet skrevs i FAS 2 och har hämtat sina tolv skanningar i varje svit sedan dess.

Vad det har betytt: under grindkörningarna den 10 och 11 oktober körde jag hela sviten minst 49 gånger (15 sekvenser i del A, 32 i slutkörningarna och 2 för rc3). Det är cirka 6,8 GB från Gustavs hemmauppkoppling på ett dygn. Sviten har dessutom körts i varje fas sedan FAS 2.

**Det som inte når servern:**

- HQ-testerna (`HqDeviceTest`, `HqSeenStressDeviceTest`) använder en fejkad skanningskälla.
- Omstartstestet och tvångsstoppstestet läser PDF:en som `device_test.sh` lägger på telefonen från datorns cache.
- JVM-testerna använder MockWebServer och MockFtpServer.
- Python-testerna av hämtningen går mot en lokal HTTP-server.
- `device_test.sh` gör inga egna förfrågningar.

**Tester som når servern, men bara på begäran:**

| Test | Argument | Vad det hämtar |
|---|---|---|
| `ReaderDeviceTest.verify…` (9 tester) | `-e dmzVerify true` | PDF:er som inte är lagda på telefonen i förväg. 1995 nr 15 är 619 MB. |
| `HqRealDeviceTest` (7 tester) | `-e dmzHqReal true` | riktiga skanningar över FTP, cirka 5 MB per sida |
| `RealIndexDeviceTest` | `-e dmzRealIndex true` | PDF:en för numret vars träff öppnas |
| `DownloadNotificationDeviceTest` | `-e dmzShowNotification true` | en PDF |

**Manuella hämtningar i det här arbetet,** som regeln nu kräver att jag redovisar:

| När | Vad | Data |
|---|---|---|
| Del E, kontroll utan testram | 8 HQ-sidor över FTP och PDF:en för 1986 nr 1 | cirka 55 MB |
| Del F, skärmbilden av HQ-felet | PDF:en för 1986 nr 1 | 15 MB |
| Del H, prov i debug-appen | ingenting, bara sökning | 0 |

### Förslag på ordning för 1.1

1. **Stäng av de tre testerna i den vanliga sviten.** De körs bara med ett argument, till exempel `-e dmzNetwork true`, som testerna mot riktig server redan gör. Tre rader. Det bör göras före publiceringen och inte vänta på 1.1, se punkt 9 i beslutsunderlaget.
2. **Lägg en spärr i testköraren.** `IsolatedTestRunner` ger appen en OkHttp-klient som vägrar varje förfrågan till dmzarkivet.se, om inte argumentet ovan är satt. Då kan ett nytt test inte börja hämta från arkivet av misstag, vilket är det som hände med omslagstestet.
3. **Skriv om omslagstestet mot MockWebServer.** Det prövar två saker: att högst fyra förfrågningar pågår samtidigt, och att ett omslag utan miniatyr hämtas från sin adress. Båda går att pröva mot en lokal server med små genererade bilder. Kräver att testet kan peka om `coverUrl`.
4. **Skriv om de två nedladdningstesterna mot MockWebServer,** som serverar den PDF som redan ligger på telefonen, med stöd för `Range`. Kräver att testet kan peka om `ocrPdfUrl` i den isolerade databasen, och att debug-bygget tillåter okrypterad trafik till den egna telefonen.
5. **Rätta kommentaren i `device_test.sh`** och avsnittet i `tools/README.md`, så att de säger vad som faktiskt hämtas.
6. **Låt testerna mot riktig server vara kvar som manuella kontroller,** med mängden data angiven i varje tests kommentar.

Steg 1 och 2 är små och tar bort hela lasten. Steg 3 och 4 ger tillbaka den täckning som steg 1 tar bort.

## 5. Del K – förberedd 1.0.0

Grenen `release/1.0.0`, från `main`, en commit (`9bfc45d`), pushad till det privata repot.

| Ändring | |
|---|---|
| Version | `versionName` 1.0.0, `versionCode` 10000 |
| Sökindexets storlek | "cirka 150 MB" → "cirka 90 MB". En förekomst i appen, i Inställningar. |
| Om-texten, källkoden | "Källkoden finns på [GitHub](https://github.com/Rognils/dmz-reader)." |
| Om-texten, upphovsrätt | "kontakta projektet via GitHub" är en länk till `https://github.com/Rognils/dmz-reader/issues` |
| Tester | länktestet i `MarkdownTest` väntar sig fyra länkar, och `AboutDeviceTest` kontrollerar de två nya |

| Svit | Resultat |
|---|---|
| JVM | 492 av 492 |
| Python | 242 av 242 |
| Instrumenterade tester | kompilerar. Inte körda, eftersom ingenting ska byggas och installeras nu. |

Ingen APK är byggd, och ingenting är taggat.

Tre saker jag såg på vägen och inte har ändrat:

- **Samma mening säger att sökindexet "byggs på datorn (search.db, …) och importeras här".** För den som har hämtat filen från Releases stämmer det inte. Bara storleken skulle ändras, så resten står kvar.
- **"Den publiceras under MIT-licensen"** står kvar i Om-texten efter den nya meningen. Det läses bra, men "Den är publicerad" vore rakare.
- **`tools/README.md` säger 149 MB** om `dist/search.db` på två ställen. Det gäller den omaskerade filen och stämmer.

## 6. Del L – beslutsunderlaget

`docs/reports/publiceringsbeslut.md` går igenom elva punkter med antal, storlek, sökvägar, alternativ och rekommendation, och slutar med en checklista på 30 steg för publiceringsdagen.

Mina rekommendationer i korthet:

| Punkt | Rekommendation |
|---|---|
| Omslagen | Behåll |
| Skärmbilderna (172 bilder, 109 MB, 80 % av repot) | Ta bort ur den publika historiken |
| Textutdragen | Ta bort de två granskningsfilerna, behåll testfilerna |
| Ordlistan och serverlistningen | Behåll |
| E-postadressen (159 commits, 19 taggar) | Byt till `117741407+Rognils@users.noreply.github.com` |
| Arbetsdokumenten och `Co-Authored-By` | Behåll |
| Tester som hämtar från arkivet | Stäng av före publicering |
| Sökindexet p2 | Publicera |
| Upphovsrätten | Ditt beslut |

Tre saker ur underlaget som är lätta att missa:

- **Det blir en enda omskrivning** om du följer rekommendationerna: adressen, skärmbilderna och granskningsfilerna i samma körning.
- **Force-push räcker inte.** GitHub behåller de gamla commits som lösa objekt, och de följer med när repot blir publikt. Jag rekommenderar att det gamla repot byter namn till ett privat arkiv och att ett nytt skapas. Proceduren med force-push står också där, som du bad om.
- **Omskrivningen måste göras före publiceringen.** Efteråt går den inte att göra om.

## 7. Skapade och ändrade filer

**På `main`:**

- `tools/privacy_filter.py`, `tools/privacy_audit.py`, `tools/build_search_index.py`, `tools/README.md` och testerna (filter version 2)
- `README.md` (del I)
- `CLAUDE.md` (regel 12)
- `docs/reports/search-privacy.md` (avsnitt 11), `docs/reports/publiceringsbeslut.md`, `docs/reports/pre-1.0.md`
- `docs/screenshots/search-privacy/02-p2-bankgiro.png`

**På `release/1.0.0`:** `app/build.gradle.kts`, `about_sv.md`, `SearchIndexSection.kt`, `MarkdownTest.kt`, `AboutDeviceTest.kt`.

**Utanför repot:** `dist/search-2026-10-06-006-p2.db`, och stickproven i `~/dmz-privacy-stickprov/v2/`.

## 8. Kvar före v1.0.0

1. **Importera p2 i din app** från Download, och använd det under veckan.
2. **Veckans användning** enligt checklistan i `rc1.md`.
3. **Dina beslut** i `publiceringsbeslut.md`. Tre av dem styr om historiken ska skrivas om.
4. **Stänga av de tre testerna som hämtar från arkivet,** om du väljer det.
5. **Publiceringsdagen** enligt checklistan: eventuell omskrivning, kontroller, publikt repo, bygge av 1.0.0, releaser och efterkontroll.

Två småsaker att ta ställning till: ordet "enstaka" i README, och meningen om att sökindexet "byggs på datorn" i Inställningar.
