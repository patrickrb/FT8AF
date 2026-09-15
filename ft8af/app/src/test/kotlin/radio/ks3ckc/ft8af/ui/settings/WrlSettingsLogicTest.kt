package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.log.ThirdPartyService
import org.junit.Test

/** Unit tests for the World Radio League logbook-picker helpers behind WrlSettingsDialog. */
class WrlSettingsLogicTest {

    private val logbooks = listOf(
        ThirdPartyService.StationProfile("uuid-home", "Home", "K1AF", ""),
        ThirdPartyService.StationProfile("uuid-pota", "POTA", "", ""),
    )

    @Test
    fun `blank id is the account default logbook`() {
        assertThat(wrlLogbookSelectionLabel(logbooks, "", "Default logbook")).isEqualTo("Default logbook")
        assertThat(wrlLogbookPickerIndex(logbooks, "")).isEqualTo(0)
        assertThat(wrlLogbookIdAt(logbooks, 0)).isEmpty()
    }

    @Test
    fun `picker lists the default first then each logbook by name`() {
        assertThat(wrlLogbookPickerItems(logbooks, "Default logbook"))
            .containsExactly("Default logbook", "Home (K1AF)", "POTA")
            .inOrder()
    }

    @Test
    fun `picker index and id round-trip through the offset default row`() {
        val index = wrlLogbookPickerIndex(logbooks, "uuid-pota")
        assertThat(index).isEqualTo(2)
        assertThat(wrlLogbookIdAt(logbooks, index)).isEqualTo("uuid-pota")
        assertThat(wrlLogbookSelectionLabel(logbooks, "uuid-home", "Default logbook")).isEqualTo("Home (K1AF)")
    }

    @Test
    fun `a lone logbook is chosen automatically only when nothing is selected`() {
        val lone = listOf(ThirdPartyService.StationProfile("uuid-only", "Test", "", ""))
        assertThat(wrlAutoSelectLogbook(lone, "")).isEqualTo("uuid-only")
        // An explicit choice (even one no longer listed) is never overridden.
        assertThat(wrlAutoSelectLogbook(lone, "uuid-other")).isEqualTo("uuid-other")
        // With several logbooks the user has to pick; with none there is nothing to pick.
        assertThat(wrlAutoSelectLogbook(logbooks, "")).isEmpty()
        assertThat(wrlAutoSelectLogbook(emptyList(), "")).isEmpty()
    }

    @Test
    fun `a saved logbook that is no longer listed shows its raw id`() {
        assertThat(wrlLogbookSelectionLabel(emptyList(), "uuid-gone", "Default logbook")).isEqualTo("uuid-gone")
        assertThat(wrlLogbookPickerIndex(logbooks, "uuid-gone")).isEqualTo(0)
        assertThat(wrlLogbookIdAt(logbooks, 9)).isEmpty()
    }
}
