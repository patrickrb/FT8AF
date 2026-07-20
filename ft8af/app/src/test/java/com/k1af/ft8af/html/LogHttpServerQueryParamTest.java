package com.k1af.ft8af.html;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for the web-logbook query-parameter guards
 * {@link LogHttpServer#parseQueryInt(String, int)} and
 * {@link LogHttpServer#clampPageIndex(int, int)}.
 *
 * <p>The always-on {@code LogHttpServer} reads {@code ?page=}, {@code ?pageSize=} and
 * {@code ?session=} straight off the request. The handlers used to
 * {@code Integer.parseInt} those raw values and clamped {@code pageIndex} only on the
 * high side, so a malformed value threw (NanoHTTPD swallows it into an aborted response)
 * and {@code ?page=0} drove the paged query's {@code LIMIT (pageIndex - 1) * pageSize}
 * offset negative. These tests pin the guarded behaviour. No Android types are touched,
 * so no Robolectric runner is needed (mirrors {@link LogHttpServerPageCountTest}).
 */
public class LogHttpServerQueryParamTest {

    @Test
    public void parseQueryInt_parsesValidValue() {
        assertThat(LogHttpServer.parseQueryInt("5", 1)).isEqualTo(5);
        assertThat(LogHttpServer.parseQueryInt("100", 1)).isEqualTo(100);
        assertThat(LogHttpServer.parseQueryInt("-3", 1)).isEqualTo(-3);
    }

    @Test
    public void parseQueryInt_fallsBackForNull() {
        // A parameter that was never supplied keeps the handler's default.
        assertThat(LogHttpServer.parseQueryInt(null, 1)).isEqualTo(1);
        assertThat(LogHttpServer.parseQueryInt(null, 100)).isEqualTo(100);
    }

    @Test
    public void parseQueryInt_fallsBackForNonNumeric() {
        // The regression: a raw parseInt of these threw NumberFormatException out of
        // serve(), which NanoHTTPD swallows into an aborted/empty response.
        assertThat(LogHttpServer.parseQueryInt("abc", 1)).isEqualTo(1);
        assertThat(LogHttpServer.parseQueryInt("", 7)).isEqualTo(7);
        assertThat(LogHttpServer.parseQueryInt("12x", 7)).isEqualTo(7);
        assertThat(LogHttpServer.parseQueryInt("99999999999999999999", 7)).isEqualTo(7);
    }

    @Test
    public void parseQueryInt_trimsSurroundingWhitespace() {
        assertThat(LogHttpServer.parseQueryInt("  5  ", 1)).isEqualTo(5);
    }

    @Test
    public void clampPageIndex_floorsZeroAndNegativeToOne() {
        // The core fix: ?page=0 / ?page=-5 must not leave pageIndex < 1, or the
        // LIMIT (pageIndex - 1) * pageSize offset goes negative.
        assertThat(LogHttpServer.clampPageIndex(0, 5)).isEqualTo(1);
        assertThat(LogHttpServer.clampPageIndex(-5, 5)).isEqualTo(1);
    }

    @Test
    public void clampPageIndex_keepsInRangeValue() {
        assertThat(LogHttpServer.clampPageIndex(1, 5)).isEqualTo(1);
        assertThat(LogHttpServer.clampPageIndex(3, 5)).isEqualTo(3);
        assertThat(LogHttpServer.clampPageIndex(5, 5)).isEqualTo(5);
    }

    @Test
    public void clampPageIndex_capsToPageCount() {
        assertThat(LogHttpServer.clampPageIndex(6, 5)).isEqualTo(5);
        assertThat(LogHttpServer.clampPageIndex(999, 5)).isEqualTo(5);
        // pageCount is always >= 1 (see LogHttpServer#pageCount), so an empty log with a
        // stale high page still lands on the single real page.
        assertThat(LogHttpServer.clampPageIndex(6, 1)).isEqualTo(1);
    }

    @Test
    public void clampPageIndex_neverProducesNegativeSqlOffset() {
        // Guards the actual downstream use: LIMIT (pageIndex - 1) * pageSize.
        int pageSize = 100;
        for (int requested : new int[] {-10, -1, 0, 1, 2, 50}) {
            int clamped = LogHttpServer.clampPageIndex(requested, 5);
            assertThat((clamped - 1) * pageSize).isAtLeast(0);
        }
    }
}
