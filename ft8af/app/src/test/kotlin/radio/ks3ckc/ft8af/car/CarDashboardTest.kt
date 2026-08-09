package radio.ks3ckc.ft8af.car

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test

/**
 * Tests for the pure Android Auto dashboard helpers — the POTA/ROTA/session
 * activation block, the "N to validate" figure, mileage formatting, and the
 * "minutes ago" clamp. The Screen only resolves the returned specs, so these
 * tests pin the row-selection and formatting rules.
 */
class CarDashboardTest {

    // -- potaValidateSpec --

    @Test
    fun potaValidate_belowTarget_countsRemaining() {
        val spec = potaValidateSpec(qsoCount = 3)
        assertThat(spec.resId).isEqualTo(R.string.car_pota_to_validate)
        assertThat(spec.args).containsExactly(POTA_ACTIVATION_TARGET - 3)
    }

    @Test
    fun potaValidate_zeroQsos_remainingIsFullTarget() {
        assertThat(potaValidateSpec(0).args).containsExactly(POTA_ACTIVATION_TARGET)
    }

    @Test
    fun potaValidate_atOrAboveTarget_isValidated() {
        for (count in listOf(POTA_ACTIVATION_TARGET, POTA_ACTIVATION_TARGET + 5)) {
            assertThat(potaValidateSpec(count).resId).isEqualTo(R.string.car_pota_validated)
        }
    }

    // -- formatMiles --

    @Test
    fun formatMiles_oneDecimal_localeIndependent() {
        assertThat(formatMiles(0.0)).isEqualTo("0.0")
        assertThat(formatMiles(12.34)).isEqualTo("12.3")
        assertThat(formatMiles(12.36)).isEqualTo("12.4")
    }

    // -- minutesAgo --

    @Test
    fun minutesAgo_nullOrNonPositiveTimestamp_isNull() {
        assertThat(minutesAgo(nowMs = 60_000L, thenMs = null)).isNull()
        assertThat(minutesAgo(nowMs = 60_000L, thenMs = 0L)).isNull()
        assertThat(minutesAgo(nowMs = 60_000L, thenMs = -5L)).isNull()
    }

    @Test
    fun minutesAgo_futureTimestamp_isNull() {
        assertThat(minutesAgo(nowMs = 1_000L, thenMs = 5_000L)).isNull()
    }

    @Test
    fun minutesAgo_flooredToWholeMinutes() {
        assertThat(minutesAgo(nowMs = 41 * 60_000L, thenMs = 0L + 1L)).isEqualTo(40)
        assertThat(minutesAgo(nowMs = 90_000L, thenMs = 1L)).isEqualTo(1)
        assertThat(minutesAgo(nowMs = 30_000L, thenMs = 1L)).isEqualTo(0)
    }

    // -- buildCarSessionRow --

    @Test
    fun sessionRow_titleCarriesCount() {
        val row = buildCarSessionRow(5, "JA1XYZ", "20m", 41)
        assertThat(row.title.resId).isEqualTo(R.string.car_session_line)
        assertThat(row.title.args).containsExactly(5)
    }

    @Test
    fun sessionRow_fullLastLogged_withBand() {
        val row = buildCarSessionRow(5, "JA1XYZ", "20m", 41)
        assertThat(row.secondary?.resId).isEqualTo(R.string.car_session_last)
        assertThat(row.secondary?.args).containsExactly("JA1XYZ", "20m", 41).inOrder()
    }

    @Test
    fun sessionRow_lastLogged_blankBandDropsToNoBandForm() {
        for (band in listOf(null, "", "  ")) {
            val row = buildCarSessionRow(5, "JA1XYZ", band, 41)
            assertThat(row.secondary?.resId).isEqualTo(R.string.car_session_last_noband)
            assertThat(row.secondary?.args).containsExactly("JA1XYZ", 41).inOrder()
        }
    }

    @Test
    fun sessionRow_noneWhenCallsignOrMinutesMissing() {
        assertThat(buildCarSessionRow(0, null, "20m", 41).secondary?.resId)
            .isEqualTo(R.string.car_session_none)
        assertThat(buildCarSessionRow(0, "  ", "20m", 41).secondary?.resId)
            .isEqualTo(R.string.car_session_none)
        assertThat(buildCarSessionRow(3, "JA1XYZ", "20m", null).secondary?.resId)
            .isEqualTo(R.string.car_session_none)
    }

    // -- buildCarActivationRows --

    private fun rows(
        potaActive: Boolean = false,
        potaParkRefsDisplay: String? = null,
        potaQsoCount: Int = 0,
        rotaActive: Boolean = false,
        rotaTripName: String? = null,
        rotaQsoCount: Int = 0,
        rotaMiles: Double = 0.0,
        sessionQsoCount: Int = 5,
        lastQsoCallsign: String? = "JA1XYZ",
        lastQsoBandName: String? = "20m",
        lastQsoMinutesAgo: Int? = 41,
    ) = buildCarActivationRows(
        potaActive, potaParkRefsDisplay, potaQsoCount,
        rotaActive, rotaTripName, rotaQsoCount, rotaMiles,
        sessionQsoCount, lastQsoCallsign, lastQsoBandName, lastQsoMinutesAgo,
    )

    @Test
    fun activationRows_potaOnly_potaRowWithValidateSecondary() {
        val r = rows(potaActive = true, potaParkRefsDisplay = "K-1234", potaQsoCount = 12)
        assertThat(r).hasSize(1)
        assertThat(r[0].title.resId).isEqualTo(R.string.car_pota_line)
        assertThat(r[0].title.args).containsExactly("K-1234", 12).inOrder()
        // 12 >= target → validated
        assertThat(r[0].secondary?.resId).isEqualTo(R.string.car_pota_validated)
    }

    @Test
    fun activationRows_rotaOnly_rotaRowWithMilesSecondary() {
        val r = rows(rotaActive = true, rotaTripName = "Route 66", rotaQsoCount = 0, rotaMiles = 0.0)
        assertThat(r).hasSize(1)
        assertThat(r[0].title.resId).isEqualTo(R.string.car_rota_line)
        assertThat(r[0].title.args).containsExactly("Route 66", 0).inOrder()
        assertThat(r[0].secondary?.resId).isEqualTo(R.string.car_rota_miles)
        assertThat(r[0].secondary?.args).containsExactly("0.0")
    }

    @Test
    fun activationRows_bothActive_potaThenRota_noSession() {
        val r = rows(
            potaActive = true, potaParkRefsDisplay = "K-1234", potaQsoCount = 3,
            rotaActive = true, rotaTripName = "Route 66", rotaQsoCount = 2, rotaMiles = 8.7,
        )
        assertThat(r).hasSize(2)
        assertThat(r[0].title.resId).isEqualTo(R.string.car_pota_line)
        assertThat(r[0].secondary?.resId).isEqualTo(R.string.car_pota_to_validate)
        assertThat(r[1].title.resId).isEqualTo(R.string.car_rota_line)
    }

    @Test
    fun activationRows_neitherActive_collapsesToSessionRow() {
        val r = rows()
        assertThat(r).hasSize(1)
        assertThat(r[0].title.resId).isEqualTo(R.string.car_session_line)
        assertThat(r[0].secondary?.resId).isEqualTo(R.string.car_session_last)
    }

    @Test
    fun activationRows_activeFlagButBlankLabel_treatedAsInactive() {
        // POTA "active" with no park ref and ROTA "active" with no trip name both
        // drop out, so the block collapses to the session row.
        val r = rows(
            potaActive = true, potaParkRefsDisplay = "  ",
            rotaActive = true, rotaTripName = "",
        )
        assertThat(r).hasSize(1)
        assertThat(r[0].title.resId).isEqualTo(R.string.car_session_line)
    }

    // -- carDecodesSecondary --

    @Test
    fun decodesSecondary_nullWhenZero_specWhenPositive() {
        assertThat(carDecodesSecondary(0)).isNull()
        val spec = carDecodesSecondary(12)
        assertThat(spec?.resId).isEqualTo(R.string.car_decodes_last_cycle)
        assertThat(spec?.args).containsExactly(12)
    }
}
