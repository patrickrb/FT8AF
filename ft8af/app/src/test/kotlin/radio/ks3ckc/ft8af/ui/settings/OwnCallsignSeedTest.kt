package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ownCallsignsToSeed] decides which callsign strings get their hashes seeded
 * into Ft8Message.hashList when the operator sets their callsign in Settings.
 * A compound call must seed its base call too, matching what DatabaseOpr does
 * at startup (issue #392).
 */
@RunWith(RobolectricTestRunner::class)
class OwnCallsignSeedTest {

    @Test
    fun simpleCallsign_seedsItself() {
        assertThat(ownCallsignsToSeed("DM5HF")).containsExactly("DM5HF")
    }

    @Test
    fun prefixedCompoundCallsign_seedsFullAndBaseCall() {
        assertThat(ownCallsignsToSeed("SV8/DM5HF"))
            .containsExactly("SV8/DM5HF", "DM5HF")
            .inOrder()
    }

    @Test
    fun suffixedCompoundCallsign_seedsFullAndBaseCall() {
        assertThat(ownCallsignsToSeed("W1AW/P"))
            .containsExactly("W1AW/P", "W1AW")
            .inOrder()
    }

    @Test
    fun emptyCallsign_seedsNothing() {
        assertThat(ownCallsignsToSeed("")).isEmpty()
    }
}
