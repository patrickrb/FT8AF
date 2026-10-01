package radio.ks3ckc.ft8af.bandadvisor.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ValidationTest {

    @Test
    fun `normalizeAdvisorGrid reduces to 4-char square`() {
        assertThat(normalizeAdvisorGrid("EM28")).isEqualTo("EM28")
        assertThat(normalizeAdvisorGrid("em28ax")).isEqualTo("EM28")
        assertThat(normalizeAdvisorGrid(" FN42 ")).isEqualTo("FN42")
        assertThat(normalizeAdvisorGrid("jo01ab")).isEqualTo("JO01")
    }

    @Test
    fun `normalizeAdvisorGrid rejects invalid grids`() {
        assertThat(normalizeAdvisorGrid(null)).isNull()
        assertThat(normalizeAdvisorGrid("")).isNull()
        assertThat(normalizeAdvisorGrid("EM")).isNull()
        assertThat(normalizeAdvisorGrid("E128")).isNull() // digit in field letters
        assertThat(normalizeAdvisorGrid("EMXX")).isNull() // letters in square digits
        assertThat(normalizeAdvisorGrid("ZZ99")).isNull() // S > R field letter
        assertThat(normalizeAdvisorGrid("RR73")).isEqualTo("RR73") // valid square; caller context decides
    }

    @Test
    fun `plausible callsigns accepted`() {
        for (call in listOf("K1AF", "W1AW", "VE3ABC", "9A1AA", "DL1ABC", "G4XYZ/P", "VP2E/K1AF", "kd9xyz")) {
            assertThat(isPlausibleCallsign(call)).isTrue()
        }
    }

    @Test
    fun `garbage callsigns rejected`() {
        for (call in listOf(null, "", " ", "AB", "NOCALL", "EM28", "1234567", "CQ", "THIRTEENCHARS")) {
            assertThat(isPlausibleCallsign(call)).isFalse()
        }
    }
}
