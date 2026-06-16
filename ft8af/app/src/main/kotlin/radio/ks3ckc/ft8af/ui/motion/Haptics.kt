package radio.ks3ckc.ft8af.ui.motion

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

class HapticsController internal constructor(private val view: View) {

    fun tick() {
        perform(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun confirm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perform(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.VIRTUAL_KEY)
        } else {
            perform(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun success() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perform(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.LONG_PRESS)
        } else {
            perform(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun warn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perform(HapticFeedbackConstants.REJECT, HapticFeedbackConstants.LONG_PRESS)
        } else {
            perform(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun perform(preferred: Int, fallback: Int) {
        runCatching { view.performHapticFeedback(preferred) }
            .onFailure { runCatching { view.performHapticFeedback(fallback) } }
    }
}

@Composable
fun rememberHaptics(): HapticsController {
    val view = LocalView.current
    return remember(view) { HapticsController(view) }
}
