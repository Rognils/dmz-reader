# Arkivanteckningar – FAS 0

Fakta om DMZArkivet så som det såg ut 2026-10-06. Underlaget är versionshanterat:

| Fil | Innehåll |
|---|---|
| `data/raw_listing.json` | FTP-listning (MLSD) av `Covers`, `OCR PDF`, `Large PDF`, `Original Scans` och `OCR PDF v2`, plus arkivets rot |
| `data/https_probe.json` | HEAD-svar från webbplatsen för varje nummers PDF och första och sista sidbild |
| `data/sample_measurements.json` | sidantal och kontrollsumma från stickproven |
| `data/archive_inventory.json` | resultatet: 185 nummer |

Allt nedan kan räknas fram igen med `python3 tools/archive_inventory.py --offline`.

## 1. Sammanfattning

- Arkivet innehåller **185 nummer i två publikationer**: Datormagazin (171 nummer, 1986–1995) och HighScore (14 nummer, 1993–1994).
- Alla 185 nummer har Large PDF och Original Scans på FTP, och alla har en läs-PDF och sidbilder på webbplatsen.
- **Läs-PDF:en hämtas från webbplatsen**, inte från FTP. Webbplatsen serverar en blandning av två OCR-generationer (avsnitt 5).
- Namngivningen är mycket regelbunden. Av 11 896 filer följer 11 893 en regel; 3 är specialfall.
- Original Scans ligger en fil per sida: 11 297 sidor.

## 2. Publikationer

| publicationId | publicationTitle | Nummer | År | Kommentar |
|---|---|---|---|---|
| `datormagazin` | Datormagazin | 171 | 1986–1995 | Filprefix `DMZ_` |
| `highscore` | HighScore | 14 | 1993 nr 17 – 1994 nr 8 | Numreras efter det Datormagazin-nummer den hör till. `relatedIssueId` pekar dit. Alltid 32 sidor. |

## 3. Namnregler

Arkivets egen nyckel för ett nummer (`sourceKey`) är `ÅÅÅÅNN`. Den återkommer i alla kataloger och på webbplatsen.

| Katalog | Datormagazin | HighScore |
|---|---|---|
| `OCR PDF`, `OCR PDF v2` | `198906.pdf` | `HighScore-199317.pdf` |
| `Large PDF` | `198906-Original.pdf` | `199317-HighScore-Original.pdf` |
| `Original Scans` | `198906-Original/DMZ_1989-06-001.jpg` | `199317-HighScore-Original/HighScore_1993-17_001.jpg` |
| `Covers` | `DMZ_1989-06-001.jpg` | `HighScore_1993-17_001.jpg` |
| Webbens PDF | `/pdf/198906.pdf` | `/pdf/HighScore-199317.pdf` |
| Webbens sidbilder | `/magazines/198906/DMZ_1989-06-001.jpg` | `/highscore/199317/HighScore_1993-17_001.jpg` |

Samma nummer har samma identitet i alla kataloger. Alla 185 nummer kopplas ihop utan undantag.

### issueId

Format (fastställt, ADR 0007): `<publicationId>-<ÅÅÅÅ>-<NN>[-<MM>][b]`

| Fall | Arkivets nyckel | issueId | issueLabel | sortKey |
|---|---|---|---|---|
| Vanligt nummer | `198906` | `datormagazin-1989-06` | Nr 6 | `1989-06` |
| Dubbelnummer | `19881617` | `datormagazin-1988-16-17` | Nr 16–17 | `1988-16` |
| b-utgåva | `198710b` | `datormagazin-1987-10b` | Nr 10b | `1987-10b` |
| HighScore | `HighScore-199317` | `highscore-1993-17` | Nr 17 | `1993-17` |

### Dubbelnummer och specialnummer

- **Dubbelnummer (3):** 1987 nr 4–5, 1988 nr 16–17, 1995 nr 5–6. Nyckeln har åtta siffror. Regeln godtar bara två nummer i följd.
- **b-utgåvor (2):** 1987 nr 10b och 1989 nr 16b. De är egna nummer vid sidan av nr 10 respektive nr 16. Vad "b" står för framgår inte av arkivet. Bara bokstaven b godtas.
- Inget annat slags specialnummer förekommer i filnamnen.

### Avvikelser som har egna regler

Dessa följer inte huvudmönstret men är entydiga, och hanteras av uttryckliga regler. Varje tillämpning redovisas av verktyget och syns som flagga i inventory.

| Avvikelse | Var | Flagga |
|---|---|---|
| Sidnummer med två siffror i skanningarna (`DMZ_1994-16-64.jpg`) | 26 nummer: 1987 nr 7, 8 · 1991 nr 5 · 1992 nr 6, 9, 13, 14, 15, 17, 20, 21, 22 · 1993 nr 1, 3, 4, 10, 11, 13, 17, 18, 19, 22 · 1994 nr 9, 10, 15, 16 | `scan_two_digit_pages` |
| Skanningar i PNG i stället för JPG | 1986 nr 4 | `scan_png` |
| Egen del `Spelbilaga` med egen sidräkning (`DMZ_1986-04-Spelbilaga-001.png` … `-016.png`) | 1986 nr 4 | `scan_parts` |
| Filerna bär bara dubbelnumrets första nummer (`19870405-Original/DMZ_1987-04-001.jpg`, `Covers/DMZ_1987-04-001.jpg`) | 1987 nr 4–5 | `scan_named_after_first_number` |

Alla 26 nummer med tvåsiffriga sidnummer saknar också omslag i `Covers` (avsnitt 5), vilket tyder på att de skannats eller bearbetats i en egen omgång.

### Specialfall (matchar ingen regel)

| Fil | Storlek | Bedömning |
|---|---|---|
| `Covers/DMZ_1986-01.png` | 32,3 MB | Arbetsfil för omslaget, sannolikt den tillsnyggade versionen. Inte kontrollerad. |
| `Covers/DMZ_1986-01.psd` | 316 MB | Photoshop-fil till samma omslag |
| `Original Scans/198601-Original/test.jpg` | 4,0 MB | Testfil, hör inte till sidföljden |

## 4. Statistik per publikation och år

Storlekarna gäller den kanoniska läs-PDF:en (webbplatsen). "Sidor" är antalet filer i Original Scans.

| Publikation | År | Nummer | Saknar FTP-omslag | Varav v2-PDF | PDF min / median / max (MB) | Summa (GB) | Sidor | Sidor per nummer |
|---|---|---|---|---|---|---|---|---|
| Datormagazin | 1986 | 4 | 1 | 4 | 15 / 18 / 23 | 0,07 | 141 | 28–49 |
| Datormagazin | 1987 | 10 | 5 | 10 | 19 / 23 / 33 | 0,24 | 444 | 36–48 |
| Datormagazin | 1988 | 16 | 4 | 16 | 20 / 25 / 26 | 0,38 | 736 | 40–48 |
| Datormagazin | 1989 | 19 | 4 | 19 | 20 / 26 / 28 | 0,47 | 872 | 36–48 |
| Datormagazin | 1990 | 20 | 0 | 20 | 19 / 24 / 31 | 0,49 | 1 362 | 36–104 |
| Datormagazin | 1991 | 22 | 1 | 21 | 22 / 24 / 123 | 0,63 | 1 800 | 80–88 |
| Datormagazin | 1992 | 22 | 9 | 0 | 102 / 114 / 292 | 2,66 | 1 681 | 72–88 |
| Datormagazin | 1993 | 22 | 10 | 0 | 51 / 86 / 180 | 1,92 | 1 455 | 48–79 |
| Datormagazin | 1994 | 22 | 4 | 0 | 50 / 67 / 88 | 1,55 | 1 274 | 46–64 |
| Datormagazin | 1995 | 14 | 0 | 0 | 81 / 115 / 563 | 2,17 | 1 084 | 64–92 |
| HighScore | 1993 | 6 | 0 | 0 | 85 / 87 / 137 | 0,57 | 192 | 32 |
| HighScore | 1994 | 8 | 0 | 0 | 123 / 140 / 144 | 1,10 | 256 | 32 |
| **Totalt** | | **185** | **38** | **90** | **15 / 52 / 563** | **12,27** | **11 297** | **28–104** |

Nummerföljden för Datormagazin är obruten inom varje år (dubbelnumren räknade som två nummer), så inget nummer saknas mitt i en årgång. Om en årgång hade fler nummer än det sista som finns i arkivet går inte att se i filerna.

### Storleken på OCR-PDF:erna (avgör lagringsvalet i FAS 5)

| Urval | Antal | Min | Median | Max | Summa |
|---|---|---|---|---|---|
| **Kanoniska (webbplatsen), alla** | 185 | 15,1 MB | 51,7 MB | 563,1 MB | 12,27 GB |
| – varav v2-generationen (1986 – 1991 nr 21) | 90 | 15,1 MB | 24,2 MB | 33,3 MB | 2,17 GB |
| – varav äldre generationen (1991 nr 22 – 1995, HighScore) | 95 | 50,5 MB | 101,7 MB | 563,1 MB | 10,09 GB |
| FTP `OCR PDF` (används inte) | 174 | 49,0 MB | 95,3 MB | 563,1 MB | 17,43 GB |

- 90 filer är under 50 MB, 48 är över 100 MB och 3 är över 200 MB.
- De fem största: 1995 nr 15 (563 MB), 1995 nr 8 (359 MB), 1992 nr 18 (292 MB), 1993 nr 21 (180 MB), HighScore 1994 nr 3 (144 MB).
- Att ladda ned hela arkivet tar 12,3 GB. En enskild årgång 1992 tar 2,7 GB.

## 5. Saknat material och de två OCR-generationerna

### OCR-PDF

FTP har två kataloger med OCR-PDF:er, och webbplatsen serverar en blandning:

| Källa | Antal | Skapad | Teknik (enligt stickproven) |
|---|---|---|---|
| FTP `OCR PDF` | 174 | april–augusti 2017 | 300–400 dpi, lager av JPEG 2000 och JBIG2 |
| FTP `OCR PDF v2` | 90 (1986 nr 1 – 1991 nr 21) | september 2017 | 150 dpi, en JPEG per sida, Adobe Acrobat Paper Capture |
| Webben `/pdf/<nyckel>.pdf` | 185 | | v2 där den finns (90), annars `OCR PDF` (94), plus 1 avvikande |

- **`OCR PDF` saknar 11 nummer**: 1987 nr 9, 10, 10b · 1988 nr 9, 10, 13, 15 · 1989 nr 2, 4, 5, 16b. Alla 11 finns i `OCR PDF v2` och på webben.
- **Webbplatsen saknar ingen.** Därför är webbens fil den kanoniska (ADR 0008). `ocrPdfFtpSource` i inventory anger vilken FTP-generation webbfilen motsvarar, avgjort på filstorlek.
- **1993 nr 3 avviker:** webbens fil är 89 792 864 byte (Last-Modified 2017-04-29), FTP `OCR PDF/199303.pdf` är 78 922 903 byte (ändrad 2017-07-27). FTP-filen är alltså nyare, men webbversionen gäller enligt beslut. Flagga: `ocr_pdf_differs_from_ftp`, `ocrPdfFtpSource` är `null`, och FTP-filen står kvar i `sourceNames`.

### Omslag (`Covers`)

147 filer för 147 nummer. **38 nummer saknar omslag på FTP** (flagga `no_ftp_cover`):

| År | Nummer utan FTP-omslag |
|---|---|
| 1986 | 4 |
| 1987 | 7, 8, 9, 10, 10b |
| 1988 | 9, 10, 13, 15 |
| 1989 | 2, 4, 5, 16b |
| 1991 | 5 |
| 1992 | 6, 9, 13, 14, 15, 17, 20, 21, 22 |
| 1993 | 1, 3, 4, 10, 11, 13, 17, 18, 19, 22 |
| 1994 | 9, 10, 15, 16 |

- Omslagsfilerna är stora: median 6,9 MB, max 33 MB, totalt 1,2 GB. De passar dåligt i ett omslagsrutnät.
- 104 av 147 är byte-för-byte lika stora som skanningens sida 1, alltså troligen obearbetade kopior. Arkivets egen beskrivning säger att arbetet med omslagen pågår.
- De 38 är de 26 numren med tvåsiffriga sidnummer i skanningarna, de 11 som saknas i `OCR PDF`, samt 1986 nr 4.
- Appen använder därför inte `Covers` i v1 utan webbens sidbild 1, som finns för alla 185 nummer (avsnitt 7).

### Large PDF

Inget nummer saknar Large PDF. Storlek: min 35 MB, median 311 MB, max 1 940 MB, totalt 68,7 GB.

## 6. Strukturen i Original Scans

- En katalog per nummer (`198906-Original`, `199317-HighScore-Original`), 185 kataloger, inga underkataloger.
- **En fil per sida**, numrerad från 001 utan luckor i alla 185 kataloger.
- 11 297 sidfiler, 70,3 GB. Per fil: min 0,6 MB, median 4,0 MB, max 36,6 MB.
- Format JPG utom 1986 nr 4 (PNG, cirka 30 MB per sida).
- Avvikelserna står i avsnitt 3: tvåsiffriga sidnummer (26 nummer), PNG och spelbilaga (1986 nr 4), första numret i filnamnet (1987 nr 4–5).
- **1986 nr 4:** skanningarna är 33 sidor i huvuddelen plus 16 sidor spelbilaga. Webbplatsen visar numret som 49 sidor i en följd, där spelbilagan är sidorna 34–49. Om PDF:en har samma ordning är inte kontrollerat (numret är inget stickprov). `scanPagePattern` täcker bara huvuddelen.

`scanPagePattern` i inventory är en FTP-adress med `{page:03d}` eller `{page:02d}` för sidnumret, till exempel `ftp://dmzarkivet.se/Original%20Scans/199416-Original/DMZ_1994-16-{page:02d}.jpg`.

## 7. HTTPS kontra FTP per materialtyp

Webbplatsen är en liten webbapplikation (gunicorn bakom Caddy) med en fast sidomeny. Kontrollerat med HEAD för alla 185 nummer.

| Material | HTTPS | URL-mönster | FTP |
|---|---|---|---|
| OCR-PDF (läsformat) | **Ja, alla 185** | `/pdf/<sourceKey>.pdf` | `OCR PDF/`, `OCR PDF v2/` (ofullständiga var för sig) |
| Sidbilder i lägre upplösning | **Ja**, första och sista sidan bekräftad för alla 185 | `/magazines/<nyckel>/<skanningens filnamn>.jpg`, HighScore under `/highscore/<nyckel>/` | finns inte på FTP |
| Covers | Nej (404 på provade adresser) | – | `Covers/` |
| Large PDF | Nej (404) | – | `Large PDF/` |
| Original Scans | Nej (404) | – | `Original Scans/` |

Egenskaper hos HTTPS-servern:

- **Range fungerar** (`206 Partial Content`), så avbrutna nedladdningar kan återupptas. Servern skickar dock ingen `Accept-Ranges`-rubrik.
- **ETag och Last-Modified** skickas för varje fil. ETag har formen `"<mtime>-<storlek>-<hash>"`. Båda sparas i `ocrPdfVersion` och kan användas för att upptäcka utbytta filer.
- `Cache-Control: no-cache` på filerna.
- HTTP omdirigeras till HTTPS (308).
- Felaktiga filnamn under `/pdf/` och `/magazines/` ger 500, inte 404.
- **Stabilitet:** adresserna bygger på arkivets egen nyckel och filerna är oförändrade sedan 2017. Webbplatsen beskriver själv arkivet som "tämligen statiskt". Sidbildernas adresser bygger på skanningens filnamn, inklusive avvikelserna (tvåsiffriga sidnummer, första numret för 1987 nr 4–5), så de måste hämtas ur inventory och får inte byggas i appen.
- Webbplatsen har en sidvisare, `/pager?issue=<nyckel>&page=<n>`, vars sidväljare visar antalet sidor. Den används inte av verktyget.

**Sidbilderna på webben** finns för varje sida i varje nummer, i lägre upplösning än skanningarna (stickprov: 950 × 1339 px och 1233 × 1747 px, runt 1 MB; sida 1 över alla nummer: 0,2–2,3 MB, median 1,2 MB). De används som omslag (sida 1). De är också en möjlig källa för miniatyrer och för snabb förhandsvisning utan att hämta PDF:en. Det beslutas senare.

## 8. Stickprov

Tre PDF:er laddades ned, en i taget. A och C via HTTPS. B finns bara på FTP och hämtades därifrån.

| | A | B | C |
|---|---|---|---|
| Nummer | 1986 nr 1 | 1986 nr 1 | 1995 nr 14 |
| Källa | webben (= `OCR PDF v2`) | FTP `OCR PDF` | webben (= `OCR PDF`) |
| Storlek | 15,1 MB | 89,0 MB | 115,6 MB |
| Skapad | 2017-09-09, Acrobat Pro DC Paper Capture | 2017-04-27 | 2017-04-30 |
| Sidor | 28 | 28 | 84 |
| Sidstorlek | cirka 810 × 1145 pt (tabloid) | samma | cirka 592 × 840 pt (A4) |
| Bild per sida | en JPEG, cirka 1680 × 2385 px, **150 dpi** | lager av JPEG 2000 + JBIG2, cirka 4470 × 6350 px, **400 dpi** | lager av JPEG 2000 + JBIG2, cirka 2466 × 3493 px, **300 dpi** |
| Textlager | ja, alla sidor | ja, alla sidor | ja, alla sidor |
| Text totalt (UTF-8) | 190 kB | 182 kB | 388 kB |
| Text per sida (tecken) | min 298, median 6 858, max 9 048 | min 244, median 6 774, max 8 525 | min 370, median 3 931, max 13 729 |
| Skanningar i Original Scans | 28 (+ `test.jpg`) | 28 | 84 |
| Sidantal = antal skanningar | **ja** | **ja** | **ja** |

`pdftotext` på hela filen ger exakt en `\f` per sida i alla tre, så uppdelning per sida fungerar som planerat.

### Sidantal och ordning mot Original Scans

- Sidantalet stämmer exakt i båda numren (28 och 84). `test.jpg` i 1986 nr 1 är ingen sida.
- Ordningen: webbens sidbild 1 har skanningens filnamn för sida 001 och visar samma sida som PDF-sida 1 i båda numren. Skanningarna själva laddades inte ned, så ordningen för övriga sidor är inte kontrollerad bild för bild. Det görs i FAS 11.
- Sidantalet för övriga 183 nummer är `null` i inventory. Antalet skanningar per nummer finns i `data/raw_listing.json` och stämde med PDF:en i båda stickproven, men det är inte uppmätt för fler.

### Textkvalitet och å, ä, ö

å, ä och ö kommer ut korrekt som Unicode i alla tre filerna. Inga ersättningstecken (U+FFFD) förekommer. Andelen å/ä/ö bland bokstäverna är 4,2 % (A), 4,3 % (B) och 3,9 % (C), vilket är normalt för svensk text.

**A (v2), sida 5:**

```text
Höjden av smaklöshet!
Det var min första reaktion
på strategispelet Theatre
Europe från P. S. Software,
USA.
```

```text
du vunnit eller förlorat. I
ett visst skede avslutar datorn spelet och meddelar
antingen att både du och
motståndaren hävdar seger, alternativt att någon
sida anser sig vunnit.
```

**B (FTP), samma sida:**

```text
eget Tredje Världskrig av­
löper.
Som i de flesta strategi­
spel handlar det istället om
var du placerar dina styrkor
och förstärkningar.
```

**C (1995 nr 14), sida 43:**

```text
Upphovsmannen beskriver noggrannt
i tillhörande textfil hur varje nivå är
konstruerad och beter sig. Enbart flera
spelare, inga monster.
```

**C, sida 26 (problemet nedan):**

```text
N y a h ö g ta la re ä r e tt b ra s ä tt a tt få m e r
in le v e ls e n ä r d u s p e la r d a to rs p e l.
```

Iakttagelser:

- **A sätter ihop avstavade ord** ("datorn spelet", "undersöka"). B och C behåller avstavningen som mjukt bindestreck (U+00AD): 1 082 stycken i B, 937 i C. Det måste hanteras i normaliseringen i FAS 8.
- **Dekorlinjer och rubriker i specialtypsnitt blir skräptecken** i alla tre (`CSQ/l~a··====`). Omslaget och helsidesbilder ger nästan ingen användbar text (A sida 1 och 15).
- Enstaka teckenfel förekommer ("Cornmodores" för "Commodores").
- **C har utspärrad text på många sidor.** Textlagret placerar bokstäverna med mellanrum som `pdftotext` tolkar som ordgränser. 47 % av orden i C är högst två bokstäver långa (21 % i A, 29 % i B), och 33 av 84 sidor är tydligt drabbade. Ordet "Amiga" hittas helt 246 gånger och söndersplittrat ytterligare 68 gånger, alltså 78 % träffbarhet. Teckenpositionerna (`pdftotext -bbox`) visar att riktiga ordmellanrum är cirka 8 pt och de falska cirka 2 pt, så orden går troligen att sätta ihop i FAS 8. Om det gäller hela den äldre generationen (95 nummer) vet vi inte efter ett enda stickprov.

**Duger texten för fulltextsökning?** Ja för v2-generationen (A). Ja med förbehåll för den äldre generationen (C): sökningen fungerar för de flesta ord, men ett märkbart antal träffar missas tills ordsammanfogningen är löst i FAS 8.

### Jämförelse A mot B (samma nummer, två generationer)

| | A (v2, webben) | B (FTP `OCR PDF`) |
|---|---|---|
| Storlek | 15,1 MB | 89,0 MB (5,9 gånger större) |
| Upplösning | 150 dpi, cirka 1680 px bred | 400 dpi, cirka 4470 px bred |
| Telefonbredd (1080 px) | skarp, brödtext läsbar | skarp, ingen synlig skillnad |
| 2x zoom (2160 px) | läsbar, något mjukare kanter (bilden skalas upp 1,3 gånger) | skarpare, tydligare rastermönster |
| Textmängd | 181 793 tecken, 25 070 ord | 174 336 tecken, 25 627 ord |
| Avstavning | ihopsatt | mjuka bindestreck kvar |
| Gemensamma ordformer | 5 141 av 7 096 | 5 141 av 6 892 |
| Vanliga ord (och, att, det, för, är, på) | 2 562 träffar | 2 611 träffar |

Renderingarna (sida 5 i 1080 px och 2160 px för A, B och C, samt jämförelsebilden `AB_p5_2x_side_by_side.png`) ligger i `samples/render/` och versionshanteras inte.

**Slutsats:** v2 har ett användbart textlager och är läsbar i telefonbredd och i 2x zoom. Beslutsregeln är uppfylld, så webbens fil gäller som kanonisk. B är skarpare vid kraftig zoom men sex gånger större. För detaljläsning finns HQ-läget (avsnitt 10). OCR-texten är likvärdig; A är något renare tack vare ihopsatt avstavning.

### Uppslagsparning

- **A (1986 nr 1):** sida 1 är omslaget, sidorna 2–3, 4–5 … hör ihop, sida 28 är baksidan. Tryckt sidnummer är lika med PDF-sidindex (sida 5 har "—5—" i sidfoten).
- **C (1995 nr 14):** sida 1 är omslaget, 2–3 är en annons över ett helt uppslag, 42–43 är ett redaktionellt uppslag med tryckta sidnummer 42 och 43. Sida 84 är baksidan.
- Båda följer alltså **standardregeln** (sida 1 ensam, därefter 2–3, 4–5 …), och `spreadOffset` behöver inte sättas. Den lämnas `null` i inventory.
- Inga tomsidor eller försättsblad finns i stickproven.

### Duger sida 1 som omslag?

- **C:** ja. Sida 1 är framsidan, och webbens sidbild 1 (1233 × 1747 px, 1,2 MB) är samma bild.
- **A:** sida 1 är framsidan, men **skannad liggande**: tidningen var i tabloidformat och vikt, så bilden visar bak- och framsida bredvid varandra, vridna 90 grader. Det gäller både webbens sidbild 1 och FTP-skanningen. PDF:en har sidrotation 270 inlagd, men innehållet står ändå på sidan. Numret har flaggan `cover_page_rotated`.
- Om fler tidiga nummer har liggande omslag går inte att se utan att titta på bilderna. Webbens sidbild 1 hade stående format i båda stickproven, så felet syns inte på bildmåtten.

## 9. Uppskattad storlek på search.db

**Detta är en uppskattning utifrån två nummer, inte en mätning.**

| | Värde |
|---|---|
| Text per sida, tidigt nummer (A) | 6,8 kB |
| Text per sida, sent nummer (C) | 4,6 kB |
| Text per nummer | 190 kB (A, 28 sidor), 388 kB (C, 84 sidor) |
| Sidor i arkivet | 11 297 |
| Rå text totalt | cirka 52–77 MB, troligast runt 64 MB |
| SQLite med FTS5 i förhållande till rå text | 1,8 gånger (uppmätt på stickprovens text, `unicode61`) |
| **search.db totalt** | **cirka 95–140 MB, troligast runt 115 MB** |
| Komprimerad text (gzip) | cirka 43 % av rå text |

Osäkerheter: de tidiga numren är i tabloidformat med mycket text per sida, och två stickprov säger lite om spridningen. Den utspärrade texten i den äldre generationen blåser upp antalet ord och därmed indexet; om orden sätts ihop i FAS 8 blir indexet mindre. Uppskattningen ligger under planens antagande om "flera hundra MB".

## 10. HQ-rekommendation

**Original Scans per sida som HQ-källa, Large PDF som reserv** (ADR 0009, preliminärt).

- Skanningarna ligger en fil per sida för alla 185 nummer, numrerade utan luckor, och sidantalet stämde med PDF:en i båda stickproven.
- En sida är i median 4,0 MB (max 36,6 MB). En Large PDF är i median 311 MB (max 1,9 GB), vilket är orimligt att hämta för att titta närmare på en sida.
- Det som måste lösas i FAS 11: tvåsiffriga sidnummer (26 nummer), PNG-filer på cirka 30 MB per sida och spelbilagans egen sidräkning (1986 nr 4), filnamn efter första numret (1987 nr 4–5), samt minnesgräns för de största bilderna.
- Skanningarna finns bara på FTP.

## 11. Rekommendation för hur appen ska hämta filer

1. **Läs-PDF:** HTTPS från `ocrPdfUrl`. Ingen FTP-klient behövs för läsning.
2. **Omslag:** HTTPS från `coverUrl` via Coil. Ingen egen FTP-fetcher behövs i FAS 2.
3. **Återupptagning:** använd Range för avbrutna nedladdningar. Kontrollera slutstorleken mot `ocrPdfSize`.
4. **Utbytt fil:** jämför serverns ETag eller Content-Length med `ocrPdfVersion` och `ocrPdfSize` (planens C7).
5. **Bygg aldrig adresser i appen.** Sidbildernas filnamn följer skanningarnas avvikelser. Appen läser färdiga adresser ur inventory.
6. **FTP bara för HQ** (Original Scans, och Large PDF som reserv), först i FAS 11, med en anslutning åt gången.
7. **Storlekar:** hälften av numren är över 50 MB och det största är 563 MB. "Läs nu" behöver förloppsindikator och avbrott, och nedladdning bör kunna begränsas till wifi.
8. Hemmaspegeln (planens E9) behövs inte utifrån det FAS 0 visar.

## 12. Upptäckt material utanför scope

Arkivets rot innehåller mer än de fyra katalogerna i planen. Följande ingår inte i projektet. Uppgifterna kommer från en ytlig listning (två nivåer) under FAS 0; inga nya listningar har gjorts.

| Plats | Innehåll | Namnmönster |
|---|---|---|
| `Other Magazines/Ahoy!` | 61 PDF:er, 3,4 GB | `Ahoy_Issue_01_1984_Jan.pdf` |
| `Other Magazines/Amiga Forum` | 15 PDF:er, 0,5 GB | `amigaforum_1_1992.pdf` |
| `Other Magazines/AmigaInfo` | 26 PDF:er (1,6 GB) och 44 underkataloger. Finns också på webbplatsen (`/pager?issue=199601&ai=1`, sidbilder under `/amigainfo/`). | `AmigaInfo-1996_01.pdf`, kataloger `03-AmigaInfo-1996_01` och `199604` |
| `Other Magazines/Atari Magazin` | 1 PDF | `Atarimagazin 198904.pdf` |
| `Other Magazines/Compute!'s Gazette` | 88 PDF:er, 4,8 GB | `Compute_Gazette_Issue_01_1983_Jul.pdf` |
| `Other Magazines/DMZ Disketten` | 55 filer: diskettavbilder och etikettbilder | `DMZ-1994-11.adf`, `.jpg`, `.zip` |
| `Other Magazines/Datorhobby` | 5 underkataloger | `1983-01`, `1983-0405`, `1983-2` |
| `Other Magazines/High Score` | 1 underkatalog | `199512` |
| `Other Magazines/Norsk Datormagazin` | 24 PDF:er, 0,3 GB | `ndm_norskdatamagasin_01_1990.pdf` |
| `Other Magazines/Relik` | 1 underkatalog | `1997-2` |
| `Other Magazines/Reset Magazine` | 10 PDF:er, 0,15 GB | `Reset01_Nov_2013.pdf` |
| `Other Magazines/Run` | 98 PDF:er, 4,5 GB | `Run_Issue_01_1984_Jan.pdf` |
| `Other Magazines/Svenska Amiga Magazin` | 215 skanningsfiler (3,9 GB) och 1 underkatalog | `SAM 1996-05 02-35_page_0001.jpg`, `.tif` |
| `Other Magazines/The Underground` | 1 underkatalog | `1994-2` |
| `PDFs V3` | i praktiken tom (`198601/cache/`) | – |
| `Löp` | 3 filer, 372 MB: en löpsedel i tre format | `11.jpg`, `11.png`, `11.psd` |
| `amigadiskar` | diskettavbilder: `original/` (29 filer) och `renamed/` (25 filer) | `dmz_01-95.adf`, `DMZ-1994-12.adf` |
| `test` | tom | – |
| Lösa filer i roten | `199501-Original.pdf`, `199502-Original.pdf` (kopior av Large PDF att döma av namnet), `DMZ_1994-10-15.jpg`, `DSC_3314.NEF`, `programbibliotek-198907.jpg`, `programbibliotek-198910.jpg`, `tshirt1989.jpg`, `index.txt` | – |
| `Raw Images`, `Uploads` | ignoreras enligt planen, inte listade | – |

Webbplatsen har dessutom diskettavbilder under `/coverdisk/DMZ-ÅÅÅÅ-NN.zip` (26 länkar).

## 13. Vilka av planens förväntade specialfall (B5) förekommer?

| Förväntat | Förekommer? |
|---|---|
| Dubbelnummer och specialnummer | Ja: 3 dubbelnummer, 2 b-utgåvor |
| Avvikande filnamn | Ja, men få och regelbundna (avsnitt 3) |
| Flera publikationer | Ja: Datormagazin och HighScore |
| Saknade omslag, OCR-PDF:er, Large PDF:er | Omslag: 38 saknas på FTP, 0 på webben. OCR-PDF: 11 saknas i `OCR PDF`, 0 på webben. Large PDF: 0 |
| Olika sidantal mellan källor | Inte i stickproven |
| Extra tomsidor, försättsblad, baksidor | Inte i stickproven |
| Oregelbunden uppslagsparning | Inte i stickproven. 1986 nr 1 har liggande omslag. 1986 nr 4 har spelbilaga. |
| OCR-fel | Ja: utspärrad text i den äldre generationen, skräp från dekor |
| Filer som byts ut i arkivet | Ja: 1993 nr 3 skiljer mellan webb och FTP |

## 14. Belastning på arkivservern under FAS 0

- FTP: en anslutning åt gången, passivt läge, 0,6 s paus före varje kataloganrop. Cirka 215 kataloganrop totalt, plus en nedladdning (stickprov B, 89 MB).
- HTTPS: cirka 640 små förfrågningar (HEAD, några webbsidor och två sidbilder) med 0,6 s paus, plus två nedladdningar (stickprov A och C, 131 MB).
- 1995 nr 15 (563 MB) har inte laddats ned. Den sparas till belastningstestet i FAS 3.
