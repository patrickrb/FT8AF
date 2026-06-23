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

    // ---- catTapAction (what tapping the CAT chip does) ----

    @Test
    fun `cat tap reconnects an existing connector`() {
        // A live connector exists (any rig) — just re-poke it. Flex IP irrelevant.
        assertThat(catTapAction(connectorExists = true, isFlexNetwork = true, savedFlexIp = "192.168.1.9"))
            .isEqualTo(CatTapAction.RECONNECT_EXISTING)
        assertThat(catTapAction(connectorExists = true, isFlexNetwork = false, savedFlexIp = ""))
            .isEqualTo(CatTapAction.RECONNECT_EXISTING)
    }

    @Test
    fun `cat tap cold-connects to a saved Flex when no connector yet`() {
        // The whole point: settings saved (Flex + remembered IP), no live
        // connector → connect straight to it instead of no-opping.
        assertThat(catTapAction(connectorExists = false, isFlexNetwork = true, savedFlexIp = "192.168.1.9"))
            .isEqualTo(CatTapAction.CONNECT_SAVED_FLEX)
    }

    @Test
    fun `cat tap needs setup when nothing to go on`() {
        // Flex selected but no remembered address.
        assertThat(catTapAction(connectorExists = false, isFlexNetwork = true, savedFlexIp = ""))
            .isEqualTo(CatTapAction.NEEDS_SETUP)
        // Not a Flex-network rig and nothing connected (e.g. cold USB before scan).
        assertThat(catTapAction(connectorExists = false, isFlexNetwork = false, savedFlexIp = ""))
            .isEqualTo(CatTapAction.NEEDS_SETUP)
    }
}
