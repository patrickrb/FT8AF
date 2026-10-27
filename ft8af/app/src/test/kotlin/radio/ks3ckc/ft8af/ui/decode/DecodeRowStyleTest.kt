package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit-tests [decodeRowDimAlpha], the row-opacity decision extracted from the
 * DecodeRow graphicsLayer lambda (which can't be unit-tested directly).
 *
 * Pure math — no Android types, so no Robolectric runner needed.
 */
class DecodeRowStyleTest {

    @Test
    fun noiseRow_isDimmedButReadable() {
        val alpha = decodeRowDimAlpha(isInQsoWithOther = true)

        assertThat(alpha).isEqualTo(0.8f)
        // Must stay brighter than the old 0.55 floor (issue #332) — a regression
        // back to the unreadable dimming fails here.
        assertThat(alpha).isGreaterThan(0.55f)
    }

    @Test
    fun directRow_isFullOpacity() {
        assertThat(decodeRowDimAlpha(isInQsoWithOther = false)).isEqualTo(1f)
    }

    @Test
    fun secondaryFieldText_prefersTheGridWhenPresent() {
        assertThat(secondaryFieldText(maidenGrid = "EM98", extraInfo = "-04")).isEqualTo("EM98")
    }

    @Test
    fun secondaryFieldText_fallsBackToExtraInfoWhenNotAGrid() {
        // Real-device finding: a signal-report exchange ("-04") never matches the
        // native decoder's strict grid-shape check, so maidenGrid comes back blank
        // and the report was only visible on the raw message text line -- which
        // compact mode hides entirely, making the row look like nothing decoded.
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "-04")).isEqualTo("-04")
        assertThat(secondaryFieldText(maidenGrid = null, extraInfo = "R-09")).isEqualTo("R-09")
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "RR73")).isEqualTo("RR73")
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "73")).isEqualTo("73")
    }

    @Test
    fun secondaryFieldText_blankWhenNeitherIsSet() {
        assertThat(secondaryFieldText(maidenGrid = null, extraInfo = null)).isEmpty()
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "")).isEmpty()
    }

    @Test
    fun secondaryFieldText_trimsExtraInfo() {
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "  -04  ")).isEqualTo("-04")
    }

    @Test
    fun decodeRowLabels_expandedShowsCallingPlusCqOrToYou() {
        assertThat(decodeRowLabels(isTarget = false, isCQ = true, isToMe = false, compact = false))
            .containsExactly(DecodeRowLabel.CQ)
        assertThat(decodeRowLabels(isTarget = false, isCQ = false, isToMe = true, compact = false))
            .containsExactly(DecodeRowLabel.TO_YOU)
        // The target calling CQ stacks both, CALLING first.
        assertThat(decodeRowLabels(isTarget = true, isCQ = true, isToMe = false, compact = false))
            .containsExactly(DecodeRowLabel.CALLING, DecodeRowLabel.CQ).inOrder()
        // CQ wins over TO YOU if a frame somehow reads as both.
        assertThat(decodeRowLabels(isTarget = false, isCQ = true, isToMe = true, compact = false))
            .containsExactly(DecodeRowLabel.CQ)
        assertThat(decodeRowLabels(isTarget = false, isCQ = false, isToMe = false, compact = false))
            .isEmpty()
    }

    @Test
    fun decodeRowLabels_compactKeepsOnlyCalling() {
        assertThat(decodeRowLabels(isTarget = true, isCQ = true, isToMe = false, compact = true))
            .containsExactly(DecodeRowLabel.CALLING)
        assertThat(decodeRowLabels(isTarget = true, isCQ = false, isToMe = true, compact = true))
            .containsExactly(DecodeRowLabel.CALLING)
        // CQ and TO YOU are already carried by the message text and status pill.
        assertThat(decodeRowLabels(isTarget = false, isCQ = true, isToMe = false, compact = true)).isEmpty()
        assertThat(decodeRowLabels(isTarget = false, isCQ = false, isToMe = true, compact = true)).isEmpty()
    }

    @Test
    fun decodeSnrText_expandedSpellsOutTheUnit() {
        assertThat(decodeSnrText(hasSnr = true, snr = -12, withUnit = true)).isEqualTo("-12 dB")
        assertThat(decodeSnrText(hasSnr = true, snr = 4, withUnit = true)).isEqualTo("4 dB")
        assertThat(decodeSnrText(hasSnr = false, snr = 0, withUnit = true)).isEqualTo("-- dB")
    }

    @Test
    fun decodeSnrText_compactIsTheBareNumber() {
        assertThat(decodeSnrText(hasSnr = true, snr = -12, withUnit = false)).isEqualTo("-12")
        assertThat(decodeSnrText(hasSnr = true, snr = 0, withUnit = false)).isEqualTo("0")
        // No SNR from the decoder: a placeholder, never a misleading "0".
        assertThat(decodeSnrText(hasSnr = false, snr = 0, withUnit = false)).isEqualTo("--")
    }

    @Test
    fun secondaryFieldText_leavesLongContestExchangesOnTheMessageLine() {
        // These carry a callsignFrom too, but would wrap or crowd the narrow slot.
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "TU; R 579 MA")).isEmpty()
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "R 2A EMA")).isEmpty()
        assertThat(secondaryFieldText(maidenGrid = null, extraInfo = "TU; R+05 EM")).isEmpty()
        // A real grid still wins whatever extraInfo holds.
        assertThat(secondaryFieldText(maidenGrid = "EM15", extraInfo = "TU; R 579 MA")).isEqualTo("EM15")
    }

    @Test
    fun isShortExchangeToken_acceptsOnlyStandardExchangeTokens() {
        for (token in listOf("-04", "+10", "R-09", "R+10", "RRR", "RR73", "73")) {
            assertThat(isShortExchangeToken(token)).isTrue()
        }
        for (token in listOf("", "R 579", "2A EMA", "TU;", "-04 5", "RR73;")) {
            assertThat(isShortExchangeToken(token)).isFalse()
        }
    }

    // ---- compact row columns -------------------------------------------------
    // Row widths measured on device: motorola edge 2022 (432dp screen) leaves the
    // row ~383dp; Galaxy A32 (384dp) ~335dp; a typical 360dp phone ~311dp.

    private fun messageRoom(availableDp: Int, layout: CompactRowLayout): Int =
        availableDp - layout.columns.sumOf { it.widthDp + COMPACT_GAP_DP } -
            COMPACT_GAP_DP - layout.pillSlotDp

    @Test
    fun compactRowLayout_wideRowKeepsEverythingInOrder() {
        val layout = compactRowLayout(availableDp = 500f, hasOperatorGrid = true, showHeading = true)
        assertThat(layout.columns).containsExactly(
            CompactMetaColumn.SNR, CompactMetaColumn.FREQ, CompactMetaColumn.DT, CompactMetaColumn.HEADING,
        ).inOrder()
        assertThat(layout.pillDotOnly).isFalse()
    }

    @Test
    fun compactRow_hasNoDistanceColumn() {
        // User decision: the message text takes priority over distance in
        // compact; expanded view still shows distance.
        assertThat(CompactMetaColumn.values().map { it.name }).doesNotContain("DISTANCE")
    }

    @Test
    fun compactRowLayout_messageKeepsItsMinimumOnEveryMeasuredDevice() {
        // The real-device bug: the A32 left the message ~122dp (~16 chars).
        for (width in listOf(383, 335, 311)) {
            for (hasGrid in listOf(true, false)) {
                val layout = compactRowLayout(width.toFloat(), hasOperatorGrid = hasGrid, showHeading = false)
                assertThat(messageRoom(width, layout)).isAtLeast(COMPACT_MIN_MESSAGE_DP)
            }
        }
    }

    @Test
    fun compactRowLayout_givesUpMetadataBeforeThePillLabel() {
        // motorola edge 2022: everything fits -- SNR, Hz, DT and the full pill.
        val moto = compactRowLayout(availableDp = 383f, hasOperatorGrid = true, showHeading = false)
        assertThat(moto.columns)
            .containsExactly(CompactMetaColumn.SNR, CompactMetaColumn.FREQ, CompactMetaColumn.DT).inOrder()
        assertThat(moto.pillDotOnly).isFalse()
        // Galaxy A32: only Hz goes; DT and the full pill label stay (user choice).
        val a32 = compactRowLayout(availableDp = 335f, hasOperatorGrid = true, showHeading = false)
        assertThat(a32.columns).containsExactly(CompactMetaColumn.SNR, CompactMetaColumn.DT).inOrder()
        assertThat(a32.pillDotOnly).isFalse()
        // 360dp phone: DT goes too, still with the full pill.
        val small = compactRowLayout(availableDp = 311f, hasOperatorGrid = true, showHeading = false)
        assertThat(small.columns).containsExactly(CompactMetaColumn.SNR)
        assertThat(small.pillDotOnly).isFalse()
        // Very narrow: the pill shrinks to its dot last; SNR always stays.
        val tiny = compactRowLayout(availableDp = 250f, hasOperatorGrid = true, showHeading = false)
        assertThat(tiny.columns).containsExactly(CompactMetaColumn.SNR)
        assertThat(tiny.pillDotOnly).isTrue()
    }

    @Test
    fun compactRowLayout_noOperatorGridLeavesOutHeading() {
        val layout = compactRowLayout(availableDp = 500f, hasOperatorGrid = false, showHeading = true)
        assertThat(layout.columns).doesNotContain(CompactMetaColumn.HEADING)
    }

    @Test
    fun compactRowLayout_headingIsTheFirstColumnToGo() {
        // Wide enough for everything: heading sits after DT in its own slot.
        val wide = compactRowLayout(availableDp = 420f, hasOperatorGrid = true, showHeading = true)
        assertThat(wide.columns).containsExactly(
            CompactMetaColumn.SNR, CompactMetaColumn.FREQ, CompactMetaColumn.DT, CompactMetaColumn.HEADING,
        ).inOrder()
        // motorola edge 2022 with heading on: heading goes, Hz and DT stay.
        val moto = compactRowLayout(availableDp = 383f, hasOperatorGrid = true, showHeading = true)
        assertThat(moto.columns)
            .containsExactly(CompactMetaColumn.SNR, CompactMetaColumn.FREQ, CompactMetaColumn.DT).inOrder()
        assertThat(moto.pillDotOnly).isFalse()
        assertThat(messageRoom(383, moto)).isAtLeast(COMPACT_MIN_MESSAGE_DP)
    }

    @Test
    fun decodeRowMetrics_compactRowIsTightAroundOneLine() {
        // Measured on device before: 37dp per compact row, ~11 rows on screen.
        val compact = decodeRowMetrics(compact = true)
        assertThat(decodeRowHeightDp(compact, COMPACT_ROW_CONTENT_DP)).isEqualTo(26)
        // The accent bar never makes a compact row taller than its content.
        assertThat(compact.accentBarDp).isAtMost(COMPACT_ROW_CONTENT_DP)
    }

    @Test
    fun decodeRowMetrics_expandedKeepsItsSpacing() {
        assertThat(decodeRowMetrics(compact = false))
            .isEqualTo(DecodeRowMetrics(outerVerticalDp = 3, innerVerticalDp = 10, accentBarDp = 52))
    }

    @Test
    fun compactMessageEmphasis_marksRepliesToMeThenTheTarget() {
        assertThat(compactMessageEmphasis(isToMe = true, isTarget = false)).isEqualTo(CompactMessageEmphasis.TO_YOU)
        assertThat(compactMessageEmphasis(isToMe = true, isTarget = true)).isEqualTo(CompactMessageEmphasis.TO_YOU)
        assertThat(compactMessageEmphasis(isToMe = false, isTarget = true)).isEqualTo(CompactMessageEmphasis.TARGET)
        assertThat(compactMessageEmphasis(isToMe = false, isTarget = false)).isEqualTo(CompactMessageEmphasis.NORMAL)
    }
}
