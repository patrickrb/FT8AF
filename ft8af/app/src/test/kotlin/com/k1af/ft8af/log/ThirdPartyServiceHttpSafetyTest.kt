package com.k1af.ft8af.log

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.io.StringReader
import java.net.URL

/**
 * The response-size cap and the redirect rule that keep a Cloudlog/Wavelog server (or a
 * man-in-the-middle on http://) from running the heap out or collecting the API key.
 * Robolectric only for org.json, which [ThirdPartyService] loads.
 */
@RunWith(RobolectricTestRunner::class)
class ThirdPartyServiceHttpSafetyTest {

    @Test
    fun `body is read with line breaks dropped, like the old readLine loop`() {
        assertThat(ThirdPartyService.readBodyCapped(StringReader("{\"a\":\r\n1}\n"), 100))
            .isEqualTo("{\"a\":1}")
    }

    @Test
    fun `body at the cap is read, one char over throws`() {
        assertThat(ThirdPartyService.readBodyCapped(StringReader("x".repeat(10)), 10)).hasLength(10)
        assertThrows(IOException::class.java) {
            ThirdPartyService.readBodyCapped(StringReader("x".repeat(11)), 10)
        }
    }

    @Test
    fun `one endless line is still capped`() {
        // readLine() would have buffered the whole line before any check could run.
        assertThrows(IOException::class.java) {
            ThirdPartyService.readBodyCapped(StringReader("y".repeat(100_000)), 1000)
        }
    }

    @Test
    fun `redirects stay on the same host`() {
        val from = URL("https://log.example.org/api/qso")
        assertThat(ThirdPartyService.redirectAllowed(from, URL("https://log.example.org/index.php/api/qso"))).isTrue()
        assertThat(ThirdPartyService.redirectAllowed(from, URL("https://LOG.example.org/api/qso"))).isTrue()
        assertThat(ThirdPartyService.redirectAllowed(from, URL("https://evil.example.net/api/qso"))).isFalse()
        assertThat(ThirdPartyService.redirectAllowed(from, URL("https://www.log.example.org/api/qso"))).isFalse()
    }

    @Test
    fun `redirects never downgrade https to http, but may upgrade`() {
        assertThat(ThirdPartyService.redirectAllowed(
            URL("https://log.example.org/api/qso"), URL("http://log.example.org/api/qso"))).isFalse()
        assertThat(ThirdPartyService.redirectAllowed(
            URL("http://192.168.1.20/api/qso"), URL("https://192.168.1.20/api/qso"))).isTrue()
        assertThat(ThirdPartyService.redirectAllowed(
            URL("http://192.168.1.20/api/qso"), URL("http://192.168.1.20/index.php/api/qso"))).isTrue()
    }
}
