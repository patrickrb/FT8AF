// Host unit tests for hamlib_feed.h — the byte-forwarding step of the hamlib
// loopback bridge (nativeFeedFromRig). Run by run_host_tests.sh / .ps1.
//
// Covered:
//   1. A valid buffer is written to the socket in full and the byte count is
//      returned; the reader gets exactly those bytes.
//   2. A large buffer that needs several write() calls (short writes on a small
//      pipe buffer) is still delivered in full — the loop drains it.
//   3. REGRESSION (the reason this file exists): a NULL `bytes` pointer — the
//      value JNIEnv::GetByteArrayElements returns when the JVM cannot pin the
//      array under memory pressure — must be a guarded no-op that returns 0, not
//      a wild write() through a bad pointer. The previous inline loop in
//      hamlib_jni.cpp fed the pin straight into write() with no null check (and
//      left the JVM's pending OOM exception unhandled on the CAT read thread).
//      This test passes NULL and can only pass if the pointer is guarded first.
//   4. A non-positive length is a no-op returning 0.
//   5. A negative fd (closed socket) is a no-op returning 0 — no write attempt.

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "hamlib_feed.h"

static int g_failures = 0;

static void check(bool ok, const char* what)
{
    printf("  %s %s\n", ok ? "ok:  " : "FAIL:", what);
    if (!ok)
        g_failures++;
}

// Drain-reader: a pipe's kernel buffer is finite, so a large write would block
// once it fills. Read the pipe on a helper thread so hamlib_feed_write's write()
// loop can make progress through short writes.
struct reader_arg {
    int fd;
    unsigned char* buf;
    long cap;
    long got;
};

static void* reader_main(void* arg)
{
    struct reader_arg* r = (struct reader_arg*)arg;
    for (;;)
    {
        ssize_t n = read(r->fd, r->buf + r->got, (size_t)(r->cap - r->got));
        if (n <= 0)
            break; // EOF (write end closed) or error
        r->got += n;
        if (r->got >= r->cap)
            break;
    }
    return NULL;
}

// Write `len` bytes through hamlib_feed_write and collect what the reader saw.
// Returns the value hamlib_feed_write returned; fills *out_got with bytes read.
static long feed_and_collect(const unsigned char* data, long len,
                             unsigned char* out, long out_cap, long* out_got)
{
    int fds[2];
    if (pipe(fds) != 0)
    {
        perror("pipe");
        exit(2);
    }
    struct reader_arg r = { fds[0], out, out_cap, 0 };
    pthread_t th;
    pthread_create(&th, NULL, reader_main, &r);

    long wrote = hamlib_feed_write(fds[1], data, len);

    close(fds[1]);          // signal EOF to the reader
    pthread_join(th, NULL);
    close(fds[0]);
    *out_got = r.got;
    return wrote;
}

int main(void)
{
    // 1. Valid small buffer: written in full, delivered exactly.
    {
        const unsigned char msg[] = { 0xFE, 0xFE, 0x00, 0x03, 0xFD }; // Icom-ish frame
        unsigned char got[16];
        long ngot = 0;
        long wrote = feed_and_collect(msg, (long)sizeof(msg), got, sizeof(got), &ngot);
        check(wrote == (long)sizeof(msg), "valid feed returns the written byte count");
        check(ngot == (long)sizeof(msg) && memcmp(got, msg, sizeof(msg)) == 0,
              "valid feed delivers the bytes exactly");
    }

    // 2. Large buffer that forces multiple short writes: still delivered whole.
    {
        const long big = 256 * 1024; // well over a pipe's default buffer
        unsigned char* data = (unsigned char*)malloc((size_t)big);
        unsigned char* got = (unsigned char*)malloc((size_t)big);
        for (long i = 0; i < big; ++i)
            data[i] = (unsigned char)(i & 0xFF);
        long ngot = 0;
        long wrote = feed_and_collect(data, big, got, big, &ngot);
        check(wrote == big, "large feed reports every byte written");
        check(ngot == big && memcmp(got, data, (size_t)big) == 0,
              "large feed drains through short writes with no loss");
        free(data);
        free(got);
    }

    // 3. REGRESSION: NULL bytes must be a guarded no-op, not a SIGSEGV.
    {
        int fds[2];
        if (pipe(fds) != 0) { perror("pipe"); return 2; }
        long wrote = hamlib_feed_write(fds[1], NULL, 5);
        check(wrote == 0, "NULL bytes is a no-op (no crash)");
        close(fds[0]);
        close(fds[1]);
    }

    // 4. Non-positive length: no-op.
    {
        int fds[2];
        if (pipe(fds) != 0) { perror("pipe"); return 2; }
        const unsigned char msg[] = { 1, 2, 3 };
        check(hamlib_feed_write(fds[1], msg, 0) == 0, "zero length is a no-op");
        check(hamlib_feed_write(fds[1], msg, -1) == 0, "negative length is a no-op");
        close(fds[0]);
        close(fds[1]);
    }

    // 5. Negative fd (closed socket): no-op, no write attempt.
    {
        const unsigned char msg[] = { 1, 2, 3 };
        check(hamlib_feed_write(-1, msg, 3) == 0, "negative fd is a no-op");
    }

    if (g_failures == 0)
    {
        printf("test_hamlib_feed: ALL PASS\n");
        return 0;
    }
    printf("test_hamlib_feed: %d FAILURE(S)\n", g_failures);
    return 1;
}
