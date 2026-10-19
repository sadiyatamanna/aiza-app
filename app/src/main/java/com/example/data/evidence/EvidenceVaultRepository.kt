package com.example.data.evidence

import android.content.Context
import android.util.Log
import com.example.data.PreferencesManager
import com.example.data.SupabaseClientProvider
import com.example.data.pqc.AizaPqcSecurityManager
import com.example.domain.SafetyRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Repository for managing PQC-encrypted evidence collection, local persistence,
 * Supabase Storage synchronization, integrity verification, and secure retrieval.
 */
class EvidenceVaultRepository(
    private val evidenceDao: EvidenceDao,
    private val preferencesManager: PreferencesManager
) {

    companion object {
        private const val TAG = "EvidenceVaultRepo"
        const val STORAGE_BUCKET = "aiza-evidence"
        const val METADATA_TABLE = "aiza_evidence"
    }

    /**
     * Resolves the current authenticated Supabase user's UUID.
     * Returns null if unauthenticated, if the session is invalid, or if the UUID is synthetic.
     */
    fun getAuthenticatedUserId(): String? {
        val currentAuthUser = SupabaseClientProvider.client.auth.currentUserOrNull()
        val authId = currentAuthUser?.id?.trim()
        if (SafetyRepository.isRealAuthenticatedUserUuid(authId)) {
            return authId
        }
        val prefId = preferencesManager.supabaseUserId.trim()
        val session = SupabaseClientProvider.client.auth.currentSessionOrNull()
        if (session != null && SafetyRepository.isRealAuthenticatedUserUuid(prefId)) {
            return prefId
        }
        return null
    }

    fun observeAllEvidence(userId: String): Flow<List<EvidenceRecordEntity>> {
        return if (userId.isNotBlank()) {
            evidenceDao.observeAllByUser(userId)
        } else {
            evidenceDao.observeAll()
        }
    }

    fun observeBySosEvent(sosEventId: String): Flow<List<EvidenceRecordEntity>> {
        return evidenceDao.observeBySosEvent(sosEventId)
    }

    suspend fun getById(id: String): EvidenceRecordEntity? = withContext(Dispatchers.IO) {
        evidenceDao.getById(id)
    }

    /**
     * Encrypts the raw captured media locally using AES-256-GCM, wraps the media key using
     * ML-KEM-768, signs the canonical evidence manifest with ML-DSA-65, saves the record to Room,
     * and triggers background backup to Supabase Storage only if the user is authenticated.
     */
    suspend fun saveAndProtectEvidence(
        context: Context,
        rawMediaFile: File,
        evidenceType: String,
        mimeType: String,
        sosEventId: String? = null
    ): Result<EvidenceRecordEntity> = withContext(Dispatchers.IO) {
        try {
            if (!rawMediaFile.exists() || rawMediaFile.length() == 0L) {
                return@withContext Result.failure(IllegalArgumentException("Raw evidence file is empty or does not exist"))
            }

            val authUserId = getAuthenticatedUserId()
            val userId = if (authUserId != null) {
                authUserId
            } else {
                // When capturing offline or before sign-in, keep record strictly local with a local identifier
                "local_user_${UUID.randomUUID()}"
            }
            val evidenceId = UUID.randomUUID().toString()

            val vaultDir = File(context.filesDir, "pqc_vault").apply { mkdirs() }

            // Execute local PQC encryption & signing pipeline
            val pkg = AizaPqcSecurityManager.protectAndPackageEvidence(
                context = context,
                rawMediaFile = rawMediaFile,
                evidenceId = evidenceId,
                userId = userId,
                evidenceType = evidenceType,
                mimeType = mimeType,
                sosEventId = sosEventId,
                outputDir = vaultDir
            )

            val remotePath = if (authUserId != null) "user_${userId}/${evidenceId}.enc" else null

            val entity = EvidenceRecordEntity(
                id = evidenceId,
                userId = userId,
                evidenceType = evidenceType,
                captureTimestamp = System.currentTimeMillis(),
                sosEventId = sosEventId?.takeIf { it.isNotBlank() },
                mimeType = mimeType,
                originalFileSize = pkg.originalFileSize,
                encryptedFileSize = pkg.encryptedFileSize,
                sha256OriginalMedia = pkg.sha256OriginalMedia,
                sha256EncryptedMedia = pkg.sha256EncryptedMedia,
                signatureBase64 = pkg.signatureBase64,
                mlDsaPublicKeyHex = pkg.mlDsaPublicKeyHex,
                mlKemPublicKeyHex = pkg.mlKemPublicKeyHex,
                kemCiphertextHex = pkg.kemCiphertextHex,
                wrappedAesKeyHex = pkg.wrappedAesKeyHex,
                wrapIvHex = pkg.wrapIvHex,
                aesGcmIvHex = pkg.aesGcmIvHex,
                encryptionAlgorithm = AizaPqcSecurityManager.ALGORITHM_IDENTIFIER,
                formatVersion = AizaPqcSecurityManager.FORMAT_VERSION,
                canonicalManifestJson = pkg.canonicalManifestJson,
                localEncryptedPath = pkg.encryptedFile.absolutePath,
                storageBucket = STORAGE_BUCKET,
                storageObjectPath = remotePath,
                syncState = EvidenceSyncState.LOCAL_ONLY,
                verificationState = EvidenceVerificationState.UNVERIFIED
            )

            evidenceDao.insert(entity)

            // Safely delete unencrypted original media file after encryption is verified
            try {
                if (rawMediaFile.exists() && rawMediaFile.absolutePath != pkg.encryptedFile.absolutePath) {
                    rawMediaFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notice: could not delete temporary raw file: ${e.message}")
            }

            // Attempt upload immediately in background ONLY if authenticated
            if (authUserId != null) {
                try {
                    uploadEvidenceToSupabase(entity)
                } catch (e: Exception) {
                    Log.w(TAG, "Initial upload postponed: ${e.message}")
                }
            } else {
                Log.i(TAG, "Evidence $evidenceId preserved locally as LOCAL_ONLY (user unauthenticated)")
            }

            val updated = evidenceDao.getById(evidenceId) ?: entity
            Result.success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to protect and package evidence: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Uploads the locally encrypted evidence package to private Supabase Storage and records metadata.
     * Enforces that the user is authenticated with a valid Supabase UUID and owns the evidence record.
     * Only transitions to BACKED_UP after both Storage upload and PostgREST metadata upsert succeed.
     */
    suspend fun uploadEvidenceToSupabase(record: EvidenceRecordEntity): Result<EvidenceRecordEntity> = withContext(Dispatchers.IO) {
        val encryptedFilePath = record.localEncryptedPath
        if (encryptedFilePath.isNullOrBlank()) {
            val error = "Missing local encrypted file path"
            evidenceDao.updateSyncState(record.id, EvidenceSyncState.FAILED, record.storageObjectPath, error)
            return@withContext Result.failure(IllegalStateException(error))
        }

        val file = File(encryptedFilePath)
        if (!file.exists() || file.length() == 0L) {
            val error = "Encrypted evidence file missing on device"
            evidenceDao.updateSyncState(record.id, EvidenceSyncState.FAILED, record.storageObjectPath, error)
            return@withContext Result.failure(IllegalStateException(error))
        }

        // FIX 1: Enforce valid Supabase Auth session and genuine user UUID
        val authenticatedUid = getAuthenticatedUserId()
        if (authenticatedUid == null) {
            val error = "Cloud upload requires an active authenticated Supabase session"
            Log.w(TAG, "Upload aborted: $error for evidence ${record.id}")
            // Retain LOCAL_ONLY state if not already failed
            if (record.syncState != EvidenceSyncState.FAILED) {
                evidenceDao.updateSyncState(record.id, EvidenceSyncState.LOCAL_ONLY, record.storageObjectPath, error)
            }
            return@withContext Result.failure(IllegalStateException(error))
        }

        // Ensure the record's user_id matches the authenticated user; do not silently transfer ownership
        if (!record.userId.equals(authenticatedUid, ignoreCase = true)) {
            val error = if (record.userId.startsWith("local_user_") || record.userId.contains("@")) {
                "Evidence was captured prior to sign-in and must be safely linked to your account before upload"
            } else {
                "Evidence belongs to a different user account and cannot be uploaded to $authenticatedUid"
            }
            Log.e(TAG, "Upload authorization mismatch: $error")
            evidenceDao.updateSyncState(record.id, EvidenceSyncState.FAILED, record.storageObjectPath, error)
            return@withContext Result.failure(SecurityException(error))
        }

        val targetPath = record.storageObjectPath ?: "user_${authenticatedUid}/${record.id}.enc"

        evidenceDao.updateSyncState(record.id, EvidenceSyncState.UPLOADING, targetPath, null)

        try {
            val bytes = file.readBytes()

            // FIX 2 & FIX 3: 1. Upload encrypted media to Supabase Storage (aiza-evidence exclusively)
            val primaryBucket = SupabaseClientProvider.client.storage.from(STORAGE_BUCKET)
            primaryBucket.upload(path = targetPath, data = bytes) {
                upsert = true
            }

            // FIX 2: 2. Sync metadata to Supabase PostgREST (public.aiza_evidence)
            val dto = EvidenceRecordDto(
                id = record.id,
                userId = authenticatedUid,
                evidenceType = record.evidenceType,
                captureTimestamp = record.captureTimestamp,
                sosEventId = record.sosEventId,
                mimeType = record.mimeType,
                originalFileSize = record.originalFileSize,
                encryptedFileSize = record.encryptedFileSize,
                sha256OriginalMedia = record.sha256OriginalMedia,
                sha256EncryptedMedia = record.sha256EncryptedMedia,
                signature = record.signatureBase64,
                publicKeyRef = record.mlDsaPublicKeyHex,
                kemPublicKey = record.mlKemPublicKeyHex,
                kemCiphertext = record.kemCiphertextHex,
                wrappedAesKey = record.wrappedAesKeyHex,
                aesGcmIv = record.aesGcmIvHex,
                wrapIv = record.wrapIvHex,
                encryptionAlgorithm = record.encryptionAlgorithm,
                formatVersion = record.formatVersion,
                canonicalManifest = record.canonicalManifestJson,
                storageBucket = STORAGE_BUCKET,
                storageObjectPath = targetPath,
                syncState = EvidenceSyncState.BACKED_UP,
                verificationState = record.verificationState,
                createdAt = record.createdAt,
                updatedAt = System.currentTimeMillis()
            )

            // Do not swallow metadata-upsert exceptions!
            SupabaseClientProvider.client.from(METADATA_TABLE).upsert(dto)

            // BOTH remote operations succeeded: mark BACKED_UP
            evidenceDao.updateSyncState(record.id, EvidenceSyncState.BACKED_UP, targetPath, null)
            val updated = record.copy(
                syncState = EvidenceSyncState.BACKED_UP,
                storageBucket = STORAGE_BUCKET,
                storageObjectPath = targetPath,
                lastSyncError = null,
                updatedAt = System.currentTimeMillis()
            )
            Result.success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for evidence ${record.id}: ${e.message}", e)
            val errorMsg = e.localizedMessage ?: e.message ?: "Upload network or authorization failure"
            // FIX 4: Keep targetPath stable for retries, update state to FAILED
            evidenceDao.updateSyncState(record.id, EvidenceSyncState.FAILED, targetPath, errorMsg)
            Result.failure(e)
        }
    }

    /**
     * Verifies the cryptographic integrity of an evidence package:
     * - Validates SHA-256 of encrypted bytes
     * - Validates ML-DSA-65 post-quantum signature against canonical manifest
     */
    suspend fun verifyEvidence(record: EvidenceRecordEntity): AizaPqcSecurityManager.VerificationResult = withContext(Dispatchers.IO) {
        val file = record.localEncryptedPath?.let { File(it) }
        if (file == null || !file.exists()) {
            return@withContext AizaPqcSecurityManager.VerificationResult(
                isValid = false,
                isSignatureValid = false,
                isEncryptedHashMatched = false,
                isOriginalHashMatched = false,
                failureReason = "Encrypted evidence file missing on device"
            )
        }

        val result = AizaPqcSecurityManager.verifyEvidenceIntegrity(
            encryptedFile = file,
            canonicalManifestJson = record.canonicalManifestJson,
            signatureBase64 = record.signatureBase64,
            mlDsaPublicKeyHex = record.mlDsaPublicKeyHex,
            expectedEncryptedHash = record.sha256EncryptedMedia
        )

        val newState = if (result.isValid) {
            EvidenceVerificationState.VERIFICATION_PASSED
        } else {
            EvidenceVerificationState.VERIFICATION_FAILED
        }
        evidenceDao.updateVerificationState(record.id, newState)

        result
    }

    /**
     * Decrypts authorized evidence for user viewing, verifying both ML-DSA signature and hashes.
     */
    suspend fun retrieveAndDecryptEvidence(
        context: Context,
        record: EvidenceRecordEntity
    ): Result<AizaPqcSecurityManager.DecryptedEvidenceResult> = withContext(Dispatchers.IO) {
        try {
            var encryptedFile = record.localEncryptedPath?.let { File(it) }

            // If local file is missing, try downloading from Supabase Storage
            if (encryptedFile == null || !encryptedFile.exists()) {
                val remotePath = record.storageObjectPath
                if (remotePath.isNullOrBlank()) {
                    return@withContext Result.failure(IllegalStateException("No local copy and no cloud path available"))
                }

                val downloadDir = File(context.cacheDir, "pqc_downloads").apply { mkdirs() }
                val targetFile = File(downloadDir, "${record.id}.enc")

                val bucket = SupabaseClientProvider.client.storage.from(STORAGE_BUCKET)
                val downloadedBytes = bucket.downloadAuthenticated(remotePath)

                targetFile.writeBytes(downloadedBytes)
                encryptedFile = targetFile
            }

            val decryptedDir = File(context.cacheDir, "pqc_decrypted").apply { mkdirs() }
            val extension = when (record.evidenceType) {
                EvidenceType.PHOTO -> "jpg"
                EvidenceType.AUDIO -> "mp4"
                EvidenceType.VIDEO -> "mp4"
                else -> "dat"
            }
            val outputDecryptedFile = File(decryptedDir, "decrypted_${record.id}.$extension")

            val decryptResult = AizaPqcSecurityManager.decryptAndVerifyEvidence(
                context = context,
                encryptedFile = encryptedFile,
                canonicalManifestJson = record.canonicalManifestJson,
                signatureBase64 = record.signatureBase64,
                mlDsaPublicKeyHex = record.mlDsaPublicKeyHex,
                expectedEncryptedHash = record.sha256EncryptedMedia,
                expectedOriginalHash = record.sha256OriginalMedia,
                aesGcmIvHex = record.aesGcmIvHex,
                kemCiphertextHex = record.kemCiphertextHex,
                wrappedAesKeyHex = record.wrappedAesKeyHex,
                wrapIvHex = record.wrapIvHex,
                outputDecryptedFile = outputDecryptedFile
            )

            if (decryptResult.isSuccess) {
                evidenceDao.updateVerificationState(record.id, EvidenceVerificationState.VERIFICATION_PASSED)
            } else {
                evidenceDao.updateVerificationState(record.id, EvidenceVerificationState.VERIFICATION_FAILED)
            }

            decryptResult
        } catch (e: Exception) {
            Log.e(TAG, "Decryption and retrieval failed for ${record.id}: ${e.message}", e)
            evidenceDao.updateVerificationState(record.id, EvidenceVerificationState.VERIFICATION_FAILED)
            Result.failure(e)
        }
    }

    /**
     * Deletes evidence from local device and Supabase storage/database.
     */
    suspend fun deleteEvidence(record: EvidenceRecordEntity): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Delete local encrypted file
            record.localEncryptedPath?.let {
                val f = File(it)
                if (f.exists()) f.delete()
            }

            // Delete from Supabase Storage
            record.storageObjectPath?.let { path ->
                try {
                    SupabaseClientProvider.client.storage.from(record.storageBucket).delete(listOf(path))
                } catch (e: Exception) {
                    Log.w(TAG, "Storage deletion failed (may already be deleted): ${e.message}")
                }
            }

            // Delete from Supabase PostgREST
            try {
                SupabaseClientProvider.client.from(METADATA_TABLE).delete {
                    filter { eq("id", record.id) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PostgREST deletion note: ${e.message}")
            }

            // Delete from local Room
            evidenceDao.deleteById(record.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Delete evidence failed for ${record.id}: ${e.message}", e)
            Result.failure(e)
        }
    }
}
