package radio.ks3ckc.ft8us.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

/**
 * Setup dialog for FT8 DXpedition "Hound" mode. The user tunes the rig to the
 * Fox's published dial frequency (via the normal frequency picker), then enters
 * the Fox's base callsign here and an initial call frequency in the 1000-4000 Hz
 * Hound band. On Start, the app begins calling the Fox and auto-QSYs down when
 * answered.
 */
@Composable
fun HoundSetupSheet(
    visible: Boolean,
    initialFoxCall: String,
    onDismiss: () -> Unit,
    onStart: (foxCall: String, callFreqHz: Float) -> Unit,
) {
    if (!visible) return

    var foxCall by remember { mutableStateOf(initialFoxCall) }
    var callFreq by remember { mutableStateOf("2500") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Work a DXpedition (Hound)") },
        text = {
            Column {
                Text(
                    "Tune the rig to the Fox's published dial frequency first. " +
                        "You call high (1000–4000 Hz); the app auto-QSYs you " +
                        "down to where the Fox answers and replies with your report.",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = foxCall,
                    onValueChange = { foxCall = it.uppercase().trim() },
                    label = { Text("Fox callsign") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = callFreq,
                    onValueChange = { v -> callFreq = v.filter { it.isDigit() }.take(4) },
                    label = { Text("Call frequency (Hz, 1000–4000)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val f = (callFreq.toFloatOrNull() ?: 2500f).coerceIn(1000f, 4000f)
                    if (foxCall.trim().length >= 3) onStart(foxCall.trim(), f)
                },
            ) { Text("Start") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
