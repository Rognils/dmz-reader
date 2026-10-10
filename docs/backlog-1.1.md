# Backlog inför 1.1

Idéer som dök upp under arbetet med releasekandidaterna men som inte hör hemma i 1.0. Det här är en lista, inte en plan: ingenting är beslutat, prioriterat eller påbörjat. Varje punkt som tas upp ska först bli ett beslut enligt planens arbetssätt.

## Läsning och nedladdning

- **Läs fortsätter själv när servern bryter.** Arkivservern bryter överföringar vid cirka 200 MB. Nedladdningskön fortsätter av sig själv, men Läs visar ett fel och kräver "Försök igen". Berör 1995 nr 15 och några till.
- **Besked när kön väntar på arkivet.** Är arkivet nere står ett köat nummer som "Köad" utan förklaring, och systemet försöker igen i tysthet. Efter några misslyckade försök borde nummervyn säga att arkivet inte går att nå just nu och att nedladdningen fortsätter av sig själv.
- **Lägre minnestopp på de tyngsta sidorna.** Medan en sida i 600 dpi ritas behövs cirka 900 MB under ett par sekunder. Det går inte att undvika med PdfRenderer. En annan PDF-motor, eller förrenderade sidor, vore ett större beslut.
- **Snabbare första zoom efter stillhet.** Efter tre sekunder utan rendering släpps den visade sidans avkodade bilder, och nästa zoom tar cirka två sekunder på de tyngsta sidorna. Tiden kunde anpassas efter hur mycket minne telefonen har.
- **Prov på äldre Android.** Android 8–13 är oprövat. En emulatorkörning av sviten per version.

## HQ

- **Minnas HQ-läget.** Läget gäller per öppning av läsaren. Ett val per nummer eller i inställningarna.
- **Mindre HQ-rad.** När skanningen väl visas tar raden med texten "HQ" plats i onödan. En liten märkning i hörnet räcker.
- **Jämföra alla nummer sida för sida.** 183 nummer vilar på stickprovet och på att antalet skanningar stämmer med sidantalet. En fullständig bildjämförelse kräver att alla skanningar hämtas, cirka 70 GB, och behöver arkivägarens medgivande.
- **HQ för sidorna 39 och 40 i 1993 nr 3.** PDF:en är gjord av en annan skanning än den som finns i arkivet. Kan bara lösas i arkivet.
- **HTTP-spegel för skanningarna.** Planens reservlösning om FTP blir ett problem.

## Sökning

- **Besked när sökindexet är äldre än arkivindexet**, med en förklaring av hur ett nytt hämtas.
- **Hämtning av sökindex från GitHub-release.** Kräver publikt repo. Specifikation:
  - Indexet hämtas aldrig automatiskt och aldrig vid uppstart.
  - Sök-fliken utan index visar ett kort: "Fulltextsökning kräver ett sökindex på cirka 90 MB." Knapp: "Ladda ned sökindex". Kortet förklarar att allt annat fungerar utan index.
  - Nedladdningen respekterar "Ladda ned endast via wifi", kan återupptas (Range), går via samma överföringsmekanism som PDF (en överföring åt gången) och kontrolleras mot SHA-256 från release-metadata före import.
  - Importen sker med befintlig import-logik. Användardata rörs aldrig.
  - Finns ett nyare index visas en notis i Inställningar, aldrig en automatisk hämtning.
  - Källan är GitHub-release, aldrig arkivservern. Inget token i appen.
- **Innehållsanalys och frågor på naturligt språk.** Planens avsnitt "Senare". Kräver ett eget beslut, bland annat om var en modell körs.

## Bokmärken, statistik och läsdata

- **Ta bort eller rätta en lässession.** I dag går en session inte att ta bort i appen. Aktualiserat av att en kontroll under flytten till release lade till en session på 12 sekunder.
- **Titta utan att det räknas som läsning.** Att öppna ett nummer för att kontrollera något räknar sidan som läst efter 1,5 sekunder.
- **Statistik per dag,** nu när sessionerna delas vid midnatt.

## Säkerhetskopia och inställningar

- **Inställningarna i säkerhetskopian.** "Ladda ned endast via wifi" och vald publikation följer inte med vid en flytt.
- **Påminnelse om export.** Appen vet när den senaste exporten gjordes.
- **Tydligare besked om var exporten hamnade.** Filväljaren öppnas där den senast användes, och appen bekräftar utan att säga var filen ligger.

## Om-sidan och projektet

- **Länk till källkoden** i `about_sv.md` när repot är publikt. Bara en textändring.
- **Licenser för tredjepartsbibliotek** på Om-sidan (Apache Commons Net, OkHttp, Coil, AndroidX, kotlinx).
- **Engelsk översättning** av appen och Om-texten.
- **Om-sidans tillbakapil i ett fast toppfält,** så att den inte rullar bort med texten.

## Tillgänglighet

- **TalkBack-genomgång med riktig skärmläsare.** Hittills är innehållsbeskrivningarna bara prövade genom testerna, inte genom att använda appen med TalkBack.

## Utveckling och test

- **Fingeravtryck av användarens data i release-bygget.** Ett release-bygge går inte att läsa med `run-as`, så testerna kan bara visa att appen inte har startats eller bytts ut. En kontrollsumma som appen själv visar under Diagnostik vore ett alternativ.
- **Nyp-zoom i prov av release-varianten.** Går inte att göra med adb. Kräver ett instrumenterat test mot rc-varianten.
- **Påhittad text i testdatabaserna.** `fts_test.db`, `search_test.db` och skripten `tools/build_fts_testdb.py` och `tools/build_search_testdb.py` innehåller korta ordagranna meningar ur tidningarna. Byt dem mot påhittad text, så att nya versioner inte innehåller tidningstext, och lägg de nya kontrollsummorna i `tools/prepush_check.sh`. Äldre versioner ligger kvar i historiken.
- **Ostadiga tester.** Fyra orsaker är belagda och rättade före 1.0 (`docs/reports/gates.md`). Kvar är ett ritningsfel i bokmärkestestet ("multithreaded access to SnapshotStateObserver"), som har inträffat en gång. Den nyare Compose-testregeln (`androidx.compose.ui.test.junit4.v2`) är värd att pröva. Tester som beror på nätet kunde också skiljas från resten av sviten.
- **Uppdatering av Kotlin-pluginen** (lint påpekar att 2.4.21 finns).
