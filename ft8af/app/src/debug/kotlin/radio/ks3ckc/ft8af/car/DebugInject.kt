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
import com.k1af.ft8af.timer.UtcTimer
import radio.ks3ckc.ft8af.pota.PotaSessionManager
import radio.ks3ckc.ft8af.pskreporter.PskReporterClient
import radio.ks3ckc.ft8af.pskreporter.PskReporterSpot
import radio.ks3ckc.ft8af.pskreporter.WhoHeardMeCache
import java.util.Locale
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
 *   --ei snr -8 --ei decodes 16 --ei psk 6 --ei qsos 12 --ei wf 6 --es mycall W1AW
 * ```
 * `decodes` adds the first N rows of the scripted busy-band scene ([DEMO_SCENE]);
 * `mycall` sets the operator callsign for the session so the partner's reply
 * renders as TO YOU; `psk` adds N "who heard me" rings; `qsos`
 * adds N demo logbook entries; `wf` streams a waterfall with N tone traces (be on
 * the Waterfall tab to see it fill). All extras are optional;
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

/**
 * One scripted decode in the demo "busy band" scene. Android-free so the scene
 * builder ([buildDemoDecodes]) is unit-testable.
 *
 * @property callTo `"CQ"`, [ME] (resolved to the operator's callsign at apply
 *   time so the row renders as TO YOU), or a third-party station.
 * @property extra the trailing frame field: a grid, a report (`-08`), `R-12`,
 *   `RR73` or `73`.
 * @property grid sender's locator — drives the map dot + distance column.
 * @property modifier directional/activity CQ token (`POTA`, `DX`, `NA`), or null.
 * @property slotsAgo 0 = most recent completed RX slot, 1 = the one before, …
 * @property newDxcc flag the sender as an unworked DXCC so the NEW pill shows.
 */
internal data class DemoDecode(
    val callTo: String,
    val callFrom: String,
    val extra: String,
    val grid: String,
    val snr: Int,
    val freqHz: Int,
    val dt: Float,
    val slotsAgo: Int,
    val modifier: String? = null,
    val newDxcc: Boolean = false,
)

/** Placeholder for the operator's own callsign in [DEMO_SCENE] rows. */
internal const val ME = "<ME>"

/**
 * A busy 20 m band as it would decode over three consecutive RX slots: CQs
 * (plain, POTA, directional), stations mid-QSO exchanging reports, and one
 * RR73/73 wrap-up — with SNR, audio offset and DT spread like a real cycle.
 * Ordered newest slot first so `decodes N` trims the oldest rows.
 */
internal val DEMO_SCENE = listOf(
    // Slot 0 — the cycle that just finished decoding.
    DemoDecode("CQ", "JA1NUT", "PM95", "PM95", -18, 1210, 0.3f, 0, modifier = "DX", newDxcc = true),
    DemoDecode("CQ", "K4ABC", "EM73", "EM73", -3, 2310, -0.1f, 0, modifier = "POTA"),
    DemoDecode("CQ", "VK3GHI", "QF22", "QF22", -21, 640, 0.6f, 0),
    DemoDecode("K7ABC", "DL1ABC", "-12", "JO31", -9, 1820, 0.2f, 0),
    DemoDecode("N5GHI", "G4XYZ", "R-15", "IO91", -14, 990, 0.2f, 0),
    DemoDecode("W6DEF", "EA5JKL", "RR73", "IM98", 2, 1780, -0.3f, 0),
    DemoDecode("CQ", "PY2PQR", "GG66", "GG66", -7, 410, -0.2f, 0),
    // Slot 1
    DemoDecode("CQ", "ZL2STU", "RE78", "RE78", -16, 2650, 0.4f, 1, newDxcc = true),
    DemoDecode("K0PQR", "F5MNO", "73", "JN18", -11, 1345, 0.0f, 1),
    DemoDecode("CQ", "OH2VWX", "KP20", "KP20", -19, 2105, 0.1f, 1),
    DemoDecode("CQ", "I2YZA", "JN45", "JN45", -13, 890, 0.5f, 1, modifier = "NA"),
    DemoDecode("CQ", "VE1EFG", "FN85", "FN85", -5, 1690, -0.1f, 1),
    DemoDecode("W9MNO", "SP5BCD", "-02", "KO02", -9, 2440, 0.2f, 1),
    // Slot 2
    DemoDecode("CQ", "K7ABC", "CN87", "CN87", -15, 1120, 0.0f, 2),
    DemoDecode("CQ", "W6DEF", "DM04", "DM04", -12, 2020, 0.3f, 2),
    DemoDecode("CQ", "N5GHI", "EM12", "EM12", -6, 760, 0.1f, 2, modifier = "POTA"),
)

/** FT8 RX slot length; demo decode timestamps are aligned to these boundaries. */
private const val FT8_SLOT_MS = 15_000L

/**
 * The first [count] rows of [DEMO_SCENE] paired with the UTC millis each would
 * have been stamped with: the start of its RX slot, counting back from the most
 * recent slot that has *completed* at [nowMillis] (the in-progress slot has not
 * decoded yet). Aligning to the slot boundary is what makes the decode list's
 * per-slot time dividers group the rows the way a live cycle does.
 */
internal fun buildDemoDecodes(count: Int, nowMillis: Long): List<Pair<DemoDecode, Long>> {
    val n = count.coerceIn(0, DEMO_SCENE.size)
    val lastCompletedSlot = (nowMillis / FT8_SLOT_MS - 1) * FT8_SLOT_MS
    return DEMO_SCENE.take(n).map { d -> d to (lastCompletedSlot - d.slotsAgo * FT8_SLOT_MS) }
}

/**
 * Resolves a scene row's [DemoDecode.callTo] for display: the [ME] placeholder
 * becomes [myCall] (or [fallback] when no callsign is configured); anything else
 * is returned as-is.
 */
internal fun resolveDemoCallTo(callTo: String, myCall: String, fallback: String = "W1AW"): String =
    if (callTo == ME) myCall.ifBlank { fallback } else callTo

/** Sample stations plotted as "who heard me" PSK rings (stations that spotted us). */
private val SAMPLE_PSK = listOf(
    "VE3XYZ" to "FN03", "K6ABC" to "CM97", "W2DEF" to "FN20", "N7GHI" to "DM43",
    "K8JKL" to "EN80", "W9MNO" to "EM69", "KH6PQR" to "BL11", "VE7STU" to "CN89",
)

/** Sample worked stations for the demo logbook — a DX-flavoured mix across continents plus some US states. */
private val SAMPLE_QSOS = listOf(
    "DL1ABC" to "JO31", "G4XYZ" to "IO91", "JA1DEF" to "PM95", "VK3GHI" to "QF22",
    "EA5JKL" to "IM98", "F5MNO" to "JN18", "PY2PQR" to "GG66", "ZL2STU" to "RE78",
    "OH2VWX" to "KP20", "I2YZA" to "JN45", "SP5BCD" to "KO02", "VE1EFG" to "FN85",
    "9A1HIJ" to "JN75", "LU3KLM" to "GF05", "ON4NOP" to "JO20", "SM3QRS" to "JP81",
    // A few US stations so Worked-All-States has something to show.
    "K7ABC" to "CN87", "W6DEF" to "DM04", "N5GHI" to "EM12", "W4JKL" to "EL96",
    "K1MNO" to "FN42", "K0PQR" to "EN35", "W7STU" to "DM79", "N9VWX" to "EN52",
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

/** Waterfall stream length: two full FT8 slots plus a little, at one frame per symbol. */
private const val WF_FRAMES = 208 // 2 * FT8_SYMBOLS_PER_SLOT + 20
private const val WF_FRAME_MS = 22L

/**
 * Forges the engine state a [DebugInjectSpec] describes: operator grid, a decode
 * for the QSO partner (+ optional sample decodes), an optional POTA activation,
 * sample "who heard me" PSK spots, and finally the QSO target — set *last*
 * because that is the LiveData the car screen observes to trigger a re-render.
 */
internal fun applyDebugInject(spec: DebugInjectSpec, vm: MainViewModel) {
    GeneralVariables.setMyMaidenheadGrid(spec.opGrid)
    spec.myCall?.let { GeneralVariables.myCallsign = it }

    val now = UtcTimer.getSystemTime()
    val list = ArrayList(vm.mutableFt8MessageList.value ?: ArrayList())
    fun putDecode(msg: Ft8Message) {
        list.removeAll { it.callsignFrom == msg.callsignFrom }
        list.add(msg)
    }
    // The partner answers our CQ with a report in the newest slot — it renders
    // as both the CALLING target and a TO YOU row.
    val scene = buildDemoDecodes(spec.decodes, now)
    val newestSlot = scene.firstOrNull()?.second ?: ((now / FT8_SLOT_MS - 1) * FT8_SLOT_MS)
    val partner = DemoDecode(
        ME, spec.partnerCall, formatDemoReport(spec.snr), spec.partnerGrid,
        spec.snr, 1520, 0.1f, 0,
    )
    putDecode(decodeMessage(partner, newestSlot))
    scene.forEach { (d, utc) -> putDecode(decodeMessage(d, utc)) }
    list.sortByDescending { it.utcTime }
    vm.mutableFt8MessageList.postValue(list)
    vm.mutable_Decoded_Counter.postValue(list.count { it.utcTime == newestSlot })

    // "Who heard me" PSK spots — projected from grid to lat/lon like a real report.
    if (spec.psk > 0) {
        WhoHeardMeCache.spots = SAMPLE_PSK.take(spec.psk).mapNotNull { (call, grid) ->
            val ll = try { MaidenheadGrid.gridToLatLng(grid) } catch (_: Exception) { null }
                ?: return@mapNotNull null
            PskReporterSpot(call, grid, ll.latitude, ll.longitude, 14_074_000L, -10 - (grid.hashCode() and 7), "FT8", 0L)
        }
    }
    // The phone map fetches its overlay live from pskreporter.info; route it to
    // the same demo spots so "who heard me" rings render offline too. An inject
    // with psk=0 hands the map back to the live fetch — the override is process
    // state, so leaving it set would pin a previous inject's rings on the map.
    PskReporterClient.spotsOverride = demoSpotsOverride(spec.psk, WhoHeardMeCache.spots)

    // notes=null matches a real no-notes activation (PotaScreen passes
    // notes.ifBlank { null }); keeps the demo POTA card clean for screenshots.
    spec.parkRef?.let { PotaSessionManager.start(listOf(it), null) }

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

/** FT8 modulation constants used to shape the demo waterfall traces. */
internal const val FT8_TONE_SPACING_HZ = 6.25
internal const val FT8_SYMBOLS_PER_SLOT = 94       // 15 s / 160 ms
internal const val FT8_TX_START_SYMBOL = 3          // ~0.5 s into the slot
internal const val FT8_TX_SYMBOLS = 79              // 12.64 s message
private val FT8_COSTAS = intArrayOf(3, 1, 4, 0, 6, 5, 2)

/**
 * Which 8-FSK tone (0..7) the demo station at [toneIndex] transmits during
 * [symbol] (0..78 within its message): the Costas sync pattern at symbols 0-6,
 * 36-42 and 72-78 like a real frame, and a deterministic pseudo-random data
 * tone elsewhere. Pure so the waterfall synth stays reproducible/testable.
 */
internal fun demoSymbolTone(toneIndex: Int, symbol: Int): Int {
    val inBlock = symbol % 36
    if (inBlock < 7 && symbol <= 78) return FT8_COSTAS[inBlock]
    val h = (toneIndex + 1) * 2_654_435_761L + symbol * 40_503L
    return ((h ushr 13) and 7L).toInt()
}

/**
 * Whether the demo station at [toneIndex] is keyed during waterfall [frame]
 * (one frame == one 160 ms symbol). Stations alternate 15 s slots like a real
 * band — index ≡ 1 (mod 3) in even slots, ≡ 2 in odd slots, ≡ 0 in both — and
 * each transmits the 79-symbol message starting ~0.5 s into its slot, so traces
 * start and stop at slot edges instead of running as solid lines.
 */
internal fun demoStationActive(toneIndex: Int, frame: Int): Boolean {
    val slot = frame / FT8_SYMBOLS_PER_SLOT
    val inSlot = frame % FT8_SYMBOLS_PER_SLOT
    val slotOk = when (toneIndex % 3) {
        0 -> true
        1 -> slot % 2 == 0
        else -> slot % 2 == 1
    }
    return slotOk && inSlot >= FT8_TX_START_SYMBOL && inSlot < FT8_TX_START_SYMBOL + FT8_TX_SYMBOLS
}

/** Relative strength of the demo station at [toneIndex] — a spread of strong and weak traces. */
private val DEMO_STATION_AMP = doubleArrayOf(0.16, 0.05, 0.11, 0.03, 0.08, 0.14, 0.04, 0.09, 0.06, 0.12, 0.025, 0.07)

/**
 * A frame of synthetic 12 kHz mono audio — one FT8 symbol period (1920 samples at
 * 12 kHz = 160 ms): every active demo station (see [demoStationActive]) emits a
 * single 8-FSK tone ([demoSymbolTone]) above its base frequency in [tonesHz],
 * at its own strength, over a small deterministic noise floor, in the float PCM
 * range [-1, 1] the recorder produces. Streamed frame after frame ([seed] is the
 * frame index), the waterfall's FFT draws each station as a 50 Hz-wide
 * FT8-looking trace that keys up and drops out at slot boundaries. Kept pure (no
 * Random) so it is unit-testable.
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
    val scale = (3.6 / tonesHz.size).coerceAtMost(1.0)
    val active = tonesHz.mapIndexedNotNull { t, base ->
        if (!demoStationActive(t, seed)) return@mapIndexedNotNull null
        val symbol = seed % FT8_SYMBOLS_PER_SLOT - FT8_TX_START_SYMBOL
        val hz = base + demoSymbolTone(t, symbol) * FT8_TONE_SPACING_HZ
        hz to DEMO_STATION_AMP[t % DEMO_STATION_AMP.size] * scale
    }
    val noiseAmp = 0.006
    for (i in 0 until n) {
        var v = 0.0
        for ((hz, amp) in active) v += amp * sin(2.0 * PI * hz * i / sampleRate)
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
 * Ft8Message. i3=1,n3=0 = a standard (type 1) message, the same shape the live
 * decoder produces for CQ and report frames, so every render path and the
 * junk-decode plausibility check treat it exactly like a real decode.
 */
private fun decodeMessage(d: DemoDecode, utcMillis: Long): Ft8Message =
    Ft8Message(1, 0, resolveDemoCallTo(d.callTo, GeneralVariables.myCallsign), d.callFrom, d.extra).apply {
        modifier = d.modifier
        maidenGrid = d.grid
        snr = d.snr
        isValid = true
        freq_hz = d.freqHz.toFloat()
        time_sec = d.dt
        utcTime = utcMillis
        fromDxcc = d.newDxcc
        // Country / continent line, looked up the way the live decoder does it.
        // The cty database may not be open yet on a cold start — skip quietly.
        try {
            GeneralVariables.callsignDatabase?.getCallInfo(d.callFrom)?.let { info ->
                fromWhere = info.CountryNameEn
                continent = info.Continent
            }
        } catch (_: Exception) {
        }
    }

/**
 * A signed FT8 report field: `-08`, `+02`, `-15`. Locale-pinned: the default
 * locale's `%d` can render non-ASCII digits (Arabic-Indic, Thai), which is not a
 * report FT8 stations exchange.
 */
internal fun formatDemoReport(snr: Int): String =
    (if (snr < 0) "-" else "+") + String.format(Locale.US, "%02d", kotlin.math.abs(snr))

/**
 * What the debug inject leaves in [PskReporterClient.spotsOverride]: the demo
 * spots while `psk > 0`, null (live pskreporter.info fetch) otherwise.
 */
internal fun demoSpotsOverride(psk: Int, spots: List<PskReporterSpot>): List<PskReporterSpot>? =
    if (psk > 0) spots else null

/** What the [DebugInjectReceiver] extras resolve to; kept Android-free so it is unit-testable. */
internal data class DebugInjectSpec(
    val opGrid: String,
    val partnerCall: String,
    val partnerGrid: String,
    val snr: Int,
    val parkRef: String?,
    val decodes: Int,
    val psk: Int,
    val qsos: Int,
    val waterfall: Int,
    val myCall: String? = null,
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
        qsos = str("qsos")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        waterfall = str("wf")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        myCall = str("mycall")?.uppercase(),
    )
}

private const val DEFAULT_OP_GRID = "EM29"
private const val DEFAULT_CALL = "W1XYZ"
private const val DEFAULT_GRID = "FN42"
private const val DEFAULT_SNR = -12
