// Whether a decoded message's third field looks like a maidenhead grid
// locator, shared by ft8_decode_jni.cpp and ft2_decode_jni.cpp (each used to
// carry its own copy of this exact function, ft8_looks_like_grid /
// ft2_looks_like_grid -- consolidated here so the fix below only has to be
// made once).
//
// Extracted into its own header (no ft8_lib/JNI dependencies) so it can be
// host-unit-tested directly -- see test_grid_shape.c / run_host_tests.sh.
#ifndef FT8AF_GRID_SHAPE_H
#define FT8AF_GRID_SHAPE_H

#include <stdbool.h>
#include <string.h>

// Real-hardware finding: "RR73" (the standard FT8/FT4 reply token, never a
// locator) passes the shape check below purely by coincidence -- 'R' falls
// in the A-R range twice, and '7'/'3' are both digits. Every RR73 message
// was therefore stored as if RR73 (a real, if obscure, Chukotka grid square
// nobody has worked) were the sender's actual location: false "NEW GRID"
// badges on every RR73 reply, and the same bogus value available to anything
// else that reads maidenGrid (distance/bearing, map markers, grid-based
// lookups). Excluded explicitly rather than widening/narrowing the general
// shape check, since RR73 is the one specific token the FT8/FT4 protocol
// reserves this way -- not a hole in the grid-shape pattern itself.
static inline bool ft8af_looks_like_grid(const char* s)
{
    if (!s || strlen(s) != 4)
        return false;
    if (s[0] >= 'A' && s[0] <= 'R' && s[1] >= 'A' && s[1] <= 'R' &&
        s[2] >= '0' && s[2] <= '9' && s[3] >= '0' && s[3] <= '9')
    {
        if (strcmp(s, "RR73") == 0)
            return false;
        return true;
    }
    return false;
}

#endif // FT8AF_GRID_SHAPE_H
