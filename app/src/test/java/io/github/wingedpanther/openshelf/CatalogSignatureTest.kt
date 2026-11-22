package io.github.wingedpanther.openshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class CatalogSignatureTest {
    private val data = javaClass.getResource("/catalog.json")!!.readBytes()

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sign(bytes: ByteArray, key: java.security.PrivateKey): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(bytes)
            sign()
        }

    @Test fun validSignaturePasses() {
        val pair = keyPair()
        assertTrue(CatalogUpdate.verify(data, sign(data, pair.private), pair.public))
    }

    @Test fun oneChangedByteFails() {
        val pair = keyPair()
        val sig = sign(data, pair.private)
        val changed = data.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertFalse(CatalogUpdate.verify(changed, sig, pair.public))
    }

    @Test fun wrongKeyFails() {
        val sig = sign(data, keyPair().private)
        assertFalse(CatalogUpdate.verify(data, sig, keyPair().public))
    }

    @Test fun garbageSignatureReturnsFalse() {
        val pair = keyPair()
        assertFalse(CatalogUpdate.verify(data, ByteArray(0), pair.public))
        assertFalse(CatalogUpdate.verify(data, byteArrayOf(1, 2, 3, 4, 5), pair.public))
        assertFalse(CatalogUpdate.verify(data, ByteArray(70) { 0x30 }, pair.public))
    }

    @Test fun theBuiltInKeyIsAnEcPublicKey() {
        val field = CatalogUpdate::class.java.getDeclaredField("KEY").apply { isAccessible = true }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(field.get(null) as String)))
        assertEquals("EC", key.algorithm)
    }
}
