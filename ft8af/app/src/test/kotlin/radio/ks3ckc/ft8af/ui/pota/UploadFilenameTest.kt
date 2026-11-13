package radio.ks3ckc.ft8af.ui.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for [PotaAdifExporter.uploadFilename] — the POTA-convention
 * (`station_callsign@park_id-yyyymmdd.adi`) name used for both the in-app
 * upload and the share sheet. Pure string logic, no runner needed.
 */
class UploadFilenameTest {

    @Test
    fun plainCallsign_followsPotaConvention() {
        assertThat(PotaAdifExporter.uploadFilename("K1AF", "US-12398", "20260905"))
            .isEqualTo("K1AF@US-12398-20260905.adi")
    }

    @Test
    fun callsignIsUppercasedAndTrimmed() {
        assertThat(PotaAdifExporter.uploadFilename(" k1af ", "US-12398", "20260905"))
            .isEqualTo("K1AF@US-12398-20260905.adi")
    }

    @Test
    fun portableSuffixSlash_becomesDash_neverAPathSeparator() {
        assertThat(PotaAdifExporter.uploadFilename("K1AF/P", "US-12398", "20260905"))
            .isEqualTo("K1AF-P@US-12398-20260905.adi")
    }

    @Test
    fun leadingAndTrailingSeparators_areTrimmedAfterSanitizing() {
        // "/K1AF/" sanitizes to "-K1AF-", then trims to "K1AF".
        assertThat(PotaAdifExporter.uploadFilename("/K1AF/", "US-12398", "20260905"))
            .isEqualTo("K1AF@US-12398-20260905.adi")
    }

    @Test
    fun nullCallsign_fallsBackToLegacyName() {
        assertThat(PotaAdifExporter.uploadFilename(null, "US-12398", "20260905"))
            .isEqualTo("pota-US-12398-20260905.adi")
    }

    @Test
    fun blankCallsign_fallsBackToLegacyName() {
        assertThat(PotaAdifExporter.uploadFilename("  ", "US-12398", "20260905"))
            .isEqualTo("pota-US-12398-20260905.adi")
    }

    @Test
    fun allSeparatorCallsign_fallsBackToLegacyName() {
        // Sanitizes to nothing but dashes, which trim away entirely.
        assertThat(PotaAdifExporter.uploadFilename("//", "US-12398", "20260905"))
            .isEqualTo("pota-US-12398-20260905.adi")
    }
}
