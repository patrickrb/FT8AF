package radio.ks3ckc.ft8af.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [PotaCqClassifier.isPotaCq] — the single predicate shared by the
 * "CQ POTA" decode filter and the Hunt auto-call path (issue #333). Pure: the
 * primitive overload takes already-resolved signals, so no Robolectric/network.
 */
class PotaCqClassifierTest {

    @Test
    fun `not a CQ is never a POTA CQ`() {
        // Even with every POTA signal present, a non-CQ message must not match.
        assertThat(
            PotaCqClassifier.isPotaCq(
                isCq = false,
                modifier = "POTA",
                callsignTo = "CQ POTA",
                isSpottedPota = true,
            ),
        ).isFalse()
    }

    @Test
    fun `explicit POTA modifier on a CQ matches`() {
        assertThat(
            PotaCqClassifier.isPotaCq(
                isCq = true,
                modifier = "POTA",
                callsignTo = "CQ POTA",
                isSpottedPota = false,
            ),
        ).isTrue()
    }

    @Test
    fun `CQ from a spotted activator matches even without the suffix`() {
        // Activators often drop the "POTA" suffix; the spot lookup carries them.
        assertThat(
            PotaCqClassifier.isPotaCq(
                isCq = true,
                modifier = null,
                callsignTo = "CQ",
                isSpottedPota = true,
            ),
        ).isTrue()
    }

    @Test
    fun `garbled CQ POT prefix matches and is case insensitive`() {
        assertThat(
            PotaCqClassifier.isPotaCq(
                isCq = true,
                modifier = null,
                callsignTo = "CQ POT",
                isSpottedPota = false,
            ),
        ).isTrue()
        assertThat(
            PotaCqClassifier.isPotaCq(
                isCq = true,
                modifier = null,
                callsignTo = "cq pota",
                isSpottedPota = false,
            ),
        ).isTrue()
    }

    @Test
    fun `plain CQ with no POTA signal does not match`() {
        // The regression we're guarding: a general CQ must NOT be treated as POTA,
        // so Hunt won't auto-call it while the CQ POTA filter is active.
        assertThat(
            PotaCqClassifier.isPotaCq(
                isCq = true,
                modifier = null,
                callsignTo = "CQ",
                isSpottedPota = false,
            ),
        ).isFalse()
    }

    @Test
    fun `null callsignTo with no other signal does not match`() {
        assertThat(
            PotaCqClassifier.isPotaCq(
                isCq = true,
                modifier = null,
                callsignTo = null,
                isSpottedPota = false,
            ),
        ).isFalse()
    }
}
