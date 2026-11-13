package com.k1af.ft8af.ui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.TimeZone;

/**
 * Unit tests for the date parsing/validation/formatting helpers in
 * {@link ExportLogSheet} that back the Export QSOs date-range picker. These
 * guarantee the picker path and the typed path both yield the strict
 * {@code YYYYMMDD} string the {@code ShareLogs} query layer expects.
 */
public class ExportLogSheetDateTest {

    // MaterialDatePicker.todayInUtcMilliseconds() is UTC-based, so all conversions
    // must be UTC regardless of the test machine's default zone.
    private static final long UTC_20260722 = utc(2026, 7, 22);

    private static long utc(int year, int month, int day) {
        java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(year, month - 1, day, 0, 0, 0);
        return c.getTimeInMillis();
    }

    // ---- isValidOptionalDate: empty means "no bound" and is valid ----

    @Test
    public void nullIsValid() {
        assertThat(ExportLogSheet.isValidOptionalDate(null)).isTrue();
    }

    @Test
    public void emptyIsValid() {
        assertThat(ExportLogSheet.isValidOptionalDate("")).isTrue();
        assertThat(ExportLogSheet.isValidOptionalDate("   ")).isTrue();
    }

    @Test
    public void wellFormedDateIsValid() {
        assertThat(ExportLogSheet.isValidOptionalDate("20260722")).isTrue();
    }

    @Test
    public void surroundingWhitespaceIsTolerated() {
        assertThat(ExportLogSheet.isValidOptionalDate("  20260722  ")).isTrue();
    }

    // ---- isValidOptionalDate: malformed non-empty input is rejected ----

    @Test
    public void wrongLengthIsInvalid() {
        assertThat(ExportLogSheet.isValidOptionalDate("2026072")).isFalse();  // 7 digits
        assertThat(ExportLogSheet.isValidOptionalDate("202607221")).isFalse(); // 9 digits
    }

    @Test
    public void nonNumericIsInvalid() {
        assertThat(ExportLogSheet.isValidOptionalDate("2026-07-22")).isFalse();
        assertThat(ExportLogSheet.isValidOptionalDate("2026Jul2")).isFalse();
    }

    @Test
    public void impossibleMonthIsInvalid() {
        assertThat(ExportLogSheet.isValidOptionalDate("20261301")).isFalse(); // month 13
        assertThat(ExportLogSheet.isValidOptionalDate("20260001")).isFalse(); // month 00
    }

    @Test
    public void impossibleDayIsInvalid() {
        assertThat(ExportLogSheet.isValidOptionalDate("20260230")).isFalse(); // Feb 30
        assertThat(ExportLogSheet.isValidOptionalDate("20260231")).isFalse(); // Feb 31
        assertThat(ExportLogSheet.isValidOptionalDate("20260732")).isFalse(); // day 32
        assertThat(ExportLogSheet.isValidOptionalDate("20260700")).isFalse(); // day 00
    }

    @Test
    public void leapDayValidatesCorrectly() {
        assertThat(ExportLogSheet.isValidOptionalDate("20240229")).isTrue();  // 2024 is a leap year
        assertThat(ExportLogSheet.isValidOptionalDate("20260229")).isFalse(); // 2026 is not
    }

    @Test
    public void yearZeroIsRejected() {
        // SimpleDateFormat would otherwise silently normalize this — the round-trip guards it.
        assertThat(ExportLogSheet.isValidOptionalDate("00000101")).isFalse();
    }

    // ---- parseYyyyMmddUtc: preselect value for the picker ----

    @Test
    public void parseReturnsUtcMidnightMillis() {
        assertThat(ExportLogSheet.parseYyyyMmddUtc("20260722")).isEqualTo(UTC_20260722);
    }

    @Test
    public void parseInvalidReturnsNull() {
        assertThat(ExportLogSheet.parseYyyyMmddUtc("not-a-date")).isNull();
        assertThat(ExportLogSheet.parseYyyyMmddUtc("")).isNull();
        assertThat(ExportLogSheet.parseYyyyMmddUtc(null)).isNull();
    }

    // ---- formatYyyyMmddUtc: what a picker selection becomes ----

    @Test
    public void formatProducesYyyyMmdd() {
        assertThat(ExportLogSheet.formatYyyyMmddUtc(UTC_20260722)).isEqualTo("20260722");
    }

    @Test
    public void formatAndParseRoundTrip() {
        Long millis = ExportLogSheet.parseYyyyMmddUtc("20250101");
        assertThat(millis).isNotNull();
        assertThat(ExportLogSheet.formatYyyyMmddUtc(millis)).isEqualTo("20250101");
    }
}
