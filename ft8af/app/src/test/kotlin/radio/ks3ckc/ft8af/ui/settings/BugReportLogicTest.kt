package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.URLDecoder

/**
 * Pure-logic tests for the in-app bug reporter. No Android types are touched, so
 * these run without Robolectric.
 */
class BugReportLogicTest {

    private fun info(callsign: String = "K1AF") = BugReportInfo(
        appVersion = "1.0.2",
        versionCode = 104,
        buildDate = "2026-06-11",
        manufacturer = "Google",
        deviceModel = "Pixel 8",
        androidRelease = "15",
        sdkInt = 35,
        callsign = callsign,
    )

    @Test
    fun `body includes the description and every context field`() {
        val body = buildBugReportBody("Decode freezes on 20m", info())
        assertThat(body).contains("Decode freezes on 20m")
        assertThat(body).contains("1.0.2")
        assertThat(body).contains("104")
        assertThat(body).contains("2026-06-11")
        assertThat(body).contains("Google Pixel 8")
        assertThat(body).contains("15")
        assertThat(body).contains("API 35")
        assertThat(body).contains("K1AF")
    }

    @Test
    fun `body shows placeholders when description and callsign are blank`() {
        val body = buildBugReportBody("   ", info(callsign = ""))
        assertThat(body).contains("(no description provided)")
        assertThat(body).contains("(not set)")
    }

    @Test
    fun `title carries the version`() {
        assertThat(buildBugReportTitle(info())).isEqualTo("Bug report (v1.0.2)")
    }

    @Test
    fun `github url targets the FT8AF new-issue endpoint`() {
        val url = buildGithubIssueUrl("hello world", info())
        assertThat(url).startsWith("https://github.com/patrickrb/FT8AF/issues/new")
        assertThat(url).contains("title=")
        assertThat(url).contains("body=")
    }

    @Test
    fun `github url points operators at the Share logs row for debug log`() {
        val url = buildGithubIssueUrl("hello", info())
        val body = URLDecoder.decode(url.substringAfter("body="), "UTF-8")
        assertThat(body).contains("Settings -> About -> Share logs")
        assertThat(body).doesNotContain("About -> Debug")
    }

    @Test
    fun `github url percent-encodes the body so it carries no raw spaces`() {
        val url = buildGithubIssueUrl("two words\nnext line", info())
        val query = url.substringAfter("?")
        assertThat(query).doesNotContain(" ")

        val bodyParam = query.substringAfter("body=")
        val decoded = URLDecoder.decode(bodyParam, "UTF-8")
        assertThat(decoded).contains("two words")
        assertThat(decoded).contains("next line")
    }

    @Test
    fun `feedback body uses the feedback heading over the same device block`() {
        val body = buildFeedbackBody("Waterfall is too dim", info())
        assertThat(body).startsWith("What's not working:\nWaterfall is too dim")
        assertThat(body).doesNotContain("Describe the problem:")
        assertThat(body).contains("App version: 1.0.2 (build 104)")
        assertThat(body).contains("Android: 15 (API 35)")
        assertThat(body).contains("Device: Google Pixel 8")
        assertThat(body).contains("Callsign: K1AF")
    }

    @Test
    fun `feedback body shows a placeholder when blank`() {
        assertThat(buildFeedbackBody(" ", info())).contains("(no feedback provided)")
    }

    @Test
    fun `feedback title carries the app version`() {
        assertThat(buildFeedbackTitle(info())).isEqualTo("App feedback (v1.0.2)")
    }
}
