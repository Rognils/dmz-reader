# ADR 0025: Avbrott, nätverksvillkor och transaktioner före 1.0

## Status
Accepted (rättningar på `fix/pre-1.0-review`, 2026-10-30). Ändringarna är lokalt verifierade; enhetsprov och releasegodkännande återstår.

## Context
En kodgranskning efter 1.0.0-rc4 hittade felvägar i redan implementerade funktioner:

- Ett systemstopp under importen av ett hämtat sökindex tog bort den färdiga filen och beställningen, men kunde lämna gränssnittet i läget Importerar.
- Android 8–13:s kö kontrollerade inte wifi-inställningen och levde längre än den synliga aktiviteten.
- Rangordningens rad-ID kunde återanvändas mot ett nytt sökindex.
- Backupens sammanslagning och läsarens läs–ändra–skriv låg delvis utanför databastransaktioner.
- PDF-nedladdaren kunde radera den gamla offlinefilen efter ett misslyckat namnbyte.
- HTTP-anropens vanliga completion-handler avbröt inte alltid blockerande nätväntan när korutinen avbröts.

Användaren har bett om rättningar och en dokumenterad överlämning till Claude Code. Detta kompletterar ADR 0012, 0013, 0017, 0018 och 0020 utan nya produktfunktioner eller ändrat dataformat. Den frysta projektplanen ändras inte.

## Decision

### Backup och användardata
Backupimportens inläsning, sammanslagning, skrivning och kontroll körs i en sammanhängande Room 3-transaktion genom `BackupDao.inTransaction`. Exportens tre tabeller läses också i samma transaktion.

`IssueUserStateDao.modify` och `BookmarkDao.modify` gör hela läs–ändra–skriv i en transaktion. Ett fristående Mutex i läsrepositoryt är inte tillräckligt för att samordna med importen. Befintliga sammanslagningsregler, mjuk radering och databasens schema 8 behålls.

### Äldre Android
`InAppDownloadScheduler` följer både aktivitetens synlighet och ett flöde med tillåtna nätverk. Ett nät är tillåtet när det är anslutet och wifi-inställningen medger det, med samma definition av wifi som tidigare: ett nät utan datakostnad.

`collectLatest` avbryter och inväntar städningen av den gamla körningen före en ny. Köposter och påbörjade filer bevaras genom den befintliga köhanteringen. Nätverksbyte, ändrad inställning och återgång till förgrunden kan återuppta kön. Rotation räknas inte som att användaren har lämnat appen. Android 14+ använder fortfarande UIDT.

### Avbrott och filbyte
Ett systemavbrott under indeximport behåller beställningen och den färdighämtade filen. UI går till Interrupted. Nästa försök kontrollerar storlek och SHA-256 igen och återanvänder den färdiga filen. Vanliga importfel och slutförd import städar som tidigare.

HTTP-anrop får en underordnad korutin som reagerar på förälderns avbrott och anropar `Call.cancel()`, även medan `execute()` eller en läsning blockerar. Bevakningen avslutas i `finally`.

Vid ett misslyckat PDF-namnbyte rapporteras fel utan att målfilen raderas. På Androids filsystem ersätter ett lyckat namnbyte målfilen atomiskt; om det inte går får den gamla kopian ligga kvar.

### Sökindexets identitet
Sökningens nyckel är den tolkade frågan tillsammans med det öppnade indexets identitet. Byte av index räknas därför som en ny sökning även om texten är oförändrad. Rangordning och utdrag kan kräva en bestämd `LoadedSearchIndex`; de returnerar null om ett annat index blivit aktivt. Gamla rangordningar grupperas inte med nya sidmappningar, och gamla utdrag läggs inte tillbaka efter bytet.

## Reason
Rättningarna uppfyller befintliga löften om läsdata, wifi, förgrundskörning, återupptagning och bevarande av tidigare filer. Regressionstester använder riktig Room 3/BundledSQLiteDriver och lokala HTTP-servrar; ingen arkivtrafik behövs.

## Consequences
- Backup håller databasens skrivtransaktion under sammanslagningen. Den lilla datamängden gör det rimligt, men stora framtida backuper bör prestandamätas.
- En verifierad men ännu inte importerad indexfil kan ligga kvar tills hämtningen slutförs eller avbryts.
- En misslyckad PDF-uppdatering kan lämna en komplett `.part` för nästa försök, samtidigt som den gamla PDF:en är kvar.
- Nätverkscallback och aktivitetens livscykel behöver fortfarande provas på Android 8–13. JVM-tester verifierar körlogiken, inte Androidsystemets callbacks.
- Ändringarna hör till ett nytt releaseunderlag efter RC4. De får inte smygas in i den frysta `release/1.0.0` under antagandet att den bara skiljer i versionsnummer. Se överlämningsrapporten.
