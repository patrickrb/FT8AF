package com.k1af.ft8af.alert;

/**
 * Pure, Android-free decision + dedup logic for {@link DxAlertNotifier}, extracted so the
 * branching can be unit-tested without a device (the notifier itself touches
 * NotificationManager and can't be).
 *
 * <p>Two alert kinds are gated here:
 * <ul>
 *   <li><b>CQ reply</b> — fire when a decoded message is addressed to the user's callsign
 *       (someone calling them). Own-TX echoes are filtered upstream before the notifier
 *       sees them, so an in-list message to my call is always another station.</li>
 *   <li><b>QSO complete</b> — fire once when a contact is logged.</li>
 * </ul>
 *
 * <p>Dedup keys are namespaced strings collected in a per-session set; {@code DxAlertNotifier}
 * only posts a notification the first time it sees a given key.
 */
public final class AlertDecisions {
    private AlertDecisions() {}

    /**
     * @param enabled       the {@code alertOnCqReply} user setting
     * @param addressedToMe whether the decoded message's target callsign is mine
     *                      (resolved by the caller via {@code checkIsMyCallsign})
     * @param blocked       whether the message is filtered by the user's block list
     */
    public static boolean shouldAlertCqReply(boolean enabled, boolean addressedToMe, boolean blocked) {
        return enabled && addressedToMe && !blocked;
    }

    /** Dedup key for a CQ-reply alert: distinct sender + message text alert once per session. */
    public static String cqReplyDedupKey(String fromCallsign, String messageText) {
        return "CQREPLY:" + norm(fromCallsign) + "|" + norm(messageText);
    }

    /**
     * Dedup key for a QSO-complete alert: one per worked station + completion time.
     * {@code endTime} is the log record's end-time token (date-time string).
     */
    public static String qsoCompleteDedupKey(String toCallsign, String endTime) {
        return "QSO:" + norm(toCallsign) + "|" + norm(endTime);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
