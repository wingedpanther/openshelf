package io.github.wingedpanther.openshelf

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogParseTest {
    private val root = JSONObject(javaClass.getResource("/catalog.json")!!.readText())
    private val apps = root.getJSONArray("apps").let { a -> (0 until a.length()).map { Catalog.parse(a.getJSONObject(it)) } }
    private val full = apps[0]
    private val sparse = apps[1]

    @Test fun parsesEveryApp() {
        assertEquals(listOf("acme-player", "acme-launcher"), apps.map { it.id })
    }

    @Test fun requiredFields() {
        assertEquals("acme/player", full.repo)
        assertEquals("Acme Player", full.name)
        assertEquals("org.acme.player", full.pkg)
        assertEquals(42L, full.versionCode)
        assertEquals("1.4.2", full.versionName)
        assertEquals(21, full.minSdk)
        assertEquals("Video", full.room)
        assertEquals("A simple video player", full.note)
        assertEquals("Plays local and network video", full.desc)
        assertEquals("acme", full.maker)
        assertEquals(120, full.stars)
        assertEquals("GPL-3.0", full.licence)
        assertEquals("v1.4.2", full.version)
        assertEquals("2026-10-20", full.date)
        assertEquals("aabbcc", full.certSha256)
    }

    @Test fun optionalFieldsPresent() {
        assertEquals("someone/player", full.forkOf)
        assertEquals(5000, full.parentStars)
        assertEquals("2026-08-01", full.added)
        assertEquals("someone/player", full.basedOn)
        assertEquals("2026-10-21", full.pushed)
        assertEquals(777, full.downloads)
        assertEquals(listOf("INTERNET", "READ_EXTERNAL_STORAGE"), full.perms)
        assertEquals(9, full.trend)
        assertEquals(1234, full.code)
        assertFalse(full.archived)
    }

    @Test fun art() {
        assertEquals("https://example.com/banner.png", full.art.banner)
        assertNull(full.art.feature)
        assertEquals("https://example.com/icon.png", full.art.icon)
        assertEquals(listOf("https://example.com/1.png", "https://example.com/2.png"), full.art.shots)
    }

    @Test fun apksKeepTheirAbiAndAnyIsWorkedOutFromTheName() {
        assertEquals(
            listOf(
                Apk("player-arm64-v8a.apk", "arm64-v8a", "https://example.com/a64.apk", 1000),
                Apk("player-armeabi-v7a.apk", "armeabi-v7a", "https://example.com/a32.apk", 900),
                Apk("player-x86_64.apk", "x86_64", "https://example.com/x64.apk", 1100),
            ),
            full.apks,
        )
        assertEquals(listOf(Apk("launcher-universal.apk", "universal", "https://example.com/l.apk", 500)), sparse.apks)
    }

    @Test fun releases() {
        assertEquals(listOf(Release("v1.4.2", "2026-10-20"), Release("v1.4.1", "2026-09-30")), full.releases)
    }

    @Test fun missingOptionalFieldsFallBackToDefaults() {
        assertNull(sparse.versionName)
        assertNull(sparse.minSdk)
        assertNull(sparse.licence)
        assertNull(sparse.certSha256)
        assertNull(sparse.forkOf)
        assertNull(sparse.added)
        assertNull(sparse.basedOn)
        assertNull(sparse.pushed)
        assertNull(sparse.code)
        assertEquals("", sparse.desc)
        assertEquals(0, sparse.parentStars)
        assertEquals(0, sparse.downloads)
        assertEquals(0, sparse.trend)
        assertFalse(sparse.archived)
        assertEquals(emptyList<String>(), sparse.perms)
        assertEquals(AppArt(null, null, null, emptyList()), sparse.art)
    }

    @Test fun streamsFlagComesFromTheTextFields() {
        assertFalse(full.streams)
        assertTrue(sparse.streams)
    }
}
