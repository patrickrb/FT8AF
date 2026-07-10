/*
 * Bionic compatibility stubs for hamlib.
 *
 * libhamlib.a is cross-compiled at API 23 (to keep libft8af.so loadable on
 * Android 6.0+, matching the app's minSdk). At that level Bionic does not yet
 * export four functions hamlib references:
 *
 *     glob / globfree      (added API 28) - microHam device discovery
 *     getifaddrs/freeifaddrs(added API 24) - network interface enumeration
 *
 * Neither code path runs in this app: we drive hamlib purely as a loopback-TCP
 * client (see hamlib_jni.cpp), never microHam discovery or interface listing.
 * These no-op stubs satisfy the linker so the single libft8af.so has no
 * API-28/API-24 symbol dependency and loads cleanly on every supported device.
 *
 * They are defined with default visibility so the linker resolves hamlib's
 * references to them; on API 28+ devices Bionic also exports glob(), but our
 * internal definition is used for hamlib's calls and never leaks out because
 * the .so exports only the JNI entry points.
 */
#include <stddef.h>

/* Mirror the layout hamlib's microham.c expects from <glob.h>; only gl_pathc /
 * gl_pathv are read, and we always report "no matches". */
typedef struct {
    size_t gl_pathc;
    char **gl_pathv;
    size_t gl_offs;
} hamlib_compat_glob_t;

#define HAMLIB_COMPAT_GLOB_NOMATCH 3

int glob(const char *pattern, int flags, void *errfunc, hamlib_compat_glob_t *pglob) {
    (void) pattern;
    (void) flags;
    (void) errfunc;
    if (pglob) {
        pglob->gl_pathc = 0;
        pglob->gl_pathv = NULL;
        pglob->gl_offs = 0;
    }
    return HAMLIB_COMPAT_GLOB_NOMATCH;
}

void globfree(hamlib_compat_glob_t *pglob) {
    (void) pglob;
}

int getifaddrs(void **ifap) {
    if (ifap) {
        *ifap = NULL;
    }
    return -1; /* report "cannot enumerate" - callers treat as no interfaces */
}

void freeifaddrs(void *ifa) {
    (void) ifa;
}
