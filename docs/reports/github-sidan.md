# GitHub-sidan: README, skärmdumpar och repo-beskrivning

2026-10-11. Ingen appkod är ändrad. Repot är privat. Ingenting hämtades från arkivservern: läsaren öppnade ett nummer som redan låg i appens läs-cache.

## 1. Skärmdumpar

Tagna i release-appen (1.0.0-rc3) med `adb exec-out screencap`, skalade till 540 px bredd.

| Fil | Visar | Format | Storlek |
|---|---|---|---|
| `docs/images/bibliotek.png` | Bibliotek, Datormagazin, med "Fortsätt läsa" | PNG, pngquant | 54 807 byte |
| `docs/images/lasare.webp` | Läsaren på omslaget till 1991 nr 15, med verktygsfält | WebP, kvalitet 88 | 120 328 byte |
| `docs/images/sok.png` | Sökning på "joystick" | PNG, pngquant | 67 588 byte |
| `docs/images/statistik.png` | Statistik | PNG, pngquant | 27 741 byte |

Alla är 540 × 1170 pixlar och under 250 kB. Läsarbilden blev 265 kB som PNG och är därför WebP.

**Statusfältet.** Demoläget gav klocka 12:00, fullt batteri och wifi, men telefonens statusfält (OnePlus) visade ändå fyra notisikoner och en mätare för näthastighet. De är övermålade i efterhand med fältets bakgrundsfärg. Läsarbilden har inget statusfält. Demoläget är avstängt och `sysui_demo_allowed` är återställt till 0.

**Granskning.** Inga notiser, inga personliga uppgifter. Sökutdragen är korta: två rader ur joysticktester och en annonsrad med priser, utan `•••`. Tre saker för användaren att ta ställning till:

- Läsarbildens miniatyrrad visar sidorna 2–6 i frimärksstorlek. Texten går inte att läsa, men det är artikelsidor.
- Omslaget till 1991 nr 15 har en liten bild av en kvinna i bikini. Ett annat nummer ger ett annat omslag.
- Statistiken är nästan tom (12 sidor, 3 minuter), eftersom den visar verklig läsdata.

**Vad som ändrades i appen.** Läsaren öppnades på sida 19 i 1991 nr 15, flyttades till omslaget och tillbaka till sida 19. Läspositionen är densamma som före. Numret gick från 8 till 10 lästa sidor och lästiden från 2 till 3 minuter. Sökfältet är tömt. Inga inställningar är ändrade.

**Spärr.** `tools/prepush_check.sh` stoppar bilder över 500 kB under `docs/images/`. Prövat med en fil på 600 kB på en tillfällig gren, som stoppades.

## 2. README

Ny inledning med en engelsk rad, bilderna i ett rutnät 2×2, nedladdningslänk och "Vad appen gör". Därefter Tack Gustav, Datormagazin idag, Installera, Sökindex, Licens, För utvecklare och Bygga själv.

Funktionslistan är kontrollerad mot appen: 171 + 14 = 185 nummer i arkivindexet, inställningen "Ladda ned endast via wifi", "Markera som läst" och "Markera som oläst", säkerhetskopia med återställning, HQ, favoriter, bokmärken och statistik.

Länken "Ladda ned senaste versionen" leder ingenstans förrän det finns en release som inte är en prerelease.

**Kontroll av visningen.** Via `gh api repos/Rognils/dmz-reader/readme` med HTML-svar: tabellen har 2 rader med 2 celler var, fyra bilder med bredden 270, och rubrikerna kommer i rätt ordning. De fyra bildfilerna finns i repot med rätt storlek. Jag har inte sett sidan i en webbläsare.

## 3. Repo-beskrivning

```
description: Free Android reader for the Swedish computer magazines Datormagazin and HighScore from DMZArkivet
homepage:    https://dmzarkivet.se
topics:      amiga, android, commodore-64, datormagazin, highscore, jetpack-compose, kotlin, pdf-reader, retro-computing, swedish
visibility:  PRIVATE
```

## 4. Release-text

Utkastet ligger i [docs/release-notes-1.0.0.md](../release-notes-1.0.0.md). Ingen release är skapad. APK:ns och certifikatets SHA-256 är märkta "FYLLS I PÅ PUBLICERINGSDAGEN". Sökindexets storlek (90 943 488 byte) och SHA-256 är kontrollerade mot filen i `dist/`.

## 5. Tester och push

Verktygens tester: 250, varav 5 överhoppade som förut. `prepush_check.sh`: historiken är ren. `main` och `release/1.0.0` är pushade.
