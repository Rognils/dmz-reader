# ADR 0004: Sökindexet byggs på datorn

## Status
Accepted (FAS 8B, 2026-10-08)

## Context
Fulltextsökningen (plan E10) behöver texten på arkivets cirka 11 300 sidor. Texten finns som ett OCR-lager i PDF:erna, sammanlagt 12,3 GB. Att låta telefonen hämta och läsa alla PDF:er för att bygga ett index är uteslutet: det skulle belasta arkivservern, ta timmar och kräva verktyg som inte finns i appen.

Textlagret är dessutom inte rent. Tre saker måste hanteras innan texten går att söka i: spalter som blandas av textutvinningen, ord som är delade i bitar ("h ö g ta la re") och ord som är avstavade vid radbrytning.

## Decision
- **`search.db` byggs på datorn** av `tools/build_search_index.py` ur en lokal PDF-cache och importeras i appen som en färdig, skrivskyddad SQLite-fil (plan D2). Appen innehåller ingen kod för textutvinning eller normalisering.
- **Textutvinning med `pdftotext -raw`**, som följer OCR-programmets ordning. Standardläget och `-layout` blandar spalter.
- **Två texter per sida:** `rawText` (orörd) och `searchText` (normaliserad). FTS5-indexet ligger över `searchText`.
- **Normaliseringen bygger på ordbitarnas lägen** (`pdftotext -raw -bbox`), inte på gissningar ur texten: bitar som ligger tätare än radens ordmellanrum hör ihop.
- **Tveksamma fall avgörs mot en fryst ordlista**, `tools/data/normalizer_lexicon_v1.txt`, byggd en gång ur arkivets eget textlager. Normaliseringen läser bara den filen och det aktuella numret.
- **`normalizerVersion`** i `meta` anger vilka regler och vilken ordlista som användes. Samma PDF och samma version ger alltid samma `searchText`.
- **Varje nummer knyts till sin PDF** i tabellen `issues`: sidantal, storlek, ETag och SHA-256.
- **Bygget är deterministiskt:** samma PDF:er, samma inventory och samma `--built-at` ger en fil med samma SHA-256.
- **Kvaliteten mäts, inte antas:** ett facit med visuellt kontrollerade sidor, ett granskat stickprov av normaliseringens ändringar och en visuell kontroll av läsordningen.

## Reason
- Datorn har verktygen (poppler), utrymmet och tiden. Hela bygget tar drygt tre minuter när PDF:erna finns.
- En färdig fil gör appens del liten och testbar: öppna, fråga, visa. FAS 1 och FAS 8 har visat att en fil byggd med datorns SQLite läses av appens `BundledSQLiteDriver`.
- En fryst ordlista gör resultatet oberoende av vilka nummer som råkar byggas tillsammans. I piloten byggdes ordlistan under körningen, och då kunde samma sida få olika text i olika byggen.
- Ordböcker (hunspell sv_SE och en_US) prövades som tillägg till ordlistan. De ändrade 0,1 procent av fallen, med ungefär lika många fel som rättningar, och hade fört in licensvillkor i förrådet. De används inte.
- Lägena på sidan skiljer utspärrad text från vanlig text på ett sätt som texten ensam inte kan: i utspärrad text är luckorna inne i orden tydligt mindre än luckorna mellan dem.

## Consequences
- Ett nytt eller ändrat nummer i arkivet kräver ett nytt bygge på datorn och en ny import i appen. Inventory kan däremot uppdateras utan nytt sökindex (plan D3).
- PDF-cachen på 12,3 GB måste finnas kvar på datorn, eller hämtas om, för att bygget ska gå att upprepa. Hämtningen tar cirka 45 minuter vid 5 MB/s.
- Ändras en regel eller ordlistan ska `normalizerVersion` höjas och ett nytt stickprov granskas. Det står i `tools/README.md`.
- Normaliseringen är inte felfri. Kända rester: ord som förblir delade när bitarna inte går att avgöra, riktiga bindestreck som försvinner i ovanliga sammansättningar vid radslut ("luma-" + "keying"), och avstavningar som bryts av att OCR-programmet har lagt blocken i annan ordning. Felandelen finns i fasrapporten för FAS 8.
- Läsordningen är OCR-programmets. Spalterna hålls ihop, men på sidor med flera artiklar kan blocken komma i annan ordning än en läsare skulle välja. Det påverkar bara frassökningar över blockgränser.
- `rawText` fördubblar ungefär databasens storlek. Den behålls åtminstone genom FAS 9 och kan tas bort med en flagga.
