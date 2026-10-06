# ADR 0005: Versionsmärkning av inventory och sökindex

## Status
Accepted (delen om sökindexet tillämpas först i FAS 9)

## Context
Appen läser två slags datafiler som toolchainen bygger: inventory och, senare, search.db. Båda kan bytas ut utan en ny APK. Appen måste kunna avgöra två saker: om den förstår filens struktur, och om filen är en annan än den som redan är inläst.

## Decision
Varje datafil bär två versioner (planens D3):

- **`schemaVersion`** beskriver strukturen. Appen stöder exakt en version (nu 1). En fil med annat värde avvisas med ett tydligt fel, och det som redan är inläst gäller fortfarande.
- **`datasetVersion`** beskriver ögonblicksbilden av arkivet, i formatet `ÅÅÅÅ-MM-DD-NNN` (listningens datum plus löpnummer).

Så används de för inventory:

1. `schemaVersion` läses först, för sig, innan resten av filen tolkas. En framtida struktur ger då ett fel om versionen och inte ett tolkningsfel.
2. Appen sparar aktiv `datasetVersion` och `schemaVersion` i DataStore.
3. Vid start jämförs filens `datasetVersion` med den aktiva. Är de olika ersätts `Issue` i en transaktion och den nya versionen sparas. Är de lika görs ingenting.
4. Är `Issue` tom läses filen alltid in, oavsett version.
5. Okända extra fält i en fil med rätt `schemaVersion` tolereras. Saknade obligatoriska fält ger fel.

För search.db gäller planens regel: olika `datasetVersion` mellan inventory och sökindex är en varning och ingen spärr.

## Reason
- Två versioner skiljer "jag kan inte läsa det här" från "det här är nytt". Bara det första är ett fel.
- En jämförelse av `datasetVersion` är billig och gör att appen inte skriver om 185 rader vid varje start.
- Att tolerera okända fält gör att toolchainen kan lägga till ett valfritt fält utan att äldre appversioner slutar fungera. Ändras betydelsen av något höjs `schemaVersion`.

## Consequences
- Två inventory med samma `datasetVersion` men olika innehåll betraktas som samma. Toolchainen måste höja löpnumret (`datasetSequence` i `tools/config.json`) när innehållet ändras. Under utveckling kan en ombyggd fil med samma version kräva att appens data rensas.
- Den aktiva versionen sparas efter transaktionen, inte i den. Om appen dör däremellan läses filen in en gång till vid nästa start, vilket är ofarligt eftersom inläsningen ger samma resultat.
- Valideringen i appen är grundare än toolchainens. Den kontrollerar struktur, obligatoriska fält och unika `issueId`, men inte hela JSON-schemat. Full schemavalidering vid import hör till FAS 7.
- Diagnostikvyn visar aktiv `datasetVersion` och `schemaVersion`, och felmeddelandet om en fil avvisats.
