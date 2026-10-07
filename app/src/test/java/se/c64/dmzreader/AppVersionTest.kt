package se.c64.dmzreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Appens version följer taggarna (planens G4): 0.6.1 ger 601, 0.7.0 ger 700, 1.0.0 ger 10000. */
class AppVersionTest {
    private fun versionCodeFor(versionName: String): Int {
        val (major, minor, patch) = versionName.split(".").map { it.toInt() }
        return major * 10_000 + minor * 100 + patch
    }

    @Test
    fun `version name has three numbers and the version code is derived from it`() {
        assertTrue(BuildConfig.VERSION_NAME, Regex("""\d+\.\d+\.\d+""").matches(BuildConfig.VERSION_NAME))
        assertEquals(versionCodeFor(BuildConfig.VERSION_NAME), BuildConfig.VERSION_CODE)
    }

    @Test
    fun `the rule for version codes`() {
        assertEquals(601, versionCodeFor("0.6.1"))
        assertEquals(700, versionCodeFor("0.7.0"))
        assertEquals(10_000, versionCodeFor("1.0.0"))
    }
}
