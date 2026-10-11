# Fasrapport – FAS 9: Fulltextsökning i appen

> **Skärmbilderna som nämns här finns inte i repot.** De ligger lokalt hos projektets ägare, se [docs/skarmbilder.md](../skarmbilder.md).

Datum: 2026-10-09 · Gren: `feature/fas-9-fulltext`, mergad till `main` och taggad `v0.9-fas9` · App 0.9.0 (versionCode 900)

## 1. Sammanfattning

Appen söker nu i tidningarnas text. Sökindexet importeras under Inställningar → Sökindex, sökningen körs medan man skriver, och träffarna visas per nummer med textutdrag. En träff öppnar läsaren på rätt sida, där en träffrad hoppar mellan träffarna i numret. "Sök i numret" finns i nummervyn och i läsaren.

Det riktiga sökindexet (185 nummer, 11 293 sidor, 149 MB) är importerat på telefonen och ligger kvar. Importen tog 2,0 sekunder. Rangordningen tar 4–96 ms för de frågor som prövades, och första sökningen efter en omstart av telefonen var lika snabb som en varm.

Användardatan är oförändrad: 1 rad i `IssueUserState`, 0 i `Bookmark` och 1 i `ReadingSession`, före och efter.

## 2. Skapade och ändrade filer

| Fil | Innehåll |
|---|---|
| `domain/FullTextQuery.kt` | frågelagret: söktext till FTS5-uttryck |
| `domain/FullTextResults.kt` | gruppering, sortering, filter, bytt PDF, textutdrag |
| `domain/MetadataSearch.kt` | filtren utökade med publikation och år |
| `data/search/SearchDb.kt` | rangordning utan utdrag, utdrag per rad, kontrollfrågor |
| `data/search/SearchIndexManager.kt` | import, kontroll, atomiskt byte, borttagning, start |
| `ui/search/SearchScreen.kt` | sökvyn och dess vymodell |
| `ui/search/IssueSearch.kt` | "Sök i numret", träffraden, träffrader med utdrag |
| `ui/settings/SearchIndexSection.kt` | Inställningar → Sökindex |
| `ui/reader/ReaderScreen.kt`, `ui/issue/IssueScreen.kt`, `ui/DmzReaderRoot.kt`, `AppContainer.kt`, `DmzReaderApp.kt` | inkoppling |
| `app/build.gradle.kts` | version 0.9.0 / 900 |
| `tools/build_search_testdb.py`, `app/src/debug/assets/search_test.db` | litet sökindex i det riktiga formatet, för testerna |
| `app/src/test/…`: `FullTextQueryTest`, `FullTextResultsTest`, `SearchIndexManagerTest` | 41 nya JVM-tester |
| `app/src/androidTest/…`: `FullTextDeviceTest`, `RealIndexDeviceTest` | instrumenterade tester |
| `tools/README.md` | "Uppdatera sökindexet på telefonen" |
| `docs/decisions/0020-fulltextsokning-i-appen.md` | ADR |
| `docs/screenshots/fas9/` | 18 skärmbilder |

## 3. Vad som byggdes

**1. Nytt inbyggt index.** Appen 0.9.0 har inventory 2026-10-06-005 och installerades ovanpå 0.7.0 utan avinstallation. Arkivindexet byttes vid första start, och alla 185 nummer har nu `pageCount`. Nummervyn visar "49 sidor" i stället för ett ungefärligt värde, även för nummer som aldrig har öppnats. Ett oöppnat nummer visar "Oläst"; procenten kommer med första lästa sidan, och stämmer då direkt (ditt eget nummer visar "2 av 44 lästa · 5 %").

**2. Import av search.db.** Filväljare, kopiering till `filesDir/data/search.db.part` med förlopp i MB och procent, kontroll, och först därefter byte. Den tidigare filen ligger kvar tills den nya har öppnats. Kontrollen: `PRAGMA quick_check`, tabellerna `meta`, `issues`, `pages` och `pages_fts`, `searchSchemaVersion` 1, en provsökning och att indexet inte är tomt. Varje fel har ett eget meddelande på svenska, och det aktiva indexet är då orört. Vyn visar arkivversion, normalisering, antal nummer och sidor, storlek och importdatum, och har knappen "Ta bort sökindex". Avviker arkivversionen från det aktiva arkivindexet visas en diskret notis, och sökningen fungerar ändå.

**3. Frågelagret** (`parseFullTextQuery`, ren Kotlin):

| Söktext | FTS5-uttryck |
|---|---|
| `amiga joystick` | `"amiga" AND "joystick"` |
| `"Commodore 128"` eller `”Commodore 128”` | `"Commodore 128"` |
| `amig*` | `"amig"*` |
| `am*` | `"am"` (för kort för prefix) |
| `C64`, `C-64`, `C 64` | `("C64" OR "C 64")` |
| `amiga AND atari` | `"amiga" AND "AND" AND "atari"` |
| `NEAR(amiga atari)` | `"NEAR(amiga" AND "atari)"` |
| `"`, `*`, `---`, `a` | ingen sökning |

Sökningen körs från två tecken, 300 ms efter senaste tangenttryckningen, och en ny sökning avbryter den pågående.

**4. Fråga i två steg.** Steg 1 ger rad-id och bm25 för alla träffar, utan textutdrag och utan att läsa tabellen `pages`. Steg 2 hämtar utdraget för en rad i taget (`MATCH ? AND rowid = ?`), först när raden visas. Filtren publikation, år, favoriter och nedladdade tillämpas på rangordningen tillsammans med katalogen och användardatan.

**5. Resultatvyn.** "N sidor i M nummer", sorteringen Relevans eller Äldst först, och filterchips. Träffarna grupperas per nummer med omslag, titel, antal träffar och de tre bästa sidorna med utdrag, där sökordet står i accentfärg. "Visa alla N träffar" fäller ut. Numren läggs till 20 åt gången när listan rullas. Metadata-träffarna (bokmärken och nummer) ligger överst när de finns. Utan sökindex fungerar metadata-sökningen som förut, med en ruta som leder till inställningarna.

**6. Öppna en träff.** Läsaren öppnas på träffens sida, genom samma väg som "Läs" (och därmed `TransferCoordinator` om PDF:en inte finns lokalt). Träffraden visar till exempel `"Commodore 128" · träff 1 av 6 ‹ ›`, hoppar mellan träffarna i numret, går runt efter den sista och kan stängas. Den försvinner med läsaren.

**7. Sök i numret.** En knapp i nummervyn och ett förstoringsglas i läsarens övre fält öppnar samma dialog: sökfält, antal sidor och träfflista med utdrag. Ett tryck öppnar sidan och tänder träffraden.

**8. Bytt PDF.** `issues.ocrPdfSize` och `ocrPdfEtag` i sökindexet jämförs med det aktiva arkivindexet. Avvikande nummer får texten "Sökindexet gäller en äldre version av filen" i nummervyn, i "Sök i numret" och vid träffarna. Ett nummer som saknas i sökindexet får i stället texten "Numret finns inte i sökindexet och går inte att söka i."

**Avvikelse från beställningen:** årsfiltret väljs med knappar för varje år (ett tryck för ett år, ett till för ett intervall), inte med ett reglage. Reglaget var svårt att träffa rätt år med.

## 4. Verifiering på telefonen

Den riktiga `dist/search.db` lades i Download och valdes i filväljaren.

**Import:** 2,0 sekunder för 148,9 MB, mätt av appen från vald fil till färdigt byte (kopiering, kontroll och öppning). Förloppsraden hann visa "Kontrollerar filen…". Filen i Download är raderad.

**Sökningar** (antalen är desamma som datorn ger för samma frågor mot samma fil):

| Sökning | Resultat | Rangordning | Gruppering | Från inmatning till visat resultat |
|---|---|---|---|---|
| högtalare | 230 sidor i 110 nummer | 5 ms | 8 ms | 0,93 s |
| Amiga | 7 289 sidor i 185 nummer | 73 ms | 25–56 ms | 0,67–0,88 s |
| "Commodore 128" | 168 sidor i 73 nummer | 5 ms | 3 ms | 0,68 s |
| C64 | 3 022 sidor i 184 nummer | 51 ms | 21 ms | 0,82 s |
| amig* | 8 204 sidor i 185 nummer | 94 ms | 35 ms | 0,77 s |
| skärm | 1 057 sidor i 177 nummer | 20 ms | 8 ms | 0,80 s |

Den sista kolumnen är mätt av testet utifrån och innehåller 300 ms väntan på paus i skrivandet, uppritningen av listan och testramverkets egen fördröjning. Sökningen och grupperingen står för högst 130 ms av den. De första textutdragen var på plats 10–20 ms efter resultatet. Jag har inte mätt hur stor del av resten som är testramverket.

"C64" ger 3 022 sidor mot 2 749 för bara stavningen "C64": de 273 extra är sidor som bara har "C-64" eller "C 64".

**Rätt sida:** den bästa träffen för "Commodore 128" är 1986 nr 1 sida 4. Läsaren öppnades på "Sida 4 av 28", med träffraden `"Commodore 128" · träff 1 av 6`. "Sök i numret" på "Amiga" gav 3 sidor (2, 13 och 18), och träffraden stegade 2 → 13 → 18.

**Sortering och filter:** "Äldst först" börjar med 1986 nr 1. Åren 1993–1995 gav "2618 sidor i 72 nummer" för "Amiga", samma som datorn.

**Efter omstart av telefonen** (du godkände omstarten):

| | Efter omstart | Varm |
|---|---|---|
| Öppna sökindexet vid appstart | 127 ms | 92 ms |
| Första sökningen, "Amiga" | 60 ms | 71–77 ms |
| "joystick" (första gången) | 31 ms | – |
| "Fairlight" (första gången) | 4 ms | – |

Första sökningen efter omstart är alltså inte långsammare än en varm. Det som FAS 8 inte kunde mäta är därmed mätt.

**Skärmbilder** i `docs/screenshots/fas9/`: 01–03 inställningarna före och efter import, 04 tom sökvy, 05 högtalare, 06 Amiga, 07 C64, 08 skärm, 09 Äldst först, 10 årsväljaren, 11 filtret 1993–1995, 12 "Commodore 128", 13 läsaren med träffraden, 14 "Sök i numret", 15 nästa träff, 16 nummervyn, 17 årgångsvyn, 18 efter omstart.

## 5. Din data

| | Före fasen | Efter fasen |
|---|---|---|
| `IssueUserState` | 1 (1988 nr 1, sida 3) | 1 (samma) |
| `Bookmark` | 0 | 0 |
| `ReadingSession` | 1 | 1 (samma) |

Kopian från fasens början ligger i `~/dmz-backup-före-fas9/appdata.tar`. Ingen nollställning gjordes.

- De nya testerna läser i 1986 nr 1 och tar bort läsdatan de skapar. Finns det egen läsdata för numret hoppas de över.
- Under arbetet lämnade en misslyckad testkörning kvar en lässession för 1986 nr 1, eftersom läsaren skriver sin session i bakgrunden efter att testet har städat. Jag tog bort raden och ändrade städningen så att den väntar på skrivningen. Därefter har radantalet varit 1/0/1 efter varje körning.
- Testerna flyttar undan ett importerat sökindex och lägger tillbaka det. Det riktiga indexet har samma storlek och tidsstämpel som vid importen.
- Kvar på telefonen efter testerna: PDF:en för 1986 nr 1 i läs-cachen (15 MB) och numrets rad i `IssueLocalState`, som är telefonens egen data och inte användardata.
- **Den äldre instrumenterade sviten kördes inte i sin helhet**, eftersom flera av dess tester skriver läsdata utan att ta bort den. Jag körde de tester som inte skriver användardata.

## 6. Testresultat

| Test | Resultat |
|---|---|
| JVM-tester | 361 av 361 (320 före fasen) |
| Python-tester | 172 av 172 |
| Lint | 0 fel, 8 varningar |
| `FullTextDeviceTest` (importera, sök, öppna träff, rätt sida, nästa träff; filter, sortering, bytt PDF; annan arkivversion) | 3 av 3 |
| `RealIndexDeviceTest` mot det riktiga indexet | 1 av 1 |
| `LibraryNavigationTest`, `FtsInstrumentedTest` | 4 av 4, 2 av 2 |
| Release-bygge | bygger, 24,3 MB; inte kört på telefonen |

JVM-testerna täcker frågelagret (escapning, fraser, prefixgränsen, C64-varianterna, tomt och skräp), gruppering och sortering, filter, paginering, validering vid import, återgång vid fel och markeringen för bytt PDF.

Ett slumptest matar 4 000 skräptexter genom frågelagret och kör resultatet mot riktig FTS5. Det hittade ett fel: ett NUL-tecken i söktexten klippte av uttrycket mitt i ett citattecken och gav ett syntaxfel. Styrtecken tas nu bort.

`LibraryNavigationTest` behövde rulla till sidantalet, som hamnade utanför skärmen när "Sök i numret" kom till.

## 7. Acceptanskriterier

Planens kriterier för FAS 9:

- [x] **En sökning ger rätt nummer, rätt sida och rätt utdrag.** Antalen för sex sökningar stämmer med datorns, och den bästa träffen för "Commodore 128" är 1986 nr 1 sida 4 med utdraget kring frasen.
- [x] **Ett tryck öppnar exakt rätt sida.** Läsaren visade "Sida 4 av 28", och träffraden stegade till sidorna 2, 13 och 18 för "Amiga".
- [x] **Sökningen svarar på under 1 sekund på telefonen.** Rangordningen tar 4–96 ms. Från inmatning till visat resultat gick det 0,67–0,93 s i testet, inräknat 0,3 s väntan på paus. Marginalen till en sekund är alltså liten i den mätningen, se kända begränsningar.
- [x] **En search.db med annan `datasetVersion` fungerar och visar en notis.** Ett testindex med arkivversion 2020-01-01-001 importerades på telefonen: sökningen gav rätt träff, och sökvyn visade "Sökindexet är från en annan arkivversion."
- [x] **En trasig search.db avvisas utan att den tidigare påverkas.** Prövat i JVM-tester med en textfil, en avklippt fil, fel `searchSchemaVersion`, saknade tabeller och ett tomt index. Den aktiva filen var byte för byte densamma efteråt, och gick att söka i.

Utöver planens kriterier: utan sökindex fungerar metadata-sökningen som förut, och "Sök i numret" finns i nummervyn och i läsaren.

## 8. Kända begränsningar

- **Tiden från inmatning till visat resultat är 0,7–0,9 s i testet.** Av det är 0,3 s den beställda pausen och högst 0,13 s sökning. Resten är uppritning och testramverk, och är inte uppdelat.
- **Relevans jämför bm25 mellan sidor.** Ett nummer rankas efter sin bästa sida. För ett ord som "Amiga" säger ordningen inte mycket.
- **Sökorden skrivs i appens logg** tillsammans med svarstiden. Det är avsett för en privat app.
- **Förloppet under kopieringen går inte att se med den här filen**: kopieringen är klar på under en sekund.
- **Android 8–13 är fortfarande oprövat**, och release-varianten är inte körd på telefonen.
- **"C64"-regeln gäller högst fyra bokstäver följda av siffror.** "Amiga 500" söks som två ord.
- Lint har två nya varningar: en om hur ledigt utrymme mäts före importen, och en om en nyare Gradle-version.

## 9. ADR

- **0020 Fulltextsökning i appen** – ny, Accepted.

## 10. Återstår, och förslag inför FAS 10

Ingenting återstår i fasen. FAS 10 är inte påbörjad.

1. **Sessioner vid tvångsstopp** (loggat inför FAS 5): utred löpande sparning av lässessioner innan statistiken byggs på dem. Den här fasen visade dessutom att sessionen skrivs i bakgrunden efter att läsaren har stängts.
2. **Bestäm vad statistiken ska räkna.** Lästa sidor och nummer finns redan i `seenPages`. Lästid finns bara i `ReadingSession`, där sessioner under 5 sekunder inte sparas.
3. **De äldre instrumenterade testerna bör städa efter sig**, på samma sätt som de nya. Annars går sviten inte att köra på en telefon med riktig läsdata.
4. **Sökstatistik** (antal sökningar, vanligaste sökord) ingår inte i planens E12. Säg till om den ska med; då behövs en egen tabell.
5. **Gradle** har en nyare version (9.8.0). En uppdatering passar mellan två faser.
