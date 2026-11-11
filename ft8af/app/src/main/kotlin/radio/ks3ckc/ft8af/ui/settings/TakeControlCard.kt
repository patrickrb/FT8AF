package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import com.k1af.ft8af.ui.ToastMessage
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.GlassCard

/**
 * The "Take control of radio" button: a manual hard reset of this app's hold on the rig
 * for when the automatic handoff between the ks3ckc apps is not enough, or a USB
 * permission was lost. A thin wrapper — everything it does is
 * [MainViewModel.hardResetRigConnection].
 */
@Composable
internal fun TakeControlCard(mainViewModel: MainViewModel) {
    val started = stringResource(R.string.radio_take_control_started)
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    mainViewModel.hardResetRigConnection()
                    ToastMessage.show(started)
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
        }
    }
}
