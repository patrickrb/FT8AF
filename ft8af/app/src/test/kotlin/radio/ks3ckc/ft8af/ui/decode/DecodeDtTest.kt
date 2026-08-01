package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import radio.ks3ckc.ft8af.ui.components.CLOCK_SYNC_FAIR_SEC
import org.junit.Test

/**
 * The per-decode DT label.
 *
 * The sign is the whole point of this field — it tells an operator which way
 * their clock is wrong — so the cases here are mostly about the sign surviving
 * rounding, and about "-0.0" never reaching the screen.
 */
class DecodeDtTest {
    @Test
    fun `sign is always explicit for non-zero values`() {
        assertThat(formatDecodeDt(1.3f)).isEqualTo("+1.3")
        assertThat(formatDecodeDt(-1.3f)).isEqualTo("-1.3")
        assertThat(formatDecodeDt(0.2f)).isEqualTo("+0.2")
        assertThat(formatDecodeDt(-0.2f)).isEqualTo("-0.2")
    }

    @Test
    fun `values rounding to zero render unsigned`() {
        // "-0.0" is noise in a column an operator is scanning for a sign.
        assertThat(formatDecodeDt(0f)).isEqualTo("0.0")
        assertThat(formatDecodeDt(-0.01f)).isEqualTo("0.0")
        assertThat(formatDecodeDt(0.04f)).isEqualTo("0.0")
        assertThat(formatDecodeDt(-0.04f)).isEqualTo("0.0")
    }

    @Test
    fun `rounds to one decimal like WSJT-X`() {
        assertThat(formatDecodeDt(1.24f)).isEqualTo("+1.2")
        assertThat(formatDecodeDt(1.26f)).isEqualTo("+1.3")
        assertThat(formatDecodeDt(-2.55f)).isEqualTo("-2.5")
    }

    @Test
    fun `a non-finite reading degrades to a placeholder rather than crashing`() {
        assertThat(formatDecodeDt(Float.NaN)).isEqualTo("--")
        assertThat(formatDecodeDt(Float.POSITIVE_INFINITY)).isEqualTo("--")
        assertThat(formatDecodeDt(Float.NEGATIVE_INFINITY)).isEqualTo("--")
    }

    @Test
    fun `notable threshold is the slot bar's fair boundary, not a copy of it`() {
        // A row must not shout while the averaged pill upstream still says "fair",
        // so the boundary is asserted against the shared constant rather than a
        // repeated literal — a literal here would pass even after the two drifted.
        assertThat(isDecodeDtNotable(CLOCK_SYNC_FAIR_SEC)).isFalse()
        assertThat(isDecodeDtNotable(CLOCK_SYNC_FAIR_SEC + 0.1f)).isTrue()
        assertThat(isDecodeDtNotable(-(CLOCK_SYNC_FAIR_SEC + 0.1f))).isTrue()
        assertThat(isDecodeDtNotable(CLOCK_SYNC_FAIR_SEC - 0.7f)).isFalse()
    }

    @Test
    fun `a non-finite reading is never notable`() {
        assertThat(isDecodeDtNotable(Float.NaN)).isFalse()
        assertThat(isDecodeDtNotable(Float.POSITIVE_INFINITY)).isFalse()
    }
}
