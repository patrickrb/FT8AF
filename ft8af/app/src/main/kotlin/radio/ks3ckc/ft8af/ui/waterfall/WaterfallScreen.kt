package radio.ks3ckc.ft8af.ui.waterfall

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.k1af.ft8af.timer.UtcTimer
import com.k1af.ft8af.ui.ColumnarView
import com.k1af.ft8af.ui.SpectrumFragment
import com.k1af.ft8af.ui.WaterfallView
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.InputLevelIndicator
import radio.ks3ckc.ft8af.ui.components.TopBar

/**
 * Height of the columnar spectrum strip above the waterfall canvas. Taller than
 * the original 56.dp so peaks have more vertical room to read at a glance
 * (issue #206). The strip's [ColumnarView] is MATCH_PARENT, so it scales to
 * whatever height this modifier gives it; the waterfall canvas below keeps the
 * remaining space via weight(1f).
 */
internal val SpectrumStripHeight = 96.dp

/**
 * Height of the bottom info/toggle strip (clock, NR/MSG toggles, live status)
 * at the bottom of the waterfall screen. Fixed so the floating QSO panel can
 * offset itself by exactly this much (see FT8AFApp.qsoPanelOverlaysContent) and
 * leave the strip's controls reachable during an active QSO instead of covering
 * them — all without resizing the waterfall AndroidView.
 */
internal val WaterfallBottomStripHeight = 34.dp

/**
 * Holder for view references using plain @Volatile fields.
 * Avoids Compose snapshot system overhead when accessed from callbacks.
 */
private class ViewHolder {
    @Volatile var columnar: ColumnarView? = null
    @Volatile var waterfall: WaterfallView? = null
    var frequencyLineTimeout: Int = 0 // plain field, only accessed from main thread
}

/**
 * Waterfall screen wrapping the existing Java WaterfallView and ColumnarView
 * via AndroidView.
 *
 * Audio data is fed to the views via observeForever on the existing
 * SpectrumListener LiveData. The observer runs on the main thread (LiveData
 * dispatches via Handler) and directly calls setWaveData + invalidate on the
 * views — exactly matching the old SpectrumFragment's drawSpectrum() pattern.
 */
@Composable
fun WaterfallScreen(mainViewModel: MainViewModel) {
    var touchedFreqHz by remember { mutableIntStateOf(-1) }
    var updateCount by remember { mutableIntStateOf(0) }

    val isTransmitting by mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observeAsState(false)
    // Live RX input level (post-gain peak + RMS), published by HamRecorder
    // once per ~250ms metering window (issue #356).
    val inputLevels by GeneralVariables.mutableInputLevel.observeAsState()
    val txFreq by GeneralVariables.mutableBaseFrequency.observeAsState(GeneralVariables.getBaseFrequency())
    val spectrumWidth by GeneralVariables.mutableSpectrumWidth.observeAsState(GeneralVariables.getSpectrumWidth())
    var deNoise by remember { mutableStateOf(mainViewModel.deNoise) }
    var showMessages by remember { mutableStateOf(mainViewModel.markMessage) }

    // Plain volatile refs — no Compose snapshot overhead
    val viewHolder = remember { ViewHolder() }

    // Observe SpectrumListener's LiveData with observeForever.
    // The observer fires on the main thread every ~160ms, directly updating
    // both views. This is the exact same pattern as the old SpectrumFragment:
    //   spectrumListener.mutableDataBuffer.observe(...) { drawSpectrum(it) }
    DisposableEffect(Unit) {
        val observer = Observer<FloatArray> { data ->
            // Runs on MAIN THREAD (setValue dispatched via Handler.post)
            updateCount++
            val fft = IntArray(data.size / 2)
            nativeFFT(data, fft, mainViewModel.deNoise)

            // While a tap cursor is active, keep the TX bandwidth markers on
            // the touched frequency instead of snapping back to the base
            // frequency on every audio tick (issue #782). The touched value
            // clears when frequencyLineTimeout reaches 0 below, at which
            // point currentTxFreq falls back to the committed base freq.
            val currentTxFreq =
                displayTxFrequencyHz(touchedFreqHz, GeneralVariables.getBaseFrequency())
            val currentTxActive = mainViewModel.ft8TransmitSignal.mutableIsTransmitting.value ?: false

            viewHolder.columnar?.let { cView ->
                if (viewHolder.frequencyLineTimeout > 0) {
                    viewHolder.frequencyLineTimeout--
                }
                if (viewHolder.frequencyLineTimeout == 0) {
                    cView.setTouch_x(-1)
                    viewHolder.waterfall?.setTouch_x(-1)
                    touchedFreqHz = -1
                }
                cView.setSpectrumWidth(GeneralVariables.getSpectrumWidth())
                cView.setTxFrequency(currentTxFreq)
                cView.setTxActive(currentTxActive)
                cView.setWaveData(fft)
                cView.invalidate()
            }

            viewHolder.waterfall?.let { wView ->
                // drawMessage is armed elsewhere, edge-triggered off the
                // decode-state LiveData (see decodingObserver below). The
                // view stamps the labels onto its scrolling bitmap on the
                // first setWaveData after it's armed, then self-resets the
                // flag, so labels appear exactly once per decode cycle.
                wView.setSpectrumWidth(GeneralVariables.getSpectrumWidth())
                wView.setTxFrequency(currentTxFreq)
                wView.setTxActive(currentTxActive)
                val messages = if (mainViewModel.markMessage) mainViewModel.currentMessages else null
                wView.setWaveData(fft, messages)
                wView.invalidate()
            }
        }
        // Arm the decode-label draw edge-triggered off the decode-state
        // LiveData, exactly like the old SpectrumFragment did
        // (setDrawMessage(!isDecoding)). A decode lasts only ~60ms and can
        // fall entirely between two ~160ms audio frames, so sampling
        // isDecoding inside the audio observer misses the transition and the
        // labels never draw. LiveData delivers every true/false transition on
        // the main thread regardless of audio cadence. currentMessages is
        // populated before isDecoding goes false (MainViewModel), so by the
        // time we arm the draw the labels are ready for the next setWaveData.
        val decodingObserver = Observer<Boolean> { decoding ->
            viewHolder.waterfall?.setDrawMessage(!decoding && mainViewModel.markMessage)
        }
        mainViewModel.spectrumListener.mutableDataBuffer.observeForever(observer)
        mainViewModel.mutableIsDecoding.observeForever(decodingObserver)
        onDispose {
            mainViewModel.spectrumListener.mutableDataBuffer.removeObserver(observer)
            mainViewModel.mutableIsDecoding.removeObserver(decodingObserver)
            viewHolder.columnar = null
            viewHolder.waterfall = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgApp),
    ) {
        TopBar(title = stringResource(R.string.waterfall_title)) {
            val freqText = if (touchedFreqHz > 0) {
                "$touchedFreqHz Hz"
            } else {
                GeneralVariables.getBaseFrequencyStr() + " Hz"
            }
            Text(
                text = freqText,
                color = Signal,
                fontFamily = GeistMonoFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // While the user is touching the spectrum, drive the TX bandwidth
        // markers from the touched frequency so the red brackets follow the
        // blue tap cursor live instead of snapping to the previous base
        // frequency until ACTION_UP commits (issue #782).
        val displayTxFreq = displayTxFrequencyHz(touchedFreqHz, txFreq)

        // Spectrum strip (columnar view)
        ColumnarStrip(
            spectrumWidth = spectrumWidth,
            txFrequency = displayTxFreq,
            txActive = isTransmitting,
            onViewCreated = { viewHolder.columnar = it },
            onTouch = { freqHz, _ ->
                touchedFreqHz = freqHz
                viewHolder.frequencyLineTimeout = 60
            },
            onTouchUp = { freqHz ->
                if (freqHz > 0 && !GeneralVariables.synFrequency) {
                    mainViewModel.databaseOpr.writeConfig("freq", freqHz.toString(), null)
                    GeneralVariables.setBaseFrequency(freqHz.toFloat())
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(SpectrumStripHeight),
        )

        FrequencyRuler(
            spectrumWidth = spectrumWidth,
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(horizontal = 2.dp),
        )

        // Main waterfall display
        WaterfallCanvas(
            spectrumWidth = spectrumWidth,
            txFrequency = displayTxFreq,
            txActive = isTransmitting,
            onViewCreated = { viewHolder.waterfall = it },
            onTouch = { freqHz, _ ->
                touchedFreqHz = freqHz
                viewHolder.frequencyLineTimeout = 60
            },
            onTouchUp = { freqHz ->
                if (freqHz > 0 && !GeneralVariables.synFrequency) {
                    mainViewModel.databaseOpr.writeConfig("freq", freqHz.toString(), null)
                    GeneralVariables.setBaseFrequency(freqHz.toFloat())
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        // Bottom info strip. Fixed height (WaterfallBottomStripHeight) so the
        // floating QSO panel can sit just above it instead of covering it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(WaterfallBottomStripHeight)
                .background(BgSurface)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = UtcTimer.getTimeStr(UtcTimer.getSystemTime()),
                color = TextMuted,
                fontFamily = GeistMonoFamily,
                fontSize = 10.5.sp,
            )

            Spacer(modifier = Modifier.width(12.dp))

            ToggleChip(
                label = stringResource(R.string.waterfall_toggle_nr),
                active = deNoise,
                onClick = {
                    deNoise = !deNoise
                    mainViewModel.deNoise = deNoise
                },
            )

            Spacer(modifier = Modifier.width(8.dp))

            ToggleChip(
                label = stringResource(R.string.waterfall_toggle_msg),
                active = showMessages,
                onClick = {
                    showMessages = !showMessages
                    mainViewModel.markMessage = showMessages
                },
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Live RX input-level meter: too low / just right / too high, with
            // clipping indication when peaks hit full scale.
            InputLevelIndicator(levels = inputLevels)

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "$updateCount",
                color = TextDim,
                fontFamily = GeistMonoFamily,
                fontSize = 9.sp,
            )

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = stringResource(R.string.waterfall_status_live),
                color = StatusConfirmed,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.08.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Columnar spectrum strip (AndroidView wrapper)
// ---------------------------------------------------------------------------

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun ColumnarStrip(
    spectrumWidth: Int,
    txFrequency: Float,
    txActive: Boolean,
    onViewCreated: (ColumnarView) -> Unit,
    onTouch: (freqHz: Int, x: Int) -> Unit,
    onTouchUp: (freqHz: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            ColumnarView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(0xFF07090F.toInt())
                setShowBlock(true)
                setSpectrumWidth(spectrumWidth)
                setTxFrequency(txFrequency)
                setTxActive(txActive)

                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                        setTouch_x(event.x.toInt())
                    }
                    dispatchSpectrumTouch(event.action, getFreq_hz(), event.x.toInt(), onTouch, onTouchUp)
                    true
                }

                onViewCreated(this)
            }
        },
        update = { view ->
            view.setSpectrumWidth(spectrumWidth)
            view.setTxFrequency(txFrequency)
            view.setTxActive(txActive)
        },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Waterfall canvas (AndroidView wrapper)
// ---------------------------------------------------------------------------

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun WaterfallCanvas(
    spectrumWidth: Int,
    txFrequency: Float,
    txActive: Boolean,
    onViewCreated: (WaterfallView) -> Unit,
    onTouch: (freqHz: Int, x: Int) -> Unit,
    onTouchUp: (freqHz: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Note: drawMessage is NOT set here. It is armed exclusively by the audio
    // observer in WaterfallScreen, edge-triggered once per decode cycle.
    // Re-arming it on recomposition would re-stamp labels onto the scrolling
    // bitmap and smear them down the waterfall.
    AndroidView(
        factory = { context ->
            WaterfallView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(0xFF000000.toInt())
                setSpectrumWidth(spectrumWidth)
                setTxFrequency(txFrequency)
                setTxActive(txActive)

                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                        setTouch_x(event.x.toInt())
                    }
                    dispatchSpectrumTouch(event.action, getFreq_hz(), event.x.toInt(), onTouch, onTouchUp)
                    true
                }

                onViewCreated(this)
            }
        },
        update = { view ->
            view.setSpectrumWidth(spectrumWidth)
            view.setTxFrequency(txFrequency)
            view.setTxActive(txActive)
        },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Frequency ruler (pure Compose)
// ---------------------------------------------------------------------------

/**
 * Which frequency the red TX-bandwidth markers should bracket right now.
 *
 * While a tap cursor is active ([touchedFreqHz] > 0) the markers follow the
 * touched column, so the reds track the blue cursor live during a drag instead
 * of snapping back to the committed base frequency on every audio tick or
 * recomposition (issue #782). When the cursor is cleared — the caller sets
 * [touchedFreqHz] back to -1 once `frequencyLineTimeout` expires, or
 * `SpectrumTouchMath.touchToFreqHz` returned -1 for an off-view drag — the
 * markers fall back to [baseFreqHz].
 *
 * Extracted from the two call sites (the audio-tick observer and the strip /
 * canvas composables) so the selection is exercised without Compose.
 */
internal fun displayTxFrequencyHz(touchedFreqHz: Int, baseFreqHz: Float): Float =
    if (touchedFreqHz > 0) touchedFreqHz.toFloat() else baseFreqHz

/**
 * Route one spectrum touch event to the screen's callbacks, given the
 * frequency the view resolved for it (`setTouch_x` -> `SpectrumTouchMath.touchToFreqHz`).
 *
 * On DOWN/MOVE the frequency is always forwarded — including the -1 an
 * off-view drag resolves to. The view has already hidden its blue cursor for
 * that event, so the screen's `touchedFreqHz` has to follow it to -1 as well;
 * forwarding only positive values left the red TX markers parked at the last
 * on-view column until `frequencyLineTimeout` expired (Copilot review on
 * #788). Only a valid frequency is committed on UP, so an off-view release
 * never writes -1 as the base frequency. Shared by both AndroidView listeners
 * and unit-tested without Compose.
 */
internal fun dispatchSpectrumTouch(
    action: Int,
    freqHz: Int,
    x: Int,
    onTouch: (freqHz: Int, x: Int) -> Unit,
    onTouchUp: (freqHz: Int) -> Unit,
) {
    when (action) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> onTouch(freqHz, x)
        MotionEvent.ACTION_UP -> if (freqHz > 0) onTouchUp(freqHz)
    }
}

/** Hz step between adjacent ruler labels. */
private const val RulerStepHz = 500

/** A single ruler label: its frequency and where it sits across the strip. */
internal data class RulerTick(val hz: Int, val fraction: Float)

/**
 * Ruler labels at [RulerStepHz] intervals from 0 up to [spectrumWidth], each
 * paired with the horizontal fraction it must sit at.
 *
 * The waterfall/columnar views map a signal at `hz` to `hz / spectrumWidth` of
 * the width ([WaterfallView].freq_width = width / spectrumWidth), so a label's
 * fraction is `hz / spectrumWidth` too. When [spectrumWidth] is a multiple of
 * [RulerStepHz] the top tick equals it (fraction 1.0) and the gaps are uniform,
 * so this reduces to the old even layout; otherwise the top tick sits below the
 * far edge instead of being stretched to it.
 */
internal fun rulerTicks(spectrumWidth: Int): List<RulerTick> {
    if (spectrumWidth <= 0) return listOf(RulerTick(0, 0f))
    return buildList {
        var hz = 0
        while (hz <= spectrumWidth) {
            add(RulerTick(hz, hz.toFloat() / spectrumWidth))
            hz += RulerStepHz
        }
    }
}

@Composable
internal fun FrequencyRuler(spectrumWidth: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.background(BgSurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ticks = rulerTicks(spectrumWidth)
        ticks.forEachIndexed { index, tick ->
            // Weight each spacer by the fraction gap to the previous tick so the
            // labels track their true Hz positions instead of stretching evenly.
            if (index > 0) {
                Spacer(modifier = Modifier.weight(tick.fraction - ticks[index - 1].fraction))
            }
            Text(
                text = tick.hz.toString(),
                color = TextDim,
                fontFamily = GeistMonoFamily,
                fontSize = 8.sp,
                letterSpacing = 0.02.sp,
            )
        }
        // Fill the remainder past the top tick so it isn't pushed to the far
        // edge when spectrumWidth isn't a multiple of the step (no-op otherwise).
        val trailing = 1f - ticks.last().fraction
        if (trailing > 0f) Spacer(modifier = Modifier.weight(trailing))
    }
}

// ---------------------------------------------------------------------------
// Toggle chip for bottom controls
// ---------------------------------------------------------------------------

@Composable
internal fun ToggleChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) AccentSoft else BgSurface3
    val textColor = if (active) Accent else TextFaint

    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = textColor,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.06.sp,
    )
}

// ---------------------------------------------------------------------------
// Native FFT bridge — delegates to SpectrumFragment's JNI methods
// ---------------------------------------------------------------------------

private fun wfLog(msg: String) {
    try {
        val ctx = GeneralVariables.getMainContext() ?: return
        val dir = ctx.getExternalFilesDir(null) ?: return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        File(dir, "debug.log").appendText("$ts $msg\n")
    } catch (_: Exception) {}
}

/**
 * Singleton FFT bridge. The native methods are bound to SpectrumFragment's
 * class via JNI (Java_com_k1af_ft8af_ui_SpectrumFragment_*), so we
 * instantiate one SpectrumFragment to call through.
 *
 * SpectrumFragment's static initializer loads the "ft8af" native library.
 */
private object FFTBridge {
    private val fragment: SpectrumFragment by lazy { SpectrumFragment() }

    fun compute(audioData: FloatArray, fftOut: IntArray, deNoise: Boolean) {
        try {
            // Push the current display knobs (issue #428) before each frame:
            // two int stores per 160 ms, and a settings change takes effect on
            // the next frame with no lifecycle wiring. Safe without locking —
            // this observer and the native reads run on the main thread only.
            SpectrumFragment.setFFTDisplayParams(
                GeneralVariables.getFftWindowType(),
                GeneralVariables.getFftAveragingMode(),
            )
            if (deNoise) {
                fragment.getFFTDataFloat(audioData, fftOut)
            } else {
                fragment.getFFTDataRawFloat(audioData, fftOut)
            }
        } catch (e: UnsatisfiedLinkError) {
            wfLog("waterfall.FFT ERROR: native library not loaded! ${e.message}")
        }
    }
}

private fun nativeFFT(audioData: FloatArray, fftOut: IntArray, deNoise: Boolean) {
    FFTBridge.compute(audioData, fftOut, deNoise)
}
