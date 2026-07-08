package com.k1af.ft8af.ft8transmit;

/**
 * Tune method setting (issue #425): what the TUNE chip does when tapped.
 *
 * <ul>
 *   <li>{@link #AUTOMATIC} — use the rig's internal ATU when a CAT rig whose
 *       protocol has an ATU-start command is connected; otherwise fall back
 *       to the low-power carrier tone.</li>
 *   <li>{@link #INTERNAL} — always use the rig's internal ATU; if none is
 *       available, do nothing but tell the operator (no surprise carrier).</li>
 *   <li>{@link #TONE} — always the low-power carrier tone (pre-#425 behavior).</li>
 * </ul>
 *
 * ATU capability can't be queried over CAT, so "available" only means the
 * protocol defines the command; rigs without an ATU ignore it harmlessly.
 */
public final class TuneMethod {

    public static final int AUTOMATIC = 0;
    public static final int INTERNAL = 1;
    public static final int TONE = 2;

    /** What a TUNE tap should do, decided by {@link #decide}. */
    public static final int ACTION_CARRIER = 0;
    public static final int ACTION_RIG_ATU = 1;
    public static final int ACTION_UNAVAILABLE = 2;

    private TuneMethod() {
    }

    /** Clamp a persisted setting to a known method; unknown values mean AUTOMATIC. */
    public static int clamp(int method) {
        return (method == INTERNAL || method == TONE) ? method : AUTOMATIC;
    }

    /**
     * Decide what a TUNE tap does.
     *
     * @param method       one of AUTOMATIC / INTERNAL / TONE (unknown = AUTOMATIC)
     * @param atuAvailable a connected rig whose protocol has an ATU-start
     *                     command, with PTT idle
     */
    public static int decide(int method, boolean atuAvailable) {
        switch (clamp(method)) {
            case INTERNAL:
                return atuAvailable ? ACTION_RIG_ATU : ACTION_UNAVAILABLE;
            case TONE:
                return ACTION_CARRIER;
            default://AUTOMATIC
                return atuAvailable ? ACTION_RIG_ATU : ACTION_CARRIER;
        }
    }
}
