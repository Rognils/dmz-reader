# Beslutsunderlag inför publicering

Datum: 2026-10-11 · Gäller `main` vid `cb471ec` och senare · Repot `Rognils/dmz-reader` är privat

Det här dokumentet samlar de öppna punkterna från `docs/reports/gates.md`. För varje punkt står vad det är, var det finns, alternativen och min rekommendation. Ingenting av det som beskrivs är gjort.

**Läs det här först:** flera alternativ kräver omskriven historik. Det måste göras **före** publiceringen. När repot väl är publikt kan vem som helst ha kopierat det, och då går ingenting att ta tillbaka.

## Sammanfattning

| # | Punkt | Rekommendation | Kräver omskriven historik |
|---|---|---|---|
| 1 | Omslagen i appen och i repot | Behåll | Nej |
| 2 | Skärmbilderna | Ta bort ur den publika historiken | Ja |
| 3 | Textutdragen | Ta bort granskningsfilerna, behåll testfilerna | Ja, för granskningsfilerna |
| 4 | Ordlistan | Behåll | Nej |
| 5 | Serverlistningen | Behåll | Nej |
| 6 | E-postadressen i historiken | Byt till GitHubs noreply-adress | Ja |
| 7 | Arbetsdokumenten | Behåll | Nej |
| 8 | Raderna `Co-Authored-By` | Behåll | Nej |
| 9 | Tester som hämtar från arkivet | Stäng av dem före publicering | Nej |
| 10 | Sökindexet som publik fil | Publicera p2 | Nej |
| 11 | Upphovsrätten | Ditt beslut, jag kan inte bedöma risken | Nej |

Följer du rekommendationerna blir det **en** omskrivning, som gör punkt 2, 3 och 6 på en gång. Proceduren står under punkt 6, och checklistan sist.

---

## 1. Omslagen

**Vad:** 185 omslagsminiatyrer i WebP, 600 pixlar breda, 16 727 820 byte (16,7 MB).

**Var:**

| Plats | |
|---|---|
| Trädet | `data/covers/`, 185 filer |
| Historiken | samma 185 filer, en version av varje |
| APK:n | `assets/covers/`, 185 filer, 16,7 MB av APK:ns 24,4 MB |

**Alternativ:**

- **A. Behåll.** Ingenting ändras. Tidningsmaterial följer med appen och ligger i ett publikt repo.
- **B. Ta bort ur appen och repot.** Appen hämtar då omslagen från arkivet. Reservvägen finns redan (ADR 0016). Följder: varje användare hämtar 233 MB hela skanningar från Gustavs hemmauppkoppling, de 46 omslag han har snyggat till används inte, biblioteket är tomt utan nät första gången, och APK:n krymper till cirka 8 MB. Kräver en ny releasekandidat, och omskriven historik om filerna också ska bort ur den.
- **C. Ta bort ur repot men behåll i appen.** Omslagen byggs då in från en katalog utanför git. Repot går inte längre att bygga av andra utan dem. Kräver omskriven historik.

**Rekommendation: A.** Gustav har inget emot det, miniatyrerna är små, och alternativ B flyttar en verklig last till hans uppkoppling för att minska en risk som inte har ändrats: materialet ligger redan öppet på hans webbplats. Om en rättighetsinnehavare hör av sig är B vägen tillbaka, och den är förberedd.

## 2. Skärmbilderna

**Vad:** 172 PNG-bilder från verifieringen av varje fas, 109 466 857 byte (109 MB). Ungefär 125 visar omslag eller tidningssidor, flera i hög upplösning. En bild, `docs/screenshots/covers/contact_sheet.png`, visar alla 185 omslag.

| Katalog | Bilder |
|---|---|
| `rc1` | 28 |
| `fas11` | 22 |
| `fas9` | 18 |
| `fas7` | 16 |
| `fas3` | 15 |
| `fas6` | 14 |
| `fas2`, `fas5` | 13 var |
| `fas4` | 11 |
| `fas10` | 7 |
| `rc2` | 5 |
| `fas1` | 4 |
| `rc3` | 3 |
| `search-privacy` | 2 |
| `covers` | 1 |

**Var:** trädet och historiken (110 MB i 173 versioner, 22 commits). Inte i APK:n.

Bilderna är 80 % av repots 138 MB.

**Alternativ:**

- **A. Behåll.** Repot blir 138 MB att klona. Helsidor ur tidningarna i hög upplösning ligger i ett publikt repo utan att appen behöver dem.
- **B. Ta bort ur trädet med en vanlig commit.** Bilderna ligger kvar i historiken och är lika publika som förut. Det löser ingenting.
- **C. Ta bort ur historiken.** Repot blir cirka 28 MB. Rapporternas länkar till bilderna slutar fungera. Kräver omskriven historik. Det nuvarande repot sparas privat som arkiv, med bilderna kvar.
- **D. Som C, men behåll de bilder som inte visar tidningsmaterial** (inställningar, statistik, dialoger, Om-sidan), cirka 45 stycken. Mer arbete: varje bild måste gås igenom.

**Rekommendation: C.** Bilderna är arbetsunderlag för dig, inte dokumentation för andra. De är den största posten tidningsmaterial i repot, och de går inte att motivera med att appen behöver dem. README har inga skärmbilder i dag. Vill du ha några där senare kan de tas nya, utan omslag.

## 3. Textutdragen

**Vad och var:**

| Fil | Innehåll | Storlek | Används av |
|---|---|---|---|
| `data/normalizer_review_v1.json`, `_v2.json` | 266 utdrag på högst 341 tecken, cirka 26 000 tecken | 91 920 byte | bara `tools/search_quality.py --check`, inget test |
| `tools/testdata/search_lines.json` | ord med lägen ur ett fåtal riktiga rader, cirka 1 100 tecken | 13 093 byte | ett tjugotal Python-tester av normaliseringen |
| `tools/testdata/search_golden.json` | 43 sökord med förväntade sidor och anteckningar, till största delen min egen text | 9 741 byte | sökfacit |
| `docs/reports/*.md`, `tools/tests/test_search_index.py` | ett tiotal korta citat | | |
| äldre `fts_test.db`, `search_test.db` och deras skript | 265 och 652 tecken i hela meningar | tre versioner i historiken | ingenting längre |

Allt finns i trädet och historiken, utom de äldre testdatabaserna som bara finns i historiken. Ingenting finns i release-APK:n.

**Alternativ:**

- **A. Behåll allt.** Korta citat, sammanlagt i storleksordningen 30 000 tecken av arkivets 55 miljoner.
- **B. Ta bort granskningsfilerna, behåll testfilerna.** Granskningsfilerna är den stora posten och behövs inte av något test. Kräver omskriven historik. `search_quality.py --check` slutar fungera utan dem.
- **C. Ta bort allt, också testfilerna.** Då måste normaliseringens tester skrivas om mot påhittade rader, och sökfacit kan inte följa med repot. Det är en dags arbete och försämrar testerna.

**Rekommendation: B.** `NOTICE.md` säger redan att äldre testdata i historiken innehåller mycket korta utdrag. Det som blir kvar är i den storleken.

## 4. Ordlistan

**Vad:** `tools/data/normalizer_lexicon_v1.txt`, 144 377 ord, ett per rad, 1 342 313 byte. Härledd ur OCR-texten, men ingen löpande text.

**Var:** trädet och historiken. Inte i APK:n.

**Alternativ:**

- **A. Behåll.** Normaliseringen och dess tester kräver den, och utan den går sökindexet inte att bygga om.
- **B. Ta bort.** Bygget av sökindexet går då inte att upprepa av någon annan, och testerna av normaliseringen måste hoppas över.

**Rekommendation: A.** En lista över ord är inte texten.

## 5. Serverlistningen

**Vad:** `data/raw_listing.json` (1 433 776 byte), varje filnamn, storlek och ändringstid i arkivets 191 FTP-kataloger. `data/https_probe.json` (128 291 byte), ETag och storlek för varje PDF.

**Var:** trädet och historiken (två versioner av listningen). Inte i APK:n.

**Alternativ:**

- **A. Behåll.** Fyra testfiler läser listningen: testerna av inventeringen, omslagen, HQ-mappningen och namnreglerna.
- **B. Ta bort.** Testerna måste då få en mindre, påhittad listning. Kräver omskriven historik.

**Rekommendation: A.** Gustav har sagt ja till att appen använder servern, och innehållet är samma katalog som FTP-servern visar för vem som helst med det publika kontot. Fråga honom gärna uttryckligen om du vill vara säker: det är den enda punkten där hans svar inte täcker exakt det som publiceras.

Samma sak gäller FTP-kontot `dmz` / `dmz`, som står i `tools/config.json` och `app/build.gradle.kts` och följer med i APK:n. Appen kan inte hämta HQ-sidor utan det.

## 6. E-postadressen i historiken

**Vad:**

| Roll | Adress | Antal |
|---|---|---|
| Författare | `Rognils <117741407+Rognils@users.noreply.github.com>` | 159 commits av 159 |
| Committer | samma | 159 commits av 159 |
| Taggare | samma | 19 annoterade taggar av 20. Den tjugonde, `search-index-2026-10-06-006-p2`, skapades av GitHub och har ingen taggare. |
| Medförfattare | `Claude Opus 5.5 <noreply@anthropic.com>` | 159 commits |

Ingen annan adress förekommer som författare, committer eller taggare, på någon gren.

Adressen står också i text: två gånger i `docs/reports/gates.md`, och i det här dokumentet.

**Var:** bara i historiken och i de två rapporterna. Inte i APK:n.

**Alternativ:**

- **A. Behåll.** Adressen blir publik och går att skörda. Ingen omskrivning.
- **B. Byt till GitHubs noreply-adress** med `git filter-repo --mailmap`. Det är möjligt: verktyget skriver om författare, committer och taggare i en körning, och behåller alla commits, grenar och taggar. Alla commit-id ändras.
- **C. Ny första commit** (`git checkout --orphan`). Historiken försvinner ur det publika repot. Löser också punkt 2 och 3, men 159 commits med motiveringar går förlorade för läsaren.

**Rekommendation: B,** i samma körning som punkt 2 och 3. Historiken är värd att visa, och en adress som har legat publik går inte att dra tillbaka.

Din noreply-adress är `117741407+Rognils@users.noreply.github.com` (konto-id 117741407). GitHub knyter commits med den adressen till ditt konto.

### Proceduren

Görs i en ny klon, aldrig i arbetskatalogen. `git filter-repo` är inte installerat i dag.

```bash
# 0. Säkerhetskopia av allt, utanför arbetskatalogen
cd ~ && git clone --mirror ~/claude/Projects/DMZ_Reader dmz-reader-fore-omskrivning.git

# 1. Verktyget
python3 -m pip install --user git-filter-repo        # version 2.47.0

# 2. En ny klon att skriva om i, med de två grenar som ska publiceras och alla taggar.
#    De äldre fasgrenarna är mergade till main och behövs inte.
git clone --no-local ~/claude/Projects/DMZ_Reader ~/dmz-reader-publik
cd ~/dmz-reader-publik
git branch release/1.0.0 origin/release/1.0.0

# 3. Reglerna
cat > ../mailmap <<'EOF'
Rognils <117741407+Rognils@users.noreply.github.com> <117741407+Rognils@users.noreply.github.com>
EOF
cat > ../ersatt.txt <<'EOF'
117741407+Rognils@users.noreply.github.com==>117741407+Rognils@users.noreply.github.com
EOF

# 4. Omskrivningen: adressen i författare, committer och taggare, adressen i filinnehåll,
#    och (om du väljer det) skärmbilderna och granskningsfilerna bort ur hela historiken
git filter-repo \
    --mailmap ../mailmap \
    --replace-text ../ersatt.txt \
    --invert-paths \
    --path docs/screenshots \
    --path data/normalizer_review_v1.json \
    --path data/normalizer_review_v2.json

# 5. Kontroll i klonen, före något lämnar datorn
git log --all --format='%ae%n%ce' | sort | uniq -c            # bara noreply-adressen
git for-each-ref refs/tags --format='%(taggeremail)' | sort | uniq -c
git log --all --oneline -S'den-gamla-adressen' | wc -l               # 0
git grep -c 'den-gamla-adressen' $(git for-each-ref --format='%(refname)' refs/heads refs/tags) | wc -l   # 0
git log --all --oneline -- docs/screenshots | wc -l           # 0, om skärmbilderna togs bort
git rev-list --all --count                                    # 159 eller något färre: commits som blir tomma tas bort
git tag | wc -l                                               # 20
tools/prepush_check.sh                                        # alla grenar och taggar
gitleaks git --log-opts="--all" .
```

Tre saker att veta om steg 4:

- **Alla commit-id ändras.** Rapporterna nämner commits med id, till exempel `645558a`. De hänvisningarna slutar stämma. De går att skriva om med `--replace-refs` och en ersättningslista, men jag föreslår att de får stå: de pekar in i det privata arkivet.
- **Commits som bara rörde skärmbilder försvinner.** Därför kan antalet bli lägre än 159.
- **`--replace-text` ändrar adressen i det här dokumentet också,** så proceduren ovan blir oläslig på den raden efteråt. Det gör ingenting: den är då redan körd.

**Till det privata repot.** Uppgiften bad om proceduren med force-push:

```bash
cd ~/dmz-reader-publik
git remote add origin https://github.com/Rognils/dmz-reader.git
git push --force origin 'refs/heads/main:refs/heads/main' 'refs/heads/release/1.0.0:refs/heads/release/1.0.0'
git push --force origin --tags

# Kontroll efteråt
git ls-remote origin | grep -v '\^{}' | sort > ../fjarr.txt
git show-ref | grep -E 'refs/(heads/(main|release/1.0.0)|tags/)' | sort > ../lokal.txt   # jämför id för id
gh api repos/Rognils/dmz-reader/commits --jq '.[0].commit.author.email'                    # noreply-adressen
gh release view search-index-2026-10-06-006-p2 --repo Rognils/dmz-reader                   # releasen finns kvar på sin tagg
```

**Men force-push räcker inte, och jag rekommenderar en annan väg.** GitHub behåller de gamla commits som lösa objekt efter en force-push. De nås inte från någon gren, men den som har ett gammalt commit-id kan öppna dem, och de städas bara bort av GitHubs support på begäran. När repot sedan görs publikt följer de med. Gamla id står i rapporterna.

Säkrare, och lika enkelt:

```bash
gh repo rename dmz-reader-arkiv --repo Rognils/dmz-reader    # det gamla blir ett privat arkiv
gh repo create Rognils/dmz-reader --private
cd ~/dmz-reader-publik
git remote add origin https://github.com/Rognils/dmz-reader.git
git push origin main release/1.0.0 --tags
```

Då har det nya repot aldrig innehållit den gamla historiken. Prereleasen med sökindexet skapas om i det nya repot, vilket ändå ska göras som en riktig release på publiceringsdagen.

**Efteråt, på datorn:**

```bash
git config --global user.email '117741407+Rognils@users.noreply.github.com'
```

och på GitHub under Settings → Emails: "Keep my email addresses private" och "Block command line pushes that expose my email". Annars kommer adressen tillbaka med nästa commit.

Arbetskatalogen `~/claude/Projects/DMZ_Reader` har kvar den gamla historiken. Fortsatt arbete ska ske i den nya klonen, eller i arbetskatalogen efter att den har pekats om. Det avgör vi när det är dags.

## 7. Arbetsdokumenten

**Vad:**

| | Filer | Storlek |
|---|---|---|
| `DMZ_READER_PROJEKTPLAN_V3.md` | 1 | 78 335 byte |
| `CLAUDE.md` | 1 | 2 811 byte |
| `docs/decisions/` | 24 ADR:er | 108 965 byte |
| `docs/reports/` | 387 filer, varav 47 packade testloggar | 3 289 394 byte |
| `docs/backlog-1.1.md`, `docs/signing.md`, `docs/migration.md` | 3 | |

**Var:** trädet och historiken. Inte i APK:n.

**Vad de avslöjar:** telefonmodell och system (OnePlus 7 Pro, LineageOS) i 11 filer, vilka nummer du har läst och hur länge under proven, sökvägar som `~/dmz-backup-före-…` och `~/dmz-keys/`, och signeringscertifikatets fingeravtryck. Jag har sökt igenom trädet och de packade loggarna efter telefonens serienummer, nätverksnamn, IP-adresser och ditt användarnamn på datorn. Inget av det finns där.

Fingeravtrycket är avsett att vara publikt: det är så någon annan kan kontrollera att en APK är din.

**Alternativ:**

- **A. Behåll allt.** Repot visar hur appen kom till, beslut för beslut.
- **B. Behåll plan och ADR:er, ta bort fasrapporterna.** Då försvinner uppgifterna om din läsning och din telefon. Kräver omskriven historik om de ska bort ur den också.
- **C. Ta bort allt utom README, LICENSE och NOTICE.** Ett rent kodrepo.

**Rekommendation: A.** Inget är hemligt, och ADR:erna och rapporterna är det som gör historiken begriplig. Läs gärna igenom `rc1.md` och `rc2.md` med publika ögon först: det är där din egen läsning nämns.

## 8. Raderna `Co-Authored-By`

**Vad:** `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` i alla 159 commits. GitHub visar dem som medförfattare.

**Alternativ:**

- **A. Behåll.**
- **B. Ta bort** med `git filter-repo --message-callback`, i samma omskrivning.

**Rekommendation: A.** Raderna säger hur koden kom till, och `CLAUDE.md` och rapporterna gör det ändå tydligt.

## 9. Tester som hämtar från arkivet

Det här stod inte i `gates.md`. Det kom fram i del J.

**Vad:** tre instrumenterade tester hämtar från den riktiga servern varje gång sviten körs, sammanlagt cirka 138 MB per körning. Störst är `CoverFallbackNetworkTest`, som hämtar tolv hela omslagsskanningar på tillsammans 103 MB. Siffrorna står i `docs/reports/pre-1.0.md`.

**Varför det hör hit:** i ett publikt repo kan vem som helst köra sviten, och varje körning drar 138 MB från Gustavs hemmauppkoppling.

**Alternativ:**

- **A. Publicera som det är,** och rätta i 1.1.
- **B. Stäng av de tre testerna före publicering,** så att de bara körs på begäran, som de övriga testerna mot riktig server redan gör. Det är tre rader testkod. Testerna skrivs om mot en lokal server i 1.1.

**Rekommendation: B.** Regel 12 i `CLAUDE.md` gäller från nu, och det är en liten ändring.

## 10. Sökindexet som publik fil

**Vad:** `search-2026-10-06-006-p2.db`, 90 968 064 byte. Hela arkivets text i sökbar form, med automatisk maskering av igenkända telefon- och faxnummer, e-postadresser, post- och bankgironummer och personnummerliknande mönster.

**Var:** som prerelease i det privata repot, och i `dist/`. Aldrig i git.

**Alternativ:**

- **A. Publicera p2 som release-fil.** Fulltextsökningen blir tillgänglig för alla.
- **B. Publicera inte.** Appen fungerar utan index, men utan sökning i texten. README ändras.

Det som talar emot: filen är hela textmassan, vilket upphovsrättsligt väger tyngre än omslagen, och maskeringen är inte fullständig. Uppskattningsvis några procent av telefonnumren står kvar, liksom utländska nummer, namn och postadresser.

Det som talar för: samma text ligger redan sökbar i arkivets PDF:er, och sökningen är en stor del av appens värde.

**Rekommendation: A,** med README:s nuvarande beskrivning, som inte lovar mer än filtret håller.

## 11. Upphovsrätten

Oförändrad sedan `gates.md`. Rättigheterna till tidningarna ligger hos utgivare och upphovsmän. Ingen av dem har tillfrågats. Gustav äger inte materialet, och hans svar är inget tillstånd.

Det som finns: Om-sidan, `NOTICE.md` och README säger att licensen inte omfattar materialet och att rättighetsinnehavare kan öppna ett ärende.

**Beslutet är ditt.** Jag kan inte bedöma den juridiska risken. Det jag kan säga är vad som går att dra tillbaka: en release-fil och ett omslag i en kommande version går att ta bort, men det som en gång har legat i ett publikt repo har kunnat kopieras.

---

## Checklista för publiceringsdagen

I den här ordningen. Stanna vid första avvikelse.

**Före: beslut**

1. Bestäm punkt 1–11 ovan. Skriv ned vad som valdes.
2. Kontrollera att veckans användning enligt `rc1.md` är klar och att inget nytt fel har dykt upp.

**Historiken (hoppas över om ingenting ska skrivas om)**

3. Säkerhetskopiera arbetskatalogen som spegelklon utanför den.
4. Kör omskrivningen i en ny klon, enligt punkt 6.
5. Kontrollera i klonen: bara noreply-adressen, inga spår av det borttagna, 20 taggar.
6. Byt namn på det gamla privata repot till `dmz-reader-arkiv` och skapa ett nytt, privat `dmz-reader`.
7. Ställ in `user.email` på datorn och e-postskyddet på GitHub.

**Kontroller före push**

8. `tools/prepush_check.sh` utan argument: alla grenar och taggar.
9. `gitleaks git --log-opts="--all"`: inga läckor.
10. Kontrollera att `git status` är rent och att `dist/` och `cache/` är ignorerade.
11. Pusha `main`, `release/1.0.0` och taggarna till det privata repot. Jämför `git ls-remote` med lokalt.

**Sista ändringarna**

12. Stäng av de tre testerna som hämtar från arkivet (punkt 9), om du valde det. Kör sviten på telefonen en gång.
13. Merga `release/1.0.0` till `main`.

**Publikt repo**

14. Läs igenom README, NOTICE och LICENSE på GitHub som en främling skulle se dem.
15. Kontrollera att Issues är påslaget. Det är det i dag.
16. Gör repot publikt: `gh repo edit Rognils/dmz-reader --visibility public --accept-visibility-change-consequences`.
17. Kontrollera utloggad, i ett privat webbläsarfönster, att repot syns och att `https://github.com/Rognils/dmz-reader/issues` går att öppna.

**Bygget av 1.0.0**

18. Kör JVM- och Python-testerna och lint på `main`.
19. Bygg `assembleRelease`. Kontrollera versionCode 10000, versionName 1.0.0, och certifikatets SHA-256 mot `5A:36:79:EC:…:F7:27`. Avvikelse betyder stopp.
20. Kontrollera att APK:n inte innehåller någon databas och inget sökindex.
21. Exportera din data ur appen till `~/dmz-backup-före-1.0.0/`, installera med `adb install -r`, exportera igen och jämför.
22. Tagga `v1.0.0` och pusha taggen.

**Releaserna**

23. Skapa releasen `v1.0.0` med APK:n. Skriv SHA-256, storlek och certifikatets fingeravtryck i release-texten.
24. Skapa releasen för sökindexet med `search-2026-10-06-006-p2.db` och texten från prereleasen. Markera den inte som prerelease. Ta bort prereleasen om den följde med.
25. Ladda ned båda filerna utloggad och jämför SHA-256.

**Efterkontroll**

26. Installera den nedladdade APK:n på en telefon utan appen, om en sådan finns, eller kontrollera signaturen med `apksigner verify --print-certs`.
27. Följ README:s anvisning från början: hämta sökindexet från Releases och importera det.
28. Öppna Om-sidan i appen. Tryck på GitHub-länken och på länken till ärendena. Båda ska öppnas i webbläsaren och leda rätt.
29. Kontrollera User-Agent-länken: öppna `https://github.com/Rognils/dmz-reader` utloggad. Det är adressen appen uppger för arkivet i varje förfrågan, och den ska nu leda till något Gustav kan läsa.
30. Skriv till Gustav att appen är släppt, med länken.
