package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Unit tests for the Cloudlog/Wavelog Station Location ID input helpers (#926). */
class StationLocationIdTest {
    @Test
    fun `digits and blank are valid`() {
        assertThat(isValidStationLocationId("")).isTrue()
        assertThat(isValidStationLocationId("0")).isTrue()
        assertThat(isValidStationLocationId("12")).isTrue()
        assertThat(isValidStationLocationId(" 7 ")).isTrue()
    }

    @Test
    fun `a callsign, sign, decimal or non-ASCII digit is invalid`() {
        assertThat(isValidStationLocationId("LB5AK")).isFalse()
        assertThat(isValidStationLocationId("-1")).isFalse()
        assertThat(isValidStationLocationId("1.5")).isFalse()
        assertThat(isValidStationLocationId("1 2")).isFalse()
        assertThat(isValidStationLocationId("١٢")).isFalse()
    }

    @Test
    fun `filter keeps only ASCII digits`() {
        assertThat(filterStationLocationId("LB5AK")).isEqualTo("5")
        assertThat(filterStationLocationId("-12.3")).isEqualTo("123")
        assertThat(filterStationLocationId("١2")).isEqualTo("2")
        assertThat(filterStationLocationId("abc")).isEmpty()
        assertThat(filterStationLocationId("42")).isEqualTo("42")
    }

    @Test
    fun `clean input passes through unchanged`() {
        val value = TextFieldValue("42", TextRange(1))
        assertThat(filterStationLocationIdInput(value)).isSameInstanceAs(value)
    }

    @Test
    fun `typed non-digit is dropped and the cursor stays put`() {
        // "12" with the cursor after "1", user types "x" -> "1x2", cursor after "x".
        val result = filterStationLocationIdInput(TextFieldValue("1x2", TextRange(2)))
        assertThat(result.text).isEqualTo("12")
        assertThat(result.selection).isEqualTo(TextRange(1))
    }

    @Test
    fun `pasted text keeps its digits with the cursor at the end`() {
        val result = filterStationLocationIdInput(TextFieldValue("ID 12\n", TextRange(6)))
        assertThat(result.text).isEqualTo("12")
        assertThat(result.selection).isEqualTo(TextRange(2))
    }
}
