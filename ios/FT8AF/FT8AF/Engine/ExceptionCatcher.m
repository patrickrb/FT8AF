#import "ExceptionCatcher.h"

NSException *_Nullable ft8af_catchException(void (^_Nonnull block)(void)) {
    @try {
        block();
        return nil;
    } @catch (NSException *exception) {
        return exception;
    }
}
