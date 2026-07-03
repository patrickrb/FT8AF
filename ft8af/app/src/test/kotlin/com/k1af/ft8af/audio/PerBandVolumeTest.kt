package com.k1af.ft8af.audio

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the per-band output level logic (issue #355). These touch org.json (an
 * Android type), so they run under Robolectric per the project's testing convention.
 */
@RunWith(RobolectricTestRunner::class)
class PerBandVolumeTest {

    // -- parse --

    @Test
    fun `parse null yields empty map`() {
        assertThat(PerBandVolume.parse(null)).isEmpty()
    }

    @Test
    fun `parse blank yields empty map`() {
        assertThat(PerBandVolume.parse("   ")).isEmpty()
    }

    @Test
    fun `parse malformed json yields empty map`() {
        assertThat(PerBandVolume.parse("{not valid")).isEmpty()
    }

    @Test
    fun `parse reads band to percent entries`() {
        val levels = PerBandVolume.parse("""{"20m":80,"40m":65}""")
        assertThat(levels).containsExactly("20m", 80, "40m", 65)
    }

    @Test
    fun `parse skips non-numeric values`() {
        val levels = PerBandVolume.parse("""{"20m":80,"40m":"loud"}""")
        assertThat(levels).containsExactly("20m", 80)
    }

    @Test
    fun `parse clamps out-of-range percentages`() {
        val levels = PerBandVolume.parse("""{"20m":150,"40m":-10}""")
        assertThat(levels).containsExactly("20m", 100, "40m", 0)
    }

    // -- serialize --

    @Test
    fun `serialize round-trips through parse`() {
        val original = mapOf("20m" to 80, "40m" to 65, "80m" to 50)
        val json = PerBandVolume.serialize(original)
        assertThat(PerBandVolume.parse(json)).containsExactlyEntriesIn(original)
    }

    @Test
    fun `serialize sorts keys deterministically`() {
        val json = PerBandVolume.serialize(mapOf("80m" to 50, "20m" to 80, "40m" to 65))
        // TreeMap ordering: keys are emitted sorted, so the output is stable.
        assertThat(json).isEqualTo("""{"20m":80,"40m":65,"80m":50}""")
    }

    @Test
    fun `serialize empty map is empty object`() {
        assertThat(PerBandVolume.serialize(emptyMap())).isEqualTo("{}")
    }

    @Test
    fun `serialize skips blank band keys`() {
        val json = PerBandVolume.serialize(mapOf("" to 80, "20m" to 65))
        assertThat(JSONObject(json).has("")).isFalse()
        assertThat(JSONObject(json).getInt("20m")).isEqualTo(65)
    }

    // -- lookup --

    @Test
    fun `lookup returns saved value`() {
        val levels = mapOf("20m" to 80)
        assertThat(PerBandVolume.lookup(levels, "20m")).isEqualTo(80)
    }

    @Test
    fun `lookup returns null when band absent`() {
        assertThat(PerBandVolume.lookup(mapOf("20m" to 80), "40m")).isNull()
    }

    @Test
    fun `lookup returns null for blank band`() {
        assertThat(PerBandVolume.lookup(mapOf("20m" to 80), "")).isNull()
        assertThat(PerBandVolume.lookup(mapOf("20m" to 80), null)).isNull()
    }

    // -- put --

    @Test
    fun `put adds a new band without mutating the input`() {
        val original = mapOf("20m" to 80)
        val updated = PerBandVolume.put(original, "40m", 65)
        assertThat(updated).containsExactly("20m", 80, "40m", 65)
        assertThat(original).containsExactly("20m", 80) // unchanged
    }

    @Test
    fun `put overwrites an existing band`() {
        val updated = PerBandVolume.put(mapOf("20m" to 80), "20m", 55)
        assertThat(updated).containsExactly("20m", 55)
    }

    @Test
    fun `put clamps the percent`() {
        assertThat(PerBandVolume.put(emptyMap(), "20m", 250)).containsExactly("20m", 100)
        assertThat(PerBandVolume.put(emptyMap(), "20m", -5)).containsExactly("20m", 0)
    }

    @Test
    fun `put ignores a blank band`() {
        assertThat(PerBandVolume.put(mapOf("20m" to 80), "", 65)).containsExactly("20m", 80)
        assertThat(PerBandVolume.put(mapOf("20m" to 80), null, 65)).containsExactly("20m", 80)
    }
}
