package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.Ft8Message
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [showsOnMap]: our own transmission's echoes (full-duplex monitoring) must not
 * become station markers. Robolectric only because [Ft8Message] touches
 * android.util.Log on construction.
 */
@RunWith(RobolectricTestRunner::class)
class OwnEchoMarkerTest {
    @Test
    fun otherStationsShow() {
        assertThat(showsOnMap(Ft8Message("CQ", "DL1ABC", "JO31"))).isTrue()
    }

    @Test
    fun ownEchoIsSkipped() {
        val echo = Ft8Message("CQ", "K1AF", "FN42").apply { isOwnEcho = true }
        assertThat(showsOnMap(echo)).isFalse()
    }
}
