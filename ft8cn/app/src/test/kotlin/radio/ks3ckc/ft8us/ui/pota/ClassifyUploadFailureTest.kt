package radio.ks3ckc.ft8us.ui.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.ft8us.pota.PotaUploadException
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
    fun `5xx from POTA is a server rejection`() {
        assertThat(classifyUploadFailure(PotaUploadException(502, "Internal server error")))
            .isEqualTo(UploadFailureKind.SERVER)
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
