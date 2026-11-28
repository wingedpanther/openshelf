package io.github.wingedpanther.openshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListedAsTest {
    private fun app(id: String, name: String, pkg: String, stars: Int) = App(
        id = id, repo = "o/$id", name = name, pkg = pkg, versionCode = 1, versionName = null, minSdk = null,
        room = "", note = "", desc = "", maker = "", stars = stars, licence = null, archived = false, streams = false,
        version = "1", date = "2026-11-28", certSha256 = null, art = AppArt(null, null, null, emptyList()),
        apks = emptyList(), releases = emptyList(), forkOf = null, parentStars = 0,
    )

    private val apps = listOf(
        app("v2", "v2rayNG", "com.v2ray.ang.fdroid", 66_000),
        app("fit", "Fitness", "cn.surkaa.fitness", 900),
        app("gh", "Notes", "io.github.alice.notes", 500),
        app("tv", "TV", "com.tv.app", 900),
    )

    @Test fun sameNameFromTheSameDeveloperMatches() {
        assertEquals("v2", listedAs("V2Ray NG", "com.v2ray.ang", apps)?.id)
        assertEquals("gh", listedAs("Notes", "io.github.alice.notes.play", apps)?.id)
    }

    @Test fun sameNameFromAnotherDeveloperDoesNotMatch() {
        assertNull(listedAs("Fitness", "io.github.cmtvdemo.fitness", apps))
        assertNull(listedAs("Notes", "io.github.bob.notes", apps))
    }

    @Test fun shortOrUnknownNamesDoNotMatch() {
        assertNull(listedAs("TV", "com.tv.app", apps))
        assertNull(listedAs("Netflix", "com.netflix.ninja", apps))
    }
}
