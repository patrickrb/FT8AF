package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.theme.TextFaint
import radio.ks3ckc.ft8af.ui.components.GlassCard
import radio.ks3ckc.ft8af.ui.components.SettingsRow

/**
 * Voice-assistant settings: per-event spoken announcement toggles (TTS) and
 * the push-to-talk voice-command button toggle (STT). All persisted through
 * the SQLite config table (writeConfig) and hydrated in DatabaseOpr — same
 * pattern as the needed-DX alert toggles.
 */
@Composable
fun VoiceSettings(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
) {
    var announceCalling by remember { mutableStateOf(GeneralVariables.voiceAnnounceCalling) }
    var announceQso by remember { mutableStateOf(GeneralVariables.voiceAnnounceQsoComplete) }
    var announceNewDxcc by remember { mutableStateOf(GeneralVariables.voiceAnnounceNewDxcc) }
    var announceNewPrefix by remember { mutableStateOf(GeneralVariables.voiceAnnounceNewPrefix) }
    var commandsEnabled by remember { mutableStateOf(GeneralVariables.voiceCommandsEnabled) }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_voice),
        onBack = onBack,
    ) {
        // =====================================================================
        // SPOKEN ANNOUNCEMENTS (TTS)
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_section_voice_announce)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsRow(
                        label = stringResource(R.string.settings_voice_announce_calling),
                        description = stringResource(R.string.settings_voice_announce_calling_desc),
                        toggle = announceCalling,
                        onToggleChange = { checked ->
                            announceCalling = checked
                            GeneralVariables.voiceAnnounceCalling = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "voiceAnnounceCalling", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_voice_announce_qso),
                        description = stringResource(R.string.settings_voice_announce_qso_desc),
                        toggle = announceQso,
                        onToggleChange = { checked ->
                            announceQso = checked
                            GeneralVariables.voiceAnnounceQsoComplete = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "voiceAnnounceQsoComplete", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_voice_announce_new_dxcc),
                        description = stringResource(R.string.settings_voice_announce_new_dxcc_desc),
                        toggle = announceNewDxcc,
                        onToggleChange = { checked ->
                            announceNewDxcc = checked
                            GeneralVariables.voiceAnnounceNewDxcc = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "voiceAnnounceNewDxcc", if (checked) "1" else "0", null,
                            )
                        },
                    )
                    SectionDivider()
                    SettingsRow(
                        label = stringResource(R.string.settings_voice_announce_new_prefix),
                        description = stringResource(R.string.settings_voice_announce_new_prefix_desc),
                        toggle = announceNewPrefix,
                        onToggleChange = { checked ->
                            announceNewPrefix = checked
                            GeneralVariables.voiceAnnounceNewPrefix = checked
                            mainViewModel.databaseOpr.writeConfig(
                                "voiceAnnounceNewPrefix", if (checked) "1" else "0", null,
                            )
                        },
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_voice_tx_note),
                color = TextFaint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // =====================================================================
        // VOICE COMMANDS (STT)
        // =====================================================================
        SettingsSection(title = stringResource(R.string.settings_section_voice_commands)) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    label = stringResource(R.string.settings_voice_commands),
                    description = stringResource(R.string.settings_voice_commands_desc),
                    toggle = commandsEnabled,
                    onToggleChange = { checked ->
                        commandsEnabled = checked
                        GeneralVariables.voiceCommandsEnabled = checked
                        // The mic button on the (already-composed) main screen
                        // observes this mirror; without it the change only shows
                        // after an app restart.
                        GeneralVariables.mutableVoiceCommandsEnabled.value = checked
                        mainViewModel.databaseOpr.writeConfig(
                            "voiceCommandsEnabled", if (checked) "1" else "0", null,
                        )
                    },
                )
            }
        }
    }
}
