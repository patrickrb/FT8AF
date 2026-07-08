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
}
