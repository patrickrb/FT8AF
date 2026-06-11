// Host-build portability shim for the native test harnesses.
//
// The vendored ft8_lib is written for Android/bionic + POSIX. When we compile it
// for a desktop host (to run the golden tests without a device), the MSVC CRT is
// missing a couple of POSIX niceties. Force-include this with clang's `-include`
// so the definitions are visible across every translation unit in the build.
//
// This file is ONLY for host test builds; the shipped Android .so uses bionic,
// which provides these natively, and never includes this header.

#ifndef FT8CN_HOST_COMPAT_H
#define FT8CN_HOST_COMPAT_H

#define _USE_MATH_DEFINES // make <math.h> define M_PI on MSVC
#include <math.h>
#include <string.h>

// POSIX stpcpy: copy s into d (incl. NUL), return pointer to the NUL. Used by
// ft8_lib message.c / unpack.c when assembling decoded message text.
//
// Only the MSVC CRT is missing stpcpy; glibc, bionic, musl, and MinGW-w64 all
// provide it, and shadowing the real one there would conflict. Gate on
// _MSC_VER so this shim only appears under MSVC (which is what our host clang
// targets — it defines _MSC_VER). Skip it if something already defined stpcpy.
#if defined(_MSC_VER) && !defined(stpcpy)
static inline char* ft8cn_stpcpy(char* d, const char* s)
{
    size_t n = strlen(s);
    memcpy(d, s, n + 1);
    return d + n;
}
#define stpcpy ft8cn_stpcpy
#endif

#endif // FT8CN_HOST_COMPAT_H
