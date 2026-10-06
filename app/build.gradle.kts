plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

android {
    namespace = "se.c64.dmzreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "se.c64.dmzreader"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

// Inventory i assets: kopieras från data/ före varje bygge, så att appens
// baseline alltid är den validerade filen från toolchainen. Kopian committas inte.
val inventorySource = rootProject.layout.projectDirectory.file("data/archive_inventory.json")

// En egen kontroll: en Copy-uppgift utan källfil hoppas bara över, och då skulle
// en gammal kopia i assets kunna följa med i bygget.
val checkInventory = tasks.register("checkInventory") {
    description = "Stoppar bygget om data/archive_inventory.json saknas."
    group = "verification"
    val source = inventorySource.asFile
    doLast {
        if (!source.isFile) {
            throw GradleException(
                "data/archive_inventory.json saknas. Kör: python3 tools/archive_inventory.py --offline"
            )
        }
    }
}
val syncInventory = tasks.register<Copy>("syncInventory") {
    description = "Kopierar data/archive_inventory.json till appens assets."
    group = "build"
    dependsOn(checkInventory)
    from(inventorySource)
    into(layout.projectDirectory.dir("src/main/assets"))
}
// Omslagsminiatyrerna från tools/build_covers.py följer med i appen (ADR 0016).
// Sync tar också bort miniatyrer som inte längre finns i data/covers/.
val syncCovers = tasks.register<Sync>("syncCovers") {
    description = "Kopierar data/covers/ till appens assets."
    group = "build"
    from(rootProject.layout.projectDirectory.dir("data/covers")) { include("*.webp") }
    into(layout.projectDirectory.dir("src/main/assets/covers"))
}
tasks.named("preBuild") { dependsOn(syncInventory, syncCovers) }

// JVM-tester: Android-varianten av sqlite-bundled innehåller bara SQLite för
// telefonens processorer. Datorns SQLite hämtas ur JVM-varianten av samma
// version och läggs på java.library.path, så att testerna kör samma kod som appen.
val hostSqlite: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val extractHostSqlite = tasks.register<Sync>("extractHostSqlite") {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val platform = when {
        osName.contains("linux") && osArch in setOf("aarch64", "arm64") -> "linux_arm64"
        osName.contains("linux") -> "linux_x64"
        osName.contains("mac") -> "osx_arm64"
        else -> "windows_x64"
    }
    from({ hostSqlite.map { zipTree(it) } }) {
        include("natives/$platform/*")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("host-sqlite"))
}
tasks.withType<Test>().configureEach {
    dependsOn(extractHostSqlite)
    val nativeDir = layout.buildDirectory.dir("host-sqlite")
    val inventory = inventorySource.asFile
    val ftsTestDb = layout.projectDirectory.file("src/debug/assets/fts_test.db").asFile
    inputs.file(inventory)
    inputs.file(ftsTestDb)
    doFirst {
        systemProperty("java.library.path", nativeDir.get().asFile.absolutePath)
    }
    systemProperty("dmz.inventoryPath", inventory.absolutePath)
    systemProperty("dmz.ftsTestDb", ftsTestDb.absolutePath)
    val schemaDir = layout.projectDirectory.dir("schemas/se.c64.dmzreader.data.db.DmzDatabase").asFile
    inputs.dir(schemaDir)
    systemProperty("dmz.schemaDir", schemaDir.absolutePath)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room3.runtime)
    ksp(libs.room3.compiler)
    implementation(libs.sqlite.bundled)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    hostSqlite(libs.sqlite.bundled.jvm)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
