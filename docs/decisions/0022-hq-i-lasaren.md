# ADR 0022: HQ i läsaren – FTP-klient, cache och avkodning

## Status
Accepted (FAS 11, 2026-10-09)

## Context
ADR 0009 fastställer att HQ är originalskanningen för sidan som visas, hämtad via FTP. Skanningarna är stora: 2–37 MB per fil, och upp till 4 890 × 7 000 pixlar (JPEG) eller 3 340 × 4 820 (PNG, cirka 30 MB). En sådan bild tar över 130 MB i minnet om den avkodas i sin helhet.

Appen hade redan en läsare med gester, zoom och utsnitt i hög upplösning (ADR 0011), en kö där högst en PDF-överföring går åt gången (`TransferCoordinator`, ADR 0013) och en läs-cache. HQ skulle passa in i det utan att ändra hur läsdata förs.

## Decision

### Hämtning
- Skanningar hämtas genom gränssnittet `ScanSource`. Appen använder `FtpScanSource`, byggd på Apache Commons Net. Testerna använder en falsk källa.
- FTP: passivt läge, binär överföring, arkivets publika konto ur byggkonfigurationen (`ARCHIVE_FTP_USER`, `ARCHIVE_FTP_PASSWORD`).
- **En anslutning.** Den återanvänds för följande sidor och stängs efter 30 sekunder utan användning. Alla anrop går genom ett lås. En anslutning som servern har stängt ersätts med en ny utan att användaren märker det.
- **All HQ-trafik går genom `TransferCoordinator` som en läsning.** Högst en överföring mot arkivet pågår åt gången, och en HQ-sida som användaren väntar på går före nedladdningskön, som pausas och sedan fortsätter.
- **Bara sidan som visas hämtas**, och först när den visas. Ingenting hämtas i förväg. Lämnar användaren sidan avbryts hämtningen; det hämtade ligger kvar som `.part` och fortsätts med REST om sidan visas igen.
- HQ har inget villkor på nätverkstyp. Det är en interaktiv hämtning, som Läs.

### Cache
- `cacheDir/hq/{issueId}/{filnamn}`, med ett tak på 500 MB. När taket överskrids tas de filer bort som användes för längst sedan (filens ändringstid sätts vid varje användning). Påbörjade hämtningar tas bort först.
- En cachad sida visas utan nät.
- Inställningar → Lagring visar HQ-cachens storlek och har knappen "Rensa HQ-cache".

### Visning
- `ScanImage` öppnar filen med `BitmapRegionDecoder` (JPEG och PNG). Hela sidan avkodas bara nedskalad, med `inSampleSize`, till skärmens storlek. Vid zoom avkodas bara det synliga utsnittet, i en bild av skärmens storlek. **Hela skanningen avkodas aldrig i full upplösning.**
- Nedskalningen väljs så att det avkodade utsnittet har minst målets upplösning och mindre än dubbla målet på varje ledd. Ett utsnitt tar därmed som mest fyra skärmbilder i minne under avkodningen och en skärmbild därefter.
- Läsarens `ZoomablePages` tar en `PageSource` i stället för ett PDF-dokument. Gester, zoom, dubbeltryck och sidbyte är samma kod för PDF och HQ, och zoom och läge behålls när källan byts.
- Skanningen vrids enligt `hqPageRotation`, både i översikten och i utsnitten.
- Högst tre skanningar hålls öppna samtidigt.

### HQ-läget
- HQ-knappen i toppraden slår på eller av HQ-läget för det öppna numret. Läget sparas inte.
- I HQ-läge visas en sida i taget, även när telefonen ligger ned. HQ av ger uppslaget tillbaka.
- En rad under toppraden visar förloppet ("HQ · hämtar 1,2 av 4,0 MB · 30 %"), "HQ" när skanningen visas, "HQ finns inte för den här sidan" eller felet med "Försök igen". I alla lägen utom det andra visas PDF-sidan.
- Läspositionen, lästa sidor (`seenPages`) och lässessionerna förs av samma kod som förut.

### Data
- `Issue` får kolumnerna `hqPageMap` och `hqPageRotation` (databasversion 8, automatisk migrering som bara lägger till kolumner i katalogtabellen). De lagras som JSON-text och tolkas av `domain/HqMapping.kt`.
- En tabell som inte går att tolka ger ingen HQ för numret. Ett filnamn i tabellen får inte kunna peka ut ur numrets katalog.

## Reason
- Ett gränssnitt för källan gör FTP utbytbart (en HTTP-spegel är planens reservlösning) och gör läsaren testbar utan nät.
- Att återanvända `TransferCoordinator` ger regeln "en överföring åt gången" utan ny kod, och prioriteten är redan prövad för Läs.
- Ingen förhandshämtning håller belastningen på arkivet nere: en bläddring genom ett nummer i HQ-läge hämtar bara de sidor användaren stannar på.
- `BitmapRegionDecoder` är det enda sättet i plattformen att läsa en del av en bild utan att avkoda hela.
- En gemensam `PageSource` gör att HQ inte får en egen, sämre prövad gestkod.

## Consequences
- Avkodningen tar tid som märks: uppmätt på OnePlus 7 Pro cirka 0,5 sekunder för översikten och 0,3–0,5 sekunder för ett utsnitt, både för den största JPEG-filen (4 890 × 7 000) och för en PNG på 30 MB. En suddigare förhandsbild visas under tiden.
- En HQ-sida syns en kort stund efter att användaren har bläddrat till den, eftersom den inte hämtas i förväg. PDF-sidan visas under tiden.
- Byter användaren sida mitt i en hämtning stängs FTP-anslutningen, och nästa sida öppnar en ny.
- Systemet kan tömma `cacheDir`. Då hämtas sidorna igen.
- Appen har ett FTP-bibliotek till (cirka 300 kB), och kontot ligger i klartext i appen. Kontot är publikt.
- FTP är okrypterat. Det som överförs är publika tidningssidor.
- HQ-läget gäller per öppning av läsaren. Den som alltid vill ha HQ får slå på det varje gång.
