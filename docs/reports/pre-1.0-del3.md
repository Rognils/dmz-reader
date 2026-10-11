# Före 1.0.0, del 3: sista städningen före publiceringsdagen

2026-10-11. Fortsättning på [pre-1.0-del2.md](pre-1.0-del2.md).

## Sammanfattning

Testsviten behöver inte längre någon arkivfil. En ren klon, utan `cache/` och utan `dist/`, klarar JVM-testerna, verktygens tester och `tools/device_test.sh`. Ett test klarade inte bytet och är nu en manuell kontroll, av en orsak som inte är utredd (se nedan). Arkivindexets text i Inställningar är ändrad på `release/1.0.0`, och den globala git-adressen är noreply-adressen.

Ingenting hämtades från arkivservern. Release-appen på telefonen är orörd (1.0.0-rc3, uppdaterad 2026-10-11 01:01:57, samma som före). Filter p3 är orört.

## 1. Testerna som läste den riktiga PDF:en för 1986 nr 1

`device_test.sh` lade filen på telefonen från datorns `cache/pdf/`. JVM-testerna och verktygens tester läste den aldrig.

### Bytta till testnumret

Testnumret (`app/src/androidTest/assets/test_issue.pdf`, gjort av `tools/make_test_media.py`) har nu 28 sidor i stället för sex, lika många som 1986 nr 1 har i arkivindexet. Testköraren lägger det i testkörningens läs-cache under det numrets namn. Ingen av testerna nedan kontrollerar vad som står på sidorna, så de verifierar samma sak som förut.

| Test | Vad det verifierar |
|---|---|
| `BookmarkDeviceTest.createBookmarkInReaderAndOpenItFromFavorites` | Bokmärke på sida 5, titel, och att det öppnas på rätt sida från Favoriter |
| `FullTextDeviceTest.importSearchOpenHitAndStepToNextHit` | En sökträff öppnar läsaren på sida 4, och nästa träff går till sida 25 |
| `ReadingStateDeviceTest.progressCountsReadPagesAndManualStatusKeepsHistory` | Lästa sidor räknas, snabbläddring gör det inte, manuell status rör inte historiken |
| `ReadingStateDeviceTest.restartPhase1ReadPages` och `restartPhase2ContinueReading` | Läspositionen överlever ett tvångsstopp |
| `StatisticsDeviceTest.idleTimeIsNotCounted` | Stillastående tid räknas inte som lästid |
| `StatisticsDeviceTest.forceStopPhase1Read` och `forceStopPhase2SessionSurvived` | Lässessionen sparas vid tvångsstopp |
| `HqDeviceTest.hqPageNextPageAndOfflineFromCache` | HQ-sida, sidbyte, zoom och läsning ur cachen utan nät |
| `HqDeviceTest.pageWithoutScanShowsMessageAndPdfPage` | Sida utan skanning ger meddelande och PDF-sidan |
| `HqDeviceTest.hqCacheIsShownAndClearedInSettings` | HQ-cachen visas och töms, läs-cachen rörs inte |

`ReaderDeviceTest` (nedladdning och återupptagning) och `OfflineDeviceTest` använde redan testnumret. Läsaren visar där nu "av 28" i stället för "av 6".

**En sak att känna till om bytet.** Läsaren kastar en cachefil vars storlek inte stämmer med arkivindexet och hämtar om den. Testköraren fyller därför ut testnumret med blanktecken till exakt den storlek arkivindexet anger för 1986 nr 1 (15 105 472 byte), mellan det sista objektet och korsreferenstabellen. Sidorna är desamma. Alternativet var att ändra numrets rad i testkörningens arkivindex, men då hade sökindexets versionskontroll börjat varna i andra tester.

### Manuella kontroller: `tools/manual_archive_file_test.sh`

Skriptet tar en sökväg till en arkivfil man har skaffat själv, säger det tydligt, kontrollerar att filen har den storlek arkivindexet anger, och kör med spärren mot arkivet påslagen. Det anropas inte av `device_test.sh`, Gradle eller något annat skript. Testerna hoppas över när ingen arkivfil ligger på telefonen.

| Nummer | Test | Varför en arkivfil krävs |
|---|---|---|
| 1986 nr 1 | `ReaderDeviceTest.verifyReadThrough198601`, `verifyZoom198601`, `verifyLandscapeSpreadAndRotation` | Renderingstider, zoom och uppslag i skannade sidor |
| 1986 nr 1 | `HqDeviceTest.hqInLandscapeShowsSinglePage` | Hänger sig med testnumret, se nedan |
| 1995 nr 14 | `ReaderDeviceTest.verifyReadThrough199514`, `verifyZoom199514`, `ReadingStateDeviceTest.verifyPrerender199514` | Förrendering och renderingstider i ett nummer på 92 sidor |
| 1995 nr 15 | `ReaderDeviceTest.verifyLoad199515`, `verifyMemoryOnPage15`, `verifyMemoryOpenedOnPage15`, `verifyMemoryPageByPage` | Minnet i arkivets tyngsta sidor |

Alla utom HQ-testet var redan körningar på begäran (`-e dmzVerify true`). Nytt är att de kräver att arkivfilen faktiskt är lagd på telefonen, så att de inte av misstag mäter i testnumret.

Skriptet är kört en gång med 1986 nr 1 ur datorns cache: 4 av 4 godkända, 0 förfrågningar till arkivet. De två andra numren är inte körda.

`HqRealDeviceTest` och `RealIndexDeviceTest` är som förut manuella kontroller mot den riktiga servern respektive det riktiga sökindexet och ingår inte i skriptet.

### Öppen fråga: HQ i liggande läge hänger sig med testnumret

`HqDeviceTest.hqInLandscapeShowsSinglePage` hängde sig tre gånger av tre med testnumret, efter rotationen till liggande läge. Skärmen visade rätt uppslag ("Sida 2–3 av 28"), men testmiljön väntade i all oändlighet på att appen skulle bli stilla. En profil visade att appens huvudtråd bara svarade på testmiljöns frågor, och att ingen sida renderades om.

Jag vet inte varför. Två iakttagelser:

- Samma test går igenom på `main` med arkivfilen, och på grenen med arkivfilen (en gång av en, i rent läge).
- En körning på grenen med arkivfilen hängde sig i det andra liggande-testet (`verifyLandscapeSpreadAndRotation`). Den startade direkt efter att jag hade tvångsstoppat en hängd körning i liggande läge, så telefonens läge kan ha spelat in. Nästa körning gick igenom.

Det kan alltså vara en egenhet i testmiljön, men det kan också vara något i läsaren som aldrig blir färdigt i liggande läge när alla sidor är lika stora. Testnumrets sidor är alla A4, medan arkivets sidor skiljer sig lite åt. Det är inte utrett, och jag har inte ändrat något i appen. Förslag: utred det före 1.0.0, eftersom något som aldrig stannar i så fall drar batteri hos en användare.

På användarens anvisning körs testet tills vidare mot en riktig fil.

### Verktygens tester som hoppas över utan arkivdata

Fem av verktygens 250 tester körs bara när arkivdata finns på datorn, och hoppas över i en ren klon. De kontaktar inte arkivet.

| Test | Kräver |
|---|---|
| `test_covers.RebuildFromCache` (1 test) | `cache/covers/` |
| `test_search_index.Golden` (3 tester) | `dist/search.db` |
| `test_search_index.RealIssueAloneAndInFullBuild` (1 test) | PDF-cachen och `dist/search.db` |

### Den rena klonen

Ny klon av grenen i en temporär katalog. Den enda PDF:en i den är testnumret, och `cache/` och `dist/` finns inte.

| Kontroll | Utfall |
|---|---|
| JVM-tester | 500 av 500, 0 överhoppade |
| Verktygens tester | 250, varav 5 överhoppade (tabellen ovan) |
| `device_test.sh`, efter avinstallation och ny installation av utvecklingsbygget och testpaketet | 77 av 77, omstartstestet 2 av 2, tvångsstoppet godkänt |
| Arkivet | 0 förfrågningar, 0 spärrade försök, 0 byte över wifi och mobilnät |
| Användarens app | yttre fingeravtryck identiskt före och efter |

Av de 77 hoppas de manuella kontrollerna över, som förut, och nu också HQ i liggande läge.

Den första körningen från den rena klonen hängde sig i HQ-testet ovan och fick avbrytas. Siffrorna gäller den andra körningen, efter att testet gjorts manuellt, från en ny klon och med ny installation.

### Övrigt i del 1

- `device_test.sh` läser inte längre något ur datorns `cache/`. `DMZ_SEED_ISSUES` är borttaget.
- README har avsnittet "För utvecklare", och `tools/README.md` beskriver det manuella skriptet.
- `tools/prepush_check.sh` tillåter testnumrets nya kontrollsumma (`e3712eef…651821b`).

## 2. Arkivindexets text

På `release/1.0.0` säger Inställningar nu: "Arkivindexet innehåller listan över nummer i arkivet. Ett nyare arkivindex kan importeras här utan att appen behöver uppdateras." Inget test nämnde den gamla texten. JVM-testerna: 500 av 500.

## 3. Global git-identitet

```
user.name=Rognils
user.email=117741407+Rognils@users.noreply.github.com
```

Två inställningar på GitHub återstår för användaren, under Settings → Emails:

1. **Keep my email addresses private**
2. **Block command line pushes that expose my email**

## Kvar till publiceringsdagen

- Utred varför läsaren inte blir stilla i liggande läge med testnumret (ovan).
- De två GitHub-inställningarna.
- I övrigt som i [pre-1.0-del2.md](pre-1.0-del2.md) och checklistan i [publiceringsbeslut.md](publiceringsbeslut.md).
