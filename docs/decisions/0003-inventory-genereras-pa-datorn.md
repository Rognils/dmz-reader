# ADR 0003: Inventory genereras på datorn och kopieras in med ett byggsteg

## Status
Accepted

## Context
Arkivets katalogstruktur och filnamn är oregelbundna på flera sätt (se `docs/archive_notes.md`): dubbelnummer, b-utgåvor, tvåsiffriga sidnummer, två generationer OCR-PDF:er. Någon måste tolka dem. Planens grundprincip (A3) är att appen aldrig ska förstå rå arkivstruktur.

Appen behöver också en katalog att starta med, innan användaren har importerat något.

## Decision
- All tolkning av arkivet görs av Python-toolchainen i `tools/`. Resultatet är `data/archive_inventory.json`, validerad mot `docs/inventory.schema.json` (FAS 0 och 0B).
- Appen läser bara den färdiga filen. Den innehåller färdiga adresser; appen bygger aldrig adresser själv.
- Filen följer med APK:n som baseline i `assets/archive_inventory.json`.
- **Kopieringen är ett byggsteg.** Gradle-uppgiften `syncInventory` kopierar `data/archive_inventory.json` till `app/src/main/assets/` före varje bygge (`preBuild` beror på den). Uppgiften `checkInventory` stoppar bygget om källfilen saknas. Kopian i `assets/` versionshanteras inte.
- Vid start läser appen assets-filen, kontrollerar att `schemaVersion` är 1 och jämför `datasetVersion` med den aktiva versionen (ADR 0005).

## Reason
- Arkivlogiken är lättare att skriva, testa och köra om i Python på en dator än i en app. Den har 97 tester mot verkliga filnamn.
- Appen blir enklare och kan inte gå sönder av att arkivet har ett oväntat filnamn.
- Ett byggsteg gör att appens baseline alltid är den validerade filen. En handkopierad fil kan bli gammal utan att någon märker det.
- En källa till sanningen: filen finns på ett ställe i repot.

## Consequences
- En ändring i arkivet kräver att toolchainen körs om och att ett nytt inventory byggs in eller importeras (import kommer i FAS 7).
- Bygget kräver att `data/archive_inventory.json` finns. Den är versionshanterad, så en ren utcheckning bygger direkt.
- Bygget kör inte toolchainen och validerar inte filen. Det gör toolchainen vid export.
- `checkInventory` behövs som egen uppgift: en kopieringsuppgift utan källfil hoppas bara över av Gradle, och då hade en gammal kopia kunnat följa med.
