# ADR 0020: Fulltextsökning i appen

## Status
Accepted (FAS 9, 2026-10-09)

## Context
Sökindexet `search.db` byggs på datorn (ADR 0004) och är 149 MB med 11 293 sidor. Appen ska importera det, söka i det medan användaren skriver och visa träffarna så att de går att överblicka, även när ett vanligt ord finns på tusentals sidor (planens E10, steg 2).

Mätningarna i FAS 8 visade tre saker som styr utformningen: textutdragen står för två tredjedelar av söktiden, en fråga som matchar om hela mängden för att hämta några utdrag kan ta en sekund, och en prefixsökning på en enda bokstav matchar nästan alla sidor.

## Decision
- **Sökindexet är en egen, skrivskyddad SQLite-fil** i `filesDir/data/search.db`, öppnad med `BundledSQLiteDriver` och helt skild från appens Room-databas. En enda anslutning används, och alla frågor går genom en tråd.
- **Import i steg**, som för arkivindexet (ADR 0019): kopiering till `search.db.part` med förlopp, kontroll (`PRAGMA quick_check`, tabeller, `searchSchemaVersion` 1, en provsökning, inte tomt), och först därefter byte. Den tidigare filen ligger kvar som `search.db.previous` tills den nya har öppnats. Vid start städas en avbruten import upp.
- **Frågelagret är ren Kotlin** (`parseFullTextQuery`): varje sökord sätts inom citattecken, flera ord betyder OCH, användarens citattecken ger frassökning, prefix kräver en uttrycklig stjärna och minst tre tecken, och "C64", "C-64" och "C 64" söks som alternativ. Styrtecken tas bort. Ingen användartext kan ge ett syntaxfel i FTS5.
- **Sökningen går i två steg.** Steg 1 rangordnar alla träffar med bm25 utan textutdrag och utan att läsa tabellen `pages`: frågan ger bara rad-id och poäng. Vilket nummer och vilken sida ett rad-id står för slås upp i en tabell som läses in en gång när indexet öppnas. Steg 2 hämtar textutdraget för en enda rad (`MATCH ? AND rowid = ?`), och bara för rader som syns.
- **Filter, gruppering och sortering görs i Kotlin** på rangordningen, tillsammans med katalogen och användardatan från Room. Träffar i nummer som saknas i det aktiva arkivindexet döljs (D3).
- **Sökningen körs medan man skriver**, från två tecken och efter 300 ms paus. En ny sökning avbryter den pågående.
- **Träffarna grupperas per nummer** med de tre bästa sidorna, och numren läggs till i listan 20 åt gången.
- **Träffraden i läsaren och "Sök i numret"** delar en vymodell som lever med läsaren respektive nummervyn. Sökningen skickas med till läsaren som ett argument i adressen.
- **Bytt PDF** upptäcks genom att `issues.ocrPdfSize` och `ocrPdfEtag` i sökindexet jämförs med det aktiva arkivindexet.

## Reason
- Rangordning utan utdrag tar 4–96 ms för de frågor som mättes mot hela arkivet, och ett utdrag per synlig rad tar någon millisekund. Det ger samma svarstid oavsett om ordet finns på 200 eller 8 000 sidor.
- Rad-id-tabellen (11 293 rader) kostar lite minne men gör att steg 1 aldrig behöver läsa de stora textraderna.
- Att filtrera i Kotlin är enklare och säkrare än att koppla ihop två databaser: sökindexet får förbli skrivskyddat och ovetande om användardata, och katalogen är under 200 nummer.
- Frågelagret prövas med ett slumptest som matar 4 000 skräptexter genom riktig FTS5. Det hittade ett fel som ingen regel förutsåg: ett NUL-tecken klippte av uttrycket mitt i ett citattecken.
- En prefixgräns på tre tecken håller den dyraste frågan borta. "a*" tog 0,4 s i mätningen i FAS 8.

## Consequences
- Sökindexet måste importeras av användaren. Utan det fungerar metadata-sökningen som förut, och sökvyn visar hur man importerar.
- Under en import behövs plats för två kopior av filen, cirka 300 MB.
- Hela rangordningen hålls i minnet under en sökning: högst 11 293 rader med rad-id och poäng.
- Sorteringen "Relevans" jämför bm25-poäng mellan sidor. Ett nummer rankas efter sin bästa sida, inte efter hur många träffar det har.
- Sökorden skrivs i appens logg (`DmzSearch`) tillsammans med svarstiden. Appen är privat; om den någonsin distribueras ska det tas bort.
- "C64"-regeln gäller beteckningar med högst fyra bokstäver följda av siffror. "Amiga 500" söks som två ord, inte som alternativ till "Amiga500".
- Byts formatet på `search.db` höjs `searchSchemaVersion`, och appen vägrar då äldre eller nyare filer med ett tydligt meddelande.
