package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.ModeProfile;
import com.k1af.ft8af.timer.OnUtcTimer;
import com.k1af.ft8af.timer.UtcTimer;

import org.junit.Test;

/**
 * Coverage for the two pure audio helpers on {@link FT8TransmitSignal} that
 * shape the outgoing TX waveform:
 *   - {@link FT8TransmitSignal#applyVolume(float[], int, int, float)} — the
 *     software volume scaling that both the AudioTrack and USB-direct output
 *     paths now share. This is the fix for the rig overdriving regardless of
 *     the slider (AudioTrack.setVolume() is a no-op on USB audio routes), so a
 *     volume of 0 MUST produce digital silence.
 *   - {@link FT8TransmitSignal#float2Short(float[])} — float→16-bit PCM
 *     conversion with hard clipping and the 8-sample zero tail used for
 *     RP2040 audio-detection compatibility.
 *
 * Plain JUnit: FT8TransmitSignal's static initialiser swallows the
 * {@code System.loadLibrary("ft8af")} UnsatisfiedLinkError, so the class loads
 * on the bare JVM. Neither helper touches JNI or Android framework classes.
 */
public class FT8TransmitSignalTest {

    private static final float TOL = 1e-6f;

    // ---- applyVolume --------------------------------------------------------

    @Test
    public void applyVolume_unityGain_isIdentity() {
        float[] src = {-1.0f, -0.25f, 0.0f, 0.5f, 1.0f};
        float[] out = FT8TransmitSignal.applyVolume(src, 0, src.length, 1.0f);
        assertThat(out).usingTolerance(TOL).containsExactly(src).inOrder();
    }

    @Test
    public void applyVolume_zeroGain_isDigitalSilence() {
        // The whole point of the bug fix: slider at 0% must mute, not overdrive.
        float[] src = {-1.0f, -0.5f, 0.3f, 0.9f, 1.0f};
        float[] out = FT8TransmitSignal.applyVolume(src, 0, src.length, 0.0f);
        for (float v : out) {
            // Negative inputs * 0 produce -0.0f; isWithin treats +0.0/-0.0 as silence.
            assertThat(v).isWithin(0.0f).of(0.0f);
        }
        assertThat(out).hasLength(src.length);
    }

    @Test
    public void applyVolume_halfGain_scalesEverySample() {
        float[] src = {-1.0f, 0.0f, 0.5f, 1.0f};
        float[] out = FT8TransmitSignal.applyVolume(src, 0, src.length, 0.5f);
        assertThat(out).usingTolerance(TOL)
                .containsExactly(new float[]{-0.5f, 0.0f, 0.25f, 0.5f}).inOrder();
    }

    @Test
    public void applyVolume_honoursSkipSamples() {
        // skipSamples drops leading samples (late-start trim); output is the tail.
        float[] src = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f};
        float[] out = FT8TransmitSignal.applyVolume(src, 2, src.length - 2, 1.0f);
        assertThat(out).usingTolerance(TOL)
                .containsExactly(new float[]{0.3f, 0.4f, 0.5f}).inOrder();
    }

    @Test
    public void applyVolume_negativeSkip_isClampedToZero() {
        float[] src = {0.1f, 0.2f, 0.3f};
        float[] out = FT8TransmitSignal.applyVolume(src, -5, src.length, 1.0f);
        assertThat(out).usingTolerance(TOL)
                .containsExactly(new float[]{0.1f, 0.2f, 0.3f}).inOrder();
    }

    @Test
    public void applyVolume_outputLengthIsPlayLength() {
        float[] src = new float[10];
        float[] out = FT8TransmitSignal.applyVolume(src, 3, 7, 0.8f);
        assertThat(out).hasLength(7);
    }

    // ---- float2Short --------------------------------------------------------

    @Test
    public void float2Short_appendsEightZeroSamples() {
        short[] out = FT8TransmitSignal.float2Short(new float[]{0.0f, 0.0f});
        assertThat(out).hasLength(2 + 8);
        for (int i = 2; i < out.length; i++) {
            assertThat((int) out[i]).isEqualTo(0);
        }
    }

    @Test
    public void float2Short_emptyInput_isJustTheZeroTail() {
        short[] out = FT8TransmitSignal.float2Short(new float[0]);
        assertThat(out).hasLength(8);
        for (short s : out) {
            assertThat((int) s).isEqualTo(0);
        }
    }

    @Test
    public void float2Short_scalesFullScaleAndMidScale() {
        short[] out = FT8TransmitSignal.float2Short(new float[]{1.0f, -1.0f, 0.0f, 0.5f});
        assertThat((int) out[0]).isEqualTo(32767);   // +1.0 * 32767
        assertThat((int) out[1]).isEqualTo(-32767);  // -1.0 * 32767
        assertThat((int) out[2]).isEqualTo(0);
        assertThat((int) out[3]).isEqualTo(16383);   // (short)(0.5 * 32767.0) truncates
    }

    @Test
    public void float2Short_clipsOutOfRangeInput() {
        short[] out = FT8TransmitSignal.float2Short(new float[]{2.5f, -3.0f});
        assertThat((int) out[0]).isEqualTo(32767);   // clipped to +1.0
        assertThat((int) out[1]).isEqualTo(-32767);  // clipped to -1.0
    }

    // ---- floatToInt16NoPad --------------------------------------------------
    // Used by the chunked MODE_STREAM playback loop: converts each chunk with NO
    // trailing zero pad (the 8-sample QP-7C pad is appended once at the end of the
    // message, not per chunk — see float2Short which keeps the pad for other uses).

    @Test
    public void floatToInt16NoPad_hasNoZeroTail() {
        short[] out = FT8TransmitSignal.floatToInt16NoPad(new float[]{1.0f, -1.0f}, 2);
        assertThat(out).hasLength(2);                // exactly length, no +8 pad
        assertThat((int) out[0]).isEqualTo(32767);
        assertThat((int) out[1]).isEqualTo(-32767);
    }

    @Test
    public void floatToInt16NoPad_honoursLengthShorterThanBuffer() {
        // The loop passes chunkLen which can be < buffer.length on the last chunk.
        short[] out = FT8TransmitSignal.floatToInt16NoPad(new float[]{0.5f, 0.5f, 0.5f}, 2);
        assertThat(out).hasLength(2);
        assertThat((int) out[0]).isEqualTo(16383);
        assertThat((int) out[1]).isEqualTo(16383);
    }

    @Test
    public void floatToInt16NoPad_clipsOutOfRange() {
        short[] out = FT8TransmitSignal.floatToInt16NoPad(new float[]{2.0f, -2.0f}, 2);
        assertThat((int) out[0]).isEqualTo(32767);
        assertThat((int) out[1]).isEqualTo(-32767);
    }

    // ---- per-chunk slicing (the real-time volume path) ----------------------
    // The streaming loop calls applyVolume(buffer, skipSamples + offset, chunkLen,
    // currentVolume) repeatedly. These prove the offset math is sound and that
    // changing the volume between chunks attenuates only the later chunks — the
    // whole point of the feature (pull the slider down mid-over to protect the rig).

    @Test
    public void chunkedSlicing_concatenatesToWholeBufferAtFixedVolume() {
        float[] src = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f};
        int skip = 1;                       // simulate a late-start trim
        int playLength = src.length - skip; // 6 samples to emit
        int chunk = 4;
        float vol = 0.5f;

        float[] whole = FT8TransmitSignal.applyVolume(src, skip, playLength, vol);

        // Re-assemble by chunked slicing the way the playback loop does.
        float[] assembled = new float[playLength];
        int pos = 0;
        for (int offset = 0; offset < playLength; offset += chunk) {
            int len = Math.min(chunk, playLength - offset);
            float[] c = FT8TransmitSignal.applyVolume(src, skip + offset, len, vol);
            System.arraycopy(c, 0, assembled, pos, len);
            pos += len;
        }
        assertThat(assembled).usingTolerance(TOL).containsExactly(whole).inOrder();
    }

    @Test
    public void chunkedSlicing_volumeChangeAffectsOnlyLaterChunks() {
        float[] src = {1.0f, 1.0f, 1.0f, 1.0f};
        // First two samples at full volume, then slider dropped to 0.25 for the rest.
        float[] first = FT8TransmitSignal.applyVolume(src, 0, 2, 1.0f);
        float[] second = FT8TransmitSignal.applyVolume(src, 2, 2, 0.25f);

        assertThat(first).usingTolerance(TOL)
                .containsExactly(new float[]{1.0f, 1.0f}).inOrder();
        assertThat(second).usingTolerance(TOL)
                .containsExactly(new float[]{0.25f, 0.25f}).inOrder();
    }

    // ---- shouldStopAfterQso -------------------------------------------------
    // After tapping a single CQ in the decode list to work one station, the run
    // must STOP once that QSO completes — it must not keep calling CQ or hunting
    // unless the operator has Hunt or Auto-CQ-after-QSO enabled. Pure decision
    // logic extracted from parseMessageToFunction so it can be tested directly.

    @Test
    public void shouldStop_singleTappedQso_huntAndAutoCqOff_stops() {
        // The core request: tap a CQ, finish the QSO, then stop.
        assertThat(FT8TransmitSignal.shouldStopAfterQso(
                /*continued*/ false, /*autoCQAfterQSO*/ false,
                /*autoFollowCQ*/ false, /*singleQsoMode*/ true)).isTrue();
    }

    @Test
    public void shouldStop_singleTappedQso_huntOn_keepsRunning() {
        // HUNT enabled -> keep hunting the next CQ after the tapped QSO.
        assertThat(FT8TransmitSignal.shouldStopAfterQso(
                false, false, /*autoFollowCQ*/ true, true)).isFalse();
    }

    @Test
    public void shouldStop_singleTappedQso_autoCqOn_keepsRunning() {
        // Auto-CQ after QSO enabled -> keep calling CQ after the tapped QSO.
        assertThat(FT8TransmitSignal.shouldStopAfterQso(
                false, /*autoCQAfterQSO*/ true, false, true)).isFalse();
    }

    @Test
    public void shouldStop_singleTappedQso_callerQueued_keepsRunning() {
        // A queued caller / Hunt target already picked up the next contact, so
        // answer them rather than stopping — even with Hunt & Auto-CQ off.
        assertThat(FT8TransmitSignal.shouldStopAfterQso(
                /*continued*/ true, false, false, true)).isFalse();
    }

    @Test
    public void shouldStop_notSingleQso_neverStops() {
        // A run that did not start as a tapped QSO (e.g. pressing CQ) is never
        // stopped by this rule, regardless of the other flags.
        assertThat(FT8TransmitSignal.shouldStopAfterQso(
                false, false, false, /*singleQsoMode*/ false)).isFalse();
        assertThat(FT8TransmitSignal.shouldStopAfterQso(
                true, true, true, false)).isFalse();
    }

    // ---- mayAutoCall --------------------------------------------------------
    // The give-up fallback after a no-reply manual QSO must not chase other CQs unless an
    // auto-call mode is on.

    @Test
    public void mayAutoCall_huntOn_answersAnyCq() {
        assertThat(FT8TransmitSignal.mayAutoCall(true, false, false)).isTrue();
        assertThat(FT8TransmitSignal.mayAutoCall(true, false, true)).isTrue();
    }

    @Test
    public void mayAutoCall_huntOff_followOn_onlyFollowed() {
        assertThat(FT8TransmitSignal.mayAutoCall(false, true, true)).isTrue();
        assertThat(FT8TransmitSignal.mayAutoCall(false, true, false)).isFalse();
    }

    @Test
    public void mayAutoCall_bothOff_neverAnswers() {
        // The reported bug: manual QSO, no reply, Hunt off -> must NOT answer other CQs.
        assertThat(FT8TransmitSignal.mayAutoCall(false, false, false)).isFalse();
        assertThat(FT8TransmitSignal.mayAutoCall(false, false, true)).isFalse();
    }

    // ---- isHuntListeningIdle ------------------------------------------------
    // Hunt arms the sequencer so it CAN reply, but it answers others' CQs and never calls
    // CQ itself: while idle (CQ baseline, no target locked) the TX slot must stay silent.

    private static TransmitCallsign cqBaseline() {
        return new TransmitCallsign(1, 0, "CQ", 0);
    }

    private static TransmitCallsign station(String call) {
        return new TransmitCallsign(1, 0, call, 0);
    }

    @Test
    public void huntIdle_armedWithCqBaseline_isListeningOnly() {
        // Hunt on, CQ state (order 6), target is the CQ placeholder -> stay silent.
        assertThat(FT8TransmitSignal.isHuntListeningIdle(true, 6, cqBaseline())).isTrue();
        // Null target (never set) is also idle.
        assertThat(FT8TransmitSignal.isHuntListeningIdle(true, 6, null)).isTrue();
    }

    @Test
    public void huntIdle_lockedOntoStation_transmitsReply() {
        // A real caller is locked: order advanced and target is a station -> not idle.
        assertThat(FT8TransmitSignal.isHuntListeningIdle(true, 1, station("K1ABC"))).isFalse();
        // Even at order 6, a real target means we're answering, not idle-listening.
        assertThat(FT8TransmitSignal.isHuntListeningIdle(true, 6, station("K1ABC"))).isFalse();
    }

    @Test
    public void huntIdle_huntOff_neverSuppresses() {
        // Hunt off: a normal user CQ run (order 6, "CQ") must transmit as usual.
        assertThat(FT8TransmitSignal.isHuntListeningIdle(false, 6, cqBaseline())).isFalse();
        assertThat(FT8TransmitSignal.isHuntListeningIdle(false, 6, null)).isFalse();
        assertThat(FT8TransmitSignal.isHuntListeningIdle(false, 1, station("K1ABC"))).isFalse();
    }

    // ---- shouldResetTargetOnSlotToggle --------------------------------------
    // When the operator toggles TX1 <-> TX2 while mid-QSO, the target callsign
    // must reset to CQ — switching slots abandons the contact. When already at
    // CQ baseline (target is "CQ", null, or empty) there is nothing to abandon,
    // so no reset is needed and we avoid the side-effects of userResetToCQ
    // (clearing the caller queue, setting pendingUserCQ, etc.).
    //
    // The decision is based solely on the target callsign, not functionOrder,
    // because resetToCQ() does not reliably post mutableFunctionOrder — callers
    // reading the LiveData can see a stale order while the target is already CQ.

    @Test
    public void slotToggle_realTarget_resetsTarget() {
        assertThat(FT8TransmitSignal.shouldResetTargetOnSlotToggle("K1ABC")).isTrue();
        assertThat(FT8TransmitSignal.shouldResetTargetOnSlotToggle("W3XYZ")).isTrue();
        assertThat(FT8TransmitSignal.shouldResetTargetOnSlotToggle("VE3ABC")).isTrue();
        assertThat(FT8TransmitSignal.shouldResetTargetOnSlotToggle("JA1XX")).isTrue();
    }

    @Test
    public void slotToggle_cqBaseline_noReset() {
        assertThat(FT8TransmitSignal.shouldResetTargetOnSlotToggle("CQ")).isFalse();
        assertThat(FT8TransmitSignal.shouldResetTargetOnSlotToggle("cq")).isFalse();
        assertThat(FT8TransmitSignal.shouldResetTargetOnSlotToggle("CQ ")).isFalse();
        assertThat(FT8TransmitSignal.shouldResetTargetOnSlotToggle(null)).isFalse();
        assertThat(FT8TransmitSignal.shouldResetTargetOnSlotToggle("")).isFalse();
        assertThat(FT8TransmitSignal.shouldResetTargetOnSlotToggle("  ")).isFalse();
    }

    // ---- huntFilterExcludes -------------------------------------------------
    // Couples the "CQ POTA" decode filter to Hunt: with the filter active
    // (huntPotaOnly), the auto-call scans must skip any CQ that isn't a POTA CQ
    // so Hunt never calls a general station. Issue #333.

    @Test
    public void huntFilter_potaOnlyOff_excludesNothing() {
        // No POTA filter: every CQ is eligible regardless of whether it's POTA.
        assertThat(FT8TransmitSignal.huntFilterExcludes(false, false)).isFalse();
        assertThat(FT8TransmitSignal.huntFilterExcludes(false, true)).isFalse();
    }

    @Test
    public void huntFilter_potaOnlyOn_excludesNonPota() {
        // The bug fix: with "CQ POTA" selected, a non-POTA CQ must be skipped.
        assertThat(FT8TransmitSignal.huntFilterExcludes(true, false)).isTrue();
    }

    @Test
    public void huntFilter_potaOnlyOn_keepsPota() {
        // A genuine POTA CQ stays eligible when the filter is active.
        assertThat(FT8TransmitSignal.huntFilterExcludes(true, true)).isFalse();
    }

    // ---- shouldFollowTargetFreq ---------------------------------------------
    // TX=RX (synFrequency) mode moves our TX offset onto the station we answer.
    // "Hold TX freq" (WSJT-X Hold Tx Freq) must override that and keep us on our own
    // offset. So we follow the target ONLY when synFrequency is on AND hold is off.

    @Test
    public void followTarget_splitOn_holdOff_follows() {
        assertThat(FT8TransmitSignal.shouldFollowTargetFreq(
                /*synFrequency*/ true, /*holdTxFreq*/ false)).isTrue();
    }

    @Test
    public void followTarget_splitOn_holdOn_holds() {
        // The reported request: keep my TX offset even with split on.
        assertThat(FT8TransmitSignal.shouldFollowTargetFreq(true, true)).isFalse();
    }

    @Test
    public void followTarget_splitOff_neverFollows() {
        // Without split there's nothing to follow, hold flag irrelevant.
        assertThat(FT8TransmitSignal.shouldFollowTargetFreq(false, false)).isFalse();
        assertThat(FT8TransmitSignal.shouldFollowTargetFreq(false, true)).isFalse();
    }

    // ---- shouldStopAfterOneShot ---------------------------------------------
    // Free text is a one-shot (WSJT-X Tx5): it transmits once and then the
    // sequencer stops, rather than repeating every cycle like a CQ. The auto-stop
    // must fire ONLY for a free-text one-shot send, never for a normal CQ run or a
    // persistent free-text mode.

    @Test
    public void oneShot_freeTextOneShot_stops() {
        // The reported request: free text sends once, then stop.
        assertThat(FT8TransmitSignal.shouldStopAfterOneShot(
                /*transmitFreeText*/ true, /*freeTextOneShot*/ true)).isTrue();
    }

    @Test
    public void oneShot_standardCq_neverStops() {
        // A normal CQ run (no free text) must keep repeating each cycle.
        assertThat(FT8TransmitSignal.shouldStopAfterOneShot(false, false)).isFalse();
    }

    @Test
    public void oneShot_freeTextWithoutOneShotFlag_neverStops() {
        // Free text armed but not flagged one-shot (defensive): don't auto-stop.
        assertThat(FT8TransmitSignal.shouldStopAfterOneShot(true, false)).isFalse();
    }

    @Test
    public void oneShot_flagSetButNotFreeText_neverStops() {
        // The one-shot flag without an active free-text message can't trigger a stop.
        assertThat(FT8TransmitSignal.shouldStopAfterOneShot(false, true)).isFalse();
    }

    // ---- isCallsignReadyToTransmit -------------------------------------------
    // The guard setTransmitting/transmitNow apply before keying. sendFreeTextOnce
    // must apply the SAME predicate before arming its one-shot (issue #401), or a
    // blocked transmission leaves the free text armed to leak into the next over.

    @Test
    public void callsignReady_realCalls_ready() {
        assertThat(FT8TransmitSignal.isCallsignReadyToTransmit("K1AF")).isTrue();
        assertThat(FT8TransmitSignal.isCallsignReadyToTransmit("2E0ABC")).isTrue();
        // Exactly the 3-character minimum the keying guard enforces.
        assertThat(FT8TransmitSignal.isCallsignReadyToTransmit("K1A")).isTrue();
    }

    @Test
    public void callsignReady_shortOrMissing_notReady() {
        assertThat(FT8TransmitSignal.isCallsignReadyToTransmit("K1")).isFalse();
        assertThat(FT8TransmitSignal.isCallsignReadyToTransmit("")).isFalse();
        assertThat(FT8TransmitSignal.isCallsignReadyToTransmit(null)).isFalse();
    }

    // ---- tuneBlockReason (issue #408) ----------------------------------------
    // The gate for starting the tune carrier. Ordered by severity: TX beats the
    // protections, protections beat route support — and tune must never become
    // a backdoor around a TX inhibit (SWR lockout, WSPR blacklist).

    @Test
    public void tune_allClear_mayStart() {
        assertThat(FT8TransmitSignal.tuneBlockReason(
                false, false, false, false, false, false))
                .isEqualTo(FT8TransmitSignal.TuneBlockReason.NONE);
    }

    @Test
    public void tune_txActiveOrArmed_blocks() {
        assertThat(FT8TransmitSignal.tuneBlockReason(
                true, false, false, false, false, false))
                .isEqualTo(FT8TransmitSignal.TuneBlockReason.TX_ACTIVE);
        // TX outranks every other reason.
        assertThat(FT8TransmitSignal.tuneBlockReason(
                true, true, true, true, true, true))
                .isEqualTo(FT8TransmitSignal.TuneBlockReason.TX_ACTIVE);
    }

    @Test
    public void tune_swrLockout_blocks() {
        assertThat(FT8TransmitSignal.tuneBlockReason(
                false, true, false, false, false, false))
                .isEqualTo(FT8TransmitSignal.TuneBlockReason.SWR_LOCKED);
    }

    @Test
    public void tune_wsprFrequency_blocks() {
        // The WSPR sub-band TX inhibit applies to tune too.
        assertThat(FT8TransmitSignal.tuneBlockReason(
                false, false, true, false, false, false))
                .isEqualTo(FT8TransmitSignal.TuneBlockReason.WSPR_FREQUENCY);
    }

    @Test
    public void tune_unsupportedRoutes_block() {
        // MVP: AudioTrack sink only; CAT-audio (truSDX), network rigs, and the
        // USB-direct path are Phase-2 follow-ups.
        assertThat(FT8TransmitSignal.tuneBlockReason(
                false, false, false, true, false, false))
                .isEqualTo(FT8TransmitSignal.TuneBlockReason.UNSUPPORTED_ROUTE);
        assertThat(FT8TransmitSignal.tuneBlockReason(
                false, false, false, false, true, false))
                .isEqualTo(FT8TransmitSignal.TuneBlockReason.UNSUPPORTED_ROUTE);
        assertThat(FT8TransmitSignal.tuneBlockReason(
                false, false, false, false, false, true))
                .isEqualTo(FT8TransmitSignal.TuneBlockReason.UNSUPPORTED_ROUTE);
    }

    // ---- shouldCompleteQso ---------------------------------------------------
    // Completion decision for the QSO state machine. The evidenceOnly flag marks
    // deep/late-pass parses: positive evidence (partner sent 73, partner moved
    // on) completes from any pass, but silence-based completions belong to the
    // fast pass alone — a deep pass that found nothing new is not "no reply".

    @Test
    public void complete_partner73_completesOnAnyPass() {
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                /*evidenceOnly*/ false, /*order*/ 5, /*newOrder*/ 5, 0, 3, false)).isTrue();
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                /*evidenceOnly*/ true, /*order*/ 5, /*newOrder*/ 5, 0, 3, false)).isTrue();
    }

    @Test
    public void complete_targetCallingOthers_completesOnAnyPass() {
        // RR73 deadlock breaker: the partner started calling someone else.
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                false, 4, -1, 0, 3, /*targetCallingOthers*/ true)).isTrue();
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                true, 4, -1, 0, 3, /*targetCallingOthers*/ true)).isTrue();
    }

    @Test
    public void complete_at73WithNoReply_fastPassOnly() {
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                false, 5, -1, 0, 3, false)).isTrue();
        // A deep pass finding nothing must not conclude the partner went silent.
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                true, 5, -1, 0, 3, false)).isFalse();
    }

    @Test
    public void complete_rr73NoReplyCapWithLimit_fastPassOnly() {
        // order 4, limit 3: cap is noReplyCount > 6.
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                false, 4, -1, /*noReplyCount*/ 7, /*noReplyLimit*/ 3, false)).isTrue();
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                true, 4, -1, 7, 3, false)).isFalse();
    }

    @Test
    public void complete_rr73NoReplyCapWithLimit_belowThresholdKeepsGoing() {
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                false, 4, -1, /*noReplyCount*/ 6, /*noReplyLimit*/ 3, false)).isFalse();
    }

    @Test
    public void complete_rr73GiveupWhenLimitIgnored_fastPassOnly() {
        // noReplyLimit 0 ("ignore"): RR73 still resets after RR73_GIVEUP_CYCLES (3).
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                false, 4, -1, /*noReplyCount*/ 4, /*noReplyLimit*/ 0, false)).isTrue();
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                true, 4, -1, 4, 0, false)).isFalse();
    }

    @Test
    public void complete_midQsoWithReply_neverCompletes() {
        // A normal advance (partner sent R-report while we're at order 2) is not
        // a completion on either pass.
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                false, 2, 3, 0, 3, false)).isFalse();
        assertThat(FT8TransmitSignal.shouldCompleteQso(
                true, 2, 3, 0, 3, false)).isFalse();
    }

    // ---- decideManualTx (issue #467) ----------------------------------------
    // The manual transmitNow() gate. FT8: 15000 ms slot, 12640 ms audio, so the
    // free slack (last moment a full waveform still fits) is 2360 ms. The tolerance
    // bounds how much *extra* leading audio we'll clip past that slack. The old
    // gate compared raw msInCycle < tolerance, so the default 2000 ms rejected
    // 360 ms *before* the slack was even used up — the setting appeared dead.

    private static final int FT8_SLACK = 2360;   // ModeProfile.FT8.audioSlackMillis
    private static final int FT8_SLOT = 15000;   // ModeProfile.FT8.slotMillis

    @Test
    public void manualTx_withinSlack_transmitsWithNoClip_evenAtZeroTolerance() {
        // Anywhere inside the free slack the whole waveform fits, so TX must go out
        // this cycle with zero clipping regardless of the tolerance setting — the
        // exact case the old raw gate wrongly rejected once msInCycle passed 2000.
        FT8TransmitSignal.ManualTxGate g =
                FT8TransmitSignal.decideManualTx(2100, FT8_SLACK, FT8_SLOT, /*tolerance*/ 0);
        assertThat(g.transmit).isTrue();
        assertThat(g.clipMs).isEqualTo(0);
    }

    @Test
    public void manualTx_onTimeStart_transmitsFullWaveform() {
        // A normal on-time tap ~600 ms into the slot: full waveform, no clip.
        FT8TransmitSignal.ManualTxGate g =
                FT8TransmitSignal.decideManualTx(600, FT8_SLACK, FT8_SLOT, 2000);
        assertThat(g.transmit).isTrue();
        assertThat(g.clipMs).isEqualTo(0);
    }

    @Test
    public void manualTx_defaultTolerance_transmitsIntoClipRegion() {
        // The reported scenario: the decode surfaces ~2.5 s into the slot and the
        // operator taps promptly. Under the default 2000 ms tolerance the effective
        // budget is slack + tolerance = 4360 ms, so this now keys up (clipping the
        // excess past the slack) instead of silently waiting ~30 s.
        FT8TransmitSignal.ManualTxGate g =
                FT8TransmitSignal.decideManualTx(2500, FT8_SLACK, FT8_SLOT, 2000);
        assertThat(g.transmit).isTrue();
        assertThat(g.clipMs).isEqualTo(2500 - FT8_SLACK); // 140 ms clipped
    }

    @Test
    public void manualTx_atToleranceBoundary_transmits() {
        // clip == tolerance is the last accepted point: msInCycle = slack + tolerance.
        int msInCycle = FT8_SLACK + 2000; // clip would be exactly 2000
        FT8TransmitSignal.ManualTxGate g =
                FT8TransmitSignal.decideManualTx(msInCycle, FT8_SLACK, FT8_SLOT, 2000);
        assertThat(g.transmit).isTrue();
        assertThat(g.clipMs).isEqualTo(2000);
    }

    @Test
    public void manualTx_pastToleranceBoundary_defers() {
        // One ms past the boundary the clip exceeds the tolerance -> defer this cycle.
        int msInCycle = FT8_SLACK + 2000 + 1; // clip 2001 > tolerance 2000
        FT8TransmitSignal.ManualTxGate g =
                FT8TransmitSignal.decideManualTx(msInCycle, FT8_SLACK, FT8_SLOT, 2000);
        assertThat(g.transmit).isFalse();
        assertThat(g.clipMs).isEqualTo(2001);
    }

    @Test
    public void manualTx_gateNeverStricterThanClipPath() {
        // Regression for the root cause: for every start offset the gate accepts,
        // the clip it reports is within the tolerance, and it accepts at least the
        // whole free-slack region (which the clip path always sends un-clipped).
        int tolerance = 2000;
        int lastAccepted = -1;
        for (int ms = 0; ms < FT8_SLOT; ms++) {
            FT8TransmitSignal.ManualTxGate g =
                    FT8TransmitSignal.decideManualTx(ms, FT8_SLACK, FT8_SLOT, tolerance);
            if (g.transmit) {
                assertThat(g.clipMs).isAtMost(tolerance);
                lastAccepted = ms;
            }
        }
        // Accepts through slack + tolerance, i.e. strictly later than the old raw
        // `msInCycle < tolerance` gate (which stopped at 1999 ms).
        assertThat(lastAccepted).isEqualTo(FT8_SLACK + tolerance);
    }

    @Test
    public void manualTx_toleranceClampedToRange() {
        // Negative tolerance clamps to 0: only the free slack sends, nothing clipped.
        FT8TransmitSignal.ManualTxGate low =
                FT8TransmitSignal.decideManualTx(FT8_SLACK + 1, FT8_SLACK, FT8_SLOT, -500);
        assertThat(low.transmit).isFalse();

        // Above-max tolerance clamps to MAX (4000): start budget slack + 4000.
        int justInside = FT8_SLACK + FT8TransmitSignal.MAX_LATE_START_TOLERANCE_MS;
        FT8TransmitSignal.ManualTxGate hi =
                FT8TransmitSignal.decideManualTx(justInside, FT8_SLACK, FT8_SLOT, 99999);
        assertThat(hi.transmit).isTrue();
        FT8TransmitSignal.ManualTxGate tooLate =
                FT8TransmitSignal.decideManualTx(justInside + 1, FT8_SLACK, FT8_SLOT, 99999);
        assertThat(tooLate.transmit).isFalse();
    }

    @Test
    public void manualTx_clipClampedBelowSlot() {
        // Defensive: an absurdly late offset never reports a clip >= the slot length
        // (the transmit runnable clamps identically before trimming the buffer).
        FT8TransmitSignal.ManualTxGate g =
                FT8TransmitSignal.decideManualTx(FT8_SLOT + 5000, FT8_SLACK, FT8_SLOT, 4000);
        assertThat(g.transmit).isFalse();
        assertThat(g.clipMs).isEqualTo(FT8_SLOT - 1);
    }

    // ---- rebuildTimerPreservingOffset ---------------------------------------
    // A mode change (or the applyLoadedOperatingMode() call at startup) rebuilds
    // the cycle timer. A fresh UtcTimer starts with time_sec = 0, so before this
    // fix the saved TX Delay loaded into the running signal was silently wiped
    // and the offset fell back to 0 ms until the operator re-edited the value —
    // the reported "TX Delay is not applied until I change its value" bug. The
    // rebuild must carry the outgoing timer's offset onto the new one.
    //
    // UtcTimer construction only schedules java.util.Timer tasks (no JNI / no
    // Android framework), so this runs on the bare JVM; delete() the timers to
    // avoid leaking their heartbeat threads between tests.

    private static final OnUtcTimer NOOP_CALLBACK = new OnUtcTimer() {
        @Override public void doHeartBeatTimer(long utc) { }
        @Override public void doOnSecTimer(long utc) { }
    };

    @Test
    public void rebuildTimer_carriesOffsetOntoNewTimer() {
        UtcTimer old = new UtcTimer(ModeProfile.FT8.slotMillis, false, NOOP_CALLBACK);
        try {
            old.setTime_sec(1800); // saved TX Delay, in ms
            UtcTimer rebuilt = FT8TransmitSignal.rebuildTimerPreservingOffset(
                    old, ModeProfile.FT4, NOOP_CALLBACK);
            try {
                // The core fix: the offset survives the rebuild instead of resetting to 0.
                assertThat(rebuilt.getTime_sec()).isEqualTo(1800);
            } finally {
                rebuilt.delete();
            }
        } finally {
            old.delete();
        }
    }

    @Test
    public void rebuildTimer_zeroOffsetStaysZero() {
        // No TX Delay set: the rebuild must not invent one.
        UtcTimer old = new UtcTimer(ModeProfile.FT8.slotMillis, false, NOOP_CALLBACK);
        try {
            UtcTimer rebuilt = FT8TransmitSignal.rebuildTimerPreservingOffset(
                    old, ModeProfile.FT8, NOOP_CALLBACK);
            try {
                assertThat(rebuilt.getTime_sec()).isEqualTo(0);
            } finally {
                rebuilt.delete();
            }
        } finally {
            old.delete();
        }
    }

    // ---- isStaleEvidence -----------------------------------------------------
    // Deep/late/stashed passes re-deliver decodes out of order: a pass finishing
    // during our transmission is replayed after key-up, by which time the fast
    // pass may already have advanced the QSO on newer evidence. Such a pass may
    // move the sequence forward but never back — a partner's opening grid
    // replayed after we reached RR73 must not rewind us to sending a report.
    // Field case (POTA 2026-07-23, K0OBX/N8GK/K0OTC): "advance order 4->2".

    @Test
    public void stale_fastPassIsNeverStale() {
        // The fast pass observes the current cycle; its verdict stands even when
        // it lowers the order (the partner really did fall back a step).
        assertThat(FT8TransmitSignal.isStaleEvidence(
                /*evidenceOnly*/ false, /*order*/ 4, /*newOrder*/ 1)).isFalse();
        assertThat(FT8TransmitSignal.isStaleEvidence(false, 5, 1)).isFalse();
    }

    @Test
    public void stale_deepGridAfterRR73_isStale() {
        // The exact field failure: at RR73 (4), a replayed opening grid (1)
        // would set order back to 2 and re-send the signal report.
        assertThat(FT8TransmitSignal.isStaleEvidence(true, 4, 1)).isTrue();
    }

    @Test
    public void stale_deepEvidenceThatAdvances_isNotStale() {
        // At order 2 the partner's R-report (3) advances to RR73 (4).
        assertThat(FT8TransmitSignal.isStaleEvidence(true, 2, 3)).isFalse();
        // At RR73 (4) their 73 (5) completes.
        assertThat(FT8TransmitSignal.isStaleEvidence(true, 4, 5)).isFalse();
    }

    @Test
    public void stale_deepEvidenceHoldingSameOrder_isNotStale() {
        // Partner repeats the message we already acted on: newOrder+1 == order.
        // Not a rewind, and re-affirming resets the no-reply count, so allow it.
        assertThat(FT8TransmitSignal.isStaleEvidence(true, 4, 3)).isFalse();
        assertThat(FT8TransmitSignal.isStaleEvidence(true, 2, 1)).isFalse();
    }

    @Test
    public void stale_cqBaselineIsNeverStale() {
        // Order 6 is the CQ baseline, not the top of the ladder: a reply
        // arriving there legitimately starts a QSO at a lower order.
        assertThat(FT8TransmitSignal.isStaleEvidence(true, 6, 1)).isFalse();
        assertThat(FT8TransmitSignal.isStaleEvidence(true, 6, 2)).isFalse();
    }

    @Test
    public void outputMaxChannels_noExplicitDeviceIsUnknown() {
        // Android's "Default" sink: there is no AudioDeviceInfo to ask, and
        // UNKNOWN is what keeps the operator's TX left/right choice in force
        // rather than silently ignoring it on a routed-USB setup.
        assertThat(FT8TransmitSignal.outputMaxChannels(null))
                .isEqualTo(com.k1af.ft8af.wave.AudioChannelCapability.UNKNOWN);
    }
}
