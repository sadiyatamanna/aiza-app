#import "AegisBluetoothModule.h"

static NSString *const kAegisBleServiceUUIDString = @"0000FFE0-0000-1000-8000-00805F9B34FB";
static NSString *const kAegisBleCharUUIDString    = @"0000FFE1-0000-1000-8000-00805F9B34FB";
static const uint8_t kTriggerChar = 'S'; // ASCII 83
static const long long kTriggerCooldownMs = 5000;

@interface AegisBluetoothModule () {
  CBCentralManager *_centralManager;
  CBPeripheral *_connectedPeripheral;
  CBCharacteristic *_notifyCharacteristic;
  BOOL _isScanning;
  BOOL _isConnected;
  long long _lastTriggerTimestamp;
  NSMutableArray<CBPeripheral *> *_discoveredPeripherals;
}
@end

@implementation AegisBluetoothModule

RCT_EXPORT_MODULE(AegisBluetoothModule);

- (NSArray<NSString *> *)supportedEvents {
  return @[@"onAegisHardwareSosTrigger", @"onAegisBluetoothStatus"];
}

+ (BOOL)requiresMainQueueSetup {
  return YES;
}

- (instancetype)init {
  self = [super init];
  if (self) {
    _centralManager = [[CBCentralManager alloc] initWithDelegate:self queue:nil];
    _isScanning = NO;
    _isConnected = NO;
    _lastTriggerTimestamp = 0;
    _discoveredPeripherals = [NSMutableArray array];
  }
  return self;
}

#pragma mark - RCT Export Methods

RCT_EXPORT_METHOD(startBluetooth:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  BOOL available = (_centralManager.state != CBManagerStateUnsupported);
  BOOL enabled = (_centralManager.state == CBManagerStatePoweredOn);
  resolve(@{
    @"available": @(available),
    @"enabled": @(enabled),
    @"state": [self centralManagerStateString:_centralManager.state],
    @"platform": @"iOS",
    @"protocol": @"CoreBluetooth BLE GATT"
  });
}

RCT_EXPORT_METHOD(getBondedDevices:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  // iOS does not expose bonded devices for Bluetooth Classic SPP without Apple MFi certification.
  // Instead, retrieve known / connected BLE peripherals for service FFE0.
  NSMutableArray *deviceList = [NSMutableArray array];
  
  CBUUID *serviceUUID = [CBUUID UUIDWithString:kAegisBleServiceUUIDString];
  NSArray<CBPeripheral *> *connected = [_centralManager retrieveConnectedPeripheralsWithServices:@[serviceUUID]];
  for (CBPeripheral *p in connected) {
    [deviceList addObject:@{
      @"name": p.name ?: @"SOS-WEAR",
      @"address": p.identifier.UUIDString,
      @"isSosWear": @(YES),
      @"type": @"BLE_GATT"
    }];
  }

  for (CBPeripheral *p in _discoveredPeripherals) {
    BOOL alreadyIncluded = NO;
    for (NSDictionary *d in deviceList) {
      if ([d[@"address"] isEqualToString:p.identifier.UUIDString]) {
        alreadyIncluded = YES;
        break;
      }
    }
    if (!alreadyIncluded) {
      [deviceList addObject:@{
        @"name": p.name ?: @"SOS-WEAR",
        @"address": p.identifier.UUIDString,
        @"isSosWear": @([self isWearableName:p.name]),
        @"type": @"BLE_GATT"
      }];
    }
  }

  resolve(deviceList);
}

RCT_EXPORT_METHOD(connectDevice:(NSString *)deviceIdentifier
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  if (_centralManager.state != CBManagerStatePoweredOn) {
    reject(@"BT_POWERED_OFF", @"Bluetooth is powered off or unauthorized.", nil);
    return;
  }

  [self sendStatusEvent:@"CONNECTING" details:@"Scanning for ESP32 BLE peripheral..."];

  if (deviceIdentifier && deviceIdentifier.length > 0) {
    NSUUID *uuid = [[NSUUID alloc] initWithUUIDString:deviceIdentifier];
    if (uuid) {
      NSArray<CBPeripheral *> *peripherals = [_centralManager retrievePeripheralsWithIdentifiers:@[uuid]];
      if (peripherals.count > 0) {
        _connectedPeripheral = peripherals.firstObject;
        _connectedPeripheral.delegate = self;
        [_centralManager connectPeripheral:_connectedPeripheral options:nil];
        resolve(@{
          @"status": @"CONNECTING",
          @"device": _connectedPeripheral.name ?: @"SOS-WEAR",
          @"address": _connectedPeripheral.identifier.UUIDString
        });
        return;
      }
    }
  }

  // Auto-scan for ESP32 / SOS-WEAR BLE peripheral
  _isScanning = YES;
  CBUUID *serviceUUID = [CBUUID UUIDWithString:kAegisBleServiceUUIDString];
  [_centralManager scanForPeripheralsWithServices:@[serviceUUID] options:@{CBCentralManagerScanOptionAllowDuplicatesKey: @(NO)}];

  resolve(@{
    @"status": @"CONNECTING",
    @"device": @"SOS-WEAR",
    @"address": deviceIdentifier ?: @"BLE_SCANNING"
  });
}

RCT_EXPORT_METHOD(disconnectDevice:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  if (_connectedPeripheral) {
    [_centralManager cancelPeripheralConnection:_connectedPeripheral];
    _connectedPeripheral = nil;
  }
  if (_isScanning) {
    [_centralManager stopScan];
    _isScanning = NO;
  }
  _isConnected = NO;
  [self sendStatusEvent:@"DISCONNECTED" details:@"Disconnected from BLE wearable."];
  resolve(@{@"status": @"DISCONNECTED"});
}

RCT_EXPORT_METHOD(isConnected:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  resolve(@(_isConnected));
}

#pragma mark - CBCentralManagerDelegate

- (void)centralManagerDidUpdateState:(CBCentralManager *)central {
  // Notify state changes if needed
}

- (void)centralManager:(CBCentralManager *)central
 didDiscoverPeripheral:(CBPeripheral *)peripheral
     advertisementData:(NSDictionary<NSString *,id> *)advertisementData
                  RSSI:(NSNumber *)RSSI
{
  if (![_discoveredPeripherals containsObject:peripheral]) {
    [_discoveredPeripherals addObject:peripheral];
  }

  NSString *name = peripheral.name ?: advertisementData[CBAdvertisementDataLocalNameKey];
  if ([self isWearableName:name]) {
    [_centralManager stopScan];
    _isScanning = NO;
    _connectedPeripheral = peripheral;
    _connectedPeripheral.delegate = self;
    [_centralManager connectPeripheral:_connectedPeripheral options:nil];
  }
}

- (void)centralManager:(CBCentralManager *)central didConnectPeripheral:(CBPeripheral *)peripheral {
  _isConnected = YES;
  [self sendStatusEvent:@"CONNECTED" details:[NSString stringWithFormat:@"Connected to %@", peripheral.name ?: @"SOS-WEAR"]];
  CBUUID *serviceUUID = [CBUUID UUIDWithString:kAegisBleServiceUUIDString];
  [peripheral discoverServices:@[serviceUUID]];
}

- (void)centralManager:(CBCentralManager *)central
didFailToConnectPeripheral:(CBPeripheral *)peripheral
                 error:(NSError *)error
{
  _isConnected = NO;
  [self sendStatusEvent:@"ERROR" details:error.localizedDescription ?: @"Connection failed"];
}

- (void)centralManager:(CBCentralManager *)central
didDisconnectPeripheral:(CBPeripheral *)peripheral
                  error:(NSError *)error
{
  _isConnected = NO;
  _connectedPeripheral = nil;
  _notifyCharacteristic = nil;
  [self sendStatusEvent:@"DISCONNECTED" details:@"Peripheral disconnected"];
}

#pragma mark - CBPeripheralDelegate

- (void)peripheral:(CBPeripheral *)peripheral didDiscoverServices:(NSError *)error {
  if (error) return;
  for (CBService *service in peripheral.services) {
    if ([service.UUID isEqual:[CBUUID UUIDWithString:kAegisBleServiceUUIDString]]) {
      CBUUID *charUUID = [CBUUID UUIDWithString:kAegisBleCharUUIDString];
      [peripheral discoverCharacteristics:@[charUUID] forService:service];
    }
  }
}

- (void)peripheral:(CBPeripheral *)peripheral
didDiscoverCharacteristicsForService:(CBService *)service
             error:(NSError *)error
{
  if (error) return;
  for (CBCharacteristic *ch in service.characteristics) {
    if ([ch.UUID isEqual:[CBUUID UUIDWithString:kAegisBleCharUUIDString]]) {
      _notifyCharacteristic = ch;
      [peripheral setNotifyValue:YES forCharacteristic:ch];
    }
  }
}

- (void)peripheral:(CBPeripheral *)peripheral
didUpdateValueForCharacteristic:(CBCharacteristic *)characteristic
             error:(NSError *)error
{
  if (error || !characteristic.value) return;

  NSData *data = characteristic.value;
  const uint8_t *bytes = (const uint8_t *)[data bytes];
  NSUInteger length = [data length];

  for (NSUInteger i = 0; i < length; i++) {
    if (bytes[i] == kTriggerChar) {
      [self handleHardwareSosTrigger:peripheral.identifier.UUIDString];
      break;
    }
  }
}

- (void)handleHardwareSosTrigger:(NSString *)deviceAddress {
  long long now = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
  if (now - _lastTriggerTimestamp < kTriggerCooldownMs) {
    // Suppressed by 5000ms cooldown
    return;
  }
  _lastTriggerTimestamp = now;

  [self sendEventWithName:@"onAegisHardwareSosTrigger" body:@{
    @"source": @"ESP32",
    @"trigger": @"S",
    @"triggerType": @"BLUETOOTH_HARDWARE",
    @"timestamp": @(now),
    @"deviceAddress": deviceAddress ?: @"00:11:22:33:AA:BB"
  }];
}

- (void)sendStatusEvent:(NSString *)status details:(NSString *)details {
  long long now = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
  [self sendEventWithName:@"onAegisBluetoothStatus" body:@{
    @"status": status,
    @"details": details ?: @"",
    @"timestamp": @(now)
  }];
}

- (BOOL)isWearableName:(NSString *)name {
  if (!name) return NO;
  NSString *upper = [name uppercaseString];
  return [upper containsString:@"SOS-WEAR"] ||
         [upper containsString:@"AIZA"] ||
         [upper containsString:@"ESP32"];
}

- (NSString *)centralManagerStateString:(CBManagerState)state {
  switch (state) {
    case CBManagerStatePoweredOn: return @"POWERED_ON";
    case CBManagerStatePoweredOff: return @"POWERED_OFF";
    case CBManagerStateResetting: return @"RESETTING";
    case CBManagerStateUnauthorized: return @"UNAUTHORIZED";
    case CBManagerStateUnsupported: return @"UNSUPPORTED";
    default: return @"UNKNOWN";
  }
}

@end
