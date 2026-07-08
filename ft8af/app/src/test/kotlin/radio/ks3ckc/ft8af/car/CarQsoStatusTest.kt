package radio.ks3ckc.ft8af.car

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test

/**
 * Tests for the pure Android Auto status mapper. [buildCarQsoStatus] is the
 * decision logic behind the car pane — the Screen only resolves the returned
 * specs — so these tests pin the headline/row selection rules.
 */
class CarQsoStatusTest {

    private fun build(
        isActivated: Boolean = false,
        isTransmitting: Boolean = false,
        functionOrder: Int = 6,
        toCallsign: String? = null,
        snr: Int? = null,
        transmittingMessage: String? = null,
        myTxSequential: Int = 0,
        currentSlot: Int = 0,
        secondsRemaining: Int = 7,
        freqHz: Long = 14_074_000L,
        bandName: String = "20m",
        modeName: String = "FT8",
        huntEnabled: Boolean = false,
        huntCallsCQ: Boolean = false,
    ) = buildCarQsoStatus(
        isActivated = isActivated,
        isTransmitting = isTransmitting,
        functionOrder = functionOrder,
        toCallsign = toCallsign,
        snr = snr,
        transmittingMessage = transmittingMessage,
        myTxSequential = myTxSequential,
        currentSlot = currentSlot,
        secondsRemaining = secondsRemaining,
        freqHz = freqHz,
        bandName = bandName,
        modeName = modeName,
        huntEnabled = huntEnabled,
        huntCallsCQ = huntCallsCQ,
    )

    @Test
    fun notActivated_headlineIsMonitoring_seqLineNull_txStateOff() {
        val status = build(isActivated = false)
        assertThat(status.headline.resId).isEqualTo(R.string.car_monitoring)
        assertThat(status.seqLine).isNull()
        assertThat(status.txState).isEqualTo(CarTxState.OFF)
    }

    @Test
    fun activatedWithoutTarget_headlineIsCallingCq() {
        for (callsign in listOf(null, "", "CQ")) {
            val status = build(isActivated = true, toCallsign = callsign)
            assertThat(status.headline.resId).isEqualTo(R.string.qsopanel_calling_cq)
            assertThat(status.snrLabel).isNull()
        }
    }

    @Test
    fun activatedWithoutTarget_huntListening_headlineIsHunting() {
        // Pure hunt (autoFollowCQ, not the CQ hybrid) is silently listening for
        // someone else's CQ, so the headline must read "Listening for CQ".
        for (callsign in listOf(null, "", "CQ")) {
            val status = build(
                isActivated = true,
                toCallsign = callsign,
                huntEnabled = true,
                huntCallsCQ = false,
            )
            assertThat(status.headline.resId).isEqualTo(R.string.qsopanel_hunting)
        }
    }

    @Test
    fun activatedWithoutTarget_huntCqHybrid_headlineStaysCallingCq() {
        // Hunt+CQ hybrid does transmit CQ when idle, so "Calling CQ" is correct.
        val status = build(
            isActivated = true,
            toCallsign = null,
            huntEnabled = true,
            huntCallsCQ = true,
        )
        assertThat(status.headline.resId).isEqualTo(R.string.qsopanel_calling_cq)
    }

    @Test
    fun targetAndTransmitting_headlineQsoingWith_messageLineSet() {
        val status = build(
            isActivated = true,
            isTransmitting = true,
            toCallsign = "W1XYZ",
            transmittingMessage = "W1XYZ KS3CKC R-12",
        )
        assertThat(status.headline.resId).isEqualTo(R.string.qsopanel_qsoing_with)
        assertThat(status.headline.args).containsExactly("W1XYZ")
        assertThat(status.messageLine).isEqualTo("W1XYZ KS3CKC R-12")
        assertThat(status.txState).isEqualTo(CarTxState.TRANSMITTING)
    }

    @Test
    fun targetNotTransmitting_headlineWaitingFor_messageLineNull() {
        val status = build(
            isActivated = true,
            toCallsign = "W1XYZ",
            transmittingMessage = "W1XYZ KS3CKC R-12",
        )
        assertThat(status.headline.resId).isEqualTo(R.string.qsopanel_waiting_for)
        assertThat(status.headline.args).containsExactly("W1XYZ")
        assertThat(status.messageLine).isNull()
        assertThat(status.txState).isEqualTo(CarTxState.ARMED_RX)
    }

    @Test
    fun transmittingWithEmptyOrNullMessage_messageLineNull() {
        for (message in listOf(null, "")) {
            val status = build(isActivated = true, isTransmitting = true, transmittingMessage = message)
            assertThat(status.messageLine).isNull()
        }
    }

    @Test
    fun snrLabel_onlyWithTarget_andFormatsSign() {
        assertThat(build(toCallsign = "W1XYZ", snr = -12).snrLabel).isEqualTo("-12 dB")
        assertThat(build(toCallsign = "W1XYZ", snr = 3).snrLabel).isEqualTo("+3 dB")
        assertThat(build(toCallsign = "W1XYZ", snr = null).snrLabel).isNull()
        assertThat(build(toCallsign = "CQ", snr = -12).snrLabel).isNull()
    }

    @Test
    fun txFunctionLabel_mapsAllSixSteps_andFallsBackForUnknown() {
        assertThat(txFunctionLabel(1)).isEqualTo("GRID")
        assertThat(txFunctionLabel(2)).isEqualTo("RPT")
        assertThat(txFunctionLabel(3)).isEqualTo("R-RPT")
        assertThat(txFunctionLabel(4)).isEqualTo("RR73")
        assertThat(txFunctionLabel(5)).isEqualTo("73")
        assertThat(txFunctionLabel(6)).isEqualTo("CQ")
        assertThat(txFunctionLabel(0)).isEqualTo("TX 0")
        assertThat(txFunctionLabel(7)).isEqualTo("TX 7")
    }

    @Test
    fun seqLine_carriesLabelOrdinalAndTotal() {
        val seqLine = build(isActivated = true, functionOrder = 4).seqLine
        assertThat(seqLine).isNotNull()
        assertThat(seqLine!!.resId).isEqualTo(R.string.car_seq_step)
        assertThat(seqLine.args).containsExactly("RR73", 4, 6).inOrder()
    }

    @Test
    fun slotLine_txWhenSlotParityMatchesMySequential() {
        assertThat(build(myTxSequential = 0, currentSlot = 0).slotLine.resId)
            .isEqualTo(R.string.car_slot_tx)
        assertThat(build(myTxSequential = 0, currentSlot = 1).slotLine.resId)
            .isEqualTo(R.string.car_slot_rx)
        assertThat(build(myTxSequential = 1, currentSlot = 3).slotLine.resId)
            .isEqualTo(R.string.car_slot_tx)
        assertThat(build(myTxSequential = 1, currentSlot = 2).slotLine.resId)
            .isEqualTo(R.string.car_slot_rx)
    }

    @Test
    fun slotLine_carriesSecondsRemaining() {
        assertThat(build(secondsRemaining = 11).slotLine.args).containsExactly(11)
    }

    @Test
    fun carBandLine_joinsFreqBandAndMode() {
        assertThat(carBandLine(14_074_000L, "20m", "FT8")).isEqualTo("14.074 MHz · 20m · FT8")
    }

    @Test
    fun carBandLine_omitsBlankBandName() {
        assertThat(carBandLine(7_074_000L, "", "FT4")).isEqualTo("7.074 MHz · FT4")
    }

    @Test
    fun buildCarDecodeRows_newestFirst_truncated_snrFormatted() {
        val rows = buildCarDecodeRows(
            decodes = listOf(
                Triple(1000L, "CQ K1ABC FN42", -12),
                Triple(3000L, "K1ABC W9XYZ EN52", 3),
                Triple(2000L, "K1ABC W9XYZ R-08", null),
            ),
            maxRows = 2,
        )
        assertThat(rows).hasSize(2)
        assertThat(rows[0].text).isEqualTo("K1ABC W9XYZ EN52")
        assertThat(rows[0].snrLabel).isEqualTo("+3 dB")
        assertThat(rows[1].text).isEqualTo("K1ABC W9XYZ R-08")
        assertThat(rows[1].snrLabel).isNull()
    }

    @Test
    fun buildCarDecodeRows_emptyInputAndNonPositiveLimit() {
        assertThat(buildCarDecodeRows(emptyList(), 6)).isEmpty()
        assertThat(buildCarDecodeRows(listOf(Triple(1L, "CQ K1ABC", -1)), 0)).isEmpty()
        assertThat(buildCarDecodeRows(listOf(Triple(1L, "CQ K1ABC", -1)), -3)).isEmpty()
    }

    @Test
    fun carDecodeSecondary_formatsUtcTime_withAndWithoutSnr() {
        // 1970-01-01 01:02:03 UTC
        val ms = ((1 * 60 + 2) * 60 + 3) * 1000L
        assertThat(carDecodeSecondary(ms, "-12 dB")).isEqualTo("01:02:03 UTC · -12 dB")
        assertThat(carDecodeSecondary(ms, null)).isEqualTo("01:02:03 UTC")
    }

    @Test
    fun shouldInvalidateForTick_onlyWhenSecondChanges() {
        assertThat(shouldInvalidateForTick(lastSecond = 7, newSecond = 7)).isFalse()
        assertThat(shouldInvalidateForTick(lastSecond = 7, newSecond = 6)).isTrue()
        assertThat(shouldInvalidateForTick(lastSecond = -1, newSecond = 15)).isTrue()
    }

    // -- buildCarPotaLine --

    @Test
    fun buildCarPotaLine_nullWhenNoActivation() {
        assertThat(buildCarPotaLine(null, null)).isNull()
        assertThat(buildCarPotaLine("", null)).isNull()
        assertThat(buildCarPotaLine("  ", null)).isNull()
    }

    @Test
    fun buildCarPotaLine_formatsSingleParkWithCount() {
        val spec = buildCarPotaLine("K-1234", 3)
        assertThat(spec?.resId).isEqualTo(R.string.car_pota_line)
        assertThat(spec?.args).containsExactly("K-1234", 3).inOrder()
    }

    @Test
    fun buildCarPotaLine_formatsMultiParkDisplay() {
        val spec = buildCarPotaLine("K-1234 + K-5678", 12)
        assertThat(spec?.resId).isEqualTo(R.string.car_pota_line)
        assertThat(spec?.args).containsExactly("K-1234 + K-5678", 12).inOrder()
    }

    @Test
    fun buildCarPotaLine_zeroCount() {
        val spec = buildCarPotaLine("K-0001", 0)
        assertThat(spec?.resId).isEqualTo(R.string.car_pota_line)
        assertThat(spec?.args).containsExactly("K-0001", 0).inOrder()
    }

    @Test
    fun buildCarPotaLine_nullCountTreatedAsZero() {
        val spec = buildCarPotaLine("K-9999", null)
        assertThat(spec?.resId).isEqualTo(R.string.car_pota_line)
        assertThat(spec?.args).containsExactly("K-9999", 0).inOrder()
    }

    // -- pskSpotsToMarkers ("who heard me" rings) --

    @Test
    fun pskSpotsToMarkers_mapsCoordsAndColor_skipsZeroZero() {
        val spots = listOf(
            radio.ks3ckc.ft8af.pskreporter.PskReporterSpot(
                "VE3XYZ", "FN03", 43.0, -79.0, 14_074_000L, -10, "FT8", 0L,
            ),
            // Gridless report at 0,0 \u2014 should be dropped, not plotted off Africa.
            radio.ks3ckc.ft8af.pskreporter.PskReporterSpot(
                "BADXYZ", "", 0.0, 0.0, 14_074_000L, -10, "FT8", 0L,
            ),
        )
        val markers = pskSpotsToMarkers(spots)
        assertThat(markers).hasSize(1)
        assertThat(markers[0].lat).isEqualTo(43.0)
        assertThat(markers[0].lon).isEqualTo(-79.0)
        assertThat(markers[0].colorInt).isEqualTo(PSK_MARKER_COLOR)
    }
}
