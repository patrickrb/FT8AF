package radio.ks3ckc.ft8af.ui.logbook

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [workedAllContinents] — the pure reducer behind the logbook
 * "Worked All Continents" (WAC) award card and Awards-tab progress bar. Runs on
 * the plain JVM: the reducer takes raw continent codes (as produced by
 * [com.k1af.ft8af.count.CountDbOpr.queryWorkedContinents]) and never touches
 * Android/DB types.
 */
class WorkedAllContinentsTest {

    @Test
    fun emptyInput_isZeroOfSix_notComplete() {
        val wac = workedAllContinents(emptyList())
        assertThat(wac.workedCount).isEqualTo(0)
        assertThat(wac.total).isEqualTo(6)
        assertThat(wac.isComplete).isFalse()
        // Always renders all six chips, in canonical order, none worked.
        assertThat(wac.chips.map { it.code }).containsExactlyElementsIn(WAC_CONTINENTS).inOrder()
        assertThat(wac.chips.none { it.worked }).isTrue()
    }

    @Test
    fun marksOnlyTheWorkedContinents() {
        val wac = workedAllContinents(listOf("NA", "EU", "AS"))
        assertThat(wac.workedCount).isEqualTo(3)
        assertThat(wac.isComplete).isFalse()
        val worked = wac.chips.filter { it.worked }.map { it.code }.toSet()
        assertThat(worked).containsExactly("NA", "EU", "AS")
    }

    @Test
    fun allSixWorked_isComplete() {
        val wac = workedAllContinents(listOf("NA", "SA", "EU", "AF", "AS", "OC"))
        assertThat(wac.workedCount).isEqualTo(6)
        assertThat(wac.isComplete).isTrue()
        assertThat(wac.chips.all { it.worked }).isTrue()
    }

    @Test
    fun duplicatesCollapse_countIsDistinct() {
        val wac = workedAllContinents(listOf("EU", "EU", "EU", "NA"))
        assertThat(wac.workedCount).isEqualTo(2)
    }

    @Test
    fun normalizesCaseAndWhitespace() {
        val wac = workedAllContinents(listOf(" na ", "eu", "As"))
        assertThat(wac.chips.filter { it.worked }.map { it.code }.toSet())
            .containsExactly("NA", "EU", "AS")
    }

    @Test
    fun antarcticaBlanksAndJunk_areIgnored() {
        // "AN" (Antarctica) is an endorsement, not one of the six required; "-",
        // blanks, nulls and unrelated tokens must never inflate the award.
        val wac = workedAllContinents(listOf("AN", "-", "", "  ", null, "ZZ", "NA"))
        assertThat(wac.workedCount).isEqualTo(1)
        assertThat(wac.chips.single { it.worked }.code).isEqualTo("NA")
        assertThat(wac.chips.map { it.code }).doesNotContain("AN")
    }
}
