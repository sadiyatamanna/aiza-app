#import "AegisBackgroundServiceModule.h"
#import <UIKit/UIKit.h>

@interface AegisBackgroundServiceModule () {
  UIBackgroundTaskIdentifier _bgTaskId;
  BOOL _isRunning;
}
@end

@implementation AegisBackgroundServiceModule

RCT_EXPORT_MODULE(AegisBackgroundServiceModule);

+ (BOOL)requiresMainQueueSetup {
  return YES;
}

- (instancetype)init {
  self = [super init];
  if (self) {
    _bgTaskId = UIBackgroundTaskInvalid;
    _isRunning = NO;
  }
  return self;
}

RCT_EXPORT_METHOD(startService:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  if (_isRunning) {
    resolve(@{@"status": @"STARTED", @"running": @(YES)});
    return;
  }

  dispatch_async(dispatch_get_main_queue(), ^{
    self->_bgTaskId = [[UIApplication sharedApplication] beginBackgroundTaskWithName:@"AIZA_Emergency_Dispatch" expirationHandler:^{
      [[UIApplication sharedApplication] endBackgroundTask:self->_bgTaskId];
      self->_bgTaskId = UIBackgroundTaskInvalid;
      self->_isRunning = NO;
    }];
    self->_isRunning = YES;

    resolve(@{
      @"status": @"STARTED",
      @"running": @(YES),
      @"platform": @"iOS",
      @"policy": @"Bounded by Apple Background Task & CoreLocation background policies."
    });
  });
}

RCT_EXPORT_METHOD(stopService:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  dispatch_async(dispatch_get_main_queue(), ^{
    if (self->_bgTaskId != UIBackgroundTaskInvalid) {
      [[UIApplication sharedApplication] endBackgroundTask:self->_bgTaskId];
      self->_bgTaskId = UIBackgroundTaskInvalid;
    }
    self->_isRunning = NO;
    resolve(@{@"status": @"STOPPED", @"running": @(NO)});
  });
}

RCT_EXPORT_METHOD(isServiceRunning:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  resolve(@(_isRunning));
}

@end
