package com.k1af.ft8af;

import java.util.ArrayList;
import java.util.List;

/**
 * Cleans up a freshly decoded FT8 cycle: it splits the messages worth keeping
 * from the two kinds that must not reach the UI — own-TX loopback echoes and
 * junk/false decodes.
 *
 * <p>When the transceiver monitors its TX audio back into the app's line-in,
 * the decoder hears and decodes our <em>own</em> transmission. A decode whose
 * sender is our own callsign can only be that loopback — you never legitimately
 * receive your own callsign in the "from" field — so it has to be removed before
 * it reaches the message list, the QSO conversation panel, or the SWL database.
 * The QSO panel already shows what we transmit via its synthesized key-up entry,
 * and PSKReporter / the auto-sequence already ignore own-callsign messages.
 *
 * <p>FT8's 14-bit CRC also lets roughly one noise event in 16k pass as a "valid"
 * decode; those false frames render as garbage callsigns like {@code ".<. >"}
 * (see {@link Ft8Message#isJunkDecode()}) and are dropped here too so a bogus
 * station never appears in the list.
 *
 * <p>The result also carries diagnostic counters used to investigate the
 * separate "missing other station responses" report: how many echoes and how
 * much junk were dropped, and whether any message addressed to us survived this
 * cycle.
 */
public final class OwnTxEchoFilter {
    /** Messages to keep — own-TX loopback echoes and junk decodes removed. */
    public final ArrayList<Ft8Message> kept;
    /** Number of own-callsign loopback echoes that were dropped. */
    public final int ownEchoCount;
    /** Number of junk/false decodes (garbage sender callsign) that were dropped. */
    public final int junkCount;
    /** True if a kept message was addressed to our callsign (a reply to us). */
    public final boolean replyToMePresent;

    private OwnTxEchoFilter(ArrayList<Ft8Message> kept, int ownEchoCount,
                            int junkCount, boolean replyToMePresent) {
        this.kept = kept;
        this.ownEchoCount = ownEchoCount;
        this.junkCount = junkCount;
        this.replyToMePresent = replyToMePresent;
    }

    /**
     * Filter one cycle's decode list.
     *
     * <p>A message is dropped when it is a {@link Ft8Message#isJunkDecode() junk
     * decode} (CRC-collision garbage with an implausible sender), or when its
     * sender ({@link Ft8Message#getCallsignFrom()}) matches our own callsign per
     * {@link GeneralVariables#checkIsMyCallsign}. Both callsign accessors return
     * "" rather than null, so this is null-safe.
     *
     * @param decoded the raw decode list for one cycle (not modified)
     * @return the kept messages plus echo/junk/reply diagnostics
     */
    public static OwnTxEchoFilter filter(List<Ft8Message> decoded) {
        ArrayList<Ft8Message> kept = new ArrayList<>(decoded.size());
        int ownEcho = 0;
        int junk = 0;
        boolean replyToMe = false;
        for (Ft8Message m : decoded) {
            if (m.isJunkDecode()) {// false decode with a garbage sender callsign
                junk++;
                continue;
            }
            if (GeneralVariables.checkIsMyCallsign(m.getCallsignFrom())) {
                ownEcho++;
                continue;
            }
            if (GeneralVariables.checkIsMyCallsign(m.getCallsignTo())) {
                replyToMe = true;
            }
            kept.add(m);
        }
        return new OwnTxEchoFilter(kept, ownEcho, junk, replyToMe);
    }

    /**
     * Format the per-cycle diagnostic line written to debug.log. Recording the
     * kept count, dropped-echo count, dropped-junk count, whether a reply
     * addressed to us survived, and the slot lets us tell "decoded but
     * mis-rendered" from "never decoded" when investigating the "missing other
     * station responses" report.
     *
     * @param sequential the FT8 slot (0/1) of this decode cycle
     * @return the log line, e.g. {@code "DECODE: kept=3 ownEcho=1 junk=0 replyToMe=true slot=0"}
     */
    public String decodeLogLine(int sequential) {
        return String.format("DECODE: kept=%d ownEcho=%d junk=%d replyToMe=%b slot=%d",
                kept.size(), ownEchoCount, junkCount, replyToMePresent, sequential);
    }
}
