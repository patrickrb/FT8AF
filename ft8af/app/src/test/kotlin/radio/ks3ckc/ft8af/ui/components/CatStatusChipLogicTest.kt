package radio.ks3ckc.ft8af.ui.components

import com.k1af.ft8af.R
import com.k1af.ft8af.database.ControlMode
import com.k1af.ft8af.rigs.CatConnectionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.StatusBad
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.TextMuted

/**
 * Unit tests for the pure CAT-status-chip logic ([catChipVisuals] and
 * [shouldShowCatChip]) extracted from CatStatusChip so the Composable stays a
 * thin wrapper. No Android runtime needed: CatConnectionState is a plain enum,
 * ControlMode is int constants, and Compose Color is a JVM value class.
 */
class CatStatusChipLogicTest {

    @Test
    fun `disconnected is muted, not pulsing, not linked`() {
        val v = catChipVisuals(CatConnectionState.DISCONNECTED)
        assertThat(v.dotColor).isEqualTo(TextMuted)
        assertThat(v.pulsing).isFalse()
        assertThat(v.contentDescriptionRes).isEqualTo(R.string.cat_status_disconnected)
        assertThat(v.labelRes).isEqualTo(R.string.cat_status_not_linked)
    }

    @Test
    fun `connecting is amber and pulsing, not linked yet`() {
        val v = catChipVisuals(CatConnectionState.CONNECTING)
        assertThat(v.dotColor).isEqualTo(Accent)
        assertThat(v.pulsing).isTrue()
        assertThat(v.contentDescriptionRes).isEqualTo(R.string.cat_status_connecting)
        assertThat(v.labelRes).isEqualTo(R.string.cat_status_not_linked)
    }

    @Test
    fun `connected is green, not pulsing, linked`() {
        val v = catChipVisuals(CatConnectionState.CONNECTED)
        assertThat(v.dotColor).isEqualTo(StatusConfirmed)
        assertThat(v.pulsing).isFalse()
        assertThat(v.contentDescriptionRes).isEqualTo(R.string.cat_status_connected)
        assertThat(v.labelRes).isEqualTo(R.string.cat_status_linked)
    }

    @Test
    fun `error is red, not pulsing, not linked`() {
        val v = catChipVisuals(CatConnectionState.ERROR)
        assertThat(v.dotColor).isEqualTo(StatusBad)
        assertThat(v.pulsing).isFalse()
        assertThat(v.contentDescriptionRes).isEqualTo(R.string.cat_status_error)
        assertThat(v.labelRes).isEqualTo(R.string.cat_status_not_linked)
    }

    @Test
    fun `only the connected state reads as linked`() {
        val linked = CatConnectionState.values().filter {
            catChipVisuals(it).labelRes == R.string.cat_status_linked
        }
        assertThat(linked).containsExactly(CatConnectionState.CONNECTED)
    }

    @Test
    fun `chip shown for any CAT-control mode even when disconnected`() {
        assertThat(shouldShowCatChip(ControlMode.CAT, CatConnectionState.DISCONNECTED)).isTrue()
        assertThat(shouldShowCatChip(ControlMode.BLUETOOTH, CatConnectionState.DISCONNECTED)).isTrue()
        assertThat(shouldShowCatChip(ControlMode.RTS, CatConnectionState.DISCONNECTED)).isTrue()
        assertThat(shouldShowCatChip(ControlMode.DTR, CatConnectionState.DISCONNECTED)).isTrue()
    }

    @Test
    fun `chip hidden for VOX while disconnected`() {
        assertThat(shouldShowCatChip(ControlMode.VOX, CatConnectionState.DISCONNECTED)).isFalse()
    }

    @Test
    fun `chip shown for VOX once a connection is in progress or active`() {
        assertThat(shouldShowCatChip(ControlMode.VOX, CatConnectionState.CONNECTING)).isTrue()
        assertThat(shouldShowCatChip(ControlMode.VOX, CatConnectionState.CONNECTED)).isTrue()
        assertThat(shouldShowCatChip(ControlMode.VOX, CatConnectionState.ERROR)).isTrue()
    }

    @Test
    fun `afterDisconnect preserves ERROR so the chip stays red`() {
        // A failed connect emits ERROR then immediately DISCONNECTED; the latter
        // must not clobber the error.
        assertThat(CatConnectionState.afterDisconnect(CatConnectionState.ERROR))
            .isEqualTo(CatConnectionState.ERROR)
    }

    @Test
    fun `afterDisconnect falls to DISCONNECTED from non-error states`() {
        assertThat(CatConnectionState.afterDisconnect(CatConnectionState.CONNECTED))
            .isEqualTo(CatConnectionState.DISCONNECTED)
        assertThat(CatConnectionState.afterDisconnect(CatConnectionState.CONNECTING))
            .isEqualTo(CatConnectionState.DISCONNECTED)
        assertThat(CatConnectionState.afterDisconnect(CatConnectionState.DISCONNECTED))
            .isEqualTo(CatConnectionState.DISCONNECTED)
    }
}
