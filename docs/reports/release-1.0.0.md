# DMZ Reader 1.0.0: publiceringen

Datum: 2026-10-31 · Releasen: <https://github.com/Rognils/dmz-reader/releases/tag/v1.0.0>

## Sammanfattning

DMZ Reader 1.0.0 är publicerad. Repot `Rognils/dmz-reader` är publikt, och `v1.0.0` är en vanlig release och latest, med APK:n, sökindexet p3 och manifestet. Varje steg i uppgiften är genomfört utan stopp. Tre saker gick annorlunda än checklistan beskriver, och de står under [Avvikelser och iakttagelser](#avvikelser-och-iakttagelser). Ingen av dem är ett fel i appen.

## Kedjan

| | |
|---|---|
| Tagg | `v1.0.0`, annoterad, tagg-objekt `54f381053ef4b66614110f8cde36e91db2572d6e` |
| Commit | `7cda1f6fcc6c45e5c7374d3b8fa389a7600f8703` (Merge release/1.0.0 – DMZ Reader 1.0.0) |
| APK | `dmz-reader-1.0.0-release.apk`, 24 427 276 byte, versionCode 10000, versionName 1.0.0 |
| APK:ns SHA-256 | `1b6edc1553d99d09928ade53d48b55f075deb2a5551809221cffe115b6520ceb` |
| Certifikatets SHA-256 | `5a3679ecc4ccbf9b61247e3580b178028f03e6e47e6d6d7f325d2a295475f727` (CN=DMZ Reader, O=C64, C=SE), APK Signature Scheme v2 |
| Sökindexet | `search-2026-10-06-006-p3.db`, 90 943 488 byte, SHA-256 `11033d29553b59083dea94fde90c55bcf7a0cb23ef5452a4083f923903682dfc` |
| Manifestet | `search-index.json`, 363 byte, SHA-256 `7e9e1301a7da0b5a4abef39981317cb5b822c1f5754753a7eca2d56feadaff75` |
| Frysningsunderlag | `v1.0.0-rc5` på `0fc1a95f21a5dbaddedae2b90271c101cb005e3c` |

APK:n byggdes med `clean :app:assembleRelease` från `7cda1f6` med ett rent arbetsträd. Den innehåller ingen databas och inget sökindex. Commiten efter taggen, `d9a2ce1`, fyller bara i release-texten.

### Manifestet

```json
{
  "manifestVersion": 1,
  "releaseTag": "v1.0.0",
  "fileName": "search-2026-10-06-006-p3.db",
  "size": 90943488,
  "sha256": "11033d29553b59083dea94fde90c55bcf7a0cb23ef5452a4083f923903682dfc",
  "searchSchemaVersion": "1",
  "inventoryDatasetVersion": "2026-10-06-006",
  "normalizerVersion": "2",
  "privacyFilterVersion": "3",
  "minAppVersionCode": 9904
}
```

`size` och `sha256` stämmer med filen. De fyra versionerna stämmer med indexets egen `meta`-tabell. `minAppVersionCode` 9904 är rc4, under 10000.

## Utfall per steg

| Steg | Utfall |
|---|---|
| 1. Beteendet a → am | dokumenterat som känd begränsning i [rc5.md](rc5.md) |
| 2. RC5 | `v1.0.0-rc5` taggad på `0fc1a95`. `main`, `release/1.0.0` och taggen pushade. `release/1.0.0` var rebasad på rc5 och pushades med `--force-with-lease` mot det kända gamla läget. `rc5.md` committad och pushad. |
| 3. Frysningskontroll | `git diff v1.0.0-rc5..release/1.0.0 -- app/src/main` är tom. Hela diffen är versionCode 9905 → 10000 och versionName 1.0.0-rc5 → 1.0.0 i `app/build.gradle.kts`. |
| 4. Kontroller | 544 JVM-tester, 260 Python-tester, lint 0 fel (21 varningar), `prepush_check.sh` ren (198 commits), gitleaks 8.30.1 0 läckor (170 commits), `device_test.sh` på telefonen 83 av 83 med 0 förfrågningar till arkivet och GitHub. Se avvikelse 1. |
| 5. Bygge och manifest | se kedjan ovan. Certifikatet stämmer. |
| 6. Din telefon | export till `~/dmz-backup-före-1.0.0/`, `adb install -r` (17:45:33), exporten lika utom `appVersion` och `exportedAt`, inställningarna lika utom "Senaste export", p3 orört (185 nummer, 11 293 sidor, importerat 15:23). Om-sidan visar Version 1.0.0 och fyra länkar. Ett nummer öppnas (se avvikelse 2). amiga ger 7 289 sidor i 185 nummer. |
| 7. Tagg och release-text | `v1.0.0` på `7cda1f6`, pushad. Release-texten har APK:ns och certifikatets SHA-256 och commiten, och utkastkommentaren är borta. |
| 8. Publikt | `prepush_check.sh` och gitleaks rena på slutliga `main` (`d9a2ce1`), arbetsträdet rent, alla taggar nås från `main`. `release/1.0.0` och `release/1.0-rc4` borttagna från origin efter kontroll att båda finns i `main`, och `git ls-remote --heads origin` visar bara `main`. `dmz-reader-private-history` är privat. Repot är publikt, med Issues på och Wiki och Projects av. Se avvikelse 3. |
| 9. Prerelease | `v1.0.0` skapad som prerelease med de tre filerna. Alla tre hämtade utan inloggning med rätt SHA-256, och `search-index.json` är byte för byte lika med det lokalt genererade. |
| 10. Debug-appen mot GitHub | godkänt, se nedan |
| 11. Vanlig release | `v1.0.0` är vanlig release och latest. Prereleasen `search-index-2026-10-06-006-p3` och dess tagg är borttagna på GitHub och lokalt. Latest-adressen till manifestet ger utloggad exakt v1.0.0-manifestet. |
| 12. Slutprov i din app | godkänt, se nedan |
| 13. Utan inloggning | godkänt, se nedan |

### Steg 10: debug-appen mot den riktiga prereleasen

Android 16, debug-appen utan sökindex, pekad på `https://github.com/Rognils/dmz-reader/releases/download/v1.0.0/search-index.json`.

- **Kortet** visar "Fulltextsökning kräver ett sökindex på cirka 91 MB", med storleken ur manifestet.
- **Hela vägen:** manifestet (302 → `release-assets.githubusercontent.com`, 200), filen (302 → 200), kontroll och import på tio sekunder. 185 nummer, 11 293 sidor.
- **Avbrott och återupptagning:** indexet togs bort och hämtningen startades om. Flygplansläget slogs på efter en sekund. Kortet sa "Hämtningen av sökindexet avbröts. Den fortsätter där den slutade." vid 12 av 91 MB (`.part` 11 956 461 byte). När flygplansläget slogs av fortsatte hämtningen med `206 Partial Content`, och importen gav "återupptaget=true". Se avvikelse 4.
- **Sökningen** på amiga ger 7 289 sidor i 185 nummer. Inställningar visar 185 nummer, 11 293 sidor och 91 MB.
- **Nätbeskedet (14b):**
  - 4G med regeln på: "Väntar på wifi …", och ingenting hämtades.
  - Flygplansläge: "Ingen internetanslutning. Hämtningen startar när telefonen är ansluten."
  - Regeln av under Inställningar: samma besked, och wifi-regeln nämns inte.
  - Wifi på: hämtningen startade av sig själv efter fyra sekunder och importerades.

  Ingenting trycktes i kortet. Wifi slogs på medan flygplansläget fortfarande var på, och flygplansläget slogs av först därefter. Annars hade 91 MB gått över ditt mobilnät, eftersom regeln var av.
- **Efteråt:** regeln är på igen, och manifestadressen är tillbaka på latest.

### Steg 12: din release-app som en ny användare

1. **Ta bort sökindex:** "Sökindexet är borttaget."
2. **Sök → Ladda ned sökindex:** kortet visade "cirka 91 MB". Se avvikelse 5.
3. **Hämtning via latest:** hämtning, kontroll och import, importerat 18:03. 185 nummer, 11 293 sidor, 91 MB.
4. **Sökningen:** amiga ger 7 289 sidor i 185 nummer.

### Steg 13: utan inloggning

Alla adresser hämtades med `curl` utan någon inloggning.

| Adress | Svar |
|---|---|
| `https://github.com/Rognils/dmz-reader` (repo-sidan, och adressen i User-Agent) | 200. README med de fyra bilderna och "Ladda ned senaste versionen". |
| De fyra bilderna, `docs/images/` | 200, byte för byte lika med repot |
| `/releases/latest`, också länken "Ladda ned senaste versionen" | leder till `/releases/tag/v1.0.0`, med APK:n |
| `/releases/latest/download/search-index.json` | v1.0.0-manifestet, byte för byte |
| `/issues`, också Om-textens "kontakta projektet via GitHub" | 200 |
| `https://dmzarkivet.se` (Om-texten) | 200, en HEAD-förfrågan |
| `https://shop.datormagazin.se` (Om-texten) | 200 |

## Avvikelser och iakttagelser

1. **Den första körningen av `device_test.sh` gav "fingeravtrycken skiljer sig".** Din release-app låg öppen i förgrunden. Varje gång ett test stängde sin aktivitet kom din app fram en kort stund, och systemet registrerade det som att den visades. Ingen data rördes, och versionen och signaturen var oförändrade. Jag tryckte på hemknappen och körde om hela sviten: 83 av 83 och identiska fingeravtryck.
2. **Röktestet förutsatte ett nedladdat nummer, men din app hade inget.** På ditt beslut öppnades Datormagazin 1989 Nr 6 ur läs-cachen i flygplansläge, alltså inte ett nedladdat nummer. Det öppnades på sida 4. Att öppna det uppdaterade numrets senaste öppningstid i din läsdata.
3. **`gh` 2.45.0 saknar flaggan `--accept-visibility-change-consequences`.** Kommandot avbröts utan att något ändrades. Samma ändring gjordes med `gh api -X PATCH repos/Rognils/dmz-reader` (visibility public, Issues på, Wiki och Projects av).
4. **Hämtningen fortsatte av sig själv efter avbrottet.** Checklistan säger "tryck Försök igen". Android stoppade jobbet när nätet försvann (skäl 7, nätvillkoret) och startade det igen när wifi kom tillbaka, innan knappen hann tryckas. Det avgörande kravet, att hämtningen fortsätter där den slutade, är uppfyllt (206).
5. **Din app tog inte om frågan efter manifestet av sig själv.** Kortet visade först "Anslut till internet för att hämta det …", fast wifi var anslutet. Appens process hade startat 17:45, före publiceringen. Den sparade då ett misslyckat svar från tiden då repot var privat eller flygplansläget var på. Efter en omstart av appen visade kortet "cirka 91 MB". En ny användare startar appen efter publiceringen och ser inte det här. Den som har appen öppen när nätet kommer tillbaka får trycka **Försök igen**. Förslag för 1.1: fråga efter manifestet igen när nätet kommer tillbaka.

Dessutom: `gitleaks` fanns inte längre på datorn. Med ditt godkännande hämtades 8.30.1 från den officiella releasen till scratchpad. SHA-256 `551f6fc8…70eb` stämde med releasens kontrollsummor.

## Trafik

- **Till arkivservern:** en HEAD-förfrågan till förstasidan av dmzarkivet.se (steg 13). Inga tester och ingen hämtning av nummer.
- **Från GitHub till telefonen:** sökindexet hämtades fyra gånger, alltid över wifi: tre gånger i debug-appen (en hämtning bröts vid 12 MB) och en gång i din app.
- **Till datorn:** de tre filerna en gång utan inloggning.

## Kvar

- Skriv till Gustav, med länken.
- Lokalt finns fortfarande grenarna `release/1.0.0`, `release/1.0-rc4` och `fix/pre-1.0-review`. De finns i `main`.
- RC5:s punkter 1–8 har fått sitt utfall i [rc5.md](rc5.md): vad som verifierades, var och när, och vad som inte gjorde det.
