package radio.ks3ckc.ft8af.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow

/**
 * About / support settings: version info, FAQ/support link, bug reporting, and
 * the local debug.log — shared in one tap or viewed in [DebugLogScreen].
 */
@Composable
fun AboutSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var showAbout by remember { mutableStateOf(false) }
    var showDebugScreen by remember { mutableStateOf(false) }
    var showBugReport by remember { mutableStateOf(false) }

    // -- About / FAQ Dialog --
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    // -- Debug log viewer --
    if (showDebugScreen) {
        DebugLogScreen(onDismiss = { showDebugScreen = false })
    }

    // -- Report a bug --
    if (showBugReport) {
        BugReportDialog(onDismiss = { showBugReport = false })
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_about),
        onBack = onBack,
    ) {
        SettingsSection(title = stringResource(R.string.settings_section_about)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.app_name),
                        description = stringResource(
                            R.string.settings_build_date_format,
                            GeneralVariables.BUILD_DATE,
                        ),
                        value = stringResource(
                            R.string.settings_version_value,
                            GeneralVariables.VERSION,
                        ),
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_faq_support),
                        showChevron = true,
                        onClick = { showAbout = true },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_report_bug),
                        showChevron = true,
                        onClick = { showBugReport = true },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_share_logs),
                        description = stringResource(R.string.settings_share_logs_desc),
                        showChevron = true,
                        onClick = {
                            if (!shareDebugLog(context)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_share_logs_empty),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_view_logs),
                        description = stringResource(R.string.settings_debug_desc),
                        showChevron = true,
                        onClick = { showDebugScreen = true },
                    )
                }
            }
        }
    }
}

/**
 * About dialog with version info, credits, and tappable QRZ links for the authors.
 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "FT8AF",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            Text(
                text = stringResource(
                    R.string.settings_about_body,
                    GeneralVariables.VERSION,
                    GeneralVariables.VERSION_CODE,
                    GeneralVariables.BUILD_DATE,
                ),
                color = TextMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )

            Text(
                text = stringResource(R.string.settings_website),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = "ft8af.app",
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://ft8af.app") },
            )

            Text(
                text = stringResource(R.string.settings_community),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = "github.com/patrickrb/FT8AF",
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://github.com/patrickrb/FT8AF") },
            )
            Text(
                text = "discord.gg/UeE3ZpwRG",
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://discord.gg/UeE3ZpwRG") },
            )

            Text(
                text = stringResource(R.string.settings_built_by),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = stringResource(R.string.settings_author_k1af),
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://www.qrz.com/db/K1AF") },
            )
            Text(
                text = stringResource(R.string.settings_author_n0rc),
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://www.qrz.com/db/N0RC") },
            )

            // Font attribution — Inter is licensed under the SIL Open Font
            // License 1.1 (see app/licenses/OFL_Inter.txt).
            Text(
                text = stringResource(R.string.about_font_attribution),
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://github.com/rsms/inter") },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_ok), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
