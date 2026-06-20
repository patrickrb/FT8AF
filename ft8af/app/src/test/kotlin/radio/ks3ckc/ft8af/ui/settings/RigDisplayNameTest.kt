package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [resolveRigDisplayName], the operator-card rig label logic.
 *
 * The bug this guards against: a release build (R8 obfuscation on) rendered the
 * rig as a single letter like "c" because the old code used
 * baseRig.javaClass.simpleName, which collapses to an obfuscated name. The fix
 * uses the user-selected model name instead.
 */
class RigDisplayNameTest {

    @Test
    fun disconnected_returnsNotConnectedLabel() {
        assertThat(
            resolveRigDisplayName(
                connected = false,
                modelName = "Lab599 TX-500",
                notConnectedLabel = "Not connected",
            ),
        ).isEqualTo("Not connected")
    }

    @Test
    fun connected_returnsModelName() {
        assertThat(
            resolveRigDisplayName(
                connected = true,
                modelName = "Lab599 TX-500",
                notConnectedLabel = "Not connected",
            ),
        ).isEqualTo("Lab599 TX-500")
    }

    @Test
    fun connected_trimsModelName() {
        assertThat(
            resolveRigDisplayName(
                connected = true,
                modelName = "  Kenwood TS-2000  ",
                notConnectedLabel = "Not connected",
            ),
        ).isEqualTo("Kenwood TS-2000")
    }

    @Test
    fun connected_blankModelName_fallsBackToDashes() {
        assertThat(
            resolveRigDisplayName(
                connected = true,
                modelName = "   ",
                notConnectedLabel = "Not connected",
            ),
        ).isEqualTo("--")
    }

    @Test
    fun connected_emptyModelName_fallsBackToDashes() {
        assertThat(
            resolveRigDisplayName(
                connected = true,
                modelName = "",
                notConnectedLabel = "Not connected",
            ),
        ).isEqualTo("--")
    }
}
