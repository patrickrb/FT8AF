package radio.ks3ckc.ft8af.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.connector.FlexConnector
import com.k1af.ft8af.connector.X6100Connector
import com.k1af.ft8af.x6100.X6100Meters

/**
 * Resolves the meters the *connected rig* currently reports, as a list of
 * [MeterSample] in display order. This is the "adapt per rig" data source: it
 * observes whichever meter stream the active rig actually produces —
 *
 *   - **FlexRadio (network)**: the UDP meter stream in [FlexConnector.mutableMeterList]
 *     (SWR ratio, ALC dB, power W, S-meter dBm, PA temp °C).
 *   - **Xiegu (network)**: [com.k1af.ft8af.x6100.X6100Radio.mutableMeters]
 *     (SWR, ALC, power, S-meter, voltage).
 *   - **Serial CAT rigs** (Yaesu/Kenwood/Icom/Elecraft/serial Xiegu): the
 *     normalized ALC/SWR fed to [com.k1af.ft8af.ft8transmit.MeterProtectionController].
 *
 * The list is NOT filtered by the user's enabled-meters setting — that's the
 * HUD's job via [visibleMeters]. Returning the rig's full available set lets the
 * HUD tell "rig reports nothing" (empty) apart from "user hid everything".
 */
@Composable
fun rememberRigMeterSamples(mainViewModel: MainViewModel, frame: Long, active: Boolean): List<MeterSample> {
    val isFlex by mainViewModel.mutableIsFlexRadio.observeAsState(false)
    val isXiegu by mainViewModel.mutableIsXieguRadio.observeAsState(false)
    val flexConnector = mainViewModel.baseRig?.connector as? FlexConnector

    // (Re)subscribe to the Flex meter stream each time the HUD opens ([active]).
    // The connect-time subscription is gated on a METER_LIST response and can be
    // missed (or race the connection coming up); re-requesting on every open makes
    // meters reliably start, rather than firing only once at connect.
    LaunchedEffect(flexConnector, isFlex, active) {
        if (isFlex && active) flexConnector?.requestMeterStream()
    }

    // [frame] ticks a few times a second (driven by the HUD) so this re-runs and
    // re-reads the rigs' live, in-place-mutated meter values. The readers below are
    // plain (non-@Composable) so their results actually propagate to the caller —
    // a poll tick read *inside* a child @Composable only recomposes that child, so
    // the new values never reached the rendered bars (the "meters don't move" bug).
    @Suppress("UNUSED_EXPRESSION") frame
    return when {
        isFlex -> flexSamples(flexConnector)
        isXiegu -> xieguSamples(mainViewModel.baseRig?.connector as? X6100Connector)
        else -> serialSamples(mainViewModel)
    }
}

/** Live read of the Flex meter values (the UDP thread mutates [FlexConnector.meterList] in place). */
private fun flexSamples(connector: FlexConnector?): List<MeterSample> {
    if (connector == null || !connector.meterDataReceived) return emptyList()
    val m = connector.meterList
    return listOf(
        swrSampleFromRatio(m.swrVal),
        alcSampleFlexDb(m.alcVal),
        powerSample(m.pwrVal),
        sMeterSampleDbm(m.sMeterVal),
        tempSample(m.tempCVal),
    )
}

private fun xieguSamples(connector: X6100Connector?): List<MeterSample> {
    val m = connector?.xieguRadio?.mutableMeters?.value ?: return emptyList()
    return listOf(
        swrSampleFromRatio(m.swr),
        alcSampleXiegu(m.alc),
        powerSample(m.power),
        sMeterSampleDbm(X6100Meters.getMeter_dBm(m.sMeter)),
        voltSample(m.volt),
    )
}

private fun serialSamples(mainViewModel: MainViewModel): List<MeterSample> {
    val controller = mainViewModel.meterProtectionController
    if (controller.meterDataReceived.value != true) return emptyList()
    val alc = controller.lastAlc.value ?: 0
    val swr = controller.lastSwr.value ?: 0
    return listOf(
        swrSampleFromNormalized(swr),
        alcSampleSerial(alc, GeneralVariables.alcTargetLow, GeneralVariables.alcTargetHigh),
    )
}

/**
 * Whether the connected rig supports setting TX power from the app. Currently
 * FlexRadio (network), whose [FlexConnector.setMaxRfPower] maps cleanly to 0-100 W.
 * Xiegu uses a different scale (setMaxTXPower / commandSetTxPower) and is left out
 * for now — see [setRigTxPowerWatts].
 */
@Composable
fun rememberRigSupportsTxPower(mainViewModel: MainViewModel): Boolean {
    val isFlex by mainViewModel.mutableIsFlexRadio.observeAsState(false)
    return isFlex && mainViewModel.baseRig?.connector is FlexConnector
}

/** The currently-set TX power in watts (persisted as flexMaxRfPower, default 10 W). */
fun currentTxPowerWatts(): Int = GeneralVariables.flexMaxRfPower

/**
 * Apply a new TX power to the rig and persist it. [FlexConnector.setMaxRfPower]
 * pushes RFPOWER to the radio and updates GeneralVariables.flexMaxRfPower; we also
 * write it to config so it survives a restart (the old FlexRadioInfoFragment did
 * this on its seekbar, but that screen is unreachable from the Compose UI).
 */
fun setRigTxPowerWatts(mainViewModel: MainViewModel, watts: Int) {
    val w = clampTxPowerWatts(watts)
    (mainViewModel.baseRig?.connector as? FlexConnector)?.setMaxRfPower(w)
    mainViewModel.databaseOpr.writeConfig("flexMaxRfPower", w.toString(), null)
}
