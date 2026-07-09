package radio.ks3ckc.ft8af

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the default for "Hold Tx freq" (WSJT-X "Hold Tx Freq") in
 * [GeneralVariables] (issue #498). The config DB only writes a holdTxFreq row
 * once the user toggles the switch, so on a fresh install the static default is
 * what takes effect — it must be enabled so the TX offset stays put unless the
 * operator opts out. Robolectric because GeneralVariables carries Android
 * LiveData statics that load with the class.
 */
@RunWith(RobolectricTestRunner::class)
class HoldTxFreqDefaultTest {

    @After
    fun restoreDefault() {
        // Other suites read GeneralVariables.holdTxFreq; leave it at the default.
        GeneralVariables.holdTxFreq = true
    }

    @Test
    fun `hold tx freq is enabled by default`() {
        assertThat(GeneralVariables.holdTxFreq).isTrue()
    }
}
