# Backlog inför 1.1

Idéer som dök upp under arbetet med 1.0 men som inte hör hemma där. Det här är en lista, inte en plan: ingenting är beslutat, prioriterat eller påbörjat. Varje punkt som tas upp ska först bli ett beslut enligt planens arbetssätt.

Uppdaterad 2026-10-31, efter publiceringen av 1.0.0 ([release-1.0.0.md](reports/release-1.0.0.md)). Listan är sorterad i tre grupper: användarnytta, underhåll och större framtida områden.

## Användarnytta

### Sökindexet

- **Fråga efter manifestet igen när nätet kommer tillbaka.** På releasedagen visade release-appen "Anslut till internet för att hämta det …" med wifi anslutet, tills den startades om. Processen hade sparat ett misslyckat svar från innan repot var publikt. Den som har appen öppen när nätet kommer tillbaka måste i dag trycka **Försök igen**.
- **Pausbeskedet anger skälet.** "Hämtningen av sökindexet avbröts. Den fortsätter där den slutade." står i dag oavsett varför hämtningen stoppades. Beskedet borde säga om det var nätet som försvann, wifi-regeln eller att appen gick till bakgrunden.
- **Notis om ett nyare sökindex.** Hämtningen från GitHub-releasen finns sedan 1.0.0-rc4 (`docs/reports/rc4.md`). Det som återstår ur specifikationen är en notis i Inställningar när releasen har ett nyare index än det importerade, aldrig en automatisk hämtning. Kräver att appen minns manifestet för det importerade indexet.
- **Besked när sökindexet är äldre än arkivindexet**, med en förklaring av hur man hämtar ett nytt.

### Sökningen

- **Sökningen bland numren behåller träffarna tills textsökningen har svarat.** Från "a" till "am" försvinner en nummerträff i ungefär en halv sekund innan textträffarna kommer, eftersom sökningen bland numren räknas om direkt för varje tecken. Textträffarna står redan kvar ([rc5.md](reports/rc5.md), kända begränsningar).

### Läsning och nedladdning

- **Läs fortsätter själv när servern bryter.** Arkivservern bryter överföringar vid cirka 200 MB. Nedladdningskön fortsätter av sig själv, men Läs visar ett fel och kräver "Försök igen". Berör 1995 nr 15 och några till.
- **Besked när kön väntar på arkivet.** Är arkivet nere står ett köat nummer som "Köad" utan förklaring, och systemet försöker igen i tysthet. Efter några misslyckade försök borde nummervyn säga att arkivet inte går att nå just nu och att nedladdningen fortsätter av sig själv.
- **Snabbare första zoom efter stillhet.** Efter tre sekunder utan rendering släpps den visade sidans avkodade bilder, och nästa zoom tar cirka två sekunder på de tyngsta sidorna. Tiden skulle kunna anpassas efter hur mycket minne telefonen har.

### HQ

- **Minnas HQ-läget.** Läget gäller bara tills läsaren stängs. Det kunde i stället väljas per nummer eller i inställningarna.
- **Mindre HQ-rad.** När skanningen väl visas tar raden med texten "HQ" onödig plats. En liten märkning i hörnet räcker.

### Bokmärken, statistik och läsdata

- **Ta bort eller rätta en lässession.** I dag går en session inte att ta bort i appen. Det blev aktuellt när en kontroll under flytten till release lade till en session på 12 sekunder.
- **Titta utan att det räknas som läsning.** Att öppna ett nummer för att kontrollera något räknar sidan som läst efter 1,5 sekunder.
- **Statistik per dag,** nu när sessionerna delas vid midnatt.

### Säkerhetskopia och inställningar

- **Inställningarna i säkerhetskopian.** "Ladda ned endast via wifi" och vald publikation följer inte med vid en flytt.
- **Påminnelse om export.** Appen vet när den senaste exporten gjordes.
- **Tydligare besked om var exporten hamnade.** Filväljaren öppnas där den senast användes, och appen bekräftar utan att säga var filen ligger.

### Om-sidan, språk och tillgänglighet

- **Licenser för tredjepartsbibliotek** på Om-sidan (Apache Commons Net, OkHttp, Coil, AndroidX, kotlinx).
- **Engelsk översättning** av appen och Om-texten.
- **Om-sidans tillbakapil i ett fast toppfält,** så att den inte rullar bort med texten.
- **TalkBack-genomgång med riktig skärmläsare.** Hittills är innehållsbeskrivningarna bara prövade genom testerna, inte genom att använda appen med TalkBack.

## Underhåll

- **Gradle 10.** Gradle varnar för att `hostSqlite` löses upp under konfigurationen, och för funktioner som tas bort i Gradle 10.
- **Compose-testregeln v2.** Testerna använder den äldre, föråldrade testregeln. Den nyare (`androidx.compose.ui.test.junit4.v2`) är också värd att pröva mot det enda kvarvarande ostadiga testet: ett ritningsfel i bokmärkestestet ("multithreaded access to SnapshotStateObserver"), som har inträffat en gång. Fyra andra orsaker är belagda och rättade (`docs/reports/gates.md`).
- **Stora UI- och ViewModel-filer.** Ingen bred uppdelning gjordes inom buggrättningarna före 1.0.
- **Lint-varningarna,** 21 stycken vid releasen. Versionsförslagen, bland annat Kotlin-pluginen 2.4.21, är inte kontrollerade mot nätet. Dessutom `UsableSpace` och KTX-förslag. Två varningar om trust manager gäller Apache Commons Net, inte appens HTTPS-klient.
- **Prov på fler Android-versioner.** Android 9 och 13 är prövade på emulatorer för rc5, och Android 16 på telefonen. Android 8 och 10–12 är oprövade, liksom 14 och 15. En emulatorkörning av sviten per version vore ett mål.
- **Fingeravtryck av användarens data i release-bygget.** Ett release-bygge går inte att läsa med `run-as`, så testerna kan bara visa att appen inte har startats eller bytts ut. Ett alternativ vore en kontrollsumma som appen själv visar under Diagnostik.
- **Nyp-zoom i prov av release-varianten.** Går inte att göra med adb. Kräver ett instrumenterat test mot rc-varianten.
- **Uppdatera `gh` i utvecklingsmiljön** till en version som har `--accept-visibility-change-consequences`. 2.45.0 saknar flaggan, och repot gjordes publikt med `gh api` i stället. Rör bara utvecklarmiljön, inte appen.

## Större framtida områden

- **Lägre minnestopp på de tyngsta sidorna.** Medan en sida i 600 dpi ritas behövs cirka 900 MB under ett par sekunder (ADR 0023). Det går inte att undvika med PdfRenderer. En annan PDF-motor, eller förrenderade sidor, vore ett större beslut.
- **Jämföra alla nummer sida för sida.** 183 nummer vilar på stickprovet och på att antalet skanningar stämmer med sidantalet. En fullständig bildjämförelse kräver att alla skanningar hämtas, cirka 70 GB, och behöver arkivägarens medgivande.
- **HQ för sidorna 39 och 40 i 1993 nr 3.** PDF:en är gjord av en annan skanning än den som finns i arkivet. Kan bara lösas i arkivet.
- **HTTP-spegel för skanningarna.** Planens reservlösning om FTP blir ett problem.
- **Innehållsanalys och frågor på naturligt språk.** Planens avsnitt "Senare". Kräver ett eget beslut, bland annat om var en modell körs.

## Genomgång av Codex kvarvarande punkter

Avsnitt 6 i [Codex granskning](reports/codex-granskning-2026-10-30.md), jämfört med [github-sidan-2.md](reports/github-sidan-2.md), [rc5.md](reports/rc5.md) och [release-1.0.0.md](reports/release-1.0.0.md):

| Codex punkt | Beslut | Varför |
|---|---|---|
| PDF-motorns minnestoppar (ADR 0023) | med, större framtida områden | Oförändrad i 1.0. Fanns redan i backloggen och står nu i rätt grupp. |
| Instabiliteten i liggande läge | utelämnad | Utredd i github-sidan-2.md och klassad som testproblem: 200 rotationer utan testram gav inga avvikelser. Ingen appbugg att rätta. |
| Lint-varningarna: versionsförslag, `UsableSpace`, KTX, trust manager i Commons Net | med, underhåll | Varningarna fanns kvar vid releasen. Den äldre punkten om Kotlin-pluginen ingår nu här. |
| Gradle: `hostSqlite` och Gradle 10 | med, underhåll | Ingenting är ändrat sedan granskningen. |
| Compose-testregeln | med, underhåll | Ingenting är ändrat. Den är sammanslagen med den äldre punkten om ostadiga tester. |
| Stora UI- och ViewModel-filer | med, underhåll | Ingen uppdelning är gjord. |
| Äldre texter beskriver historiskt beteende, och påhittad testtext är redan genomförd | utelämnad som egen punkt | Gäller dokumentationen, inte en kodändring. Den följde ändå upp: backloggens punkt om påhittad text i testdatabaserna är borttagen, eftersom den är genomförd sedan rc3. |
| Appen minns inte vilket manifest indexet kom från, och har ingen notis om nyare index | med, användarnytta | Fortfarande öppen. Står i punkten "Notis om ett nyare sökindex", som förutsätter att appen minns manifestet. |

Borttaget ur den tidigare listan, eftersom det är gjort:

- **Länk till källkoden på Om-sidan.** Finns i 1.0.
- **Påhittad text i testdatabaserna.** Genomförd i rc3.
- **Tester som beror på nätet.** Sviten når inte längre arkivet eller GitHub. Proven mot riktig server körs bara på begäran.
