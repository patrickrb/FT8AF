package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the settings backup export/import logic (issue #357). These touch
 * org.json (an Android type), so they run under Robolectric per the project's
 * testing convention.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsBackupTest {

    private val sampleConfig = linkedMapOf(
        "callsign" to "K1AF",
        "grid" to "FN42",
        "cloudlogApiKey" to "secret-key",
        "qrzXmlPassword" to "hunter2",
        "pttDelay" to "50",
    )

    // -- filterConfig --

    @Test
    fun `filterConfig drops sensitive keys by default`() {
        val filtered = SettingsBackup.filterConfig(sampleConfig, includeSensitive = false)
        assertThat(filtered.keys).containsExactly("callsign", "grid", "pttDelay")
        assertThat(filtered).doesNotContainKey("cloudlogApiKey")
        assertThat(filtered).doesNotContainKey("qrzXmlPassword")
    }

    @Test
    fun `filterConfig keeps sensitive keys when opted in`() {
        val filtered = SettingsBackup.filterConfig(sampleConfig, includeSensitive = true)
        assertThat(filtered).containsKey("cloudlogApiKey")
        assertThat(filtered).containsKey("qrzXmlPassword")
        assertThat(filtered).hasSize(sampleConfig.size)
    }

    // -- buildBackupJson --

    @Test
    fun `export without sensitive omits secrets but keeps normal keys`() {
        val json = SettingsBackup.buildBackupJson(
            sampleConfig, includeSensitive = false, appVersion = "1.2.3", createdAt = "2026-07-03 10:00",
        )
        assertThat(json).contains("\"callsign\"")
        assertThat(json).contains("K1AF")
        assertThat(json).doesNotContain("secret-key")
        assertThat(json).doesNotContain("hunter2")
        assertThat(json).contains("\"formatVersion\": 1")
        assertThat(json).contains("1.2.3")
        assertThat(json).contains("2026-07-03 10:00")
    }

    @Test
    fun `export with sensitive includes secrets`() {
        val json = SettingsBackup.buildBackupJson(
            sampleConfig, includeSensitive = true, appVersion = "1.2.3", createdAt = "now",
        )
        assertThat(json).contains("secret-key")
        assertThat(json).contains("hunter2")
    }

    // -- round trip --

    @Test
    fun `export then import round-trips the non-sensitive config`() {
        val json = SettingsBackup.buildBackupJson(
            sampleConfig, includeSensitive = false, appVersion = "1.0", createdAt = "2026-07-03",
        )
        val parsed = SettingsBackup.parseBackupJson(json)
        assertThat(parsed.formatVersion).isEqualTo(SettingsBackup.FORMAT_VERSION)
        assertThat(parsed.appVersion).isEqualTo("1.0")
        assertThat(parsed.createdAt).isEqualTo("2026-07-03")
        assertThat(parsed.config).containsExactly(
            "callsign", "K1AF",
            "grid", "FN42",
            "pttDelay", "50",
        )
    }

    @Test
    fun `export with sensitive round-trips every key`() {
        val json = SettingsBackup.buildBackupJson(
            sampleConfig, includeSensitive = true, appVersion = "1.0", createdAt = "x",
        )
        val parsed = SettingsBackup.parseBackupJson(json)
        assertThat(parsed.config).containsAtLeastEntriesIn(sampleConfig)
    }

    // -- parse validation --

    @Test
    fun `parse rejects non-JSON text`() {
        val e = runCatching { SettingsBackup.parseBackupJson("not json at all") }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `parse rejects JSON that is not a backup`() {
        val e = runCatching { SettingsBackup.parseBackupJson("{\"hello\":\"world\"}") }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("FT8AF")
    }

    @Test
    fun `parse rejects a future format version`() {
        val future = "{\"formatVersion\":999,\"config\":{\"callsign\":\"K1AF\"}}"
        val e = runCatching { SettingsBackup.parseBackupJson(future) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(e).hasMessageThat().contains("newer")
    }

    @Test
    fun `parse rejects a backup with an empty config`() {
        val empty = "{\"formatVersion\":1,\"config\":{}}"
        val e = runCatching { SettingsBackup.parseBackupJson(empty) }.exceptionOrNull()
        assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
    }

    // -- defaultFileName --

    @Test
    fun `default file name embeds date and sanitized version`() {
        val name = SettingsBackup.defaultFileName("2026-07-03", "1.2.3 (Beta 4)")
        assertThat(name).startsWith("ft8af-settings-2026-07-03-v")
        assertThat(name).endsWith(".json")
        // Spaces and parens are replaced so the name stays filesystem-safe.
        assertThat(name).doesNotContain(" ")
        assertThat(name).doesNotContain("(")
    }

    @Test
    fun `default file name falls back when version is blank`() {
        val name = SettingsBackup.defaultFileName("2026-07-03", "")
        assertThat(name).contains("unknown")
    }
}
