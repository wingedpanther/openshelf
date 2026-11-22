package io.github.wingedpanther.openshelf

import org.junit.Assert.assertEquals
import org.junit.Test

class SortTest {
    private fun app(id: String, name: String, date: String) = App(
        id = id, repo = "o/$id", name = name, pkg = "p.$id", versionCode = 1, versionName = null, minSdk = null,
        room = "", note = "", desc = "", maker = "", stars = 0, licence = null, archived = false, streams = false,
        version = "1", date = date, certSha256 = null, art = AppArt(null, null, null, emptyList()),
        apks = emptyList(), releases = emptyList(), forkOf = null, parentStars = 0,
    )

    private val installed = Installed(1, null, emptySet())

    private val mine = listOf(
        app("b", "banana", "2026-03-01"),
        app("c", "Cherry", "2026-05-01"),
        app("a", "Apple", "2026-03-01"),
        app("d", "apple", "2026-01-01"),
    ).map { it to installed }

    private fun ids(sort: AppSort) = sortApps(mine, sort).map { it.first.id }

    @Test fun defaultKeepsTheGivenOrder() {
        assertEquals(listOf("b", "c", "a", "d"), ids(AppSort.Default))
    }

    @Test fun nameIsCaseInsensitiveAndTiesKeepTheirOrder() {
        assertEquals(listOf("a", "d", "b", "c"), ids(AppSort.Name))
    }

    @Test fun dateIsNewestFirstAndTiesKeepTheirOrder() {
        assertEquals(listOf("c", "b", "a", "d"), ids(AppSort.Date))
    }

    @Test fun emptyListStaysEmpty() {
        AppSort.entries.forEach { assertEquals(0, sortApps(emptyList(), it).size) }
    }
}
