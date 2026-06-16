// Windows/MSVC compatibility shim, force-included (clang-cl /FI) only on the
// windows-msvc build. ft8_lib (unpack.c, message.c) uses POSIX stpcpy, which the
// MSVC CRT does not provide. Defining it here keeps the vendored ft8_lib sources
// unmodified. macOS/Linux get stpcpy from their libc and never include this.
#ifndef FT8AF_WIN_COMPAT_H
#define FT8AF_WIN_COMPAT_H

#ifdef _WIN32
#include <string.h>

// Copy src (incl. NUL) to dst; return pointer to the terminating NUL in dst.
static inline char* stpcpy(char* dst, const char* src)
{
    size_t len = strlen(src);
    memcpy(dst, src, len + 1);
    return dst + len;
}
#endif // _WIN32

#endif // FT8AF_WIN_COMPAT_H
