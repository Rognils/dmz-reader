# Rapport – 1.0.0-rc3: besked när arkivet är nere, LICENSE och README, påhittad testdata

Datum: 2026-10-11 · Gren: `release/1.0-rc3`, mergad till `main` och taggad `v1.0.0-rc3` · App 1.0.0-rc3 (versionCode 9903)

## 1. Sammanfattning

Tre slags ändringar, och ingen ny funktion:

- **Besked när arkivet inte går att nå.** Om-sidan säger att DMZArkivet körs på en privat uppkoppling, och feltexterna för PDF och HQ säger att arkivet kan vara tillfälligt otillgängligt.
- **`LICENSE`, `NOTICE.md` och `README.md`** i repots rot.
- **Testdatabaserna har bara påhittad text.** Ingen mening kommer längre ur tidningarna.

Ingen logik, datamodell, migrering eller importkod är ändrad. Databasen är kvar på version 8 och säkerhetskopian på format 2.

Din app är uppdaterad till rc3 med `adb install -r`, och din data är oförändrad: exporten före och exporten efter är identiska post för post.

**Ingen apprättning kom ur del E.** HQ-felet från grindkörningen låg i testet, inte i appen (`docs/reports/gates.md`, del E). rc3 innehåller därför ingen ändring av hur lästa sidor räknas.

## 2. Skapade och ändrade filer

**Nya:**

- `LICENSE`, `NOTICE.md`, `README.md`
- `app/src/main/java/se/c64/dmzreader/data/network/ArchiveMessages.kt`
- `docs/reports/rc3.md`, `docs/screenshots/rc3/`

**Ändrade i appen:**

- `app/build.gradle.kts` (version)
- `app/src/main/assets/about_sv.md` (en mening)
- `data/hq/HqRepository.kt`, `ui/reader/ReaderViewModel.kt` (feltexterna)
- `ui/search/IssueSearch.kt` (ett exempel i en kommentar, som citerade en tidningsmening)

**Ändrade tester och verktyg:**

- `AppVersionTest`, `MarkdownTest`, `ReaderLogicTest`, `HqRepositoryTest`, `SearchDbTest`, `SearchIndexManagerTest`, `AboutDeviceTest`
- `app/src/debug/assets/fts_test.db`, `search_test.db`, `tools/build_fts_testdb.py`, `tools/build_search_testdb.py`
- `tools/prepush_check.sh` (nya kontrollsummor)
- `docs/backlog-1.1.md` (den tysta kön)

## 3. Ändringarna

### Version

`versionName` 1.0.0-rc3, `versionCode` 9903. App-ID oförändrat.

### Om-sidan

Sist i stycket under "Tack, Gustav!", före länken:

> DMZArkivet körs på en privat uppkoppling, så ibland kan arkivet vara tillfälligt otillgängligt.

### Feltexterna

När arkivservern inte svarar alls behandlas anslutning som vägras, tidsgräns och DNS-fel lika: alla är IO-fel och ger samma text. Det var så förut också. Bara texten är ändrad.

| Flöde | Text |
|---|---|
| PDF | "Det gick inte att nå DMZArkivet. Kontrollera din internetanslutning och försök igen. Arkivet körs på en privat server och kan ibland vara tillfälligt otillgängligt. Nedladdningen kan återupptas där den slutade." |
| HQ | "HQ gick inte att hämta. Det gick inte att nå DMZArkivet. Kontrollera din internetanslutning och försök igen. Arkivet körs på en privat server och kan ibland vara tillfälligt otillgängligt. PDF-sidan visas." |

Den gemensamma meningen ligger på ett ställe, `ARCHIVE_UNREACHABLE_MESSAGE`.

**"Nedladdningen kan återupptas där den slutade" stämmer med hur appen beter sig:**

- *I koden:* den ofullständiga filen sparas som `.part` tillsammans med filens ETag eller ändringstid. Nästa försök skickar `Range` och `If-Range`. Svarar servern 206 fortsätter filen där den slutade. Svarar den 200 (filen har ändrats) börjar hämtningen om.
- *I praktiken:* under grindkörningen bröts en nedladdning av ett wifi-tapp tre gånger. Vid nästa försök loggade appen `nedladdad, 15105472 byte, återupptagen=true`.
- *Villkoret:* servern måste skicka ETag eller Last-Modified. Arkivet gör det för alla 185 PDF:er.

Meningen står på alla nätverksfel för PDF, även när ingenting hann hämtas. Texten kan inte skilja de fallen åt utan att logiken ändras, och du godkände det.

HQ-texten har kvar sin ram, "HQ gick inte att hämta." och "PDF-sidan visas.", som du godkände. På skärmen blir den fem rader (`docs/screenshots/rc3/03-hq-arkivet-nas-inte.png`).

**Den tysta kön** står i backloggen: ett köat nummer visas som "Köad" utan förklaring när arkivet är nere.

### LICENSE, NOTICE och README

- **`LICENSE`:** MIT-licensens standardtext med `Copyright (c) 2026 Rognils`, utan tillägg.
- **`NOTICE.md`:** licensen gäller bara DMZ Readers källkod. Tidningsmaterial, omslag, OCR-text, sökindex och annan arkivdata omfattas inte. Äldre versioner av testdata i git-historiken innehåller mycket korta utdrag ur arkivmaterialet och omfattas inte heller.
- **`README.md`:** vad appen är, tack till Gustav med länk, att arkivet körs på en privat uppkoppling, Datormagazin Retro med länk, installation av APK, manuell import av sökindex, att `•••` i sökutdrag betyder maskerade kontaktuppgifter och att tidningarna i läsaren är oförändrade, att appen saknar reklam, konton, spårning och analys, licensavgränsningen, och raden till rättighetsinnehavare. Inga skärmbilder.

README beskriver sökindexet som det ser ut efter del G. Fram till att den filen finns som release stämmer inte stycket om `•••` med det index som ligger i din app, som är omaskerat.

### Testdatabaserna

All text i `fts_test.db`, `search_test.db` och de två skripten är omskriven. Sökorden som testerna använder står kvar på samma sidor, så antalet träffar är detsamma.

Kontrollerat mot det riktiga sökindexet:

| Fil | Längsta gemensamma teckenföljd med samma sida i arkivet | Följder på 20 tecken eller mer |
|---|---|---|
| `fts_test.db` | 23 tecken | en: produktnamnet "Amiga 1200" två gånger i rad |
| `search_test.db` | 18 tecken | ingen |

Förut var det 265 respektive 652 tecken i hela meningar.

| Fil | Ny SHA-256 |
|---|---|
| `app/src/debug/assets/fts_test.db` | `0c689be27c9766237bf909d429b2f536076d43fac7778181b670aed30184da73` |
| `app/src/debug/assets/search_test.db` | `d82ecd840e1cc536b102fc6b9ea8e0bfbdd36a9602a2db8bc33b055b2b6034bc` |

`tools/prepush_check.sh` tillåter de två nya och de tre historiska, och inget annat.

**Det här gäller testdatabaserna.** Två andra testfiler innehåller fortfarande riktiga rader, som granskningen redan har noterat: `tools/testdata/search_lines.json` och `search_golden.json`, som normaliseringen och sökfacit prövas mot. Uppgiften gällde inte dem.

## 4. Testresultat

| Svit | Resultat |
|---|---|
| JVM (`testDebugUnitTest`) | 492 av 492 |
| Python (`tools/tests`) | 206 av 206 |
| Lint | 0 fel, 13 varningar (samma som rc2) |
| Instrumenterad svit på telefonen | 76 av 76 (69 tester och 7 stresstester som hoppas över), plus omstartstestet och tvångsstoppstestet |
| Yttre fingeravtryck av din app | identiskt före och efter sviten |

Första körningen av sviten föll på ett söktest: jag hade skrivit bort ordet "spel" ur en av de nya testsidorna. Ordet lades tillbaka, och sviten gick igenom.

## 5. Release-bygget

| | |
|---|---|
| Fil | `dist/dmz-reader-1.0.0-rc3-release.apk`, 24 394 456 byte |
| SHA-256 | `29d66fc89fabe11141b4feec492d81bf93e47a83ca9c5ff99aa21f145220339e` |
| Paket | `se.c64.dmzreader`, versionCode 9903, versionName 1.0.0-rc3 |
| Certifikat | `CN=DMZ Reader, O=C64, C=SE` |
| Certifikatets SHA-256 | `5A:36:79:EC:C4:CC:BF:9B:61:24:7E:35:80:B1:78:02:8F:03:E6:E4:7E:6D:6D:7F:32:5D:2A:29:54:75:F7:27` – stämmer |
| Innehåll | inga databaser, inget sökindex |

APK:n är inte uppladdad någonstans.

## 6. Uppdateringen av din app

Samma rutin som för rc2.

| Steg | Utfall |
|---|---|
| Före | 1.0.0-rc2 (9902), installerad 2026-10-10 11:13:39, uppdaterad 12:37:52 |
| Export före, via appen, till Download | "2 nummer med data, 0 bokmärken och 4 lässessioner" |
| `adb install -r` | Success |
| Efter | 1.0.0-rc3 (9903), samma installationstid, uppdaterad 2026-10-11 01:01:57, samma signatur |
| Export efter | "2 nummer med data, 0 bokmärken och 4 lässessioner" |

Jämförelsen:

| Del | Före | Efter | |
|---|---|---|---|
| Läsdata per nummer | 2 | 2 | identiska |
| Bokmärken | 0 | 0 | identiska |
| Lässessioner | 4 | 4 | identiska |

- Fortsätt läsa visar 1991 nr 15, sida 19 av 80, som förut.
- "Ladda ned endast via wifi" är på, före och efter.
- Sökindexet står kvar: arkivversion 2026-10-06-006, 185 nummer, 11 293 sidor, importerat 10 oktober 11:15.
- Inställningssidans nedre del är identisk före och efter, tecken för tecken.

Jag öppnade inte läsaren, så ingen läsning lades till. Det enda som ändrades är tidpunkten för "Senaste export", av mina två exporter. Exportfilerna är hämtade till `~/dmz-backup-före-rc3/` och borttagna ur telefonens Download.

## 7. Skärmbilder

I `docs/screenshots/rc3/`, tagna i debug-appen:

- `01-om-sidan.png` – Om-sidan med den nya meningen
- `02-pdf-arkivet-nas-inte.png` – feltexten för PDF, framkallad med flygplansläge
- `03-hq-arkivet-nas-inte.png` – feltexten för HQ, framkallad med flygplansläge

Flygplansläget påverkar inte arkivet: ingen förfrågan lämnar telefonen. Den tredje bilden visar numrets omslag bakom feltexten.

## 8. Acceptanskriterier

- [x] Version 1.0.0-rc3, versionCode 9903
- [x] Meningen på Om-sidan, sist i stycket och före länken
- [x] Feltexterna för PDF och HQ, bara text och ingen logik
- [x] Meningen om återupptagning kontrollerad mot koden och mot en verklig logg
- [x] Den tysta kön i backloggen
- [x] `LICENSE` med oförändrad MIT-text, avgränsningen i `NOTICE.md` och `README.md`
- [x] README med alla punkter, utan skärmbilder med omslag
- [x] Testdatabaserna och skripten har bara påhittad text, nya kontrollsummor i `prepush_check.sh`, de tre historiska kvar
- [x] NOTICE nämner äldre testdata i historiken
- [x] README förklarar `•••`
- [x] Alla JVM- och Python-tester och den instrumenterade sviten
- [x] Signerat bygge med rätt certifikat
- [x] Export före, `adb install -r`, jämförelse av data och inställningar
- [x] Skärmbilder
- [x] Mergad, taggad `v1.0.0-rc3`, pushad till det privata repot

## 9. Kända begränsningar

- **Feltexterna är långa.** HQ-raden täcker fem rader av sidan när felet visas.
- **Texterna är inte prövade mot ett arkiv som verkligen är nere,** bara med flygplansläge. Koden gör ingen skillnad mellan fallen.
- **README går före verkligheten** på två punkter tills 1.0.0 är släppt: det finns inga releaser att hämta APK och sökindex från, och ditt nuvarande index är omaskerat.
- **Två testfiler med riktiga rader** står kvar, se avsnitt 3.

## 10. Skapade ADR:er

Inga. Ändringarna är texter och testdata.

## 11. Vad som återstår före v1.0.0

1. **Det maskerade sökindexet** (del G): se `docs/reports/search-privacy.md`.
2. **Veckans användning** enligt checklistan i `rc1.md`.
3. **Dina beslut före en publik release**, listade sist i `docs/reports/gates.md`: omslagen i appen, skärmbilderna, textutdragen och ordlistan, arbetsfilerna, e-postadressen i historiken, arbetsdokumenten, och om historiken ska publiceras som den är.
4. **Bygga och signera 1.0.0** (versionCode 10000), kontrollera certifikatet, och uppdatera din app.
5. **Släppa:** göra repot publikt, och lägga APK:n och det maskerade sökindexet som releaser.
