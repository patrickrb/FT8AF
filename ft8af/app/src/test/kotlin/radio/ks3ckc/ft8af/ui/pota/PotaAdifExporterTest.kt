package radio.ks3ckc.ft8af.ui.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for PotaAdifExporter.adifField — the ADIF tag encoder. The
 * surrounding shareActivationAdif() is DB/Intent-bound and not unit-testable,
 * but the field encoder carries the only real logic (UTF-8 byte-length tagging)
 * and is exposed as internal/@VisibleForTesting.
 */
class PotaAdifExporterTest {

    @Test
    fun adifField_encodesNameLengthAndValue() {
        val sb = StringBuilder()
        PotaAdifExporter.adifField(sb, "CALL", "K1ABC")
        // <NAME:byteLength>VALUE followed by a trailing space.
        assertThat(sb.toString()).isEqualTo("<CALL:5>K1ABC ")
    }

    @Test
    fun adifField_lengthIsByteCountNotCharCount() {
        val sb = StringBuilder()
        // "é" is one UTF-16 char but two UTF-8 bytes; the tag must report bytes.
        PotaAdifExporter.adifField(sb, "NAME", "é")
        assertThat(sb.toString()).isEqualTo("<NAME:2>é ")
    }

    @Test
    fun adifField_nullValue_appendsNothing() {
        val sb = StringBuilder()
        PotaAdifExporter.adifField(sb, "GRIDSQUARE", null)
        assertThat(sb.toString()).isEmpty()
    }

    @Test
    fun adifField_emptyValue_appendsNothing() {
        val sb = StringBuilder()
        PotaAdifExporter.adifField(sb, "GRIDSQUARE", "")
        assertThat(sb.toString()).isEmpty()
    }

    @Test
    fun adifField_appendsSequentiallyForMultipleFields() {
        val sb = StringBuilder()
        PotaAdifExporter.adifField(sb, "CALL", "W1AW")
        PotaAdifExporter.adifField(sb, "MODE", "FT8")
        assertThat(sb.toString()).isEqualTo("<CALL:4>W1AW <MODE:3>FT8 ")
    }

    @Test
    fun adifMode_ft8PassesThroughAsMode() {
        val sb = StringBuilder()
        PotaAdifExporter.adifMode(sb, "FT8")
        assertThat(sb.toString()).isEqualTo("<MODE:3>FT8 ")
    }

    @Test
    fun adifMode_ft2EmitsMfskModeWithSubmode() {
        // The reported bug: a bare <MODE:3>FT2 is rejected by pota.app. FT2 is a
        // submode of MFSK, so we must emit MODE=MFSK + SUBMODE=FT2.
        val sb = StringBuilder()
        PotaAdifExporter.adifMode(sb, "FT2")
        assertThat(sb.toString()).isEqualTo("<MODE:4>MFSK <SUBMODE:3>FT2 ")
    }

    @Test
    fun adifMode_ft4EmitsMfskModeWithSubmode() {
        val sb = StringBuilder()
        PotaAdifExporter.adifMode(sb, "FT4")
        assertThat(sb.toString()).isEqualTo("<MODE:4>MFSK <SUBMODE:3>FT4 ")
    }

    @Test
    fun adifMode_nullEmitsNothing() {
        val sb = StringBuilder()
        PotaAdifExporter.adifMode(sb, null)
        assertThat(sb.toString()).isEmpty()
    }
}
