package radio.ks3ckc.ft8us.pota.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Computed-property coverage for the POTA data models. These are plain Kotlin
 * data classes with no Android dependencies, so a bare JUnit runner suffices.
 */
class PotaModelsTest {

    private fun spot(frequencyKhz: Double) = PotaSpot(
        activator = "K1ABC",
        frequencyKhz = frequencyKhz,
        mode = "FT8",
        reference = "K-1234",
        parkName = "Some Park",
        locationDesc = "US-CO",
        spotter = "W9XYZ",
        spotTimeUtc = "2023-11-14T22:13:20Z",
        comments = "",
    )

    private fun activation(parkRef: String, endedAtMs: Long? = null) = PotaActivation(
        id = 1L,
        parkRef = parkRef,
        operator = "K1ABC",
        startedAtMs = 0L,
        endedAtMs = endedAtMs,
        qsoCount = 0,
        notes = null,
    )

    @Test
    fun frequencyHz_convertsKhzToHz() {
        assertThat(spot(14_074.0).frequencyHz).isEqualTo(14_074_000L)
        // Fractional kHz rounds down via toLong() truncation.
        assertThat(spot(7_074.5).frequencyHz).isEqualTo(7_074_500L)
    }

    @Test
    fun isActive_trueOnlyWhenNotEnded() {
        assertThat(activation("K-1234").isActive).isTrue()
        assertThat(activation("K-1234", endedAtMs = 1_000L).isActive).isFalse()
    }

    @Test
    fun parkRefs_singleReference() {
        assertThat(activation("K-1234").parkRefs).containsExactly("K-1234")
    }

    @Test
    fun parkRefs_splitsAndTrimsCommaSeparated() {
        assertThat(activation("K-1234, K-5678 ").parkRefs)
            .containsExactly("K-1234", "K-5678").inOrder()
    }

    @Test
    fun parkRefs_filtersEmptyTokens() {
        assertThat(activation("K-1234, ,K-5678,").parkRefs)
            .containsExactly("K-1234", "K-5678").inOrder()
    }

    @Test
    fun parkRefs_emptyStringYieldsEmptyList() {
        assertThat(activation("").parkRefs).isEmpty()
    }

    @Test
    fun parkRefsDisplay_joinsWithPlus() {
        assertThat(activation("K-1234,K-5678").parkRefsDisplay).isEqualTo("K-1234 + K-5678")
        assertThat(activation("K-1234").parkRefsDisplay).isEqualTo("K-1234")
    }
}
