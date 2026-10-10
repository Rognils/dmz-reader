# DMZ Reader – regler för R8 utöver dem som biblioteken själva för med sig.
# Room 3, kotlinx.serialization, Coil och OkHttp har egna regler i sina paket.

# Radnummer i stackspår, så att en krasch i release-varianten går att läsa.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Uttryckliga regler för det som nås med reflektion eller från maskinkod.
# Biblioteken har egna regler, men de här delarna får inte gå sönder om de
# reglerna ändras: en krympt app som inte kan öppna sin databas eller läsa en
# säkerhetskopia märks först hos användaren.
# ---------------------------------------------------------------------------

# Room 3: databasen skapas genom den genererade klassen DmzDatabase_Impl, och
# DAO-implementationerna hittas på samma sätt.
-keep class * extends androidx.room3.RoomDatabase { <init>(...); }
-keep class se.c64.dmzreader.data.db.**_Impl { *; }
-keep class se.c64.dmzreader.data.db.** { *; }

# SQLite som följer med appen (sqlite-bundled): maskinkoden anropar klasserna vid namn (JNI).
-keep class androidx.sqlite.driver.bundled.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# kotlinx.serialization: arkivindex och säkerhetskopior läses med genererade serialiserare.
# Fältnamnen är filformatet och får inte bytas.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-keep,includedescriptorclasses class se.c64.dmzreader.**$$serializer { *; }
-keepclassmembers class se.c64.dmzreader.** {
    *** Companion;
}
-keepclasseswithmembers class se.c64.dmzreader.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class se.c64.dmzreader.** { *; }

# Apache Commons Net (HQ via FTP): svarstolkar och hjälpklasser skapas med reflektion ur namn.
-keep class org.apache.commons.net.ftp.** { *; }
-keep class org.apache.commons.net.io.** { *; }
-keep class org.apache.commons.net.SocketClient { *; }
-dontwarn org.apache.commons.net.**
