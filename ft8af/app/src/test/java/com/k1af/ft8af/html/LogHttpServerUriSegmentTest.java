package com.k1af.ft8af.html;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-logic coverage for {@link LogHttpServer#uriSegment(String[], int)} and
 * {@link LogHttpServer#downloadFileName(String)}, the guards that keep the
 * web-logbook {@code serve} dispatch from reading a missing {@code uriList[2]}
 * path segment.
 *
 * <p>Regression: {@code GET /SHOWQSL} (no trailing month) split to
 * {@code ["", "SHOWQSL"]} and the bare {@code uriList[2]} read threw
 * {@link ArrayIndexOutOfBoundsException}. For SHOWQSL that read was outside the
 * {@code serve} try/catch, so it escaped the handler entirely; the
 * DOWNQSL / DOWNQSLNOQSL {@code Content-Disposition} header read threw inside the
 * try and returned an opaque HTTP 500 even though the body branch had already
 * fallen back to the default page. The three guarded sibling branches
 * (DELFOLLOW / DELQSL / DELQSLCALLSIGN) already used {@code uriList.length >= 3}.
 *
 * <p>These tests reproduce the exact {@code getUri().split("/")} shape and pin the
 * guarded behaviour. No Android types are touched, so no Robolectric runner is
 * needed.
 */
public class LogHttpServerUriSegmentTest {

    @Test
    public void missingTrailingSegment_isNull() {
        // "/SHOWQSL".split("/") == ["", "SHOWQSL"] (length 2), so index 2 is absent.
        String[] uriList = "/SHOWQSL".split("/");
        assertThat(uriList).hasLength(2);
        assertThat(LogHttpServer.uriSegment(uriList, 2)).isNull();
    }

    @Test
    public void presentTrailingSegment_isReturned() {
        String[] uriList = "/SHOWQSL/202401".split("/");
        assertThat(LogHttpServer.uriSegment(uriList, 2)).isEqualTo("202401");
    }

    @Test
    public void downloadMissingMonth_isNull() {
        // The DOWNQSL Content-Disposition header used to dereference this unconditionally.
        assertThat(LogHttpServer.uriSegment("/DOWNQSL".split("/"), 2)).isNull();
        assertThat(LogHttpServer.uriSegment("/DOWNQSLNOQSL".split("/"), 2)).isNull();
    }

    @Test
    public void presentButEmptySegment_isTreatedAsAbsent() {
        // "/SHOWQSL//x".split("/") == ["", "SHOWQSL", "", "x"], so index 2 is a
        // present-but-empty segment. It must read as null (not "") so an empty
        // month never reaches showQSLByMonth/downQSLByMonth where
        // month.length() == 0 could match unintended rows.
        String[] uriList = "/SHOWQSL//x".split("/");
        assertThat(uriList[2]).isEmpty();
        assertThat(LogHttpServer.uriSegment(uriList, 2)).isNull();
        // Same for a direct empty element.
        assertThat(LogHttpServer.uriSegment(new String[] {"", "DOWNQSL", ""}, 2)).isNull();
    }

    @Test
    public void outOfRangeAndDegenerateInputs_areNull() {
        String[] one = {"", "SHOWQSL"};
        assertThat(LogHttpServer.uriSegment(one, 5)).isNull();
        assertThat(LogHttpServer.uriSegment(one, -1)).isNull();
        assertThat(LogHttpServer.uriSegment(new String[0], 0)).isNull();
        assertThat(LogHttpServer.uriSegment(null, 2)).isNull();
    }

    @Test
    public void downloadFileName_usesMonthWhenPresent() {
        // Identical to the old String.format("log%s.adi", uriList[2]) when the month exists.
        assertThat(LogHttpServer.downloadFileName("202401")).isEqualTo("log202401.adi");
        assertThat(LogHttpServer.downloadFileName("")).isEqualTo("log.adi");
    }

    @Test
    public void downloadFileName_fallsBackWhenMonthMissing() {
        // A bare GET /DOWNQSL must produce a sane filename, not crash the header build.
        assertThat(LogHttpServer.downloadFileName(null)).isEqualTo("log.adi");
    }
}
