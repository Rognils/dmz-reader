# Flytta appens data till en ny installation

Behövs när appen byter signeringsnyckel (från debug-bygget till release), när telefonen byts, eller efter en avinstallation. En app signerad med en annan nyckel kan inte installeras ovanpå den gamla, så den gamla måste avinstalleras, och då försvinner dess data. Säkerhetskopian bär över det som är ditt.

## Vad som följer med

| Följer med i säkerhetskopian | Följer inte med |
|---|---|
| favoriter | nedladdade nummer (hämtas igen) |
| bokmärken med titel och anteckning | läs-cachen och HQ-cachen |
| läsposition och lästa sidor per nummer | det importerade sökindexet (importeras igen) |
| manuell markering läst/oläst | det importerade arkivindexet, om ett sådant fanns |
| lässessioner (statistiken) | inställningen "bara wifi" och valet av publikation |

## Stegen

1. **Exportera.** I den gamla appen: Inställningar → Säkerhetskopia → Exportera. Filväljaren öppnas där den senast användes, inte alltid i Download. Välj Download uttryckligen och tryck Spara. Appen bekräftar med antalet nummer, bokmärken och sessioner.
2. **Kontrollera filen.** Den heter `dmz-reader-backup-ÅÅÅÅ-MM-DD.json`. Kopiera den gärna till datorn också (`adb pull /sdcard/Download/dmz-reader-backup-….json`). Antalen i filen ska stämma med vad appen sade.
3. **Avinstallera den gamla appen.** Först nu försvinner dess data från telefonen.
4. **Installera den nya** (`adb install app-release.apk`).
5. **Importera.** I den nya appen: Inställningar → Säkerhetskopia → Importera, välj filen. Appen visar vad filen innehåller och vad importen kommer att lägga till. Bekräfta.
6. **Kontrollera.** Biblioteket visar "Fortsätt läsa" med rätt nummer och sida. Favoriter-fliken visar favoriterna och bokmärkena. Ett tryck på ett bokmärke öppnar rätt sida (numret hämtas först, eftersom cachen är tom).
7. **Importera sökindexet.** Lägg `search.db` i Download (`adb push dist/search.db /sdcard/Download/`), och välj Inställningar → Sökindex → Importera sökindex.
8. **Städa.** Ta bort säkerhetskopian och `search.db` ur Download när allt är kontrollerat.

Importen lägger till och slår ihop. Den tar aldrig bort något, så den kan göras om utan risk.

## Övningen med testdata (2026-10-10)

Flytten övades från utvecklingsbygget (`se.c64.dmzreader.debug`, debug-nyckeln) till provvarianten (`se.c64.dmzreader.rc`, release-nyckeln, krympt med R8). Det är samma byte av nyckel och samma kod som den riktiga flytten.

**Testdatan i den gamla appen:**

| | |
|---|---|
| Favoriter | 1986 nr 1 och 1986 nr 3 |
| Läsdata | 1986 nr 1: sida 9, nio lästa sidor. 1986 nr 2: sida 3, tre lästa sidor. |
| Bokmärken | 1986 nr 1 sida 5 med titeln "Flyttprov titel" och anteckningen "Anteckning fran flyttovningen". 1986 nr 2 sida 3. |
| Sessioner | 2 (68 och 25 sekunder) |

**Resultat i den nya appen:**

| Kontroll | Resultat |
|---|---|
| Appens besked vid export | "3 nummer med data, 2 bokmärken och 2 lässessioner" |
| Filen jämförd med den gamla appens databas | samma favoriter, läspositioner, lästa sidor, bokmärken och sessioner |
| Sammanfattningen före import | 2 favoritnummer, 2 bokmärken, 2 lässessioner, 2 nummer med läsdata; "lägger till 3 nya nummer" |
| Appens besked efter import | "3 nya nummer, 0 uppdaterade, 2 nya bokmärken, 0 uppdaterade och 2 nya sessioner" |
| Fortsätt läsa | 1986 nr 2, sida 3 av 32, "3 av 32 lästa" |
| Favoriter | 1986 nr 1 och 1986 nr 3 |
| Bokmärket med anteckning | titel och anteckning finns, och det öppnar 1986 nr 1 på sida 5 |
| Det andra bokmärket | öppnar 1986 nr 2 på sida 3 |
| Statistik | 2 påbörjade, 12 lästa sidor, 1 min lästid |
| Sökindexet | "185 nummer och 11293 sidor, på 1,7 sekunder" |

**Tid:** från att exporten påbörjades till att importen var bekräftad gick det 2 minuter. Hela flytten, med kontroll av bokmärkena, statistiken och import av sökindexet, tog knappt 4 minuter. Stegen gjordes med adb, så för hand går det ungefär lika fort.

**Det övningen lärde:**

- Filväljaren öppnades i telefonens rotkatalog vid exporten, inte i Download, eftersom den minns var den senast användes. Filen hamnade därför utanför Download första gången. Välj katalogen uttryckligen.
- Appen bekräftar exporten även då. Kontrollera att filen faktiskt ligger där du tror innan den gamla appen avinstalleras.

## Den riktiga flytten (2026-10-10)

Gjord från debug-bygget 0.11.0 till release 1.0.0-rc1. Antalen före och efter, inställningarna och tiderna står i `docs/reports/rc1.md`, avsnitt 9. Från avinstallation till bekräftad import gick det 56 sekunder.

En sak att tänka på nästa gång: att öppna Fortsätt läsa för att kontrollera sidan räknas som läsning. Kontrollen lade till en läst sida och en kort session. Läs av sidan i biblioteket i stället, där den står på kortet.

Samma steg som i övningen, med två tillägg:

- Före avinstallationen tas också en direktkopia av appens data med `adb exec-out "run-as se.c64.dmzreader tar cf - ."`. Det går bara så länge den installerade appen är ett debug-bygge.
- Efter flytten kan appen behöva nätverksåtkomst och notisbehörighet på nytt, eftersom behörigheterna hör till installationen.

Efter flytten är appen ett release-bygge. Dess data går då inte längre att läsa med `run-as`, och testernas fingeravtryck av användarens data kan inte tas. Testerna körs i utvecklingsbygget, som är en annan app och inte kommer åt release-appens data.
