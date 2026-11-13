package com.k1af.ft8af;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-duplex (satellite) monitoring: decide what a decode cycle shows once the
 * operator is listening to their own downlink while transmitting.
 *
 * <p>Working a linear-transponder satellite is genuinely full duplex — the rig
 * receives the downlink the whole time it transmits on the uplink, so the
 * decoder hears our own signal come back. {@link OwnTxEchoFilter} normally
 * discards those decodes, because on a terrestrial station an own-callsign
 * decode can only be TX audio monitored back into line-in and showing it would
 * duplicate what the QSO panel already displays. On a satellite that same echo
 * is the measurement the operator needs:
 *
 * <ul>
 *   <li>its SNR is how strong our own signal comes back, which is how power is
 *       disciplined — overdriving a continuous mode like FT4 through a
 *       transponder suppresses everyone else for the whole slot;
 *   <li>its audio frequency, compared against the frequency we transmitted on,
 *       gives the uplink/downlink drift the RIT has to compensate for.
 * </ul>
 *
 * <p>This is display only. Own decodes are merged into the message list and the
 * waterfall labels and nothing else: they must never reach the auto-sequencer
 * (which would see us answering ourselves), the SWL database, PSKReporter, the
 * WSJT-X broadcast, or the clock-sync DT statistics — an echo's DT is the TX
 * chain latency, not clock error, which is exactly why
 * {@link OwnTxEchoFilter#meanTimeOffsetSec()} excludes it. Every consumer of the
 * displayed list that acts on a decode keys on {@link Ft8Message#isOwnEcho},
 * which {@link OwnTxEchoFilter} sets at the one place that decides "this is us".
 *
 * <p>Note that decoding and the waterfall already run through a transmission;
 * nothing gates them on TX state. The own-callsign filter was the only thing
 * standing between the operator and their own signal.
 */
public final class FullDuplexMonitor {
    /** Config-table key the toggle is persisted under. */
    public static final String CONFIG_KEY = "fullDuplex";

    private FullDuplexMonitor() {
    }

    /**
     * The messages to show for this pass: the kept decodes, plus our own
     * transmission's echoes when full duplex is on.
     *
     * <p>Returns {@code kept} itself when there is nothing to add, so the common
     * (feature-off) path allocates nothing and the caller keeps handing the
     * exact list it already had to the rest of the decode pipeline.
     *
     * @param kept    what survived {@link OwnTxEchoFilter} — never modified
     * @param echoes  the own-callsign decodes it dropped
     * @param enabled whether full-duplex monitoring is on
     * @return the display list; the same instance as {@code kept} when unchanged
     */
    public static ArrayList<Ft8Message> displayList(ArrayList<Ft8Message> kept,
                                                    List<Ft8Message> echoes,
                                                    boolean enabled) {
        if (!enabled || echoes == null || echoes.isEmpty()) {
            return kept;
        }
        ArrayList<Ft8Message> merged =
                new ArrayList<>(kept == null ? echoes.size() : kept.size() + echoes.size());
        if (kept != null) merged.addAll(kept);
        merged.addAll(echoes);
        return merged;
    }

    /**
     * Copy {@code messages} without our own transmission's echoes.
     *
     * <p>Used to keep echoes out of the SWL QSO scan once full duplex has put
     * them in the displayed message list — and they stay in that list after the
     * toggle is turned off, for as long as the list keeps them, which is why the
     * caller filters by what the list holds rather than by the live setting.
     * That scan walks the whole accumulated list looking for a station pair
     * exchanging reports; an echo carrying our callsign in the "from" field
     * would let it pair our own transmissions into a logged SWL QSO with
     * ourselves. With no echoes present the copy is exactly what the scan
     * always saw.
     */
    public static ArrayList<Ft8Message> withoutOwnEchoes(List<Ft8Message> messages) {
        ArrayList<Ft8Message> out = new ArrayList<>(messages == null ? 0 : messages.size());
        if (messages == null) return out;
        for (Ft8Message m : messages) {
            if (m == null || m.isOwnEcho) continue;
            out.add(m);
        }
        return out;
    }

    /** The echoes in a display list, in order; empty when it carries none. */
    public static ArrayList<Ft8Message> onlyOwnEchoes(List<Ft8Message> messages) {
        ArrayList<Ft8Message> out = new ArrayList<>();
        if (messages == null) return out;
        for (Ft8Message m : messages) {
            if (m != null && m.isOwnEcho) out.add(m);
        }
        return out;
    }
}
