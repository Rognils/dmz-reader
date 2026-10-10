package se.c64.dmzreader.data.network

/**
 * Texten när arkivservern inte svarar alls: anslutningen vägras, tiden går ut eller
 * namnet går inte att slå upp. Den används för både PDF och HQ.
 */
const val ARCHIVE_UNREACHABLE_MESSAGE =
    "Det gick inte att nå DMZArkivet. Kontrollera din internetanslutning och försök igen. " +
        "Arkivet körs på en privat server och kan ibland vara tillfälligt otillgängligt."
