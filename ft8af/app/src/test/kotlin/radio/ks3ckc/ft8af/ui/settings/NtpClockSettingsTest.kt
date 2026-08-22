package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R
import org.junit.Test

/**
 * Pure unit tests for the NTP clock-discipline settings helpers (no Android/Compose deps,
 * so no Robolectric runner needed).
 */
class NtpClockSettingsTest {

    @Test
    fun normalize_trimsWhitespace() {
        assertThat(normalizeNtpServer("  a.ntp.br  ")).isEqualTo("a.ntp.br")
    }

    @Test
    fun normalize_blankFallsBackToDefault() {
        assertThat(normalizeNtpServer("")).isEqualTo(GeneralVariables.DEFAULT_NTP_SERVER)
        assertThat(normalizeNtpServer("   ")).isEqualTo(GeneralVariables.DEFAULT_NTP_SERVER)
    }

    @Test
    fun normalize_passesThroughNonBlankUnchangedOnceTrimmed() {
        assertThat(normalizeNtpServer("pool.ntp.org")).isEqualTo("pool.ntp.org")
    }

    @Test
    fun commitValue_keepsCursorWhenTextIsAlreadyNormalized() {
        // Committing mid-edit (a focus change) must not move the caret the user placed.
        val editing = TextFieldValue("pool.ntp.org", TextRange(4))
        val committed = ntpServerCommitValue(editing)
        assertThat(committed.text).isEqualTo("pool.ntp.org")
        assertThat(committed.selection).isEqualTo(TextRange(4))
    }

    @Test
    fun commitValue_movesCursorToEndWhenNormalizationChangesText() {
        val committed = ntpServerCommitValue(TextFieldValue("  a.ntp.br  ", TextRange(3)))
        assertThat(committed.text).isEqualTo("a.ntp.br")
        assertThat(committed.selection).isEqualTo(TextRange("a.ntp.br".length))
    }

    @Test
    fun commitValue_blankFallsBackToDefaultWithCursorAtEnd() {
        val committed = ntpServerCommitValue(TextFieldValue("", TextRange(0)))
        assertThat(committed.text).isEqualTo(GeneralVariables.DEFAULT_NTP_SERVER)
        assertThat(committed.selection)
            .isEqualTo(TextRange(GeneralVariables.DEFAULT_NTP_SERVER.length))
    }

    @Test
    fun lockedMessage_gpsTakesPrecedenceWhenBothSomehowTrue() {
        assertThat(clockLockedMessageRes(disciplineFromGps = true, disciplineFromNtp = true))
            .isEqualTo(R.string.settings_time_correction_gps_locked)
    }

    @Test
    fun lockedMessage_gpsWhenOnlyGpsOn() {
        assertThat(clockLockedMessageRes(disciplineFromGps = true, disciplineFromNtp = false))
            .isEqualTo(R.string.settings_time_correction_gps_locked)
    }

    @Test
    fun lockedMessage_ntpWhenOnlyNtpOn() {
        assertThat(clockLockedMessageRes(disciplineFromGps = false, disciplineFromNtp = true))
            .isEqualTo(R.string.settings_time_correction_ntp_locked)
    }

    @Test
    fun lockedMessage_nullWhenNeitherOn() {
        assertThat(clockLockedMessageRes(disciplineFromGps = false, disciplineFromNtp = false)).isNull()
    }
}
