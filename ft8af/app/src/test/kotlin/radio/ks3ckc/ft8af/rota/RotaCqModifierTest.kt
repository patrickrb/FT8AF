package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The on-air token and the save/restore rules around it.
 *
 * The encodability check is the one that justifies the whole file: a token the
 * message formatter silently drops produces a bare "CQ <call> <grid>" that looks
 * completely normal in the log — an on-air failure nobody notices until the trip
 * is over. These assertions pin the token to something that actually encodes.
 */
class RotaCqModifierTest {
    @Test
    fun `the trip token is the program name and survives FT8 encoding`() {
        assertThat(ROTA_CQ_MODIFIER).isEqualTo("ROTA")
        assertThat(isEncodableCqModifier(ROTA_CQ_MODIFIER)).isTrue()
    }

    @Test
    fun `the pre-rename name is why the token used to be scrambled`() {
        // RTOTA is five letters and has no encoding in the 77-bit standard
        // message, so the token had to be the anagram RTOA. Renaming the program
        // to ROTA — four letters, like POTA — is what retired that workaround.
        assertThat(isEncodableCqModifier("RTOTA")).isFalse()
        assertThat(isEncodableCqModifier("RTOA")).isTrue()
    }

    @Test
    fun `encodable modifiers are one to four letters or three digits`() {
        assertThat(isEncodableCqModifier("P")).isTrue()
        assertThat(isEncodableCqModifier("POTA")).isTrue()
        assertThat(isEncodableCqModifier("DX")).isTrue()
        assertThat(isEncodableCqModifier("123")).isTrue()

        assertThat(isEncodableCqModifier("12")).isFalse()
        assertThat(isEncodableCqModifier("1234")).isFalse()
        assertThat(isEncodableCqModifier("POTAX")).isFalse()
        assertThat(isEncodableCqModifier("rtoa")).isFalse() // formatter matches uppercase only
        assertThat(isEncodableCqModifier("")).isFalse()
        assertThat(isEncodableCqModifier(null)).isFalse()
    }

    @Test
    fun `the operator's own modifier is remembered when a trip takes over`() {
        assertThat(modifierToRemember("NA")).isEqualTo("NA")
        assertThat(modifierToRemember("")).isEqualTo("")
        assertThat(modifierToRemember(null)).isEqualTo("")
    }

    @Test
    fun `re-applying the trip token does not make it its own predecessor`() {
        // The regression this guards: a restore that banked "ROTA" as the value to
        // restore would leave the road-trip token set forever after the trip ended.
        assertThat(modifierToRemember(ROTA_CQ_MODIFIER)).isEqualTo("")
    }

    @Test
    fun `ending a trip restores what the operator had before it`() {
        assertThat(modifierAfterTripEnd(ROTA_CQ_MODIFIER, "NA")).isEqualTo("NA")
        assertThat(modifierAfterTripEnd(ROTA_CQ_MODIFIER, "")).isEqualTo("")
    }

    @Test
    fun `ending a trip leaves a POTA activation's modifier alone`() {
        // A park activation started mid-trip owns the modifier now. Restoring over
        // it would put the operator back on a plain CQ mid-activation, and POTA's
        // own end would then hand "ROTA" back for a trip that no longer exists.
        assertThat(modifierAfterTripEnd("POTA", "NA")).isNull()
        assertThat(modifierAfterTripEnd("", "NA")).isNull()
        assertThat(modifierAfterTripEnd(null, "NA")).isNull()
    }
}
