package radio.ks3ckc.ft8af.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which position gets stamped on a QSO.
 *
 * Two rules, and both are about not overstating what the app knows. Priority
 * beats freshness, because a live trip fix is a better account of the drive than
 * a last-known location of unknown provenance even when it is older. And when
 * every candidate is stale the answer is *nothing* — an unlocated QSO is honest,
 * and rtota.app places it from the breadcrumb trail on upload.
 */
class RoverPositionTest {
    private fun fix(source: RoverPositionSource, ageMs: Long) =
        RoverFix(39.7392, -104.9903, ageMs, source)

    @Test
    fun `a live trip fix wins when it is fresh`() {
        val chosen =
            chooseRoverFix(
                listOf(
                    fix(RoverPositionSource.LIVE_FIX, 5_000L),
                    fix(RoverPositionSource.LAST_KNOWN, 1_000L),
                ),
            )
        assertThat(chosen?.source).isEqualTo(RoverPositionSource.LIVE_FIX)
    }

    @Test
    fun `an older live fix still beats a newer last-known one`() {
        val chosen =
            chooseRoverFix(
                listOf(
                    fix(RoverPositionSource.LIVE_FIX, MAX_ROVER_FIX_AGE_MS - 1),
                    fix(RoverPositionSource.LAST_KNOWN, 0L),
                ),
            )
        assertThat(chosen?.source).isEqualTo(RoverPositionSource.LIVE_FIX)
    }

    @Test
    fun `a stale fix is skipped for the next usable candidate`() {
        // Yesterday's drive must not place today's contact.
        val chosen =
            chooseRoverFix(
                listOf(
                    fix(RoverPositionSource.LIVE_FIX, MAX_ROVER_FIX_AGE_MS + 1),
                    fix(RoverPositionSource.LAST_KNOWN, 60_000L),
                ),
            )
        assertThat(chosen?.source).isEqualTo(RoverPositionSource.LAST_KNOWN)
    }

    @Test
    fun `every stale candidate means no position at all, never an approximation`() {
        // No grid-centre tier exists on purpose: a 4-character grid is ~55 km across,
        // and its centre written into MY_LAT/MY_LON would read as a measurement.
        // rtota.app infers a position from the breadcrumb trail instead.
        val chosen =
            chooseRoverFix(
                listOf(
                    fix(RoverPositionSource.LIVE_FIX, MAX_ROVER_FIX_AGE_MS + 1),
                    fix(RoverPositionSource.LAST_KNOWN, MAX_ROVER_FIX_AGE_MS + 1),
                ),
            )
        assertThat(chosen).isNull()
    }

    @Test
    fun `absent candidates are skipped rather than crashing`() {
        val chosen = chooseRoverFix(listOf(null, fix(RoverPositionSource.LAST_KNOWN, 1_000L)))
        assertThat(chosen?.source).isEqualTo(RoverPositionSource.LAST_KNOWN)
    }

    @Test
    fun `nothing available yields null, so the QSO is logged without a position`() {
        assertThat(chooseRoverFix(emptyList())).isNull()
        assertThat(chooseRoverFix(listOf(null, null))).isNull()
        assertThat(
            chooseRoverFix(listOf(fix(RoverPositionSource.LAST_KNOWN, MAX_ROVER_FIX_AGE_MS + 1))),
        ).isNull()
    }
}
