package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Abandoning a trip must still complete the server row when one exists —
 * discard used to be local-only, which left the trip `active` on
 * roadsontheair.com forever, with no client able to end it.
 */
class RotaAbandonTest {
    @Test
    fun `a created trip gets its server row completed`() {
        assertThat(shouldCompleteAbandonedTrip("trip-123", false, "rota_key")).isTrue()
    }

    @Test
    fun `a trip still pending creation has no row to complete`() {
        assertThat(shouldCompleteAbandonedTrip("", true, "rota_key")).isFalse()
        // Belt and braces: even if an id were somehow present mid-create, the
        // row the id names is not confirmed to exist yet.
        assertThat(shouldCompleteAbandonedTrip("trip-123", true, "rota_key")).isFalse()
    }

    @Test
    fun `no id or no key means no doomed network attempt`() {
        assertThat(shouldCompleteAbandonedTrip("", false, "rota_key")).isFalse()
        assertThat(shouldCompleteAbandonedTrip("trip-123", false, "")).isFalse()
        assertThat(shouldCompleteAbandonedTrip("trip-123", false, "   ")).isFalse()
    }
}
