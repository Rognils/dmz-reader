# ADR 0011: PDF-rendering med PdfRenderer och högupplösta utsnitt

## Status
Accepted

## Context
Läsaren ska visa arkivets PDF:er sida för sida, med zoom, uppslag och miniatyrer (planens E7). PDF:erna är skannade tidningssidor i två generationer:

| | v2 (1986 – 1991 nr 21) | äldre (1991 nr 22 – 1995, HighScore) |
|---|---|---|
| Bild per sida | en JPEG, 150 dpi, cirka 1 680 px bred | lager av JPEG 2000 och JBIG2, 300–400 dpi, cirka 2 500 px bred |
| Filstorlek | median 24 MB | median 102 MB, störst 563 MB |

En sida i 400 dpi är över 100 MB som okomprimerad bild. Den får aldrig renderas i full upplösning.

### Alternativ
- **Androids `PdfRenderer`**: finns på alla telefoner från API 21, inga beroenden, renderar en sida eller ett utsnitt till en bitmap.
- **`androidx.pdf`**: är i beta (1.0.0-beta01), kräver `minSdk` 28 och bygger på ett eget fragment med eget gränssnitt. Appen har `minSdk` 26 och ett eget gränssnitt med uppslag, tryckzoner och miniatyrremsa. Passar inte.
- **Tredjepartsbibliotek** (pdfium-omslag, MuPDF): större APK och fler beroenden. Planen säger att de bara prövas om `PdfRenderer` inte räcker.

## Decision
**`PdfRenderer`**, inslaget i klassen `PdfDocument`.

### En renderartråd
`PdfRenderer` klarar bara en öppen sida åt gången och är inte trådsäker. All åtkomst går genom en enda tråd (`pdf-renderer`).

### Tre slags rendering
1. **Grundbild i skärmupplösning.** Hela sidan, så bred som den visas (1 440 px på testtelefonen).
2. **Förhandsbild** i en fjärdedels upplösning. Den visas direkt medan grundbilden renderas.
3. **Högupplöst utsnitt.** Över 1,5 gångers zoom renderas bara det som syns på skärmen, i en bild av skärmens storlek, med `Page.render` och en transformmatris. Utsnittet läggs ovanpå grundbilden när gesten har stannat (160 ms). En hel sida renderas aldrig i full upplösning.

Över 3 gångers zoom renderas utsnittet i 3 gångers upplösning och förstoras på skärmen. Tre gånger skärmbredden är cirka 4 300 px per sida, mer än skanningarna innehåller, så ingenting går förlorat. Utan gränsen fick PDF-motorn arbeta med en sida på över 11 000 pixlars bredd vid maximal zoom (8 gånger), och utsnitten ur den äldre generationen blev aldrig klara.

Sidan fylls med vitt före rendering, eftersom `PdfRenderer` ritar på genomskinlig botten.

### Ordning och avbrott
- Sidor renderas före miniatyrer (`RenderPriority`).
- En rendering som ingen längre väntar på hoppas över, så att kön inte fylls av sidor som användaren har bläddrat förbi.
- En påbörjad rendering körs alltid klart; `PdfRenderer` får inte rita i en bild som har frigjorts.
- Sidornas storlek läses in när PDF:en öppnas, så att layout och gester aldrig väntar bakom en rendering.

### Minne
- **Bitmaps frigörs uttryckligen.** Pixlarna ligger utanför Java-heapen, så skräpsamlaren märker inte att minnet tar slut. `PageBitmapCache` räknar användare per bild och anropar `recycle()` när en bild har fallit ur cachen och inte visas. Utsnitt frigörs så fort de byts ut. En avbruten rendering frigör sin bild.
- **Cachen** för grundbilder är en åttondel av appens minnesgräns (32 MB av 256 MB på testtelefonen).
- **PDF-filen öppnas om var fjärde renderad sida.** PDF-motorn sparar avkodade bilder för varje sida den ritat så länge dokumentet är öppet. Att öppna om filen tar 11–116 ms och släpper det.
- ±1 sida eller uppslag förrenderas (`beyondViewportPageCount = 1`).

### Miniatyrer
Miniatyrremsan visar de faktiska PDF-sidorna, renderade 160 px breda. De sparas som JPEG i `cacheDir/thumbs/{issueId}-{filstorlek}/`, och bara de som syns renderas. Omslagsrättningar (`coverFlags`) används aldrig i läsaren.

## Mätningar
OnePlus 7 Pro, Android 16, debug-bygge, skärm 1 440 × 3 120 px.

**Renderingstid** (median / 90:e percentilen / max, i ms):

| | 1986 nr 1 (v2, 15 MB) | 1995 nr 14 (äldre, 116 MB) | 1995 nr 15 (äldre, 563 MB) |
|---|---|---|---|
| Förhandsbild | 69 / 103 / 119 | 209 / 325 / 439 | 374 / 695 / 720 |
| Grundbild | 107 / 121 / 226 | 828 / 1 003 / 1 420 | 1 856 / 2 004 / 2 639 |
| Högupplöst utsnitt | 79 / 165 / 165 | 435 / 457 / 457 | 591 / 600 / 600 |
| Miniatyr | cirka 40 | cirka 90 | cirka 145 |
| Sidstorlekar vid öppning | under 100 | 508 | 1 109 |

Värdena för 1986 nr 1 och 1995 nr 14 kommer från en genomläsning av alla sidor (28 respektive 84), 1995 nr 15 från 13 sidor.

**Minne vid maximal zoom och panorering:**

| | 1986 nr 1 | 1995 nr 14 | 1995 nr 15 |
|---|---|---|---|
| PSS (`dumpsys meminfo`) | 412 MB | 378 MB | 381 MB |
| Native heap | 107 MB | 107 MB | 115 MB |
| Java heap | 21 MB | 31 MB | 24 MB |
| Grafik | 178 MB | 134 MB | 138 MB |
| Topp för native heap under körningen | 108 MB | 117 MB (genomläsning) | 265 MB (under rendering) |
| Topp för Java heap | 31 MB | 29 MB | 30 MB |

Före åtgärderna ovan låg native heap på 660–815 MB och PSS på cirka 1 GB för 1995 nr 15, och växte för varje sida.

## Reason
- `PdfRenderer` klarar allt läsaren behöver, inklusive arkivets största fil, utan krasch eller minnesbrist. Inget skäl finns att byta.
- Utsnitt i skärmstorlek ger skarp text vid zoom till en fast minneskostnad (18 MB), oavsett hur stor sidan är.
- Förhandsbilden gör att ett sidbyte i den äldre generationen visar något efter 0,2 s i stället för 0,8 s.

## Consequences
- **Sidbyten i den äldre generationen är långsamma.** Grundbilden tar 0,8 s i median och 1,9 s för 1995 nr 15. JPEG 2000 är dyrt att avkoda. Förhandsbilden och förrenderingen av nästa sida döljer en del av det, men planens mål "sidbyte känns omedelbart" (G7) nås bara för v2-generationen.
- Att öppna ett nummer ur den äldre generationen tar upp till 1,1 s extra för sidstorlekarna.
- Vid 8 gångers zoom syns skanningens upplösning, särskilt i v2-generationen (150 dpi). Det är HQ-läget (FAS 11) till för.
- `PdfRenderer` är telefonens egen PDF-motor. Rendering och hastighet kan skilja mellan Android-versioner.
- Appen måste hålla reda på vem som använder varje bitmap. En bild som frigörs för tidigt ger en krasch. Frigörandet fördröjs därför 150 ms.
- Mätningarna är gjorda på en telefon och i ett debug-bygge.
