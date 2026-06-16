package radio.ks3ckc.ft8us.ui.settings

import java.net.URLEncoder

/**
 * Pure, testable logic for the in-app bug reporter. The Composable
 * ([BugReportDialog]) gathers the runtime values into [BugReportInfo] and fires
 * the email / GitHub intents; everything that formats text lives here so it can be
 * unit-tested without Android or Compose.
 */

/** GitHub `owner/repo` for issue submission — matches the link in [AboutSettings]. */
internal const val BUG_REPORT_REPO = "patrickrb/FT8AF"

/** App + device context auto-attached to every report. Plain data, no Android types. */
internal data class BugReportInfo(
    val appVersion: String,
    val versionCode: Int,
    val buildDate: String,
    val manufacturer: String,
    val deviceModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    val callsign: String,
)

/**
 * Developer-facing report body: the user's description followed by an app/device
 * block. Intentionally English (diagnostic text for the maintainer, not localized).
 */
internal fun buildBugReportBody(description: String, info: BugReportInfo): String {
    val callsign = info.callsign.ifBlank { "(not set)" }
    return buildString {
        append("Describe the problem:\n")
        append(description.ifBlank { "(no description provided)" })
        append("\n\n")
        append("--- App / device info ---\n")
        append("App version: ${info.appVersion} (build ${info.versionCode})\n")
        append("Build date: ${info.buildDate}\n")
        append("Device: ${info.manufacturer} ${info.deviceModel}\n")
        append("Android: ${info.androidRelease} (API ${info.sdkInt})\n")
        append("Callsign: $callsign\n")
    }
}

/** Short issue/email title, e.g. `Bug report (v1.0.2)`. */
internal fun buildBugReportTitle(info: BugReportInfo): String =
    "Bug report (v${info.appVersion})"

/**
 * Prefilled GitHub "new issue" URL. GitHub issues can't carry a file attachment, so
 * the body includes the inline device block plus a note to attach `debug.log` from
 * Settings -> About -> Debug if the maintainer asks.
 */
internal fun buildGithubIssueUrl(description: String, info: BugReportInfo): String {
    val title = buildBugReportTitle(info)
    val body = buildBugReportBody(description, info) +
        "\n(If asked, attach debug.log from Settings -> About -> Debug.)"
    return "https://github.com/$BUG_REPORT_REPO/issues/new" +
        "?title=${enc(title)}&body=${enc(body)}"
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
