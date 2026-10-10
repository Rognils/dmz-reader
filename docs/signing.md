# Signering av DMZ Reader

Release-varianten (`se.c64.dmzreader`) och provvarianten (`se.c64.dmzreader.rc`) signeras med en egen nyckel. Utvecklingsbygget (`se.c64.dmzreader.debug`) signeras med Androids debug-nyckel och är en annan app på telefonen.

## Var nyckeln ligger

| Vad | Var |
|---|---|
| Nyckelfilen | `~/dmz-keys/dmz-reader-release.jks` (utanför repot, läsbar bara för ägaren) |
| Lösenorden | `~/.gradle/gradle.properties` (utanför repot, läsbar bara för ägaren) |
| Nyckelns alias | `dmz-reader` |

Nyckeln är RSA 4096 med SHA256withRSA, skapad 2026-10-10 och giltig till 2061-10-10. Filen är i formatet PKCS12, där nyckeln och filen har samma lösenord.

Certifikatets SHA-256-fingeravtryck:

```text
5A:36:79:EC:C4:CC:BF:9B:61:24:7E:35:80:B1:78:02:8F:03:E6:E4:7E:6D:6D:7F:32:5D:2A:29:54:75:F7:27
```

Raderna i `~/.gradle/gradle.properties`:

```properties
dmzReleaseStoreFile=/home/<användare>/dmz-keys/dmz-reader-release.jks
dmzReleaseStorePassword=…
dmzReleaseKeyAlias=dmz-reader
dmzReleaseKeyPassword=…
```

`app/build.gradle.kts` läser dem med `providers.gradleProperty`. Saknas de byggs release och rc osignerade, och en osignerad APK går inte att installera.

## Det här får aldrig hamna i repot

Nyckelfilen och lösenorden ligger utanför projektkatalogen, så git ser dem inte. Som extra spärr ignorerar `.gitignore` filer som slutar på `.jks`, `.keystore` och `.p12` samt `keystore.properties` och `signing.properties`.

Kontrollera vid behov:

```bash
git ls-files | grep -Ei '\.(jks|keystore|p12)$|gradle\.properties'   # ska bara visa projektets egen gradle.properties
git grep -n "dmzReleaseStorePassword" -- . ':!docs' ':!app/build.gradle.kts'   # ska inte ge något
```

Projektets egen `gradle.properties` i repots rot innehåller bara bygginställningar och inga lösenord.

## Bygga

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2 ANDROID_HOME=~/Android/Sdk
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk   (se.c64.dmzreader)
./gradlew :app:assembleRc          # app/build/outputs/apk/rc/app-rc.apk             (se.c64.dmzreader.rc)
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk       (se.c64.dmzreader.debug)
```

Kontrollera signaturen:

```bash
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

SHA-256 för certifikatet ska vara fingeravtrycket ovan (utan kolon, med små bokstäver).

De tre varianterna:

| Variant | App-ID | Namn | Kod | Signering |
|---|---|---|---|---|
| release | `se.c64.dmzreader` | DMZ Reader | krympt med R8 | release-nyckeln |
| rc | `se.c64.dmzreader.rc` | DMZ Reader RC | samma som release | release-nyckeln |
| debug | `se.c64.dmzreader.debug` | DMZ Reader DEV | okrympt, felsökningsbar | debug-nyckeln |

De kan ligga bredvid varandra på telefonen och delar ingen data. Testerna körs i debug.

## Säkerhetskopiera nyckeln

Nyckelfilen och lösenorden ska finnas på en plats utanför datorn. Det behövs två saker: filen `dmz-reader-release.jks` och lösenordet ur `~/.gradle/gradle.properties`. Den ena är oanvändbar utan den andra.

**2026-10-10:** nyckeln och lösenorden är säkerhetskopierade utanför datorn av ägaren, och kopian är kontrollerad med `sha256sum`. Platsen anges inte här.

Gör om kopian om nyckeln eller lösenordet någon gång byts.

## Om nyckeln tappas bort

Android tar bara emot en uppdatering av en app om den är signerad med samma nyckel som den installerade. Utan nyckeln (eller utan lösenordet) går det inte att bygga en ny version som kan installeras ovanpå den gamla.

Det som då återstår:

1. Exportera en säkerhetskopia i appen (Inställningar → Säkerhetskopia → Exportera) medan den gamla appen finns kvar.
2. Skapa en ny nyckel och bygg appen med den.
3. Avinstallera den gamla appen. Då försvinner all dess data på telefonen.
4. Installera den nya och importera säkerhetskopian och sökindexet, enligt `docs/migration.md`.

Favoriter, bokmärken, läsdata och sessioner följer med i säkerhetskopian. Nedladdade nummer, läs-cachen, HQ-cachen och det importerade sökindexet gör det inte, och får hämtas respektive importeras på nytt.

Ingenting går alltså förlorat för gott så länge en säkerhetskopia kan exporteras, men flytten måste göras om för hand. Nyckeln skyddar inte någon hemlighet i appen. Den avgör bara vem som kan ge ut uppdateringar som telefonen godtar.

## Om nyckeln kommer på avvägar

Den som har nyckeln och lösenordet kan bygga en app som telefonen godtar som en uppdatering av DMZ Reader. Appen sprids inte, så risken är liten, men byt i så fall nyckel enligt stegen ovan.
