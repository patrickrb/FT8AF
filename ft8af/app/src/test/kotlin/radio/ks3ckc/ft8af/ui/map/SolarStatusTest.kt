package radio.ks3ckc.ft8af.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Pure unit tests for the per-station gray-line / sun-status helper. No Android
 * or Compose runtime is touched, so these run as plain JVM tests.
 */
class SolarStatusTest {

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    // -----------------------------------------------------------------------
    // Day / night classification
    // -----------------------------------------------------------------------

    @Test
    fun localSolarNoon_isDaytime() {
        // Prime meridian at 12:00 UTC near an equinox: the Sun is high overhead.
        val s = solarSnapshot(lat = 0.0, lon = 0.0, utcMillis = utc(2024, Calendar.MARCH, 20, 12))
        assertThat(s.isDay).isTrue()
        assertThat(s.elevationDeg).isGreaterThan(60.0)
        assertThat(s.onGrayLine).isFalse()
    }

    @Test
    fun localMidnight_isNight() {
        // Prime meridian at 00:00 UTC: the Sun is on the far side of the Earth.
        val s = solarSnapshot(lat = 0.0, lon = 0.0, utcMillis = utc(2024, Calendar.MARCH, 20, 0))
        assertThat(s.isDay).isFalse()
        assertThat(s.elevationDeg).isLessThan(-60.0)
        assertThat(s.onGrayLine).isFalse()
    }

    // -----------------------------------------------------------------------
    // Next sunrise / sunset
    // -----------------------------------------------------------------------

    @Test
    fun daytime_nextEventIsSunset() {
        val s = solarSnapshot(lat = 0.0, lon = 0.0, utcMillis = utc(2024, Calendar.MARCH, 20, 12))
        assertThat(s.nextKind).isEqualTo(SolarEventKind.SUNSET)
        // At the equator on an equinox, sunset is ~6 h after local noon.
        assertThat(s.minutesToNext).isWithin(30).of(6 * 60)
    }

    @Test
    fun night_nextEventIsSunrise() {
        val s = solarSnapshot(lat = 0.0, lon = 0.0, utcMillis = utc(2024, Calendar.MARCH, 20, 3))
        assertThat(s.nextKind).isEqualTo(SolarEventKind.SUNRISE)
        // 03:00 UTC on the prime meridian is a few hours before ~06:00 sunrise.
        assertThat(s.minutesToNext).isWithin(30).of(3 * 60)
    }

    @Test
    fun sunriseCrossing_flipsToDaytime() {
        // Just before sunrise it is night; the very next event is a sunrise, and
        // an hour later the same point reads as daytime.
        val beforeDawn = solarSnapshot(lat = 0.0, lon = 0.0, utcMillis = utc(2024, Calendar.MARCH, 20, 5))
        assertThat(beforeDawn.isDay).isFalse()
        assertThat(beforeDawn.nextKind).isEqualTo(SolarEventKind.SUNRISE)

        val afterDawn = solarSnapshot(lat = 0.0, lon = 0.0, utcMillis = utc(2024, Calendar.MARCH, 20, 7))
        assertThat(afterDawn.isDay).isTrue()
    }

    // -----------------------------------------------------------------------
    // Gray line
    // -----------------------------------------------------------------------

    @Test
    fun nearSunrise_isOnGrayLine() {
        // Sweep the dawn hour and confirm the point passes through the gray-line
        // band (|elevation| within the half-width) as the Sun crosses the horizon.
        var sawGrayLine = false
        for (minute in 0 until 120 step 4) {
            val s = solarSnapshot(
                lat = 0.0,
                lon = 0.0,
                utcMillis = utc(2024, Calendar.MARCH, 20, 5, minute % 60) +
                    (minute / 60) * 3600_000L,
            )
            if (s.onGrayLine) sawGrayLine = true
        }
        assertThat(sawGrayLine).isTrue()
    }

    @Test
    fun grayLineWidth_isConfigurable() {
        // A point 4° below the horizon is on the gray line at the default 6°
        // half-width but not at a tight 2° one.
        // Find an instant where the equatorial Sun sits a few degrees down.
        var base = utc(2024, Calendar.MARCH, 20, 5, 30)
        // Nudge forward until elevation lands in (-5, -3).
        var chosen = -1L
        var m = 0
        while (m < 120) {
            val t = base + m * 60_000L
            val elev = solarSnapshot(0.0, 0.0, t).elevationDeg
            if (elev in -5.0..-3.0) {
                chosen = t
                break
            }
            m++
        }
        assertThat(chosen).isGreaterThan(0L)
        assertThat(solarSnapshot(0.0, 0.0, chosen, grayLineHalfWidthDeg = 6.0).onGrayLine).isTrue()
        assertThat(solarSnapshot(0.0, 0.0, chosen, grayLineHalfWidthDeg = 2.0).onGrayLine).isFalse()
    }

    // -----------------------------------------------------------------------
    // Polar day / night
    // -----------------------------------------------------------------------

    @Test
    fun arcticSummer_hasNoSunsetWithinScan() {
        // North Pole at the June solstice: midnight sun — the Sun never sets, so
        // there is no next crossing.
        val s = solarSnapshot(lat = 89.0, lon = 0.0, utcMillis = utc(2024, Calendar.JUNE, 21, 12))
        assertThat(s.isDay).isTrue()
        assertThat(s.nextKind).isNull()
        assertThat(s.minutesToNext).isEqualTo(0)
    }

    @Test
    fun arcticWinter_hasNoSunriseWithinScan() {
        // North Pole at the December solstice: polar night — the Sun never rises.
        val s = solarSnapshot(lat = 89.0, lon = 0.0, utcMillis = utc(2024, Calendar.DECEMBER, 21, 12))
        assertThat(s.isDay).isFalse()
        assertThat(s.nextKind).isNull()
    }

    // -----------------------------------------------------------------------
    // Countdown formatting
    // -----------------------------------------------------------------------

    @Test
    fun countdown_formatsHoursAndMinutes() {
        assertThat(formatSolarCountdown(80)).isEqualTo("1h 20m")
        assertThat(formatSolarCountdown(45)).isEqualTo("45m")
        assertThat(formatSolarCountdown(120)).isEqualTo("2h")
        assertThat(formatSolarCountdown(60)).isEqualTo("1h")
    }

    @Test
    fun countdown_zeroOrNegative_isNow() {
        assertThat(formatSolarCountdown(0)).isEqualTo("now")
        assertThat(formatSolarCountdown(-5)).isEqualTo("now")
    }

    // -----------------------------------------------------------------------
    // Display tokens
    // -----------------------------------------------------------------------

    @Test
    fun display_daytime_showsDaylightAndSunset() {
        val d = grayLineDisplay(
            SolarSnapshot(
                elevationDeg = 40.0, isDay = true, onGrayLine = false,
                nextKind = SolarEventKind.SUNSET, minutesToNext = 80,
            ),
        )
        assertThat(d.phase).isEqualTo(GrayLinePhase.DAYLIGHT)
        assertThat(d.detail).isEqualTo(GrayLineDetail.SUNSET)
        assertThat(d.countdown).isEqualTo("1h 20m")
    }

    @Test
    fun display_night_showsNightAndSunrise() {
        val d = grayLineDisplay(
            SolarSnapshot(
                elevationDeg = -30.0, isDay = false, onGrayLine = false,
                nextKind = SolarEventKind.SUNRISE, minutesToNext = 45,
            ),
        )
        assertThat(d.phase).isEqualTo(GrayLinePhase.NIGHT)
        assertThat(d.detail).isEqualTo(GrayLineDetail.SUNRISE)
        assertThat(d.countdown).isEqualTo("45m")
    }

    @Test
    fun display_onGrayLine_overridesDayNightPhase() {
        val d = grayLineDisplay(
            SolarSnapshot(
                elevationDeg = 2.0, isDay = true, onGrayLine = true,
                nextKind = SolarEventKind.SUNSET, minutesToNext = 12,
            ),
        )
        assertThat(d.phase).isEqualTo(GrayLinePhase.GRAY_LINE)
        assertThat(d.detail).isEqualTo(GrayLineDetail.SUNSET)
    }

    @Test
    fun display_polarDay_showsMidnightSunWithNoCountdown() {
        val d = grayLineDisplay(
            SolarSnapshot(
                elevationDeg = 15.0, isDay = true, onGrayLine = false,
                nextKind = null, minutesToNext = 0,
            ),
        )
        assertThat(d.phase).isEqualTo(GrayLinePhase.DAYLIGHT)
        assertThat(d.detail).isEqualTo(GrayLineDetail.MIDNIGHT_SUN)
        assertThat(d.countdown).isEmpty()
    }

    @Test
    fun display_polarNight_showsPolarNightWithNoCountdown() {
        val d = grayLineDisplay(
            SolarSnapshot(
                elevationDeg = -10.0, isDay = false, onGrayLine = false,
                nextKind = null, minutesToNext = 0,
            ),
        )
        assertThat(d.phase).isEqualTo(GrayLinePhase.NIGHT)
        assertThat(d.detail).isEqualTo(GrayLineDetail.POLAR_NIGHT)
        assertThat(d.countdown).isEmpty()
    }
}
