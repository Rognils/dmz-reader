# ADR 0008: HTTPS kontra FTP per materialtyp

## Status
Accepted (beslut av C64 2026-10-06)

## Context
Planen antog att `OCR PDF` på FTP var det primära läsformatet och lät FAS 0 avgöra vad som också finns via HTTPS. Kartläggningen visade:

- Webbplatsen serverar en läs-PDF för alla 185 nummer på `https://dmzarkivet.se/pdf/<nyckel>.pdf`.
- FTP har två generationer OCR-PDF:er. `OCR PDF` (174 filer, 300–400 dpi) saknar 11 nummer. `OCR PDF v2` (90 filer, 150 dpi) täcker bara 1986 nr 1 – 1991 nr 21. Ingen av katalogerna är komplett.
- Webbens fil är v2 för 90 nummer och `OCR PDF`-filen för 94. För 1993 nr 3 matchar den ingen FTP-fil.
- Webbplatsen har sidbilder i lägre upplösning för varje sida i varje nummer.
- FTP-katalogen `Covers` saknar omslag för 38 nummer, och filerna är stora (median 6,9 MB).
- Large PDF och Original Scans finns bara på FTP.
- HTTPS-servern stöder Range och skickar ETag och Last-Modified.

Stickproven visade att v2 (150 dpi) har ett användbart textlager och är läsbar i telefonbredd och i 2x zoom.

## Decision

| Material | Källa | Fält i inventory |
|---|---|---|
| Läs-PDF (kanonisk OCR-PDF) | HTTPS: `https://dmzarkivet.se/pdf/<sourceKey>.pdf` | `ocrPdfUrl`, `ocrPdfSize`, `ocrPdfVersion`, `ocrPdfChecksum` |
| Omslag | HTTPS: webbens sidbild 1 | `coverUrl` |
| Original Scans (HQ) | FTP | `scanPagePattern` |
| Large PDF (HQ-reserv) | FTP | `largePdfUrl`, `largePdfSize` |

- Webbens PDF är den fil appen läser, och search.db byggs från exakt samma filer.
- För 1993 nr 3 gäller webbversionen, trots att FTP-filen är nyare.
- `ocrPdfVersion` innehåller serverns `etag` och `lastModified`. `ocrPdfSize` är serverns Content-Length.
- `ocrPdfFtpSource` anger vilken FTP-generation webbfilen motsvarar (`OCR PDF`, `OCR PDF v2` eller `null`), avgjort på filstorlek. FTP-filerna står i `sourceNames` för spårbarhet och används inte av appen.
- FTP-katalogen `Covers` används inte av appen i v1. Filerna står i `sourceNames`.
- Omslag har två nivåer: webbens sidbild 1, annars platshållare. Det ersätter de tre nivåerna i planens B3.
- Appen bygger aldrig adresser själv. Den läser dem ur inventory.

## Reason
- Webbens PDF är den enda kompletta uppsättningen. Den är också den uppsättning arkivets ägare valt att visa.
- HTTPS fungerar med Coil, WorkManager och vanliga HTTP-klienter utan egen kod. Planens egna FTP-fetcher för omslag (FAS 2) behövs inte.
- Range gör att avbrutna nedladdningar kan återupptas, och ETag gör att utbytta filer kan upptäckas.
- v2-filerna är ungefär en femtedel så stora som den äldre generationen, vilket ger snabbare "Läs nu" och mindre lagring.
- Webbens sidbild 1 finns för alla nummer och är runt 1 MB, mot 38 saknade och median 6,9 MB i `Covers`.

## Consequences
- Appen behöver ingen FTP-klient förrän HQ byggs i FAS 11.
- Läskvaliteten är ojämn mellan årgångar: 150 dpi för 1986 – 1991 nr 21, 300–400 dpi därefter. Filstorleken följer samma gräns (median 24 MB mot 102 MB).
- Sökindexet byggs från två OCR-generationer med olika egenskaper. Den äldre har utspärrad text som måste hanteras i FAS 8.
- Appen blir beroende av webbplatsens adresser. Om de ändras måste ett nytt inventory genereras. Sidbildernas adresser följer skanningarnas filnamn, inklusive avvikelserna.
- Omslaget är en obearbetad skanning av sida 1. För 1986 nr 1 är den liggande (flagga `cover_page_rotated`). De tillsnyggade omslagen i `Covers` används inte.
- `ocrPdfFtpSource` bygger på storleksjämförelse, inte på kontrollsumma. Två olika filer med exakt samma storlek skulle inte upptäckas.
- Webbservern skickar `Cache-Control: no-cache`, så omslagens cachning får styras av appen.

## Uppdatering 2026-10-06 (FAS 0B)
Fälten i inventory har fått sin slutliga form (planens C2):

- `ocrPdfVersion` är ersatt av de två fälten `ocrPdfEtag` och `ocrPdfLastModified`.
- `ocrPdfChecksum` är borttaget. Utbytta filer upptäcks med `ocrPdfSize`, `ocrPdfEtag` och `ocrPdfLastModified`.
- `ocrPdfFtpSource` och jämförelsen mot FTP har flyttat till `data/inventory_diagnostics.json`, som bara toolchainen använder. Flaggorna `ocr_pdf_differs_from_ftp` och `no_ftp_cover` finns inte längre i inventory.
- `coverFlags` beskriver omslagsbilden. 1986 nr 1 har `["rotated"]`, satt i `tools/overrides.json`.
- `coverUrl` och `ocrPdfUrl` är obligatoriska. Ett nummer utan läs-PDF eller sidbild 1 på webbplatsen kan inte exporteras.

## Uppdatering 2026-10-06 (FAS 2)
Omslagsdelen är ersatt av ADR 0016: omslagen tas fram av toolchainen, delvis från FTP-katalogen `Covers`, och följer med appen som miniatyrer (`coverAsset`). `coverUrl` är kvar som reserv. Appen använder fortfarande aldrig FTP för omslag.
