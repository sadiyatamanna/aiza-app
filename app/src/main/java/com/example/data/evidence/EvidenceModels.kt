package com.example.data.evidence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object EvidenceType {
    const val PHOTO = "PHOTO"
    const val AUDIO = "AUDIO"
    const val VIDEO = "VIDEO"
}

object EvidenceSyncState {
    const val LOCAL_ONLY = "Local Only"
    const val UPLOADING = "Uploading"
    const val BACKED_UP = "Backed Up"
    const val FAILED = "Backup Failed"
}

object EvidenceVerificationState {
    const val UNVERIFIED = "Unverified"
    const val VERIFICATION_PASSED = "Verification Passed"
    const val VERIFICATION_FAILED = "Verification Failed"
}

@Entity(
    tableName = "evidence_records",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["sosEventId"]),
        Index(value = ["captureTimestamp"]),
        Index(value = ["syncState"])
    ]
)
data class EvidenceRecordEntity(
    @PrimaryKey
    val id: String,
    val userId: String,
    val evidenceType: String,
    val captureTimestamp: Long,
    val sosEventId: String? = null,
    val mimeType: String,
    val originalFileSize: Long,
    val encryptedFileSize: Long,
    val sha256OriginalMedia: String,
    val sha256EncryptedMedia: String,
    val signatureBase64: String,
    val mlDsaPublicKeyHex: String,
    val mlKemPublicKeyHex: String,
    val kemCiphertextHex: String,
    val wrappedAesKeyHex: String,
    val wrapIvHex: String,
    val aesGcmIvHex: String,
    val encryptionAlgorithm: String = "AES-256-GCM / ML-KEM-768 / ML-DSA-65",
    val formatVersion: String = "AIZA-PQC-VAULT-V1",
    val canonicalManifestJson: String,
    val localEncryptedPath: String? = null,
    val storageBucket: String = "aiza-evidence",
    val storageObjectPath: String? = null,
    val syncState: String = EvidenceSyncState.LOCAL_ONLY,
    val verificationState: String = EvidenceVerificationState.UNVERIFIED,
    val lastSyncError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class EvidenceRecordDto(
    @SerialName("id")
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("evidence_type")
    val evidenceType: String,
    @SerialName("capture_timestamp")
    val captureTimestamp: Long,
    @SerialName("sos_event_id")
    val sosEventId: String? = null,
    @SerialName("mime_type")
    val mimeType: String,
    @SerialName("original_file_size")
    val originalFileSize: Long,
    @SerialName("encrypted_file_size")
    val encryptedFileSize: Long,
    @SerialName("sha256_original_media")
    val sha256OriginalMedia: String,
    @SerialName("sha256_encrypted_media")
    val sha256EncryptedMedia: String,
    @SerialName("signature")
    val signature: String,
    @SerialName("public_key_ref")
    val publicKeyRef: String,
    @SerialName("kem_public_key")
    val kemPublicKey: String,
    @SerialName("kem_ciphertext")
    val kemCiphertext: String,
    @SerialName("wrapped_aes_key")
    val wrappedAesKey: String,
    @SerialName("aes_gcm_iv")
    val aesGcmIv: String,
    @SerialName("wrap_iv")
    val wrapIv: String? = null,
    @SerialName("encryption_algorithm")
    val encryptionAlgorithm: String = "AES-256-GCM / ML-KEM-768 / ML-DSA-65",
    @SerialName("format_version")
    val formatVersion: String = "AIZA-PQC-VAULT-V1",
    @SerialName("canonical_manifest")
    val canonicalManifest: String? = null,
    @SerialName("storage_bucket")
    val storageBucket: String = "aiza-evidence",
    @SerialName("storage_object_path")
    val storageObjectPath: String? = null,
    @SerialName("sync_state")
    val syncState: String = EvidenceSyncState.BACKED_UP,
    @SerialName("verification_state")
    val verificationState: String = EvidenceVerificationState.UNVERIFIED,
    @SerialName("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @SerialName("updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
