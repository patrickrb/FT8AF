package radio.ks3ckc.ft8af.rtota

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.util.Locale

/**
 * Callsign normalization has to be locale-independent.
 *
 * The bug this guards is invisible on an English phone: `String.uppercase()`
 * without a locale uses the default one, and Turkish/Azeri map ASCII "i" to "İ"
 * (U+0130) rather than "I". An operator in that locale would store and register
 * a callsign the server can never match, while the rest of the feature
 * normalizes with `Locale.US` and disagrees with the setting that produced it.
 */
class RtotaCallsignTest {
    private val original: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `callsigns upper-case identically under a Turkish locale`() {
        Locale.setDefault(Locale("tr", "TR"))
        // The dotted capital İ is what the default-locale uppercase() would give.
        assertThat(normalizeCallsign("ki7abc")).isEqualTo("KI7ABC")
        assertThat(normalizeCallsign("ki7abc")).doesNotContain("İ")
    }

    @Test
    fun `the same input gives the same answer in every locale`() {
        val locales = listOf(Locale.US, Locale("tr", "TR"), Locale("az", "AZ"), Locale.GERMANY)
        val results =
            locales.map {
                Locale.setDefault(it)
                normalizeCallsign(" ki7abc ")
            }
        assertThat(results.toSet()).containsExactly("KI7ABC")
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        Locale.setDefault(Locale.US)
        assertThat(normalizeCallsign("  k1abc\t")).isEqualTo("K1ABC")
        assertThat(normalizeCallsign("")).isEmpty()
    }

    @Test
    fun `a portable suffix survives normalization`() {
        Locale.setDefault(Locale.US)
        assertThat(normalizeCallsign("k1abc/m")).isEqualTo("K1ABC/M")
    }
}
