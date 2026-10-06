# ADR 0015: Bildladdning och cache för omslag

## Status
Accepted

## Context
Biblioteket visar omslag för 185 nummer. Omslagen är webbplatsens sidbild 1 (`coverUrl`, ADR 0008): JPEG-filer på 0,2–2,3 MB, median 1,2 MB, cirka 950 × 1340 px. I ett rutnät med tre kolumner är ett kort ungefär 440 px brett på en telefon.

Arkivet drivs av en privatperson. Planen (A4) kräver att servern belastas försiktigt. Biblioteket ska också fungera utan nät (FAS 2).

## Decision
**Bibliotek:** Coil 3.6.3 med OkHttp 5.5.0 (`coil-compose`, `coil-network-okhttp`). En enda `ImageLoader` för hela appen, skapad i `CoverImageLoader`.

**Adresser:** bildladdaren får `coverUrl` exakt som den står i inventory (`coverImageData`). Appen bygger aldrig adresser.

**Cache:**

- Diskcache på 300 MB i `cacheDir/cover_cache`. Alla 185 omslag i original ryms (cirka 220 MB).
- Minnescache enligt Coils standard.
- En bild som finns i diskcachen hämtas inte igen och kontrolleras inte mot servern. Coil 3 bryr sig inte om serverns `Cache-Control: no-cache` om man inte lägger till en särskild modul, och det gör vi inte.

**Hänsyn till servern:**

- Högst 4 förfrågningar åt gången. Gränsen sätts på två ställen: OkHttps `Dispatcher` (`maxRequests` och `maxRequestsPerHost`) och en egen `ConcurrencyLimitInterceptor` med en semafor, som gäller oavsett hur anropet startats.
- Bara omslag som faktiskt visas laddas. Rutnäten är lata listor. Ingen förhandsladdning.
- Varje bild hämtas en gång.

**Storlek:** varje förfrågan anger kortets storlek i pixlar, och Coil avkodar bilden nedskalad till den. Originalet avkodas aldrig i full upplösning i ett rutnät.

**Visning enligt `coverFlags`:** `CoverTransform.fromFlags` översätter flaggorna till en vridning och en beskärning, och `CoverFlagsTransformation` tillämpar dem på den avkodade bilden: vridning först, sedan beskärning. Tillåtna värden är `rotate_cw`, `rotate_ccw`, `rotate_180`, `crop_left_half` och `crop_right_half` (`docs/inventory.schema.json`). Koden känner inte till något enskilt nummer. För en vriden eller beskuren bild begärs en större källa, så att resultatet blir skarpt i kortets storlek.

1986 nr 1 har `["crop_right_half", "rotate_cw"]`: sidbild 1 är ett liggande uppslag som står på högkant, med framsidan överst. Vriden medurs hamnar baksidan till vänster och framsidan till höger.

**Platshållare:** saknas `coverUrl`, eller misslyckas hämtningen, visas en platshållare i samma format (0,72) med publikation, år och nummer. Platshållaren ligger alltid under bilden och syns tills bilden har ritats. Fel visas inte för användaren och leder inte till omförsök i en slinga.

## Reason
- Coil är byggt för Compose och Kotlin, har diskcache och nedskalning inbyggt, och är det planen pekar ut (A5).
- 300 MB räcker för hela arkivet med marginal, och ligger i `cacheDir` så att systemet kan frigöra utrymmet vid behov.
- Fyra samtidiga förfrågningar fyller en skärm (9–12 omslag) på någon sekund utan att servern får en skur av anrop vid snabb rullning.
- En egen spärr utöver dispatchern gör gränsen oberoende av hur bildbiblioteket anropar OkHttp, och den går att testa (`ConcurrencyLimitInterceptorTest`).
- Att inte kontrollera cachade bilder mot servern ger noll anrop för redan sedda omslag och gör att biblioteket fungerar utan nät.

## Consequences
- Appen har behörigheten `INTERNET`.
- Byts ett omslag i arkivet syns det inte förrän cachen rensas. Rensning från inställningarna kommer i FAS 5.
- Diskcachen innehåller originalfilerna, inte de nedskalade bilderna. Nedskalningen görs om vid varje avkodning som inte träffar minnescachen.
- Systemet kan tömma `cacheDir`. Då hämtas omslagen igen när de visas.
- Ett omslag som misslyckats försöker igen först nästa gång kortet visas.
- I debug-byggen loggas varje förfrågan med antalet aktiva (`DmzCovers` i logcat), och Coils fel loggas på varningsnivå.
- Compose-testerna kräver Espresso 3.7.0 uttryckligen. Den version som följer med Compose fungerar inte på Android 16.
