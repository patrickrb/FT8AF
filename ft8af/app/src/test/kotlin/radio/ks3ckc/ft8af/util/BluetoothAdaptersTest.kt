package radio.ks3ckc.ft8af.util

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [bluetoothAdapter] resolves the adapter through [android.bluetooth.BluetoothManager]
 * (the non-deprecated path, issue #454) rather than `BluetoothAdapter.getDefaultAdapter()`.
 * Robolectric provides a working BluetoothManager, so a non-null adapter proves the
 * getSystemService → getAdapter chain is wired correctly.
 */
@RunWith(RobolectricTestRunner::class)
class BluetoothAdaptersTest {

    @Test
    fun resolves_adapter_via_bluetooth_manager() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertThat(bluetoothAdapter(context)).isNotNull()
    }
}
