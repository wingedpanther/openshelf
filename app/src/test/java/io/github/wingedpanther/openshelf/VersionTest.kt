package io.github.wingedpanther.openshelf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test fun newerOlderEqual() {
        assertTrue(SelfUpdate.newer("1.0.1", "1.0.0"))
        assertFalse(SelfUpdate.newer("1.0.0", "1.0.1"))
        assertFalse(SelfUpdate.newer("1.0.0", "1.0.0"))
    }

    @Test fun differentLengths() {
        assertFalse(SelfUpdate.newer("1.2", "1.2.0"))
        assertFalse(SelfUpdate.newer("1.2.0", "1.2"))
        assertTrue(SelfUpdate.newer("1.2.1", "1.2"))
        assertFalse(SelfUpdate.newer("1.2", "1.2.1"))
        assertTrue(SelfUpdate.newer("2", "1.9.9"))
    }

    @Test fun multiDigitPartsCompareAsNumbers() {
        assertTrue(SelfUpdate.newer("0.10.0", "0.9.9"))
        assertFalse(SelfUpdate.newer("0.9.9", "0.10.0"))
        assertTrue(SelfUpdate.newer("1.10", "1.9"))
    }

    @Test fun preReleaseSuffixCountsAsZeroSoItIsNeverNewer() {
        assertFalse(SelfUpdate.newer("0.10.0-rc1", "0.10.0"))
        assertFalse(SelfUpdate.newer("0.10.0", "0.10.0-rc1"))
        assertTrue(SelfUpdate.newer("0.11.0-rc1", "0.10.0"))
    }

    @Test fun nonNumericPartsCountAsZero() {
        assertFalse(SelfUpdate.newer("abc", "0"))
        assertTrue(SelfUpdate.newer("1.0", "abc"))
        assertFalse(SelfUpdate.newer("", ""))
    }
}
