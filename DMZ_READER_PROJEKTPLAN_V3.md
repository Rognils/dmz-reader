# DMZ Reader – Projektplan version 3

> Privat Android-app för att läsa, organisera, söka och utforska det digitaliserade arkivet från DMZArkivet (Datormagazin m.fl.).
>
> **Status: BASELINE (V3.1, fryst 2026-10-06).** Version 3 ersätter alla tidigare versioner. Den bygger på version 2 och lägger till rättelser kring databasintegritet, lokalt framtagen data, FTS5-stöd på Android, sidnumrering och versionsmatchning. Ändringar mot version 2 finns samlade i bilaga A.
>
> **Planen skrivs inte om.** Avvikelser som upptäcks under arbetet hanteras som ADR i `docs/decisions/` och som föreslagna ändringar i fasrapporten. Godkända ändringar förs in i bilaga E (ändringslogg efter baseline). Ingen ny huvudversion görs.

---

## Innehåll

- **Del A – Grund:** mål, avgränsning, arkitektur, grundregler
- **Del B – Arkivet:** datakällor och materialtyper
- **Del C – Datamodell:** tabeller, identiteter, sidnumrering
- **Del D – Dataformat och import:** inventory, search.db, versioner, importflöde
- **Del E – Appens funktioner:** UI, läsare, lagring, sök, backup
- **Del F – Faser:** FAS 0 till FAS 11, med acceptanskriterier
- **Del G – Arbetssätt:** regler för bygg-AI, rapportering, Git, tester
- **Bilagor:** ändringslogg, ADR-lista, öppna frågor

---

# DEL A – GRUND

## A1. Mål

DMZ Reader ska ge **den bästa möjliga privata läsupplevelsen för DMZArkivets digitaliserade tidskrifter**.

Appen ska kännas som ett modernt digitalt tidskriftsbibliotek – inte som en PDF-mapp, filhanterare eller FTP-klient.

Kärnan, i prioritetsordning:

1. snyggt bibliotek med omslag som visuella kort
2. tydliga årgångar
3. en riktigt bra läsare
4. läsposition och "Fortsätt läsa"
5. offline-läge
6. favoriter, bokmärken och anteckningar
7. backup av personlig data
8. fulltextsökning i hela arkivet (senare)
9. statistik (senare)
10. innehållsanalys och AI/RAG (långt senare)

Projektet byggs **fas för fas**. Varje fas ska vara användbar och stabil innan nästa påbörjas.

## A2. Avgränsning

Appen är endast för personligt bruk och installeras manuellt som APK.

Följande ska **inte** byggas:

- Google Play-publicering
- användarkonton, inloggning
- betalning, abonnemang, reklam
- molnbackend eller molnsynk
- synk mellan flera användare
- sociala funktioner, kommentarer, delning
- offentligt API
- telemetri

## A3. Systemarkitektur

Systemet består av tre delar:

```text
DMZARKIVET (FTP + ev. HTTPS)
        │
        ▼
┌───────────────────────────────┐
│ 1. PYTHON-TOOLCHAIN (datorn)  │
│  - inventering                │
│  - normalisering av filnamn   │
│  - publikationsregler         │
│  - textutvinning              │
│  - sökindex                   │
└───────────────┬───────────────┘
                │
        ┌───────┴────────┐
        ▼                ▼
archive_inventory.json  search.db
 (liten, följer med     (stor, importeras
  APK som baseline)      separat)
        │                │
        └───────┬────────┘
                ▼
┌───────────────────────────────┐
│ 3. ANDROID-APP                │
│  Bibliotek · Läsare · Sök     │
│  Favoriter · Statistik        │
│                               │
│  Room:                        │
│   Issue           (katalog)   │
│   IssueLocalState (telefonen) │
│   IssueUserState  (du)        │
│   Bookmark, ReadingSession    │
└───────────────────────────────┘
```

**Grundprincip:**

> All rå arkivlogik ligger i Python-toolchainen. Android-appen läser bara normaliserad data och behöver aldrig förstå DMZ-serverns katalogstruktur eller filnamn.

## A4. Fem absoluta regler

Dessa regler gäller i alla faser och får aldrig brytas.

1. **Användardata förstörs aldrig av katalogdata.** Import av nytt inventory eller search.db får aldrig radera, nollställa eller ändra `IssueUserState`, `Bookmark` eller `ReadingSession`.
2. **Inga främmande nycklar med kaskadradering** från användardata eller lokal data till `Issue`.
3. **Okända arkivformat gissas aldrig tyst.** De markeras som specialfall och rapporteras.
4. **Appen förstår aldrig rå FTP-struktur.** All sådan logik ligger i toolchainen.
5. **Arkivservern belastas försiktigt.** En anslutning åt gången, pauser, cache och ingen bulknedladdning från appen.

## A5. Teknik

| Område | Val |
|---|---|
| Språk | Kotlin |
| UI | Jetpack Compose |
| Databas | **Room 3** (`androidx.room3`, stabil 3.0.x) – inte Room 2 |
| SQLite-drivrutin | `BundledSQLiteDriver` (paketerad SQLite med FTS5) – verifieras i FAS 1 (ADR 0006) |
| Inställningar | DataStore |
| Bakgrundsjobb | JobScheduler User-Initiated Data Transfer (UIDT) på API 34+. På API 26–33 finns ingen bakgrundsnedladdning. |
| Bildladdning | Coil, omslag via HTTPS |
| PDF-rendering | Utvärderas i FAS 3; i första hand Androids `PdfRenderer` |
| minSdk | 26 |
| targetSdk | Aktuell standard när projektet skapas |
| Toolchain | Python 3, `ftplib`, `pdftotext` (Poppler), `sqlite3` |

---

# DEL B – ARKIVET

## B1. Datakällor

Huvudkällan är `https://dmzarkivet.se/`. Arkivet har även en publik FTP-server, enligt arkivets egen beskrivning:

- server: `dmzarkivet.se`
- användarnamn: `dmz`
- lösenord: `dmz`

Det publika kontot ska ändå läsas från konfiguration och inte hårdkodas på flera ställen.

**Vilka materialtyper som också finns via HTTPS ska inte antas.** Det verifieras i FAS 0.

## B2. Materialtyper

Arkivägarens egen beskrivning, med appens användning:

| Katalog | Arkivets beskrivning | Användning i appen |
|---|---|---|
| `Covers` | Omslagen, utplockade och tillsnyggade. Arbetet pågår. | Källa för toolchainens omslagsminiatyrer där filen är bearbetad (ADR 0016). Appen hämtar aldrig härifrån. |
| `OCR PDF` | Behandlade PDF:er, mindre och sökbara | **Primärt läsformat.** Källa för sökindex. |
| `Large PDF` | PDF i samma kvalitet som skanningarna, kan bli mycket stora | Möjlig HQ-källa, i andra hand |
| `Original Scans` | Skannade sidor, oftast i mycket högre upplösning | Möjlig HQ-källa, i första hand om sidorna ligger en och en |
| `Raw Images` | Obehandlade bilder direkt från skannern, osorterat | Ignoreras |
| `Uploads` | För bidrag | Ignoreras |

## B3. Omslag – tre nivåer

Appen prövar källorna i den här ordningen:

1. `coverAsset`: en färdig miniatyr som följer med appen
2. `coverUrl`: webbplatsens sidbild 1, hämtad via nätet och rättad enligt `coverFlags`
3. neutral platshållare med publikation, år och nummer i text

Toolchainen väljer källa för miniatyren (ADR 0016): ett bearbetat omslag från FTP-katalogen `Covers` om det finns, annars webbens sidbild 1 med rättningar i `coverFlags`. Transformordningen är alltid vridning, beskärning, skalning. Miniatyrerna är redan rättade, så appen tillämpar inga `coverFlags` på dem.

Appen använder aldrig FTP för omslag och laddar aldrig ned en PDF för att få ett omslag.

## B4. Kvalitetsläge

Användaren ser bara **Standard** och **HQ**, aldrig tekniska källnamn.

- **Standard** = OCR-PDF
- **HQ** = bästa tillgängliga källa, i den här ordningen:
  1. originalskanning för aktuell sida, om skanningarna ligger en sida per fil
  2. Large PDF
  3. Standard, om HQ saknas

Den slutliga HQ-källan beslutas efter FAS 0 (ADR 0009).

**Fastställt i FAS 11 (ADR 0009):** HQ är originalskanningen för aktuell sida, hämtad via FTP. Large PDF används inte. En sida utan säker motsvarighet bland skanningarna har ingen HQ, och visas som Standard med meddelandet "HQ finns inte för den här sidan".

## B5. Förväntade specialfall

Systemet ska förvänta sig och hantera:

- dubbelnummer och specialnummer
- avvikande filnamn
- flera publikationer
- saknade omslag, OCR-PDF:er eller Large PDF:er
- olika sidantal mellan källor
- extra tomsidor, försättsblad och baksidor
- oregelbunden uppslagsparning
- OCR-fel
- filer som byts ut i arkivet (omskanning)

FAS 0 dokumenterar vilka av dessa som faktiskt förekommer.

---

# DEL C – DATAMODELL

## C1. Tre slags data

Datamodellen delas i tre strikt åtskilda kategorier:

| Kategori | Tabell | Kommer från | Skrivs över vid import? | Ingår i backup? |
|---|---|---|---|---|
| Katalogdata | `Issue` | Inventory | **Ja** | Nej |
| Lokal data | `IssueLocalState` | Telefonen själv | Nej (kan återskapas) | Nej |
| Användardata | `IssueUserState`, `Bookmark`, `ReadingSession` | Användaren | **Aldrig** | **Ja** |

Separationen är projektets viktigaste arkitekturbeslut (ADR 0002).

## C2. Issue – katalogdata

Speglar `archive_inventory.json`. Ersätts helt vid import av ett nytt inventory.

```text
Issue
├── issueId             String, primärnyckel
├── publicationId       String, t.ex. "datormagazin"
├── publicationTitle    String, t.ex. "Datormagazin"
├── year                Int
├── issueLabel          String, visningstext, t.ex. "Nr 6–7"
├── sortKey             String, för ordning inom år
├── sourceKey           String, rå identitet från arkivet
├── sourceNames         List<String>, originalsökvägar
├── relatedIssueId      String?, t.ex. HighScore → motsvarande Datormagazin-nummer
├── coverAsset          String?, miniatyr som följer med appen, t.ex. "covers/datormagazin-1989-06.webp"
├── coverUrl            String?, webbens sidbild 1, null om den saknas
├── coverFlags          List<String>, rättningar av bilden på coverUrl, t.ex. "rotate_cw"
├── ocrPdfUrl           String?, kanonisk läs-PDF (webbplatsen), null om den saknas
├── ocrPdfSize          Long?, serverns Content-Length
├── ocrPdfEtag          String?
├── ocrPdfLastModified  String?
├── largePdfUrl         String?
├── largePdfSize        Long?
├── hqSource            String?, "scans" | "large_pdf" | null
├── scanPagePattern     String?, mönster för originalskanning per sida
├── scanPageCount       Int?, antal skanningar i numrets huvuddel
├── pageCount           Int?, nullable
├── spreadOffset        Int?, nullable
├── flags               List<String>, t.ex. "double_issue", "scan_supplement",
│                       "missing_cover", "missing_ocr_pdf"
├── hqPageMap           String?, JSON: skanningens filnamn per PDF-sida, eller null för
│                       en sida utan HQ. Bara för nummer där sida N inte är skanning N.
└── hqPageRotation      String?, JSON: grader medurs (90, 180, 270) för de sidor där
                        skanningen ligger vriden i förhållande till PDF-sidan
```

`hqPageMap` och `hqPageRotation` är valfria fält i inventory (FAS 11, ADR 0009 och 0022), framtagna med bildjämförelse i toolchainen. Saknas `hqPageMap` gäller `scanPagePattern` med sida N som skanning N, förutsatt att PDF:en har lika många sidor som det finns skanningar.

**Regler:**

- `pageCount` och `spreadOffset` är nullable. Ingen kod får anta att de finns.
- `coverUrl` och `ocrPdfUrl` är nullable. Ett nummer utan omslag har flaggan `missing_cover`, och ett nummer utan läs-PDF har flaggan `missing_ocr_pdf`.
- Omslaget tas i första hand från `coverAsset`, i andra hand från `coverUrl`, och sist visas en platshållare (B3).
- Appens Room-entitet är en delmängd av inventory: allt utom `sourceNames` (se FAS 1).
- `scanPageCount` är inte samma sak som `pageCount` och får inte användas i dess ställe.
- `Issue` innehåller aldrig personlig eller lokal data.

## C3. IssueLocalState – telefonens egen kunskap

Data som telefonen tar fram själv och som arkivet inte känner till. Den överlever import av nytt inventory.

```text
IssueLocalState
├── issueId                String, primärnyckel (ingen FK)
├── observedPageCount      Int?, uppmätt när PDF:en öppnades
├── observedPdfSize        Long?, storlek på den fil som mättes
├── downloadState          NONE | QUEUED | DOWNLOADING | DOWNLOADED | FAILED
├── downloadedPath         String?, permanent kopia i filesDir
├── downloadedBytes        Long?
├── downloadedAt           Long?
├── downloadQueuedAt       Long?, när nedladdningen begärdes; bestämmer ordningen i kön
└── detectedSpreadOffset   Int?, om appen själv upptäcker parningen
```

**Regler för effektivt sidantal:**

```text
effectivePageCount =
    Issue.pageCount
    ?: IssueLocalState.observedPageCount
    ?: null
```

- Om `observedPdfSize` skiljer sig från `Issue.ocrPdfSize` har filen bytts i arkivet. Då ignoreras det uppmätta sidantalet och mäts om, och appen markerar att bokmärken kan peka fel (se C7).
- `IssueLocalState` ingår inte i backup. Det kan återskapas genom att öppna eller ladda ned numret igen.

## C4. IssueUserState – personlig data

```text
IssueUserState
├── issueId          String, primärnyckel (ingen FK)
├── isFavorite       Boolean
├── lastPage         Int?, var "Fortsätt läsa" öppnar
├── maxPageReached   Int?, hur långt användaren kommit (högsta sida i seenPages)
├── seenPages        String?, lästa sidor som bitmängd (en bit per PDF-sidindex), lagrad som Base64-text
├── firstOpenedAt    Long?
├── lastOpenedAt     Long?
├── completedAt          Long?
├── readStatusOverride   READ | UNREAD | null (null = automatiskt)
└── updatedAt            Long
```

**Regler:**

- skrivs aldrig av import
- en manuell markering ändrar aldrig `lastPage`, `maxPageReached` eller `seenPages`. Läshistoriken bevaras.
- `maxPageReached` är alltid den högsta sidan i `seenPages`
- överlever att numret försvinner ur inventory
- exporteras och importeras via backup

## C5. Bookmark

Ett bokmärke är också det som tidigare kallades "favoritsida". Det finns bara ett system för sparade sidor.

```text
Bookmark
├── id                Long, primärnyckel (gäller bara i telefonens databas)
├── bookmarkId        String, UUID som skapas en gång och aldrig ändras
├── issueId           String (ingen FK)
├── pageNumber        Int, enligt C7
├── title             String?
├── note              String?
├── createdAt         Long
├── updatedAt         Long
├── pdfSize           Long?, PDF-versionen när bokmärket skapades (C7)
├── pdfEtag           String?
├── pdfLastModified   String?
└── deletedAt         Long?, mjuk radering
```

Att ta bort ett bokmärke sätter `deletedAt`. Ett bokmärke med `deletedAt` visas aldrig, men raden finns kvar så att en äldre backup inte kan återuppliva det. "Ångra" nollställer `deletedAt`. Borttagna bokmärken rensas inte automatiskt.

I UI:t kan bokmärken visas under Favoriter tillsammans med favoritnummer.

## C6. ReadingSession

```text
ReadingSession
├── id               Long, primärnyckel
├── issueId          String (ingen FK)
├── startedAt        Long
├── endedAt          Long
├── startPage        Int
├── endPage          Int
├── pagesViewed      Int, antal unika sidor visade i sessionen
└── durationSeconds  Int, aktiv lästid
```

En session skapas när läsaren öppnas. Raden uppdateras var 30:e sekund, två sekunder efter ett sidbyte, när appen går i bakgrunden och när läsaren stängs. Dör appen gäller den senaste skrivningen, och högst 30 sekunder går förlorade.

`durationSeconds` är **aktiv tid**: klockan går bara när läsaren är i förgrunden och det har förekommit en interaktion (sidbyte, tryck, zoom eller panorering) under de senaste 5 minuterna. `endedAt` är den sista stunden som räknades som aktiv.

En session **delas vid lokal midnatt**: den pågående avslutas 23:59:59 och en ny börjar 00:00 på samma nummer och sida (v0.10.1). Sessioner sparade före v0.10.1 är inte delade.

Rotation avslutar inte sessionen. Kommer läsaren tillbaka från bakgrunden inom 5 minuter fortsätter samma session, annars börjar en ny. En session som slutar med under 5 sekunder aktiv tid tas bort, och kvarlämnade korta sessioner städas när appen startar.

## C7. Sidnumrering

**Alla sidnummer i hela systemet är PDF-sidindex, där 1 är första sidan i OCR-PDF:en.**

Det gäller `lastPage`, `maxPageReached`, `Bookmark.pageNumber`, sökträffar i `search.db`, `spreadOffset` och HQ-mappningen mot originalskanningar.

Tidningens tryckta sidnummer är något annat. De kan visas som extra information senare, men lagras aldrig som referens.

**Om en PDF byts i arkivet** kan sidindex förskjutas. Bytet upptäcks med `ocrPdfSize`, `ocrPdfEtag` och `ocrPdfLastModified`: om någon av dem har ändrats kan filen ha bytts. Då ska appen:

- behålla alla bokmärken
- visa en diskret markering på numret: "Arkivfilen har uppdaterats – bokmärken kan peka fel"
- inte försöka flytta bokmärken automatiskt

Detsamma gäller ett permanent nedladdat nummer: filen raderas aldrig automatiskt. Nummervyn visar "Din nedladdade kopia är äldre" och knappen **"Uppdatera nedladdning"**. Den gamla kopian ligger kvar och går att läsa tills den nya är komplett hämtad.

## C8. issueId

`issueId` är en stabil strängidentifierare.

- Det exakta formatet fastställs i FAS 0 efter kartläggningen och dokumenteras i ADR 0007.
- Ingen information i originalnamnet får gå förlorad, eftersom `sourceKey` och `sourceNames` bevaras.
- Dubbelnummer och specialnummer måste kunna uttryckas.
- När formatet väl är fastställt är det permanent. En senare ändring kräver en aliastabell och migrering av användardata.

Exempel (bara illustration): `datormagazin-1989-06-07`

## C9. publicationId och publicationTitle

- `publicationId` är ett maskin-ID, t.ex. `datormagazin`. Det är stabilt och skrivs med små bokstäver, utan mellanslag.
- `publicationTitle` är visningsnamnet, t.ex. `Datormagazin`.
- Vilka publikationer som finns verifieras i FAS 0. Inga namn antas i förväg.

## C10. Läsprogress och lässtatus

- `lastPage` bestämmer var "Fortsätt läsa" öppnar.
- `seenPages` bestämmer hur mycket användaren har läst. `maxPageReached` är den högsta sidan i `seenPages`.

```text
progress = antal sidor i seenPages / effectivePageCount   (om sidantalet är känt)
```

En sida räknas som läst när den har visats i minst 1,5 sekunder i följd. I uppslag räknas båda sidorna. Snabbläddring via reglage, miniatyrer eller snabba svep räknas inte.

Status bestäms i två steg. Först gäller en eventuell manuell markering, annars den automatiska regeln.

| `readStatusOverride` | Status |
|---|---|
| `READ` | Läst |
| `UNREAD` | Oläst |
| `null` | automatiskt enligt tabellen nedan |

| Automatisk status | Regel |
|---|---|
| Oläst | `seenPages` är tom |
| Påbörjad | läst, men progress under 90 %, eller okänt sidantal |
| Läst | progress ≥ 90 % |

- Om sidantalet är okänt visas status utan procent.
- `completedAt` sätts första gången ett nummer blir "Läst", automatiskt eller manuellt.
- **Markera som läst** sätter `READ`. Den gäller tills användaren ändrar den.
- **Markera som oläst** sätter `UNREAD`. Numret visas då inte i "Fortsätt läsa".
- `UNREAD` nollställs automatiskt till `null` när en ny sida räknas som läst. Det är orimligt att ett nummer man läser står som oläst.
- **Återställ** sätter `null`.

---

# DEL D – DATAFORMAT OCH IMPORT

## D1. archive_inventory.json

Genereras av toolchainen. Följer med APK:n som baseline och kan ersättas genom import.

```json
{
  "schemaVersion": 1,
  "datasetVersion": "2026-10-06-001",
  "generatedAt": "2026-10-06T12:00:00Z",
  "generator": "archive_inventory.py 0.1.0",
  "publications": [
    { "publicationId": "datormagazin", "publicationTitle": "Datormagazin" }
  ],
  "issues": [
    {
      "issueId": "datormagazin-1989-12",
      "publicationId": "datormagazin",
      "publicationTitle": "Datormagazin",
      "year": 1989,
      "issueLabel": "Nr 12",
      "sortKey": "1989-12",
      "sourceKey": "198912",
      "sourceNames": ["OCR PDF/…", "Covers/…"],
      "relatedIssueId": null,
      "coverUrl": "…",
      "coverFlags": [],
      "ocrPdfUrl": "…",
      "ocrPdfSize": 39845120,
      "ocrPdfEtag": "…",
      "ocrPdfLastModified": "…",
      "largePdfUrl": "…",
      "largePdfSize": 310000000,
      "hqSource": "scans",
      "scanPagePattern": "…",
      "scanPageCount": 48,
      "pageCount": null,
      "spreadOffset": null,
      "flags": []
    }
  ]
}
```

Fälten är de som står i C2. Formatet beskrivs formellt i `docs/inventory.schema.json`.

## D2. search.db

Genereras av toolchainen i FAS 8 och importeras separat. Den följer inte med APK:n.

```text
meta        (key, value)
            searchSchemaVersion, inventoryDatasetVersion, normalizerVersion, lexiconVersion,
            tokenizer, builtAt, generator, sqliteVersion, pdftotextMode,
            issueCount, pageCount

issues      (issueId, pageCount, ocrPdfSize, ocrPdfEtag, ocrPdfSha256)

pages       (id, issueId, pageNumber, rawText, searchText)
            id är en heltalsnyckel; (issueId, pageNumber) är unik
pages_fts   FTS5-tabell över pages.searchText, med pages.id som rad-id
```

- `rawText` är texten som den kom ur PDF:en. `searchText` är normaliserad. FTS-indexet och textutdragen använder `searchText`.
- `issues` knyter varje nummer till den PDF som indexerades, så att appen kan upptäcka att just ett nummers fil har bytts sedan dess.
- `pages.id` behövs för att `VACUUM` inte ska kunna numrera om raderna under FTS-indexet.
- `normalizerVersion` anger vilken version av normaliseringen och dess ordlista som användes. Samma PDF och samma version ger alltid samma `searchText`.
- Tokenizer: `unicode61 remove_diacritics 0` (ADR 0010). FTS-version enligt ADR 0006.

## D3. Versioner

- **`schemaVersion`** beskriver datastrukturen. Appen vägrar importera en fil med en `schemaVersion` den inte stöder och visar ett tydligt felmeddelande.
- **`datasetVersion`** beskriver vilken ögonblicksbild av arkivet datan kommer från.

**Matchning mellan inventory och search.db är en varning, inte en spärr:**

- Om versionerna skiljer sig fungerar sökningen ändå. Sökvyn visar en diskret notis: "Sökindexet är från en annan arkivversion."
- Sökträffar på `issueId` som saknas i aktivt inventory döljs.
- Nummer som finns i inventory men saknas i search.db går inte att söka i, och det syns tydligt i nummervyn.

Det gör att ett nytt nummer kan läggas till i inventory utan att sökdatabasen på flera hundra MB måste byggas om.

## D4. Baseline och importerat inventory

Assets i APK:n är skrivskyddade. Därför:

```text
Finns giltigt importerat inventory i privat lagring?
├─ ja  → använd det
└─ nej → använd assets/archive_inventory.json
```

Det ska gå att återgå till baseline från inställningarna.

Appen sparar **källa** (inbyggt eller importerat) och **importdatum** för det aktiva indexet och visar dem i inställningarna tillsammans med `datasetVersion`.

## D5. Importflöde

Samma flöde används för både inventory och search.db:

```text
Filväljare (Storage Access Framework)
↓
kopiera till temporär fil i privat lagring
(öppna aldrig stora databaser direkt från en content-URI)
↓
validera: format, schemaVersion, obligatoriska fält,
          för inventory mot docs/inventory.schema.json,
          för search.db även PRAGMA integrity_check
          och att FTS-tabellen går att fråga
↓
byt aktiv fil atomiskt (rename)
↓
för inventory: ersätt Issue-tabellen i EN Room-transaktion
↓
rör aldrig IssueUserState, Bookmark, ReadingSession
↓
behåll föregående version tills den nya fungerar,
radera den sedan
```

Om något steg misslyckas ska den tidigare aktiva filen fortfarande gälla.

## D6. Användardata som saknar katalogpost

Om ett nummer försvinner ur inventory men har användardata:

- användardatan behålls
- numret visas i Favoriter och Bokmärken som "Saknas i aktuellt arkivindex"
- användaren kan ta bort posten manuellt
- om numret kommer tillbaka i ett senare inventory kopplas datan ihop igen automatiskt via `issueId`

---

# DEL E – APPENS FUNKTIONER

## E1. Navigation

Fyra flikar i botten:

- **Bibliotek**
- **Sök** (platshållare fram till FAS 9, metadata-sökning från FAS 7)
- **Favoriter** (platshållare fram till FAS 6)
- **Statistik** (platshållare fram till FAS 10)

En **Inställningar**-vy nås från biblioteket. Där finns import, backup, lagring och återgång till baseline.

## E2. Designprinciper

- mörk, ren, vuxen och modern
- tidningsfokuserad
- nostalgisk utan att bli retro-C64
- omslagen står för färgen
- få distraktioner, stora omslag

## E3. Designspecifikation

**Färger:**

| Roll | Färg |
|---|---|
| Bakgrund | `#0E0F12` |
| Ytor | `#16181D` |
| Kantlinjer | `#23252B` / `#2A2C33` |
| Huvudtext | `#ECE8DF` |
| Sekundär text | `#A9A499` |
| Etiketter | `#8E8A80` |
| Accent | `#F5B53D` |
| Läsarbakgrund | `#050506` |

**Typsnitt:**

- **Archivo** för rubriker (800) och brödtext (400/600)
- **IBM Plex Mono** för etiketter i versaler med extra teckenavstånd, metadata, sidnummer, procent och statusrader
- en liten `READY.`-etikett i mono och accentfärg ovanför appnamnet

**Övrigt:**

- tryckytor minst 48dp
- textkontrast minst 4.5:1
- typsnitten paketeras i appen, inte laddas från nätet

## E4. Biblioteksvy

Sektionerna visas uppifrån och ned. Sektioner utan innehåll döljs.

1. **Fortsätt läsa:** stort kort med omslag, publikation, år, nummer, "Sida X av Y" (eller bara "Sida X"), progressbar och "Fortsätt →"
2. **Årgångar:** rutnät med 3 kolumner. Varje kort visar årtal och ett progress-streck. Vid flera publikationer kan en publikationsväljare visas överst.
3. **Senast lästa:** horisontell rad med omslag
4. **Favoriter:** horisontell rad med omslag
5. **Nedladdat:** horisontell rad med omslag

## E5. Årgångsvy

- stort årtal och "X / Y lästa"
- filterchips i en horisontellt rullbar rad: Alla, Olästa, Påbörjade, Lästa, Offline
- omslagsrutnät i 3 kolumner, med format cirka 0.72
- per kort:
  - progress-streck och offline-ikon
  - favoritmarkering
  - lästa nummer tonade till cirka 60 %

## E6. Nummervy

- stort omslag på tonad bakgrund
- titel: publikation, år och nummer
- metadata i mono: sidantal (om känt), filstorlek, lässtatus, nedladdningsstatus
- primär åtgärd: **Läs** eller **Fortsätt på sida X**
- nummer med flaggan `missing_ocr_pdf` visar "PDF saknas" och ingen Läs-knapp
- sekundära åtgärder: Ladda ned/Ta bort, Favorit, Bokmärken, Sök i numret (från FAS 9), HQ (från FAS 11)
- lista över bokmärken i numret
- markeringar: "Arkivfilen har uppdaterats" (C7) och "Ingår inte i sökindexet" (D3)

## E7. Läsaren

Läsaren är projektets viktigaste funktion och prioriteras före allt utom biblioteket.

**Grundfunktioner:**

- helskärm med mörk bakgrund (`#050506`)
- svep mellan sidor
- tryckzoner: vänster 30 % ger föregående sida, höger 30 % ger nästa, mitten visar eller döljer kontrollerna
- pinch-to-zoom, dubbeltryck för zoom, panorering när sidan är zoomad
- stående och liggande läge
- automatisk sparning av läsposition

**Kontroller** (döljs automatiskt):

- topprad: stäng, titel, "Sida X av Y · Z %", bokmärke, HQ
- nedre rad: miniatyrremsa med aktuell sida i accentram, progressbar och snabbhopp till sida
- miniatyrremsan visar den faktiska PDF-sidan, renderad ur PDF:en. Omslagsrättningar (`coverFlags`) används aldrig i läsaren.

**Uppslagsläge** (liggande):

- två sidor sida vid sida
- parningen hårdkodas aldrig. Den bestäms av `spreadOffset` (från `Issue` eller `IssueLocalState`), annars standardregeln: sida 1 ensam, därefter 2–3, 4–5 …
- användaren kan förskjuta parningen ett steg manuellt om den blir fel. Valet sparas i `IssueLocalState.detectedSpreadOffset`.

**Prestanda:**

- rendera sida för sida, aldrig hela dokumentet
- förrendera högst ±1 sida (±1 uppslag)
- rendera i skärmupplösning och rendera om vid zoom
- återanvänd bitmaps och frigör dem tidigt
- inga krascher på stora PDF:er

## E8. Lagring

| Data | Plats | Rensas av systemet? |
|---|---|---|
| "Läs nu" (tillfällig PDF) | `cacheDir` | Ja |
| Renderade sidor, miniatyrer, HQ-sidor | `cacheDir` | Ja |
| Omslag (webbens sidbild 1) | Coils diskcache | Ja |
| "Ladda ned" (permanent PDF) | `filesDir/issues/` (ADR 0013) | Nej |
| Importerat inventory och search.db | `filesDir/data/` | Nej |
| Room-databasen | Standardplats | Nej |

**Regler:**

- inga breda lagringsbehörigheter. `filesDir` fungerar utan behörigheter.
- permanenta PDF:er lagras i `filesDir/issues/{issueId}.pdf`. Skälet står i ADR 0013.
- permanent nedladdning körs på API 34+ som ett UIDT-jobb och överlever att appen stängs. På API 26–33 sker nedladdningen medan appen är öppen, med en tydlig text om det. Ingen WorkManager-reserv byggs, eftersom den inte kan testas på någon tillgänglig enhet.
- "Läs nu" körs interaktivt med synlig förloppsindikator och kan avbrytas
- om en nedladdad fil finns används den alltid före nätet
- inställningar visar använt utrymme och kan rensa cache

Observera: app-specifik lagring raderas när appen avinstalleras. Därför finns backup (E11).

## E9. Nätverk

- HTTPS används där FAS 0 visar att det finns
- omslag hämtas via HTTPS (`coverUrl`)
- FTP används bara för HQ (Original Scans) och Large PDF, via en FTP-klient (t.ex. Apache Commons Net)
- om filer bara finns via FTP betyder "Läs nu": ladda ned till cache och öppna lokalt
- högst en FTP-anslutning åt gången, och nedladdningar köas
- **HQ (FAS 11, ADR 0022):** skanningarna hämtas genom ett utbytbart gränssnitt (`ScanSource`), i appen med Apache Commons Net: passivt läge, binärt, arkivets publika konto. Anslutningen återanvänds för följande sidor och stängs efter 30 sekunder utan användning. All HQ-trafik går genom `TransferCoordinator` som en läsning: högst en överföring mot arkivet åt gången, och en HQ-sida går före nedladdningskön. Bara sidan som visas hämtas, aldrig i förväg. HQ fungerar på mobildata. Hämtade sidor cachas i `cacheDir/hq/{issueId}/` med ett tak på 500 MB, och fungerar sedan utan nät.
- **en PDF-överföring åt gången i hela appen.** All PDF-trafik, både "Läs" och nedladdningskön, går genom en gemensam `TransferCoordinator`. "Läs" går före: kön pausas (den aktuella förfrågan avbryts och `.part` ligger kvar) och återupptas när läsningens nedladdning är klar. "Läs" på ett nummer som redan laddas ned väntar på den nedladdningen i stället för att hämta numret en gång till.
- en egen HTTP-spegel i hemmalabbet är en dokumenterad reservlösning, inte ett beslut. Den byggs bara om FAS 0 eller verklig användning visar att den behövs.

## E10. Sökning

Sökning byggs i två steg.

**Steg 1 – metadata (FAS 7).** Sök på år, nummer, publikation, favorit och nedladdat. Sökningen körs mot `Issue` och kräver inget sökindex.

**Steg 2 – fulltext (FAS 9).** När search.db är importerad:

```text
Sök: "Fairlight"

Datormagazin 1988 · Nr 7 · Sida 14
"…demogruppen Fairlight släppte…"
```

- träffarna rankas och visar ett textutdrag med sökordet markerat
- ett tryck öppnar rätt nummer på rätt sida
- om PDF:en inte finns lokalt laddas den till cache först
- i läsaren och nummervyn finns "Sök i numret", filtrerat på `issueId`

**FTS5 på Android:**

- **Primärt val: FTS5** via Room 3 och `BundledSQLiteDriver`. Room 3 har `@Fts5`, och den paketerade SQLite-drivrutinen stöder FTS5 oberoende av telefonens inbyggda SQLite. Det ger `bm25`-rankning, `snippet` och `highlight`.
- search.db öppnas som en separat, skrivskyddad databas med samma drivrutin, skild från appens Room-databas.
- FAS 1 **verifierar** valet på den riktiga telefonen: att en förgenererad search.db kan öppnas och sökas korrekt.
- **FTS4 är bara en reservlösning** om testet visar ett faktiskt problem.

Beslutet dokumenteras i ADR 0006.

**Viktigt för bygg-AI:** Room 3 är nytt (stabil sedan juli 2026, paketnamn `androidx.room3`). En `SQLiteDriver` krävs, och de gamla SupportSQLite-API:erna gäller inte. Använd Room 3:s dokumentation och release notes, inte Room 2-mönster.

**Tokenizer och svenska tecken:** `unicode61` med `remove_diacritics` kan göra att "får" och "far" blir samma ord. Det kan vara bra för OCR-fel men sämre för precision. Beslutet tas i FAS 8 efter test på verklig text och dokumenteras i ADR 0010.

## E11. Backup av personlig data

**Export** (via filväljare) till `dmz-reader-backup-YYYY-MM-DD.json`:

```json
{
  "backupFormat": 2,
  "exportedAt": "…",
  "appVersion": "…",
  "issueUserStates": [],
  "bookmarks": [],
  "readingSessions": []
}
```

- `appVersion` är appens `versionName` (G4).
- `issueUserStates` innehåller alla fält i C4, däribland `seenPages` (Base64) och `readStatusOverride`.
- `bookmarks` innehåller alla fält i C5 utom `id`: `bookmarkId`, `deletedAt` och PDF-versionen (`pdfSize`, `pdfEtag`, `pdfLastModified`) följer med.
- Format 1 (FAS 6) saknar `bookmarkId` och `deletedAt` och går fortfarande att importera.

**Ingår inte:** PDF:er, cache, omslag, inventory, search.db eller `IssueLocalState`.

**Import:**

- validera `backupFormat`
- visa en sammanfattning före import (antal bokmärken, favoriter och sessioner)
- sammanslagning:
  - `IssueUserState`: posten med senaste `updatedAt` vinner för `lastPage`, `readStatusOverride` och `isFavorite`. `seenPages` slås ihop (bitvis OR) och `maxPageReached` räknas om från resultatet. Det motsäger inte "Markera som oläst", eftersom den markeringen ligger i `readStatusOverride` och inte rör `seenPages` eller `maxPageReached`.
  - bokmärken matchas på `bookmarkId`: senaste `updatedAt` vinner för titel och anteckning. Är `deletedAt` satt på någon sida förblir bokmärket borttaget. Bokmärken ur format 1 får nya UUID, och bara för dem används dubblettregeln (samma issueId, pageNumber och title).
  - sessioner: dubbletter (samma issueId och startedAt) hoppas över
- ingenting raderas vid import

## E12. Statistik (FAS 10)

Definitioner (ADR 0021):

- **Lästa sidor:** summan av antalet satta bitar i `seenPages`, för all användardata.
- **Lästid:** summan av `durationSeconds` i `ReadingSession` (aktiv tid enligt C6).
- **Lästa, påbörjade och olästa nummer:** lässtatus enligt C10, med `readStatusOverride`. Användardata för nummer som saknas i katalogen (D6) räknas med bland lästa och påbörjade.
- **Årgångsprogress:** andel lästa nummer och andel lästa sidor per år och publikation. Nummer med okänt sidantal ingår inte i sidsumman.
- **Aktivitet:** lästid per vecka (måndag–söndag, lokal tid) för de senaste 12 veckorna. En session räknas till den vecka den började. Eftersom sessioner delas vid lokal midnatt (C6) hamnar tiden i rätt dag och vecka.
- **Mest lästa årgång:** den årgång där flest sidor är lästa. Har flera årgångar lika många visas alla ("Datormagazin 1988, 1991 · 2 sidor").

Årgångsradens procenttal gäller sidor och skrivs "N % sidor".

Fliken visar nyckeltal (lästa nummer, påbörjade, lästa sidor, total lästid), aktivitet per vecka som stapeldiagram, en rad per årgång med stapel för andelen lästa sidor och "X av Y nummer lästa", samt mest lästa årgång, antal favoriter och antal bokmärken. Staplarna ritas med Compose Canvas, utan diagrambibliotek. Sökningar räknas inte.

All statistik beräknas från lokal data. Ingenting lagras.

---

# DEL F – FASER

Varje fas har samma struktur:

- **Mål:** vad fasen ska åstadkomma
- **Förutsätter:** vilka faser som måste vara klara
- **Bygg:** vad som ska göras
- **Inte i denna fas:** gränsen mot senare faser
- **Leverabler:** filer och resultat
- **Klart när:** acceptanskriterier som ska kunna verifieras
- **ADR:** beslut som ska dokumenteras

**Översikt:**

| Fas | Namn | Del | Typ |
|---|---|---|---|
| 0 | Arkivanalys | Toolchain | Undersökning |
| 0B | Reproducerbar inventering | Toolchain | Bygg |
| 1 | Android-grund + FTS-test | App | Bygg |
| 2 | Bibliotek | App | Bygg |
| 3 | Läsare | App | Bygg |
| 4 | Lässtatus | App | Bygg |
| 5 | Offline | App | Bygg |
| 6 | Personligt bibliotek + backup | App | Bygg |
| 7 | Import av inventory + metadata-sökning | App | Bygg |
| 8A | Sökindex på datorn – pilot | Toolchain | Undersökning + bygg |
| 8B | Sökindex på datorn – fullkörning | Toolchain | Bygg |
| 9 | Fulltextsökning i appen | App | Bygg |
| 10 | Statistik | App | Bygg |
| 11 | HQ | App + Toolchain | Bygg |
| — | Innehållsanalys, AI/RAG | — | Långt senare |

**Milstolpar:**

- **MVP** = FAS 0–5
- **Privat 1.0** = FAS 0–7
- **1.x** = FAS 8–11

---

## FAS 0 – Arkivanalys

**Mål:** Ersätta antaganden med fakta om arkivet. Ingen Android-kod skrivs.

**Förutsätter:** ingenting.

**Bygg:**

1. `tools/archive_inventory.py` med:
   - FTP i passivt läge, en anslutning
   - rekursiv listning av `Covers`, `OCR PDF`, `Large PDF` och `Original Scans`
   - filnamn och storlek (via `MLSD` om servern stöder det, annars `LIST` eller `SIZE`)
   - paus på minst 0,5 sekunder mellan kataloganrop
   - rålistning sparad i `data/raw_listing.json`
   - flaggan `--offline`, som bara använder cachen
2. HTTPS-kartläggning per materialtyp:
   - finns materialet på dmzarkivet.se, och med vilket URL-mönster?
   - är adresserna stabila?
   - kontrollera med `HEAD`, annars en liten `Range`-förfrågan. Ladda aldrig ned hela filer för att kontrollera tillgänglighet.
3. Publikationer: identifiera vilka som faktiskt finns. Inga namn antas.
4. Namnregler:
   - analysera alla filnamn
   - föreslå format för `issueId`
   - koppla ihop samma nummer mellan katalogerna
   - allt som inte matchar en regel listas som specialfall
5. Stickprov: ladda ned **högst 2** OCR-PDF:er, **ett tidigt och ett sent nummer**. Verifiera:
   - textkvalitet från `pdftotext` hela filen, uppdelad på sidbrytning (`\f`)
   - text per sida och verkligt sidantal
   - uppslagsparning (vilka sidor hör ihop)
   - hur sidorna förhåller sig till Original Scans (antal och ordning)
   - om sida 1 duger som omslag
   - om OCR-texten duger för fulltextsökning
   - hur å, ä, ö kommer ut i texten
6. Sidantal:
   - mät det för stickproven
   - om Original Scans ligger en fil per sida, räkna filerna och jämför med PDF:ens sidantal
   - lämna `null` i övrigt
7. Uppskatta storleken på search.db: textmängd per sida och per nummer, och en grov total. Uppskattningen ska märkas som just en uppskattning.
8. HQ-rekommendation: Original Scans per sida, Large PDF, eller båda.

**Inte i denna fas:**

- Android-kod
- att bygga search.db
- att ladda ned mer än 2 PDF:er

**Leverabler:**

- `tools/archive_inventory.py`
- `data/raw_listing.json`
- `data/archive_inventory.json` (första versionen, `pageCount` null där okänt)
- `docs/archive_notes.md` med:
  - namnregler, specialfall och publikationer
  - statistik per publikation och år
  - saknade omslag, OCR-PDF:er och Large PDF:er
  - strukturen i Original Scans
  - HTTPS kontra FTP per materialtyp
  - stickprovsresultat och sidparning
  - uppskattad storlek på search.db
  - HQ-rekommendation
  - rekommendation för hur appen ska hämta filer
- `tools/tests/test_normalize.py` med verkliga filnamn, inklusive alla specialfall

**Klart när:**

- [ ] varje fil i de fyra katalogerna är antingen kopplad till ett `issueId` eller listad som specialfall
- [ ] `--offline` ger samma resultat som en körning mot servern
- [ ] alla tester passerar
- [ ] frågorna i bilaga C är besvarade eller uttryckligen markerade som obesvarade

**ADR:** 0007 (format för issueId, förslag), 0008 (HTTPS kontra FTP), 0009 (HQ-källa, preliminärt).

---

## FAS 0B – Reproducerbar inventering

**Mål:** Göra inventeringen deterministisk och färdig för appen.

**Förutsätter:** FAS 0, och att C64 har godkänt formatet för `issueId`.

**Bygg:**

- normalisering och generator för `issueId` enligt ADR 0007
- regler för `publicationId`
- export enligt formatet i D1, med `schemaVersion` och `datasetVersion`
- validering:
  - unika `issueId`
  - obligatoriska fält
  - inga tysta gissningar
  - rapport över specialfall
- deterministisk utdata: samma rålistning ger byte-identisk JSON (stabil sortering och nycklar)
- jämförelse mot föregående inventory: nya, borttagna och ändrade nummer
- en kort `tools/README.md` som beskriver hur verktyget körs

**Inte i denna fas:** sökindex, Android-kod.

**Leverabler:** uppdaterat `archive_inventory.py`, `data/archive_inventory.json`, tester och `tools/README.md`.

**Klart när:**

- [ ] två körningar med `--offline` ger identiska filer
- [ ] valideringen fångar dubbletter och saknade fält (testat)
- [ ] jämförelsen rapporterar korrekt mot en manipulerad testfil

**ADR:** 0007 (fastställt, nu permanent).

---

## FAS 1 – Android-grund och FTS-test

**Mål:** En stabil Android-grund, och ett faktiskt besked om FTS5 fungerar.

**Förutsätter:** FAS 0B, så att datamodellen bygger på verklig data.

**Bygg:**

- nytt projekt: Kotlin, Compose, minSdk 26
- tema enligt E3, med paketerade typsnitt
- navigation med fyra flikar enligt E1, platshållare för Sök, Favoriter och Statistik
- Room 3 (`androidx.room3`) med `BundledSQLiteDriver` och alla fem tabeller (`Issue`, `IssueLocalState`, `IssueUserState`, `Bookmark`, `ReadingSession`), **utan främmande nycklar till Issue**
- DataStore för inställningar
- repository-lager
- inläsning av `assets/archive_inventory.json` till `Issue`
- **FTS5-verifiering** på C64:s telefon (testar att det primära valet fungerar, inte om Android klarar FTS5):
  1. bygg en liten test-search.db på datorn med Python och FTS5, med svensk text inklusive å, ä, ö
  2. kopiera den till appens privata lagring och öppna den skrivskyddad via `BundledSQLiteDriver`
  3. kör en `MATCH`-fråga med `bm25` och `snippet` och kontrollera att träffarna är rätt
  4. rapportera resultatet. Bara om något faktiskt fallerar utvärderas FTS4 som reserv.

**Inte i denna fas:** riktigt biblioteks-UI, PDF, nätverk.

**Leverabler:** körbart projekt, testresultat och ADR 0006.

**Klart när:**

- [ ] appen startar och visar antalet nummer från inventory
- [ ] ett test bekräftar att import av ett nytt inventory inte påverkar `IssueUserState`
- [ ] en förgenererad FTS5-databas öppnas och söks korrekt på den riktiga telefonen, och resultatet är dokumenterat
- [ ] en debug-APK går att installera

**ADR:** 0001 (native Android), 0002 (separering av data), 0003 (inventory genereras på datorn), 0006 (FTS5 med Room 3 och `BundledSQLiteDriver`, verifierat).

---

## FAS 2 – Bibliotek

**Mål:** Den första riktiga biblioteksupplevelsen.

**Förutsätter:** FAS 1.

**Bygg:**

- biblioteksvy enligt E4 (Fortsätt läsa, Senast lästa och Favoriter kan vara tomma och dolda)
- årgångsvy enligt E5
- nummervy enligt E6, utan läs- och nedladdningsfunktion
- omslag från `coverUrl` via Coil med diskcache, annars platshållare (B3)
- stöd för flera publikationer om FAS 0 visade att det finns fler

**Inte i denna fas:** PDF, läsning, nedladdning.

**Klart när:**

- [ ] användaren kan bläddra mellan år och nummer
- [ ] rätt omslag visas för rätt nummer
- [ ] saknade omslag visar en platshållare
- [ ] rullningen flyter även i det största året
- [ ] biblioteket fungerar utan nätverk, med platshållare för omslag som inte är cachade

---

## FAS 3 – Läsare

**Mål:** Ett helt nummer kan läsas bekvämt.

**Förutsätter:** FAS 2.

**Bygg:**

- "Läs nu": ladda ned OCR-PDF:en till `cacheDir` med förloppsindikator och möjlighet att avbryta
- utvärdera PDF-rendering: `PdfRenderer` i första hand. Byt bara om den inte klarar stabilitet, zoom eller minne, och dokumentera skälet.
- läsare enligt E7 (helskärm, svep, tryckzoner, zoom, panorering, miniatyrer, progressbar, snabbhopp)
- uppslagsläge i liggande läge, med manuell förskjutning av parningen
- uppmät sidantal och PDF-storlek sparas i `IssueLocalState`
- belastningstest med arkivets största OCR-PDF, 1995 nr 15 (563 MB)
- miniatyrer: webbplatsens sidbilder är ett alternativ till att rendera dem ur PDF:en. Alternativen utvärderas här.
- läsposition sparas tillfälligt i minnet (riktig lagring kommer i FAS 4)

**Inte i denna fas:** permanent lagring av läsposition, nedladdning, bokmärken, HQ.

**Klart när:**

- [ ] ett helt nummer kan läsas från första till sista sidan
- [ ] zoom och panorering är mjuka
- [ ] rotation tappar inte sidan
- [ ] uppslagsläget visar rätt par för stickprovsnumren
- [ ] den största OCR-PDF:en i arkivet, 1995 nr 15 (563 MB), går att läsa utan krasch eller minnesbrist
- [ ] avbruten nedladdning lämnar ingen trasig fil

**ADR:** 0011 (PDF-rendering).

---

## FAS 4 – Lässtatus

**Mål:** Appen minns var användaren är.

**Förutsätter:** FAS 3.

**Bygg:**

- `IssueUserState`: `lastPage`, `maxPageReached`, `firstOpenedAt`, `lastOpenedAt`, `completedAt`
- lässtatus enligt C10, inklusive `readStatusOverride` (Markera som läst, Markera som oläst, Återställ)
- `ReadingSession` enligt C6
- sektionerna Fortsätt läsa och Senast lästa i biblioteket
- progress i årgångsvy och nummervy

**Klart när:**

- [ ] rätt sida återställs efter att appen stängts och startats om
- [ ] att hoppa tillbaka sänker inte progressen
- [ ] "Markera som oläst" ändrar status utan att `maxPageReached` försvinner, och nollställs när numret läses igen
- [ ] nummer med okänt sidantal visar status utan procent
- [ ] sessioner sparas med rimlig längd

---

## FAS 5 – Offline

**Mål:** Nedladdade nummer kan läsas helt utan internet.

**Förutsätter:** FAS 4.

**Bygg:**

- beslut om lagringsplats för permanenta PDF:er utifrån FAS 0:s filstorlekar (ADR 0013)
- "Ladda ned" till `filesDir/issues/`: på API 34+ via JobScheduler User-Initiated Data Transfer (UIDT), på API 26–33 bara medan appen är öppen, med en tydlig text om det. Ingen WorkManager-reserv byggs.
- `downloadState` och tillhörande fält i `IssueLocalState`
- kö med högst en FTP-anslutning i taget
- status i årgångsvy och nummervy (ikon och förlopp)
- ta bort nedladdning
- sektionen Nedladdat och filtret Offline
- lagringsöversikt och cacherensning i inställningarna
- nedladdad fil används alltid före nätet

**Klart när:**

- [ ] ett nedladdat nummer kan läsas i flygplansläge
- [ ] en nedladdning överlever att appen stängs (API 34+)
- [ ] en avbruten eller misslyckad nedladdning kan startas om och lämnar inga trasiga filer
- [ ] borttagning frigör utrymmet

**Milstolpe: MVP klar.**

---

## FAS 6 – Personligt bibliotek och backup

**Mål:** Appen blir en personlig samling, och datan går att återställa.

**Förutsätter:** FAS 5.

**Bygg:**

- favoritnummer
- bokmärken enligt C5, med titel och anteckning, skapade från läsaren
- favoritvy: favoritnummer och bokmärken grupperade per nummer
- bokmärken i nummervyn, och ett tryck öppnar rätt sida
- markering för fil som bytts i arkivet (C7)
- export och import av backup enligt E11
- visning av poster som saknas i inventory (D6)

**Klart när:**

- [ ] ett bokmärke öppnar exakt rätt sida
- [ ] export, avinstallation, ominstallation och import återställer allt
- [ ] att importera samma backup två gånger skapar inga dubbletter

**ADR:** 0012 (backupformat och regler för sammanslagning).

---

## FAS 7 – Import av inventory och metadata-sökning

**Mål:** Katalogen kan uppdateras utan ny APK, och kända nummer kan hittas snabbt.

**Förutsätter:** FAS 6.

**Bygg:**

- import av `archive_inventory.json` enligt D5
- validering av `schemaVersion` och mot `docs/inventory.schema.json`, med tydliga felmeddelanden
- atomiskt byte och ersättning av `Issue` i en transaktion
- återgång till baseline
- visning av nummer som saknas (D6)
- metadata-sökning i Sök-fliken enligt E10 steg 1
- ett importerat `coverAsset` är bara en ledtråd: appen kontrollerar att filen finns i assets. Finns den inte används `coverUrl`, och sist platshållaren.

**Klart när:**

- [ ] ett nytt inventory med ett tillagt och ett borttaget nummer importeras korrekt
- [ ] användardata för det borttagna numret finns kvar och visas som saknad
- [ ] när numret läggs tillbaka kopplas datan ihop igen
- [ ] en fil med fel schema avvisas, och den tidigare filen gäller fortfarande
- [ ] metadata-sökning hittar ett nummer på år eller nummer

**Milstolpe: Privat 1.0 klar.**

---

## FAS 8 – Sökindex på datorn

**Mål:** Ett reproducerbart fulltextindex för hela arkivet.

**Förutsätter:** FAS 0B och ADR 0006 från FAS 1.

Fasen är delad i två. **8A** prövar hela kedjan på ett urval och ger mätdata för besluten. **8B** kör alla nummer och påbörjas först när piloten är godkänd.

**Inte i någon av delarna:** Android-kod eller ändringar i appens gränssnitt.

### FAS 8A – Pilot

**Bygg:**

- urval om 11 nummer (cirka 0,7 GB) som täcker båda PDF-generationerna, HighScore, ett b-nummer, ett dubbelnummer och 1995 nr 14 med utspärrad text. 1995 nr 15 (563 MB) hämtas inte.
- hämtning via HTTPS från `ocrPdfUrl` till `cache/pdf/`: en fil i taget, återupptagbar, högst 5 MB/s, verifierad mot `ocrPdfSize` och ETag. Storlek och ETag sparas per fil.
- `tools/build_search_index.py`:
  - jämförelse av `pdftotext` i standardläge, `-layout` och `-raw` på flerspaltiga sidor; det läge som håller ihop spalterna väljs
  - uppdelning på `\f` till text per PDF-sida (C7)
  - två texter per sida: `rawText` (orörd) och `searchText` (normaliserad)
  - normalisering till `searchText`: **ihopsättning av utspärrade ord**, **borttagning av mjuka bindestreck** (U+00AD), ihopsättning av avstavning vid radbrytning och normaliserade blanksteg. Riktiga bindestreck (CD-ROM, C-64) bevaras.
- search.db enligt D2 med FTS5, i två varianter: `unicode61 remove_diacritics 0` och `unicode61 remove_diacritics 2`
  - `meta`: `searchSchemaVersion`, `inventoryDatasetVersion`, `tokenizer`, `builtAt`, `generator`, `sqliteVersion`
  - `issues(issueId, pageCount, ocrPdfSize, ocrPdfEtag)`, så att appen kan upptäcka att just ett nummers PDF har bytts sedan indexeringen
- facit i `tools/testdata/search_golden.json`: cirka 25 sökord med förväntat nummer och sida, där sidan är kontrollerad visuellt mot en rendering
- mätning för båda varianterna: träffsäkerhet mot facit, storlek (rå text, SQLite, FTS-index, total och komprimerad, uppräknad till 185 nummer per generation) och byggtid per nummer
- uppmätt `pageCount` per pilotnummer jämförs med inventory. Inventory ändras inte i 8A.
- piloten prövas i appens motor (`BundledSQLiteDriver`) på telefonen
- underlag för 8B: diskutrymme, total nedladdning, tid och ordning

**Klart när:**

- [ ] search.db byggs reproducerbart från den lokala PDF-cachen, i båda varianterna
- [ ] facit finns, med visuellt kontrollerade sidor
- [ ] test mot 1995 nr 14: sökningen hittar "Amiga" och "högtalare" på de sidor där texten är utspärrad
- [ ] träffsäkerhet, storlek och byggtid är uppmätta för båda varianterna
- [ ] pilotens search.db går att öppna och söka i med appens motor på telefonen
- [ ] ADR 0010 finns som Proposed, med mätdata och rekommendation

**ADR:** 0010 (tokenizer och diakritiska tecken), Proposed.

### FAS 8B – Fullkörning

**Förutsätter:** att piloten i 8A är godkänd och att tokenizer är vald.

**Bygg:**

- hämtning av alla OCR-PDF:er till den lokala cachen, på samma försiktiga sätt som i piloten, de minsta filerna först
- search.db för hela arkivet med vald tokenizer
- uppmätt `pageCount` för alla nummer skrivs tillbaka till inventory, med ny `datasetVersion`
- slutlig mätning av storlek och körtid
- `rawText` får tas bort om storleksmätningen motiverar det

**Klart när:**

- [ ] search.db för hela arkivet byggs reproducerbart från den lokala PDF-cachen
- [ ] facit från piloten ger samma resultat mot det fullständiga indexet
- [ ] `pageCount` är infört i inventory för alla nummer som har en PDF
- [ ] storleken är uppmätt och dokumenterad
- [ ] om storleken är problematisk finns ett förslag (uppdelning per publikation eller årsspann, eller FTS utan lagrad text). Ingen uppdelning görs utan mätdata.

**ADR:** 0004 (sökindex byggs på datorn), 0010 fastställs.

---

## FAS 9 – Fulltextsökning i appen

**Mål:** Sök i hela arkivet från telefonen.

**Förutsätter:** FAS 7 och FAS 8.

**Bygg:**

- import av search.db enligt D5 (kopiera, kör integritetskontroll, fråga FTS-tabellen, byt atomiskt)
- searchDb öppnas skrivskyddad och separat från Room
- versionskontroll enligt D3 (varning, inte spärr)
- sökvy med rankade träffar, textutdrag och markerat sökord
- ett tryck på en träff öppnar rätt nummer och sida, och laddar till cache om det behövs
- "Sök i numret" i läsaren och nummervyn
- markering "Ingår inte i sökindexet"

**Klart när:**

- [ ] en sökning ger rätt nummer, rätt sida och rätt utdrag
- [ ] ett tryck öppnar exakt rätt sida
- [ ] sökningen svarar på under 1 sekund på telefonen
- [ ] en search.db med annan `datasetVersion` fungerar och visar en notis
- [ ] en trasig search.db avvisas utan att den tidigare påverkas

---

## FAS 10 – Statistik

**Mål:** Personlig lässtatistik.

**Förutsätter:** FAS 6.

**Bygg:** statistikvyn enligt E12.

**Lässessioner:** en session som pågår när appen tvångsstoppas eller kraschar gick tidigare förlorad (FAS 4, ADR 0018). Sessionerna sparas därför löpande, och lästiden är aktiv tid (C6, ADR 0021).

**Tester:** de instrumenterade testerna körs mot isolerad lagring och rör aldrig användarens data (G6).

**Klart när:**

- [ ] siffrorna stämmer mot kontrollerade testdata
- [ ] vyn fungerar med tom databas

---

## FAS 11 – HQ

**Mål:** Detaljläsning i hög kvalitet.

**Förutsätter:** FAS 3 och ADR 0009 (fastställd).

**Bygg:**

- HQ-knappen i läsaren
- hämtning av HQ-källa per sida (originalskanning) eller Large PDF enligt ADR 0009
- mappning mellan PDF-sidindex och skanningsfil, verifierad mot stickproven. För nummer med avvikelser (se flaggorna i inventory) får mappningen inte konstrueras matematiskt. Den exakta mappningen beslutas i denna fas.
  **Beslutat i FAS 11:** mappningen tas fram med bildjämförelse i toolchainen (`tools/map_hq_pages.py`). 1986 nr 4 och 1993 nr 3 har `hqPageMap` i inventory, och sidor där skanningen ligger vriden har `hqPageRotation`. Övriga nummer följer sida N = skanning N.
- cache av HQ-sidor i `cacheDir`, med storleksgräns
- tillbaka till Standard utan att sidan tappas

**Klart när:**

- [ ] en sida kan växlas till HQ och tillbaka utan att läspositionen tappas
- [ ] rätt skanning visas för rätt sida
- [ ] minnet håller även vid maximal zoom

---

## Senare – innehållsanalys och AI/RAG

Byggs inte förrän fulltextsökningen är stabil.

**Innehållsanalys:**

- begrepp över tid, t.ex. "Amiga 500": första förekomst, träffar per år
- ämnesfrekvens, produkter, spel, företag och annonser

**AI/RAG:**

- frågor på naturligt språk mot sökindexet
- varje svar måste hänvisa till publikation, år, nummer och sida
- ett tryck på en källhänvisning öppnar exakt sida
- AI får aldrig ersätta källan
- en lokal modell (t.ex. LM Studio i hemmalabbet) kan vara ett alternativ till moln och kräver då ett uttryckligt beslut

**Not från FAS 8B (blockordning):** texten i search.db följer den ordning OCR-programmet skrev den i. Spalterna hålls ihop, men på sidor med flera artiklar kan blocken komma i annan ordning än artiklarna (4 av 28 granskade sidor, `data/reading_order_review.json`). Det påverkar inte sökningen per sida. Den som delar texten i stycken eller artiklar för RAG kan däremot inte utgå från att texten följer artiklarna, och behöver ordbitarnas lägen på sidan (`pdftotext -raw -bbox`) för att dela rätt.

---

# DEL G – ARBETSSÄTT

## G1. Regler för bygg-AI

1. **Implementera bara aktuell fas.** Bygg inte framtida funktioner "lite i förväg".
2. **Läs relevanta ADR:er** innan arbetet börjar.
3. **Följ de fem absoluta reglerna i A4.**
4. **Behåll all fungerande funktionalitet.** Kör befintliga tester före och efter.
5. **Använd verklig data** bara där fasen kräver det, annars mockdata.
6. **Fråga i stället för att gissa** när planen och verkligheten inte stämmer. Stanna och rapportera.
7. **Dokumentera viktiga beslut som ADR.**
8. **Optimera inte för Google Play.** Lägg inte till konton eller molntjänster.
9. **Prioritera läsaren** före avancerade funktioner.
10. **Ingen AI-funktion** före fulltextsökning.
11. **Validera `schemaVersion`**, och hantera `datasetVersion` enligt D3.
12. **Belasta servern försiktigt.**
13. **Ändra inte planen på egen hand.** Föreslå ändringar i rapporten.

## G2. Rapport efter varje fas

1. sammanfattning av vad som byggts
2. skapade och ändrade filer
3. testresultat, med hur de körts
4. acceptanskriterierna från planen, avbockade eller med förklaring
5. kända begränsningar
6. skapade ADR:er
7. vad som återstår
8. förslag på nästa fas, och eventuella ändringar i planen

## G3. ADR-mall

```markdown
# ADR 000X: Titel

## Status
Proposed | Accepted | Superseded by ADR 00YY

## Context
Varför beslutet behövs.

## Decision
Vad som beslutades.

## Reason
Varför detta alternativ.

## Consequences
Vad det innebär, inklusive nackdelar.
```

## G4. Git

- `main` innehåller bara fungerande, testade versioner
- en funktionsgren per fas: `feature/fas-0-archive-analysis`, `feature/fas-1-android-foundation` osv.
- separata commits per logiskt steg
- merge till `main` först när acceptanskriterierna är uppfyllda
- tagg vid milstolpar: `v0.5-mvp`, `v1.0-private`
- **appens version följer taggarna:** `versionName` är taggens nummer med tre delar och `versionCode` räknas ur det (0.6.1 ger 601, 0.7.0 ger 700, 1.0.0 ger 10000). Versionen sätts på ett ställe, i `app/build.gradle.kts`, och läses i koden från `BuildConfig`.
- `data/raw_listing.json` och `archive_inventory.json` versionshanteras
- nedladdade PDF:er, PDF-cachen och search.db versionshanteras **inte** (läggs i `.gitignore`)

## G5. Projektstruktur

```text
dmz-reader/
├── CLAUDE.md                     korta, absoluta regler för Claude Code
├── DMZ_READER_PROJEKTPLAN_V3.md  denna plan
├── app/                          Android-appen
│   └── src/main/java/…/
│       ├── data/
│       │   ├── db/               Room: entiteter, DAO:er
│       │   ├── search/           searchDb (separat, skrivskyddad)
│       │   ├── inventory/        inläsning och import av inventory
│       │   ├── network/          HTTPS och FTP (FTP bara för HQ och Large PDF)
│       │   └── repository/
│       ├── domain/               logik för lässtatus, progress, effektivt sidantal
│       ├── ui/
│       │   ├── theme/
│       │   ├── library/
│       │   ├── year/
│       │   ├── issue/
│       │   ├── reader/
│       │   ├── search/
│       │   ├── favorites/
│       │   ├── statistics/
│       │   └── settings/
│       ├── work/                 WorkManager-jobb
│       └── backup/
├── tools/                        Python-toolchain
│   ├── archive_inventory.py
│   ├── build_search_index.py
│   ├── README.md
│   └── tests/
├── data/
│   ├── raw_listing.json
│   └── archive_inventory.json
└── docs/
    ├── archive_notes.md
    ├── ui_design.md
    └── decisions/
```

## G6. Teststrategi

| Område | Test |
|---|---|
| Toolchain | normalisering med verkliga filnamn, deterministisk utdata, validering, jämförelse |
| Datamodell | import av inventory påverkar inte användardata; inga FK-kaskader |
| Bibliotek | rätt år, nummer och omslag; platshållare vid saknat omslag |
| Läsare | öppna, byta sida, zoom, rotation, uppslag, största PDF:en |
| Lässtatus | `lastPage` och `maxPageReached` efter omstart; okänt sidantal |
| Offline | flygplansläge; avbruten nedladdning; borttagning |
| Bokmärken | rätt sida; fil som bytts markeras |
| Backup | export och import ger samma data; ingen dubblering |
| Import | fel schema avvisas; atomiskt byte; återgång till tidigare fil vid fel |
| Sökning | rätt nummer, sida och utdrag; versionsnotis; trasig fil avvisas |

| Statistik | definitionerna mot kontrollerade testdata; tomt läge; aktiv lästid och tvångsstopp |

**De instrumenterade testerna rör aldrig användarens data.** De körs med testköraren `IsolatedTestRunner`, som ger appen en egen databas, egna inställningar, eget `filesDir` och eget `cacheDir` för körningen. `tools/device_test.sh` kör hela sviten och jämför ett fingeravtryck av användarens data före och efter.

Domänlogik (progress, status, effektivt sidantal, sammanslagning vid backup) testas med enhetstester. Läsaren testas manuellt på C64:s telefon enligt en checklista i fasrapporten.

## G7. Prestandakrav

- biblioteket startar på under 1 sekund med cachad data
- rullningen i omslagsrutnätet flyter
- vid sidbyte ges visuell respons direkt. v2-PDF:er visar full sida inom några hundra ms. Äldre PDF:er (JPEG 2000) visar först en förhandsbild (mål under 0,5 s) och därefter full rendering. Gränssnittet blockeras aldrig av rendering.
- ingen helsidesbitmap behålls i onödan; minnet för HQ har en gräns
- fulltextsökning svarar på under 1 sekund

---

# BILAGOR

## Bilaga A – Ändringar från version 2 till version 3

| # | Ändring | Skäl |
|---|---|---|
| 1 | Ny tabell `IssueLocalState` | Sidantal, nedladdningsstatus och genererade omslag som telefonen tar fram hade ingen plats och skulle ha skrivits över av import |
| 2 | Förbud mot främmande nycklar med kaskad till `Issue` | Kaskadradering hade tyst raderat användardata vid import |
| 3 | Visning av användardata som saknar katalogpost (D6) | Följd av punkt 2 |
| 4 | FTS5 via Room 3 och `BundledSQLiteDriver`, verifierat i FAS 1 (ADR 0006) | Telefonens inbyggda SQLite har inte garanterat FTS5; den paketerade drivrutinen har det |
| 5 | Matchning av `datasetVersion` är en varning, inte en spärr | Annars måste search.db byggas om för varje nytt nummer |
| 6 | Sidnummer definierat som PDF-sidindex från 1 (C7) | Bokmärken, sökträffar och progress måste referera på samma sätt |
| 7 | Kontrollsumma/storlek för att upptäcka utbytta filer | Omskanning kan förskjuta sidor |
| 8 | Favoritsidor slås ihop med bokmärken | Två system för samma sak |
| 9 | Coil-fetcher för FTP i FAS 2 vid behov | Coil kan inte läsa `ftp://` |
| 10 | Tokenizerbeslut för svenska tecken (ADR 0010) | "får" och "far" kan bli samma ord |
| 11 | Stickprov: ett tidigt och ett sent nummer | Skanning och OCR varierar mellan årgångar |
| 12 | Separat `CLAUDE.md` | Viktiga regler ska inte drunkna i en lång plan |
| 13 | Ingen `develop`-gren; Python-tester under `tools/tests/` | Förenkling för en ensam utvecklare |
| 14 | Metadata-sökning flyttad till FAS 7 | Den behöver inget sökindex och passar med import |
| 15 | Manuell förskjutning av uppslagsparningen | Felaktig parning ska gå att rätta i läsaren |
| 16 | Regler för sammanslagning vid import av backup | Undvika dubbletter och dataförlust |
| 17 | Acceptanskriterier som kryssrutor i alla faser | Verifierbart avslut per fas |

## Bilaga B – ADR-lista

| ADR | Ämne | Skrivs i |
|---|---|---|
| 0001 | Native Android (Kotlin + Compose) | FAS 1 |
| 0002 | Separering av katalogdata, lokal data och användardata | FAS 1 |
| 0003 | Inventory genereras på datorn | FAS 1 |
| 0004 | Sökindex byggs på datorn | FAS 8B |
| 0005 | Versionsmärkning och matchning (D3) | FAS 1 |
| 0006 | FTS5 med Room 3 och `BundledSQLiteDriver` | FAS 1 |
| 0007 | Format för issueId | FAS 0 (förslag), FAS 0B (fastställt) |
| 0008 | HTTPS kontra FTP per materialtyp | FAS 0 |
| 0009 | HQ-källa | FAS 0 (preliminärt), FAS 11 (fastställt) |
| 0010 | Tokenizer och diakritiska tecken | FAS 8A |
| 0011 | PDF-rendering | FAS 3 |
| 0012 | Backupformat och regler för sammanslagning | FAS 6 |
| 0013 | Lagringsplats för permanenta PDF:er | FAS 5 |

## Bilaga C – Öppna frågor som FAS 0 ska besvara

1. Hur namnges filerna i varje katalog?
2. Har samma nummer samma identitet i alla kataloger?
3. Hur namnges dubbelnummer och specialnummer?
4. Vilka publikationer finns?
5. Hur många nummer finns per publikation och år?
6. Vilka nummer saknar omslag, OCR-PDF eller Large PDF?
7. Ligger Original Scans en fil per sida? Hur namnges de?
8. Stämmer antalet skanningar med sidantalet i OCR-PDF:en?
9. Vilka material finns via HTTPS, och med vilka URL-mönster?
10. Hur stora är OCR-PDF:erna (min, median, max)?
11. Hur bra är OCR-texten i ett tidigt respektive sent nummer?
12. Hur ser uppslagsparningen ut i stickproven?
13. Duger sida 1 som omslag?
14. Hur stor blir search.db ungefär?
15. Vilken HQ-källa rekommenderas?

## Bilaga D – Slutvision

```text
Öppna DMZ Reader
↓
Välj 1989 → se alla omslag
↓
Öppna nummer 12 → fortsätt på sida 43
↓
Vrid telefonen → läs i uppslag
↓
Bokmärk en intressant annons
↓
Sök "Fairlight" → se alla nummer och sidor
↓
Öppna exakt träff → visa i HQ vid behov
```

Längre fram:

```text
"Visa alla artiklar om Amiga 500 mellan 1988 och 1991"
"Hur förändrades Datormagazins syn på piratkopiering över tid?"
```

med hänvisningar tillbaka till originalnummer och originalsidor.

## Bilaga E – Ändringslogg efter baseline

Godkända ändringar efter att planen frysts förs in här, med datum och ADR-hänvisning. Huvudtexten ovan ändras bara när en ändring är godkänd av C64.

| Datum | Ändring | ADR |
|---|---|---|
| 2026-10-06 | V3.1: FTS5 via Room 3 och `BundledSQLiteDriver` som primärt val, FTS4 som reserv | 0006 |
| 2026-10-06 | V3.1: `readStatusOverride` i `IssueUserState` för manuell läst/oläst | — |
| 2026-10-06 | V3.1: lagringsplats för permanenta PDF:er beslutas i FAS 5 utifrån filstorlekar | 0013 |
| 2026-10-06 | V3.1: planen märkt som BASELINE och fryst | — |
| 2026-10-06 | FAS 0: kanonisk OCR-PDF är webbplatsens `https://dmzarkivet.se/pdf/<nyckel>.pdf` för alla nummer. Appen läser den filen och search.db byggs från exakt samma filer. FTP-katalogerna `OCR PDF` och `OCR PDF v2` bevaras bara i `sourceNames`. För 1993 nr 3 gäller webbversionen. | 0008 |
| 2026-10-06 | FAS 0: nya fält i inventory: `ocrPdfVersion` (ETag och Last-Modified för den kanoniska PDF:en), `ocrPdfFtpSource` (vilken FTP-generation webbfilen motsvarar, utifrån storlek) och det valfria `relatedIssueId` | 0008 |
| 2026-10-06 | FAS 0: `coverUrl` är webbplatsens sidbild 1 för alla nummer. FTP-katalogen `Covers` används inte av appen i v1. Omslag har två nivåer: webbens sidbild 1, annars platshållare (ersätter de tre nivåerna i B3). | 0008 |
| 2026-10-06 | FAS 0: HighScore är en egen publikation (`publicationId` "highscore", `publicationTitle` "HighScore"). `relatedIssueId` pekar på motsvarande Datormagazin-nummer. | 0007 |
| 2026-10-06 | FAS 0: formatet för `issueId` fastställt som `<publicationId>-<ÅÅÅÅ>-<NN>[-<MM>][b]`. Formatet är nu permanent. | 0007 |
| 2026-10-06 | FAS 0: `Other Magazines`, `PDFs V3`, `Löp`, `amigadiskar`, `test` och lösa filer i arkivets rot ingår inte i projektet | — |
| 2026-10-06 | FAS 0B: C2 och D1 har nya Issue-fält: `relatedIssueId`, `coverFlags`, `ocrPdfEtag`, `ocrPdfLastModified`, `scanPageCount`. `ocrPdfChecksum` är borttaget. `coverUrl` och `ocrPdfUrl` är obligatoriska. | 0008 |
| 2026-10-06 | FAS 0B: C7: utbytta filer upptäcks med `ocrPdfSize`, `ocrPdfEtag` och `ocrPdfLastModified` i stället för kontrollsumma | 0008 |
| 2026-10-06 | FAS 0B: B3: omslag är webbens sidbild 1, annars platshållare. Nivån "renderad sida 1 ur PDF" är struken. FTP `Covers` används inte i v1. | 0008 |
| 2026-10-06 | FAS 0B: FAS 2: FTP-fetchern för Coil och PDF-genererade omslag strukna. FAS 3: omslag nivå 2 struket; belastningstest med 1995 nr 15 (563 MB) tillagt; webbens sidbilder utvärderas som källa för miniatyrer. | 0008 |
| 2026-10-06 | FAS 0B: FAS 8: search.db sparar `rawText` och `searchText` per sida. Normaliseringen sätter ihop utspärrade ord och tar bort mjuka bindestreck. Test mot 1995 nr 14 tillagt. | — |
| 2026-10-06 | FAS 0B: FAS 11: sidmappningen får inte konstrueras matematiskt för nummer med avvikelser. Den beslutas i FAS 11. | 0009 |
| 2026-10-06 | FAS 0B: D5 och FAS 7: import av inventory valideras mot `docs/inventory.schema.json` | — |
| 2026-10-06 | Efter FAS 0B: C3: `generatedCoverPath` borttaget ur `IssueLocalState`. E8: raden "Genererade omslag" borttagen. | 0008 |
| 2026-10-06 | Efter FAS 0B: A5 och E9: ingen FTP-fetcher för Coil. Omslag hämtas via HTTPS. FTP används bara för HQ (Original Scans) och Large PDF. | 0008 |
| 2026-10-06 | Efter FAS 0B: B2: `Covers` används inte av appen i v1. Omslag är webbens sidbild 1 (`coverUrl`). | 0008 |
| 2026-10-06 | Efter FAS 0B: D2: `pages` har (issueId, pageNumber, rawText, searchText). FTS-indexet och textutdragen använder `searchText`. | — |
| 2026-10-06 | Efter FAS 0B: C2: `coverUrl` och `ocrPdfUrl` är nullable, med flaggorna `missing_cover` och `missing_ocr_pdf`. Appens Room-entitet är en delmängd av inventory. | 0008 |
| 2026-10-06 | Efter FAS 0B: E6: nummer med `missing_ocr_pdf` visar "PDF saknas" och ingen Läs-knapp | — |
| 2026-10-06 | Före FAS 1: E8: raden "Omslag från Covers" ersatt med "Omslag (webbens sidbild 1)". G5: `network/` är HTTPS och FTP, där FTP bara används för HQ och Large PDF. | 0008 |
| 2026-10-06 | FAS 2: omslagen tas fram av toolchainen och följer med i appen som miniatyrer (`coverAsset`). Källprioritet per nummer: bearbetat omslag från FTP `Covers`, annars webbens sidbild 1 med rättningar i `coverFlags`, annars platshållare. Appen laddar `coverAsset` i första hand och `coverUrl` i andra hand, och använder fortfarande aldrig FTP. Transformordning: vridning, beskärning, skalning. Ersätter omslagsbesluten från FAS 0 och 0B (B2, B3, C2). | 0016 |
| 2026-10-06 | Före FAS 3: B2, B3 och C2 enligt ADR 0016: omslag är `coverAsset` i första hand, `coverUrl` i andra hand och platshållare sist. Toolchainen väljer källa för miniatyren. | 0016 |
| 2026-10-06 | Före FAS 3: FAS 7: ett importerat `coverAsset` är bara en ledtråd; appen kontrollerar att filen finns i assets | 0016 |
| 2026-10-06 | Före FAS 3: E7: miniatyrremsan i läsaren visar den faktiska PDF-sidan. `coverFlags` används aldrig i läsaren. | 0016 |
| 2026-10-07 | Före FAS 4: G7: "sidbyte känns omedelbart" ersatt med krav på direkt visuell respons: v2-PDF:er visar full sida inom några hundra ms, äldre PDF:er (JPEG 2000) först en förhandsbild (mål under 0,5 s) | 0011 |
| 2026-10-07 | Före FAS 4: C4: nytt fält `seenPages` i `IssueUserState` (bitmängd, en bit per PDF-sidindex, Base64-text). `maxPageReached` är högsta sidan i `seenPages`. | 0018 |
| 2026-10-07 | Före FAS 4: C10: progress är antal sidor i `seenPages` delat med sidantalet. En sida räknas som läst efter 1,5 s i följd (båda sidorna i uppslag); snabbläddring räknas inte. `UNREAD` nollställs när en ny sida räknas som läst. | 0018 |
| 2026-10-07 | Före FAS 4: E11: vid import av backup slås `seenPages` ihop med bitvis OR och `maxPageReached` räknas om | 0018 |
| 2026-10-07 | Före FAS 5: E5: filterchips Alla, Olästa, Påbörjade, Lästa och Offline i en horisontellt rullbar rad | — |
| 2026-10-07 | Före FAS 5: A5, E8 och FAS 5: permanent nedladdning sker på API 34+ via JobScheduler User-Initiated Data Transfer (UIDT). På API 26–33 finns ingen bakgrundsnedladdning: nedladdningen sker medan appen är öppen, med en tydlig text om det. Ingen WorkManager-reserv byggs, eftersom den inte kan testas på någon tillgänglig enhet. | 0013 |
| 2026-10-07 | Före FAS 5: FAS 10: sessioner som pågår vid tvångsstopp går förlorade. Löpande sparning utreds i FAS 10. | 0018 |
| 2026-10-07 | Före FAS 5: E8: permanenta PDF:er lagras i `filesDir/issues/` | 0013 |
| 2026-10-07 | Efter FAS 5 (v0.5.1): C3: nytt fält `downloadQueuedAt` i `IssueLocalState`, som bestämmer ordningen i nedladdningskön | 0013 |
| 2026-10-07 | Efter FAS 5 (v0.5.1): E9: en PDF-överföring åt gången i hela appen, genom en gemensam `TransferCoordinator`. "Läs" går före kön, som pausas och återupptas. "Läs" på ett nummer som laddas ned väntar på den nedladdningen. | 0013 |
| 2026-10-07 | v0.6.1: G4: appens `versionName` och `versionCode` följer taggarna och läses från `BuildConfig` | — |
| 2026-10-07 | v0.6.1: C5 och E11: `backupFormat` 2. `Bookmark` får `bookmarkId` (UUID) och `deletedAt` (mjuk radering). Import matchar bokmärken på `bookmarkId`, och ett borttaget bokmärke förblir borttaget. Format 1 går fortfarande att importera. | 0012 |
| 2026-10-07 | v0.6.1: C5 och E11: `seenPages` och bokmärkenas PDF-versionsfält (`pdfSize`, `pdfEtag`, `pdfLastModified`) tillagda, så att planen stämmer med koden | 0012, 0018 |
| 2026-10-08 | Efter FAS 7: C5: borttagna bokmärken (rader med `deletedAt`) rensas inte automatiskt | 0012 |
| 2026-10-08 | Efter FAS 7: D4: källa och importdatum för det aktiva indexet sparas och visas. C7: "Uppdatera nedladdning" för ett nedladdat nummer vars arkivfil har ändrats; filen raderas aldrig automatiskt. | 0019 |
| 2026-10-08 | Före FAS 8: FAS 8 delad i 8A (pilot på 11 nummer, två tokenizer-varianter, facit och mätning) och 8B (fullkörning, efter godkänd pilot) | 0010 |
| 2026-10-08 | Efter FAS 8A: tokenizer `unicode61 remove_diacritics 0` | 0010 |
| 2026-10-08 | Efter FAS 8A: `rawText` behålls i search.db, åtminstone genom FAS 9 | — |
| 2026-10-08 | Efter FAS 8A: D2 uppdaterad till den verifierade designen: `pages.id`, nya `meta`-nycklar (bland dem `normalizerVersion`) och `issues` med `ocrPdfSize`, `ocrPdfEtag` och `ocrPdfSha256` | 0010 |
| 2026-10-08 | Efter FAS 8A: `pageCount` är PDF:ens sidantal (C7), även när det skiljer sig från antalet skannade sidor | — |
| 2026-10-08 | Efter FAS 8A: "C64" mot "C-64" hanteras i frågelagret i FAS 9, inte i indexet | 0010 |
| 2026-10-08 | Före FAS 8B: normaliseringen får inte bero på vilka andra nummer som byggs. Ordlistan fryses i `tools/data/normalizer_lexicon_v1.txt` med `normalizerVersion` 1. | 0004 |
| 2026-10-08 | FAS 8B: `normalizerVersion` 2 efter granskat stickprov; `meta` har också `lexiconVersion`. Ordlistan är fortsatt version 1. | 0004 |
| 2026-10-08 | FAS 8B: inventory 2026-10-06-005 har uppmätt `pageCount` för alla 185 nummer | — |
| 2026-10-09 | FAS 8B: not under "Senare – innehållsanalys och AI/RAG" om att blockordningen i search.db inte alltid följer artiklarna | 0004 |
| 2026-10-09 | FAS 9: E10: frågelagret (citattecken kring varje ord, OCH mellan ord, prefix med stjärna och minst tre tecken, "C64"/"C-64"/"C 64" som alternativ), sökning medan man skriver och fråga i två steg (rangordning, därefter textutdrag per synlig rad) | 0020 |
| 2026-10-09 | FAS 9: E10: träffarna grupperas per nummer, med sorteringen Relevans eller Äldst först och filtren publikation, år, favoriter och nedladdade. Läsaren har en träffrad som hoppar mellan träffarna i numret. | 0020 |
| 2026-10-09 | FAS 9: D5: search.db importeras till `filesDir/data/search.db` med kontroll och atomiskt byte, och kan tas bort i inställningarna | 0020 |
| 2026-10-09 | Före FAS 10: G6: de instrumenterade testerna körs med en egen testkörare mot isolerad databas, inställningar, filesDir och cacheDir. `tools/device_test.sh` kör hela sviten och jämför ett fingeravtryck av användarens data före och efter. | 0021 |
| 2026-10-09 | FAS 10: C6: sessioner sparas löpande (var 30:e sekund, efter sidbyte, i bakgrunden och vid stängning). `durationSeconds` är aktiv tid med 5 minuters inaktivitetsgräns. Tabellen är oförändrad, så ingen migrering behövdes. | 0021 |
| 2026-10-09 | FAS 10: E12: definitioner av lästa sidor, lästid, lässtatus, årgångsprogress, aktivitet per vecka och mest lästa årgång. Lästa sidor räknas ur `seenPages`, inte ur `pagesViewed`. | 0021 |
| 2026-10-09 | FAS 10: E10: sökord skrivs i loggen bara i debug-bygget | 0020 |
| 2026-10-09 | FAS 11: B4 och ADR 0009 fastställda: HQ är originalskanningar per sida via FTP, Large PDF används inte. C2 och D1: nya valfria fält `hqPageMap` (på användarens beslut) och `hqPageRotation` (tillagt under fasen, sedan bildjämförelsen visat att 97 liggande sidor har skanningen vriden; förslag som användaren får ta ställning till i fasrapporten). E9: HQ genom `ScanSource` och `TransferCoordinator`, cache på 500 MB. | 0009, 0022 |
| 2026-10-09 | Före FAS 11: Gradle 9.8.1 (AGP 9.4 kräver minst 9.6.0). | – |
| 2026-10-09 | v0.10.1: C6 och E12: sessioner delas vid lokal midnatt. Mest lästa årgång visar alla årgångar med lika många sidor. Årgångsradens procent skrivs "N % sidor". | 0021 |
