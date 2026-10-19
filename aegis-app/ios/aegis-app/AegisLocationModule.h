#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>
#import <CoreLocation/CoreLocation.h>

@interface AegisLocationModule : RCTEventEmitter <RCTBridgeModule, CLLocationManagerDelegate>

@end
