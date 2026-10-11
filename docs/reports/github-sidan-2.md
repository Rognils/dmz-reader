# GitHub-sidan, del 2: Gustavs namn, fristående projekt och hänget i liggande läge

2026-10-11. Fortsättning på [github-sidan.md](github-sidan.md) och [pre-1.0-del3.md](pre-1.0-del3.md). Repot är privat. Release-appen på telefonen är orörd (1.0.0-rc3, uppdaterad 2026-10-11 01:01:57). Ingenting hämtades från arkivservern. Skärmdumparna i `docs/images/` är oförändrade.

## Hänget i liggande läge

### Slutsats

**Klass (a): testproblem.** Appen hänger sig inte. Det är testmiljön som väntar i all oändlighet på att appen ska bli stilla, fast den redan är det. Utan testram gick felet inte att återskapa på 200 rotationer.

Det jag skrev i del 3, att något i läsaren kanske aldrig blir färdigt och drar batteri, stämmer inte med mätningarna. Jag skrev också i del 3 att felet inte gick att knyta till testnumret. Det var fel: skillnaden mellan testnumret och arkivfilen är tydlig.

### Vad som hände

- **Var:** bara i utvecklingsbygget (`se.c64.dmzreader.debug`) under testramen, i `HqDeviceTest.hqInLandscapeShowsSinglePage`. Aldrig i release-appen, och aldrig i utvecklingsbygget utan testram.
- **Skärm:** läsaren, 1986 nr 1, i liggande läge med uppslaget "Sida 2–3 av 28" och kontrollerna framme.
- **Steg:** testet öppnar numret, bläddrar till sida 3, begär liggande läge, väntar 1,5 s, visar kontrollerna och läser sidraden. Allt det går bra. Nästa steg är ett tryck på HQ-knappen. Före varje tryck väntar testramen på att appen ska vara i vila, och den väntan tar aldrig slut.
- **Skärmen under hänget** ser rätt ut: rätt uppslag, rätt sidrad, miniatyrerna på plats.

### Vad loggen och profilen visar

Jag körde testet i serier och sparade logg, skärmbild, bildrutestatistik och en profil av huvudtråden (3 s) vid varje häng.

| Serie, under testramen | Häng |
|---|---|
| 30 körningar med testnumret | 8 |
| 30 körningar med arkivfilen för 1986 nr 1 | 0 |

Alla åtta häng ser likadana ut:

- **Aktiviteten startar om normalt** vid rotationen (PAUSED, STOPPED, DESTROYED, ny CREATED, STARTED, RESUMED), och sidorna renderas i liggande storlek inom en halv sekund. Sedan kommer inga fler rader från läsaren.
- **Appen ritar ingenting.** 135–160 bildrutor sammanlagt under hela körningen, alltså inga nya under de 40 sekunder testet stod still.
- **Ingen appkod körs.** Profilen har noll rader med ritning, omkomposition eller kod ur `se.c64.dmzreader`. Det enda huvudtråden gör är att svara på testramens frågor om vila (`ComposeIdlingResource.isIdleNow`, `checkLayoutBusy`, `checkRecompositionBusy`).

Testramen anser alltså att något väntar, medan appen inte har något schemalagt. Vilket av testramens villkor som står kvar har jag inte kunnat läsa ut. Det mest sannolika är en väntande ommätning av layouten som inget ritpass är beställt för, men det är en gissning.

**Varför testnumret?** Jag vet inte säkert. Den tydliga skillnaden är hastigheten: testnumrets sidor renderas på 2–10 ms, arkivets på betydligt längre tid. Det talar för en kapplöpning mellan en färdig sidbild och layouten under rotationen, som bara uppstår när bilden är klar nästan omedelbart.

**En körning passar inte in.** I del 3 hängde sig det andra liggande-testet (`verifyLandscapeSpreadAndRotation`) en gång med arkivfilen. Den körningen startade direkt efter att jag hade tvångsstoppat en hängd körning i liggande läge. Jag har inte kunnat upprepa det: 30 av 30 med arkivfilen i dag, och 4 av 4 i det manuella skriptet i del 3.

### Försök att återskapa utan testram

Utvecklingsbygget startades som en vanlig app, utan testram, och roterades med `wm user-rotation`. Efter varje rotation mättes under två sekunder hur många bildrutor appen ritade och hur mycket CPU-tid huvudtråden använde, och om systemets UI-automat fick appen i vila.

| Skärm | Rotationer | Avvikelser |
|---|---|---|
| Bibliotek | 20 | 0 |
| Sök, med träffar | 20 | 0 |
| Statistik | 20 | 0 |
| Inställningar | 20 | 0 |
| Om-sidan | 20 | 0 |
| Läsaren, omslaget, kontroller framme (arkivfil) | 20 | 0 |
| Läsaren, omslaget, utan kontroller (arkivfil) | 20 | 0 |
| Läsaren, uppslag 2–3, kontroller framme (arkivfil) | 20 | 0 |
| Läsaren, uppslag 2–3, kontroller framme (testnummer) | 20 | 0 |
| Läsaren, uppslag 2–3, utan kontroller (testnummer) | 20 | 0 |

I alla 200 mätningar ritade appen 0 bildrutor i vila och använde högst 0,01 s CPU-tid på två sekunder. Processen kraschade aldrig, och appen svarade varje gång. Läsaren stod kvar på rätt sida efter rotation fram och tillbaka.

Näst sista raden är just det läge som hänger sig under testramen. Utan testram händer ingenting.

Begränsningar: sökskärmen prövades med sökning på titel och år, eftersom utvecklingsbygget inte hade något sökindex. Statistiken var tom vid provet. HQ-läget var inte påslaget under rotationerna.

### Vad det betyder

- Ingen rättning i appen föreslås. Det finns inget belägg för ett fel som en användare kan råka ut för.
- Testet står kvar som manuell kontroll mot en arkivfil, som i del 3, och kommentaren i testet beskriver nu vad som händer.
- Vill man ha tillbaka testet i den vanliga sviten finns två vägar: låta alla tester läsa en arkivfil igen, eller ändra testet så att det inte väntar på vila efter rotationen. Ingendera är gjord.

### Användarens besked under arbetet

Under utredningen kom beskedet att strunta i testnumret och köra med en riktig fil. Jag tolkade det som att utredningen skulle göras med arkivfilen, och gjorde serien och rotationsprovet med den, ur datorns cache. Jag körde ändå läget som hänger sig också med testnumret, eftersom det var det enda sättet att se om felet finns utan testram.

Om beskedet i stället gällde hela testsviten, alltså att testerna ska läsa en arkivfil igen och testnumret tas bort, så är det inte gjort. Det skulle ta bort hänget men också göra att en ren klon inte klarar sviten utan att man skaffar filen.

### Vad som gjordes på telefonen

Allt i utvecklingsbygget. Skärmrotationen låstes om under provet och är tillbaka i utgångsläget (`lock 0`, automatisk rotation av, som före). Arkivfilen och testnumret lades tillfälligt i `/data/local/tmp/` och i utvecklingsbyggets läs-cache och är borttagna, och utvecklingsbyggets data är rensad. Loggen visar noll förfrågningar till dmzarkivet.se.

## Gustavs namn

Efternamnet är borttaget ur nuvarande träd. Historiken är inte omskriven.

| Fil | Ändring |
|---|---|
| `README.md` | Avsnittet "Tack, Gustav!" har den nya texten |
| `app/src/main/assets/about_sv.md` | "…byggs och underhålls av Gustav, som lagt ner…" |
| `docs/release-notes-1.0.0.md` | "…byggs och underhålls av Gustav." |
| `docs/reports/gates.md` | Två ställen: README-utkastet och rubriken om Gustavs svar |
| `app/src/test/.../MarkdownTest.kt` | Testet kontrollerar den nya meningen och att efternamnet inte finns |

`NOTICE.md` och projektplanen innehöll inte efternamnet. Den enda kvarvarande träffen på sökningen är ordet "malmström" i normaliseringens ordlista, som är ett ord ur tidningarna och inte har med saken att göra.

Om-texten ändrades på `main`, inte bara på `release/1.0.0`, eftersom filen finns på båda grenarna och efternamnet annars hade stått kvar i trädet.

## Fristående projekt

Meningen "DMZ Reader är ett fristående projekt och inte en officiell app från DMZArkivet." står nu i README (under "Tack, Gustav!"), i Om-texten (i första stycket) och i release-texten. Release-texten var inte uppräknad i uppgiften, men den är en användartext av samma slag.

I Om-texten står meningen i första stycket och inte som ett eget, eftersom ett test kontrollerar textens uppbyggnad stycke för stycke.

## Tester

JVM-testerna: 500 av 500 på `main` och på `release/1.0.0` efter rebase.
