#import "AegisLocationModule.h"

@interface AegisLocationModule () {
  CLLocationManager *_locationManager;
  BOOL _isTracking;
  NSMutableArray<RCTPromiseResolveBlock> *_locationResolvers;
  NSMutableArray<RCTPromiseRejectBlock> *_locationRejecters;
}
@end

@implementation AegisLocationModule

RCT_EXPORT_MODULE(AegisLocationModule);

- (NSArray<NSString *> *)supportedEvents {
  return @[@"onAegisLocationUpdate"];
}

+ (BOOL)requiresMainQueueSetup {
  return YES;
}

- (instancetype)init {
  self = [super init];
  if (self) {
    _locationManager = [[CLLocationManager alloc] init];
    _locationManager.delegate = self;
    _locationManager.desiredAccuracy = kCLLocationAccuracyBest;
    _locationManager.distanceFilter = 5.0; // 5 meters
    _isTracking = NO;
    _locationResolvers = [NSMutableArray array];
    _locationRejecters = [NSMutableArray array];
  }
  return self;
}

RCT_EXPORT_METHOD(requestPermissions:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  dispatch_async(dispatch_get_main_queue(), ^{
    [self->_locationManager requestAlwaysAuthorization];
    resolve(@{@"status": @"REQUESTED"});
  });
}

RCT_EXPORT_METHOD(getCurrentPosition:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  dispatch_async(dispatch_get_main_queue(), ^{
    CLLocation *loc = self->_locationManager.location;
    // Check if cached location is fresh (within 30 seconds)
    if (loc && fabs([loc.timestamp timeIntervalSinceNow]) < 30.0) {
      resolve(@{
        @"latitude": @(loc.coordinate.latitude),
        @"longitude": @(loc.coordinate.longitude),
        @"accuracy": @(loc.horizontalAccuracy),
        @"altitude": @(loc.altitude),
        @"speed": @(loc.speed >= 0 ? loc.speed : 0),
        @"timestamp": @((long long)([loc.timestamp timeIntervalSince1970] * 1000.0)),
        @"provider": @"iOS CoreLocation"
      });
      return;
    }

    [self->_locationResolvers addObject:resolve];
    [self->_locationRejecters addObject:reject];
    [self->_locationManager requestLocation];
  });
}

RCT_EXPORT_METHOD(startContinuousTracking:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  dispatch_async(dispatch_get_main_queue(), ^{
    self->_locationManager.allowsBackgroundLocationUpdates = YES;
    self->_locationManager.pausesLocationUpdatesAutomatically = NO;
    [self->_locationManager startUpdatingLocation];
    self->_isTracking = YES;
    resolve(@{@"status": @"TRACKING_STARTED", @"active": @(YES)});
  });
}

RCT_EXPORT_METHOD(stopContinuousTracking:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  dispatch_async(dispatch_get_main_queue(), ^{
    [self->_locationManager stopUpdatingLocation];
    self->_isTracking = NO;
    resolve(@{@"status": @"TRACKING_STOPPED", @"active": @(NO)});
  });
}

#pragma mark - CLLocationManagerDelegate

- (void)locationManager:(CLLocationManager *)manager didUpdateLocations:(NSArray<CLLocation *> *)locations {
  CLLocation *loc = locations.lastObject;
  if (!loc) return;

  NSDictionary *locDict = @{
    @"latitude": @(loc.coordinate.latitude),
    @"longitude": @(loc.coordinate.longitude),
    @"accuracy": @(loc.horizontalAccuracy),
    @"altitude": @(loc.altitude),
    @"speed": @(loc.speed >= 0 ? loc.speed : 0),
    @"timestamp": @((long long)([loc.timestamp timeIntervalSince1970] * 1000.0)),
    @"provider": @"iOS CoreLocation"
  };

  // Resolve any pending one-shot queries
  for (RCTPromiseResolveBlock resolve in _locationResolvers) {
    resolve(locDict);
  }
  [_locationResolvers removeAllObjects];
  [_locationRejecters removeAllObjects];

  // Emit event if tracking
  if (_isTracking) {
    [self sendEventWithName:@"onAegisLocationUpdate" body:locDict];
  }
}

- (void)locationManager:(CLLocationManager *)manager didFailWithError:(NSError *)error {
  for (RCTPromiseRejectBlock reject in _locationRejecters) {
    reject(@"LOCATION_FAILED", error.localizedDescription, error);
  }
  [_locationResolvers removeAllObjects];
  [_locationRejecters removeAllObjects];
}

@end
