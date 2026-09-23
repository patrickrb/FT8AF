package radio.ks3ckc.ft8af.pota

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Coverage for the two request-shape helpers that make POTA's `/adif` endpoint
 * actually ingest an in-app upload: [buildUploadBody] (the multipart body must
 * carry the `reference`/`location`/`callsign` fields alongside the file, or POTA
 * returns 200 but silently discards the log) and [uploadRequestHeaders] (header
 * names must be lowercase, or the Lambda misses the boundary and 502s). Pure JVM
 * logic — no Android types, so no Robolectric.
 */
class UploadBodyTest {

    private fun bodyString(adif: String) = String(
        buildUploadBody(
            boundary = "----ft8af123",
            filename = "K1AF@US-12398-20260905.adi",
            adif = adif,
            reference = "US-12398",
            location = "US-KS",
            callsign = "K1AF",
        ),
        StandardCharsets.UTF_8,
    )

    @Test
    fun `body carries the file part with filename and octet-stream type`() {
        val body = bodyString("<CALL:4>K1AF <EOR>\n")
        assertThat(body).contains("------ft8af123\r\n") // opening boundary ("--" + "----ft8af123")
        assertThat(body).contains(
            "Content-Disposition: form-data; name=\"adif\"; filename=\"K1AF@US-12398-20260905.adi\"\r\n",
        )
        assertThat(body).contains("Content-Type: application/octet-stream\r\n\r\n")
        assertThat(body).contains("<CALL:4>K1AF <EOR>\n")
    }

    @Test
    fun `body includes the three plain fields POTA needs to create a job`() {
        // Without these, the endpoint accepts the POST (200) but never processes
        // the log — the exact reason in-app uploads never appeared on POTA.
        val body = bodyString("<CALL:4>K1AF <EOR>\n")
        assertThat(body).contains("Content-Disposition: form-data; name=\"reference\"\r\n\r\nUS-12398\r\n")
        assertThat(body).contains("Content-Disposition: form-data; name=\"location\"\r\n\r\nUS-KS\r\n")
        assertThat(body).contains("Content-Disposition: form-data; name=\"callsign\"\r\n\r\nK1AF\r\n")
    }

    @Test
    fun `body ends with the closing boundary delimiter`() {
        val body = bodyString("<CALL:4>K1AF <EOR>\n")
        assertThat(body).endsWith("------ft8af123--\r\n")
    }

    @Test
    fun `field ordering is adif then reference, location, callsign`() {
        val body = bodyString("<CALL:4>K1AF <EOR>\n")
        val iAdif = body.indexOf("name=\"adif\"")
        val iRef = body.indexOf("name=\"reference\"")
        val iLoc = body.indexOf("name=\"location\"")
        val iCall = body.indexOf("name=\"callsign\"")
        assertThat(iAdif).isLessThan(iRef)
        assertThat(iRef).isLessThan(iLoc)
        assertThat(iLoc).isLessThan(iCall)
    }

    @Test
    fun `request headers are all lowercase with the raw token and boundary`() {
        // POTA's Lambda reads headers by their lowercased name; HttpURLConnection
        // preserves the casing we set (HTTP/1.1), so a capitalized "Content-Type"
        // makes the backend miss the multipart boundary and crash with a 502.
        val headers = uploadRequestHeaders("ID.TOKEN.JWT", "----ft8af123")
        assertThat(headers.map { it.first }).containsExactly(
            "user-agent", "authorization", "content-type", "accept",
        )
        val map = headers.toMap()
        assertThat(map["authorization"]).isEqualTo("ID.TOKEN.JWT") // raw JWT, no "Bearer "
        assertThat(map["content-type"]).isEqualTo("multipart/form-data; boundary=----ft8af123")
    }
}
