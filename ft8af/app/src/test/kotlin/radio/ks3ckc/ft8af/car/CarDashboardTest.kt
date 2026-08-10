package radio.ks3ckc.ft8af.car

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the pure Android Auto dashboard helpers (design "1a" Pane): the
 * status/band/POTA/ROTA/session row builders, their colored badges and emphasis
 * spans, the "N to validate" figure, mileage formatting, and the "minutes ago"
 * clamp. The Screen only rasterizes the badges and applies the spans, so these
 * tests pin the row content, ordering, and color rules.
 */
class CarDashboardTest {

    private fun text(spans: List<CarSpan>) = spans.joinToString("") { it.text }

    // -- carStatusDashRow --

    @Test
    fun status_receiving_greenCountdown_targetAndSnr() {
        val row = carStatusDashRow(
            txState = CarTxState.ARMED_RX,
            secondsRemaining = 9,
            target = "W1ABC",
            snrLabel = "-8 dB",
            txMessage = null,
            hunting = false,
        )
        assertThat(text(row.title)).isEqualTo("Receiving · next TX in 9 s")
        // The countdown run is green; the state word is not.
        assertThat(row.title.first { it.text.contains("9 s") }.color).isEqualTo(CarSpanColor.GREEN)
        assertThat(row.title.first().color).isNull()
        assertThat(text(row.secondary!!)).isEqualTo("Waiting for W1ABC · -8 dB · no TX queued")
        assertThat(row.secondary!!.first { it.text == "-8 dB" }.color).isEqualTo(CarSpanColor.YELLOW)
        // Armed → green dot badge (empty glyph).
        assertThat(row.badge).isEqualTo(CarBadge("", CAR_GREEN_FG, CAR_GREEN_BG))
        assertThat(row.priority).isEqualTo(CAR_ROW_HEADLINE)
    }

    @Test
    fun status_transmitting_showsWorkingAndMessage() {
        val row = carStatusDashRow(
            txState = CarTxState.TRANSMITTING,
            secondsRemaining = 4,
            target = "W1ABC",
            snrLabel = "-8 dB",
            txMessage = "W1ABC K7XYZ RR73",
            hunting = false,
        )
        assertThat(text(row.title)).isEqualTo("Transmitting · 4 s left")
        assertThat(text(row.secondary!!)).isEqualTo("Working W1ABC · -8 dB · W1ABC K7XYZ RR73")
    }

    @Test
    fun status_txOff_grayBadge_monitoring_noCountdownColor_noTxSegment() {
        val row = carStatusDashRow(
            txState = CarTxState.OFF,
            secondsRemaining = 7,
            target = null,
            snrLabel = null,
            txMessage = null,
            hunting = false,
        )
        assertThat(text(row.title)).isEqualTo("Monitoring · TX off")
        assertThat(row.title.last().color).isNull()
        // No target and TX off → secondary is just the monitoring line, no "no TX queued".
        assertThat(text(row.secondary!!)).isEqualTo("Monitoring — TX off")
        assertThat(row.badge).isEqualTo(CarBadge("", CAR_GRAY_FG, CAR_GRAY_BG))
    }

    @Test
    fun status_callingCq_and_hunting_secondaries() {
        val cq = carStatusDashRow(CarTxState.ARMED_RX, 5, null, null, null, hunting = false)
        assertThat(text(cq.secondary!!)).isEqualTo("Calling CQ · no TX queued")
        val hunt = carStatusDashRow(CarTxState.ARMED_RX, 5, null, null, null, hunting = true)
        assertThat(text(hunt.secondary!!)).isEqualTo("Hunting for CQ · no TX queued")
    }

    // -- carBandDashRow --

    @Test
    fun band_badgeIsBandName_titleHasMhzAndMode_decodesSecondary() {
        val row = carBandDashRow(freqHz = 14_074_000L, bandName = "20m", modeName = "FT8", decodeCount = 12)
        assertThat(row.badge).isEqualTo(CarBadge("20m", CAR_BLUE_FG, CAR_BLUE_BG))
        assertThat(text(row.title)).isEqualTo("14.074 MHz · FT8")
        assertThat(text(row.secondary!!)).isEqualTo("12 decodes last cycle")
        assertThat(row.priority).isEqualTo(CAR_ROW_BAND)
    }

    @Test
    fun band_silentCycle_dropsDecodesSecondary_blankBandFallsBackToRf() {
        val row = carBandDashRow(freqHz = 7_074_000L, bandName = "  ", modeName = "FT8", decodeCount = 0)
        assertThat(row.secondary).isNull()
        assertThat(row.badge.text).isEqualTo("RF")
    }

    // -- carPotaDashRow --

    @Test
    fun pota_nullWhenNoActivation() {
        assertThat(carPotaDashRow(null, 3)).isNull()
        assertThat(carPotaDashRow("  ", 3)).isNull()
    }

    @Test
    fun pota_greenQsoCount_and_toValidateSecondary() {
        val row = carPotaDashRow("K-1234", 3)!!
        assertThat(text(row.title)).isEqualTo("POTA K-1234 · 3 QSOs")
        assertThat(row.title.first { it.text.contains("QSOs") }.color).isEqualTo(CarSpanColor.GREEN)
        assertThat(text(row.secondary!!)).isEqualTo("7 more to validate the activation")
        assertThat(row.badge).isEqualTo(CarBadge("P", CAR_AMBER_FG, CAR_AMBER_BG))
    }

    @Test
    fun pota_atOrAboveTarget_isValidated() {
        for (count in listOf(POTA_ACTIVATION_TARGET, POTA_ACTIVATION_TARGET + 5)) {
            assertThat(text(carPotaDashRow("K-1234", count)!!.secondary!!)).isEqualTo("Activation validated")
        }
    }

    // -- carRotaDashRow --

    @Test
    fun rota_nullWhenInactiveOrBlank() {
        assertThat(carRotaDashRow(false, "Route 66", 5, 12.0)).isNull()
        assertThat(carRotaDashRow(true, "  ", 5, 12.0)).isNull()
    }

    @Test
    fun rota_titleAndMilesSecondary() {
        val row = carRotaDashRow(true, "Route 66", 0, 0.0)!!
        assertThat(text(row.title)).isEqualTo("ROTA Route 66 · 0 QSOs")
        assertThat(text(row.secondary!!)).isEqualTo("0.0 mi driven this activation")
        assertThat(row.badge).isEqualTo(CarBadge("R", CAR_AMBER_FG, CAR_AMBER_BG))
    }

    // -- carSessionDashRow --

    @Test
    fun session_title_and_lastLoggedVariants() {
        val full = carSessionDashRow(5, "JA1XYZ", "20m", 41)
        assertThat(text(full.title)).isEqualTo("Session · 5 QSOs")
        assertThat(text(full.secondary!!)).isEqualTo("Last logged JA1XYZ · 20m · 41 min")
        assertThat(full.badge).isEqualTo(CarBadge("Σ", CAR_GRAY_FG, CAR_GRAY_BG))

        val noBand = carSessionDashRow(5, "JA1XYZ", "  ", 41)
        assertThat(text(noBand.secondary!!)).isEqualTo("Last logged JA1XYZ · 41 min")

        val none = carSessionDashRow(0, null, "20m", 41)
        assertThat(text(none.secondary!!)).isEqualTo("No QSOs logged yet")
        val noneMinutes = carSessionDashRow(3, "JA1XYZ", "20m", null)
        assertThat(text(noneMinutes.secondary!!)).isEqualTo("No QSOs logged yet")
    }

    // -- buildCarDashboardRows --

    private val status = carStatusDashRow(CarTxState.ARMED_RX, 9, "W1ABC", "-8 dB", null, false)
    private val band = carBandDashRow(14_074_000L, "20m", "FT8", 12)
    private val session = carSessionDashRow(5, "JA1XYZ", "20m", 41)

    @Test
    fun dashboard_bothActive_statusBandPotaRota_noSession() {
        val rows = buildCarDashboardRows(
            status, band,
            carPotaDashRow("K-1234", 3), carRotaDashRow(true, "Route 66", 2, 8.7), session,
        )
        assertThat(rows.map { it.badge.text }).containsExactly("", "20m", "P", "R").inOrder()
        assertThat(text(rows[3].title)).isEqualTo("ROTA Route 66 · 2 QSOs")
    }

    @Test
    fun dashboard_neitherActive_collapsesToSession() {
        val rows = buildCarDashboardRows(status, band, null, null, session)
        assertThat(rows).hasSize(3)
        assertThat(rows[2].badge.text).isEqualTo("Σ")
    }

    @Test
    fun dashboard_onlyPota_noSessionRow() {
        val rows = buildCarDashboardRows(status, band, carPotaDashRow("K-1234", 3), null, session)
        assertThat(rows.map { it.badge.text }).containsExactly("", "20m", "P").inOrder()
    }

    // -- formatMiles / minutesAgo --

    @Test
    fun formatMiles_oneDecimal_localeIndependent() {
        assertThat(formatMiles(0.0)).isEqualTo("0.0")
        assertThat(formatMiles(12.34)).isEqualTo("12.3")
        assertThat(formatMiles(12.36)).isEqualTo("12.4")
    }

    @Test
    fun minutesAgo_nullOnMissingOrFutureTimestamp_flooredOtherwise() {
        assertThat(minutesAgo(60_000L, null)).isNull()
        assertThat(minutesAgo(60_000L, 0L)).isNull()
        assertThat(minutesAgo(1_000L, 5_000L)).isNull()
        assertThat(minutesAgo(41 * 60_000L + 1L, 1L)).isEqualTo(41)
        assertThat(minutesAgo(30_000L, 1L)).isEqualTo(0)
    }

    // -- singular/plural labels --

    @Test
    fun qsosLabel_singularOnlyAtOne() {
        assertThat(qsosLabel(0)).isEqualTo("0 QSOs")
        assertThat(qsosLabel(1)).isEqualTo("1 QSO")
        assertThat(qsosLabel(2)).isEqualTo("2 QSOs")
    }

    @Test
    fun decodesLabel_singularOnlyAtOne() {
        assertThat(decodesLabel(1)).isEqualTo("1 decode")
        assertThat(decodesLabel(12)).isEqualTo("12 decodes")
    }

    @Test
    fun rows_useSingularAtCountOfOne() {
        assertThat(text(carPotaDashRow("K-1234", 1)!!.title)).isEqualTo("POTA K-1234 · 1 QSO")
        assertThat(text(carRotaDashRow(true, "Route 66", 1, 1.0)!!.title)).isEqualTo("ROTA Route 66 · 1 QSO")
        assertThat(text(carSessionDashRow(1, "JA1XYZ", "20m", 41).title)).isEqualTo("Session · 1 QSO")
        assertThat(text(carBandDashRow(14_074_000L, "20m", "FT8", 1).secondary!!)).isEqualTo("1 decode last cycle")
    }
}
