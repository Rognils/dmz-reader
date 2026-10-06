# ADR 0016: Omslagen tas fram av toolchainen och följer med som miniatyrer

## Status
Accepted (beslut av C64 2026-10-06). Ersätter omslagsdelen av ADR 0008 och kompletterar ADR 0015.

## Context
Efter FAS 0 bestämdes att omslaget skulle vara webbplatsens sidbild 1, hämtad av appen via `coverUrl` (ADR 0008). När biblioteket byggdes i FAS 2 visade det sig att det inte håller:

- Sidbild 1 är för flera tidiga nummer ett skannat uppslag, bak- och framsida bredvid varandra och vridet 90 grader. Det gäller 12 nummer från 1986–1989, inte bara 1986 nr 1.
- Arkivägaren har plockat ut och snyggat till omslag i FTP-katalogen `Covers`. 46 av de 147 filerna där är faktiskt bearbetade. De övriga 101 är obearbetade kopior av skanningens sida 1.
- Appen ska aldrig använda FTP för omslag och ska inte behöva förstå arkivets egenheter.
- Biblioteket ska fungera utan nät.

## Decision
**Toolchainen väljer, rättar och förminskar omslagen. Appen får färdiga bilder.**

### Hämtning
`tools/fetch_covers.py` hämtar till `cache/covers/` (versionshanteras inte): webbens sidbild 1 för alla 185 nummer via HTTPS, och alla `.jpg` och `.png` i FTP `Covers` (148 filer). En anslutning, 0,6 s paus, återupptagbart.

### Källa per nummer
1. **Bearbetat omslag från `Covers`.** En fil räknas som bearbetad om den skiljer sig från sidbild 1 i en nedskalad pixeljämförelse (32 × 32 px i gråskala, medelskillnad över 3 av 255) eller i bildformat (över 3 %). I arkivet ger obearbetade kopior en skillnad på högst 0,74 och bearbetade minst 5,5, så gränsen är inte känslig.
2. **Webbens sidbild 1**, rättad enligt `coverFlags`.
3. **Platshållare** i appen.

Källan kan tvingas per nummer med `coverSource` i `tools/overrides.json`.

### Transformordning
Definieras en gång och gäller överallt, i toolchainen och i appen:

1. **vridning** (`rotate_cw`, `rotate_ccw`, `rotate_180`)
2. **beskärning** (`crop_left_half`, `crop_right_half`), av den vridna bilden
3. **skalning**

Högst en vridning och en beskärning. `coverFlags` beskriver webbens sidbild 1 och tillämpas bara på den, aldrig på en fil från `Covers`. Rättningarna står i `tools/overrides.json`, aldrig i kod.

### Miniatyrer
`tools/build_covers.py` skriver en miniatyr per nummer till `data/covers/<issueId>.webp`: högst 600 px bred, WebP med kvalitet 80. Utdatan är deterministisk för en given version av Pillow. `data/covers/` versionshanteras.

- Inventory får det valfria fältet `coverAsset`, till exempel `covers/datormagazin-1989-06.webp`.
- `data/inventory_diagnostics.json` får `coverSource` (`covers_ftp` eller `web_page1`) och `ftpCoverProcessed`.
- `data/covers_manifest.json` har källa, mått, storlek och kontrollsumma per miniatyr.

### Granskning
`build_covers.py` gör en kontaktkarta över de valda omslagen (`docs/screenshots/covers/contact_sheet.png`). Den granskas visuellt, och resultatet står i `data/cover_review.json`.

### Appen
- Byggsteget `syncCovers` kopierar `data/covers/` till `assets/covers/`.
- `IssueEntity` har `coverAsset` (databasversion 2, med automatisk migrering som inte rör användardata).
- Källorna prövas i ordning (`coverSources`): `coverAsset` ur assets, sedan `coverUrl` via nätet, sist platshållare.
- **Inga `coverFlags` tillämpas på inbyggda miniatyrer.** Stödet för `coverFlags` finns kvar för reservvägen med `coverUrl`.

### Utfall 2026-10-06

| | Antal |
|---|---|
| Nummer med miniatyr | 185 av 185 |
| Bearbetat omslag från `Covers` | 46 |
| Webbens sidbild 1, orörd | 128 |
| Webbens sidbild 1, rättad med `rotate_cw` och `crop_right_half` | 11 |
| Granskning: ok / vridet uppslag / annat | 171 / 12 / 2 |
| Miniatyrernas totala storlek | 16,7 MB |

De 12 vridna uppslagen är 1986 nr 1 (miniatyr från `Covers`), 1987 nr 7, 8, 9, 10, 1988 nr 9, 10, 13, 15 och 1989 nr 2, 4, 5. "Annat" är två skadade skanningar som står rätt: 1991 nr 2 och 1994 nr 19.

## Reason
- Rätt omslag kräver kunskap om arkivet (vilka filer som är bearbetade, vilka skanningar som är uppslag). Sådan kunskap hör hemma i toolchainen (planens A3).
- Färdiga miniatyrer gör att biblioteket visar alla omslag direkt, utan nät, och utan att arkivets server belastas alls vid vanlig användning.
- 16,7 MB i APK:n är ett rimligt pris för det. Originalen är 1,5 GB.
- En enda definierad transformordning gör att samma `coverFlags` ger samma bild i toolchainen och i appens reservväg. Båda har tester mot samma syntetiska bild.
- En pixeljämförelse skiljer bearbetade omslag från kopior, vilket filstorleken inte gör säkert.

## Consequences
- APK:n växer med 16,7 MB.
- Ett nytt eller ändrat omslag kräver att toolchainen körs och att en ny APK byggs. Import av inventory (FAS 7) kan inte tillföra miniatyrer; nya nummer får då `coverUrl` som reserv.
- Jämförelsen görs mot webbens sidbild 1, som är en förminskning av skanningen, inte mot originalskanningen på FTP.
- `coverFlags` finns kvar i inventory fast miniatyrerna redan är rättade. De behövs för reservvägen.
- Miniatyrerna är byte-identiska bara med samma version av Pillow och libwebp (nu Pillow 12.3.0, libwebp 1.6.0). En annan version kan ge andra byte för samma bild.
- Två miniatyrer visar skadade skanningar. Det går inte att rätta utan bättre original.
- Reservvägen med `coverUrl` följer fortfarande ADR 0015: högst fyra samtidiga förfrågningar och diskcache.
- Planens B2, B3 och C2 beskriver fortfarande omslaget som enbart webbens sidbild 1. Ändringen är loggad i bilaga E.
