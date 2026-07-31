package com.k1af.ft8af.ft8listener;

/**
 * What {@code MainViewModel.afterDecode} does with a FAST-pass decode: act on it now, or
 * stash it for replay on the next delivery.
 *
 * <p>Before this existed the answer was "act on it now, or throw it away". Two conditions
 * dropped it silently — the transmitter had already keyed, or the decode had eaten the
 * auto-reply budget — and neither was logged. Measured on the 2026-07-31 activation:
 * <strong>34 of the 66 cycles where a station called us (52%) were discarded that way</strong>.
 * They decoded correctly ({@code replyToMe=true} in the log) and the sequencer never saw
 * them, so the operator kept calling CQ at people who were answering, and had to pick
 * callers by hand.
 *
 * <p>The timing is unforgiving by construction: a fast decode is delivered about
 * {@code earlyDecodeMillis} plus decode time into the slot, so a ~2 s decode puts delivery
 * a few hundred milliseconds PAST the slot boundary — and therefore past key-up, which
 * happens within the first half second. Deep passes landing in exactly that window were
 * already stashed and replayed; the fast pass, the one carrying the timely reply, was not.
 *
 * <p>Stashing is never wrong here: replay runs through the evidence-only parse, which still
 * answers a station calling us but suppresses absence-of-evidence decisions (no-reply
 * counting, give-up). Those belong to the cycle that already made them. The load-bearing
 * detail is that {@code FT8TransmitSignal.parseMessageToFunctionInner} calls
 * {@code checkCQMeOrFollowCQMessage} <em>above</em> its {@code evidenceOnly} guard — if that
 * call ever moves below the guard, a replayed decode silently stops answering callers and
 * this whole mechanism goes quiet again.
 */
public final class FastPassDisposition {

    private FastPassDisposition() {}

    /** What to do with this fast-pass delivery. */
    public enum Action {
        /** Hand it to the sequencer now — it can still key up this cycle. */
        PARSE,
        /** Too late to act on this cycle; keep it for replay as evidence next cycle. */
        STASH
    }

    /**
     * Decide the disposition of one fast-pass delivery.
     *
     * <p>Ordering matters: an in-flight transmission is checked first, because once we are
     * keyed the reply budget is irrelevant — no decode, however quick, can change what is
     * already going out over the air.
     *
     * @param transmitting whether TX has already keyed for this cycle
     * @param replyCostMs  decode elapsed time plus the PTT and transmit delays — what it
     *                     would cost to still get a reply out this cycle
     * @param budgetMs     the auto-reply budget (scales with the late-start tolerance)
     */
    public static Action decide(boolean transmitting, long replyCostMs, long budgetMs) {
        if (transmitting) return Action.STASH;
        if (replyCostMs > budgetMs) return Action.STASH;
        return Action.PARSE;
    }
}
