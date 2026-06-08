package radio.ks3ckc.ft8us.ui.pota

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bg7yoz.ft8cn.R
import kotlinx.coroutines.launch
import radio.ks3ckc.ft8us.pota.PotaAuth
import radio.ks3ckc.ft8us.theme.Accent
import radio.ks3ckc.ft8us.theme.BgApp
import radio.ks3ckc.ft8us.theme.TextMuted
import radio.ks3ckc.ft8us.theme.TextPrimary

/**
 * Full-screen WebView that drives POTA's Cognito hosted-UI OAuth2 flow, so users
 * who sign in with Google / Facebook / Login-with-Amazon (federated identities
 * with no Cognito password) can authenticate. The SRP email+password path in
 * [radio.ks3ckc.ft8us.ui.pota.PotaLoginDialog] can't serve those accounts.
 *
 * Why a WebView and not Chrome Custom Tabs (Google's preferred container): POTA's
 * Cognito app client registers exactly one redirect URI — `https://pota.app/` —
 * and rejects anything we could claim from the app (custom scheme, localhost, …).
 * A Custom Tab would hand that redirect to the system browser and we'd never see
 * the code. A WebView lets us watch navigation and lift the `?code=` out the
 * instant Cognito redirects, before pota.app's page loads.
 *
 * The default Android WebView UA contains "; wv", which Google's consent screen
 * rejects (`disallowed_useragent`). We override it with a plain Chrome UA so
 * Google sign-in works; Facebook / Amazon / email work either way.
 *
 * [onClose] fires exactly once: `true` if a refresh token was obtained and stored
 * (caller can proceed to upload), `false` on cancel / error.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PotaOAuthDialog(onClose: (success: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val pkce = remember { PotaAuth.newPkce() }
    val currentOnClose by rememberUpdatedState(onClose)
    var exchanging by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!exchanging) currentOnClose(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgApp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.pota_oauth_title),
                    color = TextPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                TextButton(
                    onClick = { if (!exchanging) currentOnClose(false) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Text(stringResource(R.string.pota_login_cancel), color = TextMuted)
                }
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // Google's consent screen rejects the "; wv" token the default
                            // Android WebView UA carries (disallowed_useragent). Strip just
                            // that token so the UA stays current with the device's real
                            // Chrome/WebView version instead of pinning a version that ages out.
                            settings.userAgentString = stripWebViewToken(settings.userAgentString)

                            var captured = false

                            fun handleRedirect(url: String): Boolean {
                                if (captured || !url.startsWith(PotaAuth.OAUTH_REDIRECT)) return false
                                captured = true
                                val uri = Uri.parse(url)
                                val code = uri.getQueryParameter("code")
                                if (code.isNullOrBlank()) {
                                    // error=… or user bailed at the provider — treat as cancel.
                                    currentOnClose(false)
                                    return true
                                }
                                exchanging = true
                                scope.launch {
                                    val r = PotaAuth.exchangeCode(code, pkce.verifier)
                                    exchanging = false
                                    currentOnClose(r.isSuccess)
                                }
                                return true
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean = handleRedirect(request.url.toString())

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    url: String,
                                ): Boolean = handleRedirect(url)
                            }
                            // removeAllCookies is async; load the authorize URL from its
                            // callback (fires on the main thread) so navigation only begins
                            // once cookies are cleared — otherwise the page could reuse a
                            // stale session, contradicting the clean-start intent.
                            val authUrl = PotaAuth.authorizeUrl(pkce)
                            CookieManager.getInstance().removeAllCookies { loadUrl(authUrl) }
                        }
                    },
                )
                if (exchanging) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BgApp.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Accent)
                    }
                }
            }
        }
    }
}

/**
 * Remove the "; wv" token an Android WebView appends to its user-agent. Google's
 * OAuth consent screen rejects any UA carrying that token with
 * `disallowed_useragent`; stripping it yields a plain Chrome UA that loads, while
 * keeping the device's real (and self-updating) Chrome/WebView version.
 */
internal fun stripWebViewToken(ua: String): String = ua.replace("; wv", "")
