package io.github.wingedpanther.openshelf

import org.junit.Assert.assertEquals
import org.junit.Test

class SignatureRulesTest {
    private val mine = "aa"
    private val other = "bb"

    @Test fun matchingCertIsOk() {
        assertEquals(SignatureRule.OK, signatureRule(mine, setOf(mine), setOf(mine), null))
    }

    @Test fun keyChangedWhenFileLacksTheCatalogueCert() {
        assertEquals(SignatureRule.KEY_CHANGED, signatureRule(mine, setOf(other), setOf(mine), null))
        assertEquals(SignatureRule.KEY_CHANGED, signatureRule(mine, setOf(other), null, null))
    }

    @Test fun keyChangedWinsOverSibling() {
        assertEquals(SignatureRule.KEY_CHANGED, signatureRule(mine, setOf(other), setOf("cc"), "o/sibling"))
    }

    @Test fun installedAsASiblingCatalogueBuild() {
        assertEquals(SignatureRule.SIBLING, signatureRule(null, setOf(mine), setOf(other), "o/sibling"))
        assertEquals(SignatureRule.SIBLING, signatureRule(mine, setOf(mine), setOf(other), "o/sibling"))
    }

    @Test fun installedFromAnotherSource() {
        assertEquals(SignatureRule.OTHER_BUILD, signatureRule(null, setOf(mine), setOf(other), null))
        assertEquals(SignatureRule.OTHER_BUILD, signatureRule(mine, setOf(mine), setOf(other), null))
    }

    @Test fun notInstalledIsOk() {
        assertEquals(SignatureRule.OK, signatureRule(mine, setOf(mine), null, null))
        assertEquals(SignatureRule.OK, signatureRule(mine, setOf(mine), null, "o/sibling"))
    }

    @Test fun noRecordedCatalogueCertIsOkWhenNothingElseDiffers() {
        assertEquals(SignatureRule.OK, signatureRule(null, setOf(mine), setOf(mine), null))
        assertEquals(SignatureRule.OK, signatureRule(null, setOf(other), null, null))
    }

    @Test fun noCertsOnTheFileOrInstalledAppIsOk() {
        assertEquals(SignatureRule.OK, signatureRule(mine, emptySet(), setOf(other), "o/sibling"))
        assertEquals(SignatureRule.OK, signatureRule(mine, setOf(mine), emptySet(), "o/sibling"))
    }

    @Test fun anyOverlappingCertCounts() {
        assertEquals(SignatureRule.OK, signatureRule(null, setOf(mine, other), setOf(other, "cc"), "o/sibling"))
    }
}
