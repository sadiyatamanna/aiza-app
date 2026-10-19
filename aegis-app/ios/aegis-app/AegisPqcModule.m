#import "AegisPqcModule.h"

@implementation AegisPqcModule

RCT_EXPORT_MODULE(AegisPqcModule);

+ (BOOL)requiresMainQueueSetup {
  return NO;
}

RCT_EXPORT_METHOD(isPqcAvailable:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  // iOS native PQC is unavailable until native Swift/C NIST FIPS 203/204 integration.
  resolve(@(NO));
}

RCT_EXPORT_METHOD(getPqcStatus:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  resolve(@{
    @"available": @(NO),
    @"status": @"PQC_NATIVE_IMPLEMENTATION_PENDING",
    @"detail": @"iOS native Post-Quantum Cryptography bridge pending (scheduled for future native Swift integration).",
    @"supportedAlgorithms": @[]
  });
}

RCT_EXPORT_METHOD(generatePqcKeyPair:(NSString *)algorithm
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  reject(@"PQC_UNAVAILABLE", @"Native Post-Quantum Cryptography module is not available on iOS (PQC_NATIVE_IMPLEMENTATION_PENDING).", nil);
}

RCT_EXPORT_METHOD(kemEncapsulate:(NSString *)publicKeyBase64
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  reject(@"PQC_UNAVAILABLE", @"ML-KEM-1024 encapsulation requires active native PQC module.", nil);
}

RCT_EXPORT_METHOD(kemDecapsulate:(NSString *)cipherTextBase64
                  privateKeyBase64:(NSString *)privateKeyBase64
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  reject(@"PQC_UNAVAILABLE", @"ML-KEM-1024 decapsulation requires active native PQC module.", nil);
}

RCT_EXPORT_METHOD(dsaSign:(NSString *)message
                  privateKeyBase64:(NSString *)privateKeyBase64
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  reject(@"PQC_UNAVAILABLE", @"ML-DSA-87 signing requires active native PQC module.", nil);
}

RCT_EXPORT_METHOD(dsaVerify:(NSString *)message
                  signatureBase64:(NSString *)signatureBase64
                  publicKeyBase64:(NSString *)publicKeyBase64
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  reject(@"PQC_UNAVAILABLE", @"ML-DSA-87 verification requires active native PQC module.", nil);
}

RCT_EXPORT_METHOD(encrypt:(NSString *)plaintext
                  recipientPublicKey:(NSString *)recipientPublicKey
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  reject(@"PQC_UNAVAILABLE", @"Cannot encrypt payload without active native PQC module. Plaintext fallback is strictly prohibited.", nil);
}

RCT_EXPORT_METHOD(decrypt:(NSString *)packetJson
                  recipientPrivateKey:(NSString *)recipientPrivateKey
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  reject(@"PQC_UNAVAILABLE", @"Cannot decrypt payload without active native PQC module.", nil);
}

RCT_EXPORT_METHOD(sign:(NSString *)payload
                  signerPrivateKey:(NSString *)signerPrivateKey
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  reject(@"PQC_UNAVAILABLE", @"Cannot sign payload without active native PQC module. Empty signature fallback is strictly prohibited.", nil);
}

@end
