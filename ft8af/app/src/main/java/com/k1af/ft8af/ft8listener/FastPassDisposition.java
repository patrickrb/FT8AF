package com.k1af.ft8af.ft8listener;

/**
 * How {@code MainViewModel.afterDecode} hands a FAST-pass decode to the QSO sequencer:
 * as this cycle's authoritative pass, or as evidence only.
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
 * happens within the first half second.
 *
 * <p>A late delivery is parsed <em>immediately</em>, not stashed for the next delivery.
 * It used to be stashed and replayed on the next delivery with TX idle — which, right
 * after key-up, is our own transmit slot's fast pass some 14 s later. That cost a whole
 * cycle: the partner's R-report decoded 0.3 s after key-up, we finished re-sending our
 * report, and only the following over carried the RR73. Worse, it made the mid-cycle TX
 * swap ({@code FT8TransmitSignal.shouldRestartForNewOrder}) unreachable: the swap fires
 * from the parse that advances the QSO, and nothing was parsed while transmitting.
 * Parsing at once lets the sequencer advance the order while the old over is still on
 * the air and, inside the audio slack, replace it with the right message.
 *
 * <p>Evidence-only is still the correct mode for a late delivery: it answers a station
 * calling us and advances the QSO, but suppresses absence-of-evidence decisions (no-reply
 * counting, give-up, queue rotation) — the cycle they belong to has already keyed. The
 * load-bearing detail is that {@code FT8TransmitSignal.parseMessageToFunctionInner} calls
 * {@code checkCQMeOrFollowCQMessage} <em>above</em> its {@code evidenceOnly} guard — if that
 * call ever moves below the guard, a late decode silently stops answering callers.
 */
public final class FastPassDisposition {

    private FastPassDisposition() {}

    /** How to hand this fast-pass delivery to the sequencer. */
    public enum Action {
        /** The cycle's authoritative pass — it can still key up this cycle. */
        PARSE,
        /**
         * Too late to decide this cycle; parse now as positive evidence only (advance,
         * answer callers, mid-cycle swap) without the cycle's absence decisions.
         */
        EVIDENCE_ONLY
    }

    /**
     * Decide the disposition of one fast-pass delivery.
     *
     * <p>Ordering matters: an in-flight transmission is checked first, because once we are
     * keyed the reply budget is irrelevant — this cycle's decision has been made, and the
     * decode can only amend it (via the mid-cycle swap) or inform the next one.
     *
     * @param transmitting whether TX has already keyed for this cycle
     * @param replyCostMs  decode elapsed time plus the PTT and transmit delays — what it
     *                     would cost to still get a reply out this cycle
     * @param budgetMs     the auto-reply budget (scales with the late-start tolerance)
     */
    public static Action decide(boolean transmitting, long replyCostMs, long budgetMs) {
        if (transmitting) return Action.EVIDENCE_ONLY;
        if (replyCostMs > budgetMs) return Action.EVIDENCE_ONLY;
        return Action.PARSE;
    }
}
