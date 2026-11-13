package radio.ks3ckc.ft8af.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.pota.model.PotaQso

/**
 * The POTA dupe rule (issue #823): the same station worked again on the same band
 * within an activation doesn't count, no matter the mode; the same station on a
 * different band does. The chronologically-first contact per (call, band) is the
 * one that counts. Must mirror DatabaseOpr.recountActivationQsos' SQL key.
 */
class PotaDupesTest {

    private fun qso(
        id: Long,
        call: String,
        band: String,
        mode: String = "FT8",
        date: String = "20260918",
        time: String = "120000",
    ) = PotaQso(
        id = id,
        callsign = call,
        grid = "FN31",
        band = band,
        mode = mode,
        rstSent = "-05",
        rstRcvd = "-10",
        qsoDate = date,
        timeOn = time,
        sig = null,
        sigInfo = null,
    )

    @Test
    fun dupeKey_normalizesCaseAndWhitespace() {
        assertThat(potaDupeKey(" w1aw ", "20M")).isEqualTo(potaDupeKey("W1AW", " 20m"))
        assertThat(potaDupeKey("W1AW", "20m")).isNotEqualTo(potaDupeKey("W1AW", "40m"))
    }

    @Test
    fun sameCallSameBand_laterQsoIsTheDupe_evenNewestFirst() {
        // DAO order: newest first. The 12:00 contact counts; 12:15 is the dupe.
        val qsos = listOf(
            qso(id = 2, call = "W1AW", band = "20m", time = "121500"),
            qso(id = 1, call = "W1AW", band = "20m", time = "120000"),
        )

        assertThat(potaDupeQsoIds(qsos)).containsExactly(2L)
    }

    @Test
    fun sameCallDifferentBand_isNotADupe() {
        val qsos = listOf(
            qso(id = 2, call = "W1AW", band = "40m", time = "121500"),
            qso(id = 1, call = "W1AW", band = "20m", time = "120000"),
        )

        assertThat(potaDupeQsoIds(qsos)).isEmpty()
    }

    @Test
    fun sameCallSameBandDifferentMode_isStillADupe() {
        // FT8 then FT4 on the same band: POTA doesn't split dupes by mode.
        val qsos = listOf(
            qso(id = 2, call = "W1AW", band = "20m", mode = "FT4", time = "121500"),
            qso(id = 1, call = "W1AW", band = "20m", mode = "FT8", time = "120000"),
        )

        assertThat(potaDupeQsoIds(qsos)).containsExactly(2L)
    }

    @Test
    fun caseAndWhitespaceVariants_collapseToOneCredit() {
        val qsos = listOf(
            qso(id = 3, call = "w1aw", band = "20M", time = "123000"),
            qso(id = 2, call = " W1AW", band = "20m ", time = "121500"),
            qso(id = 1, call = "W1AW", band = "20m", time = "120000"),
        )

        assertThat(potaDupeQsoIds(qsos)).containsExactly(2L, 3L)
    }

    @Test
    fun oddWidthTimeOn_ordersAsDroppedLeadingZero() {
        // "815" is an imported row's 08:15 with the leading zero dropped: it must
        // sort before 09:00, so it takes the credit and the 09:00 row is the dupe.
        val qsos = listOf(
            qso(id = 1, call = "W1AW", band = "20m", time = "90000"),
            qso(id = 2, call = "W1AW", band = "20m", time = "815"),
        )

        assertThat(normalizedQsoStamp("20260918", "815")).isEqualTo("20260918081500")
        assertThat(potaDupeQsoIds(qsos)).containsExactly(1L)
    }

    @Test
    fun sameStamp_tieBreaksByInsertionOrder() {
        val qsos = listOf(
            qso(id = 9, call = "W1AW", band = "20m"),
            qso(id = 4, call = "W1AW", band = "20m"),
        )

        assertThat(potaDupeQsoIds(qsos)).containsExactly(9L)
    }

    @Test
    fun emptyAndAllUniqueLists_produceNoDupes() {
        assertThat(potaDupeQsoIds(emptyList())).isEmpty()
        val qsos = listOf(
            qso(id = 1, call = "W1AW", band = "20m"),
            qso(id = 2, call = "K2XYZ", band = "20m"),
        )
        assertThat(potaDupeQsoIds(qsos)).isEmpty()
    }
}
