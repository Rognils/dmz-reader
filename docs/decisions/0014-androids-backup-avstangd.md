# ADR 0014: Androids egen backup är avstängd

## Status
Accepted

## Context
Android kan säkerhetskopiera en apps data automatiskt, till Google-kontot och vid överföring till en ny telefon. Det är påslaget som standard.

DMZ Reader lagrar tre slags data: katalogdata (återskapas ur inventory), nedladdade PDF:er (hundratals MB, går att hämta igen) och användardata (läsposition, favoriter, bokmärken, sessioner). Bara användardatan är oersättlig, och för den har planen en egen backupväg: export och import av en JSON-fil (E11, byggs i FAS 6).

Androids molnbackup har en gräns på 25 MB per app. En enda nedladdad PDF är i median 52 MB, så backupen skulle antingen misslyckas eller ta med en godtycklig del av datan.

## Decision
Androids backup stängs av helt:

- `android:allowBackup="false"` och `android:fullBackupContent="false"` i manifestet.
- `android:dataExtractionRules` pekar på `res/xml/data_extraction_rules.xml`, som utesluter alla domäner (`root`, `file`, `database`, `sharedpref`, `external`) för både `cloud-backup` och `device-transfer`. Från Android 12 styr den filen, inte `allowBackup`.

Appens egen JSON-export i FAS 6 är den enda backupvägen.

## Reason
- En backupväg i stället för två. Användaren ska kunna lita på att exportfilen innehåller allt som går att återställa, och inte behöva undra vad Android tog med.
- 25 MB-gränsen krockar med nedladdade PDF:er.
- En automatisk återställning av Room-databasen utan filerna den pekar på skulle ge `IssueLocalState` som säger att nummer är nedladdade fast de inte finns.
- Appen är privat och ska inte skicka data till någon molntjänst (planens A2).

## Consequences
- Vid byte av telefon eller ominstallation följer ingenting med automatiskt. Användaren måste exportera och importera själv.
- **Fram till FAS 6 finns ingen backup alls.** Läsposition och annan användardata som skapas i FAS 4 och 5 försvinner om appen avinstalleras.
- Lintvarningen `DataExtractionRules` är borta.
