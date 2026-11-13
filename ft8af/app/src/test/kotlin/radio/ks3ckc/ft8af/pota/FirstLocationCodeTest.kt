package radio.ks3ckc.ft8af.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for [firstLocationCode], which reduces a park's `locationDesc` to a
 * single POTA location code for the upload's `location` form field. A multi-region
 * park reports a comma-separated list POTA rejects when sent whole (verified in the
 * field: a comma value is silently dropped like a wrong one), so it must collapse
 * to one valid code. Pure JVM logic — no Android types, so no Robolectric.
 */
class FirstLocationCodeTest {

    @Test
    fun `single-region park passes its code through unchanged`() {
        assertThat(firstLocationCode("US-KS")).isEqualTo("US-KS")
    }

    @Test
    fun `multi-region park collapses to the first code`() {
        // Black Ridge Canyons (US-5750) and the Appalachian Trail (US-4556) report
        // comma-joined regions; POTA rejects the joined string, so pick one valid code.
        assertThat(firstLocationCode("US-CO,US-UT")).isEqualTo("US-CO")
        assertThat(firstLocationCode("US-CT,US-GA,US-MA,US-MD,US-ME")).isEqualTo("US-CT")
    }

    @Test
    fun `surrounding whitespace on the first code is trimmed`() {
        assertThat(firstLocationCode(" US-CO , US-UT ")).isEqualTo("US-CO")
    }

    @Test
    fun `leading empty segment is skipped for the next real code`() {
        assertThat(firstLocationCode(",US-UT")).isEqualTo("US-UT")
    }

    @Test
    fun `blank, empty, and null collapse to null`() {
        assertThat(firstLocationCode(null)).isNull()
        assertThat(firstLocationCode("")).isNull()
        assertThat(firstLocationCode("   ")).isNull()
        assertThat(firstLocationCode(",")).isNull()
    }
}
