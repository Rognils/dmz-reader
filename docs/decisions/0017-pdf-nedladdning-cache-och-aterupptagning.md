# ADR 0017: PDF-nedladdning, läs-cache och återupptagning

## Status
Accepted

## Context
"Läs nu" (planens FAS 3) hämtar numrets läs-PDF till telefonen och öppnar den lokalt. Filerna är mellan 15 och 563 MB (median 52 MB) och hämtas från en server som drivs av en privatperson. Servern stöder Range och skickar ETag och Last-Modified (ADR 0008).

Permanent nedladdning för offline-läsning är en egen funktion i FAS 5. Det här beslutet gäller den tillfälliga läs-cachen.

## Decision

### Egen HTTP-klient
PDF:er hämtas med en egen OkHttp-klient, skild från bildladdarens (ADR 0015): anslutningstid 15 s, lästid 60 s och ingen total tidsgräns. En fil på 563 MB får ta den tid den tar.

### Filer
Allt ligger i `cacheDir/pdf/`:

| Fil | Innehåll |
|---|---|
| `{issueId}.pdf` | en komplett PDF |
| `{issueId}.pdf.part` | en pågående eller avbruten nedladdning |
| `{issueId}.pdf.part.validator` | ETag (eller Last-Modified) för `.part`-filen |

En `.part`-fil döps om till `.pdf` först när dess storlek stämmer med den servern uppgav. **En `.pdf` är därför alltid komplett.**

### Återupptagning
Finns en `.part`-fil med validerare skickas `Range: bytes=N-` och `If-Range: <validerare>`.

| Svar | Åtgärd |
|---|---|
| 206 med rätt startposition | fortsätt skriva i `.part` |
| 200 | filen har ändrats, eller så stöds inte Range: börja om från början |
| 206 med annan startposition | avvisas |
| 416 | `.part` är längre än filen: kasta den |

En `.part`-fil utan validerare används inte, eftersom det inte går att veta att resten hör till samma fil.

### Avbrott
Nedladdningen går att avbryta när som helst. Avbrottet bryter även en läsning som väntar på nätet. `.part`-filen ligger kvar till nästa försök.

### Utrymme
Före start kontrolleras att det finns plats för det som återstår plus tio procent av filens storlek, först mot storleken i inventory och sedan mot serverns svar. Annars visas ett felmeddelande med hur mycket som behövs och hur mycket som är ledigt.

### En nedladdning åt gången
`PdfRepository` har ett lås. Ingen förhandshämtning av andra nummer.

### Läs-cachen
Taket är 1,5 GB. När cachen är för stor tas de minst nyligen öppnade filerna bort först. Numret som är öppet eller laddas ned tas aldrig bort. Plats görs innan en nedladdning börjar.

### Utbytta filer (planens C7)
Efter en nedladdning sparas storlek, ETag och Last-Modified i `IssueLocalState` (`observedPdfSize`, `observedPdfEtag`, `observedPdfLastModified`). Skiljer sig något av dem från inventory loggas en varning. Läsningen blockeras inte. Sidantalet som mäts när PDF:en öppnas sparas i `observedPageCount`.

### Fel
Utan nät, vid HTTP-fel, fel längd eller för lite utrymme visas ett meddelande på svenska med knappen "Försök igen". En PDF som inte går att öppna tas bort, så att nästa försök hämtar den på nytt.

## Reason
- Regeln att `.pdf` alltid är komplett gör att läsaren aldrig öppnar en halv fil och att ingen separat markering behövs.
- `If-Range` gör återupptagningen säker: om filen har bytts på servern svarar den med hela den nya filen i stället för en del som inte passar ihop med den gamla.
- Att återuppta sparar både användarens tid och serverns bandbredd. Ett avbrott vid 66 % av 563 MB kostar annars 370 MB extra.
- 1,5 GB rymmer arkivets största nummer tre gånger om, eller ett trettiotal typiska.

## Consequences
- Läs-cachen ligger i `cacheDir`, som systemet får tömma när utrymmet tryter. Då hämtas numret igen. Offline-läsning som går att lita på kommer i FAS 5.
- En halv nedladdning tar plats tills den slutförs eller rensas. Den räknas in i taket och rensas som andra filer.
- `File.usableSpace` används för utrymmeskontrollen. Lint föreslår `StorageManager.getAllocatableBytes`, som också räknar utrymme systemet kan frigöra, men den finns först från API 26 med extra anrop och behövs inte för en cachekatalog.
- Jämförelsen mot inventory bygger på vad servern uppger. ETag från en annan serverkonfiguration skulle ge falska varningar.
- Verifierat på telefonen med 1995 nr 15 (563 123 977 byte): avbruten vid 369 MB, återupptagen därifrån, och den färdiga filen hade exakt rätt storlek. Hastigheten var cirka 17–28 MB/s över wifi.
- Testerna kör mot en lokal testserver (MockWebServer) och täcker hel nedladdning, återupptagning, 200 på Range, fel längd, avbrott, för lite utrymme och rensning.
- Samma nedladdningsmotor används sedan FAS 5 för permanenta nedladdningar i `filesDir/issues/` (ADR 0013). Filnamnsreglerna (`.pdf`, `.pdf.part`, `.pdf.part.validator`) är gemensamma i `PdfFiles`.
