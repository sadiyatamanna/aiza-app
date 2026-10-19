package com.example

import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PqcSanityTest {

    @Test
    fun testMlKem768KeyEncapsulation() {
        val random = SecureRandom()
        val keyGen = MLKEMKeyPairGenerator()
        keyGen.init(MLKEMKeyGenerationParameters(random, MLKEMParameters.ml_kem_768))
        val keyPair = keyGen.generateKeyPair()

        val pubKey = keyPair.public as MLKEMPublicKeyParameters
        val privKey = keyPair.private as MLKEMPrivateKeyParameters
        assertNotNull(pubKey)
        assertNotNull(privKey)

        // Encapsulate
        val generator = MLKEMGenerator(random)
        val secretWithEncapsulation = generator.generateEncapsulated(pubKey)
        val sharedSecret = secretWithEncapsulation.secret
        val encapsulation = secretWithEncapsulation.encapsulation
        assertNotNull(sharedSecret)
        assertNotNull(encapsulation)
        assertEquals(32, sharedSecret.size) // 256 bits

        // Decapsulate / Extract
        val extractor = MLKEMExtractor(privKey)
        val extractedSecret = extractor.extractSecret(encapsulation)
        assertNotNull(extractedSecret)
        assertArrayEquals(sharedSecret, extractedSecret)
    }

    @Test
    fun testMlDsa65SigningAndVerification() {
        val random = SecureRandom()
        val keyGen = MLDSAKeyPairGenerator()
        keyGen.init(MLDSAKeyGenerationParameters(random, MLDSAParameters.ml_dsa_65))
        val keyPair = keyGen.generateKeyPair()

        val pubKey = keyPair.public as MLDSAPublicKeyParameters
        val privKey = keyPair.private as MLDSAPrivateKeyParameters
        assertNotNull(pubKey)
        assertNotNull(privKey)

        val message = "AIZA Secure Evidence Manifest Canonical Payload".toByteArray(Charsets.UTF_8)

        // Sign
        val signer = MLDSASigner()
        signer.init(true, privKey)
        signer.update(message, 0, message.size)
        val signature = signer.generateSignature()
        assertNotNull(signature)
        assertTrue(signature.isNotEmpty())

        // Verify
        val verifier = MLDSASigner()
        verifier.init(false, pubKey)
        verifier.update(message, 0, message.size)
        val isValid = verifier.verifySignature(signature)
        assertTrue("Signature must be valid", isValid)

        // Tamper test
        val tamperedMessage = "AIZA Tampered Evidence Payload".toByteArray(Charsets.UTF_8)
        val verifier2 = MLDSASigner()
        verifier2.init(false, pubKey)
        verifier2.update(tamperedMessage, 0, tamperedMessage.size)
        val isTamperedValid = verifier2.verifySignature(signature)
        assertTrue("Tampered message signature must fail", !isTamperedValid)
    }
}
