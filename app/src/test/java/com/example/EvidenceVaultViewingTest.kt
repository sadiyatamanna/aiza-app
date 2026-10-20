package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.evidence.EvidenceRecordEntity
import com.example.data.evidence.EvidenceSyncState
import com.example.data.evidence.EvidenceType
import com.example.data.evidence.EvidenceVerificationState
import com.example.data.pqc.AizaPqcSecurityManager
import com.example.viewmodel.SafetyViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EvidenceVaultViewingTest {

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var viewModel: SafetyViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        context = application
        viewModel = SafetyViewModel(application)
    }

    @Test
    fun testPhotoAndVideoEvidenceDistinction() {
        val photoRecord = EvidenceRecordEntity(
            id = "photo_123",
            userId = "user_test",
            captureTimestamp = System.currentTimeMillis(),
            evidenceType = EvidenceType.PHOTO,
            mimeType = "image/jpeg",
            originalFileSize = 2048,
            encryptedFileSize = 2100,
            sha256OriginalMedia = "abc1",
            sha256EncryptedMedia = "def1",
            aesGcmIvHex = "00".repeat(12),
            kemCiphertextHex = "00".repeat(32),
            wrappedAesKeyHex = "00".repeat(32),
            wrapIvHex = "00".repeat(12),
            mlDsaPublicKeyHex = "00".repeat(32),
            mlKemPublicKeyHex = "00".repeat(32),
            canonicalManifestJson = "{}",
            signatureBase64 = "c2ln",
            verificationState = EvidenceVerificationState.VERIFICATION_PASSED,
            syncState = EvidenceSyncState.LOCAL_ONLY
        )

        val videoRecord = photoRecord.copy(
            id = "video_456",
            evidenceType = EvidenceType.VIDEO,
            mimeType = "video/mp4",
            originalFileSize = 1048576,
            encryptedFileSize = 1049000
        )

        assertEquals(EvidenceType.PHOTO, photoRecord.evidenceType)
        assertEquals(EvidenceType.VIDEO, videoRecord.evidenceType)
        assertTrue(videoRecord.originalFileSize > photoRecord.originalFileSize)
        assertEquals("image/jpeg", photoRecord.mimeType)
        assertEquals("video/mp4", videoRecord.mimeType)
    }

    @Test
    fun testDecryptionAndTemporaryCacheLifecycle() {
        // Create sample raw evidence media
        val rawData = "AIZA Raw Evidence Sample Data for Testing".toByteArray(Charsets.UTF_8)
        val tempRawFile = File(context.cacheDir, "test_raw_evidence.jpg").apply {
            writeBytes(rawData)
        }

        val outputDir = File(context.cacheDir, "test_pqc_output").apply { mkdirs() }

        // Encrypt with PQC Manager
        val protectedMedia = AizaPqcSecurityManager.protectAndPackageEvidence(
            context = context,
            rawMediaFile = tempRawFile,
            evidenceId = "test_ev_001",
            userId = "test_user_alice",
            evidenceType = EvidenceType.PHOTO,
            mimeType = "image/jpeg",
            sosEventId = null,
            outputDir = outputDir
        )

        val encryptedFile = protectedMedia.encryptedFile
        assertTrue("Encrypted file must exist", encryptedFile.exists())
        assertTrue("Ciphertext must not match raw data", !encryptedFile.readBytes().contentEquals(rawData))

        // Decrypt to temporary viewer cache
        val tempViewerFile = File(context.cacheDir, "test_view_decrypted.jpg")
        val decryptResult = AizaPqcSecurityManager.decryptAndVerifyEvidence(
            context = context,
            encryptedFile = encryptedFile,
            canonicalManifestJson = protectedMedia.canonicalManifestJson,
            signatureBase64 = protectedMedia.signatureBase64,
            mlDsaPublicKeyHex = protectedMedia.mlDsaPublicKeyHex,
            expectedEncryptedHash = protectedMedia.sha256EncryptedMedia,
            expectedOriginalHash = protectedMedia.sha256OriginalMedia,
            aesGcmIvHex = protectedMedia.aesGcmIvHex,
            kemCiphertextHex = protectedMedia.kemCiphertextHex,
            wrappedAesKeyHex = protectedMedia.wrappedAesKeyHex,
            wrapIvHex = protectedMedia.wrapIvHex,
            outputDecryptedFile = tempViewerFile
        )

        assertTrue("Decryption must succeed", decryptResult.isSuccess)
        val decryptedData = decryptResult.getOrNull()
        assertNotNull(decryptedData)
        assertTrue("Decrypted file must exist in cache", tempViewerFile.exists())
        assertEquals(rawData.size.toLong(), tempViewerFile.length())
        assertTrue("Decrypted bytes must match raw evidence", tempViewerFile.readBytes().contentEquals(rawData))
        assertTrue("Integrity verification passed", decryptedData!!.verificationResult.isValid)
        assertTrue("ML-DSA signature verified", decryptedData.verificationResult.isSignatureValid)

        // Simulate ViewModel cache cleanup
        if (tempViewerFile.exists()) {
            tempViewerFile.delete()
        }
        assertFalse("Decrypted viewer cache file must be cleaned up", tempViewerFile.exists())

        // Cleanup raw & encrypted files
        tempRawFile.delete()
        encryptedFile.delete()
    }

    @Test
    fun testMissingEncryptedFileGracefulFailure() {
        val nonExistentFile = File(context.cacheDir, "missing_nonexistent_evidence.enc")
        if (nonExistentFile.exists()) nonExistentFile.delete()

        val verification = AizaPqcSecurityManager.verifyEvidenceIntegrity(
            encryptedFile = nonExistentFile,
            canonicalManifestJson = "{}",
            signatureBase64 = "invalid",
            mlDsaPublicKeyHex = "00".repeat(32),
            expectedEncryptedHash = "abc"
        )

        assertFalse("Missing file must fail verification gracefully", verification.isValid)
        assertEquals("Encrypted media file not found locally", verification.failureReason)
    }

    @Test
    fun testCorruptedCiphertextFailsIntegrityCheck() {
        // Create raw media
        val rawData = "Video stream frame sample bytes".toByteArray(Charsets.UTF_8)
        val tempRawFile = File(context.cacheDir, "test_video_frame.mp4").apply {
            writeBytes(rawData)
        }

        val outputDir = File(context.cacheDir, "test_pqc_output_video").apply { mkdirs() }

        val protectedMedia = AizaPqcSecurityManager.protectAndPackageEvidence(
            context = context,
            rawMediaFile = tempRawFile,
            evidenceId = "test_ev_video_002",
            userId = "test_user_bob",
            evidenceType = EvidenceType.VIDEO,
            mimeType = "video/mp4",
            sosEventId = null,
            outputDir = outputDir
        )

        val encryptedFile = protectedMedia.encryptedFile
        assertTrue(encryptedFile.exists())

        // Tamper with ciphertext by corrupting bytes
        val corruptedBytes = encryptedFile.readBytes()
        corruptedBytes[0] = (corruptedBytes[0].toInt() xor 0xFF).toByte()
        val corruptedFile = File(context.cacheDir, "corrupted_video.enc").apply {
            writeBytes(corruptedBytes)
        }

        val tempDecryptedFile = File(context.cacheDir, "should_never_exist.mp4")
        val decryptResult = AizaPqcSecurityManager.decryptAndVerifyEvidence(
            context = context,
            encryptedFile = corruptedFile,
            canonicalManifestJson = protectedMedia.canonicalManifestJson,
            signatureBase64 = protectedMedia.signatureBase64,
            mlDsaPublicKeyHex = protectedMedia.mlDsaPublicKeyHex,
            expectedEncryptedHash = protectedMedia.sha256EncryptedMedia,
            expectedOriginalHash = protectedMedia.sha256OriginalMedia,
            aesGcmIvHex = protectedMedia.aesGcmIvHex,
            kemCiphertextHex = protectedMedia.kemCiphertextHex,
            wrappedAesKeyHex = protectedMedia.wrappedAesKeyHex,
            wrapIvHex = protectedMedia.wrapIvHex,
            outputDecryptedFile = tempDecryptedFile
        )

        assertTrue("Decryption of corrupted ciphertext must fail", decryptResult.isFailure)
        assertFalse("Decrypted output file must not be created on failure", tempDecryptedFile.exists())

        // Cleanup
        tempRawFile.delete()
        encryptedFile.delete()
        corruptedFile.delete()
    }
}
