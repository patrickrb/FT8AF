package radio.ks3ckc.ft8af.sync

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.log.ThirdPartyService
import org.junit.Test

/**
 * Unit tests for [summarize], the `debug.log` line for a finished auto-sync run.
 *
 * The counts alone were ambiguous: `cloudlog=0 qrz=0 of 113` reads identically whether the
 * network was down, the key expired, or the server rejected every record. Attaching the
 * server's own reason is what turns that line into a diagnosis.
 */
class QsoAutoSyncSummaryTest {
    private fun result(
        total: Int,
        cloudlogOk: Int,
        qrzOk: Int,
        cloudlogError: String? = null,
        qrzError: String? = null,
    ): ThirdPartyService.SyncResult =
        ThirdPartyService.SyncResult(
            total,
            cloudlogOk,
            qrzOk,
            true,
            true,
            cloudlogError,
            qrzError,
        )

    @Test
    fun `fully successful run reports counts only`() {
        val summary = summarize(result(12, 12, 12))

        assertThat(summary).isEqualTo("cloudlog=12 qrz=12 of 12")
    }

    @Test
    fun `failing cloudlog run carries the server explanation`() {
        val reason = "HTTP 400: column \"tx_pwr\" of relation \"contacts\" does not exist"
        val summary = summarize(result(113, 0, 113, cloudlogError = reason))

        assertThat(summary).startsWith("cloudlog=0 qrz=113 of 113")
        assertThat(summary).contains("cloudlogError=HTTP 400:")
        assertThat(summary).contains("tx_pwr")
        assertThat(summary).doesNotContain("qrzError=")
    }

    @Test
    fun `both services failing report both reasons`() {
        val summary =
            summarize(
                result(4, 0, 0, cloudlogError = "HTTP 401", qrzError = "RESULT=FAIL: bad key"),
            )

        assertThat(summary)
            .isEqualTo("cloudlog=0 qrz=0 of 4 cloudlogError=HTTP 401 qrzError=RESULT=FAIL: bad key")
    }

    @Test
    fun `empty error strings are treated as absent`() {
        // An empty StringBuilder must not produce a dangling "cloudlogError=".
        val summary = summarize(result(1, 1, 1, cloudlogError = "", qrzError = ""))

        assertThat(summary).isEqualTo("cloudlog=1 qrz=1 of 1")
    }

    @Test
    fun `summary is a single line so it cannot corrupt the log`() {
        val summary =
            summarize(
                result(2, 0, 0, cloudlogError = "HTTP 500: a b c", qrzError = "RESULT=FAIL"),
            )

        assertThat(summary).doesNotContain("\n")
    }
}
