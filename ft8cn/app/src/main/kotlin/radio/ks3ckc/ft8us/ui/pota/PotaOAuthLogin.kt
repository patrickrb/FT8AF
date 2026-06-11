package radio.ks3ckc.ft8us.ui.pota

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * Getting Google sign-in to actually render inside a WebView takes three things,
 * all of which used to be missing and produced a blank white page when the user
 * tapped "Sign in with Google":
 *  1. A non-"; wv" user-agent ([stripWebViewToken]) — Google's consent screen
 *     rejects the default WebView UA with `disallowed_useragent`.
 *  2. Third-party cookies enabled — Google's account chooser / consent set and
 *     read cookies across google.com; WebView blocks third-party cookies by
 *     default, which can leave the page blank.
 *  3. Multi-window handling — Google sometimes opens the account chooser via
 *     `window.open`; a WebView with no [WebChromeClient.onCreateWindow] silently
 *     drops the popup, so the user sees nothing happen. We route the popup's
 *     navigation back into the main WebView instead.
 *
 * Every navigation, error, and console message is written to debug.log so a future
 * "blank page" report carries the failing URL / HTTP status instead of nothing.
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
                            // Google opens its account chooser in a popup window in some
                            // flows; without these + the onCreateWindow handler below the
                            // popup is silently dropped (blank screen, "nothing happened").
                            settings.setSupportMultipleWindows(true)
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            // Google's consent screen rejects the "; wv" token the default
                            // Android WebView UA carries (disallowed_useragent). Strip just
                            // that token so the UA stays current with the device's real
                            // Chrome/WebView version instead of pinning a version that ages out.
                            settings.userAgentString = stripWebViewToken(settings.userAgentString)

                            // Google sign-in reads/writes cookies across google.com while the
                            // page lives on the Cognito hosted-UI origin — third-party from
                            // the WebView's perspective. WebView blocks those by default,
                            // which can leave the consent page blank.
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            var captured = false

                            fun handleRedirect(url: String): Boolean {
                                if (captured) return false
                                return when (val r = classifyRedirect(url, PotaAuth.OAUTH_REDIRECT)) {
                                    OAuthRedirect.NotRedirect -> false
                                    OAuthRedirect.NoCode -> {
                                        // error=… or user bailed at the provider — treat as cancel.
                                        captured = true
                                        oauthLog(ctx, "redirect without code -> cancel")
                                        currentOnClose(false)
                                        true
                                    }
                                    is OAuthRedirect.WithCode -> {
                                        captured = true
                                        oauthLog(ctx, "captured auth code, exchanging")
                                        exchanging = true
                                        scope.launch {
                                            val res = PotaAuth.exchangeCode(r.code, pkce.verifier)
                                            exchanging = false
                                            currentOnClose(res.isSuccess)
                                        }
                                        true
                                    }
                                }
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

                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    oauthLog(ctx, "page start: ${redactUrl(url)}")
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (request.isForMainFrame) {
                                        oauthLog(ctx, "load error ${error.errorCode} '${error.description}' for ${redactUrl(request.url.toString())}")
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    errorResponse: WebResourceResponse,
                                ) {
                                    if (request.isForMainFrame) {
                                        oauthLog(ctx, "http ${errorResponse.statusCode} for ${redactUrl(request.url.toString())}")
                                    }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                // Route a popup (window.open / target=_blank) back into this
                                // WebView so Google's account chooser is actually shown.
                                override fun onCreateWindow(
                                    view: WebView,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message,
                                ): Boolean {
                                    oauthLog(ctx, "popup window requested -> routing into main view")
                                    val popup = WebView(view.context)
                                    popup.settings.javaScriptEnabled = true
                                    popup.settings.userAgentString = view.settings.userAgentString
                                    popup.webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            v: WebView,
                                            request: WebResourceRequest,
                                        ): Boolean = adoptPopupUrl(view, ::handleRedirect, request.url.toString(), popup)

                                        @Deprecated("Deprecated in Java")
                                        override fun shouldOverrideUrlLoading(
                                            v: WebView,
                                            url: String,
                                        ): Boolean = adoptPopupUrl(view, ::handleRedirect, url, popup)
                                    }
                                    (resultMsg.obj as WebView.WebViewTransport).webView = popup
                                    resultMsg.sendToTarget()
                                    return true
                                }

                                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                        oauthLog(ctx, "console error: ${message.message()}")
                                    }
                                    return true
                                }
                            }
                            // removeAllCookies is async; load the authorize URL from its
                            // callback (fires on the main thread) so navigation only begins
                            // once cookies are cleared — otherwise the page could reuse a
                            // stale session, contradicting the clean-start intent.
                            val authUrl = PotaAuth.authorizeUrl(pkce)
                            oauthLog(ctx, "starting OAuth flow")
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
 * Where a navigation in the OAuth WebView lands relative to POTA's registered
 * redirect URI. [classifyRedirect] keeps the URL parsing out of the WebView
 * callbacks so it can be unit-tested.
 */
internal sealed class OAuthRedirect {
    /** Not the redirect URI — let the WebView load it normally (Google, Cognito, …). */
    object NotRedirect : OAuthRedirect()

    /** The redirect URI but with no `code` (provider error or user cancelled). */
    object NoCode : OAuthRedirect()

    /** The redirect URI carrying the authorization `code` to exchange for tokens. */
    data class WithCode(val code: String) : OAuthRedirect()
}

/**
 * Classify a WebView navigation against POTA's hosted-UI [redirectPrefix]
 * (`https://pota.app/`). Only URLs at that prefix are ours to intercept; a `code`
 * query parameter means success, its absence means the provider redirected back
 * with an error or the user bailed.
 */
internal fun classifyRedirect(url: String, redirectPrefix: String): OAuthRedirect {
    if (!url.startsWith(redirectPrefix)) return OAuthRedirect.NotRedirect
    val code = Uri.parse(url).getQueryParameter("code")
    return if (code.isNullOrBlank()) OAuthRedirect.NoCode else OAuthRedirect.WithCode(code)
}

/**
 * Handle a navigation that arrived in a popup WebView: if it's our redirect URI,
 * finish the flow via [handleRedirect]; otherwise load it in the visible [main]
 * WebView. Either way the throwaway [popup] is destroyed. Returns true so the
 * popup never navigates on its own (it's offscreen and would render nothing).
 */
private fun adoptPopupUrl(
    main: WebView,
    handleRedirect: (String) -> Boolean,
    url: String,
    popup: WebView,
): Boolean {
    if (!handleRedirect(url)) main.loadUrl(url)
    popup.destroy()
    return true
}

/**
 * Remove the "; wv" token an Android WebView appends to its user-agent. Google's
 * OAuth consent screen rejects any UA carrying that token with
 * `disallowed_useragent`; stripping it yields a plain Chrome UA that loads, while
 * keeping the device's real (and self-updating) Chrome/WebView version.
 */
internal fun stripWebViewToken(ua: String): String = ua.replace("; wv", "")

/**
 * Strip the query string from a URL before logging it. OAuth URLs carry the
 * authorization `code`, PKCE challenge, and (on the token endpoint) tokens —
 * none of which belong in debug.log.
 */
internal fun redactUrl(url: String?): String {
    if (url.isNullOrEmpty()) return "?"
    val q = url.indexOf('?')
    return if (q >= 0) url.substring(0, q) + "?…" else url
}

private fun oauthLog(ctx: Context, msg: String) {
    Log.d("PotaOAuth", msg)
    try {
        val dir = ctx.getExternalFilesDir(null) ?: return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        FileWriter(File(dir, "debug.log"), true).use { it.append("$ts PotaOAuth: $msg\n") }
    } catch (_: Exception) {
    }
}
