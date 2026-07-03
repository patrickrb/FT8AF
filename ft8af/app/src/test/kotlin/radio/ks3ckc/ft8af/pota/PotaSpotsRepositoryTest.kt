package radio.ks3ckc.ft8af.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit coverage for [PotaSpotsRepository.parkRefFor], the synchronous lookup
 * helper used by the QSO save path and decode-row enrichment.
 *
 * The poller ([start]/[stop]) is networked and is left alone here; the spots
 * cache therefore stays at its empty default, which exercises every branch of
 * parkRefFor that does not require a populated cache:
 *   - a null callsign short-circuits to null, and
 *   - any callsign that is not in the cache resolves to null.
 *
 * No Android runtime is touched (parkRefFor only reads a StateFlow), so plain
 * JUnit suffices.
 */
class PotaSpotsRepositoryTest {

    @Test
    fun parkRefFor_nullCallsign_returnsNull() {
        assertThat(PotaSpotsRepository.parkRefFor(null)).isNull()
    }

    @Test
    fun parkRefFor_unknownCallsign_returnsNull() {
        assertThat(PotaSpotsRepository.parkRefFor("K1ABC")).isNull()
    }

    @Test
    fun parkRefFor_emptyCallsign_returnsNull() {
        assertThat(PotaSpotsRepository.parkRefFor("")).isNull()
    }

    @Test
    fun spotsByCall_defaultsToEmpty() {
        assertThat(PotaSpotsRepository.spotsByCall.value).isEmpty()
    }

    @Test
    fun spotLookupKey_stripsAngleBracketsFromHashedCallsigns() {
        assertThat(spotLookupKey("<K1ABC/P>")).isEqualTo("K1ABC/P")
    }

    @Test
    fun spotLookupKey_uppercasesPlainCallsigns() {
        assertThat(spotLookupKey("k1abc")).isEqualTo("K1ABC")
    }

    @Test
    fun spotLookupKey_nullAndBlankResolveToNull() {
        assertThat(spotLookupKey(null)).isNull()
        assertThat(spotLookupKey("")).isNull()
        assertThat(spotLookupKey("<>")).isNull()
    }

    @Test
    fun parkRefFor_bracketedCallsign_missesEmptyCacheWithoutThrowing() {
        assertThat(PotaSpotsRepository.parkRefFor("<K1ABC/P>")).isNull()
    }
}
