package radio.ks3ckc.ft8af.ui.settings

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
        assertThat(normalizeNtpServer("time.nist.gov")).isEqualTo("time.nist.gov")
    }

    @Test
    fun defaultServer_isTheWorldwidePool() {
        // Not a national server: the app ships everywhere, and pool.ntp.org resolves to
        // servers near the device. Regional pools stay one text field away.
        assertThat(GeneralVariables.DEFAULT_NTP_SERVER).isEqualTo("pool.ntp.org")
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
