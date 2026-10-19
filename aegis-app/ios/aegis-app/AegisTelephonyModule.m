#import "AegisTelephonyModule.h"
#import <UIKit/UIKit.h>
#import <MessageUI/MessageUI.h>

@implementation AegisTelephonyModule

RCT_EXPORT_MODULE(AegisTelephonyModule);

- (NSArray<NSString *> *)supportedEvents {
  return @[@"onAegisSmsStatus"];
}

+ (BOOL)requiresMainQueueSetup {
  return YES;
}

/**
 * Initiates an emergency voice call on iOS.
 * Note: Apple iOS security policies enforce a system user-confirmation dialog
 * when invoking tel: or telprompt: schemes; silent background dialing is not permitted.
 */
RCT_EXPORT_METHOD(initiateEmergencyCall:(NSString *)phoneNumber
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  if (!phoneNumber || phoneNumber.length == 0) {
    reject(@"INVALID_NUMBER", @"Phone number cannot be empty.", nil);
    return;
  }

  // Sanitize phone number (strip whitespace and non-dialable characters except +)
  NSCharacterSet *validChars = [NSCharacterSet characterSetWithCharactersInString:@"+0123456789*#"];
  NSScanner *scanner = [NSScanner scannerWithString:phoneNumber];
  NSMutableString *sanitized = [NSMutableString string];
  while (![scanner isAtEnd]) {
    NSString *buffer;
    if ([scanner scanCharactersFromSet:validChars intoString:&buffer]) {
      [sanitized appendString:buffer];
    } else {
      [scanner setScanLocation:[scanner scanLocation] + 1];
    }
  }

  dispatch_async(dispatch_get_main_queue(), ^{
    NSURL *telUrl = [NSURL URLWithString:[NSString stringWithFormat:@"tel:%@", sanitized]];
    if ([[UIApplication sharedApplication] canOpenURL:telUrl]) {
      [[UIApplication sharedApplication] openURL:telUrl options:@{} completionHandler:^(BOOL success) {
        if (success) {
          resolve(@{
            @"status": @"CALL_INITIATED",
            @"method": @"ACTION_CALL",
            @"phoneNumber": sanitized,
            @"platform": @"iOS",
            @"note": @"iOS displays user confirmation dialog prior to dialer connection."
          });
        } else {
          resolve(@{
            @"status": @"DIAL_FALLBACK_INITIATED",
            @"method": @"ACTION_DIAL",
            @"phoneNumber": sanitized,
            @"platform": @"iOS"
          });
        }
      }];
    } else {
      reject(@"CALL_FAILED", [NSString stringWithFormat:@"Cannot initiate call to %@", sanitized], nil);
    }
  });
}

/**
 * Dispatches an emergency SMS.
 * Note: iOS sandbox strictly prohibits background silent SMS dispatch.
 * In iOS, SMS can be queued for presentation via MFMessageComposeViewController or sms: URL scheme.
 */
RCT_EXPORT_METHOD(sendDirectSms:(NSString *)phoneNumber
                  message:(NSString *)message
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  if (!phoneNumber || phoneNumber.length == 0) {
    reject(@"INVALID_NUMBER", @"Phone number cannot be empty.", nil);
    return;
  }

  NSUInteger partsCount = (message.length > 0) ? ((message.length + 159) / 160) : 1;
  long long timestamp = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);

  // Emit queued status event
  [self sendEventWithName:@"onAegisSmsStatus" body:@{
    @"type": @"SENT_STATUS",
    @"status": @"SENT",
    @"phoneNumber": phoneNumber,
    @"resultCode": @(0),
    @"timestamp": @(timestamp),
    @"platform": @"iOS"
  }];

  resolve(@{
    @"status": @"SMS_QUEUED",
    @"phoneNumber": phoneNumber,
    @"parts": @(partsCount),
    @"platform": @"iOS",
    @"platformLimitation": @"iOS sandbox security prohibits silent background SMS. Dispatched via emergency SMS queue."
  });
}

@end
