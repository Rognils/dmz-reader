# ADR 0023: Separata app-ID, egen signeringsnyckel och PDF-minnets gräns

## Status
Accepted (FAS RC, 2026-10-10)

## Context
Inför 1.0 behövde tre saker avgöras.

**Utveckling och användning delade app.** Debug-bygget hade samma app-ID som den app användaren läser i. Testerna kördes i en isolerad katalog (ADR 0021), men varje installation av ett nytt bygge bytte ut användarens app, och en release signerad med en annan nyckel hade inte gått att installera utan att avinstallera den.

**Release signerades med debug-nyckeln.** Den nyckeln finns på varje utvecklingsdator och duger inte för en app som ska uppdateras över tid.

**PDF-läsaren kunde hålla över 600 MB i native heap.** PDF-motorn (PdfRenderer) sparar avkodade bilder för varje sida den har ritat så länge filen är öppen. Sedan FAS 3 öppnas filen om efter fyra olika sidor (ADR 0011). I 1995 nr 15, där sidorna är skannade i 600 dpi, kostar en sida 150–630 MB, och fyra sådana gav 850–940 MB PSS. Mätningen i FAS 3 gjordes på sidor där minnet råkade vara lågt och visade 381 MB. FAS 3-bygget beter sig likadant som det nuvarande på samma sidor: det är ingen regression.

## Decision

### App-ID
| Variant | App-ID | Namn | Användning |
|---|---|---|---|
| release | `se.c64.dmzreader` | DMZ Reader | användarens app |
| rc | `se.c64.dmzreader.rc` | DMZ Reader RC | prov av release-koden bredvid användarens app |
| debug | `se.c64.dmzreader.debug` | DMZ Reader DEV | utveckling och alla tester |

- rc har samma kod, R8-regler och signering som release. Bara app-ID och namn skiljer.
- De instrumenterade testerna körs i debug, fortfarande med isolerad lagring. `tools/device_test.sh` tar fingeravtrycket av användarens app och kör testerna i debug.

### Signering
- Release och rc signeras med en egen nyckel: RSA 4096, giltig till 2061, i `~/dmz-keys/dmz-reader-release.jks`.
- Lösenorden läses från `~/.gradle/gradle.properties`. Varken nyckel eller lösenord finns i repot, och `.gitignore` spärrar nyckelfiler. Se `docs/signing.md`.
- Utan uppgifterna byggs release och rc osignerade, hellre än med debug-nyckeln.

### R8
Uttryckliga keep-regler i `app/proguard-rules.pro` för Room 3 (de genererade `_Impl`-klasserna och databasens klasser), SQLite som följer med appen (JNI), kotlinx.serialization (serialiserare och fältnamn, som är filformatet för arkivindex och säkerhetskopior) och Apache Commons Net.

### Versioner
En releasekandidat heter `X.Y.Z-rcN` och har versionCode strax under sin version: 1.0.0-rc1 ger 9901, och 1.0.0 ger 10000. Den färdiga versionen kan då installeras ovanpå kandidaten.

### PDF-minnet
Utöver regeln om fyra sidor öppnas filen om, vilket släpper det sparade, när:

- native heap har vuxit mer än 192 MB sedan filen öppnades, och det sparade hör till andra sidor än dem som ska ritas nu
- ingenting har ritats på tre sekunder, och native heap har vuxit mer än 192 MB

Sidorna som ritas i samma anrop (ett uppslag) får ligga kvar tillsammans.

## Reason
- Med egna app-ID kan ett nytt bygge och alla tester installeras utan att användarens app berörs, och release-koden kan provas på telefonen innan den ersätter något.
- En nyckel utanför repot kan inte läcka med koden, och lösenord i en fil som bara ägaren kan läsa är den enklaste lösningen som fungerar med Gradle.
- Uttryckliga R8-regler gör att ett fel visar sig i bygget eller i provet, inte hos användaren efter en biblioteksuppdatering.
- Gränsen i byte i stället för i antal sidor träffar det som är problemet. Vanliga nummer (tiotals MB per sida) påverkas inte: för dem gäller fortfarande regeln om fyra sidor, och renderingstiderna är oförändrade.
- Att släppa den visade sidans bilder efter tre sekunder kostar en ny avkodning vid nästa zoom (cirka två sekunder på de tyngsta sidorna), men annars låg över 600 MB kvar så länge sidan visades.

## Consequences
- Medan en tung sida ritas behövs minnet ändå: toppen under renderingen av sida 15 i 1995 nr 15 är oförändrad, cirka 790 MB native heap under ett par sekunder. Det går inte att undvika med PdfRenderer.
- Gränsen mäts på hela appens native heap, där också appens egna bilder ligger. Den har marginal för dem, men en omöppning kan ibland ske i onödan. Det kostar 10–120 ms.
- Efter flytten till release-bygget är användarens app inte felsökningsbar. Dess data går då inte att läsa med `run-as`, så testernas fingeravtryck av användarens data kan inte längre tas. Skyddet är i stället att testerna körs i en annan app.
- Tappas nyckeln eller lösenordet bort går appen inte att uppdatera utan avinstallation och en ny flytt (`docs/migration.md`).
- Telefonen kan ha tre appar med liknande namn. DEV och RC kan avinstalleras när de inte behövs.
