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
                decodes = 0,
                psk = 0,
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
}
