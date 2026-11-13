// Host unit test for ft8_grid_shape.h — the shared grid-shape check behind a
// real-device finding: "RR73" (the standard FT8/FT4 reply token, never a
// locator) passed the old per-file ft8_looks_like_grid/ft2_looks_like_grid
// checks by coincidence, so every RR73 reply was stored and treated as the
// sender's real grid (a genuine, if obscure, Chukotka square nobody has
// worked) -- false "NEW GRID" badges on every RR73 row, and the same bogus
// value available to anything else that reads maidenGrid.
//
// Build/run: added to run_host_tests.sh / run_host_tests.ps1. Standalone:
//   clang -std=c11 -I . test_grid_shape.c -o /tmp/t && /tmp/t

#include <stdio.h>
#include <string.h>
#include <stdbool.h>

#include "ft8_grid_shape.h"

static int g_failures = 0;

#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);        \
            ++g_failures;                                                 \
        }                                                                 \
    } while (0)

int main(void)
{
    // Real grid locators: accepted.
    CHECK(ft8af_looks_like_grid("EM98"));
    CHECK(ft8af_looks_like_grid("DM78"));
    CHECK(ft8af_looks_like_grid("FM05"));
    CHECK(ft8af_looks_like_grid("AA00"));
    CHECK(ft8af_looks_like_grid("RR99"));

    // RR73: shape-matches (R/R both in A-R, 7/3 both digits) but is the
    // protocol's reply token, never a locator -- must be excluded.
    CHECK(!ft8af_looks_like_grid("RR73"));

    // Signal reports and other exchange tokens: never grid-shaped, must stay
    // excluded (these are the messages the real-device "message not shown"
    // bug was about -- see DecodeRow's secondaryFieldText fallback, a
    // separate fix that this one must not regress: a signal report must NOT
    // start showing up as a (fake) grid either).
    CHECK(!ft8af_looks_like_grid("-04"));
    CHECK(!ft8af_looks_like_grid("R-09"));
    CHECK(!ft8af_looks_like_grid("+10"));
    CHECK(!ft8af_looks_like_grid("73"));

    // Shape edge cases.
    CHECK(!ft8af_looks_like_grid(NULL));
    CHECK(!ft8af_looks_like_grid(""));
    CHECK(!ft8af_looks_like_grid("EM9")); // too short
    CHECK(!ft8af_looks_like_grid("EM980")); // too long
    CHECK(!ft8af_looks_like_grid("em98")); // lowercase: the field letters must be uppercase A-R
    CHECK(!ft8af_looks_like_grid("SA12")); // 'S' is outside A-R
    CHECK(!ft8af_looks_like_grid("AAAA")); // digits missing

    if (g_failures == 0)
        printf("test_grid_shape: all checks passed\n");
    else
        printf("test_grid_shape: %d check(s) failed\n", g_failures);

    return g_failures == 0 ? 0 : 1;
}
