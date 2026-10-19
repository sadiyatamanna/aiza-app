import { NativeModules, Platform } from './nativeBridge';

const { AegisPqcModule } = NativeModules;

export interface PqcStatusInfo {
  available: boolean;
  status: 'PQC_NATIVE_IMPLEMENTATION_PENDING' | 'PQC_ACTIVE';
  detail: string;
  supportedAlgorithms: string[];
}

export interface PqcKeyPair {
  algorithm: 'ML-KEM-1024' | 'ML-DSA-87';
  publicKey: string;
  privateKey: string;
  publicKeyLength: number;
  privateKeyLength: number;
}

export interface KemEncapsulationResult {
  cipherText: string;
  sharedSecret: string;
  cipherTextLength: number;
  sharedSecretLength: number;
}

export interface KemDecapsulationResult {
  sharedSecret: string;
  sharedSecretLength: number;
}

export interface DsaSignatureResult {
  signature: string;
  signatureLength: number;
  algorithm: string;
}

/**
 * Post-Quantum Cryptography Service for AIZA / Aegis Safety.
 *
 * CRYPTOGRAPHIC ARCHITECTURE (NIST POST-QUANTUM STANDARDS):
 * ---------------------------------------------------------
 * 1. Key Encapsulation Mechanism: NIST FIPS 203 (ML-KEM-1024)
 *    Used to establish a quantum-safe 256-bit symmetric shared secret between sender and recipient.
 *    (Note: ML-KEM itself encapsulates a shared secret; it does NOT directly encrypt user plaintext).
 *
 * 2. Authenticated Symmetric Encryption: AES-256-GCM
 *    Used to encrypt the actual SOS emergency payload with the ML-KEM established shared secret,
 *    utilizing a fresh 96-bit (12-byte) CSPRNG IV and a 128-bit authentication tag for tamper resistance.
 *
 * 3. Digital Signatures: NIST FIPS 204 (ML-DSA-87)
 *    Used for cryptographic non-repudiation and integrity verification of the SOS payload.
 *
 * SECURITY INTEGRITY POLICY:
 * Strict prohibition of silent plaintext fallback, mock wrappers, RSA substitutes,
 * or empty signature placeholders. If native PQC is unavailable or fails, operations
 * fail explicitly with descriptive errors.
 */
export const PqcSecurityService = {
  /**
   * Checks whether native post-quantum cryptographic libraries are available and active.
   */
  async isPqcAvailable(): Promise<boolean> {
    if (Platform.OS === 'android' && AegisPqcModule?.isPqcAvailable) {
      try {
        return await AegisPqcModule.isPqcAvailable();
      } catch {
        return false;
      }
    }
    return false;
  },

  /**
   * Returns detailed status and diagnostics of PQC capabilities.
   * Reports PQC_ACTIVE only when real Android native implementation is operating.
   * Unsupported platforms (like iOS prior to Phase 8) report PQC_NATIVE_IMPLEMENTATION_PENDING.
   */
  async getPqcStatus(): Promise<PqcStatusInfo> {
    if (Platform.OS === 'android' && AegisPqcModule?.getPqcStatus) {
      try {
        return await AegisPqcModule.getPqcStatus();
      } catch (e: any) {
        return {
          available: false,
          status: 'PQC_NATIVE_IMPLEMENTATION_PENDING',
          detail: `Native PQC module error: ${e?.message || 'Unknown'}`,
          supportedAlgorithms: [],
        };
      }
    }
    return {
      available: false,
      status: 'PQC_NATIVE_IMPLEMENTATION_PENDING',
      detail:
        Platform.OS === 'ios'
          ? 'iOS native Post-Quantum Cryptography bridge pending (scheduled for future native Swift integration).'
          : 'Native Post-Quantum Cryptography pending native library integration.',
      supportedAlgorithms: [],
    };
  },

  /**
   * Generates a genuine post-quantum key pair for ML-KEM-1024 or ML-DSA-87.
   * Strictly enforces NIST FIPS parameter sets and throws on unsupported algorithms.
   */
  async generatePqcKeyPair(
    algorithm: 'ML-KEM-1024' | 'ML-DSA-87' | string = 'ML-KEM-1024'
  ): Promise<PqcKeyPair> {
    const isAvail = await this.isPqcAvailable();
    if (!isAvail || !AegisPqcModule?.generatePqcKeyPair) {
      throw new Error(
        'PQC_UNAVAILABLE: Native Post-Quantum Cryptography module is not available (PQC_NATIVE_IMPLEMENTATION_PENDING).'
      );
    }
    const normalized = algorithm.toUpperCase().replace(/[-_]/g, '');
    if (normalized !== 'MLKEM1024' && normalized !== 'MLDSA87') {
      throw new Error(
        `PQC_UNSUPPORTED_ALGORITHM: Unsupported PQC algorithm '${algorithm}'. Supported: ML-KEM-1024, ML-DSA-87.`
      );
    }
    return await AegisPqcModule.generatePqcKeyPair(algorithm);
  },

  /**
   * Encapsulates a shared secret using recipient's ML-KEM-1024 public key.
   */
  async kemEncapsulate(publicKeyBase64: string): Promise<KemEncapsulationResult> {
    const isAvail = await this.isPqcAvailable();
    if (!isAvail || !AegisPqcModule?.kemEncapsulate) {
      throw new Error('PQC_UNAVAILABLE: ML-KEM-1024 encapsulation requires active native PQC module.');
    }
    return await AegisPqcModule.kemEncapsulate(publicKeyBase64);
  },

  /**
   * Decapsulates the shared secret using recipient's ML-KEM-1024 private key and received ciphertext.
   */
  async kemDecapsulate(
    cipherTextBase64: string,
    privateKeyBase64: string
  ): Promise<KemDecapsulationResult> {
    const isAvail = await this.isPqcAvailable();
    if (!isAvail || !AegisPqcModule?.kemDecapsulate) {
      throw new Error('PQC_UNAVAILABLE: ML-KEM-1024 decapsulation requires active native PQC module.');
    }
    return await AegisPqcModule.kemDecapsulate(cipherTextBase64, privateKeyBase64);
  },

  /**
   * Signs message using sender's ML-DSA-87 private key.
   */
  async dsaSign(message: string, privateKeyBase64: string): Promise<DsaSignatureResult> {
    const isAvail = await this.isPqcAvailable();
    if (!isAvail || !AegisPqcModule?.dsaSign) {
      throw new Error('PQC_UNAVAILABLE: ML-DSA-87 signing requires active native PQC module.');
    }
    return await AegisPqcModule.dsaSign(message, privateKeyBase64);
  },

  /**
   * Verifies an ML-DSA-87 digital signature against public key.
   */
  async dsaVerify(
    message: string,
    signatureBase64: string,
    publicKeyBase64: string
  ): Promise<boolean> {
    const isAvail = await this.isPqcAvailable();
    if (!isAvail || !AegisPqcModule?.dsaVerify) {
      throw new Error('PQC_UNAVAILABLE: ML-DSA-87 verification requires active native PQC module.');
    }
    return await AegisPqcModule.dsaVerify(message, signatureBase64, publicKeyBase64);
  },

  /**
   * Encrypts plaintext via hybrid ML-KEM-1024 + AES-256-GCM.
   * Prohibits silent plaintext fallback: if PQC is unavailable, throws an explicit error.
   */
  async encryptPayload(plaintext: string, recipientPublicKeyBase64?: string): Promise<string> {
    const isAvail = await this.isPqcAvailable();
    if (!isAvail || !AegisPqcModule?.encrypt) {
      throw new Error(
        'PQC_UNAVAILABLE: Cannot encrypt payload without active native PQC module. Plaintext fallback is strictly prohibited.'
      );
    }
    return await AegisPqcModule.encrypt(plaintext, recipientPublicKeyBase64 || null);
  },

  /**
   * Decrypts packet JSON via hybrid ML-KEM-1024 decapsulation and AES-256-GCM authenticated decryption.
   * Fails explicitly if authentication tag mismatches (tampered ciphertext).
   */
  async decryptPayload(packetJson: string, recipientPrivateKeyBase64: string): Promise<string> {
    const isAvail = await this.isPqcAvailable();
    if (!isAvail || !AegisPqcModule?.decrypt) {
      throw new Error('PQC_UNAVAILABLE: Cannot decrypt payload without active native PQC module.');
    }
    return await AegisPqcModule.decrypt(packetJson, recipientPrivateKeyBase64);
  },

  /**
   * Signs payload using ML-DSA-87.
   * Prohibits silent empty-signature fallback: if PQC is unavailable, throws an explicit error.
   */
  async signPayload(payload: string, signerPrivateKeyBase64?: string): Promise<string> {
    const isAvail = await this.isPqcAvailable();
    if (!isAvail || !AegisPqcModule?.sign) {
      throw new Error(
        'PQC_UNAVAILABLE: Cannot sign payload without active native PQC module. Empty signature fallback is strictly prohibited.'
      );
    }
    return await AegisPqcModule.sign(payload, signerPrivateKeyBase64 || null);
  },
};
