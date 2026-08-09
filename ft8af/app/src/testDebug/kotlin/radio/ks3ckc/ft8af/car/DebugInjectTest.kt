package radio.ks3ckc.ft8af.car

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [parseDebugInject] — the pure extras→[DebugInjectSpec] mapping
 * behind the debug-only Android Auto map injection receiver. Lives in `testDebug`
 * because the class under test is in the `debug` source set.
 */
class DebugInjectTest {

    /** Empty extras → all defaults. */
    @Test
    fun defaults_whenNoExtras() {
        val spec = parseDebugInject { null }
        assertThat(spec).isEqualTo(
            DebugInjectSpec(
                opGrid = "EM29",
                partnerCall = "W1XYZ",
                partnerGrid = "FN42",
                snr = -12,
                parkRef = null,
                rotaTrip = null,
                decodes = 0,
                psk = 0,
                qsos = 0,
                waterfall = 0,
            ),
        )
    }

    /** decodes/psk counts parse as ints and clamp negatives to zero. */
    @Test
    fun decodeAndPskCounts_parseAndClamp() {
        val extras = mapOf("decodes" to "8", "psk" to "-3")
        val spec = parseDebugInject { extras[it] }
        assertThat(spec.decodes).isEqualTo(8)
        assertThat(spec.psk).isEqualTo(0)
    }

    /** qsos/wf counts parse as ints and clamp negatives to zero. */
    @Test
    fun qsoAndWaterfallCounts_parseAndClamp() {
        val extras = mapOf("qsos" to "12", "wf" to "-4")
        val spec = parseDebugInject { extras[it] }
        assertThat(spec.qsos).isEqualTo(12)
        assertThat(spec.waterfall).isEqualTo(0)
    }

    /** Supplied values win over defaults and are upper-cased. */
    @Test
    fun overrides_areUpperCased() {
        val extras = mapOf(
            "call" to "kb1abc",
            "grid" to "cn87",
            "opgrid" to "em29",
            "park" to "k-1234",
            "snr" to "5",
        )
        val spec = parseDebugInject { extras[it] }
        assertThat(spec.partnerCall).isEqualTo("KB1ABC")
        assertThat(spec.partnerGrid).isEqualTo("CN87")
        assertThat(spec.opGrid).isEqualTo("EM29")
        assertThat(spec.parkRef).isEqualTo("K-1234")
        assertThat(spec.snr).isEqualTo(5)
    }

    /** The rota extra keeps its case (trip names are free text) and trims. */
    @Test
    fun rotaTrip_parsesVerbatim_andBlankIsAbsent() {
        assertThat(parseDebugInject { mapOf("rota" to " Route 66 ")[it] }.rotaTrip)
            .isEqualTo("Route 66")
        assertThat(parseDebugInject { mapOf("rota" to "   ")[it] }.rotaTrip).isNull()
        assertThat(parseDebugInject { null }.rotaTrip).isNull()
    }

    /** Blank/whitespace extras are treated as absent (fall back to defaults). */
    @Test
    fun blankExtras_fallBackToDefaults() {
        val extras = mapOf("call" to "   ", "grid" to "", "park" to "  ")
        val spec = parseDebugInject { extras[it] }
        assertThat(spec.partnerCall).isEqualTo("W1XYZ")
        assertThat(spec.partnerGrid).isEqualTo("FN42")
        assertThat(spec.parkRef).isNull()
    }

    /** A non-numeric SNR falls back to the default rather than crashing. */
    @Test
    fun nonNumericSnr_fallsBackToDefault() {
        val spec = parseDebugInject { if (it == "snr") "loud" else null }
        assertThat(spec.snr).isEqualTo(-12)
    }

    /** Negative SNR (the common FT8 case) parses through. */
    @Test
    fun negativeSnr_parses() {
        val spec = parseDebugInject { if (it == "snr") "-8" else null }
        assertThat(spec.snr).isEqualTo(-8)
    }

    // --- demoToneFrequencies -------------------------------------------------

    /** Zero tones → empty; the caller then posts silence / skips the waterfall. */
    @Test
    fun toneFrequencies_zero_isEmpty() {
        assertThat(demoToneFrequencies(0)).isEmpty()
    }

    /** Counts above the cap are clamped so a huge `wf` value can't blow up. */
    @Test
    fun toneFrequencies_clampAndStayInBand() {
        val tones = demoToneFrequencies(999)
        assertThat(tones).hasSize(12)
        tones.forEach {
            assertThat(it).isAtLeast(250.0)
            assertThat(it).isAtMost(2750.0)
        }
    }

    /** Multiple tones are distinct and ascending (evenly spread across the band). */
    @Test
    fun toneFrequencies_distinctAscending() {
        val tones = demoToneFrequencies(6)
        assertThat(tones).hasSize(6)
        assertThat(tones).isInStrictOrder()
        assertThat(tones.first()).isWithin(1e-9).of(250.0)
        assertThat(tones.last()).isWithin(1e-9).of(2750.0)
    }

    // --- buildDemoAudio ------------------------------------------------------

    /** No tones → a silent buffer of the requested length (no crash). */
    @Test
    fun demoAudio_noTones_isSilentAndSized() {
        val audio = buildDemoAudio(emptyList(), sampleCount = 1920)
        assertThat(audio).hasLength(1920)
        assertThat(audio.all { it == 0f }).isTrue()
    }

    /** Every sample stays inside the [-1, 1] float-PCM range the recorder emits. */
    @Test
    fun demoAudio_staysInPcmRange() {
        val audio = buildDemoAudio(demoToneFrequencies(8))
        assertThat(audio).hasLength(1920)
        audio.forEach {
            assertThat(it).isAtLeast(-1f)
            assertThat(it).isAtMost(1f)
        }
    }

    /**
     * The FFT the waterfall computes should show energy at the injected tone and
     * near-nothing in an empty part of the band — otherwise the traces wouldn't
     * appear. Verified with a direct DFT magnitude at the tone vs. a quiet bin.
     */
    @Test
    fun demoAudio_concentratesEnergyAtTone() {
        val toneHz = 1500.0
        val audio = buildDemoAudio(listOf(toneHz), sampleCount = 1920, sampleRate = 12000)
        val atTone = dftMagnitude(audio, toneHz, 12000)
        val atQuiet = dftMagnitude(audio, 60.0, 12000) // below the passband → noise floor only
        assertThat(atTone).isGreaterThan(atQuiet * 20)
    }

    /** Same seed → identical buffer (deterministic); different seed → it varies. */
    @Test
    fun demoAudio_isDeterministicPerSeed() {
        val tones = demoToneFrequencies(4)
        assertThat(buildDemoAudio(tones, seed = 3)).isEqualTo(buildDemoAudio(tones, seed = 3))
        assertThat(buildDemoAudio(tones, seed = 3)).isNotEqualTo(buildDemoAudio(tones, seed = 4))
    }

    // --- buildDemoQsoLog -----------------------------------------------------

    /** Count is clamped to the sample-station pool; zero yields no entries. */
    @Test
    fun qsoLog_countClamps() {
        assertThat(buildDemoQsoLog(0, NOW)).isEmpty()
        assertThat(buildDemoQsoLog(5, NOW)).hasSize(5)
        // Requesting more than the pool returns the whole pool, not a crash.
        assertThat(buildDemoQsoLog(999, NOW).size).isAtLeast(12)
    }

    /** Every demo QSO sits in the past, has a positive duration, and cascades back. */
    @Test
    fun qsoLog_timestampsAreRecentPast() {
        val log = buildDemoQsoLog(8, NOW)
        log.forEach {
            assertThat(it.startMillis).isLessThan(NOW)
            assertThat(it.endMillis).isGreaterThan(it.startMillis)
        }
        // Newer entries first: each start is strictly earlier than the previous.
        log.map { it.startMillis }.zipWithNext { a, b -> assertThat(b).isLessThan(a) }
    }

    /** Fields are realistic: distinct calls, known modes/bands, plausible reports. */
    @Test
    fun qsoLog_fieldsAreRealistic() {
        val log = buildDemoQsoLog(12, NOW)
        assertThat(log.map { it.toCall }.toSet()).hasSize(log.size) // no duplicate calls
        log.forEach {
            assertThat(it.mode).isAnyOf("FT8", "FT4")
            assertThat(it.bandFreqHz).isGreaterThan(1_000_000L)
            assertThat(it.audioHz).isIn(200..2700)
            assertThat(it.sendReport).isIn(-25..5)
            assertThat(it.receivedReport).isIn(-25..5)
        }
    }

    private companion object {
        // Fixed reference "now" so the timestamp tests are deterministic.
        const val NOW = 1_700_000_000_000L

        /** Single-bin DFT magnitude at [freqHz] — enough to prove a tone is present. */
        fun dftMagnitude(samples: FloatArray, freqHz: Double, sampleRate: Int): Double {
            var re = 0.0
            var im = 0.0
            for (i in samples.indices) {
                val a = 2.0 * Math.PI * freqHz * i / sampleRate
                re += samples[i] * Math.cos(a)
                im += samples[i] * Math.sin(a)
            }
            return Math.hypot(re, im)
        }
    }
}
