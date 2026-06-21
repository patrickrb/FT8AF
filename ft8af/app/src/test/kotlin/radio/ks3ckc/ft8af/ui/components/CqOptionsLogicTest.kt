package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CqOptionsLogicTest {

    // ---- presetLabel ----

    @Test
    fun presetLabel_emptyModifier_returnsCQ() {
        assertThat(presetLabel("")).isEqualTo("CQ")
    }

    @Test
    fun presetLabel_dx_returnsCQDX() {
        assertThat(presetLabel("DX")).isEqualTo("CQ DX")
    }

    @Test
    fun presetLabel_pota_returnsCQPOTA() {
        assertThat(presetLabel("POTA")).isEqualTo("CQ POTA")
    }

    // ---- isValidModifier ----

    @Test
    fun isValidModifier_empty_valid() {
        assertThat(isValidModifier("")).isTrue()
    }

    @Test
    fun isValidModifier_dx_valid() {
        assertThat(isValidModifier("DX")).isTrue()
    }

    @Test
    fun isValidModifier_pota_valid() {
        assertThat(isValidModifier("POTA")).isTrue()
    }

    @Test
    fun isValidModifier_threeDigits_valid() {
        assertThat(isValidModifier("599")).isTrue()
    }

    @Test
    fun isValidModifier_fiveLetters_invalid() {
        assertThat(isValidModifier("ABCDE")).isFalse()
    }

    @Test
    fun isValidModifier_lowercase_invalid() {
        assertThat(isValidModifier("dx")).isFalse()
    }

    @Test
    fun isValidModifier_twoDigits_invalid() {
        assertThat(isValidModifier("12")).isFalse()
    }

    @Test
    fun isValidModifier_singleLetter_valid() {
        assertThat(isValidModifier("A")).isTrue()
    }

    // ---- isValidFreeText ----

    @Test
    fun isValidFreeText_allFt8Chars_valid() {
        assertThat(isValidFreeText("HELLO WORLD")).isTrue()
    }

    @Test
    fun isValidFreeText_numbersAndSymbols_valid() {
        assertThat(isValidFreeText("73 +-./?")).isTrue()
    }

    @Test
    fun isValidFreeText_exactlyThirteen_valid() {
        assertThat(isValidFreeText("1234567890ABC")).isTrue()
    }

    @Test
    fun isValidFreeText_overThirteen_invalid() {
        assertThat(isValidFreeText("12345678901234")).isFalse()
    }

    @Test
    fun isValidFreeText_lowercase_invalid() {
        assertThat(isValidFreeText("hello")).isFalse()
    }

    @Test
    fun isValidFreeText_invalidChars_invalid() {
        assertThat(isValidFreeText("@#\$")).isFalse()
    }

    @Test
    fun isValidFreeText_empty_valid() {
        assertThat(isValidFreeText("")).isTrue()
    }

    // ---- sanitizeModifier ----

    @Test
    fun sanitizeModifier_uppercases() {
        assertThat(sanitizeModifier("dx")).isEqualTo("DX")
    }

    @Test
    fun sanitizeModifier_stripsSpecialChars() {
        assertThat(sanitizeModifier("P@O#T$A")).isEqualTo("POTA")
    }

    @Test
    fun sanitizeModifier_truncatesTo4() {
        assertThat(sanitizeModifier("ABCDEFG")).isEqualTo("ABCD")
    }

    @Test
    fun sanitizeModifier_preservesDigits() {
        assertThat(sanitizeModifier("599")).isEqualTo("599")
    }

    @Test
    fun sanitizeModifier_empty_returnsEmpty() {
        assertThat(sanitizeModifier("")).isEqualTo("")
    }

    // ---- sanitizeFreeText ----

    @Test
    fun sanitizeFreeText_uppercases() {
        assertThat(sanitizeFreeText("hello")).isEqualTo("HELLO")
    }

    @Test
    fun sanitizeFreeText_keepsFt8Chars() {
        assertThat(sanitizeFreeText("73 +-./?")).isEqualTo("73 +-./?")
    }

    @Test
    fun sanitizeFreeText_stripsInvalidChars() {
        assertThat(sanitizeFreeText("HI@THERE#")).isEqualTo("HITHERE")
    }

    @Test
    fun sanitizeFreeText_truncatesTo13() {
        assertThat(sanitizeFreeText("ABCDEFGHIJKLMNOP")).isEqualTo("ABCDEFGHIJKLM")
    }

    @Test
    fun sanitizeFreeText_empty_returnsEmpty() {
        assertThat(sanitizeFreeText("")).isEqualTo("")
    }

    @Test
    fun sanitizeFreeText_preservesSpaces() {
        assertThat(sanitizeFreeText("A B C")).isEqualTo("A B C")
    }

    // ---- CQ_PRESETS ----

    @Test
    fun cqPresets_containsExpectedEntries() {
        assertThat(CQ_PRESETS).containsExactly("", "DX", "NA", "EU", "SA", "AS", "OC", "AF")
            .inOrder()
    }
}
