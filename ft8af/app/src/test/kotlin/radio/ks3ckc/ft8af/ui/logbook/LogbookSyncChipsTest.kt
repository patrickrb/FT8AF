package radio.ks3ckc.ft8af.ui.logbook

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Unit tests for [syncChipLabels], the per-row "uploaded to" chips in the logbook. */
class LogbookSyncChipsTest {

    @Test
    fun `no chips when the row reached no service`() {
        assertThat(syncChipLabels(cloudlog = false, qrz = false, wrl = false, cloudlogLabel = "CL")).isEmpty()
    }

    @Test
    fun `all three services appear in a fixed order`() {
        assertThat(syncChipLabels(cloudlog = true, qrz = true, wrl = true, cloudlogLabel = "WL"))
            .containsExactly("WL", "QRZ", "WRL")
            .inOrder()
    }

    @Test
    fun `world radio league alone gets its own chip`() {
        assertThat(syncChipLabels(cloudlog = false, qrz = false, wrl = true, cloudlogLabel = "CL"))
            .containsExactly("WRL")
    }
}
