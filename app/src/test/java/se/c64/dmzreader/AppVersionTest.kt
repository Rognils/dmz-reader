package se.c64.dmzreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Appens version följer taggarna (planens G4): 0.6.1 ger 601, 0.7.0 ger 700, 1.0.0 ger 10000.
 * En releasekandidat ligger strax under sin version, så att den färdiga versionen kan
 * installeras ovanpå: 1.0.0-rc1 ger 9901, 1.0.0-rc2 ger 9902 och 1.0.0-rc3 ger 9903.
 */
class AppVersionTest {
    private fun versionCodeFor(versionName: String): Int {
        val candidate = versionName.substringAfter("-rc", "").toIntOrNull()
        val (major, minor, patch) = versionName.substringBefore("-").split(".").map { it.toInt() }
        val final = major * 10_000 + minor * 100 + patch
        return if (candidate == null) final else final - 100 + candidate
    }

    @Test
    fun `version name has three numbers and the version code is derived from it`() {
        assertTrue(BuildConfig.VERSION_NAME, Regex("""\d+\.\d+\.\d+(-rc[1-9]\d?)?""").matches(BuildConfig.VERSION_NAME))
        assertEquals(versionCodeFor(BuildConfig.VERSION_NAME), BuildConfig.VERSION_CODE)
    }

    @Test
    fun `the rule for version codes`() {
        assertEquals(601, versionCodeFor("0.6.1"))
        assertEquals(700, versionCodeFor("0.7.0"))
        assertEquals(900, versionCodeFor("0.9.0"))
        assertEquals(1000, versionCodeFor("0.10.0"))
        assertEquals(1001, versionCodeFor("0.10.1"))
        assertEquals(1100, versionCodeFor("0.11.0"))
        assertEquals(10_000, versionCodeFor("1.0.0"))
        assertEquals(9_901, versionCodeFor("1.0.0-rc1"))
        assertEquals(9_902, versionCodeFor("1.0.0-rc2"))
        assertEquals(9_903, versionCodeFor("1.0.0-rc3"))
        // Kandidaterna ligger över 0.11.0 och under den färdiga versionen.
        assertTrue(versionCodeFor("0.11.0") < versionCodeFor("1.0.0-rc1"))
        assertTrue(versionCodeFor("1.0.0-rc9") < versionCodeFor("1.0.0"))
    }
}
