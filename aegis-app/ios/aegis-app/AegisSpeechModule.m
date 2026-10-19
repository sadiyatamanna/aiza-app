#import "AegisSpeechModule.h"

static const long long kVoiceTriggerCooldownMs = 5000;

@interface AegisSpeechModule () {
  SFSpeechRecognizer *_speechRecognizer;
  SFSpeechAudioBufferRecognitionRequest *_recognitionRequest;
  SFSpeechRecognitionTask *_recognitionTask;
  AVAudioEngine *_audioEngine;
  BOOL _isListening;
  long long _lastVoiceTriggerTimestamp;
}
@end

@implementation AegisSpeechModule

RCT_EXPORT_MODULE(AegisSpeechModule);

- (NSArray<NSString *> *)supportedEvents {
  return @[@"onAegisVoiceSosTrigger"];
}

+ (BOOL)requiresMainQueueSetup {
  return YES;
}

- (instancetype)init {
  self = [super init];
  if (self) {
    _speechRecognizer = [[SFSpeechRecognizer alloc] initWithLocale:[NSLocale localeWithLocaleIdentifier:@"en-US"]];
    _speechRecognizer.delegate = self;
    _audioEngine = [[AVAudioEngine alloc] init];
    _isListening = NO;
    _lastVoiceTriggerTimestamp = 0;
  }
  return self;
}

RCT_EXPORT_METHOD(requestSpeechAuthorization:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  [SFSpeechRecognizer requestAuthorization:^(SFSpeechRecognizerAuthorizationStatus status) {
    BOOL granted = (status == SFSpeechRecognizerAuthorizationStatusAuthorized);
    resolve(@{
      @"granted": @(granted),
      @"status": [self authStatusString:status]
    });
  }];
}

RCT_EXPORT_METHOD(startVoiceRecognition:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  if (_isListening) {
    resolve(@{@"status": @"ALREADY_LISTENING", @"active": @(YES)});
    return;
  }

  [SFSpeechRecognizer requestAuthorization:^(SFSpeechRecognizerAuthorizationStatus authStatus) {
    if (authStatus != SFSpeechRecognizerAuthorizationStatusAuthorized) {
      reject(@"SPEECH_UNAUTHORIZED", @"Speech recognition permission not granted.", nil);
      return;
    }

    dispatch_async(dispatch_get_main_queue(), ^{
      NSError *error = nil;
      [self startAudioRecognitionSession:&error];
      if (error) {
        reject(@"AUDIO_ENGINE_FAILED", error.localizedDescription, error);
      } else {
        self->_isListening = YES;
        resolve(@{
          @"status": @"LISTENING_STARTED",
          @"active": @(YES),
          @"platform": @"iOS SFSpeechRecognizer"
        });
      }
    });
  }];
}

RCT_EXPORT_METHOD(stopVoiceRecognition:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  [self stopAudioRecognitionSession];
  resolve(@{@"status": @"LISTENING_STOPPED", @"active": @(NO)});
}

RCT_EXPORT_METHOD(isVoiceRecognitionActive:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
  resolve(@(_isListening));
}

- (void)startAudioRecognitionSession:(NSError **)outError {
  if (_recognitionTask) {
    [_recognitionTask cancel];
    _recognitionTask = nil;
  }

  AVAudioSession *audioSession = [AVAudioSession sharedInstance];
  [audioSession setCategory:AVAudioSessionCategoryRecord mode:AVAudioSessionModeMeasurement options:AVAudioSessionCategoryOptionDuckOthers error:outError];
  [audioSession setActive:YES withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation error:outError];

  _recognitionRequest = [[SFSpeechAudioBufferRecognitionRequest alloc] init];
  _recognitionRequest.shouldReportPartialResults = YES;

  AVAudioInputNode *inputNode = _audioEngine.inputNode;
  if (!inputNode) return;

  __weak typeof(self) weakSelf = self;
  _recognitionTask = [_speechRecognizer recognitionTaskWithRequest:_recognitionRequest resultHandler:^(SFSpeechRecognitionResult * _Nullable result, NSError * _Nullable error) {
    if (result) {
      NSString *transcript = result.bestTranscription.formattedString;
      [weakSelf checkVoiceEmergencyKeywords:transcript];
    }
    if (error || result.isFinal) {
      [weakSelf stopAudioRecognitionSession];
    }
  }];

  AVAudioFormat *recordingFormat = [inputNode outputFormatForBus:0];
  [inputNode removeTapOnBus:0];
  [inputNode installTapOnBus:0 bufferSize:1024 format:recordingFormat block:^(AVAudioPCMBuffer * _Nonnull buffer, AVAudioTime * _Nonnull when) {
    [weakSelf->_recognitionRequest appendAudioPCMBuffer:buffer];
  }];

  [_audioEngine prepare];
  [_audioEngine startAndReturnError:outError];
}

- (void)stopAudioRecognitionSession {
  if (_audioEngine.isRunning) {
    [_audioEngine stop];
    [_audioEngine.inputNode removeTapOnBus:0];
  }
  if (_recognitionRequest) {
    [_recognitionRequest endAudio];
    _recognitionRequest = nil;
  }
  if (_recognitionTask) {
    [_recognitionTask cancel];
    _recognitionTask = nil;
  }
  _isListening = NO;
}

- (void)checkVoiceEmergencyKeywords:(NSString *)transcript {
  if (!transcript || transcript.length == 0) return;
  NSString *lower = [transcript lowercaseString];

  NSArray<NSString *> *keywords = @[
    @"help",
    @"emergency",
    @"save me",
    @"aiza help",
    @"aiza emergency"
  ];

  for (NSString *kw in keywords) {
    if ([lower containsString:kw]) {
      long long now = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
      if (now - _lastVoiceTriggerTimestamp >= kVoiceTriggerCooldownMs) {
        _lastVoiceTriggerTimestamp = now;
        [self sendEventWithName:@"onAegisVoiceSosTrigger" body:@{
          @"source": @"VOICE",
          @"triggerType": @"SPEECH_RECOGNITION",
          @"matchedPhrase": kw,
          @"transcript": transcript,
          @"timestamp": @(now),
          @"platform": @"iOS"
        }];
      }
      break;
    }
  }
}

- (NSString *)authStatusString:(SFSpeechRecognizerAuthorizationStatus)status {
  switch (status) {
    case SFSpeechRecognizerAuthorizationStatusAuthorized: return @"AUTHORIZED";
    case SFSpeechRecognizerAuthorizationStatusDenied: return @"DENIED";
    case SFSpeechRecognizerAuthorizationStatusRestricted: return @"RESTRICTED";
    case SFSpeechRecognizerAuthorizationStatusNotDetermined: return @"NOT_DETERMINED";
  }
}

@end
