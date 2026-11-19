package com.lyricsadapter.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    private fun version(text: String) = AppVersion.parse(text) ?: error("$text did not parse")

    @Test
    fun `reads a version with or without the tag's v`() {
        assertEquals(AppVersion(0, 1, 2, null), version("0.1.2"))
        assertEquals(AppVersion(0, 1, 2, null), version("v0.1.2"))
        assertEquals(AppVersion(1, 0, 0, "beta.1"), version("v1.0.0-beta.1"))
    }

    @Test
    fun `refuses what is not a version`() {
        listOf("", "v", "1.2", "1.2.3.4", "1.2.x", "-1.2.3", "1.2.3-", "latest", "v1.2.3 ", "1.2.3-béta").forEach { text ->
            assertNull(AppVersion.parse(text), text)
        }
    }

    @Test
    fun `refuses numbers too large to hold`() {
        assertNull(AppVersion.parse("99999999999.0.0"))
    }

    @Test
    fun `orders by number, not by text`() {
        assertTrue(version("0.1.10") > version("0.1.9"))
        assertTrue(version("0.2.0") > version("0.1.99"))
        assertTrue(version("1.0.0") > version("0.99.99"))
        assertEquals(0, version("v0.1.2").compareTo(version("0.1.2")))
    }

    @Test
    fun `a pre-release comes before the version it leads to`() {
        assertTrue(version("1.0.0") > version("1.0.0-beta.2"))
        assertTrue(version("1.0.0-beta.2") > version("1.0.0-beta.1"))
        assertTrue(version("1.0.0-beta.10") > version("1.0.0-beta.9"))
        assertTrue(version("1.0.0-beta.1") > version("1.0.0-alpha.5"))
        assertTrue(version("1.0.0-beta.1") > version("1.0.0-beta"))
        assertTrue(version("1.0.0-rc") > version("1.0.0-1"))
        assertTrue(version("1.0.0-beta.1") > version("0.9.9"))
    }

    @Test
    fun `text is the version as the app reports it`() {
        assertEquals("0.1.2", version("v0.1.2").toString())
        assertEquals("1.0.0-beta.1", version("1.0.0-beta.1").toString())
    }

    /** Desktop's rule (`electron/updater.ts`): versions under 1.0 and suffixed ones follow pre-releases. */
    @Test
    fun `versions under one and suffixed versions take pre-releases`() {
        assertTrue(version("0.1.2").takesPreReleases)
        assertTrue(version("1.0.0-beta.1").takesPreReleases)
        assertFalse(version("1.0.0").takesPreReleases)
        assertFalse(version("2.3.4").takesPreReleases)
    }
}
