package radio.ks3ckc.ft8us.ui.map

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun pskOverlayToggle_clickEmitsToggledValue() {
        val label = context.getString(R.string.map_overlay_psk)
        var toggledTo: Boolean? = null
        composeRule.setContent {
            PskOverlayToggle(enabled = false, onToggle = { toggledTo = it })
        }

        composeRule.onNodeWithText(label).assertIsDisplayed()
        composeRule.onNodeWithText(label).performClick()

        assertThat(toggledTo).isTrue()
    }

    @Test
    fun mapViewToggle_selectingAzimuthalEmitsThatMode() {
        val azimuthalLabel = context.getString(R.string.map_mode_azimuthal)
        var selected: MapViewMode? = null
        composeRule.setContent {
            MapViewToggle(mode = MapViewMode.STANDARD, onModeChange = { selected = it })
        }

        composeRule.onNodeWithText(azimuthalLabel).performClick()

        assertThat(selected).isEqualTo(MapViewMode.AZIMUTHAL)
    }
}
