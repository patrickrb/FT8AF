package radio.ks3ckc.ft8us.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.R
import radio.ks3ckc.ft8us.theme.Accent
import radio.ks3ckc.ft8us.theme.BgSurface2
import radio.ks3ckc.ft8us.theme.BorderStrong
import radio.ks3ckc.ft8us.theme.TextFaint
import radio.ks3ckc.ft8us.theme.TextMuted
import radio.ks3ckc.ft8us.theme.TextPrimary
import java.io.File

/**
 * "Report a Bug" dialog surfaced from Settings -> About. The user types a short
 * description, then either emails it (with app/device context + the debug.log
 * attached) or opens a prefilled GitHub issue. All text formatting lives in the
 * testable [BugReportLogic] helpers; this Composable just collects input and fires
 * the intents.
 */
@Composable
fun BugReportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var description by remember { mutableStateOf("") }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedBorderColor = Accent,
        unfocusedBorderColor = BorderStrong,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextMuted,
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_report_bug),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = {
                    Text(stringResource(R.string.bug_report_description_hint), color = TextFaint)
                },
                singleLine = false,
                minLines = 3,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(onClick = {
                    uriHandler.openUri(buildGithubIssueUrl(description, gatherInfo()))
                    onDismiss()
                }) {
                    Text(stringResource(R.string.bug_report_open_github), color = Accent)
                }
                TextButton(onClick = {
                    sendBugReportEmail(context, description)
                    onDismiss()
                }) {
                    Text(
                        stringResource(R.string.bug_report_send_email),
                        color = Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Snapshot the current app/device/operator context into a [BugReportInfo]. */
private fun gatherInfo(): BugReportInfo = BugReportInfo(
    appVersion = GeneralVariables.VERSION,
    versionCode = GeneralVariables.VERSION_CODE,
    buildDate = GeneralVariables.BUILD_DATE,
    manufacturer = Build.MANUFACTURER ?: "",
    deviceModel = Build.MODEL ?: "",
    androidRelease = Build.VERSION.RELEASE ?: "",
    sdkInt = Build.VERSION.SDK_INT,
    callsign = GeneralVariables.myCallsign,
)

/**
 * Fire an email intent prefilled with the report body, addressed to the maintainer,
 * attaching debug.log when it exists. Mirrors the FileProvider/flags handling in
 * [DebugLogScreen.shareDebugLog].
 *
 * Uses an ACTION_SEND intent (so the debug.log attachment rides along via
 * EXTRA_STREAM) narrowed with a `mailto:` selector. The selector restricts the
 * candidate apps to email handlers, so the report — which carries the operator's
 * callsign and device info — can't be sent to an arbitrary share target like a
 * messaging or social app.
 */
private fun sendBugReportEmail(context: Context, description: String) {
    val info = gatherInfo()
    val logFile = context.getExternalFilesDir(null)?.let { File(it, "debug.log") }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        // Narrow the chooser to email apps only; a bare ACTION_SEND would let any
        // share target (messaging, social, etc.) receive the callsign/device block.
        selector = Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:") }
        putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.bug_report_email)))
        putExtra(Intent.EXTRA_SUBJECT, buildBugReportTitle(info))
        putExtra(Intent.EXTRA_TEXT, buildBugReportBody(description, info))
        if (logFile != null && logFile.exists()) {
            val uri = FileProvider.getUriForFile(
                context, "radio.ks3ckc.ft8af.fileprovider", logFile,
            )
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // A device with no mail handler (or all disabled) throws here; surface a
    // message instead of crashing out of Settings -> About.
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(
            context,
            context.getString(R.string.bug_report_no_email_app),
            Toast.LENGTH_LONG,
        ).show()
    }
}
