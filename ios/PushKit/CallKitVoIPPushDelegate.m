#import "CallKitVoIPPushDelegate.h"

@interface CallKitVoIPPushDelegate ()
@property(nonatomic, weak) id<CallKitVoIPPushReceiver> receiver;
@end

@implementation CallKitVoIPPushDelegate

- (instancetype)initWithReceiver:(id<CallKitVoIPPushReceiver>)receiver {
  if ((self = [super init])) _receiver = receiver;
  return self;
}

- (void)pushRegistry:(PKPushRegistry *)registry
    didUpdatePushCredentials:(PKPushCredentials *)credentials
                     forType:(PKPushType)type {
  if ([type isEqualToString:PKPushTypeVoIP]) [self.receiver handleVoIPCredentials:credentials];
}

- (void)pushRegistry:(PKPushRegistry *)registry didInvalidatePushTokenForType:(PKPushType)type {
  if ([type isEqualToString:PKPushTypeVoIP]) [self.receiver invalidateVoIPCredentials];
}

- (void)pushRegistry:(PKPushRegistry *)registry
    didReceiveIncomingPushWithPayload:(PKPushPayload *)payload
                             forType:(PKPushType)type
               withCompletionHandler:(void (^)(void))completion {
  id<CallKitVoIPPushReceiver> receiver = self.receiver;
  if (![type isEqualToString:PKPushTypeVoIP] || !receiver) { completion(); return; }
  [receiver handleVoIPPayload:payload mustReport:YES completion:completion];
}

// Availability annotations alone cannot compile an unknown SDK type. Clang's
// header feature check keeps Xcode 26.3 builds on the legacy delegate path.
#if __has_include(<PushKit/PKVoIPPushMetadata.h>) && !defined(CALLKIT_TELECOM_LEGACY_PUSH_ONLY)
- (void)pushRegistry:(PKPushRegistry *)registry
    didReceiveIncomingVoIPPushWithPayload:(PKPushPayload *)payload
                                metadata:(PKVoIPPushMetadata *)metadata
                   withCompletionHandler:(void (^)(void))completion API_AVAILABLE(ios(26.4)) {
  id<CallKitVoIPPushReceiver> receiver = self.receiver;
  if (!receiver) { completion(); return; }
  [receiver handleVoIPPayload:payload mustReport:metadata.mustReport completion:completion];
}
#endif

@end
