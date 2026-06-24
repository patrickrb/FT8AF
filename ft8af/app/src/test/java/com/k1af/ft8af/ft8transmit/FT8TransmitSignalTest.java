package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

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
}
