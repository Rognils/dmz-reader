# Fasrapport: FAS 2 – Bibliotek

Avslutad 2026-10-06. Verifierad på OnePlus 7 Pro (GM1911), Android 16 (API 36, LineageOS 23.2).

## 1. Sammanfattning

- **Bibliotek:** publikationsväljare (sparad i DataStore) och årgångskort i tre kolumner. Sektionerna Fortsätt läsa, Senast lästa, Favoriter och Nedladdat finns som komponenter men är dolda tills de får data i FAS 4–6.
- **Årgång:** stort årtal, antal nummer och ett omslagsrutnät i tre kolumner med stabila nycklar.
- **Nummer:** stort omslag, titel, sidantal, PDF-storlek, inaktiverad läsknapp och länk mellan bilaga och huvudnummer i båda riktningarna.
- **Navigation:** Bibliotek → Årgång → Nummer under fliken Bibliotek med bottenfält. Inställningar och Diagnostik utan bottenfält.
- **Omslag:** tas fram av toolchainen och följer med appen som miniatyrer (ADR 0016). `coverUrl` via nätet är reserv, med högst fyra samtidiga förfrågningar (ADR 0015).

## 2. Omslagen

| | Antal |
|---|---|
| Nummer med miniatyr | 185 av 185 |
| Bearbetat omslag från FTP `Covers` | 46 |
| Webbens sidbild 1, orörd | 128 |
| Webbens sidbild 1, rättad (`rotate_cw` + `crop_right_half`) | 11 |
| Miniatyrernas totala storlek | 16,7 MB |

- **Hämtat till cachen:** 185 webbsidbilder (243 MB) och 148 filer från `Covers` (1 253 MB).
- **Klassning:** av 147 omslag i `Covers` är 46 bearbetade och 101 obearbetade kopior av skanningen. Jämförelsen är en nedskalad pixeljämförelse; kopiorna skiljer sig högst 0,74 av 255 från sidbild 1 och de bearbetade minst 5,5.
- **Granskning av kontaktkartan** (`docs/screenshots/covers/contact_sheet.png`): 171 ok, 12 vridna uppslag, 2 annat.
  - Vridna uppslag: 1986 nr 1, 1987 nr 7, 8, 9, 10, 1988 nr 9, 10, 13, 15 och 1989 nr 2, 4, 5. Alla är vridna åt samma håll. 1986 nr 1 får sin miniatyr från `Covers`; de övriga elva är rättade i `tools/overrides.json`.
  - Annat: 1991 nr 2 och 1994 nr 19 är skadade skanningar som står rätt. 1987 nr 10 och 1989 nr 2 är rättade uppslag vars skanningar dessutom är trasiga.
- `Covers/DMZ_1986-01.png` hämtades men används inte; numret har redan ett bearbetat omslag som `.jpg`.

## 3. Testresultat

| Test | Hur | Resultat |
|---|---|---|
| JVM-tester | `./gradlew :app:testDebugUnitTest` | 81 av 81 |
| Instrumenterade tester på telefonen | `adb shell am instrument -w se.c64.dmzreader.test/androidx.test.runner.AndroidJUnitRunner` | 20 av 20 |
| Python-tester | `.venv/bin/python -m unittest discover -s tools/tests` | 125 av 125 |
| Lint | `./gradlew :app:lintDebug` | 0 fel, 3 varningar |

De instrumenterade testerna körs med `am instrument` i stället för `connectedDebugAndroidTest`, som avinstallerar appen efteråt. På den här telefonen nollställer en avinstallation appens nätverkstillstånd.

## 4. Acceptanskriterier

- [x] **Användaren kan bläddra mellan år och nummer.** Verifierat på telefonen och i `LibraryNavigationTest`.
- [x] **Rätt omslag visas för rätt nummer.** Miniatyrerna heter efter `issueId`, kontaktkartan är granskad, och skärmbilderna visar rätt omslag för 1989, 1986–1988 och HighScore 1993.
- [x] **Saknade omslag visar en platshållare.** Testat för nummer utan miniatyr och utan `coverUrl`, och sett på telefonen när nätet var blockerat.
- [x] **Rullningen flyter även i det största året.** Årgång 1993 (22 nummer): 2,4 % janky frames.
- [x] **Biblioteket fungerar utan nätverk.** I flygplansläge med tömd bildcache visas alla omslag, utan ett enda nätanrop.

## 5. Verifiering på telefonen

**Skärmbilder** i `docs/screenshots/fas2/` (förminskade till halv upplösning):

| Fil | Visar |
|---|---|
| `01-bibliotek-datormagazin.png`, `02-bibliotek-highscore.png` | biblioteket med båda publikationerna |
| `03-argang-1989.png` | årgång 1989; nr 2, 4 och 5 var tidigare uppslag |
| `04-nummer-1989-06.png` | nummervy: "≈ 48 sidor", "25,7 MB" |
| `05-argang-1986.png`, `06-nummer-1986-01.png` | 1986 nr 1 med rätt framsida, "28 sidor" |
| `07-argang-1987.png`, `08-argang-1988.png` | årgångar med rättade uppslag (1987 nr 7–10, 1988 nr 9, 10, 13, 15) |
| `09-argang-highscore-1993.png`, `10-highscore-1993-17-bilagelank.png` | HighScore och länken "Bilaga till Datormagazin 1993 Nr 17" |
| `11-diagnostik-utan-bottenfalt.png` | Diagnostik utan bottenfält, `datasetVersion` 2026-10-06-004 |
| `12-…`, `13-flygplanslage-argang-1993….png` | flygplansläge: alla omslag visas |

**Prestanda** i årgång 1993, debug-bygge, åtta svep upp och ned (`dumpsys gfxinfo`):

| Mått | Värde |
|---|---|
| Renderade bildrutor | 745 |
| Janky frames | 18 (2,42 %) |
| Frame time, 50:e percentilen | 9 ms |
| Frame time, 90:e percentilen | 13 ms |
| Frame time, 95:e / 99:e percentilen | 18 ms / 42 ms |
| Missade vsync | 0 |

**Samtidighet** på reservvägen: testet `CoverFallbackNetworkTest` startar tolv hämtningar samtidigt för nummer utan miniatyr, utan cache. Högsta antal aktiva förfrågningar var 4, både enligt spärrens egen räknare och i logcat (`DmzCovers`). Alla tolv lyckades, och bilderna avkodades nedskalade.

**logcat:** inga krascher och inga återkommande fel från appen. Vid vanlig användning gör appen inga nätanrop alls för omslag.

**Databasen** på telefonen migrerades från version 1 till 2 utan fel.

## 6. Kända begränsningar

- **Två omslag visar skadade skanningar** (1991 nr 2, 1994 nr 19), och två rättade uppslag är trasiga (1987 nr 10, 1989 nr 2). Det finns inga bättre original i arkivet.
- **APK:n är 36,7 MB**, varav miniatyrerna 16,7 MB.
- **Import av inventory (FAS 7) kan inte tillföra miniatyrer.** Nya nummer får då omslaget via `coverUrl`.
- **Miniatyrerna är byte-identiska bara med samma version av Pillow och libwebp** (Pillow 12.3.0, libwebp 1.6.0).
- **Klassningen jämför mot webbens sidbild 1**, som är en förminskning av skanningen, inte mot originalskanningen på FTP.
- **Prestandan är mätt i ett debug-bygge** och på en telefon med 90 Hz-skärm.
- **Statistik-fliken har fortfarande en platshållarikon.**
- **Planens B2, B3 och C2** beskriver fortfarande omslaget som enbart webbens sidbild 1. Ändringen är loggad i bilaga E.

## 7. ADR:er

- 0015 Bildladdning och cache för omslag: Accepted
- 0016 Omslagen tas fram av toolchainen och följer med som miniatyrer: Accepted
- 0008 och 0015 har fått uppdateringsnoter.

## 8. Återstår och förslag inför FAS 3

Ingenting återstår i FAS 2. Förslag:

1. **Rätta planens B2, B3 och C2** efter ADR 0016, så att huvudtexten stämmer med beslutet (`coverAsset`, källprioritet, transformordning).
2. **Miniatyrer i läsaren:** planen säger att webbens sidbilder ska utvärderas som källa i FAS 3. Samma `ImageLoader` och samtidighetsgräns kan användas. Tänk på att sidbilderna för de tidiga numren har samma problem som omslagen: sida 1 är ibland ett vridet uppslag.
3. **PDF-nedladdningen i FAS 3** bör gå genom samma hänsynsregler som omslagen men med egen klient, eftersom filerna är upp till 563 MB.
4. **Belastningstestet med 1995 nr 15** kräver att telefonen tillåter appen nätåtkomst. Inställningen finns kvar så länge appen inte avinstalleras.
