import FT8Engine
import SwiftUI
import WebKit

/// Full-screen sheet that drives POTA's Cognito hosted-UI OAuth2 flow in a
/// WKWebView, so any POTA account — email/password *or* federated (Google /
/// Facebook / Login-with-Amazon) — can authenticate. The hosted UI serves both
/// through the same authorization-code + PKCE redirect.
///
/// Why a WebView and not `ASWebAuthenticationSession`/`SFSafariViewController`:
/// POTA's Cognito app client registers exactly one redirect URI —
/// `https://pota.app/` — and rejects anything we could claim from the app
/// (custom scheme, etc.). A system auth session needs a scheme it can hand back;
/// a WKWebView instead lets us watch navigation and lift the `?code=` out the
/// instant Cognito redirects, before pota.app's page loads.
///
/// Ports the Android `PotaOAuthDialog` gotchas:
///  - a Safari-like `customUserAgent` in case a federated provider rejects the
///    default WebView UA (WKWebView's UA has no ";wv" token, unlike Android, so
///    this is usually unnecessary — but harmless and future-proof);
///  - `window.open` popups (Google's account chooser) routed back into the main
///    web view via `WKUIDelegate` so they aren't silently dropped.
///
/// `onFinished(success:)` fires exactly once: true if a refresh token was
/// obtained and stored, false on cancel / error.
struct PotaLoginSheet: View {
    let onFinished: (_ success: Bool) -> Void

    @State private var pkce = PotaAuth.newPkce()
    @State private var exchanging = false
    // Guard so onFinished fires exactly once across the delegate + Cancel button.
    @State private var finished = false

    var body: some View {
        ZStack {
            bgApp.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack {
                    Text("Sign in to POTA")
                        .font(.ft8afUI(size: 14, weight: .semibold))
                        .foregroundStyle(textPrimary)
                    Spacer()
                    Button("Cancel") { finish(false) }
                        .font(.ft8afUI(size: 14))
                        .foregroundStyle(textMuted)
                        .disabled(exchanging)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)

                ZStack {
                    PotaOAuthWebView(
                        authorizeURL: PotaAuth.authorizeURL(codeChallenge: pkce.challenge),
                        onCode: handleCode,
                        onCancel: { finish(false) })
                    if exchanging {
                        bgApp.opacity(0.7).ignoresSafeArea()
                        ProgressView().tint(accent)
                    }
                }
            }
        }
    }

    private func handleCode(_ code: String) {
        guard !finished else { return }
        exchanging = true
        Task {
            let ok = await PotaAuthService.shared.exchange(code: code, verifier: pkce.verifier)
            exchanging = false
            finish(ok)
        }
    }

    private func finish(_ success: Bool) {
        guard !finished else { return }
        finished = true
        onFinished(success)
    }
}

/// The WKWebView itself. Loads the authorize URL and intercepts navigation to
/// `https://pota.app/?code=…`, handing the code up (never letting pota.app load).
/// Redirect classification is the security-critical `PotaAuth.classifyRedirect`,
/// which matches scheme + host exactly so a lookalike host can't steal the code.
private struct PotaOAuthWebView: UIViewRepresentable {
    let authorizeURL: String
    let onCode: (String) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onCode: onCode, onCancel: onCancel)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent() // clean start, no stale session
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        // A plain Safari-ish UA in case a federated provider is picky. WKWebView's
        // default UA already omits Android's ";wv" token, so this is belt-and-braces.
        webView.customUserAgent =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        if let url = URL(string: authorizeURL) {
            webView.load(URLRequest(url: url))
        }
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        private let onCode: (String) -> Void
        private let onCancel: () -> Void
        // Once we capture (or reject) the redirect, ignore later navigations.
        private var captured = false

        init(onCode: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
            self.onCode = onCode
            self.onCancel = onCancel
        }

        /// Intercept every navigation. Returns true (and .cancel) when it was our
        /// redirect URI; otherwise the navigation is allowed to proceed.
        private func handle(_ url: URL?) -> Bool {
            guard !captured, let url else { return false }
            switch PotaAuth.classifyRedirect(url.absoluteString) {
            case .notRedirect:
                return false
            case .noCode:
                // error=… or the user bailed at the provider — treat as cancel.
                captured = true
                onCancel()
                return true
            case .withCode(let code):
                captured = true
                onCode(code)
                return true
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            if handle(navigationAction.request.url) {
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
            }
        }

        // Route a popup (window.open / target=_blank — e.g. Google's account
        // chooser) back into the main web view instead of dropping it.
        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            if let url = navigationAction.request.url, !handle(url) {
                webView.load(URLRequest(url: url))
            }
            return nil
        }
    }
}
