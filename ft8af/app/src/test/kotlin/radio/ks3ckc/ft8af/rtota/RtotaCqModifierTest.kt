package radio.ks3ckc.ft8af.rtota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The on-air token and the save/restore rules around it.
 *
 * The encodability check is the one that justifies the whole file: "RTOTA" is
 * the name of the program but not a legal FT8 CQ modifier, and a token the
 * message formatter silently drops produces a bare "CQ <call> <grid>" that looks
 * completely normal in the log — an on-air failure nobody notices until the
 * trip is over. These assertions pin the difference.
 */
class RtotaCqModifierTest {
    @Test
    fun `the trip token is four letters so it survives FT8 encoding`() {
        assertThat(RTOTA_CQ_MODIFIER).isEqualTo("RTOA")
        assertThat(isEncodableCqModifier(RTOTA_CQ_MODIFIER)).isTrue()
    }

    @Test
    fun `RTOTA itself cannot be encoded`() {
        // Five letters — no encoding exists in the 77-bit standard message, which
        // is exactly why the token above is not simply the program's name.
        assertThat(isEncodableCqModifier("RTOTA")).isFalse()
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
        // The regression this guards: a restore that banked "RTOA" as the value to
        // restore would leave the road-trip token set forever after the trip ended.
        assertThat(modifierToRemember(RTOTA_CQ_MODIFIER)).isEqualTo("")
    }

    @Test
    fun `ending a trip restores what the operator had before it`() {
        assertThat(modifierAfterTripEnd(RTOTA_CQ_MODIFIER, "NA")).isEqualTo("NA")
        assertThat(modifierAfterTripEnd(RTOTA_CQ_MODIFIER, "")).isEqualTo("")
    }

    @Test
    fun `ending a trip leaves a POTA activation's modifier alone`() {
        // A park activation started mid-trip owns the modifier now. Restoring over
        // it would put the operator back on a plain CQ mid-activation, and POTA's
        // own end would then hand "RTOA" back for a trip that no longer exists.
        assertThat(modifierAfterTripEnd("POTA", "NA")).isNull()
        assertThat(modifierAfterTripEnd("", "NA")).isNull()
        assertThat(modifierAfterTripEnd(null, "NA")).isNull()
    }
}
