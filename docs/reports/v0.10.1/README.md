# v0.10.1 – rättningar efter FAS 10

> **Skärmbilderna som nämns här finns inte i repot.** De ligger lokalt hos projektets ägare, se [docs/skarmbilder.md](../../skarmbilder.md).

Datum: 2026-10-09 · På `main` · App 0.10.1 (versionCode 1001)

1. **Sessioner delas vid lokal midnatt.** En pågående session avslutas 23:59:59 och en ny börjar 00:00 på samma nummer och sida. JVM-testet läser söndag 23:50 till måndag 00:20 och får 10 minuter i den ena veckan och 20 i den andra. Äldre sessioner rörs inte. Planens C6 och E12 och ADR 0021 är uppdaterade.
2. **Årgångsraden** i Statistik skriver "N % sidor".
3. **Mest lästa årgång** visar alla årgångar med lika många sidor: "Datormagazin 1988, 1991 · 2 sidor". Värdet står på egen rad under etiketten, så att det får plats.

## Tester

| Test | Resultat |
|---|---|
| JVM | 399 av 399 (10 nya) |
| Python | 172 av 172 |
| Lint | 0 fel, 8 varningar |
| Instrumenterad svit, körning 1 | 51 av 52: `ReaderDeviceTest.readDownloadTurnPageAndClose` föll |
| Instrumenterad svit, körning 2 | 52 av 52 |
| Omstartstestet och tvångsstoppet | godkända i båda körningarna |

Testet som föll i körning 1 hämtar 1986 nr 1 från arkivet. Felmeddelandet skrevs inte ut av skriptet, så orsaken är inte belagd. Testet gick igenom ensamt direkt efteråt och i körning 2. `tools/device_test.sh` skriver numera ut felmeddelandet.

## Användarens data

Fingeravtrycken före och efter är identiska i båda körningarna, och körning 1 före är identisk med körning 2 efter. Filerna ligger i den här katalogen.

Skärmbilder med testdata i isolerad lagring: `docs/screenshots/fas10/06-v0.10.1-procent-sidor.png` och `07-v0.10.1-ovrigt.png`.
