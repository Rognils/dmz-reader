# DMZ Reader 1.0.0

DMZ Reader är en gratis Android-app för att läsa Datormagazin och HighScore från [DMZArkivet](https://dmzarkivet.se): 185 nummer, årgång för årgång, direkt eller nedladdade för läsning utan nät.

Det här är den första publika versionen.

## Installera

Appen finns inte i Google Play. Den kräver Android 8.0 eller senare.

1. Hämta `dmz-reader-1.0.0-release.apk` nedan till telefonen.
2. Öppna filen. Första gången frågar Android om webbläsaren eller filhanteraren får installera appar. Tillåt det.
3. Välj **Installera**.

### Kontrollera filen

| | SHA-256 |
|---|---|
| `dmz-reader-1.0.0-release.apk` | `1b6edc1553d99d09928ade53d48b55f075deb2a5551809221cffe115b6520ceb` |
| Signeringscertifikatet | `5a3679ecc4ccbf9b61247e3580b178028f03e6e47e6d6d7f325d2a295475f727` |

APK:n är byggd från taggen `v1.0.0`, commit `7cda1f6fcc6c45e5c7374d3b8fa389a7600f8703`.

## Sökindex (valfritt)

DMZ Reader fungerar utan sökindex. Indexet behövs bara för fulltextsökning i tidningarnas text.

| Fil | Storlek | SHA-256 |
|---|---|---|
| `search-2026-10-06-006-p3.db` | 90,9 MB (90 943 488 byte) | `11033d29553b59083dea94fde90c55bcf7a0cb23ef5452a4083f923903682dfc` |

Enklast är att trycka **Ladda ned sökindex** i appens Sök-flik eller under **Inställningar → Sökindex**. Appen hämtar filen från den här releasen, kontrollerar storlek och SHA-256 mot `search-index.json` och importerar den. Hämtningen följer inställningen **Ladda ned endast via wifi** och kan återupptas om den bryts.

Det går också att hämta filen till telefonen och välja **Importera sökindex** under **Inställningar**. Efter importen kan du ta bort den hämtade filen.

Sökindexet tillämpar automatisk maskering av igenkända telefon- och faxnummer, e-postadresser, post- och bankgironummer och personnummerliknande mönster. OCR-skador kan innebära att en del uppgifter inte känns igen. I sökutdrag kan `•••` markera en maskerad uppgift. Tidningarna i läsaren är oförändrade.

## Om arkivet

Tidningarna hämtas direkt från DMZArkivet, som byggs och underhålls av Gustav. DMZ Reader är ett fristående projekt och inte en officiell app från DMZArkivet. **Arkivet körs på en privat uppkoppling**, så ibland kan det vara tillfälligt otillgängligt. Nummer som du redan har laddat ned går att läsa ändå, och en nedladdning som bryts fortsätter där den slutade.

## Licens

Källkoden är licensierad under MIT. **Licensen gäller bara DMZ Readers källkod.** Tidningsmaterial, omslag, OCR-text, sökindex och annan arkivdata omfattas inte, och rättigheterna tillhör respektive rättighetsinnehavare. Se [NOTICE.md](https://github.com/Rognils/dmz-reader/blob/main/NOTICE.md).

Rättighetsinnehavare som vill att material tas bort kan öppna ett ärende under [Issues](https://github.com/Rognils/dmz-reader/issues).
