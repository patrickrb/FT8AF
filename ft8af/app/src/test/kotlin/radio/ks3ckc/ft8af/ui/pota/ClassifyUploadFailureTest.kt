package radio.ks3ckc.ft8af.ui.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8af.pota.PotaUploadException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Coverage for [classifyUploadFailure], which turns an upload failure into the
 * category [startUpload] uses to pick a user-facing message. Pure JVM logic — no
 * Android types, so no Robolectric runner.
 */
class ClassifyUploadFailureTest {

    @Test
    fun `gateway statuses are transient busy errors`() {
        // 502/503/504 = POTA's gateway couldn't reach the upstream — retried then
        // surfaced as BUSY ("try again shortly"), not a log rejection.
        assertThat(classifyUploadFailure(PotaUploadException(502, "Internal server error")))
            .isEqualTo(UploadFailureKind.BUSY)
        assertThat(classifyUploadFailure(PotaUploadException(503, "")))
            .isEqualTo(UploadFailureKind.BUSY)
        assertThat(classifyUploadFailure(PotaUploadException(504, "")))
            .isEqualTo(UploadFailureKind.BUSY)
    }

    @Test
    fun `other 5xx is a server rejection`() {
        // A plain 500 (or other non-gateway 5xx) means POTA processed the request
        // and rejected the log — point the user at their park ref / callsign.
        assertThat(classifyUploadFailure(PotaUploadException(500, "")))
            .isEqualTo(UploadFailureKind.SERVER)
        assertThat(classifyUploadFailure(PotaUploadException(599, "")))
            .isEqualTo(UploadFailureKind.SERVER)
    }

    @Test
    fun `4xx is not treated as a server error`() {
        assertThat(classifyUploadFailure(PotaUploadException(400, "bad request")))
            .isEqualTo(UploadFailureKind.OTHER)
        assertThat(classifyUploadFailure(PotaUploadException(403, "forbidden")))
            .isEqualTo(UploadFailureKind.OTHER)
    }

    @Test
    fun `connectivity exceptions are network failures`() {
        assertThat(classifyUploadFailure(UnknownHostException("api.pota.app")))
            .isEqualTo(UploadFailureKind.NETWORK)
        assertThat(classifyUploadFailure(SocketTimeoutException("timeout")))
            .isEqualTo(UploadFailureKind.NETWORK)
        assertThat(classifyUploadFailure(IOException("broken pipe")))
            .isEqualTo(UploadFailureKind.NETWORK)
    }

    @Test
    fun `null and unrecognized errors fall back to OTHER`() {
        assertThat(classifyUploadFailure(null)).isEqualTo(UploadFailureKind.OTHER)
        assertThat(classifyUploadFailure(IllegalStateException("no database")))
            .isEqualTo(UploadFailureKind.OTHER)
    }

    @Test
    fun `summarizeUpload succeeds only when every park uploaded`() {
        assertThat(summarizeUpload(ok = 2, total = 2, serverError = null, preflightError = null).isSuccess)
            .isTrue()
        assertThat(summarizeUpload(ok = 2, total = 2, serverError = null, preflightError = null).getOrNull())
            .isEqualTo(2)
    }

    @Test
    fun `summarizeUpload prefers a server error over a pre-flight resolution error`() {
        // Two-park activation: park A couldn't resolve its location (pre-flight),
        // park B got a retryable 502 (server). The 502 must win so the UI shows
        // BUSY ("try again") rather than the generic OTHER an IllegalStateException
        // classifies to.
        val server = PotaUploadException(502, "Internal server error")
        val preflight = IllegalStateException("could not resolve POTA location for US-4556")
        val err = summarizeUpload(ok = 0, total = 2, serverError = server, preflightError = preflight)
            .exceptionOrNull()
        assertThat(err).isSameInstanceAs(server)
        assertThat(classifyUploadFailure(err)).isEqualTo(UploadFailureKind.BUSY)
    }

    @Test
    fun `summarizeUpload surfaces the pre-flight error when there is no server error`() {
        val preflight = IllegalStateException("no station callsign for US-1234 — set your callsign")
        val err = summarizeUpload(ok = 0, total = 1, serverError = null, preflightError = preflight)
            .exceptionOrNull()
        assertThat(err).isSameInstanceAs(preflight)
    }

    @Test
    fun `summarizeUpload falls back to a count message when no error was recorded`() {
        val err = summarizeUpload(ok = 1, total = 2, serverError = null, preflightError = null)
            .exceptionOrNull()
        assertThat(err).isInstanceOf(IllegalStateException::class.java)
        assertThat(err).hasMessageThat().isEqualTo("uploaded 1 of 2")
    }
}
