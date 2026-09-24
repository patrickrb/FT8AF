#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Runs `block` inside an Obj-C `@try/@catch` and returns the raised
/// `NSException`, or `nil` if none was raised.
///
/// Swift cannot catch Obj-C exceptions natively (`try` only handles Swift
/// `Error`), so an AVFAudio call that raises one — e.g.
/// `AVAudioPlayerNode.play()` when the engine graph is torn down by a
/// configuration change mid-call — aborts the whole process. Wrapping that call
/// in this shim degrades the abort into a recoverable `nil`/exception result.
NSException *_Nullable ft8af_catchException(void (^_Nonnull block)(void));

NS_ASSUME_NONNULL_END
