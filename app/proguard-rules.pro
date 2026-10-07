# DMZ Reader – regler för R8 utöver dem som biblioteken själva för med sig.
# Room 3, kotlinx.serialization, Coil och OkHttp har egna regler i sina paket.

# Radnummer i stackspår, så att en krasch i release-varianten går att läsa.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
