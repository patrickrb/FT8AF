package radio.ks3ckc.ft8us.pota

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
}
