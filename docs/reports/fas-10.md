# Fasrapport – FAS 10: Statistik

Datum: 2026-10-09 · Gren: `feature/fas-10-statistics`, mergad till `main` och taggad `v0.10-fas10` · App 0.10.0 (versionCode 1000)

## 1. Sammanfattning

De instrumenterade testerna körs nu mot isolerad lagring och rör aldrig användarens data. Hela sviten har körts två gånger på telefonen, och ett fingeravtryck av databasen, radantalen, läs-cachen och övriga filer är identiskt före och efter varje körning.

Lässessionerna sparas löpande, och lästiden är aktiv tid: efter fem minuter utan interaktion slutar klockan gå. Fliken Statistik visar nyckeltal, lästid per vecka, progress per årgång, mest lästa årgång, favoriter och bokmärken.

## 2. Din data

Appdatan kopierades till `~/dmz-backup-före-fas10/` innan något annat gjordes. Ingen nollställning.

| | Före fasen | Efter fasen |
|---|---|---|
| `IssueUserState` | 2 (1988 nr 1, 1991 nr 15) | 2 (samma) |
| `Bookmark` | 0 | 0 |
| `ReadingSession` | 2 | 2 (samma) |
| `IssueLocalState` | 3 | 2 |
| Läs-cachen | 1986 nr 1, 1988 nr 1, 1991 nr 15 | 1988 nr 1, 1991 nr 15 |
| Nedladdningar | inga | inga |
| Sökindexet | search.db, 148 877 312 byte | samma fil |

Skillnaden i `IssueLocalState` och läs-cachen är testresten från FAS 9, som du bad mig ta bort: PDF:en för 1986 nr 1 och numrets rad. Jag kontrollerade först att du inte hade någon läsdata, session eller något bokmärke för numret. Inget annat togs bort.

Två saker utöver det som beställdes hände med din data, och båda är ofarliga:

- **Databasens logg slogs ihop med huvudfilen.** Raden togs bort med telefonens eget `sqlite3`, som då gjorde en checkpoint: `dmz-reader.db-wal` försvann och `dmz-reader.db` växte från 155 648 till 159 744 byte. Innehållet är detsamma, och `PRAGMA integrity_check` svarar "ok".
- **Appen startades en gång med dina data** för skärmbilderna av Statistik-fliken. Jag öppnade fliken och rullade, inget annat. Vid start tar appen numera bort sessioner med under 5 sekunder aktiv tid; dina två (61 och 7 sekunder) berördes inte.

## 3. Steg 0: testerna rör aldrig riktig data

**Hur det fungerar.** Testpaketet har en egen testkörare, `IsolatedTestRunner`. Den startar appen som `TestDmzReaderApp`, vars lagring utgår från en kontext där `filesDir`, `cacheDir`, databassökvägen och övriga kataloger pekar in i `app_dmz_test/<körning>/`. Room, DataStore, läs-cachen, nedladdningarna, sökindexet, bildcachen och miniatyrerna hamnar därmed alla där. I appens egen kod behövdes en enda ändring: `DmzReaderApp.storageContext`.

- Katalogen töms före körningen och tas bort efter den. Testköraren väntar 2,5 sekunder först, så att läsarens bakgrundsskrivningar (läsposition och session) är klara.
- Rester efter en avbruten körning tas bort vid nästa.
- Den vanliga testköraren finns inte i testpaketets manifest, och testernas hjälpfunktioner vägrar arbeta om appen inte är `TestDmzReaderApp`. Testerna går alltså inte att starta mot riktig data av misstag.
- Tester som behöver en PDF får den från datorns `cache/pdf/`, som skriptet lägger i `/data/local/tmp/dmz-test-pdfs/` och tar bort efteråt. Två tester prövar själva nedladdningen och hämtar 1986 nr 1 (15 MB var) från arkivet till testets egen katalog.
- `PhoneCleanupDeviceTest`, som kunde nollställa riktig data på begäran, är borttagen.

**Beviset.** `tools/device_test.sh` tar ett fingeravtryck av användarens data, kör hela sviten, omstartstestet och tvångsstoppet, tar ett nytt fingeravtryck och jämför dem.

| Körning | Tester | Fingeravtryck före och efter |
|---|---|---|
| Steg 0, före ny funktionalitet | 48 i sviten + omstartstestets 2 delar, alla godkända | identiska |
| Fasens slut | 52 i sviten + omstartstestets 2 delar + tvångsstoppets 2 delar, alla godkända | identiska |

Fingeravtrycket vid fasens slut (`docs/reports/fas-10/fingeravtryck-slut.txt`; det från steg 0 ligger bredvid):

```text
== dmz-reader.db efter checkpoint, och radantal
9a6d0383bef34a391e867a31575772865981d51b3304358acf40c00028fdf5f9 dmz-reader.db (efter checkpoint)
     0  Bookmark
   185  Issue
     2  IssueLocalState
     2  IssueUserState
     2  ReadingSession
     1  room_master_table
     1  sqlite_sequence
== filer (SHA-256, storlek, ändrad, namn)
b7a62b69…5ee43b84  24474598  2026-10-09 00:19:09  cache/pdf/datormagazin-1988-01.pdf
feaff108…57cf6c1b  25481916  2026-10-09 19:37:09  cache/pdf/datormagazin-1991-15.pdf
files/issues: finns inte
fd2c4f40…4ee3a9b1     57344  2026-10-09 19:18:22  files/data/fts_test.db
f150bd8a…f0bd6fb7 148877312  2026-10-09 19:04:29  files/data/search.db
(inställningsfilen settings.preferences_pb ingår också)
== testkataloger (ska vara tomt)
```

Fingeravtrycket omfattar SHA-256 för databasfilerna som de ligger på telefonen, SHA-256 för databasen efter checkpoint (gjord på en kopia på datorn), radantalet i varje tabell, och SHA-256, storlek och ändringstid för varje fil i läs-cachen, nedladdningarna, `filesDir/data` och inställningarna.

Steg 0 är committat för sig: `dc166c4`.

## 4. Vad som byggdes

**Lässessioner** (planens C6):

- En session skapas när läsaren öppnas och skrivs direkt. Samma rad uppdateras var 30:e sekund, två sekunder efter ett sidbyte, när appen går i bakgrunden och när läsaren stängs.
- `durationSeconds` är aktiv tid: klockan går bara i förgrunden och när en interaktion har förekommit under de senaste 5 minuterna. Varje beröring av sidan räknas (tryck, zoom, panorering, bläddring), liksom sidbyten.
- `endedAt` är den sista aktiva stunden. Längden mäts med en monoton klocka.
- Rotation avslutar inte sessionen. Tillbaka från bakgrunden inom 5 minuter fortsätter samma session, annars börjar en ny.
- Sessioner som slutar med under 5 sekunder aktiv tid tas bort, och kvarlämnade korta städas vid appstart.
- **Ingen migrering behövdes:** tabellen är oförändrad, databasen är kvar på version 7 och befintliga sessioner ligger kvar.

**Statistik** (E12): nyckeltalen lästa nummer, påbörjade, lästa sidor och lästid; stapeldiagram över lästid per vecka i tolv veckor; en rad per årgång med stapel för andelen lästa sidor, "X av Y nummer lästa" och procent, där ett tryck öppnar årgångsvyn; publikationsväljare som i biblioteket; mest lästa årgång, favoriter, bokmärken och olästa nummer; ett tomt läge med förklaring. Staplarna ritas med Compose Canvas. Nyckeltal, veckostaplar och årgångsrader har beskrivningar för skärmläsare ("v. 41: 42 min", "Datormagazin 1986: 2 av 4 nummer lästa, 27 procent av sidorna lästa"). Ingen sökstatistik.

**Sökord i loggen** skrivs bara i debug-bygget. Release loggar "Sökning: 230 sidor på 5,1 ms".

Definitionerna står i planens E12 och i ADR 0021.

## 5. Verifiering på telefonen

Scenarierna kördes i testernas isolerade databas, inte i din.

| Scenario | Resultat |
|---|---|
| Inaktivitetsgränsen | Läsaren öppen i 20 s utan interaktion, med gränsen kortad till 8 s: 8 s räknades. Efter ett tryck och 5 s till: 11 s, på samma rad. |
| Samma logik med 5 minuter | JVM-test med styrd klocka: 7 minuter öppen ger 300 s, och `endedAt` är fem minuter efter start. |
| Tvångsstopp mitt i läsning | Läsning i cirka 30 s med ett sidbyte, sedan `am force-stop`. I en ny process fanns sessionen kvar med 30 s aktiv tid och sidorna 1–2, och Statistik visade lästiden. |
| Statistik med kända data | 3 lästa nummer, 2 påbörjade, 82 sidor och 4 h 37 min, som förväntat. Veckostapeln visade 42 min, årgång 1986 visade 27 % och ett tryck öppnade årgångsvyn. |
| Tomt läge | "Inget läst ännu" med förklaring, och årgångarna med noll. |

Jag körde inte läsaren i sju riktiga minuter. Gränsen kortades i det instrumenterade testet, och femminutersfallet är prövat med styrd klocka. Det som inte är prövat på telefonen är alltså just väntan på fem minuter, inte logiken.

**Skärmbilder** i `docs/screenshots/fas10/`: 01 tomt läge (testdata), 02 nyckeltal och aktivitet (testdata), 03 årgångar och övrigt (testdata), 04 dina riktiga data, 05 dina riktiga data längre ned.

Med dina data visar fliken 0 lästa nummer, 2 påbörjade, 4 lästa sidor och 1 min lästid, med hela lästiden i vecka 41.

## 6. Testresultat

| Test | Resultat |
|---|---|
| JVM-tester | 389 av 389 (361 före fasen) |
| Python-tester | 172 av 172 |
| Lint | 0 fel, 8 varningar |
| Instrumenterad svit på telefonen, isolerad lagring | 52 av 52 |
| Omstartstestet | 2 av 2 delar |
| Tvångsstopp under läsning | 2 av 2 delar |
| Release-bygge | bygger; inte kört på telefonen |

Nya JVM-tester: aktiv tid med inaktivitetsgräns, bakgrund och rotation; löpande sparning, återhämtning efter en avbruten session och städning av korta sessioner; alla statistikdefinitioner med kontrollerade data, med manuell markering, okänt sidantal och saknade nummer; veckoindelning över årsskiftet 2026–2027 (vecka 53) och i lokal tid; och att sökord bara loggas när det är begärt.

## 7. Acceptanskriterier

- [x] siffrorna stämmer mot kontrollerade testdata (JVM och på telefonen)
- [x] vyn fungerar med tom databas

## 8. Kända begränsningar

- **Äldre sessioner har väggklockstid.** De två sessioner du hade före fasen är mätta på det gamla sättet och kan vara något för långa.
- **En session räknas till den vecka den började**, även om den går över midnatt till måndag.
- **Den som läser en sida i mer än fem minuter utan att röra skärmen** får inte den överskjutande tiden räknad.
- **Vid lika många lästa sidor** vinner den tidigaste årgången som "mest lästa". Med dina data är det 1988 och 1991 som har två sidor var.
- **Väntan på fem riktiga minuter är inte prövad på telefonen**, se avsnitt 5.
- **Statistiken räknas om varje gång något ändras.** Med 185 nummer och en handfull sessioner märks det inte; med tusentals sessioner är det inte mätt.
- **Inaktivitetsgränsen går inte att ställa in i appen.** Testerna ändrar den genom `AppContainer`.
- Release-varianten är inte körd på telefonen, och Android 8–13 är fortfarande oprövat.

## 9. ADR

- **0021 Lässessioner med aktiv tid, statistikens definitioner och isolerade tester** – ny, Accepted.

## 10. Återstår, och förslag inför FAS 11

Ingenting återstår i fasen. FAS 11 är inte påbörjad.

1. **HQ-källan** (ADR 0009 är preliminär): bestäm om HQ ska hämtas från Original Scans via FTP eller från Large PDF. Det avgör om appen behöver en FTP-klient alls.
2. **Sidmappningen:** 183 av 185 nummer har samma sidantal i PDF:en som bland skanningarna. 1986 nr 4 (bilaga på 16 sidor) och 1993 nr 3 (68 mot 72 sidor) behöver en egen mappning eller undantas från HQ.
3. **Belastningen på arkivservern:** HQ-bilderna är stora. Bestäm en gräns för samtidiga hämtningar och om HQ-sidor ska cachas, och i så fall hur mycket.
4. **Testerna för HQ** kan använda samma isolerade lagring. En FTP-server går inte att nå i ett JVM-test, så protokollet bör ligga bakom ett gränssnitt som går att ersätta.
5. **Gradle 9.8.0** finns. En uppdatering passar före FAS 11.
