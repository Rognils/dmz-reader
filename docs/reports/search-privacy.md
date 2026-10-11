# Rapport – maskerat sökindex för distribution

> **Skärmbilderna som nämns här finns inte i repot.** De ligger lokalt hos projektets ägare, se [docs/skarmbilder.md](../skarmbilder.md).

Datum: 2026-10-11 · Grenar: `tools/privacy-filter`, `tools/privacy-filter-p2` och `tools/privacy-filter-p3`, mergade till `main` · Filter: `privacyFilterVersion` 3 · Arkivversion 2026-10-06-006

**Den gällande filen är `search-2026-10-06-006-p3.db`, se avsnitt 12.** Avsnitt 1–10 beskriver version 1 (p1) och avsnitt 11 version 2 (p2), som p3 bygger vidare på. Prereleaserna p1 och p2 finns inte i det nya repot.

Rapporten citerar inga personuppgifter. Stickproven som bedömningen bygger på ligger utanför repot, i `~/dmz-privacy-stickprov/`.

## 1. Sammanfattning

Sökindexet som delas ut är byggt, kontrollerat och upplagt som prerelease i det privata repot. Den omaskerade `dist/search.db` är orörd och har inte laddats upp någonstans.

| | |
|---|---|
| Fil | `search-2026-10-06-006-p1.db`, 90 980 352 byte (91 MB) |
| SHA-256 | `1256339bf39679c21581f1b620f4846918450ad7ba1ddd0cd2913ff0acf5f2e6` |
| Innehåll | 185 nummer, 11 293 sidor |
| Maskerat | 46 331 telefonnummer, 1 937 postgironummer, 309 e-postadresser, 12 personnummerformer |
| Berörda sidor | 5 025 av 11 293 (44 %) |
| `rawText` | tom på varje rad |
| Falska träffar i stickproven | telefon 0 av 150, postgiro 0 av 133, e-post 1 av 60, personnummerform 0 av 12 |
| Sökfacit | 43 av 43 före och efter, 0 irrelevanta före och efter |
| Integritetskontroll | 0 träffar med SQL, 0 i `strings`-utdata, 0 i råa bytes |
| Två byggen | byte-identiska |

**Det filtret inte tar:** uppskattningsvis 3 % av telefonnumren med riktnummer, som är skadade av texttolkningen, samt nummer utan riktnummer, utländska nummer, bankgironummer, namn och postadresser. Se avsnitt 6.

## 2. Textkolumnerna

`search.db` har två textkolumner i tabellen `pages`:

| Kolumn | Innehåll | Läser appen den? | I distributionsfilen |
|---|---|---|---|
| `searchText` | texten normaliserad för sökning | Ja. FTS-indexet är byggt över den, och utdragen i sökträffarna hämtas ur den. | maskerad |
| `rawText` | texten som den kom ur PDF:en | Nej. Ingen fråga i appen läser kolumnen. | **tom** |

Kolumnen `rawText` finns kvar och schemat är oförändrat. Appen kräver tabellerna `meta`, `issues`, `pages` och `pages_fts`, och läser ur `pages` bara `id`, `issueId` och `pageNumber`.

Filen blev 91 MB i stället för 149.

## 3. Filtret

`tools/privacy_filter.py`, `PRIVACY_FILTER_VERSION = 1`. Det körs i `build_search_index.py --privacy-filter` på varje sidas `searchText`, innan raden skrivs och innan FTS-indexet får texten. Det är deterministiskt.

### Markeringen

Alla kategorier byts mot `•••`. Markeringen har inga bokstäver eller siffror, så den ger inga ord i indexet.

Verifierat på tre sätt:

- **Tokeniseraren:** en text med bara markeringar ger noll ord i FTS5 med `unicode61 remove_diacritics 0` (test).
- **Normaliseringen:** `normalize_page` lämnar markeringen orörd och lägger inte till något (test).
- **Den färdiga filen:** indexets ordlista har 333 918 ord, och inget av dem innehåller tecknet `•`.

Sökorden som den förra markeringen förstörde ger nu samma antal sidor som i det omaskerade indexet:

| Sökord | Omaskerat | Maskerat |
|---|---|---|
| telefon | 1 253 | 1 253 |
| epost | 2 | 2 |
| e-post | 10 | 10 |
| postgiro | 294 | 295 |
| personnummer | 88 | 88 |

"postgiro" får en sida till, eftersom ordet på ett ställe stod hopskrivet med numret och nu står för sig.

**En sak att känna till:** `•••` förekommer redan i tidningarnas text, som punktlistor och utfyllnad, på 362 sidor. I den maskerade filen finns teckenföljden på 5 162 sidor. README säger att `•••` i ett sökutdrag betyder en maskerad kontaktuppgift. Det stämmer nästan alltid, men inte alltid.

### Kategorierna

**E-postadresser**

- en vanlig adress: namn, `@`, en domän med minst en punkt
- en adress som texttolkningen har dragit isär, med mellanslag kring `@` eller inne i domänen, om den slutar på en känd toppdomän inom 25 tecken

**Telefon- och faxnummer**

- riktnummer (0 och 1–3 siffror), skiljetecken `-` eller `/`, och ett abonnentnummer på minst 5 siffror i grupper. Högst 8 siffror efter ett tvåsiffrigt riktnummer, 7 efter tre och 6 efter fyra. Samma regel täcker 010, 020 och 07x.
- riktnummer inom parentes, och `+46` med eller utan `(0)`
- efter orden tel, telefon, tfn, fax, telefax, bbs, ring och mobil: också riktnummer följt av bara ett mellanslag, nummer i ett stycke, och nummer hopskrivet med ordet
- det som texttolkningen ställer till med: utspärrade siffror, `·` eller `*` i stället för bindestreck, och nummer hopskrivna med ordet före

**Postgironummer**

- två till åtta siffror, bindestreck och en kontrollsiffra, direkt efter ordet postgiro, postgirot eller pg, med eller utan nr, nummer eller konto
- mellan ordet och numret får det stå högst fyra tecken som inte är bokstäver eller siffror
- skiftlägesokänsligt, och ordet och siffrorna får vara utspärrade
- bara numret maskeras. Ordet står kvar.

**Personnummerform**

- `ÅÅMMDD-NNNN` och `ÅÅÅÅMMDD-NNNN`, där månaden är 01–12 och dagen 01–31
- bindestrecket krävs, eller plustecknet

**Med avsikt utanför:** fyra siffror efter riktnumret (tider som 0900-1700 och ISSN har den formen), nummer utan riktnummer, utländska nummer, bankgironummer, och beteckningar som 68EC030-25. Namn och postadresser maskeras inte.

## 4. Tester

`tools/tests/test_privacy_filter.py` och `test_privacy_audit.py`, 31 tester med påhittade nummer och adresser. Inget exempel kommer ur tidningarna.

- **Ska maskeras:** telefonnummer i 43 exempel, e-postadresser i 12, postgiro i 18, personnummerform i 6.
- **Ska inte maskeras:** 1989, 499 kr, 1 995:-, Commodore 128, 68000, 512 KB, C-64, Nr 12, postnummer, datum, poäng, artikelnummer, versionsnummer, klockslag, ISSN, ISBN, minnesadresser, FidoNet-adresser, modemhastigheter, processorbeteckningar, organisationsnummer, bankgiro, personnummerformer med omöjligt datum eller fel längd. Sammanlagt 92 exempel.
- **Egenskaper:** en maskerad text har inget kvar att maskera, också när ett nummer bara maskeras till en del. Maskeringen ger samma resultat varje gång.
- **Databasen:** `searchText` maskeras, `rawText` är tom, numren finns ingenstans i filens bytes, `meta` får filtrets version, ett omaskerat bygge är oförändrat, och två maskerade byggen är byte-identiska.
- **Kontrollverktyget:** en omaskerad fil faller på varje kontroll, text i en ledig sida hittas, och bytes i FTS-indexet som liknar en adress redovisas med läge och hex.

Alla Python-tester: 237 av 237.

## 5. Mätning och stickprov

Mätt med `tools/privacy_audit.py measure` på den omaskerade `dist/search.db`, i `searchText`:

| Kategori | Träffar | Sidor |
|---|---|---|
| Telefonnummer | 46 331 | 4 810 |
| Postgironummer | 1 937 | 740 |
| E-postadresser | 309 | 172 |
| Personnummerform | 12 | 9 |
| Någon kategori | | 5 025 |

De allra flesta telefonnumren hör till företag: butiker, postorder och BBS:er i annonser och listor. Privatpersoners nummer finns i radannonserna. Jag har inte räknat fördelningen.

### Stickproven

Dragna med fast frö av `privacy_audit.py sample`, och sparade med mina bedömningar i `~/dmz-privacy-stickprov/`.

| Kategori | Granskade | Rätt | Falska | Gräns |
|---|---|---|---|---|
| Telefonnummer | 150 | 150 | 0 | 3 % |
| Postgironummer | 120, och de 13 som tillkom sist | 133 | 0 | 3 % |
| E-postadresser | 60 | 59 | 1 | |
| Personnummerform | alla 12 | 12 | 0 | 3 % |

- **Telefon:** noll falska av 150 betyder med 95 % säkerhet under 2 % i hela korpusen. Tre träffar är delvis maskerade: texttolkningen har delat numret så att ett par siffror står kvar.
- **Postgiro:** alla 133 är postgironummer efter ordet. En del hör till företag och insamlingar, inte privatpersoner. Regeln skiljer inte på dem.
- **E-post:** den falska träffen är brus ur en bild som råkar ha formen av en adress. De flesta adresserna är redaktionens egna.
- **Personnummerform:** åtta står som kontonummer efter postgiro eller pg, det gamla personkontot där kontonumret var personnumret. Fyra står efter "P-nr" i exempeldata till ett registerprogram och kan vara påhittade. Alla tolv har rätt form, och ingen är något annat, till exempel ett artikelnummer.

Filtret ändrades flera gånger efter att telefonstickprovet drogs. Jag drog inte ett nytt varje gång, utan granskade de nya reglernas träffar för sig: 82 efter nyckelord och med `*`, 40 efter en siffra och ett mellanslag, 60 hopskrivna med ordet före, och 71 isärdragna e-postadresser. De enda falska fanns bland de hopskrivna: processorbeteckningar och minnesadresser, som sedan undantogs.

**Känd falsk träff:** en felskriven modellbeteckning i en prislista maskeras som telefonnummer. Den uppstod när regeln för beteckningar gjordes oberoende av å, ä och ö, så att filtret ger samma svar på texten som på `strings`-utdata. Två eller tre liknande fanns redan.

## 6. Det filtret inte tar

### Missade telefonnummer, cirka 3 %

Ett stickprov på 200 av de drygt 50 000 siffersträngar med skiljetecken som står kvar i den maskerade texten gav sex missade telefonnummer med riktnummer. Uppräknat är det cirka 1 700, mot 46 331 maskerade. Osäkerheten är stor: mellan 1 och 6 % med 95 % säkerhet.

Alla sex är skadade av texttolkningen, på fyra sätt:

| Skada | Hur numret ser ut i texten | Varför det inte går att känna igen säkert |
|---|---|---|
| En siffra har blivit en bokstav | riktnumret innehåller ett D, O, S eller l | En regel som godtar bokstäver i riktnummer träffar artikelnummer, minnesadresser och programkod. |
| Riktnumret är delat av ett mellanslag | tre siffror, mellanslag, en siffra och bindestreck | Samma form har prislistor och tabeller, där tal står bredvid varandra. |
| Numret är avhugget | riktnummer och bara två till fyra siffror | Kortare än ett telefonnummer. Tider, ISSN och intervall har samma form. |
| En siffra för mycket först | fem siffror före bindestrecket | Riktnummer börjar på 0. Utan den regeln träffas postnummer och artikelnummer. |

Jag har inte gjort reglerna mer aggressiva för dem. Varje sådan regel skulle maskera mer av den text som sökningen är till för: priser, modeller och listningar.

### Utanför kategorierna

Uppskattat ur samma stickprov på 200:

| | Antal av 200 | Uppskattat i korpusen |
|---|---|---|
| Utländska nummer | 5 | cirka 1 300 |
| Nummer utan riktnummer | 1, och 3 osäkra | 300–1 100 |
| ISBN | 1 | |
| Inte nummer alls (priser, modeller, programkod, datum) | 174 | |

Stickprovet drogs innan postgiro lades till, och tio av de 200 var då postgironummer. De maskeras nu.

- **Bankgironummer** maskeras inte. Uppgiften gällde postgiro.
- **Postgironummer utan ordet före,** till exempel i en tabell, maskeras inte.
- **Namn och postadresser** maskeras inte.

## 7. Stoppreglerna

| Villkor | Utfall |
|---|---|
| Högst 3 % falska telefonträffar | 0 av 150 |
| Högst 3 % falska postgiroträffar | 0 av 133 |
| Högst 3 % falska personnummerformer | 0 av 12 |
| Sökfacit inte försämrat | 43 av 43 före och efter, 0 irrelevanta före och efter |

**En reservation om facit:** antalet träffar och antalet hittade sidor är detsamma, men träffarnas ordning ändras något, åt båda håll. 15 av 43 sökord får en förväntad sida på en annan plats, oftast en till tre platser bort. "Commodore 128" går från plats 1 till 2, "Amiga" från 6 till 4 och "för" från 32 till 26. Orsaken är att sidorna blir kortare när nummer tas bort, och rangordningen väger in sidans längd. Jag har inte räknat det som försämrat facit, eftersom ingen förväntad sida försvinner och rörelsen går åt båda håll. Vill du räkna det annorlunda är det här stället att säga till.

## 8. Bygget och kontrollen

Byggt från PDF:erna med `--all --privacy-filter --built-at 2026-10-11T00:00:00Z`. Texten maskeras innan den skrivs, och databasen avslutas med `VACUUM`. Ingen befintlig databas har uppdaterats.

| | |
|---|---|
| Fil | `dist/search-2026-10-06-006-p1.db` |
| Storlek | 90 980 352 byte |
| SHA-256 | `1256339bf39679c21581f1b620f4846918450ad7ba1ddd0cd2913ff0acf5f2e6` |
| Två byggen | byte-identiska (`cmp`) |

Metadata i filen:

| Nyckel | Värde |
|---|---|
| `inventoryDatasetVersion` | 2026-10-06-006 |
| `normalizerVersion` | 2 |
| `privacyFilterVersion` | 1 |
| `searchSchemaVersion` | 1 |
| `lexiconVersion` | 1 |
| `tokenizer` | unicode61 remove_diacritics 0 |
| `issueCount`, `pageCount` | 185, 11293 |
| `builtAt` | 2026-10-11T00:00:00Z |

Källornas SHA-256 står i tabellen `issues`, en rad per nummer med PDF:ens storlek, ETag och SHA-256, för alla 185.

### Sökfacit

| | Hittar alla förväntade sidor | Irrelevanta träffar |
|---|---|---|
| Omaskerat (`dist/search.db`) | 43 av 43 | 0 |
| Maskerat (`-p1`) | 43 av 43 | 0 |

### Integritetskontrollen

`tools/privacy_audit.py verify`, och dessutom det riktiga verktyget `strings`:

| Kontroll | Träffar |
|---|---|
| Filtret på varje rad i `searchText` och `rawText` (SQL) | 0 |
| `rawText` tom på varje rad | ja |
| Filtret på `strings -n 4` av filen | 0 |
| Filtret på filens alla bytes | 0 |
| FTS-indexet stämmer med texten | ja |

Inga träffar i råa bytes behövde alltså verifieras som binärdata. Verktyget gör den verifieringen när det behövs och redovisar då läge och hex. Ett tidigare provbygge hade en sådan träff: sex bytes i FTS-indexets packade data som råkade ha formen av en e-postadress med en domän på ett enda tecken. Regeln för e-postadresser kräver nu minst två tecken i domänen. Inga adresser i korpusen berördes.

**Kontrollen hittade riktiga fel på vägen,** och det är värt att veta hur:

- Den första råa sökningen visade sju telefonnummer som stod hopskrivna med ordet före, eller följdes av ett tankstreck.
- `strings` delar texten vid å, ä och ö. Det visade tretton postgironummer som stod efter ett annat skiljetecken än regeln tillät, eller hopskrivna med ordet före.
- SQL-kontrollen visade ett fall där resten av ett delvis maskerat nummer gav en ny träff.

Alla tre är rättade, och filen är byggd om från grunden efter varje ändring.

### Prov i appen

Importerad i debug-appen genom filväljaren, som en användare gör:

- "Sökindexet är importerat: 185 nummer och 11293 sidor, på 1,6 sekunder."
- Inställningar visar arkivversion 2026-10-06-006, normalisering version 2, 185 nummer, 11 293 sidor, 91 MB.

| Sökning | Resultat i appen |
|---|---|
| telefon | 1 253 sidor i 184 nummer |
| ordertelefon | 365 sidor i 168 nummer. Utdragen visar `•••` där numren stod. |
| postgiro | 295 sidor i 144 nummer |
| personnummer | 88 sidor i 57 nummer |
| epost | 2 sidor i 2 nummer |

Skärmbild: `docs/screenshots/search-privacy/01-sokutdrag-med-markering.png`. Release-appen är inte rörd. Du importerar själv.

**Två texter stämmer inte längre:** appens inställningssida och backloggens specifikation säger "cirka 150 MB". Filen är 91 MB. Det är en textändring för 1.0.0.

## 9. Uppladdningen

Prerelease i det privata repot `Rognils/dmz-reader`:

| | |
|---|---|
| Tagg | `search-index-2026-10-06-006-p1` |
| Fil | `search-2026-10-06-006-p1.db` |
| Markerad som | prerelease |

Release-texten innehåller SHA-256 och storlek, arkivversion, `normalizerVersion` och `privacyFilterVersion`, antal nummer och sidor, de fyra maskerade kategorierna, att `rawText` är tom, och att rättigheterna till texten tillhör respektive rättighetsinnehavare.

APK:n är inte uppladdad. Repot är privat.

## 10. Kvar att ta ställning till

- **Facit:** om de små ändringarna i träffarnas ordning är godtagbara.
- **Markeringen:** `•••` finns också i tidningarnas egen text på 362 sidor.
- **Bankgiro** och **nummer utan riktnummer:** utanför filtret i dag. Ändras reglerna efter att filen har delats ut publikt blir det `privacyFilterVersion` 2 och en ny fil.
- **"Cirka 150 MB"** i appens text och i backloggen.

## 11. Version 2 (p2): bankgiro och telefonnummer utan riktnummer

Två kategorier till, båda bundna till ett ord före numret. Ingen av dem är en allmän sifferregel.

| | |
|---|---|
| Fil | `search-2026-10-06-006-p2.db`, 90 968 064 byte (91 MB) |
| SHA-256 | `7710b864cff9c9d1bcdbdd2a2b885466e112401abaa6306a7ed054b347796b90` |
| `privacyFilterVersion` | 2 |
| Maskerat | 46 331 telefonnummer, 833 telefonnummer utan riktnummer, 1 937 postgironummer, 74 bankgironummer, 309 e-postadresser, 12 personnummerformer |
| Berörda sidor | 5 074 av 11 293 |
| Sökfacit | 43 av 43 före och efter, 0 irrelevanta, ingen förväntad sida tappad |
| Integritetskontroll | 0 träffar med SQL, 0 i `strings`-utdata, 0 i råa bytes |
| Två byggen | byte-identiska |

### Reglerna

**Bankgiro:** tre eller fyra siffror, bindestreck och fyra siffror, direkt efter bankgiro, bankgirot, bg eller bg-nr (också bankgironummer och bankgirokonto). Skiftlägesokänsligt, och ordet och siffrorna får vara utspärrade. `bg` ska stå för sig: ett ord som slutar på bg räknas inte.

**Telefonnummer utan riktnummer:** fem till åtta siffror direkt efter tel, telefon, tfn, fax, telefax, bbs eller modem, med eventuell punkt och eventuellt kolon. Numret är antingen ett stycke eller grupper om högst tre siffror med mellanslag eller bindestreck.

- Ordet ska stå för sig. "telefonmodem", "BBS-program" och "telefonen" räknas inte.
- Numret tas inte om det följs av en enhet eller ett pris (`bps`, `baud`, `kr`, `st`, `:-`, `%`), ett snedstreck, en decimal eller ett `x`.
- De vanliga modemhastigheterna från 12000 till 115200 tas aldrig.
- Intervall som "1993-94" och "100-200" tas inte.

### Tester

36 tester i `test_privacy_filter.py` och `test_privacy_audit.py`, 242 Python-tester totalt, alla gröna. Nya påhittade fall:

- **Ska maskeras:** 8 för bankgiro och 13 för telefonnummer utan riktnummer.
- **Ska inte maskeras trots ordet intill:** 12 för bankgiro och 35 för telefon, bland dem "telefonmodem 2400", "BBS-program 1.2", "fax 9600 baud", "modem 14400", "fax 14400/v32", "fax 640x480", "BBS 1.200 kr" och "tel 1993-94".

### Stickproven

| Kategori | Träffar i korpusen | Granskade | Rätt | Falska |
|---|---|---|---|---|
| Bankgiro | 74 | alla 74 | 74 | 0 |
| Telefonnummer utan riktnummer | 833 | 150 | 149 | 1 (0,7 %) |

Bankgiro har färre än 100 träffar, så jag granskade alla. Nästan alla hör till insamlingar och företag.

Den falska telefonträffen är ett postnummer som står direkt efter ordet telefon, där texttolkningen har tappat själva numret.

**Kategorin heter "utan riktnummer", men innehåller mer än så.** Av de 149 rätta:

| Vad träffen är | Antal |
|---|---|
| Nummer utan riktnummer, som regeln var tänkt för | 57 |
| Nummer med riktnummer som texttolkningen har skadat, så att den vanliga regeln inte tog det | 67 |
| Början på ett utländskt nummer | 25 |

- **De skadade numren** är en vinst: de hörde till de cirka 3 % som p1 missade. Ofta står numrets två sista siffror kvar efter markeringen.
- **De utländska numren maskeras dåligt.** Regeln tar utlandsprefixet och landsnumret, och resten av numret står kvar. De var helt omaskerade i p1, så det är ingen försämring, men det är inte heller en maskering värd namnet.

### Sökord före och efter

| Sökord | Omaskerat | p2 |
|---|---|---|
| telefon | 1 253 | 1 253 |
| bankgiro | 67 | 67 |
| postgiro | 294 | 295 |

Träffarnas ordning ändras som i p1: 15 av 43 sökord får en förväntad sida på en annan plats, oftast en till tre platser bort.

### Prov i appen

Importerad i debug-appen genom filväljaren: "Sökindexet är importerat: 185 nummer och 11293 sidor, på 1,8 sekunder."

| Sökning | Resultat |
|---|---|
| telefon | 1 253 sidor i 184 nummer |
| bankgiro | 67 sidor i 47 nummer. Utdragen visar `•••` efter både Postgiro och Bankgiro. |
| postgiro | 295 sidor i 144 nummer |
| ordertelefon | 365 sidor i 168 nummer |

Skärmbild: `docs/screenshots/search-privacy/02-p2-bankgiro.png`.

### Releasen

- Prerelease `search-index-2026-10-06-006-p2` i det privata repot, med filen och release-texten.
- Prerelease `search-index-2026-10-06-006-p1` och dess tagg är borttagna.
- Filen ligger i telefonens Download som `search-2026-10-06-006-p2.db`, för din egen import. Jag har inte importerat den i release-appen.

### Kvar utanför filtret

- utländska nummer, utom den del som regeln ovan råkar ta
- nummer utan riktnummer som inte står direkt efter ett av orden, till exempel ett andra nummer efter "eller"
- post- och bankgironummer utan ordet före
- nummer som texttolkningen har skadat och som inte står efter tel eller fax
- namn och postadresser

## 12. Version 3 (p3): hela nummeruttrycket efter tel, fax och liknande ord

I p2 tog regeln för nummer utan riktnummer bara början av ett utländskt nummer. I p3 maskeras hela det sammanhängande nummeruttrycket när det står direkt efter tel, telefon, tfn, fax, telefax, bbs eller modem.

| | |
|---|---|
| Fil | `search-2026-10-06-006-p3.db`, 90 943 488 byte (91 MB) |
| SHA-256 | `11033d29553b59083dea94fde90c55bcf7a0cb23ef5452a4083f923903682dfc` |
| `privacyFilterVersion` | 3 |
| Maskerat | 46 126 telefonnummer med riktnummer, 2 081 nummer efter tel, fax och liknande ord, 1 937 postgironummer, 74 bankgironummer, 309 e-postadresser, 12 personnummerformer |
| Berörda sidor | 5 135 av 11 293 |
| Sökfacit | 43 av 43 före och efter, 0 irrelevanta, ingen förväntad sida tappad |
| Integritetskontroll | 0 träffar med SQL, 0 i `strings`-utdata, 0 i råa bytes |
| Två byggen | byte-identiska |

### Regeln

Uttrycket börjar direkt efter ordet, med eventuell punkt och eventuellt kolon. Det får innehålla ett plustecken först, siffergrupper, och grupper inom parentes. Mellan grupperna får det stå mellanslag, bindestreck eller snedstreck, och de tecken texttolkningen sätter i deras ställe (`·` och `*`). En parentes får sitta tätt intill siffrorna, som i `+49(0)…`. Uttrycket har minst 5 och högst 15 siffror.

Uttrycket tar slut före en grupp som:

- följs av en enhet, ett pris, en tid eller en decimal (`kr`, `:-`, `st`, `bps`, `baud`, `timmar`, `%`, `16.00`)
- följs av ett snedstreck och en bokstav, som i `14400/v32bis`
- är ett årtal efter ett mellanslag (1900–2099), när numret redan har fem siffror
- är en modemhastighet efter ett mellanslag, i ett inhemskt nummer
- är nästa nummers riktnummer
- skulle göra uttrycket längre än 15 siffror

Ett inhemskt nummer skrivet i ett stycke på minst sex siffror är färdigt där: det som följer är en annan kolumn i en lista.

Ordningen är: utländska nummer efter orden (de börjar med `+`, en parentes eller `00`) tas först och hela. Sedan tar den vanliga regeln nummer med riktnummer. Sist tas det som står kvar direkt efter orden.

### Tester

38 tester för filtret och kontrollverktyget, 247 Python-tester totalt, alla gröna. Nya påhittade fall:

- **Utländska nummer i 16 skrivsätt:** `009` och `00` som utlandsprefix, `+` med och utan mellanslag, riktnummer inom parentes med och utan mellanslag, bindestreck, snedstreck och `·` som skiljetecken.
- **Det som följer står kvar, 12 fall:** ord, priser, antal, årtal, hastigheter, tider, nästa nummer och en annan kolumn i en lista.
- **Fallen från p2** står kvar, och sex nya som inte ska maskeras: "modem 300 1200 2400", "fax 00-tal", "tel 007 är", "Tel: 00 12 kr", "Tel (se nedan)" och "Tel + moms".

### Stickproven

| Stickprov | Granskade | Utfall |
|---|---|---|
| De 25 utländska fallen från p2 | alla 25 | 24 maskeras hela. I ett har texttolkningen satt en bokstav mitt i numret, och siffrorna efter den står kvar. |
| Nya träffar efter orden | 130 | 128 rätt, 2 falska (1,5 %) |
| Träffar med parentes tätt intill siffrorna, tillkomna efter stickprovet | 45 av 79 | 45 rätt |

De två falska var modemhastigheter efter ordet modem: en utspärrad och en skriven som två hastigheter med snedstreck. Reglerna rättades för båda, och de står nu som testfall.

Stoppregeln: högst 3 % falska, och ingen förväntad sida tappad i facit. Båda är uppfyllda.

### Vad som ändrades mot p2

| | p2 | p3 |
|---|---|---|
| Telefonnummer med riktnummer | 46 331 | 46 126 |
| Nummer efter tel, fax och liknande ord | 833 | 2 081 |

Summan ökar med cirka 1 040. Den vanliga regeln tar färre, eftersom utländska nummer efter orden nu tas hela av den nya regeln i stället för till en del av den vanliga.

### Sökord, facit och prov i appen

| Sökord | Omaskerat | p3 |
|---|---|---|
| telefon | 1 253 | 1 253 |
| bankgiro | 67 | 67 |
| postgiro | 294 | 295 |

Träffarnas ordning ändras som förut för 15 av 43 sökord.

Importerad i debug-appen genom filväljaren: "Sökindexet är importerat: 185 nummer och 11293 sidor, på 1,6 sekunder." Sökningarna i appen ger samma antal som ovan, och utdrag med utländska nummer visar `Tel: •••` utan siffror efter.

### Kontrollen hittade ett fel till

`strings`-kontrollen av det första p3-bygget visade ett utländskt nummer som inte var maskerat alls: en parentes satt tätt intill siffrorna, och regeln krävde ett skiljetecken där. Regeln rättades, och 79 nummer till maskeras. Filen byggdes om från grunden.

### Kvar utanför filtret

- utländska nummer som inte står direkt efter ett av orden
- nummer som texttolkningen har delat med en bokstav: det som står efter bokstaven blir kvar
- nummer utan riktnummer som inte står direkt efter ett av orden, till exempel ett andra nummer efter "eller"
- post- och bankgironummer utan ordet före
- namn och postadresser

### Telefonen och releasen

- `search-2026-10-06-006-p2.db` är borttagen ur telefonens Download, och `search-2026-10-06-006-p3.db` ligger där, 90 943 488 byte. Jag har inte importerat den i release-appen.
- Prereleasen med p3 skapas i det nya repot, se `docs/reports/pre-1.0-del2.md`.
