package radio.ks3ckc.ft8af.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Coverage for the upload retry policy in [PotaClient]: which failures are worth
 * retrying ([isRetryableUploadFailure]) and how long to wait between tries
 * ([uploadBackoffMs]). Pure JVM logic — no Android types, so no Robolectric.
 */
class UploadRetryTest {

    @Test
    fun `gateway statuses are retryable`() {
        // The 502 seen in the field, plus the sibling gateway statuses.
        assertThat(isRetryableUploadFailure(PotaUploadException(502, "Internal server error"))).isTrue()
        assertThat(isRetryableUploadFailure(PotaUploadException(503, ""))).isTrue()
        assertThat(isRetryableUploadFailure(PotaUploadException(504, ""))).isTrue()
    }

    @Test
    fun `plain 500 and 4xx are not retryable`() {
        // A non-gateway 5xx means the log itself was rejected; a 4xx is an
        // auth/request problem. Retrying either just fails again identically.
        assertThat(isRetryableUploadFailure(PotaUploadException(500, ""))).isFalse()
        assertThat(isRetryableUploadFailure(PotaUploadException(400, "bad request"))).isFalse()
        assertThat(isRetryableUploadFailure(PotaUploadException(403, "forbidden"))).isFalse()
    }

    @Test
    fun `connectivity exceptions are retryable`() {
        assertThat(isRetryableUploadFailure(SocketTimeoutException("timeout"))).isTrue()
        assertThat(isRetryableUploadFailure(UnknownHostException("api.pota.app"))).isTrue()
        assertThat(isRetryableUploadFailure(IOException("connection reset"))).isTrue()
    }

    @Test
    fun `non-network non-http failures are not retryable`() {
        assertThat(isRetryableUploadFailure(null)).isFalse()
        assertThat(isRetryableUploadFailure(IllegalStateException("no database"))).isFalse()
    }

    @Test
    fun `backoff grows exponentially from one second`() {
        assertThat(uploadBackoffMs(1)).isEqualTo(1000L)
        assertThat(uploadBackoffMs(2)).isEqualTo(2000L)
        assertThat(uploadBackoffMs(3)).isEqualTo(4000L)
    }
}
