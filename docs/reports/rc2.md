# Rapport – 1.0.0-rc2: Om-sida, klientidentifiering och ikonjustering

Datum: 2026-10-10 · Gren: `release/1.0-rc2`, mergad till `main` och taggad `v1.0.0-rc2` · App 1.0.0-rc2 (versionCode 9902)

## 1. Sammanfattning

Fem ändringar, var och en i egen commit:

- **Version** 1.0.0-rc2, versionCode 9902. App-ID oförändrat.
- **Klientidentifiering:** appen presenterar sig för arkivet som `DMZReader/1.0.0-rc2 (Android; +https://github.com/Rognils/dmz-reader)`, som User-Agent i all HTTP-trafik och med CLNT över FTP.
- **Om-sida** med texten ur `assets/about_sv.md`, nåbar från Inställningar och från en info-ikon i bibliotekets toppfält.
- **Statistik-fliken** har en diagramikon.
- **Backlog** inför 1.1 i `docs/backlog-1.1.md`.

Ingen kärnfunktion, datamodell, migrering eller importlogik är ändrad. Databasen är kvar på version 8 och säkerhetskopian på format 2.

Din app är uppdaterad till rc2 med `adb install -r`, och din data är oförändrad: en export före och en efter är identiska post för post.

**Arkivets FTP-server stöder CLNT.** Den annonserar CLNT i FEAT och godtog kommandot.

## 2. Skapade och ändrade filer

**Nya:**

- `app/src/main/assets/about_sv.md`
- `app/src/main/java/se/c64/dmzreader/data/network/ClientIdentity.kt`
- `app/src/main/java/se/c64/dmzreader/domain/Markdown.kt`
- `app/src/main/java/se/c64/dmzreader/ui/about/AboutScreen.kt`
- `app/src/main/java/se/c64/dmzreader/ui/components/Icons.kt`
- `app/src/test/java/se/c64/dmzreader/data/network/ClientIdentityTest.kt`
- `app/src/test/java/se/c64/dmzreader/domain/MarkdownTest.kt`
- `app/src/androidTest/java/se/c64/dmzreader/AboutDeviceTest.kt`
- `docs/backlog-1.1.md`, `docs/decisions/0024-klientidentifiering-mot-arkivet.md`, `docs/reports/rc2.md`, `docs/reports/rc2/`, `docs/screenshots/rc2/`

**Ändrade:**

- `app/build.gradle.kts` (version)
- `data/network/CoverImageLoader.kt`, `data/pdf/PdfDownloader.kt` (interceptorn)
- `data/hq/FtpScanSource.kt` (FEAT och CLNT efter inloggningen)
- `AppContainer.kt` (identiteten till FTP-källan, loggning i debug, läsning av Om-texten)
- `ui/DmzReaderRoot.kt` (rutten Om, ikonen)
- `ui/library/LibraryScreen.kt` (info-ikonen), `ui/settings/SettingsScreen.kt` (raden Om DMZ Reader)
- `FtpScanSourceTest.kt` (sex nya tester), `LibraryNavigationTest.kt` (stadigare väntan)
- `tools/device_test.sh` (yttre fingeravtryck, sparad utskrift från tvångsstoppstestet)

**Utanför repot:** `dist/dmz-reader-1.0.0-rc2-release.apk` och `~/dmz-backup-före-rc2/`.

## 3. Vad som gjordes

### Klientidentifiering

- `ClientIdentity.userAgent` bygger strängen ur `BuildConfig.VERSION_NAME`.
- En interceptor sätter den på båda HTTP-klienterna: PDF-klienten och omslagsklienten, som är den Coil använder. Appen har ingen annan HTTP-trafik; arkivindex och sökindex importeras från filer.
- Över FTP skickas FEAT efter inloggningen. Listar servern CLNT skickas `CLNT <strängen>`, en gång per anslutning. Saknas FEAT eller CLNT, eller nekas kommandot, fortsätter anslutningen som vanligt, utan omförsök och utan felloggning.

Beslutet står i ADR 0024.

### Om-sidan

- Texten ligger i `app/src/main/assets/about_sv.md`, med det innehåll du angav, och kan ändras utan kodändring.
- En egen tolk i `domain/Markdown.kt` stöder `#`, `##`, stycken, `**fet**` och `[text](adress)`. Inget nytt beroende.
- `{version}` ersätts med byggets version vid visningen.
- Rubrikerna är märkta som rubriker för TalkBack. Länkarna är riktiga länkar (`LinkAnnotation.Url`) med länkroll, understrukna i accentfärgen, och öppnas i telefonens webbläsare.
- Bara adresser som börjar med `http://` eller `https://` blir länkar.
- Sidan visas utan bottenfält, som Inställningar och Diagnostik.

Två saker att känna till:

- Tillbakapilen rullar med texten, som i Inställningar. Längst ned på sidan används systemets tillbakagest.
- Ingen villkorslogik finns för GitHub-länken. När repot blir publikt ändras texten i filen.

### Ikonen

Appen har bara grunduppsättningen av Material-ikoner, där ingen diagramikon ingår. I stället för ett nytt beroende är ikonen ritad för hand som en `ImageVector` med tre staplar, i samma rutnät som Materials "bar chart". Etikett och funktion är oförändrade.

## 4. Testresultat

| Test | Resultat |
|---|---|
| JVM | 491 av 491 (24 nya) |
| Python | 206 av 206 |
| Lint | 0 fel, 13 varningar (oförändrat) |
| Instrumenterad svit i debug-appen, isolerad lagring | 69 av 69 (3 nya) |
| Omstartstestet och tvångsstoppet | godkända |

**Nya enhetstester:**

- User-Agent finns på förfrågan mot MockWebServer, för både PDF-klienten och omslagsklienten, även på Range-förfrågningar, och ersätter en header som anroparen satt.
- FTP-källan mot en falsk server: CLNT skickas när FEAT listar det, inte annars; en server utan FEAT fungerar som vanligt; ett nekat CLNT (500, 502, 530, 550) ändrar ingenting och prövas inte igen; återupptagning och saknad fil fungerar likadant med CLNT.
- Markdown-tolken: rubriker, stycken, fetstil, länkar, osäkra adresser och `{version}`. Dessutom kontrolleras Om-texten i assets: rubrikerna i ordning, de två länkarna och versionsraden.

**Nya instrumenterade tester:** Om-sidan öppnas från biblioteket och från Inställningar, "Tack, Gustav!" visas, versionen är ifylld, rubrikerna är märkta som rubriker och länkarna bär rätt adress.

**Svitkörningarna gick inte igenom varje gång.** Skriptet kördes fem gånger under arbetet:

| Körning | Resultat |
|---|---|
| 1, direkt efter ny installation | omstartstestet och tvångsstoppstestet föll (läsaren öppnades inte i tid). Svitens egen rad sparades inte. |
| 2 | 68 av 69: ett navigeringstest föll på en kapplöpning i testet, som tryckte innan årgångskortet var färdigritat |
| 3, efter att testet gjorts stadigare | allt godkänt |
| 4, direkt efter ny installation | 69 av 69, men tvångsstoppstestets första del startade inte |
| 5 | allt godkänt, med den slutliga koden |

Felet i körning 1 och 4 kom båda gångerna i första körningen efter att debug-appen hade installerats om, och testet gick igenom när jag körde det för sig. Orsaken är inte belagd, eftersom skriptet kastade bort testets utskrift. Det sparar den numera. Felen gäller testernas start, inte appens beteende, men jag kan inte utesluta att det finns en verklig tröghet vid första starten efter installation.

## 5. Verifiering på telefonen

### Certifikatet

`apksigner verify --print-certs dist/dmz-reader-1.0.0-rc2-release.apk`:

```text
Signer #1 certificate DN: CN=DMZ Reader, O=C64, C=SE
Signer #1 certificate SHA-256 digest: 5a3679ecc4ccbf9b61247e3580b178028f03e6e47e6d6d7f325d2a295475f727
```

Det är samma fingeravtryck som det förväntade. APK:n är 24,4 MB, SHA-256 `f7927abb…aeb83b17`.

### Din data före och efter uppdateringen

Säkerhetskopian togs genom appens export (Download vald uttryckligen) och hämtades till `~/dmz-backup-före-rc2/`. Appen uppdaterades med `adb install -r`, utan avinstallation.

| | Före (rc1) | Efter (rc2) |
|---|---|---|
| Favoriter | 0 | 0 |
| Bokmärken | 0 | 0 |
| Nummer med läsdata | 2 | 2 |
| Lästa nummer | 0 | 0 |
| Lästa sidor | 10 | 10 |
| Sessioner | 4 | 4 |
| Lästid | 136 s (2 min) | 136 s (2 min) |
| Fortsätt läsa | 1991 nr 15, sida 19 av 80 | samma |

Exporten efter är identisk med den före i alla tre delarna (läsdata per nummer, bokmärken och sessioner). Jag öppnade inte läsaren den här gången, så ingen läsning lades till.

| Inställning | Före | Efter |
|---|---|---|
| Ladda ned endast via wifi | på | på |
| Vald publikation | Datormagazin | Datormagazin |
| Sökindex | arkivversion 006, 185 nummer | samma, ingen versionsnotis |
| Läs-cache | 25,6 MB | 25,6 MB |
| Notisbehörighet | beviljad | beviljad |

Det enda som ändrades är tidpunkten för "Senaste export", av mina exporter. Appen installerades två gånger, eftersom jag gjorde Om-sidan utan bottenfält efter den första installationen. Jämförelsen ovan gäller den slutliga.

### Testerna och din app

Din app är ett release-bygge och går inte längre att läsa med `run-as`, så det gamla fingeravtrycket av filernas innehåll kan inte tas. `device_test.sh` tar i stället ett yttre fingeravtryck: version, installationstid, signatur, senaste gången appen syntes och om processen är igång. Det var identiskt före och efter varje svitkörning. Det visar att testerna inte startade, uppdaterade eller bytte ut appen. Att de inte rörde dess filer följer av att de körs i en annan app (`se.c64.dmzreader.debug`), som systemet inte ger åtkomst till dem.

### User-Agent och CLNT i praktiken

Ur debug-byggets logg, mot arkivet:

```text
DmzNet: User-Agent: DMZReader/1.0.0-rc2 (Android; +https://github.com/Rognils/dmz-reader) · GET dmzarkivet.se/pdf/198604.pdf
DmzHq: FTP-servern stöder CLNT; klienten presenterad som DMZReader/1.0.0-rc2 (Android; +https://github.com/Rognils/dmz-reader)
DmzHq: FTP ansluten till dmzarkivet.se:21 (anslutning 1)
DmzHq: HQ hämtad: datormagazin-1986-04/DMZ_1986-04-001.png, 13403398 byte på 1018 ms
```

- **HTTP:** headern skickades på PDF-hämtningen. Hämtningen blev inte kort: 1986 nr 4 (23 MB) hann bli klar innan jag avbröt.
- **FTP:** servern annonserar CLNT och svarade positivt. HQ-sidan hämtades som vanligt.
- **Omslag:** ingen omslagsförfrågan gjordes, eftersom alla omslag följer med appen och reservvägen via nätet inte användes. Att omslagsklienten skickar samma header är belagt i enhetstestet, inte mot arkivet.

### Skärmbilder

I `docs/screenshots/rc2/`, från release-appen:

| Fil | Visar |
|---|---|
| `01-om-overst.jpg` | Om-sidan överst |
| `02-om-nederst.jpg` | Om-sidan nederst, med "Version 1.0.0-rc2" |
| `03-installningar-om-dmz-reader.jpg` | Inställningar med raden "Om DMZ Reader" |
| `04-bibliotek-info-ikon.jpg` | biblioteket med info-ikonen bredvid kugghjulet |
| `05-statistik-ny-ikon.jpg` | Statistik-fliken med diagramikonen |

## 6. Acceptanskriterier

- [x] versionName 1.0.0-rc2, versionCode ett över rc1, app-ID oförändrat
- [x] en central identitet, med versionen från bygget
- [x] User-Agent på all HTTP-trafik genom en interceptor, och samma klient för Coil
- [x] CLNT bara när FEAT listar det, och utan påverkan när det saknas eller nekas
- [x] enhetstest för User-Agent mot MockWebServer
- [x] enhetstest för FTP-källan med och utan CLNT, och med nekat CLNT
- [x] Om-texten i `about_sv.md`, visad med egen tolk, utan nytt beroende
- [x] `{version}` ersätts, länkar öppnas externt, rubriker och länkar är märkta för TalkBack
- [x] Om-sidan nås från Inställningar och från biblioteket
- [x] enhetstest av tolken och instrumenterat test av sidan
- [x] diagramikon på Statistik, inget annat ändrat
- [x] `docs/backlog-1.1.md`
- [x] alla enhetstester och den instrumenterade sviten godkända, med slutlig kod
- [x] signerad release i `dist/`, certifikatet kontrollerat
- [x] säkerhetskopia före, installation med `-r`, samma antal och inställningar efter
- [x] User-Agent och CLNT kontrollerade mot arkivet
- [x] skärmbilder

## 7. Kända begränsningar

- **Svitens första körning efter en ny installation av debug-appen föll två gånger** på testernas start. Orsaken är inte belagd.
- **Omslagsklientens User-Agent är inte sedd mot arkivet,** bara i enhetstest.
- **TalkBack är inte provat med en riktig skärmläsare.** Märkningen är kontrollerad i semantikträdet.
- **Adressen i User-Agent pekar på ett repo som inte är publikt än.**
- **Fingeravtryck av innehållet i din data kan inte tas** mot ett release-bygge.
- Begränsningarna från rc1 gäller fortfarande, se `docs/reports/rc1.md`, avsnitt 8.

## 8. ADR

- **0024** (ny): klientidentifiering mot arkivservern.

## 9. Vad som återstår och förslag

- Veckans användning enligt checklistan i `docs/reports/rc1.md`, avsnitt 12, gäller nu rc2.
- Inför v1.0.0: uppdatera `about_sv.md` om repot har blivit publikt, och sätt versionen till 1.0.0 (versionCode 10000).
- Inget är pushat till GitHub.
- Förslag: undersök första svitkörningen efter installation innan nästa version, nu när skriptet sparar utskriften.
