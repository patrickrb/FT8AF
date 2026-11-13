package radio.ks3ckc.ft8af

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Restore media volume" (issue #985): put the phone's media volume back to the
 * level the operator saved, at startup and before every key-down.
 *
 * Some phones (reported on a Samsung SM-A505) drop media volume after a reboot,
 * and Android's hearing-safety policy lowers it again after long listening on a
 * wired/USB output. The sound-card TX path plays on the media stream, so a
 * silently lowered volume means less (or no) drive to the rig.
 *
 * Only ever raises: if the volume is already at or above the saved level it is
 * left alone, so this never pushes output past what the operator chose. Android
 * may refuse a raise above its safe-volume threshold until the user accepts the
 * "high volume may damage your hearing" warning once; that shows up as
 * [Outcome.CAPPED] (read back below the target) and is logged, not retried.
 *
 * The decision logic is plain functions so it's unit-testable; [restore] is the
 * thin wrapper over [AudioManager].
 */
object MediaVolumeRestore {

    const val CONFIG_ENABLED = "restoreMediaVolume"
    const val CONFIG_LEVEL = "restoreMediaVolumeLevel"

    enum class Outcome { DISABLED, NO_SAVED_LEVEL, UNAVAILABLE, AT_LEVEL, RAISED, CAPPED }

    // One toast per process for a capped raise: the before-TX check runs every
    // over, and the fix (accept the system warning once) doesn't change per over.
    @Volatile
    private var cappedToastShown = false

    private val _savedLevel = MutableStateFlow(GeneralVariables.restoreMediaVolumeLevel)

    /**
     * The saved level as observable state, so the Settings row follows changes
     * made elsewhere (hardware volume keys) instead of showing a stale copy
     * (Copilot review on #986).
     */
    @JvmStatic
    val savedLevel: StateFlow<Int> = _savedLevel.asStateFlow()

    /**
     * Set the level to restore to. The one writer for
     * [GeneralVariables.restoreMediaVolumeLevel]; callers persist it to the DB.
     */
    @JvmStatic
    fun saveLevel(level: Int) {
        GeneralVariables.restoreMediaVolumeLevel = level
        _savedLevel.value = level
    }

    /**
     * The media volume index to raise to, or null to leave it alone.
     *
     * @param current the stream's current index
     * @param saved the saved index, negative when none has been saved
     * @param max the stream's max index on this device
     */
    @JvmStatic
    fun raiseTarget(current: Int, saved: Int, max: Int): Int? {
        if (saved < 0 || max <= 0) return null
        val target = saved.coerceAtMost(max)
        return if (current < target) target else null
    }

    /** Whether the index read back after a raise to [target] reached it. */
    @JvmStatic
    fun classify(target: Int, after: Int): Outcome = if (after >= target) Outcome.RAISED else Outcome.CAPPED

    /** The current media volume index, or -1 when it can't be read. */
    @JvmStatic
    fun currentLevel(context: Context?): Int {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return -1
        return am.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    /** The media stream's max index, or 0 when it can't be read. */
    @JvmStatic
    fun maxLevel(context: Context?): Int {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    /** Settings row description: adds the saved level once one exists and the setting is on. */
    @JvmStatic
    fun description(base: String, saved: String, enabled: Boolean, savedLevel: Int): String =
        if (enabled && savedLevel >= 0) "$base\n$saved" else base

    /**
     * Raise media volume to the saved level if the setting is on and it has
     * dropped below it. Safe to call from any thread.
     *
     * @param reason short label for the log line ("startup", "resume", "tx", "tune")
     */
    @JvmStatic
    fun restore(context: Context?, reason: String): Outcome {
        if (!GeneralVariables.restoreMediaVolume) return Outcome.DISABLED
        val saved = GeneralVariables.restoreMediaVolumeLevel
        if (saved < 0) return Outcome.NO_SAVED_LEVEL
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Outcome.UNAVAILABLE
        return try {
            if (am.isVolumeFixed) return Outcome.UNAVAILABLE
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = raiseTarget(current, saved, max) ?: return Outcome.AT_LEVEL
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            val after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val outcome = classify(target, after)
            GeneralVariables.fileLog(
                "MediaVolumeRestore($reason): media volume $current -> $after of $max " +
                    "(saved $saved) $outcome",
            )
            if (outcome == Outcome.CAPPED && !cappedToastShown) {
                cappedToastShown = true
                // A real toast, not ToastMessage: that only feeds the legacy
                // debug-message LiveData, which the Compose launcher doesn't
                // observe (Copilot review on #986). This runs on the TX worker
                // too, so post to the main looper.
                val appContext = context.applicationContext
                val text = appContext.getString(R.string.restore_media_volume_capped).format(after, max)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
                }
            }
            outcome
        } catch (e: RuntimeException) {
            // Do Not Disturb policy can reject volume changes (SecurityException),
            // and this runs on the keying path, so nothing here may block TX.
            GeneralVariables.fileLog("MediaVolumeRestore($reason): rejected: ${e.message}")
            Outcome.UNAVAILABLE
        }
    }
}
