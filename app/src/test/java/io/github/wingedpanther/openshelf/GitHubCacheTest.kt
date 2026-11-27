package io.github.wingedpanther.openshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GitHubCacheTest {
    @Test fun savedNotesComeBackAfterARestart() {
        GitHub.use(Files.createTempDirectory("releases").toFile())
        val info = ReleaseInfo("v2.1", "Fixes \"quotes\" and\nnew lines", listOf(Apk("a-arm64.apk", "arm64-v8a", "https://example.com/a.apk", 1234)), "2026-11-20")
        GitHub.keep("o/a@v2.1", info)
        assertEquals(info, GitHub.load("o/a@v2.1"))
        assertNull(GitHub.load("o/a@v2.2"))
    }

    @Test fun apiCallsStopWhileTheLimitIsUsedUp() {
        GitHub.limitedUntil = System.currentTimeMillis() + 60_000
        try {
            val thrown = runCatching { fetchText("${GitHub.API}repos/o/a/releases/latest") }.exceptionOrNull()
            assertTrue(thrown is RateLimited)
        } finally {
            GitHub.limitedUntil = 0L
        }
    }
}
