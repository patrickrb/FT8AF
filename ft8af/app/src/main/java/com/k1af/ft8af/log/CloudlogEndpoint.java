package com.k1af.ft8af.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Pure URL plumbing for the Cloudlog / Wavelog / Nextlog API (issue #756).
 *
 * <p>Users type the server address by hand and self-hosted installs vary a lot:
 * <ul>
 *   <li>{@code 192.168.15.20/cloudlog} — no scheme at all ({@code new URL()} would throw
 *       {@code MalformedURLException: no protocol});</li>
 *   <li>{@code http://192.168.186.73/wavelog/} — plain HTTP on a LAN;</li>
 *   <li>installs without Apache {@code mod_rewrite}/{@code .htaccess}, where the API only
 *       answers at {@code /wavelog/index.php/api/...} and {@code /wavelog/api/...} is a 404.</li>
 * </ul>
 *
 * <p>This class turns the raw address into a normalized base URL and, for each API path,
 * the ordered list of candidate URLs to try: the rewritten form first, then the
 * {@code index.php/} form. Once a variant answers, {@link #rememberWorking} pins it so
 * later uploads don't pay for a 404 round-trip on every QSO. No Android imports — keep
 * it that way so it stays unit-testable.
 */
public final class CloudlogEndpoint {

    static final String INDEX_PHP = "index.php/";

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /** Address (as typed, trimmed) → base URL variant that last answered. In-memory only. */
    private static final Map<String, String> WORKING_BASE = new ConcurrentHashMap<>();

    private CloudlogEndpoint() {
    }

    /**
     * Normalizes a user-entered address into a base URL ending in {@code /}.
     *
     * <ul>
     *   <li>Trims surrounding whitespace.</li>
     *   <li>Adds a scheme when missing: {@code http://} for IPv4 literals, {@code localhost},
     *       single-label hosts and {@code .local}/{@code .lan}/{@code .home}/{@code .internal}
     *       names (LAN boxes practically never have a certificate); {@code https://} for
     *       anything that looks like a public hostname.</li>
     *   <li>Guarantees exactly one trailing slash.</li>
     * </ul>
     *
     * @return the normalized base, or {@code null} if the address is null/blank.
     */
    public static String normalizeBase(String address) {
        if (address == null) return null;
        String a = address.trim();
        if (a.isEmpty()) return null;
        if (!a.contains("://")) {
            a = (looksLikeLanHost(hostOf(a)) ? "http://" : "https://") + a;
        }
        while (a.endsWith("//")) {
            a = a.substring(0, a.length() - 1);
        }
        if (!a.endsWith("/")) {
            a += "/";
        }
        return a;
    }

    /** Host part of a scheme-less address, minus any port, path or userinfo. */
    static String hostOf(String schemeless) {
        String h = schemeless;
        int at = h.indexOf('@');
        if (at >= 0) h = h.substring(at + 1);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            return close >= 0 ? h.substring(0, close + 1) : h;
        }
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        return h;
    }

    static boolean looksLikeLanHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) return true; // IPv6 literal — almost always a LAN box
        if (IPV4.matcher(h).matches()) return true;
        if (h.equals("localhost")) return true;
        if (!h.contains(".")) return true; // single-label name: "nas", "raspberrypi"
        return h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")
                || h.endsWith(".internal");
    }

    /**
     * Ordered candidate URLs for {@code path} (e.g. {@code api/auth/KEY}) under a normalized
     * base. Returns the rewritten form first, then the {@code index.php/} fallback. If the base
     * already carries {@code index.php}, only that single URL is returned. A variant that
     * previously answered for this base is moved to the front.
     */
    public static List<String> candidates(String base, String path) {
        List<String> out = new ArrayList<>(2);
        if (base == null) return out;
        String p = path == null ? "" : path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (endsWithIndexPhpSegment(base)) {
            out.add(base + p);
            return out;
        }
        String plain = base + p;
        String rewritten = base + INDEX_PHP + p;
        String known = WORKING_BASE.get(base);
        if (known != null && known.endsWith(INDEX_PHP)) {
            out.add(rewritten);
            out.add(plain);
        } else {
            out.add(plain);
            out.add(rewritten);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * True when a normalized base URL's <em>path</em> already ends in an {@code index.php/}
     * segment, i.e. the user typed the no-rewrite form themselves and there is nothing to
     * fall back to. Only the path is inspected: a host such as {@code index.php.example}
     * or a directory merely containing the text ({@code /index.php-old/}) doesn't count.
     */
    static boolean endsWithIndexPhpSegment(String base) {
        if (base == null) return false;
        String rest = base;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) rest = rest.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String path = slash >= 0 ? rest.substring(slash) : "";
        return path.toLowerCase(Locale.ROOT).endsWith("/" + INDEX_PHP);
    }

    /**
     * Records which base variant answered so the next {@link #candidates} call for the same
     * base tries it first. {@code url} is the full URL that succeeded.
     */
    public static void rememberWorking(String base, String url) {
        if (base == null || url == null) return;
        if (url.startsWith(base + INDEX_PHP)) {
            WORKING_BASE.put(base, base + INDEX_PHP);
        } else if (url.startsWith(base)) {
            WORKING_BASE.put(base, base);
        }
    }

    /** Test hook / settings-change hook: forget every pinned variant. */
    public static void forgetAll() {
        WORKING_BASE.clear();
    }

    /**
     * Human-readable reason for a failed request, shown next to "Fail" in the settings
     * dialog and written to debug.log. Never includes the API key (callers pass redacted
     * URLs only).
     */
    public static String describeFailure(String redactedUrl, int httpStatus, Exception e) {
        if (e != null) {
            String msg = e.getMessage();
            String name = e.getClass().getSimpleName();
            if (msg == null || msg.isEmpty()) return name;
            // Common java.net messages are clear on their own; keep the class for the rest.
            if (e instanceof java.net.UnknownHostException) return "Unknown host: " + msg;
            if (e instanceof java.net.MalformedURLException) return "Bad URL: " + msg;
            if (e instanceof java.net.SocketTimeoutException) return "Timed out: " + msg;
            if (e instanceof java.net.ConnectException) return "Connect failed: " + msg;
            return name + ": " + msg;
        }
        if (httpStatus > 0) {
            return "HTTP " + httpStatus + (redactedUrl != null ? " from " + redactedUrl : "");
        }
        return "No response" + (redactedUrl != null ? " from " + redactedUrl : "");
    }
}
