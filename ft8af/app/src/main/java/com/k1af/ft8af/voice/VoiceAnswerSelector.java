package com.k1af.ft8af.voice;

import java.util.List;

/**
 * Pure, Android-free candidate selection for the "answer" voice command:
 * which decoded message should {@code MainViewModel.callStation} be handed?
 *
 * <p>Preference order:
 * <ol>
 *   <li>The newest decode addressed to my callsign (a station actively
 *       calling me right now).</li>
 *   <li>Else the newest decode from the head of the caller queue (a station
 *       that called me earlier and is waiting its turn).</li>
 *   <li>Else null — nothing to answer.</li>
 * </ol>
 *
 * <p>Generic over the message type (with tiny accessor interfaces instead of
 * {@code java.util.function} — minSdk 23, no core-library desugaring) so the
 * selection logic is testable without Android's {@code Ft8Message}.
 */
public final class VoiceAnswerSelector {
    private VoiceAnswerSelector() {}

    /** Whether a message is addressed to my callsign. */
    public interface AddressedToMe<T> {
        boolean test(T message);
    }

    /** The sender callsign of a message (may be null/empty for junk rows). */
    public interface SenderOf<T> {
        String get(T message);
    }

    /**
     * @param decodesOldestFirst the decode list in arrival order (the app's
     *                           ft8Messages list appends newest last)
     * @param addressedToMe      resolved by the caller via checkIsMyCallsign
     * @param senderOf           sender-callsign accessor
     * @param queueHeadCallsign  callsign at the head of the caller queue, or
     *                           null when the queue is empty
     * @return the message to answer, or null when there is no candidate
     */
    public static <T> T pick(List<T> decodesOldestFirst,
                             AddressedToMe<T> addressedToMe,
                             SenderOf<T> senderOf,
                             String queueHeadCallsign) {
        if (decodesOldestFirst == null || decodesOldestFirst.isEmpty()) return null;

        // Newest-first scan for a station calling me.
        for (int i = decodesOldestFirst.size() - 1; i >= 0; i--) {
            T msg = decodesOldestFirst.get(i);
            if (msg == null) continue;
            if (!hasSender(senderOf.get(msg))) continue;
            if (addressedToMe.test(msg)) return msg;
        }

        // Fall back to the queued caller's newest decode.
        if (queueHeadCallsign != null && !queueHeadCallsign.trim().isEmpty()) {
            for (int i = decodesOldestFirst.size() - 1; i >= 0; i--) {
                T msg = decodesOldestFirst.get(i);
                if (msg == null) continue;
                String sender = senderOf.get(msg);
                if (!hasSender(sender)) continue;
                if (sender.trim().equalsIgnoreCase(queueHeadCallsign.trim())) return msg;
            }
        }
        return null;
    }

    private static boolean hasSender(String sender) {
        return sender != null && !sender.trim().isEmpty();
    }
}
