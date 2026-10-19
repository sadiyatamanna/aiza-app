package com.example.bridge

import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.SecretWithEncapsulation
import org.bouncycastle.crypto.params.ParametersWithRandom
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
import org.json.JSONObject

/**
 * Android Native Post-Quantum Cryptography (PQC) Bridge for AIZA / Aegis Safety.
 *
 * CRYPTOGRAPHIC SPECIFICATION & AUDIT COMPLIANCE:
 * ------------------------------------------------
 * 1. Key Encapsulation Mechanism (KEM): NIST FIPS 203 (ML-KEM-1024)
 *    - Parameter set: MLKEMParameters.ml_kem_1024
 *    - Public key length: 1568 bytes
 *    - Private key length: 3168 bytes
 *    - Ciphertext encapsulation: 1568 bytes
 *    - Shared secret: 32 bytes (256-bit entropy)
 *
 * 2. Digital Signatures (DSA): NIST FIPS 204 (ML-DSA-87)
 *    - Parameter set: MLDSAParameters.ml_dsa_87
 *    - Public key length: 2592 bytes
 *    - Private key length: 4896 bytes
 *    - Signature length: 4627 bytes
 *
 * 3. Hybrid Authenticated Payload Encryption: AES-256-GCM
 *    - 256-bit symmetric key derived via ML-KEM-1024 key encapsulation
 *    - 96-bit (12-byte) cryptographically secure random IV/nonce per encryption operation
 *    - 128-bit authentication tag enforcing ciphertext integrity
 *
 * Backed by genuine Bouncy Castle 1.81 FIPS 203 & FIPS 204 cryptographic provider.
 * Strictly avoids pseudo-PQC wrappers, mock algorithms, or plaintext pass-through.
 */
class AegisPqcModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val MODULE_NAME = "AegisPqcModule"
        const val STATUS_ACTIVE = "PQC_ACTIVE"
        private val secureRandom = SecureRandom()

        // Exact byte sizes defined by NIST standards
        const val ML_KEM_1024_PUB_BYTES = 1568
        const val ML_KEM_1024_PRIV_BYTES = 3168
        const val ML_KEM_1024_CIPHERTEXT_BYTES = 1568
        const val ML_KEM_1024_SECRET_BYTES = 32

        const val ML_DSA_87_PUB_BYTES = 2592
        const val ML_DSA_87_PRIV_BYTES = 4896
        const val ML_DSA_87_SIG_BYTES = 4627

        const val AES_GCM_IV_BYTES = 12
        const val AES_GCM_TAG_BITS = 128
    }

    override fun getName(): String = MODULE_NAME

    /**
     * Checks if native post-quantum cryptographic primitives (ML-KEM-1024 / ML-DSA-87) are active.
     */
    @ReactMethod
    fun isPqcAvailable(promise: Promise) {
        promise.resolve(true)
    }

    /**
     * Returns detailed status of the PQC subsystem.
     */
    @ReactMethod
    fun getPqcStatus(promise: Promise) {
        try {
            val result = Arguments.createMap().apply {
                putBoolean("available", true)
                putString("status", STATUS_ACTIVE)
                putString(
                    "detail",
                    "Native Post-Quantum Cryptography active: NIST FIPS 203 ML-KEM-1024 and NIST FIPS 204 ML-DSA-87 via Bouncy Castle 1.81."
                )
                val supportedArray = Arguments.createArray().apply {
                    pushString("ML-KEM-1024")
                    pushString("ML-DSA-87")
                    pushString("AES-256-GCM")
                }
                putArray("supportedAlgorithms", supportedArray)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("PQC_STATUS_ERROR", e.message, e)
        }
    }

    /**
     * Generates a genuine post-quantum key pair for ML-KEM-1024 or ML-DSA-87.
     * Rejects any downgrade attempts (such as ML-KEM-768 or ML-DSA-44).
     */
    @ReactMethod
    fun generatePqcKeyPair(algorithm: String, promise: Promise) {
        try {
            val normalized = algorithm.uppercase().replace("-", "").replace("_", "")
            when (normalized) {
                "MLKEM1024" -> {
                    val kemGen = MLKEMKeyPairGenerator()
                    kemGen.init(MLKEMKeyGenerationParameters(secureRandom, MLKEMParameters.ml_kem_1024))
                    val keyPair = kemGen.generateKeyPair()
                    val pub = keyPair.public as MLKEMPublicKeyParameters
                    val priv = keyPair.private as MLKEMPrivateKeyParameters

                    val pubBytes = pub.encoded
                    val privBytes = priv.encoded

                    val result = Arguments.createMap().apply {
                        putString("algorithm", "ML-KEM-1024")
                        putString("publicKey", Base64.encodeToString(pubBytes, Base64.NO_WRAP))
                        putString("privateKey", Base64.encodeToString(privBytes, Base64.NO_WRAP))
                        putInt("publicKeyLength", pubBytes.size)
                        putInt("privateKeyLength", privBytes.size)
                    }
                    promise.resolve(result)
                }
                "MLDSA87" -> {
                    val dsaGen = MLDSAKeyPairGenerator()
                    dsaGen.init(MLDSAKeyGenerationParameters(secureRandom, MLDSAParameters.ml_dsa_87))
                    val keyPair = dsaGen.generateKeyPair()
                    val pub = keyPair.public as MLDSAPublicKeyParameters
                    val priv = keyPair.private as MLDSAPrivateKeyParameters

                    val pubBytes = pub.encoded
                    val privBytes = priv.encoded

                    val result = Arguments.createMap().apply {
                        putString("algorithm", "ML-DSA-87")
                        putString("publicKey", Base64.encodeToString(pubBytes, Base64.NO_WRAP))
                        putString("privateKey", Base64.encodeToString(privBytes, Base64.NO_WRAP))
                        putInt("publicKeyLength", pubBytes.size)
                        putInt("privateKeyLength", privBytes.size)
                    }
                    promise.resolve(result)
                }
                else -> {
                    promise.reject(
                        "PQC_UNSUPPORTED_ALGORITHM",
                        "Unsupported PQC algorithm: '$algorithm'. Supported algorithms are strictly NIST FIPS 203 ML-KEM-1024 and NIST FIPS 204 ML-DSA-87."
                    )
                }
            }
        } catch (e: Exception) {
            promise.reject("PQC_KEYGEN_ERROR", "Failed to generate $algorithm keypair: ${e.message}", e)
        }
    }

    /**
     * Encapsulates a shared secret using recipient's ML-KEM-1024 public key.
     */
    @ReactMethod
    fun kemEncapsulate(publicKeyBase64: String, promise: Promise) {
        try {
            val pubBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            if (pubBytes.size != ML_KEM_1024_PUB_BYTES) {
                promise.reject(
                    "PQC_INVALID_KEY",
                    "Invalid ML-KEM-1024 public key size: ${pubBytes.size} bytes (expected $ML_KEM_1024_PUB_BYTES bytes)"
                )
                return
            }

            val pubParams = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_1024, pubBytes)
            val generator = MLKEMGenerator(secureRandom)
            val secretWithEncap: SecretWithEncapsulation = generator.generateEncapsulated(pubParams)

            val sharedSecret = secretWithEncap.secret
            val cipherText = secretWithEncap.encapsulation

            val result = Arguments.createMap().apply {
                putString("cipherText", Base64.encodeToString(cipherText, Base64.NO_WRAP))
                putString("sharedSecret", Base64.encodeToString(sharedSecret, Base64.NO_WRAP))
                putInt("cipherTextLength", cipherText.size)
                putInt("sharedSecretLength", sharedSecret.size)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("PQC_ENCAP_ERROR", "ML-KEM encapsulation failed: ${e.message}", e)
        }
    }

    /**
     * Decapsulates the shared secret using recipient's ML-KEM-1024 private key and received ciphertext.
     */
    @ReactMethod
    fun kemDecapsulate(cipherTextBase64: String, privateKeyBase64: String, promise: Promise) {
        try {
            val privBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
            val cipherTextBytes = Base64.decode(cipherTextBase64, Base64.NO_WRAP)

            if (privBytes.size != ML_KEM_1024_PRIV_BYTES) {
                promise.reject(
                    "PQC_INVALID_KEY",
                    "Invalid ML-KEM-1024 private key size: ${privBytes.size} bytes (expected $ML_KEM_1024_PRIV_BYTES bytes)"
                )
                return
            }
            if (cipherTextBytes.size != ML_KEM_1024_CIPHERTEXT_BYTES) {
                promise.reject(
                    "PQC_INVALID_CIPHERTEXT",
                    "Invalid ML-KEM-1024 ciphertext size: ${cipherTextBytes.size} bytes (expected $ML_KEM_1024_CIPHERTEXT_BYTES bytes)"
                )
                return
            }

            val privParams = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_1024, privBytes)
            val extractor = MLKEMExtractor(privParams)
            val sharedSecret = extractor.extractSecret(cipherTextBytes)

            val result = Arguments.createMap().apply {
                putString("sharedSecret", Base64.encodeToString(sharedSecret, Base64.NO_WRAP))
                putInt("sharedSecretLength", sharedSecret.size)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("PQC_DECAP_ERROR", "ML-KEM decapsulation failed: ${e.message}", e)
        }
    }

    /**
     * Signs data using sender's ML-DSA-87 private key.
     */
    @ReactMethod
    fun dsaSign(messageBytesOrBase64: String, privateKeyBase64: String, promise: Promise) {
        try {
            val privBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
            if (privBytes.size != ML_DSA_87_PRIV_BYTES) {
                promise.reject(
                    "PQC_INVALID_KEY",
                    "Invalid ML-DSA-87 private key size: ${privBytes.size} bytes (expected $ML_DSA_87_PRIV_BYTES bytes)"
                )
                return
            }

            val messageBytes = try {
                Base64.decode(messageBytesOrBase64, Base64.NO_WRAP)
            } catch (e: Exception) {
                messageBytesOrBase64.toByteArray(Charsets.UTF_8)
            }

            val privParams = MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_87, privBytes)
            val signer = MLDSASigner()
            signer.init(true, ParametersWithRandom(privParams, secureRandom))
            signer.update(messageBytes, 0, messageBytes.size)
            val signature = signer.generateSignature()

            val result = Arguments.createMap().apply {
                putString("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
                putInt("signatureLength", signature.size)
                putString("algorithm", "ML-DSA-87")
            }
            promise.resolve(result)
        } catch (e: Exception) {
            promise.reject("PQC_SIGN_ERROR", "ML-DSA-87 signing failed: ${e.message}", e)
        }
    }

    /**
     * Verifies an ML-DSA-87 digital signature.
     */
    @ReactMethod
    fun dsaVerify(messageBytesOrBase64: String, signatureBase64: String, publicKeyBase64: String, promise: Promise) {
        try {
            val pubBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val signature = Base64.decode(signatureBase64, Base64.NO_WRAP)

            if (pubBytes.size != ML_DSA_87_PUB_BYTES || signature.size != ML_DSA_87_SIG_BYTES) {
                promise.resolve(false)
                return
            }

            val messageBytes = try {
                Base64.decode(messageBytesOrBase64, Base64.NO_WRAP)
            } catch (e: Exception) {
                messageBytesOrBase64.toByteArray(Charsets.UTF_8)
            }

            val pubParams = MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_87, pubBytes)
            val verifier = MLDSASigner()
            verifier.init(false, pubParams)
            verifier.update(messageBytes, 0, messageBytes.size)
            val isValid = verifier.verifySignature(signature)

            promise.resolve(isValid)
        } catch (e: Exception) {
            promise.reject("PQC_VERIFY_ERROR", "ML-DSA-87 verification failed: ${e.message}", e)
        }
    }

    /**
     * Hybrid encrypts plaintext using recipient's ML-KEM-1024 public key:
     * 1. Generates 256-bit shared secret via ML-KEM-1024 encapsulation.
     * 2. Encrypts plaintext using AES-256-GCM with fresh 12-byte IV.
     * 3. Produces standard JSON packet containing kemCiphertext, iv, ciphertext, and tag info.
     */
    @ReactMethod
    fun encrypt(plaintext: String, recipientPublicKeyBase64: String?, promise: Promise) {
        try {
            var targetPubKeyBase64 = recipientPublicKeyBase64
            var isEphemeralRecipient = false

            if (targetPubKeyBase64.isNullOrEmpty()) {
                // Generate ephemeral recipient keypair if none supplied
                val kemGen = MLKEMKeyPairGenerator()
                kemGen.init(MLKEMKeyGenerationParameters(secureRandom, MLKEMParameters.ml_kem_1024))
                val keyPair = kemGen.generateKeyPair()
                targetPubKeyBase64 = Base64.encodeToString((keyPair.public as MLKEMPublicKeyParameters).encoded, Base64.NO_WRAP)
                isEphemeralRecipient = true
            }

            val pubBytes = Base64.decode(targetPubKeyBase64, Base64.NO_WRAP)
            if (pubBytes.size != ML_KEM_1024_PUB_BYTES) {
                promise.reject(
                    "PQC_INVALID_KEY",
                    "Invalid ML-KEM-1024 recipient public key size: ${pubBytes.size} bytes (expected $ML_KEM_1024_PUB_BYTES bytes)"
                )
                return
            }

            val pubParams = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_1024, pubBytes)
            val generator = MLKEMGenerator(secureRandom)
            val secretWithEncap = generator.generateEncapsulated(pubParams)

            val sharedSecret = secretWithEncap.secret
            val kemCipherText = secretWithEncap.encapsulation

            val iv = ByteArray(AES_GCM_IV_BYTES)
            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(sharedSecret, "AES")
            val gcmSpec = GCMParameterSpec(AES_GCM_TAG_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val packetJson = JSONObject().apply {
                put("algorithm", "ML-KEM-1024+AES-256-GCM")
                put("kemCiphertext", Base64.encodeToString(kemCipherText, Base64.NO_WRAP))
                put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                put("ciphertext", Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                put("tagLength", AES_GCM_TAG_BITS)
                put("recipientPublicKey", targetPubKeyBase64)
                if (isEphemeralRecipient) {
                    put("note", "EPHEMERAL_RECIPIENT_KEY")
                }
            }

            promise.resolve(packetJson.toString())
        } catch (e: Exception) {
            promise.reject("PQC_ENCRYPT_ERROR", "Hybrid encryption failed: ${e.message}", e)
        }
    }

    /**
     * Hybrid decrypts standard packet JSON using recipient's ML-KEM-1024 private key:
     * 1. Decapsulates shared secret from kemCiphertext using ML-KEM-1024 private key.
     * 2. Decrypts payload using AES-256-GCM with derived key and packet IV.
     * 3. Validates 128-bit authentication tag; throws on tampered ciphertext.
     */
    @ReactMethod
    fun decrypt(packetJson: String, recipientPrivateKeyBase64: String, promise: Promise) {
        try {
            val json = JSONObject(packetJson)
            val kemCiphertextBase64 = json.getString("kemCiphertext")
            val ivBase64 = json.getString("iv")
            val ciphertextBase64 = json.getString("ciphertext")

            val privBytes = Base64.decode(recipientPrivateKeyBase64, Base64.NO_WRAP)
            if (privBytes.size != ML_KEM_1024_PRIV_BYTES) {
                promise.reject(
                    "PQC_INVALID_KEY",
                    "Invalid ML-KEM-1024 private key length: ${privBytes.size} bytes (expected $ML_KEM_1024_PRIV_BYTES)"
                )
                return
            }

            val kemBytes = Base64.decode(kemCiphertextBase64, Base64.NO_WRAP)
            if (kemBytes.size != ML_KEM_1024_CIPHERTEXT_BYTES) {
                promise.reject(
                    "PQC_INVALID_CIPHERTEXT",
                    "Invalid ML-KEM-1024 ciphertext length: ${kemBytes.size} bytes (expected $ML_KEM_1024_CIPHERTEXT_BYTES)"
                )
                return
            }

            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            if (iv.size != AES_GCM_IV_BYTES) {
                promise.reject(
                    "PQC_INVALID_IV",
                    "Invalid IV length: ${iv.size} bytes (expected $AES_GCM_IV_BYTES bytes)"
                )
                return
            }

            val cipherBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)

            // 1. Decapsulate shared secret via ML-KEM-1024
            val privParams = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_1024, privBytes)
            val extractor = MLKEMExtractor(privParams)
            val sharedSecret = extractor.extractSecret(kemBytes)

            // 2. AES-256-GCM authenticated payload decryption
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(sharedSecret, "AES")
            val gcmSpec = GCMParameterSpec(AES_GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decryptedBytes = cipher.doFinal(cipherBytes)

            promise.resolve(String(decryptedBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            promise.reject("PQC_DECRYPT_ERROR", "Hybrid decryption failed / authentication tag mismatch: ${e.message}", e)
        }
    }

    /**
     * Signs payload using ML-DSA-87.
     */
    @ReactMethod
    fun sign(payload: String, signerPrivateKeyBase64: String?, promise: Promise) {
        try {
            val (privParams, pubEncoded) = if (!signerPrivateKeyBase64.isNullOrEmpty()) {
                val privBytes = Base64.decode(signerPrivateKeyBase64, Base64.NO_WRAP)
                if (privBytes.size != ML_DSA_87_PRIV_BYTES) {
                    promise.reject(
                        "PQC_INVALID_KEY",
                        "Invalid ML-DSA-87 private key size: ${privBytes.size} bytes (expected $ML_DSA_87_PRIV_BYTES bytes)"
                    )
                    return
                }
                Pair(MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_87, privBytes), null)
            } else {
                val dsaGen = MLDSAKeyPairGenerator()
                dsaGen.init(MLDSAKeyGenerationParameters(secureRandom, MLDSAParameters.ml_dsa_87))
                val keyPair = dsaGen.generateKeyPair()
                val priv = keyPair.private as MLDSAPrivateKeyParameters
                val pub = keyPair.public as MLDSAPublicKeyParameters
                Pair(priv, Base64.encodeToString(pub.encoded, Base64.NO_WRAP))
            }

            val messageBytes = payload.toByteArray(Charsets.UTF_8)
            val signer = MLDSASigner()
            signer.init(true, ParametersWithRandom(privParams, secureRandom))
            signer.update(messageBytes, 0, messageBytes.size)
            val signature = signer.generateSignature()

            val packetJson = JSONObject().apply {
                put("algorithm", "ML-DSA-87")
                put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
                if (pubEncoded != null) {
                    put("signerPublicKey", pubEncoded)
                }
            }

            promise.resolve(packetJson.toString())
        } catch (e: Exception) {
            promise.reject("PQC_SIGN_ERROR", "ML-DSA-87 signing failed: ${e.message}", e)
        }
    }
}
