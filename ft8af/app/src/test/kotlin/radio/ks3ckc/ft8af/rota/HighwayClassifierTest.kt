package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Road-name canonicalization.
 *
 * The service groups these into a per-trip "highways traveled" list, so the real
 * requirement is that every spelling of one road collapses to one label — the
 * geocoder is free to say "I-70", "I 70 E" or "Interstate 70" for the same
 * asphalt, and a roll-up listing all three is noise.
 */
class HighwayClassifierTest {
    @Test
    fun `interstates collapse to one label however they are spelled`() {
        val expected = "I-70"
        assertThat(classifyHighway("I-70")).isEqualTo(expected)
        assertThat(classifyHighway("I 70")).isEqualTo(expected)
        assertThat(classifyHighway("Interstate 70")).isEqualTo(expected)
        assertThat(classifyHighway("I-70 E")).isEqualTo(expected)
        assertThat(classifyHighway("E I-70")).isEqualTo(expected)
        assertThat(classifyHighway("i-70")).isEqualTo(expected)
    }

    @Test
    fun `US routes collapse to one label`() {
        val expected = "US-285"
        assertThat(classifyHighway("US-285")).isEqualTo(expected)
        assertThat(classifyHighway("US 285")).isEqualTo(expected)
        assertThat(classifyHighway("U.S. 285")).isEqualTo(expected)
        assertThat(classifyHighway("US Highway 285")).isEqualTo(expected)
        assertThat(classifyHighway("US-285 S")).isEqualTo(expected)
        assertThat(classifyHighway("US Route 285")).isEqualTo(expected)
    }

    @Test
    fun `a state-prefixed route keeps its own state, not the rover's`() {
        // Near a border the geocoder is the better authority: it named the road.
        assertThat(classifyHighway("CO-93", stateCode = "CO")).isEqualTo("CO-93")
        assertThat(classifyHighway("WY-230", stateCode = "CO")).isEqualTo("WY-230")
        assertThat(classifyHighway("TX 130")).isEqualTo("TX-130")
    }

    @Test
    fun `an unprefixed state route borrows the state the rover is in`() {
        assertThat(classifyHighway("State Highway 7", stateCode = "CO")).isEqualTo("CO-7")
        assertThat(classifyHighway("SH 7", stateCode = "CO")).isEqualTo("CO-7")
        assertThat(classifyHighway("State Route 520", stateCode = "WA")).isEqualTo("WA-520")
    }

    @Test
    fun `an unprefixed state route with no known state stays generic-prefixed`() {
        // Better an honest "SR-7" than a guessed state the QSO wasn't in.
        assertThat(classifyHighway("State Highway 7", stateCode = null)).isEqualTo("SR-7")
        assertThat(classifyHighway("SR 520", stateCode = "ZZ")).isEqualTo("SR-520")
    }

    @Test
    fun `two-letter prefixes that are not states do not become state routes`() {
        // "CR 73" is a county road and "FM 1960" a Texas farm-to-market road; neither
        // is a state route, and neither may invent the state code "CR"/"FM".
        assertThat(classifyHighway("CR 73")).isEqualTo(LOCAL_ROAD_LABEL)
        assertThat(classifyHighway("County Road 73")).isEqualTo(LOCAL_ROAD_LABEL)
        assertThat(classifyHighway("FM 1960")).isEqualTo(LOCAL_ROAD_LABEL)
    }

    @Test
    fun `a state route is not mistaken for an interstate`() {
        // "IA-80" and "IN-65" begin with I but are Iowa and Indiana state routes; the
        // interstate pattern must not swallow them.
        assertThat(classifyHighway("IA-80")).isEqualTo("IA-80")
        assertThat(classifyHighway("IN-65")).isEqualTo("IN-65")
        assertThat(classifyHighway("HI-1")).isEqualTo("HI-1")
    }

    @Test
    fun `ordinary streets fall into the generic bucket`() {
        assertThat(classifyHighway("W Colfax Ave")).isEqualTo(LOCAL_ROAD_LABEL)
        assertThat(classifyHighway("Main Street")).isEqualTo(LOCAL_ROAD_LABEL)
        assertThat(classifyHighway("Pearl St")).isEqualTo(LOCAL_ROAD_LABEL)
    }

    @Test
    fun `an unresolved road is null, which is not the same as city driving`() {
        // The offline case. Claiming city driving across a dead zone would invent a
        // fact; null says only that nothing was resolved.
        assertThat(classifyHighway(null)).isNull()
        assertThat(classifyHighway("")).isNull()
        assertThat(classifyHighway("   ")).isNull()
    }

    @Test
    fun `the generic label is a fixed English constant, not a localized string`() {
        // It is grouped server-side; a localized spelling would split the bucket.
        assertThat(LOCAL_ROAD_LABEL).isEqualTo("Local roads")
    }
}
