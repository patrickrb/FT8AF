package radio.ks3ckc.ft8af.flags

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for org.json (a stub in plain JVM unit tests). */
@RunWith(RobolectricTestRunner::class)
class RemoteFlagConfigTest {

    private val valid = """
        {
          "version": 1,
          "generatedAt": "2026-09-27T12:00:00Z",
          "expiresAt": "2026-09-27T18:00:00Z",
          "flags": {
            "bandAdvisor": true,
            "personalPskAnalytics": false,
            "propagationAlerts": true
          }
        }
    """.trimIndent()

    @Test
    fun `parses a valid payload`() {
        val config = parseRemoteFlagConfig(valid)!!
        assertThat(config.version).isEqualTo(1)
        assertThat(config.valueFor(FeatureFlag.BAND_ADVISOR)).isTrue()
        assertThat(config.valueFor(FeatureFlag.PERSONAL_PSK_ANALYTICS)).isFalse()
        assertThat(config.expiresAtMs - config.generatedAtMs).isEqualTo(6L * 3600 * 1000)
    }

    @Test
    fun `freshness follows expiresAt`() {
        val config = parseRemoteFlagConfig(valid)!!
        assertThat(config.isFresh(config.expiresAtMs - 1)).isTrue()
        assertThat(config.isFresh(config.expiresAtMs)).isFalse()
    }

    @Test
    fun `rejects malformed json`() {
        assertThat(parseRemoteFlagConfig("not json")).isNull()
        assertThat(parseRemoteFlagConfig("")).isNull()
        assertThat(parseRemoteFlagConfig("[]")).isNull()
    }

    @Test
    fun `rejects missing or unsupported version`() {
        assertThat(parseRemoteFlagConfig(valid.replace("\"version\": 1", "\"version\": 99"))).isNull()
        assertThat(parseRemoteFlagConfig(valid.replace("\"version\": 1,", ""))).isNull()
        assertThat(parseRemoteFlagConfig(valid.replace("\"version\": 1", "\"version\": 0"))).isNull()
    }

    @Test
    fun `rejects bad timestamps`() {
        assertThat(
            parseRemoteFlagConfig(valid.replace("2026-09-27T12:00:00Z", "yesterday")),
        ).isNull()
        // Expiry must be after generation.
        assertThat(
            parseRemoteFlagConfig(valid.replace("2026-09-27T18:00:00Z", "2026-09-27T11:00:00Z")),
        ).isNull()
    }

    @Test
    fun `rejects missing flags object`() {
        val noFlags = """{"version":1,"generatedAt":"2026-09-27T12:00:00Z","expiresAt":"2026-09-27T18:00:00Z"}"""
        assertThat(parseRemoteFlagConfig(noFlags)).isNull()
    }

    @Test
    fun `drops non-boolean flag values instead of coercing`() {
        val mixed = """
            {"version":1,"generatedAt":"2026-09-27T12:00:00Z","expiresAt":"2026-09-27T18:00:00Z",
             "flags":{"bandAdvisor":"yes","propagationAlerts":true}}
        """.trimIndent()
        val config = parseRemoteFlagConfig(mixed)!!
        assertThat(config.valueFor(FeatureFlag.BAND_ADVISOR)).isNull()
        assertThat(config.valueFor(FeatureFlag.PROPAGATION_ALERTS)).isTrue()
    }

    @Test
    fun `unknown flag names survive parsing but are simply never looked up`() {
        val future = """
            {"version":1,"generatedAt":"2026-09-27T12:00:00Z","expiresAt":"2026-09-27T18:00:00Z",
             "flags":{"someFutureFeature":true}}
        """.trimIndent()
        val config = parseRemoteFlagConfig(future)!!
        for (flag in FeatureFlag.entries) {
            assertThat(config.valueFor(flag)).isNull()
        }
    }

    @Test
    fun `iso parser handles fractional seconds and rejects garbage`() {
        assertThat(parseIso8601UtcMs("1970-01-01T00:00:00Z")).isEqualTo(0L)
        assertThat(parseIso8601UtcMs("1970-01-01T00:00:01.500Z")).isEqualTo(1000L)
        assertThat(parseIso8601UtcMs("2026-09-27T12:00:00Zjunk")).isNull()
        assertThat(parseIso8601UtcMs(null)).isNull()
        assertThat(parseIso8601UtcMs("")).isNull()
        assertThat(parseIso8601UtcMs("2026-13-45T99:00:00Z")).isNull()
    }
}
