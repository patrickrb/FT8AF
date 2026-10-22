package radio.ks3ckc.ft8af.ui.bandadvisor

import android.util.Log
import com.k1af.ft8af.GeneralVariables
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only usage telemetry for Band Advisor. The app has no remote
 * analytics backend; events go to logcat + the on-device debug.log (the
 * project's established diagnostic channel) so testers can report what they
 * did. Never logs decoded message contents, GPS coordinates, or tokens —
 * only coarse event names and band/goal labels.
 */
object BandAdvisorTelemetry {
    private const val TAG = "BandAdvisor"

    fun event(name: String, detail: String?) {
        val line = if (detail.isNullOrBlank()) name else "$name: $detail"
        Log.d(TAG, line)
        try {
            val ctx = GeneralVariables.getMainContext() ?: return
            val dir = ctx.getExternalFilesDir(null) ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(File(dir, "debug.log"), true).use {
                it.append("$ts BandAdvisor: $line\n")
            }
        } catch (_: Exception) {
        }
    }
}
