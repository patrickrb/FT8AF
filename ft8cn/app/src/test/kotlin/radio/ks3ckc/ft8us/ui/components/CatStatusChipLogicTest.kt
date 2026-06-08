package radio.ks3ckc.ft8us.ui.components

import com.bg7yoz.ft8cn.R
import com.bg7yoz.ft8cn.database.ControlMode
import com.bg7yoz.ft8cn.rigs.CatConnectionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8us.theme.Accent
import radio.ks3ckc.ft8us.theme.StatusBad
import radio.ks3ckc.ft8us.theme.StatusConfirmed
import radio.ks3ckc.ft8us.theme.TextMuted

/**
 * Unit tests for the pure CAT-status-chip logic ([catChipVisuals] and
 * [shouldShowCatChip]) extracted from CatStatusChip so the Composable stays a
 * thin wrapper. No Android runtime needed: CatConnectionState is a plain enum,
 * ControlMode is int constants, and Compose Color is a JVM value class.
 */
class CatStatusChipLogicTest {

    @Test
    fun `disconnected is muted, not pulsing`() {
        val v = catChipVisuals(CatConnectionState.DISCONNECTED)
        assertThat(v.dotColor).isEqualTo(TextMuted)
        assertThat(v.pulsing).isFalse()
        assertThat(v.contentDescriptionRes).isEqualTo(R.string.cat_status_disconnected)
    }

    @Test
    fun `connecting is amber and pulsing`() {
        val v = catChipVisuals(CatConnectionState.CONNECTING)
        assertThat(v.dotColor).isEqualTo(Accent)
        assertThat(v.pulsing).isTrue()
        assertThat(v.contentDescriptionRes).isEqualTo(R.string.cat_status_connecting)
    }

    @Test
    fun `connected is green, not pulsing`() {
        val v = catChipVisuals(CatConnectionState.CONNECTED)
        assertThat(v.dotColor).isEqualTo(StatusConfirmed)
        assertThat(v.pulsing).isFalse()
        assertThat(v.contentDescriptionRes).isEqualTo(R.string.cat_status_connected)
    }

    @Test
    fun `error is red, not pulsing`() {
        val v = catChipVisuals(CatConnectionState.ERROR)
        assertThat(v.dotColor).isEqualTo(StatusBad)
        assertThat(v.pulsing).isFalse()
        assertThat(v.contentDescriptionRes).isEqualTo(R.string.cat_status_error)
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
}
