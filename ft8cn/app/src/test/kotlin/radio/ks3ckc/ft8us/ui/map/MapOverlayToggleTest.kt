package radio.ks3ckc.ft8us.ui.map

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bg7yoz.ft8cn.R
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose render + interaction tests for the Map screen's overlay/mode toggles
 * (run on the JVM via Robolectric). The Canvas-drawn maps themselves can't
 * render here; these cover the stateless controls layered over them — the PSK
 * Reporter overlay toggle and the standard/azimuthal view switch.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class MapOverlayToggleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pskOverlayToggle_clickEmitsToggledValue() {
        var toggledTo: Boolean? = null
        lateinit var label: String
        composeRule.setContent {
            label = stringResource(R.string.map_overlay_psk)
            PskOverlayToggle(enabled = false, onToggle = { toggledTo = it })
        }

        composeRule.onNodeWithText(label).assertIsDisplayed()
        composeRule.onNodeWithText(label).performClick()

        assertThat(toggledTo).isTrue()
    }

    @Test
    fun mapViewToggle_selectingAzimuthalEmitsThatMode() {
        var selected: MapViewMode? = null
        lateinit var azimuthalLabel: String
        composeRule.setContent {
            azimuthalLabel = stringResource(R.string.map_mode_azimuthal)
            MapViewToggle(mode = MapViewMode.STANDARD, onModeChange = { selected = it })
        }

        composeRule.onNodeWithText(azimuthalLabel).performClick()

        assertThat(selected).isEqualTo(MapViewMode.AZIMUTHAL)
    }
}
