package com.example.bridge;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;

/**
 * JVM CLI Runner for Bouncy Castle 1.81 Post-Quantum Cryptography.
 * Bridges Node.js test suites directly to genuine native Java Bouncy Castle 1.81
 * without mocks, RSA fallbacks, or pseudo-crypto.
 */
public class AegisPqcJvmCli {
    private static final SecureRandom secureRandom = new SecureRandom();

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("No command provided");
            System.exit(1);
        }

        String cmd = args[0];
        try {
            switch (cmd) {
                case "isPqcAvailable":
                    System.out.println("true");
                    break;

                case "getPqcStatus":
                    System.out.println("{"
                        + "\"available\":true,"
                        + "\"status\":\"PQC_ACTIVE\","
                        + "\"detail\":\"Native Post-Quantum Cryptography active: NIST FIPS 203 ML-KEM-1024 and NIST FIPS 204 ML-DSA-87 via Bouncy Castle 1.81.\","
                        + "\"supportedAlgorithms\":[\"ML-KEM-1024\",\"ML-DSA-87\",\"AES-256-GCM\"]"
                        + "}");
                    break;

                case "generatePqcKeyPair": {
                    String algorithm = args[1];
                    String normalized = algorithm.toUpperCase().replace("-", "").replace("_", "");
                    if ("MLKEM1024".equals(normalized)) {
                        MLKEMKeyPairGenerator kemGen = new MLKEMKeyPairGenerator();
                        kemGen.init(new MLKEMKeyGenerationParameters(secureRandom, MLKEMParameters.ml_kem_1024));
                        var keyPair = kemGen.generateKeyPair();
                        var pub = (MLKEMPublicKeyParameters) keyPair.getPublic();
                        var priv = (MLKEMPrivateKeyParameters) keyPair.getPrivate();

                        byte[] pubBytes = pub.getEncoded();
                        byte[] privBytes = priv.getEncoded();
                        System.out.println("{"
                            + "\"algorithm\":\"ML-KEM-1024\","
                            + "\"publicKey\":\"" + Base64.getEncoder().encodeToString(pubBytes) + "\","
                            + "\"privateKey\":\"" + Base64.getEncoder().encodeToString(privBytes) + "\","
                            + "\"publicKeyLength\":" + pubBytes.length + ","
                            + "\"privateKeyLength\":" + privBytes.length
                            + "}");
                    } else if ("MLDSA87".equals(normalized)) {
                        MLDSAKeyPairGenerator dsaGen = new MLDSAKeyPairGenerator();
                        dsaGen.init(new MLDSAKeyGenerationParameters(secureRandom, MLDSAParameters.ml_dsa_87));
                        var keyPair = dsaGen.generateKeyPair();
                        var pub = (MLDSAPublicKeyParameters) keyPair.getPublic();
                        var priv = (MLDSAPrivateKeyParameters) keyPair.getPrivate();

                        byte[] pubBytes = pub.getEncoded();
                        byte[] privBytes = priv.getEncoded();
                        System.out.println("{"
                            + "\"algorithm\":\"ML-DSA-87\","
                            + "\"publicKey\":\"" + Base64.getEncoder().encodeToString(pubBytes) + "\","
                            + "\"privateKey\":\"" + Base64.getEncoder().encodeToString(privBytes) + "\","
                            + "\"publicKeyLength\":" + pubBytes.length + ","
                            + "\"privateKeyLength\":" + privBytes.length
                            + "}");
                    } else {
                        System.err.println("PQC_UNSUPPORTED_ALGORITHM: Unsupported PQC algorithm: " + algorithm);
                        System.exit(2);
                    }
                    break;
                }

                case "kemEncapsulate": {
                    byte[] pubBytes = Base64.getDecoder().decode(args[1]);
                    if (pubBytes.length != 1568) {
                        System.err.println("PQC_INVALID_KEY: Invalid ML-KEM-1024 public key size: " + pubBytes.length);
                        System.exit(3);
                    }
                    var pubParams = new MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_1024, pubBytes);
                    MLKEMGenerator generator = new MLKEMGenerator(secureRandom);
                    SecretWithEncapsulation encap = generator.generateEncapsulated(pubParams);

                    byte[] sharedSecret = encap.getSecret();
                    byte[] cipherText = encap.getEncapsulation();
                    System.out.println("{"
                        + "\"cipherText\":\"" + Base64.getEncoder().encodeToString(cipherText) + "\","
                        + "\"sharedSecret\":\"" + Base64.getEncoder().encodeToString(sharedSecret) + "\","
                        + "\"cipherTextLength\":" + cipherText.length + ","
                        + "\"sharedSecretLength\":" + sharedSecret.length
                        + "}");
                    break;
                }

                case "kemDecapsulate": {
                    byte[] cipherTextBytes = Base64.getDecoder().decode(args[1]);
                    byte[] privBytes = Base64.getDecoder().decode(args[2]);
                    if (privBytes.length != 3168) {
                        System.err.println("PQC_INVALID_KEY: Invalid ML-KEM-1024 private key size: " + privBytes.length);
                        System.exit(4);
                    }
                    if (cipherTextBytes.length != 1568) {
                        System.err.println("PQC_INVALID_CIPHERTEXT: Invalid ML-KEM-1024 ciphertext size: " + cipherTextBytes.length);
                        System.exit(5);
                    }

                    var privParams = new MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_1024, privBytes);
                    MLKEMExtractor extractor = new MLKEMExtractor(privParams);
                    byte[] sharedSecret = extractor.extractSecret(cipherTextBytes);
                    System.out.println("{"
                        + "\"sharedSecret\":\"" + Base64.getEncoder().encodeToString(sharedSecret) + "\","
                        + "\"sharedSecretLength\":" + sharedSecret.length
                        + "}");
                    break;
                }

                case "dsaSign": {
                    byte[] messageBytes = Base64.getDecoder().decode(args[1]);
                    byte[] privBytes = Base64.getDecoder().decode(args[2]);
                    if (privBytes.length != 4896) {
                        System.err.println("PQC_INVALID_KEY: Invalid ML-DSA-87 private key size: " + privBytes.length);
                        System.exit(6);
                    }

                    var privParams = new MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_87, privBytes);
                    MLDSASigner signer = new MLDSASigner();
                    signer.init(true, new ParametersWithRandom(privParams, secureRandom));
                    signer.update(messageBytes, 0, messageBytes.length);
                    byte[] signature = signer.generateSignature();
                    System.out.println("{"
                        + "\"signature\":\"" + Base64.getEncoder().encodeToString(signature) + "\","
                        + "\"signatureLength\":" + signature.length + ","
                        + "\"algorithm\":\"ML-DSA-87\""
                        + "}");
                    break;
                }

                case "dsaVerify": {
                    byte[] messageBytes = Base64.getDecoder().decode(args[1]);
                    byte[] signature = Base64.getDecoder().decode(args[2]);
                    byte[] pubBytes = Base64.getDecoder().decode(args[3]);

                    if (pubBytes.length != 2592 || signature.length != 4627) {
                        System.out.println("false");
                        break;
                    }

                    var pubParams = new MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_87, pubBytes);
                    MLDSASigner verifier = new MLDSASigner();
                    verifier.init(false, pubParams);
                    verifier.update(messageBytes, 0, messageBytes.length);
                    boolean isValid = verifier.verifySignature(signature);
                    System.out.println(isValid ? "true" : "false");
                    break;
                }

                case "encrypt": {
                    byte[] plaintextBytes = Base64.getDecoder().decode(args[1]);
                    String recipientPubKeyBase64 = args.length > 2 && !args[2].isEmpty() && !"null".equals(args[2]) ? args[2] : null;

                    if (recipientPubKeyBase64 == null) {
                        MLKEMKeyPairGenerator kemGen = new MLKEMKeyPairGenerator();
                        kemGen.init(new MLKEMKeyGenerationParameters(secureRandom, MLKEMParameters.ml_kem_1024));
                        var keyPair = kemGen.generateKeyPair();
                        recipientPubKeyBase64 = Base64.getEncoder().encodeToString(((MLKEMPublicKeyParameters) keyPair.getPublic()).getEncoded());
                    }

                    byte[] pubBytes = Base64.getDecoder().decode(recipientPubKeyBase64);
                    var pubParams = new MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_1024, pubBytes);
                    MLKEMGenerator generator = new MLKEMGenerator(secureRandom);
                    SecretWithEncapsulation encap = generator.generateEncapsulated(pubParams);

                    byte[] sharedSecret = encap.getSecret();
                    byte[] kemCiphertext = encap.getEncapsulation();

                    byte[] iv = new byte[12];
                    secureRandom.nextBytes(iv);

                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sharedSecret, "AES"), new GCMParameterSpec(128, iv));
                    byte[] ciphertext = cipher.doFinal(plaintextBytes);

                    System.out.println("{"
                        + "\"algorithm\":\"ML-KEM-1024+AES-256-GCM\","
                        + "\"kemCiphertext\":\"" + Base64.getEncoder().encodeToString(kemCiphertext) + "\","
                        + "\"iv\":\"" + Base64.getEncoder().encodeToString(iv) + "\","
                        + "\"ciphertext\":\"" + Base64.getEncoder().encodeToString(ciphertext) + "\","
                        + "\"tagLength\":128,"
                        + "\"recipientPublicKey\":\"" + recipientPubKeyBase64 + "\""
                        + "}");
                    break;
                }

                case "decrypt": {
                    String packetJsonStr = new String(Base64.getDecoder().decode(args[1]), "UTF-8");
                    byte[] privBytes = Base64.getDecoder().decode(args[2]);

                    // Simple JSON parsing without external dependencies
                    String kemCiphertextBase64 = extractJsonField(packetJsonStr, "kemCiphertext");
                    String ivBase64 = extractJsonField(packetJsonStr, "iv");
                    String ciphertextBase64 = extractJsonField(packetJsonStr, "ciphertext");

                    byte[] kemBytes = Base64.getDecoder().decode(kemCiphertextBase64);
                    byte[] iv = Base64.getDecoder().decode(ivBase64);
                    byte[] cipherBytes = Base64.getDecoder().decode(ciphertextBase64);

                    var privParams = new MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_1024, privBytes);
                    MLKEMExtractor extractor = new MLKEMExtractor(privParams);
                    byte[] sharedSecret = extractor.extractSecret(kemBytes);

                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sharedSecret, "AES"), new GCMParameterSpec(128, iv));
                    byte[] decrypted = cipher.doFinal(cipherBytes);

                    System.out.println(Base64.getEncoder().encodeToString(decrypted));
                    break;
                }

                case "sign": {
                    byte[] payloadBytes = Base64.getDecoder().decode(args[1]);
                    String signerPrivKeyBase64 = args.length > 2 && !args[2].isEmpty() && !"null".equals(args[2]) ? args[2] : null;
                    String signerPubKeyBase64 = null;
                    byte[] privBytes;

                    if (signerPrivKeyBase64 != null) {
                        privBytes = Base64.getDecoder().decode(signerPrivKeyBase64);
                    } else {
                        MLDSAKeyPairGenerator dsaGen = new MLDSAKeyPairGenerator();
                        dsaGen.init(new MLDSAKeyGenerationParameters(secureRandom, MLDSAParameters.ml_dsa_87));
                        var keyPair = dsaGen.generateKeyPair();
                        privBytes = ((MLDSAPrivateKeyParameters) keyPair.getPrivate()).getEncoded();
                        signerPubKeyBase64 = Base64.getEncoder().encodeToString(((MLDSAPublicKeyParameters) keyPair.getPublic()).getEncoded());
                    }

                    var privParams = new MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_87, privBytes);
                    MLDSASigner signer = new MLDSASigner();
                    signer.init(true, new ParametersWithRandom(privParams, secureRandom));
                    signer.update(payloadBytes, 0, payloadBytes.length);
                    byte[] signature = signer.generateSignature();

                    String pubPart = signerPubKeyBase64 != null ? ",\"signerPublicKey\":\"" + signerPubKeyBase64 + "\"" : "";
                    System.out.println("{"
                        + "\"algorithm\":\"ML-DSA-87\","
                        + "\"signature\":\"" + Base64.getEncoder().encodeToString(signature) + "\""
                        + pubPart
                        + "}");
                    break;
                }

                default:
                    System.err.println("Unknown command: " + cmd);
                    System.exit(9);
            }
        } catch (Exception e) {
            System.err.println("PQC_EXECUTION_ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            System.exit(10);
        }
    }

    private static String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\":\"";
        int start = json.indexOf(pattern);
        if (start == -1) {
            throw new IllegalArgumentException("Field not found: " + field);
        }
        start += pattern.length();
        int end = json.indexOf("\"", start);
        if (end == -1) {
            throw new IllegalArgumentException("Malformed JSON for field: " + field);
        }
        return json.substring(start, end);
    }
}
