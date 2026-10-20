#import <Foundation/Foundation.h>
#import <PushKit/PushKit.h>

NS_ASSUME_NONNULL_BEGIN

/// Keeps SDK-specific PushKit types out of the Swift/Expo boundary.
@protocol CallKitVoIPPushReceiver <NSObject>
- (void)handleVoIPPayload:(PKPushPayload *)payload
              mustReport:(BOOL)mustReport
              completion:(void (^)(void))completion;
- (void)handleVoIPCredentials:(PKPushCredentials *)credentials;
- (void)invalidateVoIPCredentials;
@end

@interface CallKitVoIPPushDelegate : NSObject <PKPushRegistryDelegate>
- (instancetype)initWithReceiver:(id<CallKitVoIPPushReceiver>)receiver;
@end

NS_ASSUME_NONNULL_END
