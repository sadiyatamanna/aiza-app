#import "AegisHardwareModule.h"
#import <AVFoundation/AVFoundation.h>

@interface AegisHardwareModule () {
  AVAudioPlayer *_audioPlayer;
  BOOL _isSirenPlaying;
  BOOL _isTorchOn;
}
@end

@implementation AegisHardwareModule

RCT_EXPORT_MODULE(AegisHardwareModule);

+ (BOOL)requiresMainQueueSetup {
  return YES;
}

- (instancetype)init {
  self = [super init];
  if (self) {
    _isSirenPlaying = NO;
    _isTorchOn = NO;
  }
  return self;
}

#pragma mark - Torch / Flashlight Controls

RCT_EXPORT_METHOD(isTorchAvailable:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  AVCaptureDevice *device = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo];
  BOOL available = (device != nil && [device hasTorch] && [device isTorchAvailable]);
  resolve(@(available));
}

RCT_EXPORT_METHOD(toggleTorch:(BOOL)enabled
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  AVCaptureDevice *device = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo];
  if (!device || ![device hasTorch]) {
    resolve(@{
      @"status": @"FAILED",
      @"enabled": @(NO),
      @"message": @"Camera torch unit not available on this iOS device."
    });
    return;
  }

  NSError *error = nil;
  if ([device lockForConfiguration:&error]) {
    if (enabled) {
      if ([device isTorchModeSupported:AVCaptureTorchModeOn]) {
        [device setTorchMode:AVCaptureTorchModeOn];
        _isTorchOn = YES;
      }
    } else {
      if ([device isTorchModeSupported:AVCaptureTorchModeOff]) {
        [device setTorchMode:AVCaptureTorchModeOff];
        _isTorchOn = NO;
      }
    }
    [device unlockForConfiguration];
    resolve(@{
      @"status": enabled ? @"TORCH_ON" : @"TORCH_OFF",
      @"enabled": @(_isTorchOn)
    });
  } else {
    resolve(@{
      @"status": @"FAILED",
      @"enabled": @(NO),
      @"message": error.localizedDescription ?: @"Failed to lock camera device for torch."
    });
  }
}

RCT_EXPORT_METHOD(isTorchOn:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  AVCaptureDevice *device = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo];
  BOOL on = (device != nil && device.torchMode == AVCaptureTorchModeOn);
  resolve(@(on));
}

#pragma mark - Emergency Siren Controls

RCT_EXPORT_METHOD(playEmergencySiren:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  NSError *sessionError = nil;
  AVAudioSession *session = [AVAudioSession sharedInstance];
  [session setCategory:AVAudioSessionCategoryPlayback
           withOptions:AVAudioSessionCategoryOptionDuckOthers
                 error:&sessionError];
  [session setActive:YES error:&sessionError];

  // Synthesize emergency alternating dual-frequency siren tone in memory
  NSData *sirenData = [self generateSirenPcmWavData];
  if (!sirenData) {
    resolve(@{
      @"success": @(NO),
      @"status": @"SIREN_INITIALIZATION_FAILED",
      @"message": @"Could not synthesize siren PCM audio data."
    });
    return;
  }

  NSError *playerError = nil;
  _audioPlayer = [[AVAudioPlayer alloc] initWithData:sirenData error:&playerError];
  if (playerError || !_audioPlayer) {
    resolve(@{
      @"success": @(NO),
      @"status": @"SIREN_INITIALIZATION_FAILED",
      @"message": playerError.localizedDescription ?: @"Failed to initialize AVAudioPlayer"
    });
    return;
  }

  _audioPlayer.numberOfLoops = -1; // Infinite loop until stopped
  _audioPlayer.volume = 1.0f;     // High emergency volume
  [_audioPlayer prepareToPlay];
  [_audioPlayer play];
  _isSirenPlaying = YES;

  resolve(@{
    @"success": @(YES),
    @"status": @"PLAYING"
  });
}

RCT_EXPORT_METHOD(stopEmergencySiren:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  if (_audioPlayer) {
    [_audioPlayer stop];
    _audioPlayer = nil;
  }
  _isSirenPlaying = NO;

  AVAudioSession *session = [AVAudioSession sharedInstance];
  [session setActive:NO withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation error:nil];

  resolve(@{
    @"success": @(YES),
    @"status": @"STOPPED"
  });
}

RCT_EXPORT_METHOD(isSirenPlaying:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  resolve(@(_isSirenPlaying));
}

#pragma mark - Siren PCM Audio Generator (WAV Header + 2-tone frequency sweep)

- (NSData *)generateSirenPcmWavData {
  const int sampleRate = 44100;
  const int durationSeconds = 2;
  const int totalSamples = sampleRate * durationSeconds;
  const int numChannels = 1;
  const int bitsPerSample = 16;
  const int byteRate = sampleRate * numChannels * (bitsPerSample / 8);
  const int blockAlign = numChannels * (bitsPerSample / 8);
  const int dataSize = totalSamples * (bitsPerSample / 8);

  NSMutableData *wavData = [NSMutableData dataWithCapacity:44 + dataSize];

  // RIFF header
  [wavData appendBytes:"RIFF" length:4];
  int32_t chunkSize = 36 + dataSize;
  [wavData appendBytes:&chunkSize length:4];
  [wavData appendBytes:"WAVE" length:4];

  // fmt chunk
  [wavData appendBytes:"fmt " length:4];
  int32_t subchunk1Size = 16;
  [wavData appendBytes:&subchunk1Size length:4];
  int16_t audioFormat = 1; // PCM
  [wavData appendBytes:&audioFormat length:2];
  int16_t channels = numChannels;
  [wavData appendBytes:&channels length:2];
  int32_t sRate = sampleRate;
  [wavData appendBytes:&sRate length:4];
  int32_t bRate = byteRate;
  [wavData appendBytes:&bRate length:4];
  int16_t bAlign = blockAlign;
  [wavData appendBytes:&bAlign length:2];
  int16_t bps = bitsPerSample;
  [wavData appendBytes:&bps length:2];

  // data chunk
  [wavData appendBytes:"data" length:4];
  int32_t dSize = dataSize;
  [wavData appendBytes:&dSize length:4];

  // Synthesize European / American alternating emergency siren frequencies (750 Hz to 1200 Hz)
  for (int i = 0; i < totalSamples; i++) {
    double t = (double)i / (double)sampleRate;
    double freq = (fmod(t, 1.0) < 0.5) ? 780.0 : 1080.0;
    double sample = sin(2.0 * M_PI * freq * t);
    int16_t intSample = (int16_t)(sample * 30000.0);
    [wavData appendBytes:&intSample length:2];
  }

  return wavData;
}

@end
