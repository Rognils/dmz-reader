# Backlog inför 1.1

Idéer som dök upp under arbetet med releasekandidaterna men som inte hör hemma i 1.0. Det här är en lista, inte en plan: ingenting är beslutat, prioriterat eller påbörjat. Varje punkt som tas upp ska först bli ett beslut enligt planens arbetssätt.

## Läsning och nedladdning

- **Läs fortsätter själv när servern bryter.** Arkivservern bryter överföringar vid cirka 200 MB. Nedladdningskön fortsätter av sig själv, men Läs visar ett fel och kräver "Försök igen". Berör 1995 nr 15 och några till.
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

## Utveckling och test

- **Fingeravtryck av användarens data i release-bygget.** Ett release-bygge går inte att läsa med `run-as`, så testerna kan bara visa att appen inte har startats eller bytts ut. En kontrollsumma som appen själv visar under Diagnostik vore ett alternativ.
- **Nyp-zoom i prov av release-varianten.** Går inte att göra med adb. Kräver ett instrumenterat test mot rc-varianten.
- **Ostadiga tester.** Ett läsartest som hämtar från arkivet föll en gång utan belagd orsak under v0.10.1. Tester som beror på nätet kunde skiljas från resten av sviten.
- **Uppdatering av Kotlin-pluginen** (lint påpekar att 2.4.21 finns).
