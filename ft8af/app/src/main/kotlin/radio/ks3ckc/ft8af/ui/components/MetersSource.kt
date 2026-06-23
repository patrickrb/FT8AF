package radio.ks3ckc.ft8af.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.MutableLiveData
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
fun rememberRigMeterSamples(mainViewModel: MainViewModel): List<MeterSample> {
    val isFlex by mainViewModel.mutableIsFlexRadio.observeAsState(false)
    val isXiegu by mainViewModel.mutableIsXieguRadio.observeAsState(false)
    return when {
        isFlex -> flexMeterSamples(mainViewModel)
        isXiegu -> xieguMeterSamples(mainViewModel)
        else -> serialMeterSamples(mainViewModel)
    }
}

@Composable
private fun flexMeterSamples(mainViewModel: MainViewModel): List<MeterSample> {
    val connector = mainViewModel.baseRig?.connector as? FlexConnector

    // Force a meter (re)subscription when the HUD opens. The connect-time
    // subscription is gated on a METER_LIST response arriving, which is easily
    // missed; this guarantees the stream is running while the HUD is shown.
    LaunchedEffect(connector) { connector?.requestMeterStream() }

    // The Flex posts the SAME FlexMeterList instance on every update, which
    // observeAsState dedupes (so bars would freeze after the first packet). Poll
    // the live instance a few times a second instead — the UDP thread keeps
    // mutating it in place — and use a tick to drive recomposition.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(connector) {
        while (true) {
            kotlinx.coroutines.delay(250)
            tick++
        }
    }
    tick // read so recomposition tracks the poll

    val m = connector?.meterList
    if (m == null || !connector.meterDataReceived) return emptyList()
    return listOf(
        swrSampleFromRatio(m.swrVal),
        alcSampleFlexDb(m.alcVal),
        powerSample(m.pwrVal),
        sMeterSampleDbm(m.sMeterVal),
        tempSample(m.tempCVal),
    )
}

@Composable
private fun xieguMeterSamples(mainViewModel: MainViewModel): List<MeterSample> {
    val empty = remember { MutableLiveData<X6100Meters>() }
    val radio = (mainViewModel.baseRig?.connector as? X6100Connector)?.xieguRadio
    val meters by (radio?.mutableMeters ?: empty).observeAsState()
    val m = meters ?: return emptyList()
    return listOf(
        swrSampleFromRatio(m.swr),
        alcSampleXiegu(m.alc),
        powerSample(m.power),
        sMeterSampleDbm(X6100Meters.getMeter_dBm(m.sMeter)),
        voltSample(m.volt),
    )
}

@Composable
private fun serialMeterSamples(mainViewModel: MainViewModel): List<MeterSample> {
    val controller = mainViewModel.meterProtectionController
    val alc by controller.lastAlc.observeAsState(0)
    val swr by controller.lastSwr.observeAsState(0)
    val hasData by controller.meterDataReceived.observeAsState(false)
    if (!hasData) return emptyList()
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
