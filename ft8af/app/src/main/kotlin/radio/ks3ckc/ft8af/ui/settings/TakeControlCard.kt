package radio.ks3ckc.ft8af.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.handoff.RigHardReset
import com.k1af.ft8af.ui.ToastMessage
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.GlassCard

/** Request code for the microphone re-ask; the activity handles the result for any code. */
private const val TAKE_CONTROL_MIC_REQUEST = 0x7AC0

/**
 * The line shown after "Take control of radio" is pressed: either a permission prompt is
 * coming, or there was nothing to ask because every permission is already granted.
 */
internal fun takeControlMessageRes(outcome: RigHardReset.Outcome): Int = when (outcome) {
    RigHardReset.Outcome.ASKING -> R.string.radio_take_control_asking
    RigHardReset.Outcome.ALL_GRANTED -> R.string.radio_take_control_all_granted
    RigHardReset.Outcome.NO_USB_DEVICE -> R.string.radio_take_control_no_usb
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The "Take control of radio" button: a manual hard reset of this app's hold on the rig
 * for when the automatic handoff between the ks3ckc apps is not enough, or a permission
 * was lost. It re-asks for whatever is missing (USB devices and the microphone) and says
 * so; when nothing is missing it tells the operator every permission is in place. A thin
 * wrapper — the work is [MainViewModel.hardResetRigConnection], the wording decision
 * [RigHardReset.outcome].
 */
@Composable
internal fun TakeControlCard(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val micMissing = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO,
                    ) != PackageManager.PERMISSION_GRANTED
                    val result = mainViewModel.hardResetRigConnection()
                    if (micMissing) {
                        context.findActivity()?.let {
                            ActivityCompat.requestPermissions(
                                it,
                                arrayOf(Manifest.permission.RECORD_AUDIO),
                                TAKE_CONTROL_MIC_REQUEST,
                            )
                        }
                    }
                    val message = context.getString(
                        takeControlMessageRes(RigHardReset.outcome(result, micMissing)),
                    )
                    status = message
                    ToastMessage.show(message)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.radio_take_control), color = Accent)
            }
            Text(
                text = stringResource(R.string.radio_take_control_hint),
                color = TextMuted,
                fontSize = 12.sp,
            )
            status?.let {
                Text(text = it, color = Accent, fontSize = 13.sp)
            }
        }
    }
}
