package radio.ks3ckc.ft8af.car

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.pskreporter.PskReporterSpot
import java.util.Locale

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
        // Frame 10 is inside the station's TX burst (see demoStationActive).
        val audio = buildDemoAudio(listOf(toneHz), sampleCount = 1920, sampleRate = 12000, seed = 10)
        // 8-FSK: the energy sits on exactly one of the eight 6.25 Hz tones above the base.
        val expected = toneHz + demoSymbolTone(0, 10 - FT8_TX_START_SYMBOL) * FT8_TONE_SPACING_HZ
        val atTone = dftMagnitude(audio, expected, 12000)
        val atQuiet = dftMagnitude(audio, 60.0, 12000) // below the passband → noise floor only
        assertThat(atTone).isGreaterThan(atQuiet * 20)
    }

    /** Between messages (slot edges) a station is silent: only the noise floor remains. */
    @Test
    fun demoAudio_silentOutsideTxBurst() {
        val toneHz = 1500.0
        val audio = buildDemoAudio(listOf(toneHz), seed = 0) // symbol 0 of the slot, before TX start
        val peak = audio.maxOf { kotlin.math.abs(it) }
        assertThat(peak).isLessThan(0.02f)
    }

    /** Costas sync tones land at symbols 0-6 / 36-42 / 72-78; data symbols stay in 0..7. */
    @Test
    fun demoSymbolTone_costasAndRange() {
        val costas = listOf(3, 1, 4, 0, 6, 5, 2)
        assertThat((0..6).map { demoSymbolTone(0, it) }).isEqualTo(costas)
        assertThat((36..42).map { demoSymbolTone(5, it) }).isEqualTo(costas)
        assertThat((72..78).map { demoSymbolTone(2, it) }).isEqualTo(costas)
        (7..35).forEach { assertThat(demoSymbolTone(1, it)).isIn(0..7) }
        // Different stations hop differently, so traces don't move in lock-step.
        assertThat((7..35).map { demoSymbolTone(1, it) }).isNotEqualTo((7..35).map { demoSymbolTone(2, it) })
    }

    /** Stations alternate 15 s slots and key only for the 79-symbol message window. */
    @Test
    fun demoStationActive_slotParityAndBurstWindow() {
        val burst = FT8_TX_START_SYMBOL until FT8_TX_START_SYMBOL + FT8_TX_SYMBOLS
        // index 0: both slots; index 1: even slots only; index 2: odd slots only.
        assertThat(demoStationActive(0, burst.first)).isTrue()
        assertThat(demoStationActive(0, FT8_SYMBOLS_PER_SLOT + burst.first)).isTrue()
        assertThat(demoStationActive(1, burst.first)).isTrue()
        assertThat(demoStationActive(1, FT8_SYMBOLS_PER_SLOT + burst.first)).isFalse()
        assertThat(demoStationActive(2, burst.first)).isFalse()
        assertThat(demoStationActive(2, FT8_SYMBOLS_PER_SLOT + burst.first)).isTrue()
        // Outside the message window nobody is keyed.
        assertThat(demoStationActive(0, 0)).isFalse()
        assertThat(demoStationActive(0, burst.last + 1)).isFalse()
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

    // --- busy-band decode scene ---------------------------------------------

    /** `mycall` parses and is upper-cased; absent → null so the live callsign is kept. */
    @Test
    fun myCall_parsesUpperCased_orNull() {
        assertThat(parseDebugInject { if (it == "mycall") "ks3ckc" else null }.myCall).isEqualTo("KS3CKC")
        assertThat(parseDebugInject { null }.myCall).isNull()
    }

    /** Count is clamped to the scene size; zero yields no rows. */
    @Test
    fun demoDecodes_countClamps() {
        assertThat(buildDemoDecodes(0, NOW)).isEmpty()
        assertThat(buildDemoDecodes(5, NOW)).hasSize(5)
        assertThat(buildDemoDecodes(999, NOW)).hasSize(DEMO_SCENE.size)
    }

    /** Timestamps sit on 15 s slot boundaries, in the past, newest row first. */
    @Test
    fun demoDecodes_timestampsAreSlotAlignedAndDescending() {
        val rows = buildDemoDecodes(DEMO_SCENE.size, NOW)
        rows.forEach { (_, utc) ->
            assertThat(utc % 15_000L).isEqualTo(0L)
            assertThat(utc).isLessThan(NOW)
        }
        rows.map { it.second }.zipWithNext { a, b -> assertThat(b).isAtMost(a) }
        // The newest slot is the last *completed* one, not the in-progress slot.
        val inProgressSlot = (NOW / 15_000L) * 15_000L
        assertThat(rows.first().second).isEqualTo(inProgressSlot - 15_000L)
    }

    /** Scene rows look like a real cycle: distinct stations, in-passband offsets, varied SNR. */
    @Test
    fun demoScene_fieldsAreRealistic() {
        assertThat(DEMO_SCENE.map { it.callFrom }.toSet()).hasSize(DEMO_SCENE.size)
        DEMO_SCENE.forEach {
            assertThat(it.freqHz).isIn(200..2800)
            assertThat(it.snr).isIn(-24..10)
            assertThat(it.dt).isWithin(1.0f).of(0f)
            assertThat(it.grid).matches("[A-R]{2}[0-9]{2}")
        }
        assertThat(DEMO_SCENE.map { it.snr }.toSet().size).isGreaterThan(5)
        assertThat(DEMO_SCENE.map { it.slotsAgo }.toSet()).containsAtLeast(0, 1)
    }

    /** The ME placeholder resolves to the operator callsign, or a fallback when unset. */
    @Test
    fun resolveDemoCallTo_handlesPlaceholder() {
        assertThat(resolveDemoCallTo(ME, "KS3CKC")).isEqualTo("KS3CKC")
        assertThat(resolveDemoCallTo(ME, "")).isEqualTo("W1AW")
        assertThat(resolveDemoCallTo("CQ", "KS3CKC")).isEqualTo("CQ")
        assertThat(resolveDemoCallTo("K7ABC", "")).isEqualTo("K7ABC")
    }

    /** Reports are signed and zero-padded to two digits like a real FT8 frame. */
    @Test
    fun formatDemoReport_signedTwoDigits() {
        assertThat(formatDemoReport(-8)).isEqualTo("-08")
        assertThat(formatDemoReport(-15)).isEqualTo("-15")
        assertThat(formatDemoReport(2)).isEqualTo("+02")
        assertThat(formatDemoReport(0)).isEqualTo("+00")
    }

    /**
     * The report must be ASCII regardless of the device locale: a default-locale
     * `String.format("%02d")` renders Arabic-Indic digits under ar-EG.
     */
    @Test
    fun formatDemoReport_isLocaleInvariant() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertThat(formatDemoReport(-8)).isEqualTo("-08")
            assertThat(formatDemoReport(12)).isEqualTo("+12")
        } finally {
            Locale.setDefault(saved)
        }
    }

    /** psk>0 routes the map to the demo spots; psk=0 hands it back to the live fetch. */
    @Test
    fun demoSpotsOverride_nullWhenNoPskRequested() {
        val spots = listOf(PskReporterSpot("K7ABC", "CN87", 47.5, -122.3, 14_074_000L, -12, "FT8", 0L))
        assertThat(demoSpotsOverride(3, spots)).isEqualTo(spots)
        assertThat(demoSpotsOverride(0, spots)).isNull()
        assertThat(demoSpotsOverride(0, emptyList())).isNull()
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
