#import "AegisMotionModule.h"
#import <CoreMotion/CoreMotion.h>

static const double kShakeAccelerationThresholdG = 2.3;
static const long long kShakeCooldownMs = 5000;

@interface AegisMotionModule () {
  CMMotionManager *_motionManager;
  BOOL _isMonitoring;
  long long _lastShakeTimestamp;
}
@end

@implementation AegisMotionModule

RCT_EXPORT_MODULE(AegisMotionModule);

- (NSArray<NSString *> *)supportedEvents {
  return @[@"onAegisShakeSosTrigger"];
}

+ (BOOL)requiresMainQueueSetup {
  return NO;
}

- (instancetype)init {
  self = [super init];
  if (self) {
    _motionManager = [[CMMotionManager alloc] init];
    _isMonitoring = NO;
    _lastShakeTimestamp = 0;
  }
  return self;
}

RCT_EXPORT_METHOD(isMotionAvailable:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  resolve(@([_motionManager isAccelerometerAvailable]));
}

RCT_EXPORT_METHOD(startShakeDetection:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  if (![_motionManager isAccelerometerAvailable]) {
    reject(@"ACCELEROMETER_UNAVAILABLE", @"Accelerometer motion sensing is not available on this iOS device.", nil);
    return;
  }

  if (_isMonitoring) {
    resolve(@{@"status": @"ALREADY_MONITORING", @"active": @(YES)});
    return;
  }

  _motionManager.accelerometerUpdateInterval = 0.05; // 20 Hz
  NSOperationQueue *queue = [[NSOperationQueue alloc] init];
  queue.name = @"com.aiza.motionQueue";

  __weak typeof(self) weakSelf = self;
  [_motionManager startAccelerometerUpdatesToQueue:queue withHandler:^(CMAccelerometerData * _Nullable data, NSError * _Nullable error) {
    if (error || !data) return;
    [weakSelf evaluateAccelerometerData:data];
  }];

  _isMonitoring = YES;
  resolve(@{
    @"status": @"MONITORING_STARTED",
    @"active": @(YES),
    @"platform": @"iOS CoreMotion"
  });
}

RCT_EXPORT_METHOD(stopShakeDetection:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  if (_isMonitoring && _motionManager) {
    [_motionManager stopAccelerometerUpdates];
    _isMonitoring = NO;
  }
  resolve(@{@"status": @"MONITORING_STOPPED", @"active": @(NO)});
}

RCT_EXPORT_METHOD(isShakeDetectionActive:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  resolve(@(_isMonitoring));
}

- (void)evaluateAccelerometerData:(CMAccelerometerData *)data {
  double x = data.acceleration.x;
  double y = data.acceleration.y;
  double z = data.acceleration.z;

  // Compute 3D vector acceleration magnitude in Gs
  double gForce = sqrt(x * x + y * y + z * z);

  if (gForce >= kShakeAccelerationThresholdG) {
    long long now = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
    if (now - _lastShakeTimestamp >= kShakeCooldownMs) {
      _lastShakeTimestamp = now;
      [self sendEventWithName:@"onAegisShakeSosTrigger" body:@{
        @"source": @"SHAKE",
        @"triggerType": @"CORE_MOTION_ACCELERATION",
        @"accelerationG": @(gForce),
        @"timestamp": @(now),
        @"platform": @"iOS"
      }];
    }
  }
}

@end
