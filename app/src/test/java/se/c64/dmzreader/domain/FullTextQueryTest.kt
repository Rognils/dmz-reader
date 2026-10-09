package se.c64.dmzreader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.c64.dmzreader.data.search.SearchDb
import se.c64.dmzreader.testing.TestFiles
import kotlin.random.Random

/** Frågelagret: det användaren skriver blir ett FTS5-uttryck som aldrig ger syntaxfel. */
class FullTextQueryTest {
    private fun match(text: String) = parseFullTextQuery(text)?.match

    @Test
    fun `each word is quoted and several words mean AND`() {
        assertEquals("\"högtalare\"", match("högtalare"))
        assertEquals("\"amiga\" AND \"joystick\"", match("amiga joystick"))
        assertEquals("\"Amiga\" AND \"joystick\"", match("  Amiga   joystick  "))
    }

    @Test
    fun `quotes from the user give a phrase`() {
        assertEquals("\"Commodore 128\"", match("\"Commodore 128\""))
        assertEquals("\"Commodore 128\" AND \"basic\"", match("\"Commodore 128\" basic"))
        assertEquals("\"basic\" AND \"Commodore 128\"", match("basic \"Commodore 128\""))
        // Typografiska citattecken från tangentbordet räknas också.
        assertEquals("\"Commodore 128\"", match("”Commodore 128”"))
        assertEquals("\"Sju tips\"", match("“Sju tips”"))
    }

    @Test
    fun `an unclosed quote makes the rest a phrase`() {
        assertEquals("\"Commodore 128\"", match("\"Commodore 128"))
        assertEquals("\"amiga\" AND \"sju tips\"", match("amiga \"sju tips"))
    }

    @Test
    fun `prefix needs an explicit star and three characters`() {
        assertEquals("\"amig\"*", match("amig*"))
        assertEquals("\"hög\"*", match("hög*"))
        assertEquals("\"am\"", match("am*"))
        assertEquals("\"a\" AND \"spel\"", match("a* spel"))
        assertEquals("\"amig\"* AND \"spel\"", match("amig* spel"))
        // Utan stjärna görs ingen prefixsökning.
        assertEquals("\"amig\"", match("amig"))
        // En stjärna mitt i ett ord eller efter skiljetecken ger ingen prefixsökning.
        assertEquals("\"cd-ro\"", match("cd-ro*"))
        assertEquals("\"am*ga\"", match("am*ga"))
        assertEquals("\"amig\"*", match("amig***"))
    }

    @Test
    fun `C64, C-64 and C 64 are searched as alternatives`() {
        val expected = "(\"C64\" OR \"C 64\")"
        assertEquals(expected, match("C64"))
        assertEquals(expected, match("C-64"))
        assertEquals(expected, match("C 64"))
        assertEquals(expected, match("\"C 64\""))
        assertEquals(expected, match("\"C-64\""))
        assertEquals("(\"c64\" OR \"c 64\")", match("c64"))
        assertEquals("(\"A1200\" OR \"A 1200\")", match("A1200"))
        assertEquals("(\"VIC20\" OR \"VIC 20\")", match("VIC-20"))
        assertEquals("(\"C64\" OR \"C 64\") AND \"joystick\"", match("C64 joystick"))
        assertEquals("\"spel\" AND (\"C64\" OR \"C 64\")", match("spel C 64"))
        // Ett vanligt ord följt av ett tal är två sökord, inte en modellbeteckning.
        assertEquals("\"sidan\" AND \"12\"", match("sidan 12"))
        assertEquals("\"1989\"", match("1989"))
        assertEquals("\"Commodore 128\"", match("\"Commodore 128\""))
    }

    @Test
    fun `FTS5 syntax in the text is made harmless`() {
        assertEquals("\"amiga\" AND \"AND\" AND \"atari\"", match("amiga AND atari"))
        assertEquals("\"amiga\" AND \"OR\" AND \"NOT\" AND \"atari\"", match("amiga OR NOT atari"))
        assertEquals("\"NEAR(amiga\" AND \"atari)\"", match("NEAR(amiga atari)"))
        assertEquals("\"(amiga)\"", match("(amiga)"))
        assertEquals("\"searchText:amiga\"", match("searchText:amiga"))
        assertEquals("\"-amiga\"", match("-amiga"))
        assertEquals("\"^amiga\"", match("^amiga"))
        assertEquals("\"amiga+atari\"", match("amiga+atari"))
        assertEquals("\"64:an\"", match("64:an"))
        assertEquals("\"it's\"", match("it's"))
    }

    @Test
    fun `control characters and soft hyphens cannot break the expression`() {
        // Slumptestet hittade det här: ett NUL-tecken klippte av uttrycket mitt i ett citattecken.
        assertEquals("\"amiga\" AND \"atari\"", match("amiga\u0000atari"))
        assertEquals("\"amiga\" AND \"atari\"", match("amiga\natari\t"))
        assertEquals("\"högtalare\"", match("hög\u00adtalare"))
        assertNull(parseFullTextQuery("\u0000\u0000\u0000"))
    }

    @Test
    fun `empty text and garbage give no query`() {
        for (text in listOf("", " ", "a", " a ", "*", "**", "\"\"", "\" \"", "---", "( )", "\"", "+ - ^", "…", "\"*\"")) {
            assertNull("[$text]", parseFullTextQuery(text))
        }
        // Skiljetecken bredvid ett riktigt ord faller bort.
        assertEquals("\"amiga\"", match("amiga -- ()"))
        assertEquals("\"amiga\"", match("\"\" amiga"))
    }

    @Test
    fun `display text is what the user searched for`() {
        assertEquals("högtalare", parseFullTextQuery("  högtalare ")!!.display)
        assertEquals("\"Commodore 128\" basic", parseFullTextQuery("”Commodore 128”   basic")!!.display)
        assertEquals("amig*", parseFullTextQuery("amig*")!!.display)
    }

    @Test
    fun `the queries give the right pages in a real index`() {
        SearchDb.openReadOnly(TestFiles.searchTestDb.path).use { db ->
            val pages = db.pageIndex().associateBy { it.rowId }
            fun hits(text: String) = db.rank(parseFullTextQuery(text)!!.match)
                .map { pages.getValue(it.rowId) }.map { "${it.issueId.takeLast(7)}:${it.pageNumber}" }.sorted()

            assertEquals(listOf("1995-14:26", "1995-14:27", "1995-14:29", "1999-01:3"), hits("högtalare"))
            assertEquals(listOf("1995-14:26", "1995-14:27", "1995-14:28", "1995-14:29", "1999-01:3"), hits("högtal*"))
            assertEquals(listOf("1986-01:4"), hits("\"Commodore 128\""))
            assertEquals(listOf("1986-01:4"), hits("commodore 128"))
            assertEquals(emptyList<String>(), hits("\"128 Commodore\""))
            // C64 (s. 46), C 64 (s. 47) och C-64 (1986 nr 1 s. 25) hittas med alla tre skrivsätten.
            val c64 = listOf("1986-01:25", "1989-06:46", "1989-06:47")
            assertEquals(c64, hits("C64"))
            assertEquals(c64, hits("c-64"))
            assertEquals(c64, hits("C 64"))
            assertEquals(listOf("1995-14:27", "1999-01:3"), hits("Amiga högtalare"))
            assertEquals(listOf("1995-14:28"), hits("Amiga-ägare"))
            // å är en egen bokstav.
            assertEquals(emptyList<String>(), hits("hogtalare"))
            assertEquals(listOf("1995-14:27", "1999-01:3"), hits("HÖGTALARE amiga"))
        }
    }

    @Test
    fun `no input can make FTS5 fail`() {
        val alphabet = "ab cåö64\"\"“”*()-+^:.,;'`~{}[]<>|\\/!?%&=#@\t\n NEAR AND OR NOT 128 \u0000­…"
        val words = listOf("AND", "OR", "NOT", "NEAR", "NEAR(", "*", "\"", "(", ")", "a*", "amig*", "C-64", "c 64", "\"\"", "^", ":", "-", "+")
        val random = Random(9)
        SearchDb.openReadOnly(TestFiles.searchTestDb.path).use { db ->
            var searched = 0
            repeat(4000) { round ->
                val text = if (round % 2 == 0) {
                    (0 until random.nextInt(1, 14)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
                } else {
                    (0 until random.nextInt(1, 6)).joinToString(if (random.nextBoolean()) " " else "") { words[random.nextInt(words.size)] }
                }
                val query = parseFullTextQuery(text) ?: return@repeat
                // Får inte kasta. Ett syntaxfel i FTS5 ger ett undantag här.
                try {
                    val rows = db.rank(query.match)
                    rows.firstOrNull()?.let { db.snippet(it.rowId, query.match) }
                } catch (e: Exception) {
                    throw AssertionError("Söktexten [$text] gav uttrycket [${query.match}], som FTS5 inte godtar: $e", e)
                }
                searched++
            }
            assertTrue("bara $searched av 4000 texter gav en fråga", searched > 2000)
            assertNotNull(parseFullTextQuery("amiga"))
        }
    }
}
