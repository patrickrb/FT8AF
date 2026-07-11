package com.k1af.ft8af.html;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for {@link LogHttpServer#pageCount(int, int)}, the page-count
 * math shared by the three web-logbook views (SWL messages, SWL QSOs, QSL logbook).
 *
 * <p>The old inline form {@code Math.round(rc / pageSize + 0.5f)} evaluated to
 * {@code floor(rc / pageSize) + 1}, so an exact multiple of the page size reported one
 * page too many and the web logbook showed a trailing empty page. These tests pin the
 * correct ceiling division and lock in that regression, plus the empty-log and
 * malformed-{@code pageSize} guards. No Android types are touched, so no Robolectric
 * runner is needed.
 */
public class LogHttpServerPageCountTest {

    @Test
    public void exactMultiple_hasNoTrailingEmptyPage() {
        // The core regression: rows == k * pageSize must be exactly k pages, not k + 1.
        assertThat(LogHttpServer.pageCount(100, 100)).isEqualTo(1);
        assertThat(LogHttpServer.pageCount(200, 100)).isEqualTo(2);
        assertThat(LogHttpServer.pageCount(300, 100)).isEqualTo(3);
        assertThat(LogHttpServer.pageCount(7, 7)).isEqualTo(1);
        assertThat(LogHttpServer.pageCount(5, 1)).isEqualTo(5);
    }

    @Test
    public void partialLastPage_roundsUp() {
        assertThat(LogHttpServer.pageCount(1, 100)).isEqualTo(1);
        assertThat(LogHttpServer.pageCount(99, 100)).isEqualTo(1);
        assertThat(LogHttpServer.pageCount(101, 100)).isEqualTo(2);
        assertThat(LogHttpServer.pageCount(150, 100)).isEqualTo(2);
        assertThat(LogHttpServer.pageCount(250, 100)).isEqualTo(3);
    }

    @Test
    public void emptyLog_stillOnePage() {
        // Preserve the old behaviour (Math.round(0 + 0.5f) == 1): show a single empty
        // page so the page controls still render.
        assertThat(LogHttpServer.pageCount(0, 100)).isEqualTo(1);
    }

    @Test
    public void nonPositivePageSize_collapsesToOnePage() {
        // A malformed ?pageSize= query value must not divide by zero (the old float form
        // produced Integer.MAX_VALUE via rc / 0f == Infinity).
        assertThat(LogHttpServer.pageCount(50, 0)).isEqualTo(1);
        assertThat(LogHttpServer.pageCount(50, -5)).isEqualTo(1);
    }
}
