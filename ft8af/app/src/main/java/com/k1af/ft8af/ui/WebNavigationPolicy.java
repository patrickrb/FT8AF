package com.k1af.ft8af.ui;

import java.util.Locale;

/**
 * Navigation policy for the app's embedded {@code WebView}s (QRZ lookup, FAQ/feedback).
 *
 * <p>Why: issue #756 relaxed the network-security {@code base-config} to permit cleartext,
 * because the user-typed Cloudlog/Wavelog address is very often a plain-HTTP LAN box. That
 * flag is app-wide, so the WebViews — which follow arbitrary links with JavaScript on —
 * would silently start honouring {@code http://} navigations too. This keeps them where
 * they were before: HTTPS only. Pure, no Android types, so it is unit-testable.
 */
public final class WebNavigationPolicy {

    private WebNavigationPolicy() {
    }

    /** True when {@code url} may be loaded inside an app WebView: an {@code https://} URL. */
    public static boolean isAllowed(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("https://") && u.length() > "https://".length();
    }
}
