package radio.ks3ckc.ft8us

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Creates a [PendingIntent] for USB permission requests that is safe on all
 * supported API levels.
 *
 * Two Android version gates apply:
 *
 * 1. **API 31+ (Android 12 / S):** `FLAG_MUTABLE` is required so the system
 *    can attach `EXTRA_PERMISSION_GRANTED` to the result intent. Older APIs
 *    use `FLAG_IMMUTABLE`.
 *
 * 2. **API 34+ (Android 14 / U) with targetSdk 34+:** A mutable
 *    `PendingIntent` backed by an *implicit* intent (no component or package)
 *    throws `IllegalArgumentException`. Setting the package makes the intent
 *    explicit and avoids the crash.
 *
 * Centralising this logic in one place prevents the implicit-intent regression
 * from reappearing in any caller.
 *
 * @param context  Application or activity context.
 * @param action   The broadcast action string
 *                 (e.g. `"com.bg7yoz.ft8cn.USB_AUDIO_PERMISSION"`).
 * @return A broadcast [PendingIntent] ready for [android.hardware.usb.UsbManager.requestPermission].
 */
fun createUsbPermissionIntent(context: Context, action: String): PendingIntent {
    val intent = Intent(action).apply {
        setPackage(context.packageName)
    }
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_MUTABLE
    } else {
        PendingIntent.FLAG_IMMUTABLE
    }
    return PendingIntent.getBroadcast(context, 0, intent, flags)
}
