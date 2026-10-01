package radio.ks3ckc.ft8af.ui.bandadvisor

import android.content.Context
import android.widget.Toast
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.database.OperationBand
import radio.ks3ckc.ft8af.ui.components.formatMhz
import radio.ks3ckc.ft8af.ui.components.selectBandIndex

/**
 * One-tap tune to a recommended dial. Reuses [selectBandIndex] — the same
 * path as every manual band pick — so all its guarantees apply unchanged:
 * operator-dial protection (RigDialTarget), config persistence, per-band
 * output level restore, decode clearing, and CAT push only when a CAT-capable
 * control mode is active. This function NEVER starts a transmission and never
 * schedules further tuning: one tap, one dial change, done.
 *
 * Returns true when the dial was applied. False = the frequency is not in the
 * band plan for the current mode; the caller shows it for manual tuning and
 * the app state is left untouched (the advisor never invents dial entries).
 */
fun tuneToAdvisorFrequency(
    mainViewModel: MainViewModel,
    context: Context,
    frequencyHz: Long,
): Boolean {
    val index = advisorTuneIndexFor(
        bands = OperationBand.bandList,
        frequencyHz = frequencyHz,
        modeId = GeneralVariables.operatingMode,
    )
    if (index == null) {
        BandAdvisorTelemetry.event("tune_unsupported", frequencyHz.toString())
        Toast.makeText(
            context,
            context.getString(R.string.band_advisor_tune_manual, formatMhz(frequencyHz)),
            Toast.LENGTH_LONG,
        ).show()
        return false
    }
    BandAdvisorTelemetry.event("tune", "$frequencyHz")
    selectBandIndex(mainViewModel, context, index)
    return true
}
