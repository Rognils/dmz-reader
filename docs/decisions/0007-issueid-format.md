# ADR 0007: Format för issueId

## Status
Accepted (godkänt av C64 2026-10-06, permanent)

## Context
`issueId` är den stabila identitet som kopplar användardata (`IssueUserState`, `Bookmark`, `ReadingSession`) till ett nummer. Planen (C8) kräver att formatet bygger på arkivets verkliga namngivning, att dubbelnummer och specialnummer kan uttryckas, och att ingen information i originalnamnet går förlorad.

FAS 0 visade:

- Arkivets egen nyckel är `ÅÅÅÅNN` och används likadant i alla kataloger och på webbplatsen.
- Det finns två publikationer: Datormagazin (171 nummer) och HighScore (14 nummer). HighScore numreras efter det Datormagazin-nummer den hör till, så nyckeln `199317` finns för båda.
- Tre dubbelnummer har åtta siffror (`19870405`, `19881617`, `19950506`).
- Två nummer har suffixet b (`198710b`, `198916b`) och finns vid sidan av `198710` och `198916`.

## Decision
`issueId` har formatet

```text
<publicationId>-<ÅÅÅÅ>-<NN>[-<MM>][b]
```

| Fall | Arkivets nyckel | issueId | issueLabel | sortKey |
|---|---|---|---|---|
| Vanligt nummer | `198906` | `datormagazin-1989-06` | Nr 6 | `1989-06` |
| Dubbelnummer | `19881617` | `datormagazin-1988-16-17` | Nr 16–17 | `1988-16` |
| b-utgåva | `198710b` | `datormagazin-1987-10b` | Nr 10b | `1987-10b` |
| HighScore | `HighScore-199317` | `highscore-1993-17` | Nr 17 | `1993-17` |

Regler:

- `publicationId` är `datormagazin` eller `highscore`. HighScore är en egen publikation. Det valfria fältet `relatedIssueId` pekar från ett HighScore-nummer till motsvarande Datormagazin-nummer och är `null` för alla andra.
- År har fyra siffror och nummer två, med inledande nolla.
- Åtta siffror i nyckeln tolkas som dubbelnummer bara om de två numren följer på varandra. Annars blir filen ett specialfall.
- Bara suffixet `b` godtas. Andra bokstäver blir specialfall.
- `sortKey` är `ÅÅÅÅ-NN` plus eventuellt suffix. Ett dubbelnummer sorteras på sitt första nummer. Vanlig strängsortering ger rätt ordning inom en publikation.
- `sourceKey` är arkivets nyckel så som den står i OCR-PDF:ens filnamn (`198906`, `HighScore-199317`).

## Reason
- Formatet går att läsa och går att härleda mekaniskt ur arkivets nyckel, åt båda håll.
- Publikationen måste ingå, eftersom Datormagazin och HighScore delar nyckel.
- Bindestreck mellan delarna gör dubbelnummer entydiga: `1988-16-17` kan inte förväxlas med ett nummer 1617.
- Alla 185 nummer får unika id, och alla 11 893 regelrätta filer i arkivet kopplas till ett av dem (`tools/tests/test_normalize.py`).

## Consequences
- Formatet är permanent. En ändring kräver en aliastabell och migrering av användardata (planens C8).
- Vad "b" betyder är inte känt. Numren behandlas som egna nummer och visas som "Nr 10b".
- Om arkivet får en ny sorts nummer (annat suffix, trippelnummer) blir det ett specialfall som måste få en uttrycklig regel. Det gissas inte.
- Nya publikationer (till exempel ur `Other Magazines`) kräver ett nytt `publicationId` och egna namnregler, men formatet rymmer dem.
