# ADR 0010: Tokenizer och diakritiska tecken i sökindexet

## Status
Accepted (2026-10-08, efter godkänd pilot i FAS 8A).

## Context
Sökindexet (`search.db`, plan D2) använder FTS5 med tokenizern `unicode61` (ADR 0006). Den har en inställning, `remove_diacritics`, som avgör om bokstäver med diakritiska tecken ska räknas som sin grundbokstav:

- `remove_diacritics 0`: å, ä och ö är egna bokstäver. "får" och "far" är olika ord.
- `remove_diacritics 2`: tecknen tas bort före indexering och sökning. "får", "far", "för" och "for" blir samma ord.

På svenska är å, ä och ö egna bokstäver, så det första ger bättre precision. Det andra skulle kunna rädda träffar där OCR:en har tappat prickarna. Planen (E10) lämnade valet till en mätning på verklig text.

Piloten i FAS 8A byggde båda varianterna av 11 nummer (585 sidor) ur båda PDF-generationerna och HighScore, och jämförde dem mot ett facit med 29 sökord (`tools/testdata/search_golden.json`). Varje förväntad sida i facit är kontrollerad mot en rendering av PDF-sidan.

## Decision
**`unicode61 remove_diacritics 0`.**

Tokenizern skrivs i `meta.tokenizer` i `search.db`, så att appen kan visa och kontrollera vilken som användes.

## Reason
Mätningen (`data/search_pilot.json`):

| | `remove_diacritics 0` | `remove_diacritics 2` |
|---|---|---|
| Sökord som hittar alla förväntade sidor | 29 av 29 | 29 av 29 |
| Irrelevanta träffar totalt (sidor där ordet inte står som det stavas) | 0 | 155 |
| "här": träffar, varav irrelevanta | 357, 0 | 495, 138 |
| "får": träffar, varav irrelevanta | 349, 0 | 356, 7 |
| "för": träffar, varav irrelevanta | 542, 0 | 549, 7 |
| Rangordning för förväntad sida: "får", "för", "här" | 1, 1, 3 | 3, 2, 5 |
| FTS-index, piloten | 1,76 MB | 1,74 MB |
| `search.db`, piloten | 8,65 MB | 8,63 MB |
| Byggtid | samma | samma |

- **Varianten med 2 hittar inget som varianten med 0 missar.** Alla 29 sökord hittar rätt sida i båda.
- **Den ger sämre resultat för vanliga svenska ord.** "här" får 138 extra sidor som bara innehåller "har", och den förväntade sidan sjunker i rangordningen för alla tre orden.
- **De extra träffarna är nästan aldrig räddade OCR-fel.** För "får" och "för" är de engelskans "for" och "far" och ordet "för". Det enda fall där tillägget var meningsfullt är "hårddisk", som också hittade två annonser med stavningen "harddisk".
- **Storleken skiljer sig med 0,2 procent** och är inget argument åt något håll.
- OCR-felen i arkivet är oftast av annat slag än tappade prickar (fel bokstav, delade eller hopslagna ord), och dem hjälper ingen av varianterna mot.

## Consequences
- En sökning på "har" hittar inte "här", och "ide" hittar inte "idé". Den som söker måste skriva å, ä, ö och é. Det är det förväntade på svenska.
- Skiftläge spelar ingen roll, även för Å, Ä och Ö.
- Ord där OCR:en har tappat prickarna ("harddisk" för "hårddisk") hittas bara med den felaktiga stavningen. Om det visar sig vara ett problem i praktiken kan appen i FAS 9 erbjuda en bredare sökning som tillval, till exempel genom att söka på flera stavningar. Det kräver inget nytt index.
- Bindestreck och andra skiljetecken delar ord i båda varianterna. "CD-ROM" söks som frasen "cd rom", och "C-64" som "c 64". "C64" och "C-64" är därför olika sökningar (149 respektive 39 sidor i piloten). Appen bör sätta varje sökord inom citattecken, så att bindestreck och siffror inte tolkas som FTS5-syntax. "C64" mot "C-64" hanteras i frågelagret i FAS 9, inte i indexet.
- Byts tokenizer senare måste `search.db` byggas om. Det tar några minuter när PDF-cachen finns.
