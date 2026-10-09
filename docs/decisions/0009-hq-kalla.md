# ADR 0009: HQ-källa

## Status
Accepted (FAS 11, 2026-10-09). Preliminärt efter FAS 0. Det fastställda beslutet står under "Fastställt i FAS 11" sist i dokumentet och gäller före texten ovanför det.

## Context
HQ-läget ska ge detaljläsning i högre kvalitet än läs-PDF:en (planens B4). Två källor finns, båda bara på FTP:

| | Original Scans | Large PDF |
|---|---|---|
| Täckning | 185 av 185 nummer | 185 av 185 nummer |
| Enhet | en fil per sida | en fil per nummer |
| Storlek | median 4,0 MB per sida, max 36,6 MB | median 311 MB per nummer, max 1 940 MB |
| Totalt | 11 297 filer, 70,3 GB | 68,7 GB |

Sidantalet i läs-PDF:en stämde med antalet skanningar i båda stickproven (28 och 84 sidor). Skanningarna är numrerade från 001 utan luckor i alla 185 kataloger.

Behovet av HQ är störst för 1986 – 1991 nr 21, där läs-PDF:en bara är 150 dpi.

## Decision
- **Original Scans per sida är HQ-källa.** `hqSource` är `"scans"` för alla nummer.
- **Large PDF är reserv**, för nummer där sidmappningen mot skanningarna inte går att lita på.
- PDF-sidindex n motsvarar skanningsfilen med sidnummer n i numrets huvuddel. Adressen bildas ur `scanPagePattern` i inventory.

Kända avvikelser som FAS 11 måste hantera:

| Avvikelse | Omfattning | Hantering |
|---|---|---|
| Tvåsiffriga sidnummer i filnamnet | 26 nummer (flagga `scan_two_digit_pages`) | Löst i `scanPagePattern`, som har `{page:02d}` för dessa |
| PNG i stället för JPG, cirka 30 MB per sida | 1986 nr 4 (`scan_png`) | Löst i mönstret. Kräver minnesgräns vid avkodning. |
| Spelbilaga med egen sidräkning (16 sidor) | 1986 nr 4 (`scan_parts`) | Olöst. Mönstret täcker bara huvuddelens 33 sidor. Webbplatsen visar bilagan som sidorna 34–49. Large PDF är reserv tills mappningen är verifierad. |
| Filnamn efter dubbelnumrets första nummer | 1987 nr 4–5 (`scan_named_after_first_number`) | Löst i mönstret (`DMZ_1987-04-{page:03d}.jpg`) |
| `test.jpg` i katalogen | 1986 nr 1 | Ignoreras, matchar inte mönstret |

## Reason
- En sida i HQ kostar några MB i stället för flera hundra, så HQ går att använda på mobilnät och med liten cache.
- Det stämmer med planens förstahandsval (B4) när skanningarna ligger en sida per fil.
- Large PDF har samma bildkvalitet som skanningarna enligt arkivets beskrivning, så den ger inget utöver dem.

## Consequences
- HQ kräver en FTP-klient i appen, med högst en anslutning åt gången.
- Sidmappningen är bara verifierad på sidantal för två nummer, och på bild för sida 1. För övriga 183 nummer är den ett antagande tills läs-PDF:ens sidantal är uppmätt (FAS 8) och kan jämföras med antalet skanningar.
- Skanningarnas upplösning i pixlar är inte uppmätt, eftersom inga skanningar laddades ned i FAS 0. De största filerna (upp till 36,6 MB) kräver nedskalad avkodning.
- Om läs-PDF:en för ett nummer har ett annat sidantal än skanningarna ska HQ stängas av för det numret, hellre än att visa fel sida.
- Webbens sidbilder är inte en HQ-källa (lägre upplösning än läs-PDF:en), men kan bli källa för miniatyrer. Det beslutas senare.

## Uppdatering 2026-10-06 (FAS 0B)
- Flaggan `scan_parts` heter nu `scan_supplement`.
- `scanPageCount` finns för alla 185 nummer och räknar bara huvuddelen. För 1986 nr 4 är det 33; spelbilagans 16 sidor räknas inte.
- Planen säger nu att mappningen mellan PDF-sida och skanningsfil **inte får konstrueras matematiskt för nummer med avvikelser**. Regeln "PDF-sidindex n motsvarar skanning n" ovan gäller därför bara som utgångspunkt för nummer utan avvikelseflaggor. Den exakta mappningen beslutas i FAS 11.

## Fastställt i FAS 11 (2026-10-09)

### Decision
- **HQ är originalskanningen för aktuell sida, hämtad via FTP.** Large PDF används inte, inte heller som reserv. `largePdfUrl` finns kvar i inventory men läses inte av appen.
- **Huvudregeln är att PDF-sida N är skanning N**, med adressen ur `scanPagePattern`. Den gäller bara när PDF:en har lika många sidor som det finns skanningar. Annars har numret ingen HQ.
- **Nummer där regeln inte håller har en uttrycklig tabell, `hqPageMap`**, i inventory: skanningens filnamn för varje PDF-sida, eller null för en sida utan säker motsvarighet. Appen räknar aldrig fram en mappning för sådana nummer.
- **Sidor där skanningen ligger vriden i förhållande till PDF-sidan har `hqPageRotation`** (90, 180 eller 270 grader medurs).
- **Mappningen tas fram med bildjämförelse i toolchainen** (`tools/map_hq_pages.py`), inte med regler om filnamn. PDF-sidan renderas nedskalad och jämförs med skanningen nedskalad (normaliserad korskorrelation mellan gråskalebilder, bästa värdet över beskärningar av båda bilderna, eftersom skanningen kan ha annan beskärning och marginal). En sida räknas som säkert mappad när poängen är minst 0,60 och närmaste andra kandidat ligger minst 0,10 lägre.

### Resultat av bildjämförelsen
Underlaget finns i `data/hq_mapping_report.json`.

| Kontroll | Resultat |
|---|---|
| 1986 nr 4, alla sidor mot alla 49 skanningar | 49 av 49 sidor mappade, poäng 0,984–0,998. Sidorna 1–33 är huvuddelen och 34–49 spelbilagan (`DMZ_1986-04-Spelbilaga-001.png` till `-016.png`). |
| 1993 nr 3, alla sidor mot alla 72 skanningar | 66 av 68 sidor mappade, poäng 0,776–0,985. PDF-sida N är skanning N+2: PDF:en saknar skanning 01, 02, 71 och 72 (omslaget). Sida 27 är skanning 29 vänd 180 grader. Sidorna 39 och 40 har ingen motsvarighet: skanning 41 och 42 visar samma uppslag men med annat innehåll i nederkanten (bästa poäng 0,378 och 0,404). |
| Stickprov, 13 nummer, första, mellersta och sista sidan | 39 av 39 sidor stämmer med sida N = skanning N, poäng 0,975–0,999. Bästa andra skanning i samma nummer fick som mest 0,513. |
| Alla 107 liggande PDF-sidor (uppslag skannade som en bild) | 107 av 107 hittade sin skanning, poäng 0,923–0,999. 10 skanningar ligger som PDF-sidan, 81 ska vridas 90 grader och 16 ska vridas 270 grader. |

Stickprovet: 1986 nr 1, 1987 nr 4–5 (filer namngivna efter första numret), 1988 nr 16–17, 1989 nr 6, 1990 nr 10, 1991 nr 2, 1991 nr 22, 1992 nr 13 (tvåsiffriga sidfilnamn), 1994 nr 9, 1995 nr 14, 1995 nr 15, HighScore 1993 nr 17 och HighScore 1994 nr 8.

Antalet skanningsfiler är lika med PDF:ens sidantal i 184 av 185 nummer (alla utom 1993 nr 3), när bilagan i 1986 nr 4 räknas med.

### Reason
- En HQ-sida kostar 2–37 MB i stället för flera hundra, och hämtas på under en sekund över wifi. Large PDF hade krävt en nedladdning på i median 311 MB för en enda sida.
- Bildjämförelsen fann tre saker som inga regler om filnamn hade gett: förskjutningen i 1993 nr 3, de två sidorna där PDF:en är gjord av en annan skanning, och att vridningen av liggande sidor inte är densamma överallt.
- Null för en osäker sida följer planens regel att okända fall aldrig gissas: hellre ingen HQ än fel sida.

### Consequences
- 183 av 185 nummer bygger på att sida N är skanning N utan att varje sida är jämförd. Stödet är stickprovet (39 av 39) och att filantalet stämmer med sidantalet. En förskjutning inne i ett nummer med rätt antal filer skulle inte upptäckas.
- PDF-sidorna 39 och 40 i 1993 nr 3 har ingen HQ. Skanningarna 01, 02, 41, 42, 71 och 72 i det numret nås inte från appen.
- `hqPageRotation` är ett fält utöver det som beställdes för fasen. Utan det hade 97 sidor visats liggande på sidan eller upp och ned i HQ.
- Ett nytt inventory behövs om arkivet byter eller lägger till skanningar. Mappningen görs om med `tools/map_hq_pages.py`.
- De tidigare avsnitten "Decision" och "Kända avvikelser" ovan gäller inte längre där de säger att Large PDF är reserv och att spelbilagan är olöst.

