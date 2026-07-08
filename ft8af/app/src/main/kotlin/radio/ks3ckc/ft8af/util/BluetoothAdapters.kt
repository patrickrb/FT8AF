package radio.ks3ckc.ft8af.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * Returns the device's [BluetoothAdapter], obtained via the [BluetoothManager] system
 * service. This is the non-deprecated replacement for `BluetoothAdapter.getDefaultAdapter()`,
 * which was deprecated in Android 12 (API 31); the [BluetoothManager] route has been the
 * recommended way to obtain the adapter since API 18 (issue #454).
 *
 * Returns `null` on devices without Bluetooth — either the `BLUETOOTH_SERVICE` is absent
 * or the manager reports no adapter — mirroring the null contract of the old API so call
 * sites keep their existing null handling.
 */
fun bluetoothAdapter(context: Context): BluetoothAdapter? {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return manager?.adapter
}
