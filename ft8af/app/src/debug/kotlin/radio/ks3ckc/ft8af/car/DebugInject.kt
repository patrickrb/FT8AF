package radio.ks3ckc.ft8af.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.k1af.ft8af.Ft8Message
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.ft8transmit.TransmitCallsign
import com.k1af.ft8af.log.QSLRecord
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import radio.ks3ckc.ft8af.pota.PotaSessionManager
import radio.ks3ckc.ft8af.pskreporter.PskReporterSpot
import radio.ks3ckc.ft8af.pskreporter.WhoHeardMeCache
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * DEBUG-ONLY test/demo harness. Lives in `src/debug`, so it is never compiled
 * into a release build. Originally built to exercise the Android Auto QSO map on
 * an emulator; it doubles as the "demo data" source for Play Store screenshots —
 * it forges the live engine state the main phone screens read, so each tab looks
 * populated without a radio.
 *
 * What it drives:
 * - **Decode list + map**: operator dot from [GeneralVariables.getMyMaidenheadGrid],
 *   partner dot + line from the current QSO target, decode dots from
 *   [MainViewModel.mutableFt8MessageList], "who heard me" rings from [WhoHeardMeCache].
 * - **Waterfall**: a stream of synthetic 12 kHz audio frames posted to
 *   `spectrumListener.mutableDataBuffer`; the Waterfall screen FFTs each frame, so
 *   chosen audio tones render as clean vertical FT8-style traces (`wf` extra).
 * - **Logbook**: sample completed QSOs written into `QSLTable` via the app's own
 *   insert path so the Logbook tab, which re-queries the DB, shows a full log
 *   (`qsos` extra). These use obviously-fictitious demo callsigns; remove them by
 *   reinstalling the debug build (which wipes the DB).
 *
 * Usage (app must be open so the engine singleton exists):
 * ```
 * adb shell am broadcast -a ft8af.DEBUG_INJECT \
 *   --es call W1ABC --es grid FN42 --es opgrid EM29 --es park K-1234 \
 *   --es rota "Route 66" --ei snr -8 --ei decodes 8 --ei psk 6 --ei qsos 12 --ei wf 6
 * ```
 * `decodes` adds N sample decode dots; `psk` adds N "who heard me" rings; `qsos`
 * adds N demo logbook entries; `wf` streams a waterfall with N tone traces (be on
 * the Waterfall tab to see it fill). `rota` starts a real offline ROTA trip
 * (pendingCreate — no server or API key needed) so the car pane's ROTA row
 * renders; end it from the phone's ROTA screen. All extras are optional;
 * [parseDebugInject] fills sensible defaults.
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

/** Sample worked stations for the demo logbook — a DX-flavoured mix across continents. */
private val SAMPLE_QSOS = listOf(
    "DL1ABC" to "JO31", "G4XYZ" to "IO91", "JA1DEF" to "PM95", "VK3GHI" to "QF22",
    "EA5JKL" to "IM98", "F5MNO" to "JN18", "PY2PQR" to "GG66", "ZL2STU" to "RE78",
    "OH2VWX" to "KP20", "I2YZA" to "JN45", "SP5BCD" to "KO02", "VE1EFG" to "FN85",
    "9A1HIJ" to "JN75", "LU3KLM" to "GF05", "ON4NOP" to "JO20", "SM3QRS" to "JP81",
)

/** FT8/FT4 dial frequencies (Hz) the demo QSOs are spread across. */
private data class DemoBand(val mode: String, val dialHz: Long)
private val DEMO_BANDS = listOf(
    DemoBand("FT8", 14_074_000L), // 20m
    DemoBand("FT8", 7_074_000L),  // 40m
    DemoBand("FT4", 14_080_000L), // 20m FT4
    DemoBand("FT8", 21_074_000L), // 15m
    DemoBand("FT8", 28_074_000L), // 10m
    DemoBand("FT8", 10_136_000L), // 30m
)

/** Waterfall passband (audio Hz) the demo tone traces are spread across. */
private const val WF_MIN_HZ = 250.0
private const val WF_MAX_HZ = 2750.0

/** Waterfall stream length: enough frames to fill the scrolling bitmap (~3s at 22ms). */
private const val WF_FRAMES = 140
private const val WF_FRAME_MS = 22L

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

    // notes=null matches a real no-notes activation (PotaScreen passes
    // notes.ifBlank { null }); keeps the demo POTA card clean for screenshots.
    spec.parkRef?.let { PotaSessionManager.start(listOf(it), null) }

    // A real offline trip (deferred server create), not forged state: the car
    // row, notification, and phone ROTA screen all show it, and demo QSOs below
    // queue into it via the normal RotaTripManager.onQsoLogged path.
    spec.rotaTrip?.let { radio.ks3ckc.ft8af.rota.RotaTripManager.startTrip(it, "private") }

    // Demo logbook QSOs — written straight into QSLTable through the app's own
    // insert path, so the Logbook tab (which re-queries the DB on load, not the
    // in-memory list) shows a populated log. Uses the caller's real callsign if
    // set, else a placeholder; the worked stations are obviously-fictitious demo
    // calls. addQSL_Callsign runs its own background insert.
    if (spec.qsos > 0) {
        val myCall = GeneralVariables.myCallsign.ifEmpty { "W1AW" }
        val myGrid = GeneralVariables.getMyMaidenheadGrid() ?: spec.opGrid
        buildDemoQsoLog(spec.qsos, System.currentTimeMillis()).forEach { q ->
            val record = QSLRecord(
                q.startMillis, q.endMillis, myCall, myGrid,
                q.toCall, q.toGrid, q.sendReport, q.receivedReport,
                q.mode, q.bandFreqHz, q.audioHz,
            )
            // Stamp the active park (if any) through the app's real helper so
            // the demo QSOs bump the POTA activation counter like real ones.
            PotaSessionManager.stampQso(record, null)
            vm.databaseOpr.addQSL_Callsign(record)
        }
    }

    // Setting the target triggers the car screen's observer → re-render.
    val target = TransmitCallsign(0, 0, spec.partnerCall, 0f, 0, spec.snr)
    vm.ft8TransmitSignal.mutableToCallsign.postValue(target)

    // Waterfall: post a stream of synthetic 12 kHz audio frames. The Waterfall
    // screen FFTs each frame (mutableDataBuffer carries raw audio, not a spectrum),
    // so the chosen tones render as vertical traces and the scrolling bitmap fills.
    // Done LAST so its ~3s of spaced posts don't delay the other state above; the
    // observer only runs while the Waterfall tab is on screen. postValue (not
    // setValue) because this runs on a worker thread; the spacing keeps LiveData's
    // coalescing from dropping most frames.
    if (spec.waterfall > 0) {
        val tones = demoToneFrequencies(spec.waterfall)
        vm.spectrumListener?.let { listener ->
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            repeat(WF_FRAMES) { frame ->
                val buf = buildDemoAudio(tones, seed = frame)
                // Use setValue on the main thread so each frame is delivered (no postValue coalescing).
                mainHandler.post { listener.mutableDataBuffer.value = buf }
                try {
                    Thread.sleep(WF_FRAME_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }
}

/** Audio tone offsets (Hz) for the demo waterfall, spread evenly across the passband. */
internal fun demoToneFrequencies(count: Int): List<Double> {
    val n = count.coerceIn(0, 12)
    return when {
        n == 0 -> emptyList()
        n == 1 -> listOf((WF_MIN_HZ + WF_MAX_HZ) / 2)
        else -> {
            val step = (WF_MAX_HZ - WF_MIN_HZ) / (n - 1)
            (0 until n).map { WF_MIN_HZ + it * step }
        }
    }
}

/**
 * A frame of synthetic 12 kHz mono audio: the sum of sinusoids at [tonesHz] plus a
 * small deterministic noise floor, in the float PCM range [-1, 1] the recorder
 * produces. The waterfall's FFT of this shows a vertical trace per tone. Kept pure
 * (no Random — [seed] varies frame-to-frame reproducibly) so it is unit-testable.
 */
internal fun buildDemoAudio(
    tonesHz: List<Double>,
    sampleCount: Int = 1920,
    sampleRate: Int = 12000,
    seed: Int = 0,
): FloatArray {
    val n = sampleCount.coerceAtLeast(0)
    val out = FloatArray(n)
    if (tonesHz.isEmpty() || n == 0 || sampleRate <= 0) return out
    // Keep the summed peak inside [-1, 1] even with several tones.
    val amp = (0.9 / tonesHz.size).coerceAtMost(0.18)
    val noiseAmp = 0.006
    for (i in 0 until n) {
        var v = 0.0
        for (f in tonesHz) v += amp * sin(2.0 * PI * f * i / sampleRate)
        // Hash-based pseudo-noise: deterministic, phase-varied per frame by seed.
        val h = sin((i + seed * 7919 + 1) * 12.9898) * 43758.5453
        val noise = ((h - floor(h)) * 2.0 - 1.0) * noiseAmp
        out[i] = (v + noise).toFloat().coerceIn(-1f, 1f)
    }
    return out
}

/** A single demo logbook entry; Android-free so [buildDemoQsoLog] stays unit-testable. */
internal data class DemoQso(
    val toCall: String,
    val toGrid: String,
    val mode: String,
    val bandFreqHz: Long,
    val audioHz: Int,
    val sendReport: Int,
    val receivedReport: Int,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * Builds up to [count] demo QSOs (clamped to the sample-station pool) ending
 * shortly before [nowMillis], stepping ~7 minutes further into the past per entry
 * so the log reads as a recent operating run. Deterministic given its inputs.
 */
internal fun buildDemoQsoLog(count: Int, nowMillis: Long): List<DemoQso> {
    val n = count.coerceIn(0, SAMPLE_QSOS.size)
    return (0 until n).map { i ->
        val (call, grid) = SAMPLE_QSOS[i]
        val band = DEMO_BANDS[i % DEMO_BANDS.size]
        val start = nowMillis - (i + 1) * 7L * 60_000L
        DemoQso(
            toCall = call,
            toGrid = grid,
            mode = band.mode,
            bandFreqHz = band.dialHz,
            audioHz = 400 + (i * 233) % 2200,
            sendReport = -6 - (i % 12),
            receivedReport = -3 - (i * 3) % 15,
            startMillis = start,
            endMillis = start + 75_000L,
        )
    }
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
    val rotaTrip: String?,
    val decodes: Int,
    val psk: Int,
    val qsos: Int,
    val waterfall: Int,
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
        // Trip names are free text — no uppercasing.
        rotaTrip = str("rota"),
        decodes = str("decodes")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        psk = str("psk")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        qsos = str("qsos")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        waterfall = str("wf")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    )
}

private const val DEFAULT_OP_GRID = "EM29"
private const val DEFAULT_CALL = "W1XYZ"
private const val DEFAULT_GRID = "FN42"
private const val DEFAULT_SNR = -12
