# ADR 0001: Native Android med Kotlin och Compose, och valda versioner

## Status
Accepted

## Context
DMZ Reader är en privat läsapp för en enda användare och en enda telefon (planens A1 och A2). Den ska rendera stora PDF:er, ladda ned filer i bakgrunden och söka i ett lokalt FTS5-index. Planen (A5) pekar ut Kotlin, Jetpack Compose och Room 3.

FAS 1 lägger grunden, och då måste hela verktygskedjan bestämmas. Versionerna hänger ihop: AGP bestämmer Gradle, JDK och högsta API-nivå, och Room 3 kräver KSP.

## Decision
Appen byggs som en native Android-app i Kotlin med Jetpack Compose, i en enda modul (`app`), med `applicationId` `se.c64.dmzreader` och `minSdk` 26.

Versioner, valda 2026-10-06 som senaste stabila. Alla står i `gradle/libs.versions.toml`.

| Del | Version | Källa |
|---|---|---|
| Android Gradle Plugin | 9.4.1 | Googles Maven-arkiv, `com.android.tools.build:gradle` (senaste utan alpha/beta/rc) |
| Gradle | 9.6.0 | AGP 9.4 release notes, kompatibilitetstabellen: lägsta och förvald version 9.6.0 |
| JDK | 17 (Temurin 17.0.20) | AGP 9.4 release notes: lägsta och förvald version 17 |
| compileSdk och targetSdk | 37 | AGP 9.4 release notes: "The maximum API level that Android Gradle plugin 9.4 supports is API level 37" |
| SDK Build Tools | 36.0.0, anges inte i bygget | AGP 9.4:s förval. AGP hämtar dem själv. |
| Kotlin | 2.4.20 | Maven Central, `org.jetbrains.kotlin:kotlin-gradle-plugin` |
| KSP | 2.3.12 | Maven Central och KSP:s releaser på GitHub. Stöd för Kotlin 2.4 finns från 2.3.10. |
| Room 3 | 3.0.3 | Googles Maven-arkiv, `androidx.room3`, och Room 3 release notes |
| sqlite-bundled | 2.7.1 | Googles Maven-arkiv, `androidx.sqlite`. Samma version som Room 3.0.3 själv beror på. Innehåller SQLite 3.50.1. |
| Compose BOM | 2026.09.00 | Googles Maven-arkiv, `androidx.compose:compose-bom` (Compose UI 1.12.1, Material 3 1.4.0) |
| DataStore | 1.2.1 | Googles Maven-arkiv, `androidx.datastore` |
| Navigation Compose | 2.10.2 | Googles Maven-arkiv, `androidx.navigation` |
| kotlinx.serialization | 1.11.0 | Maven Central |
| kotlinx.coroutines | 1.11.0 | Maven Central |
| activity-compose / lifecycle / core-ktx | 1.13.0 / 2.11.0 / 1.19.1 | Googles Maven-arkiv |

Övrigt:

- AGP 9 har inbyggt Kotlin-stöd, så `org.jetbrains.kotlin.android` används inte. Kotlin-versionen sätts av Compose- och serialiseringspluginerna (2.4.20). AGP 9.4.1 är själv byggt mot Kotlin 2.2.10.
- Room 3 används enligt sina egna release notes: paketet `androidx.room3`, KSP, en `SQLiteDriver`, och alla DAO-funktioner är `suspend` eller returnerar `Flow`. Inga SupportSQLite-API:er. Typkonverterare heter `@ColumnTypeConverter` och `@ColumnTypeConverters` i Room 3.
- Ingen DI-ram. Beroendena skapas i `AppContainer`.

## Reason
- Native ger direkt tillgång till `PdfRenderer`, WorkManager och app-specifik lagring, som läsaren och offline-läget bygger på.
- En modul räcker för en app av den här storleken och håller bygget enkelt.
- Senaste stabila versioner väljs en gång, i början, så att projektet inte börjar med teknisk skuld. Gradle följer AGP:s dokumenterade krav och inte "senaste" (9.8.0 finns), eftersom AGP bara är testat mot den version dess release notes anger.

## Consequences
- Appen finns bara för Android.
- API 37 som `targetSdk` ger kant-till-kant-layout som standard. Appen använder `enableEdgeToEdge()`.
- Room 3 är nytt (stabil sedan juli 2026). Exempel på nätet gäller ofta Room 2 och stämmer inte.
- Lint varnar för att en nyare Gradle finns. Det är väntat och ska inte åtgärdas utan att AGP samtidigt uppdateras.
- Uppgradering görs samlat: AGP först, sedan det dess release notes kräver.
- Utvecklingsmiljön ligger i hemkatalogen: JDK i `~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2`, Android SDK i `~/Android/Sdk`, `sdk.dir` i `local.properties` (versionshanteras inte).
