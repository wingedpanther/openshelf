package io.github.wingedpanther.openshelf

import org.junit.Assert.assertEquals
import org.junit.Test

class NewsTest {
    private fun app(id: String, stars: Int, forkOf: String? = null) = App(
        id = id, repo = "o/$id", name = id, pkg = "p.$id", versionCode = 1, versionName = null, minSdk = null,
        room = "", note = "", desc = "", maker = "", stars = stars, licence = null, archived = false, streams = false,
        version = "1", date = "2026-11-26", certSha256 = null, art = AppArt(null, null, null, emptyList()),
        apks = emptyList(), releases = emptyList(), forkOf = forkOf, parentStars = 0,
    )

    @Test fun onlyUnknownReposMostStarredFirst() {
        val apps = listOf(app("old", 900), app("small", 12), app("big", 400), app("Case", 50))
        val news = CatalogUpdate.added(setOf("o/old", "o/case"), apps)
        assertEquals(listOf("big", "small"), news.map { it.id })
    }

    @Test fun forksAreNotNews() {
        val news = CatalogUpdate.added(setOf("o/old"), listOf(app("fork", 80, forkOf = "o/old"), app("new", 20)))
        assertEquals(listOf("new"), news.map { it.id })
    }
}
