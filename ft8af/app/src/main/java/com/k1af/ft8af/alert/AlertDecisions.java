package com.k1af.ft8af.alert;

import java.util.Set;

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
     * Claim the right to post an alert for {@code dedupKey}, returning {@code true} only when
     * the caller should proceed to post.
     *
     * <p>The permission gate ({@code canPost}) is evaluated <b>before</b> the dedup set is
     * touched. Consuming the key while notifications are denied would permanently suppress the
     * station: {@code alerted} lives for the whole process and is never cleared, so once the
     * key is burned the same station never fires again this session — even after the user
     * grants POST_NOTIFICATIONS. Gate first, then dedup, so a station first heard during the
     * permission-denied window still alerts on its next decode once permission is granted.
     *
     * @param alerted  per-session set of already-claimed dedup keys; mutated only when this
     *                 returns {@code true}
     * @param dedupKey namespaced key for this alert
     * @param canPost  whether a notification can actually be posted right now (permission
     *                 present / pre-Android-13)
     */
    public static boolean claimAlert(Set<String> alerted, String dedupKey, boolean canPost) {
        if (!canPost) return false;          // gate FIRST — do not burn the key while denied
        return alerted.add(dedupKey);        // consume the key only when we can post
    }

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

    /**
     * Watchlist alert — fire when a decoded station matches the user's callsign
     * watchlist (a rare DXpedition prefix, a needed call, a friend). Unlike the
     * needed-DX alerts this is not CQ-gated: the point is to know the instant the
     * station is on the air, whatever it's transmitting.
     *
     * @param hasWatchlist whether the user has any watchlist entries (the feature is
     *                     active purely by having a non-empty list — no separate toggle)
     * @param matched      whether the decoded sender matches a watchlist entry
     *                     (resolved by the caller via {@code checkIsWatchedCallsign})
     * @param blocked      whether the message is filtered by the user's block list
     */
    public static boolean shouldAlertWatch(boolean hasWatchlist, boolean matched, boolean blocked) {
        return hasWatchlist && matched && !blocked;
    }

    /** Dedup key for a watchlist alert: alert once per session per watched station. */
    public static String watchDedupKey(String fromCallsign) {
        return "WATCH:" + norm(fromCallsign);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
