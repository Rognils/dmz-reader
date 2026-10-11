# DMZ Reader

DMZ Reader är en gratis Android-app för att läsa Datormagazin och HighScore från [DMZArkivet](https://dmzarkivet.se). Appen är gjord för att arkivet ska vara enkelt att utforska och läsa på mobilen.

- Bläddra bland alla nummer, årgång för årgång, med omslag.
- Läs direkt eller ladda ned nummer för läsning utan nät.
- Appen minns var du slutade, och du kan sätta bokmärken och favoriter.
- Sök i tidningarnas text, med ett sökindex som du importerar själv.
- Visa en sida i originalskanningens upplösning (HQ).
- Säkerhetskopiera läsdata och bokmärken till en fil.

Appen har ingen reklam, inga köp, inga användarkonton och ingen spårning eller analys.

## Tack, Gustav!

DMZArkivet byggs och underhålls av Gustav Almström, som har lagt ner ett omfattande arbete på att samla in, skanna, OCR-behandla och tillgängliggöra de gamla tidningarna. Utan DMZArkivet hade den här appen inte funnits.

DMZ Reader är ett fristående projekt. Appen hämtar tidningarna direkt från arkivet när du läser eller laddar ned dem.

**Arkivet körs på en privat uppkoppling**, så ibland kan det vara tillfälligt otillgängligt. Nummer som du redan har laddat ned går att läsa ändå. En nedladdning som bryts fortsätter där den slutade när arkivet går att nå igen.

## Datormagazin idag

Datormagazin lever vidare genom Datormagazin Retro, med nya nummer om den klassiska datortiden. De nya utgåvorna ingår inte i DMZArkivet. De kan köpas som tidning och, för flera nummer, som PDF på [shop.datormagazin.se](https://shop.datormagazin.se).

## Installera

Appen finns inte i Google Play. Den installeras från en APK-fil och kräver Android 8.0 eller senare.

1. Hämta den senaste `dmz-reader-…-release.apk` under **Releases** här på GitHub.
2. Öppna filen på telefonen. Första gången frågar Android om webbläsaren eller filhanteraren får installera appar. Tillåt det.
3. Välj **Installera**.

En ny version installeras på samma sätt, ovanpå den gamla. Läsdata, bokmärken och nedladdade nummer ligger kvar.

## Sökindex

Sökning på titel, år och nummer fungerar direkt. För att söka i tidningarnas text behövs ett sökindex. Det följer inte med appen, eftersom filen är stor (cirka 90 MB).

1. Hämta `search-….db` under **Releases** till telefonen.
2. Öppna **Inställningar** i appen och välj **Importera sökindex**. Finns det redan ett index heter knappen **Importera nytt sökindex**.
3. Välj filen. Appen kontrollerar den och säger till när sökningen är klar att använda.

Efter importen kan du ta bort den hämtade filen. Allt annat i appen fungerar utan sökindex.

Sökindexet tillämpar automatisk maskering av igenkända telefon- och faxnummer, e-postadresser, post- och bankgironummer och personnummerliknande mönster. OCR-skador kan innebära att en del uppgifter inte känns igen. I sökutdrag kan `•••` markera en maskerad uppgift. Tidningarna i läsaren är oförändrade.

## Licens

Källkoden är licensierad under MIT, se [LICENSE](LICENSE).

**Licensen gäller bara DMZ Readers källkod.** Tidningsmaterial, omslag, OCR-text, sökindex och annan arkivdata omfattas inte, och rättigheterna tillhör respektive rättighetsinnehavare. Se [NOTICE.md](NOTICE.md).

Rättighetsinnehavare som vill att material tas bort kan öppna ett ärende här på GitHub.

## Bygga själv

Appen byggs med Gradle och JDK 17:

```
./gradlew :app:assembleDebug
```

Ett release-bygge kräver en egen signeringsnyckel, se [docs/signing.md](docs/signing.md). Verktygen som förbereder arkivdata beskrivs i [tools/README.md](tools/README.md).
