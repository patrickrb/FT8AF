package com.k1af.ft8af.handoff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The wire contract the ks3ckc radio apps (FT8AF, SSTVAF, RTTYAF, JTTYAF) use to hand one
 * rig between them. Pure and Android-free so it is unit-testable and can be copied
 * verbatim into every app of the family — the constants below ARE the protocol, so they
 * must be byte-identical in all of them.
 *
 * <p>Why it exists (measured 2026-11-10, SSTVAF + FT8AF on one phone and one rig): the
 * app left in the background keeps its foreground service, its CAT serial port and its
 * direct-USB audio claim. The app the operator then opens connects to the same rig, and
 * the two take the USB audio interface from each other on their retry timers — the
 * foreground app received under three minutes of rig audio in ten, fell back to the phone
 * microphone without saying so, and decoded nothing; later the background app retuned the
 * rig under it. Neither app is wrong on its own; they just never told each other.
 *
 * <p>The handoff: the app that comes to the foreground broadcasts {@link #ACTION_TAKEOVER}
 * to its siblings. A sibling holding the rig stops transmitting, closes its CAT link and
 * USB audio, stays off the rig until it is itself foregrounded, and answers
 * {@link #ACTION_RELEASED} so the claimant can connect at once.
 */
public final class RigHandoffProtocol {

    private RigHandoffProtocol() {}

    /** "I am in front of the operator now — let go of the rig." */
    public static final String ACTION_TAKEOVER = "radio.ks3ckc.rig.action.TAKEOVER";

    /** "I have let go of the rig — it is yours." Sent only to the claimant. */
    public static final String ACTION_RELEASED = "radio.ks3ckc.rig.action.RELEASED";

    /** The sending app's package name (String). */
    public static final String EXTRA_FROM_PACKAGE = "radio.ks3ckc.rig.extra.FROM_PACKAGE";

    /** When the message was sent: {@code System.currentTimeMillis()} (long). */
    public static final String EXTRA_SENT_AT_MS = "radio.ks3ckc.rig.extra.SENT_AT_MS";

    /**
     * A message older than this is ignored. Android can hold a broadcast for a frozen
     * background process and deliver it when that app is next opened — by which time the
     * sender is no longer in front, and obeying it would make the app the operator just
     * opened hand the rig straight back. Generous on purpose: a cold-starting app's
     * main thread can sit on a message for several seconds (4.7 s measured on an
     * emulator), and {@link #predatesOwnClaim} catches the stale case exactly.
     */
    public static final long MAX_MESSAGE_AGE_MS = 15_000;

    /**
     * Whether a takeover was sent before this app last came to the foreground. The last
     * app to be put in front of the operator wins, so a request older than our own claim
     * has already been superseded by it — however late it happens to be delivered.
     *
     * @param sentAtMs             when the takeover was sent
     * @param lastForegroundedAtMs when this app last came to the foreground, 0 if never
     */
    public static boolean predatesOwnClaim(long sentAtMs, long lastForegroundedAtMs) {
        return lastForegroundedAtMs > 0 && sentAtMs < lastForegroundedAtMs;
    }

    /**
     * Every app that speaks this protocol. An app not on this list is never obeyed, so a
     * new family member must be added here in ALL apps before it can take a rig over.
     */
    public static final List<String> FAMILY_PACKAGES = Collections.unmodifiableList(
            Arrays.asList(
                    "radio.ks3ckc.ft8af",
                    "radio.ks3ckc.sstvaf",
                    "radio.ks3ckc.rttyaf",
                    "radio.ks3ckc.jttyaf"));

    /** The family members a takeover is sent to: everyone but the sender itself. */
    public static List<String> siblingsOf(String selfPackage) {
        List<String> siblings = new ArrayList<>();
        for (String pkg : FAMILY_PACKAGES) {
            if (!pkg.equals(selfPackage)) siblings.add(pkg);
        }
        return siblings;
    }

    /**
     * Whether a received handoff message should be acted on.
     *
     * <p>The receiver is necessarily exported (the sender is another app), so the message
     * itself is untrusted. It is honoured only when it names a family member other than
     * ourselves, and — where the platform can vouch for the sender (Android 14+ shares the
     * sending package when asked) — only when the platform's answer matches the claim.
     * Below Android 14 {@code verifiedSender} is null and the claim stands alone; the
     * worst a forged message can then do is make this app let go of the rig until the
     * operator next opens it.
     *
     * @param selfPackage    this app's package name
     * @param claimedFrom    {@link #EXTRA_FROM_PACKAGE} from the message, may be null
     * @param verifiedSender the platform-attested sending package, or null if unavailable
     * @param sentAtMs       {@link #EXTRA_SENT_AT_MS} from the message, 0 if absent
     * @param nowMs          current wall clock
     */
    public static boolean shouldHonor(String selfPackage, String claimedFrom,
                                      String verifiedSender, long sentAtMs, long nowMs) {
        if (claimedFrom == null || claimedFrom.equals(selfPackage)) return false;
        if (!FAMILY_PACKAGES.contains(claimedFrom)) return false;
        if (verifiedSender != null && !verifiedSender.equals(claimedFrom)) return false;
        return isFresh(sentAtMs, nowMs);
    }

    /**
     * Whether a message is recent enough to act on. Both apps read the same device clock,
     * so a stamp from the future (beyond a small tolerance) is as wrong as a stale one.
     */
    public static boolean isFresh(long sentAtMs, long nowMs) {
        if (sentAtMs <= 0) return false;
        long age = nowMs - sentAtMs;
        return age >= -MAX_MESSAGE_AGE_MS && age <= MAX_MESSAGE_AGE_MS;
    }

    /** Operator-facing name for a family package ("FT8AF"), falling back to the package. */
    public static String displayName(String pkg) {
        if (pkg == null) return "";
        int dot = pkg.lastIndexOf('.');
        if (!FAMILY_PACKAGES.contains(pkg) || dot < 0) return pkg;
        return pkg.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
    }
}
