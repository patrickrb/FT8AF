// hamlib_feed.h — the byte-forwarding step of the hamlib loopback bridge.
//
// WHY THIS EXISTS
// ------------------------------------------------------------------
// nativeFeedFromRig (hamlib_jni.cpp) pushes every CAT reply the radio sends
// into hamlib's read side by writing the bytes to the accepted loopback socket
// `srv_fd`. It runs on the serial read thread for EVERY reply while a rig is
// connected — a genuinely hot, live path.
//
// The write loop takes a `jbyte *` obtained from JNIEnv::GetByteArrayElements,
// which is allowed to return NULL when the JVM cannot pin the array (out-of-
// memory / heap pressure), leaving a pending exception. The original inline loop
// did not null-check that pin: it fed the NULL pointer straight into write() and
// then returned to Java with the JVM's OOM exception still pending on the CAT
// read thread. Every other JNI pin in this codebase is null-checked (see
// ftx_feed.h); the hamlib bridge, added later, missed the pattern. The matching
// JNI-side fix in hamlib_jni.cpp also guards a NULL `data` jarray (a NULL passed
// to GetArrayLength is itself undefined behaviour) and clears the pending
// exception on a failed pin.
//
// This header collapses the write loop into one null-safe, host-tested
// implementation (test_hamlib_feed.c). It takes plain POSIX types (no JNI
// dependency) so it links with no NDK. The behaviour is identical to the old
// inline loop for every valid buffer, save two hardening changes: a NULL
// pointer, a non-positive length, or a closed socket (fd < 0) is now a no-op
// instead of a crash, and a write() interrupted by a signal (EINTR) is retried
// instead of ending the loop — the old loop treated that as "socket gone" and
// silently dropped the rest of a CAT reply on a still-healthy fd.

#ifndef FT8AF_HAMLIB_FEED_H
#define FT8AF_HAMLIB_FEED_H

#include <errno.h>
#include <stddef.h>
#include <sys/types.h>
#include <unistd.h>

// Write all `len` bytes of `bytes` to socket `fd`, tolerating short writes
// (loops until the whole buffer is sent). A NULL `bytes`, a non-positive `len`,
// or a negative `fd` is a no-op — never a dereference. A write() interrupted by
// a signal (EINTR) is retried — the fd is still healthy, so bailing there would
// silently truncate a CAT reply. Stops early only when write() actually fails or
// the peer is gone (return <= 0 for any other reason), mirroring the original
// loop.
//
// Returns the number of bytes actually written (0 on any of the guarded cases).
static inline long hamlib_feed_write(int fd, const unsigned char *bytes, long len)
{
    if (fd < 0 || bytes == NULL || len <= 0)
        return 0;

    long off = 0;
    while (off < len)
    {
        ssize_t w = write(fd, bytes + off, (size_t)(len - off));
        if (w <= 0)
        {
            if (w < 0 && errno == EINTR)
                continue; // interrupted before any byte moved — retry, don't truncate
            break;
        }
        off += w;
    }
    return off;
}

#endif // FT8AF_HAMLIB_FEED_H
