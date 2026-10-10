# Rapport – maskerat sökindex för distribution

Datum: 2026-10-10 · Gren: `tools/privacy-filter` · Filter: `privacyFilterVersion` 1 · Arkivversion 2026-10-06-006

**Utkast. Del D1 är gjord. D2 (bygge och kontroll) är bara provkörd, och D3 (uppladdning) är inte gjord:** arbetet stannade före 1.0.0-rc3 när grindkörningen inte gav 10 av 10, se `docs/reports/gates.md`. Grenen är inte mergad, och ingen fil är uppladdad.

Rapporten citerar inga personuppgifter. Stickproven som bedömningen bygger på ligger utanför repot, i `~/dmz-privacy-stickprov/`.

## 1. Sammanfattning

Sökindexet som delas ut byggs nu med ett filter som byter e-postadresser mot `[e-post]` och telefonnummer mot `[telefon]` innan texten läggs i databasen. Den omaskerade `dist/search.db` är orörd och har inte laddats upp någonstans.

| | |
|---|---|
| Maskerade telefonnummer | 46 330, på 4 810 av 11 293 sidor (43 %) |
| Maskerade e-postadresser | 309, på 172 sidor |
| Falska telefonträffar i stickprovet | 0 av 150 |
| Falska e-postträffar i stickprovet | 1 av 60 |
| Uppskattad andel missade telefonnummer med riktnummer | cirka 3 %, alla skadade av texttolkningen |
| Sökfacit | 43 av 43 före och efter, 0 irrelevanta före och efter |

**Det filtret inte tar är inte litet.** Utanför de två kategorierna finns postgironummer till privatpersoner (uppskattningsvis 2 000–3 000), nummer utan riktnummer, utländska nummer och tolv teckenföljder som har formen av ett personnummer. Namn och postadresser maskeras inte, enligt uppgiften. Se avsnitt 5.

## 2. Textkolumnerna (D1.1)

`search.db` har två textkolumner i tabellen `pages`:

| Kolumn | Innehåll | Läser appen den? |
|---|---|---|
| `searchText` | texten normaliserad för sökning | Ja. FTS-indexet är byggt över den, och utdragen i sökträffarna hämtas ur den. |
| `rawText` | texten som den kom ur PDF:en | **Nej.** Ingen fråga i appen läser kolumnen. |

Appen kräver att tabellerna `meta`, `issues`, `pages` och `pages_fts` finns, och läser ur `pages` bara `id`, `issueId` och `pageNumber`.

Båda kolumnerna är maskerade i distributionsfilen, som uppgiften kräver.

**Förslag: uteslut `rawText` ur nästa distributionsfil.** Kolumnen är ungefär 57 MB av filens 148 och används inte av appen. Tom kolumn ger en fil på cirka 92 MB, och halverar mängden tidningstext som delas ut. Byggskriptet har redan flaggan `--without-raw-text`, som lämnar kolumnen tom men kvar i schemat, så appen behöver inte ändras. Jag har inte gjort det i den här filen, eftersom uppgiften bad om ett förslag.

## 3. Filtret (D1.2)

`tools/privacy_filter.py`, `PRIVACY_FILTER_VERSION = 1`. Det körs i `build_search_index.py --privacy-filter` på varje sidas `rawText` och `searchText`, innan raden skrivs och innan FTS-indexet får texten. Det är deterministiskt: samma text ger samma resultat.

**E-postadresser → `[e-post]`**

- en vanlig adress: namn, `@`, en domän med minst en punkt
- en adress som texttolkningen har dragit isär, med mellanslag kring `@` eller inne i domänen, om den slutar på en känd toppdomän inom 25 tecken

**Telefon- och faxnummer → `[telefon]`**

- riktnummer (0 och 1–3 siffror), skiljetecken `-` eller `/`, och ett abonnentnummer på minst 5 siffror i grupper. Högst 8 siffror efter ett tvåsiffrigt riktnummer, 7 efter tre och 6 efter fyra. Samma regel täcker 010, 020 och 07x.
- riktnummer inom parentes
- `+46`, med eller utan `(0)`
- efter orden tel, telefon, tfn, fax, telefax, bbs, ring och mobil: också riktnummer följt av bara ett mellanslag, nummer i ett stycke, och nummer hopskrivet med ordet
- det som texttolkningen ställer till med: utspärrade siffror, `·` eller `*` i stället för bindestreck, och nummer hopskrivna med ordet före

Reglerna för var numret slutar står i modulens huvud. Kort: en siffergrupp räknas inte till numret om den gör numret för långt, är ett pris eller ett antal (`:-`, `kr`, `st`, `bps`), eller är nästa nummers riktnummer.

**Med avsikt utanför:** fyra siffror efter riktnumret (tider som 0900-1700 och ISSN har den formen), nummer utan riktnummer, utländska nummer, och beteckningar som 68EC030-25.

Filtret byggdes ut i fem omgångar efter vad stickproven och filkontrollen visade. Den första versionen följde bara formaten i uppgiften och missade uppskattningsvis 7 % av numren. Tilläggen är de tre sista punkterna ovan och regeln för isärdragna e-postadresser. Utan den regeln missades 71 av 309 adresser.

## 4. Tester (D1.3)

`tools/tests/test_privacy_filter.py` och `test_privacy_audit.py`, 23 tester med påhittade nummer och adresser. Inget exempel kommer ur tidningarna.

- **Ska maskeras:** 08-, 031-, 0303/, 070-, 010-, 020-, åtta siffror i Stockholm, `+46` i fyra skrivsätt, parentes, utspärrade siffror, `·` och `*`, nummer efter tel och fax utan skiljetecken, nummer hopskrivna med ordet före, e-postadresser i fem former och fem isärdragna.
- **Ska inte maskeras:** 1989, 499 kr, 1 995:-, Commodore 128, 68000, 512 KB, C-64, Nr 12, postnummer, datum i fyra former, poäng, artikelnummer, versionsnummer, klockslag, ISSN, ISBN (även utspärrat), minnesadresser, FidoNet-adresser, modemhastigheter, postgironummer, processorbeteckningar. Sammanlagt 60 exempel.
- **Egenskaper:** en maskerad text har inget kvar att maskera, och maskeringen ger samma resultat varje gång.
- **Databasen:** båda kolumnerna maskeras, numren finns ingenstans i filens bytes, `meta` får filtrets version, ett omaskerat bygge är oförändrat, och två maskerade byggen är byte-identiska.

Alla Python-tester: 229 av 229 (4 hoppas över när den färdiga databasen inte finns i katalogen).

## 5. Mätning över hela korpusen (D1.4)

Mätt med `tools/privacy_audit.py measure` på den omaskerade `dist/search.db`.

| Kolumn | Telefonnummer | Sidor | E-postadresser | Sidor | Sidor med någon maskering |
|---|---|---|---|---|---|
| `searchText` | 46 330 | 4 810 | 309 | 172 | 4 886 |
| `rawText` | 43 329 | 4 707 | 314 | 180 | 4 791 |

Skillnaden mellan kolumnerna beror på att `rawText` har radbrytningar och utspärrad text som normaliseringen har rättat i `searchText`. Varje kolumn maskeras för sig.

De allra flesta numren hör till företag: butiker, postorder och BBS:er i annonser och listor. Privatpersoners nummer finns i radannonserna. Jag har inte räknat fördelningen.

### Stickprovet

Draget med fast frö av `privacy_audit.py sample`, och sparat med mina bedömningar i `~/dmz-privacy-stickprov/v1/`.

| Stickprov | Antal | Rätt | Falska |
|---|---|---|---|
| Telefonträffar | 150 | 150 | 0 |
| E-postträffar | 60 | 59 | 1 |

- Den falska e-postträffen är brus ur en bild som råkar ha formen av en adress.
- Tre träffar är delvis maskerade: texttolkningen har delat numret eller adressen så att ett par tecken står kvar efter markeringen.
- Noll falska av 150 betyder inte noll i hela korpusen. Med 95 % säkerhet ligger andelen under 2 %, alltså under gränsen på 3 %.

Filtret ändrades efter att stickprovet drogs, i fyra små steg. Jag drog inte ett nytt stickprov varje gång. I stället granskade jag de nya reglernas träffar för sig: 82 träffar efter nyckelord och med `*`, 40 träffar efter en siffra och ett mellanslag, 60 nummer hopskrivna med ordet före, och 71 isärdragna e-postadresser. De enda falska fanns bland de hopskrivna: processorbeteckningar och minnesadresser, som sedan undantogs. Två eller tre felskrivna modellbeteckningar av 84 maskeras fortfarande.

### Det som inte maskerades

Ett stickprov på 200 av de 55 224 siffersträngar med skiljetecken som står kvar i den maskerade texten:

| Bedömning | Antal av 200 | Uppskattat i korpusen |
|---|---|---|
| Inte ett telefonnummer (priser, modeller, programkod, datum) | 174 | |
| **Missat telefonnummer med riktnummer** | 6 | cirka 1 700 |
| Utländskt nummer | 5 | cirka 1 400 |
| Nummer utan riktnummer | 1, och 3 osäkra | 300–1 100 |
| Postgiro eller bankgiro | 10 | cirka 2 800 |
| ISBN | 1 | |

- **De sex missade** är alla skadade av texttolkningen: en siffra har blivit en bokstav, riktnumret är delat mitt i, eller numret är avhugget. Mot 46 330 maskerade ger det en miss på cirka 3,5 %. Osäkerheten är stor: mellan 1 och 6 % med 95 % säkerhet.
- **Siffrorna är grova.** Sex träffar i ett stickprov bär inte mer än en storleksordning.

### Utanför filtrets två kategorier

Det här bad uppgiften inte om, men det hör till bilden av vad filen innehåller:

- **Postgiro- och bankgironummer.** Radannonser ber ofta om betalning till ett postgiro, och numret står bredvid ett namn. En sökning efter orden postgiro, pg och bankgiro följt av ett nummer ger 1 777 träffar på 601 sidor.
- **Teckenföljder med personnummers form:** 12 stycken (sex siffror som är ett giltigt datum, bindestreck, fyra siffror). Minst en står som kontonummer i en annons. Jag har inte gått igenom alla tolv.
- **Nummer utan riktnummer** och **utländska nummer**, enligt tabellen.
- **Namn och postadresser** maskeras inte.

Ett filter i version 2 skulle kunna ta postgiro, bankgiro och personnummerformen. Det är tre väl avgränsade mönster.

## 6. Stoppunkten (D1.5)

| Villkor | Utfall |
|---|---|
| Andel falska telefonträffar högst 3 % | 0 av 150 |
| Ingen negativ påverkan på `search_golden.json` | 43 av 43 före och efter, 0 irrelevanta före och efter, samma antal träffar för varje sökord |

Bygget fick fortsätta.

**En reservation om facit:** träffarnas ordning ändras något, åt båda håll. 14 av 43 sökord får en förväntad sida på en annan plats, oftast en till tre platser bort. "Commodore 128" går från plats 1 till 2 och "Amiga" från 6 till 4. Orsaken är att sidorna blir kortare när nummer byts mot ett ord, och rangordningen väger in sidans längd. Jag bedömer inte det som negativ påverkan, men det är en ändring.

**En bieffekt som syns i appen:** markeringarna är vanliga ord i indexet. En sökning på "telefon" ger 5 000 sidor i stället för 1 253, och "e-post" 180 i stället för 10. Det går inte att undvika med de markeringar uppgiften anger. En markering utan bokstäver vore osynlig för sökningen, men säger mindre till läsaren.

## 7. Provbygget (inte den slutliga filen)

Ett bygge från PDF:erna med `--privacy-filter` och `--built-at 2026-10-10T00:00:00Z`, lagt utanför repot:

| | |
|---|---|
| Storlek | 148 246 528 byte |
| Nummer och sidor | 185 och 11 293 |
| Maskerat i `searchText` | 309 e-postadresser, 46 330 telefonnummer |
| `meta` | `privacyFilterVersion` 1, `normalizerVersion` 2, `inventoryDatasetVersion` 2026-10-06-006, `searchSchemaVersion` 1 |
| Källornas SHA-256 | i tabellen `issues`, som förut |
| `privacy_audit.py verify` | SQL: 0 träffar i båda kolumnerna. Läsbara teckenföljder och råa bytes: 1 träff, se nedan. |

**Den råa sökningen ger inte noll, utan en träff.** Sex bytes inne i FTS-indexets binära data råkar ha formen av en e-postadress. De står mellan styrtecken, finns inte i någon textkolumn och är inte text. Kontrollverktyget redovisar sådana träffar som upplysning i stället för fel, men bara om teckenföljden finns i ett FTS-block och inte i texten. Kravet "noll träffar" är alltså uppfyllt för text, inte bokstavligt för varje byte.

Filkontrollen hittade också riktiga missar i den första provfilen: sju nummer som stod hopskrivna med ordet före, eller följdes av ett tankstreck. De ledde till de två sista ändringarna i filtret.

Kvar att göra i D2: bygga den slutliga filen två gånger och jämföra byte för byte, köra facit mot den, och importera den i debug-appen. Siffrorna om facit i avsnitt 6 kommer från en provdatabas byggd av den maskerade texten, inte från ett bygge ur PDF:erna.
