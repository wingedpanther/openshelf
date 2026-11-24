package io.github.wingedpanther.openshelf

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyStatusTest {
    private val mine = "aa"
    private val other = "bb"

    @Test fun matchingCertMatchesEvenWithAStaleAcceptedKey() {
        assertEquals(KeyStatus.MATCHES, keyStatus(mine, setOf(mine), null))
        assertEquals(KeyStatus.MATCHES, keyStatus(mine, setOf(mine, other), other))
        assertEquals(KeyStatus.MATCHES, keyStatus(mine, setOf(mine), "cc"))
    }

    @Test fun differentKeyDiffers() {
        assertEquals(KeyStatus.DIFFERS, keyStatus(mine, setOf(other), null))
    }

    @Test fun acceptedInstalledKeyIsIgnored() {
        assertEquals(KeyStatus.IGNORED, keyStatus(mine, setOf(other), other))
    }

    @Test fun aDifferentKeyTurningUpLaterDiffersAgain() {
        assertEquals(KeyStatus.DIFFERS, keyStatus(mine, setOf("cc"), other))
    }

    @Test fun nothingToCompareMatches() {
        assertEquals(KeyStatus.MATCHES, keyStatus(null, setOf(other), null))
        assertEquals(KeyStatus.MATCHES, keyStatus(null, setOf(other), other))
        assertEquals(KeyStatus.MATCHES, keyStatus(mine, emptySet(), null))
        assertEquals(KeyStatus.MATCHES, keyStatus(mine, emptySet(), other))
    }
}
