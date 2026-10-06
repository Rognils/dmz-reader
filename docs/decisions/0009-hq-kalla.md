# ADR 0009: HQ-källa

## Status
Proposed (preliminärt efter FAS 0, fastställs i FAS 11)

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
