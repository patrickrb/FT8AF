package radio.ks3ckc.ft8af.rota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Base-URL normalization.
 *
 * The apex case is not cosmetic: `roadsontheair.com` answers with a 308, no HTTP client
 * may auto-follow a 308 for a POST, and [isRetryableRotaFailure] classifies a
 * 3xx as fatal — so a single wrong host turns every upload of a trip into a
 * permanent failure with a full queue behind it.
 */
class RotaBaseUrlTest {
    @Test
    fun `the apex host is rewritten to www so POSTs are not 308ed`() {
        assertThat(normalizeRotaBaseUrl("https://roadsontheair.com")).isEqualTo("https://www.roadsontheair.com")
        assertThat(normalizeRotaBaseUrl("http://roadsontheair.com")).isEqualTo("http://www.roadsontheair.com")
        assertThat(normalizeRotaBaseUrl("https://ROADSONTHEAIR.COM")).isEqualTo("https://www.roadsontheair.com")
    }

    @Test
    fun `the default is already the www host`() {
        assertThat(RotaSettings.DEFAULT_BASE_URL).isEqualTo("https://www.roadsontheair.com")
        assertThat(normalizeRotaBaseUrl(RotaSettings.DEFAULT_BASE_URL))
            .isEqualTo(RotaSettings.DEFAULT_BASE_URL)
    }

    @Test
    fun `a bare host gets https rather than throwing at request time`() {
        assertThat(normalizeRotaBaseUrl("roadsontheair.com")).isEqualTo("https://www.roadsontheair.com")
        assertThat(normalizeRotaBaseUrl("dev.example.com")).isEqualTo("https://dev.example.com")
    }

    @Test
    fun `trailing slashes and whitespace are stripped so paths append cleanly`() {
        assertThat(normalizeRotaBaseUrl("  https://www.roadsontheair.com/  ")).isEqualTo("https://www.roadsontheair.com")
        assertThat(normalizeRotaBaseUrl("https://roadsontheair.com///")).isEqualTo("https://www.roadsontheair.com")
    }

    @Test
    fun `a self-hosted or dev origin is left exactly as typed`() {
        // Nothing here knows how someone else's origin is fronted, so guessing
        // would be worse than leaving it alone.
        assertThat(normalizeRotaBaseUrl("http://192.168.1.50:3000")).isEqualTo("http://192.168.1.50:3000")
        assertThat(normalizeRotaBaseUrl("https://rota.example.org")).isEqualTo("https://rota.example.org")
    }

    @Test
    fun `only the apex host matches, not a lookalike or a path`() {
        assertThat(normalizeRotaBaseUrl("https://myroadsontheair.com")).isEqualTo("https://myroadsontheair.com")
        assertThat(normalizeRotaBaseUrl("https://staging.roadsontheair.com")).isEqualTo("https://staging.roadsontheair.com")
        assertThat(normalizeRotaBaseUrl("https://example.com/roadsontheair.com")).isEqualTo("https://example.com/roadsontheair.com")
    }

    @Test
    fun `a host with a port or path keeps them while the host is fixed`() {
        assertThat(normalizeRotaBaseUrl("https://roadsontheair.com/base")).isEqualTo("https://www.roadsontheair.com/base")
    }

    @Test
    fun `the pre-rename rtota_app origin is repointed at the current domain`() {
        // An install configured before the Roads On The Air rename has the old
        // origin sitting in its prefs. Normalizing on read is the only thing
        // standing between that install and every upload failing against a host
        // we no longer serve.
        assertThat(normalizeRotaBaseUrl("https://www.rtota.app")).isEqualTo("https://www.roadsontheair.com")
        assertThat(normalizeRotaBaseUrl("https://rtota.app")).isEqualTo("https://www.roadsontheair.com")
        assertThat(normalizeRotaBaseUrl("rtota.app")).isEqualTo("https://www.roadsontheair.com")
        assertThat(normalizeRotaBaseUrl("https://RTOTA.APP")).isEqualTo("https://www.roadsontheair.com")
        assertThat(normalizeRotaBaseUrl("https://www.rtota.app/base")).isEqualTo("https://www.roadsontheair.com/base")
    }

    @Test
    fun `a lookalike of the old domain is still left alone`() {
        assertThat(normalizeRotaBaseUrl("https://myrtota.app")).isEqualTo("https://myrtota.app")
        assertThat(normalizeRotaBaseUrl("https://staging.rtota.app")).isEqualTo("https://staging.rtota.app")
    }

    @Test
    fun `empty stays empty so the caller can fall back to the default`() {
        assertThat(normalizeRotaBaseUrl("")).isEmpty()
        assertThat(normalizeRotaBaseUrl("   ")).isEmpty()
    }
}
