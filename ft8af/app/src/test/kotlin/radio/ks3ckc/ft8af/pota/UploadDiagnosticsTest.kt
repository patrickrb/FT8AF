package radio.ks3ckc.ft8af.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Coverage for the upload-failure diagnostics helpers in [PotaClient]:
 * [uploadTraceSummary] (which AWS trace headers get logged on a 502) and
 * [countOccurrences] (the QSO count reported in the send line). Pure JVM logic —
 * no Android types, so no Robolectric.
 */
class UploadDiagnosticsTest {

    @Test
    fun `trace summary picks known headers in declared order, case-insensitively`() {
        // Real API Gateway casing varies (apigw-requestid is lowercase, x-amzn-RequestId
        // mixed); the null-keyed status-line entry HttpURLConnection adds must be ignored.
        val headers = mapOf(
            null to listOf("HTTP/1.1 502 Bad Gateway"),
            "Content-Type" to listOf("application/json"),
            "apigw-requestid" to listOf("abc123="),
            "X-Amzn-Requestid" to listOf("req-987"),
            "x-cache" to listOf("Error from cloudfront"),
            "Server" to listOf("CloudFront"),
        )
        val summary = uploadTraceSummary(headers)
        // Declared order: x-amzn-RequestId, ..., apigw-requestid, ..., x-cache, ..., Content-Type.
        assertThat(summary)
            .isEqualTo("x-amzn-RequestId=req-987 apigw-requestid=abc123= x-cache=Error from cloudfront Content-Type=application/json")
        // Unknown headers are dropped.
        assertThat(summary).doesNotContain("CloudFront")
    }

    @Test
    fun `trace summary is empty when no known headers present`() {
        assertThat(uploadTraceSummary(mapOf<String?, List<String>>("Server" to listOf("nginx")))).isEmpty()
        assertThat(uploadTraceSummary(emptyMap())).isEmpty()
    }

    @Test
    fun `trace summary comma-joins multi-valued headers`() {
        val headers = mapOf<String?, List<String>>("Via" to listOf("1.1 aaa", "1.1 bbb"))
        assertThat(uploadTraceSummary(headers)).isEqualTo("via=1.1 aaa,1.1 bbb")
    }

    @Test
    fun `countOccurrences counts non-overlapping matches`() {
        val adif = "<CALL:4>K1AF <EOR>\n<CALL:5>KC3RH <EOR>\n<CALL:4>W4SF <EOR>\n"
        assertThat(countOccurrences(adif, "<EOR>")).isEqualTo(3)
    }

    @Test
    fun `countOccurrences returns zero for no match, empty inputs`() {
        assertThat(countOccurrences("header only, no records", "<EOR>")).isEqualTo(0)
        assertThat(countOccurrences("", "<EOR>")).isEqualTo(0)
        // Empty needle must not loop forever.
        assertThat(countOccurrences("anything", "")).isEqualTo(0)
    }
}
