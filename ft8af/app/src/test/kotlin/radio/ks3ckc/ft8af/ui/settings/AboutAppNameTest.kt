package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import radio.ks3ckc.ft8af.R

/**
 * Guards the app-name label shown on the Settings > About screen.
 *
 * The original implementation had a hardcoded "FT8US" typo at AboutSettings.kt:91.
 * This test verifies that the string resource used for that label resolves to the
 * correct app name "FT8AF", so a future accidental rename or resource-ID swap will
 * be caught before it ships.
 */
@RunWith(RobolectricTestRunner::class)
class AboutAppNameTest {

    @Test
    fun appName_stringResource_resolves_to_FT8AF() {
        val context = RuntimeEnvironment.getApplication()
        assertThat(context.getString(R.string.app_name)).isEqualTo("FT8AF")
    }
}
