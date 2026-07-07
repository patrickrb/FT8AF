package radio.ks3ckc.ft8af.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.ft8transmit.TransmitCallsign
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import radio.ks3ckc.ft8af.pota.PotaSessionManager
import radio.ks3ckc.ft8af.pskreporter.PskReporterSpot
import radio.ks3ckc.ft8af.pskreporter.WhoHeardMeCache

/**
 * DEBUG-ONLY test harness for the Android Auto QSO map. Lives in `src/debug`, so
 * it is never compiled into a release build.
 *
 * The car map is a read-out of the live engine: it draws the operator dot from
 * [GeneralVariables.getMyMaidenheadGrid], the partner dot + connecting line from
 * the current QSO target, decode dots from [MainViewModel.mutableFt8MessageList],
 * and "who heard me" rings from [WhoHeardMeCache]. On an emulator there is no
 * radio, so this receiver forges that state.
 *
 * Usage (app must be open so the engine singleton exists):
 * ```
 * adb shell am broadcast -a ft8af.DEBUG_INJECT \
 *   --es call W1ABC --es grid FN42 --es opgrid EM29 --es park K-1234 \
 *   --ei snr -8 --ei decodes 8 --ei psk 6
 * ```
 * `decodes` adds N sample US decode dots; `psk` adds N sample "who heard me"
 * rings. All extras are optional; [parseDebugInject] fills sensible defaults.
 */
class DebugInjectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Coerce any extra to a String so both `--es key v` and `--ei key n` work.
        val spec = parseDebugInject { key -> intent.extras?.get(key)?.toString() }
        val vm = MainViewModel.peekInstance()
        if (vm == null) {
            Log.w(TAG, "ignored — engine singleton is null; open the app on the phone first")
            return
        }
        // Room writes (POTA start) must not run on the main thread; postValue is
        // thread-safe, so drive the whole injection off a worker thread.
        Thread {
            applyDebugInject(spec, vm)
            Log.i(TAG, "injected $spec")
        }.start()
    }

    private companion object {
        const val TAG = "DebugInject"
    }
}

/** Sample US stations plotted as decode dots (stations we heard). */
private val SAMPLE_DECODES = listOf(
    "K7ABC" to "CN87", "W6DEF" to "DM04", "N5GHI" to "EM12", "W4JKL" to "EL96",
    "K1MNO" to "FN42", "K0PQR" to "EN35", "W7STU" to "DM79", "N9VWX" to "EN52",
    "K4YZA" to "EM73", "W5BCD" to "EM10",
)

/** Sample stations plotted as "who heard me" PSK rings (stations that spotted us). */
private val SAMPLE_PSK = listOf(
    "VE3XYZ" to "FN03", "K6ABC" to "CM97", "W2DEF" to "FN20", "N7GHI" to "DM43",
    "K8JKL" to "EN80", "W9MNO" to "EM69", "KH6PQR" to "BL11", "VE7STU" to "CN89",
)

/**
 * Forges the engine state a [DebugInjectSpec] describes: operator grid, a decode
 * for the QSO partner (+ optional sample decodes), an optional POTA activation,
 * sample "who heard me" PSK spots, and finally the QSO target — set *last*
 * because that is the LiveData the car screen observes to trigger a re-render.
 */
internal fun applyDebugInject(spec: DebugInjectSpec, vm: MainViewModel) {
    GeneralVariables.setMyMaidenheadGrid(spec.opGrid)

    val list = ArrayList(vm.mutableFt8MessageList.value ?: ArrayList())
    fun putDecode(call: String, grid: String, snr: Int) {
        list.removeAll { it.callsignFrom == call }
        list.add(decodeMessage(call, grid, snr))
    }
    putDecode(spec.partnerCall, spec.partnerGrid, spec.snr)
    SAMPLE_DECODES.take(spec.decodes).forEach { (call, grid) -> putDecode(call, grid, -15) }
    vm.mutableFt8MessageList.postValue(list)

    // "Who heard me" PSK spots — projected from grid to lat/lon like a real report.
    if (spec.psk > 0) {
        WhoHeardMeCache.spots = SAMPLE_PSK.take(spec.psk).mapNotNull { (call, grid) ->
            val ll = try { MaidenheadGrid.gridToLatLng(grid) } catch (_: Exception) { null }
                ?: return@mapNotNull null
            PskReporterSpot(call, grid, ll.latitude, ll.longitude, 14_074_000L, -10, "FT8", 0L)
        }
    }

    spec.parkRef?.let { PotaSessionManager.start(listOf(it), "debug inject") }

    // Setting the target triggers the car screen's observer → re-render.
    val target = TransmitCallsign(0, 0, spec.partnerCall, 0f, 0, spec.snr)
    vm.ft8TransmitSignal.mutableToCallsign.postValue(target)
}

/**
 * A CQ decode. Built via the (i3,n3,callTo,callFrom,extra) constructor so
 * callsignTo is non-null ("CQ") — the phone's Decode list renders this same
 * object and calls checkIsCQ()/getMessageText(), both of which NPE on a bare
 * Ft8Message. i3=0,n3=0 = free-text, the safest render path.
 */
private fun decodeMessage(call: String, grid: String, snr: Int): Ft8Message =
    Ft8Message(0, 0, "CQ", call, grid).apply {
        maidenGrid = grid
        this.snr = snr
        isValid = true
        freq_hz = 1500f
    }

/** What the [DebugInjectReceiver] extras resolve to; kept Android-free so it is unit-testable. */
internal data class DebugInjectSpec(
    val opGrid: String,
    val partnerCall: String,
    val partnerGrid: String,
    val snr: Int,
    val parkRef: String?,
    val decodes: Int,
    val psk: Int,
)

/**
 * Resolves the broadcast extras to a [DebugInjectSpec], applying defaults for any
 * omitted key. Callsigns/grids are upper-cased so the map's grid lookup (an exact
 * `callsignFrom` match) and Maidenhead parse both behave. [get] is the extras
 * accessor; a blank value is treated as absent. `decodes`/`psk` counts are clamped
 * to the available sample sizes by the caller via `take(n)`.
 */
internal fun parseDebugInject(get: (String) -> String?): DebugInjectSpec {
    fun str(key: String) = get(key)?.trim()?.takeIf { it.isNotEmpty() }
    return DebugInjectSpec(
        opGrid = (str("opgrid") ?: DEFAULT_OP_GRID).uppercase(),
        partnerCall = (str("call") ?: DEFAULT_CALL).uppercase(),
        partnerGrid = (str("grid") ?: DEFAULT_GRID).uppercase(),
        snr = str("snr")?.toIntOrNull() ?: DEFAULT_SNR,
        parkRef = str("park")?.uppercase(),
        decodes = str("decodes")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        psk = str("psk")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    )
}

private const val DEFAULT_OP_GRID = "EM29"
private const val DEFAULT_CALL = "W1XYZ"
private const val DEFAULT_GRID = "FN42"
private const val DEFAULT_SNR = -12
