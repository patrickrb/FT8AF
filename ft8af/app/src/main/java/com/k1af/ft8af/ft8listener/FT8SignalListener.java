package com.k1af.ft8af.ft8listener;
/**
 * Class for listening to audio. Listening cycles are controlled by the UtcTimer clock,
 * and audio data is read through the OnWaveDataListener interface.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.k1af.ft8af.FT8Common;
import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.OwnTxEchoFilter;
import com.k1af.ft8af.ModeProfile;
import com.k1af.ft8af.database.DatabaseOpr;
import com.k1af.ft8af.ft8transmit.GenerateFT8;
import com.k1af.ft8af.timer.OnUtcTimer;
import com.k1af.ft8af.timer.UtcTimer;
import com.k1af.ft8af.wave.OnGetVoiceDataDone;
import com.k1af.ft8af.wave.WaveFileReader;
import com.k1af.ft8af.wave.WaveFileWriter;

import java.util.ArrayList;

public class FT8SignalListener {
    private static final String TAG = "FT8SignalListener";
    // Not final: rebuildTimer() recreates it when the operating mode (and thus cycle
    // length) changes. See rebuildTimer().
    private UtcTimer utcTimer;
    //private HamRecorder hamRecorder;
    private final OnFt8Listen onFt8Listen;// event triggered when listening starts and decoding finishes
    //private long band;
    public MutableLiveData<Long> decodeTimeSec = new MutableLiveData<>();// decode duration
    public long timeSec=0;// decode duration

    private OnWaveDataListener onWaveDataListener;


    private DatabaseOpr db;

    // The FT8 cross-slot recall cache (cpp/ft8af_glue/ft8_xslot.c) is process-global and,
    // by its own contract, NOT thread-safe; in the app it is only ever touched from
    // inside DecoderFt8Analysis. The late full-slot pass (issue #363) lets a previous
    // slot's decode thread overlap the next slot's decode thread, so candidate analysis
    // must be serialized across threads. All other native decode state is per-decoder
    // (or thread-local), so per-candidate granularity is enough. The gate is asymmetric:
    // the time-critical early path blocks (bounded by one candidate), the best-effort
    // late path tries briefly and aborts its remaining candidates on contention, so late
    // work never slows the next slot's early decode. See LateDecode.AnalysisGate.
    private static final LateDecode.AnalysisGate ANALYSIS_GATE = new LateDecode.AnalysisGate();

    /**
     * Lets the transmitter hold key-up while this slot's fast pass is still running, so a
     * busy band answers callers in the right cycle instead of one late. See
     * {@link FastDecodeGate}.
     */
    private final FastDecodeGate fastDecodeGate = new FastDecodeGate();

    public FastDecodeGate getFastDecodeGate() {
        return fastDecodeGate;
    }


    static {
        try {
            System.loadLibrary("ft8af");
        } catch (UnsatisfiedLinkError e) {
            // Best-effort load: JVM unit tests don't have the native libs on
            // java.library.path. The native decode methods throw if actually invoked
            // without the library; class init must not crash.
            Log.w(TAG, "native library not loaded: " + e.getMessage());
        }
    }

    public interface OnWaveDataListener {
        void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone);
    }

    public FT8SignalListener(DatabaseOpr db, OnFt8Listen onFt8Listen) {
        //this.hamRecorder = hamRecorder;
        this.onFt8Listen = onFt8Listen;
        this.db = db;

        // Create action trigger, synchronized with UTC time, on the current mode's cycle
        // (FT8 = 15000ms, FT4 = 7500ms, FT2 = 3750ms). DoOnSecTimer fires at the start of each cycle.
        utcTimer = new UtcTimer(GeneralVariables.currentMode().slotMillis, false, makeTimerCallback());
    }

    /** The cycle-trigger callback, shared between the constructor and {@link #rebuildTimer}. */
    private OnUtcTimer makeTimerCallback() {
        return new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {// clock info when not triggered
            }

            @Override
            public void doOnSecTimer(long utc) {// triggered at the specified interval
                Log.d(TAG, String.format("Recording triggered, %d", utc));
                runRecorde(utc);
            }
        };
    }

    /**
     * Recreate the cycle timer for a new operating mode. {@link UtcTimer}'s period is fixed
     * at construction, so switching from FT8 (15s) to FT4 (7.5s) means tearing down the old
     * timer and building a fresh one. Preserves the listening state.
     */
    public void rebuildTimer(ModeProfile mode) {
        boolean wasListening = utcTimer.isRunning();
        utcTimer.delete();
        utcTimer = new UtcTimer(mode.slotMillis, false, makeTimerCallback());
        if (wasListening) {
            utcTimer.start();
        }
    }

    public void startListen() {
        utcTimer.start();
    }

    public void stopListen() {
        utcTimer.stop();
    }

    public boolean isListening() {
        return utcTimer.isRunning();
    }

    /**
     * Get the current time offset, including both the overall clock offset and this instance's offset.
     *
     * @return int
     */
    public int time_Offset() {
        return utcTimer.getTime_sec() + UtcTimer.delay;
    }

    /**
     * Record audio. Recording runs in the background using multiple threads and automatically
     * generates a temporary WAV file. There are two callbacks: one for when recording starts
     * and one for when it ends. When recording ends, the decoder is activated.
     *
     * @param utc the current UTC time for decoding
     */
    private void runRecorde(long utc) {
        Log.d(TAG, "Starting recording...");

        if (onWaveDataListener != null) {
            // Freeze the whole cycle's decode plan NOW, at the slot boundary: the record
            // window (fast turnaround = shorter window so decoding finishes ~1s before
            // the cycle boundary; off = full slot for max sensitivity), whether a late
            // full-slot pass runs (issue #363: the early window loses signals whose DT
            // pushes them past its end, ~+0.86s for FT8), and — critically — the
            // OPERATING MODE every decode pass of this slot must use. A mid-slot mode
            // switch must not retroactively change how this slot's buffers are decoded;
            // see LateDecode.planSlot.
            final LateDecode.SlotPlan plan = LateDecode.planSlot(
                    GeneralVariables.earlyDecode, GeneralVariables.currentMode(),
                    System.currentTimeMillis());
            onWaveDataListener.getVoiceData(plan.recordMillis, true
                    , new OnGetVoiceDataDone() {
                        @Override
                        public void onGetDone(float[] data) {
                            Log.d(TAG, String.format("Starting decode...###, data length: %d",data.length));
                            decodeFt8(utc, data, plan.mode, plan.lateHandoff);
                        }
                    });
            if (plan.lateHandoff != null) {
                // Second one-shot monitor over the WHOLE slot; the decode thread picks
                // the buffer up after the early passes finish and decodes only the new
                // messages as an extra late (isDeep) delivery. See LateDecode.
                onWaveDataListener.getVoiceData(plan.mode.slotMillis, true
                        , new OnGetVoiceDataDone() {
                            @Override
                            public void onGetDone(float[] data) {
                                plan.lateHandoff.offer(data);
                            }
                        });
            }
        }
    }

    public void decodeFt8(long utc, float[] voiceData) {
        decodeFt8(utc, voiceData, GeneralVariables.currentMode(), null);
    }

    /**
     * @param mode        the operating mode {@code voiceData} was RECORDED under, captured
     *                    at the slot boundary (see {@link LateDecode#planSlot}). Every
     *                    decode pass of this slot — decoder init, protocol, deep budget,
     *                    late pass — uses this snapshot, never
     *                    {@code GeneralVariables.currentMode()}: a mid-slot mode switch
     *                    must not make an in-flight decode reinterpret its buffer under
     *                    the new mode's protocol.
     * @param lateHandoff when non-null, the source of a second full-slot buffer to decode
     *                    after the early passes finish (late full-slot pass, issue #363);
     *                    null = no late pass this cycle
     */
    public void decodeFt8(long utc, float[] voiceData, ModeProfile mode,
                          LateDecode.Handoff lateHandoff) {

        // Test code below -------------------------
//        String fileName = getCacheFileName("test_01.wav");
//        Log.e(TAG, "onClick: fileName:" + fileName);
//        WaveFileReader reader = new WaveFileReader(fileName);
//        int data[][] = reader.getData();
        //----------------------------------------------------------

        // Marked in flight BEFORE the thread starts, not inside it: the transmitter can
        // reach its key-up check before this thread is scheduled, and would then see an
        // idle gate and key up against a decode that was about to run.
        fastDecodeGate.begin();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runDecodeThread();
                } finally {
                    // Backstop. The timely release happens right after delivery below, but
                    // anything throwing before it — JNI init, a decode failure, OOM —
                    // would otherwise strand the gate in flight FOREVER, making every
                    // later key-up wait out the full hold. end() is idempotent.
                    fastDecodeGate.end();
                }
            }

            private void runDecodeThread() {
                long time = System.currentTimeMillis();
                if (onFt8Listen != null) {
                    onFt8Listen.beforeListen(utc);
                }

//                float[] tempData = ints2floats(data);


                /// Read audio data and perform preprocessing
                // Note: decoding must complete within one cycle, otherwise a new decode cycle will begin
                // Both decoders are from-source in libft8af.so: FT2/FT4 use the *Ft2 entry
                // points (ft2_decode_jni.cpp), FT8 the plain ones (ft8_decode_jni.cpp). All
                // native ops dispatch through the *Decode helpers on this flag — derived
                // from the RECORD-TIME mode snapshot, not the live global.
                final boolean fromSource = mode.usesFromSourceDecoder();
                long ft8Decoder = initDecoder(utc, voiceData.length, mode);
//                        , tempData.length, true);
                pressFloatDecode(voiceData, ft8Decoder, fromSource);// load audio data
//                DecoderMonitorPressFloat(tempData, ft8Decoder);// load audio data


                // The a91 list is LOCAL to this decode thread (not instance state): the
                // late full-slot pass keeps this thread alive into the next slot, whose
                // own decode thread must be able to run concurrently without either
                // corrupting the other's subtract bookkeeping.
                final A91List a91List = new A91List();
                ArrayList<Ft8Message> allMsg = new ArrayList<>();
//                ArrayList<Ft8Message> msgs = runDecode(utc, voiceData,false);
                ArrayList<Ft8Message> msgs = runDecode(ft8Decoder, utc, false, mode,
                        DeepDecodeBudget.NO_DEADLINE, a91List, null);
                addMsgToList(allMsg, msgs);
                timeSec = System.currentTimeMillis() - time;
                decodeTimeSec.postValue(timeSec);// decode elapsed time
                try {
                    if (onFt8Listen != null) {
                        onFt8Listen.afterDecode(utc, OwnTxEchoFilter.meanTimeOffsetSec(allMsg), UtcTimer.sequential(utc), msgs, false);
                    }
                } finally {
                    // Released only after DELIVERY, not merely after decoding: the
                    // sequencer acts inside afterDecode, so a transmitter waiting on this
                    // would otherwise still race it. finally, so a throw in a listener
                    // cannot strand TX waiting out the full hold. See FastDecodeGate.
                    fastDecodeGate.end();
                }


                if (GeneralVariables.deepDecodeMode) {// enter deep decode mode
                    //float[] newSignal=tempData;
                    msgs = runDecode(ft8Decoder, utc, true, mode,
                            DeepDecodeBudget.NO_DEADLINE, a91List, null);
                    addMsgToList(allMsg, msgs);
                    timeSec = System.currentTimeMillis() - time;
                    decodeTimeSec.postValue(timeSec);// decode elapsed time
                    if (onFt8Listen != null) {
                        onFt8Listen.afterDecode(utc, OwnTxEchoFilter.meanTimeOffsetSec(allMsg), UtcTimer.sequential(utc), msgs, true);
                    }

                    // The subtract-and-redecode loop runs on the early buffer ONLY when no
                    // late full-slot pass is scheduled (early decode off, or FT4/FT2).
                    // With a late pass pending, the loop is deferred to the full-slot
                    // buffer (a strict superset of this one): running it here doubled the
                    // deep work and pushed the late pass's start past the next slot's
                    // early decode, whose first gate contention then aborted the late
                    // candidate scan almost every cycle. See LateDecode.deepLoopRunsOnEarlyBuffer.
                    if (LateDecode.deepLoopRunsOnEarlyBuffer(lateHandoff)) {
                        final long deepDecodeBudgetMs = mode.deepDecodeBudgetMillis();
                        // Budget the subtract-and-redecode loop by ITS OWN elapsed time, not the total
                        // decode time. On a slow device the initial fast + first deep pass can already
                        // exceed the budget, which would abort subtraction before it ran even once.
                        final long deepLoopStart = System.currentTimeMillis();
                        // Passes inside the loop also stop their candidate scan at this instant,
                        // so one pass over a huge candidate list can't blow through the budget.
                        final long passDeadline = DeepDecodeBudget.passDeadline(deepLoopStart, deepDecodeBudgetMs);
                        do {
                            if (DeepDecodeBudget.loopExhausted(deepLoopStart, System.currentTimeMillis(), deepDecodeBudgetMs)) break;// stop once the subtraction loop has spent its budget
                            // subtract decoded signals
                            subtractDecode(ft8Decoder, a91List, fromSource);

                            // perform another decode pass
                            msgs = runDecode(ft8Decoder, utc, true, mode, passDeadline, a91List, null);
                            addMsgToList(allMsg, msgs);
                            timeSec = System.currentTimeMillis() - time;
                            decodeTimeSec.postValue(timeSec);// decode elapsed time
                            if (onFt8Listen != null) {
                                onFt8Listen.afterDecode(utc, OwnTxEchoFilter.meanTimeOffsetSec(allMsg), UtcTimer.sequential(utc), msgs, true);
                            }

                        } while (msgs.size() > 0 );
                    }

                }
                // Moved to finalize() method
                deleteDecoder(ft8Decoder, fromSource);

                Log.d(TAG, String.format("Decode took: %d ms", System.currentTimeMillis() - time));

                // Late full-slot pass (issue #363): runs after the early buffer has been
                // fully processed and its decoder freed, on this same thread, so the late
                // decodes share the slot's allMsg dedup scope and only NEW messages get
                // delivered. Runs into the next slot's cycle by design; the next slot's
                // decode uses its own thread, decoder, and a91 list.
                if (lateHandoff != null) {
                    runLateFullSlotPass(utc, allMsg, a91List, mode, lateHandoff);
                }

            }
        }).start();
    }

    /**
     * Decode the full-slot buffer captured alongside the early window: the slot's DEPTH
     * pass, complementing the early buffer's SPEED pass. It recovers signals whose DT
     * pushed them past the early window's end, and — with deep decode on — hosts the
     * slot's subtract-and-redecode loop (deferred here from the early buffer, which is a
     * strict prefix of this one; see {@link LateDecode#deepLoopRunsOnEarlyBuffer}).
     * Deliveries reuse the slot's {@code allMsg} dedup scope, so everything the early
     * passes already reported is filtered out and only genuinely new messages reach
     * {@code afterDecode} — flagged isDeep=true so the auto-sequencer treats them as
     * evidence rather than re-triggering on them.
     *
     * <p>Every candidate loop in here is bounded by the ABSOLUTE window deadline (next
     * slot boundary + early window − safety margin, capped by the mode's deep budget):
     * the pass must finish before the next slot's early decode thread starts, so the
     * analysis-gate abort is a backstop for overruns, not the routine exit path.
     *
     * <p>Deliberately does NOT touch {@link #timeSec}/{@link #decodeTimeSec}: by the time
     * this runs the next slot may already be decoding, and clobbering the shared elapsed
     * time could make MainViewModel's auto-reply budget check misjudge that slot.
     *
     * @param mode the RECORD-TIME mode snapshot for this slot; the late buffer must be
     *             decoded under the mode it was captured in, even if the user has since
     *             switched modes
     */
    private void runLateFullSlotPass(long utc, ArrayList<Ft8Message> allMsg, A91List a91List,
                                     ModeProfile mode, LateDecode.Handoff handoff) {
        float[] fullData = handoff.awaitBuffer(System.currentTimeMillis());
        if (fullData == null) {
            // Capture stopped mid-slot (or never started); the buffer is not coming.
            Log.w(TAG, "late full-slot pass skipped: full-slot buffer never arrived");
            return;
        }
        final boolean fromSource = mode.usesFromSourceDecoder();
        long time = System.currentTimeMillis();
        long lateDecoder = initDecoder(utc, fullData.length, mode);
        try {
            pressFloatDecode(fullData, lateDecoder, fromSource);
            final long deadline = LateDecode.effectiveLatePassDeadline(
                    handoff.latePassDeadlineEpochMs,
                    DeepDecodeBudget.passDeadline(time, mode.deepDecodeBudgetMillis()));
            // Best-effort priority: the first analysis-gate contention with the next
            // slot's early decode trips this and the late pass gives up the rest of its
            // candidates (and the deep loop). See LateDecode.AnalysisGate.
            final LateDecode.LateAbort lateAbort = new LateDecode.LateAbort();
            deliverLateMessages(utc, allMsg,
                    runDecode(lateDecoder, utc, false, mode, deadline, a91List, lateAbort));
            if (GeneralVariables.deepDecodeMode && !lateAbort.tripped()) {
                ArrayList<Ft8Message> msgs =
                        runDecode(lateDecoder, utc, true, mode, deadline, a91List, lateAbort);
                deliverLateMessages(utc, allMsg, msgs);
                // Subtract-and-redecode on the full buffer. Same convergence rule as the
                // early-buffer loop this replaces (do/while: at least one subtraction
                // round even when the deep pass above surfaced nothing new — subtraction
                // is what uncovers weak signals UNDER the already-decoded strong ones):
                // keep going while a pass still yields NEW messages (deliverLateMessages
                // strips already-delivered ones from msgs in place) and the deadline
                // hasn't passed. a91List holds the previous pass's decodes — exactly
                // what the next subtraction removes.
                do {
                    if (lateAbort.tripped()
                            || DeepDecodeBudget.passExpired(deadline, System.currentTimeMillis())) {
                        break;
                    }
                    subtractDecode(lateDecoder, a91List, fromSource);
                    msgs = runDecode(lateDecoder, utc, true, mode, deadline, a91List, lateAbort);
                    deliverLateMessages(utc, allMsg, msgs);
                } while (!msgs.isEmpty());
            }
        } finally {
            deleteDecoder(lateDecoder, fromSource);
        }
        Log.d(TAG, String.format("Late full-slot pass took: %d ms",
                System.currentTimeMillis() - time));
    }

    /**
     * Merge one late pass's decodes into the slot's dedup scope and deliver only the NEW
     * messages. An empty remainder is not delivered at all — the late pass runs during the
     * next slot's cycle, and a no-op afterDecode would clear that slot's decoding marker
     * and waterfall labels behind its back.
     */
    private void deliverLateMessages(long utc, ArrayList<Ft8Message> allMsg,
                                     ArrayList<Ft8Message> msgs) {
        addMsgToList(allMsg, msgs);// drops everything the early passes already delivered
        if (msgs.isEmpty() || onFt8Listen == null) {
            return;
        }
        // isDeep=true: MainViewModel appends these without re-triggering auto-sequence.
        onFt8Listen.afterDecode(utc, OwnTxEchoFilter.meanTimeOffsetSec(allMsg), UtcTimer.sequential(utc), msgs, true);
    }


    /**
     * @param mode      the RECORD-TIME mode snapshot for the slot being decoded (never the
     *                  live {@code GeneralVariables.currentMode()})
     * @param lateAbort non-null only for late full-slot passes: candidate analysis then
     *                  yields to the next slot's early decode, tripping this flag (and
     *                  stopping the scan) on the first analysis-gate contention
     */
    private ArrayList<Ft8Message> runDecode(long ft8Decoder, long utc, boolean isDeep, ModeProfile mode,
                                            long deadlineEpochMs, A91List a91List,
                                            LateDecode.LateAbort lateAbort) {
        final boolean fromSource = mode.usesFromSourceDecoder();
        ArrayList<Ft8Message> ft8Messages = new ArrayList<>();
        Ft8Message ft8Message = new Ft8Message(mode.id);

        ft8Message.utcTime = utc;
        ft8Message.band = GeneralVariables.band;
        a91List.clear();

        setDeepDecode(ft8Decoder, isDeep, fromSource);// set iteration count; isDeep==true increases iterations

        int num_candidates = findSyncDecode(ft8Decoder, fromSource);// up to FT8AF_MAX_CANDIDATES candidates
        for (int idx = 0; idx < num_candidates; ++idx) {
            if (DeepDecodeBudget.passExpired(deadlineEpochMs, System.currentTimeMillis())) {
                Log.d(TAG, "pass deadline hit at candidate " + idx + "/" + num_candidates);
                break;
            }
            if (lateAbort != null && lateAbort.tripped()) {
                Log.d(TAG, "late pass aborted by analysis-gate contention at candidate "
                        + idx + "/" + num_candidates);
                break;
            }
            ft8Message.snr = Ft8Message.SNR_UNKNOWN; // reset before each candidate
            try {// protect against decode failure
                if (analysisDecode(idx, ft8Decoder, ft8Message, fromSource, lateAbort)) {

                    if (ft8Message.isValid) {
                        if (!ft8Message.hasSnr()) {
                            Log.d(TAG, "SNR not set by decoder for candidate " + idx
                                    + " (" + ft8Message.callsignFrom + ")");
                        }
                        Ft8Message msg = new Ft8Message(ft8Message);// using msg here because some hashed callsigns will replace <...>
                        byte[] a91 = getA91Decode(ft8Decoder, fromSource);
                        a91List.add(a91, ft8Message.freq_hz, ft8Message.time_sec);

                        if (checkMessageSame(ft8Messages, msg)) {
                            continue;
                        }

                        msg.isWeakSignal = isDeep;// whether it is a weak signal
                        ft8Messages.add(msg);

                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "run: " + e.getMessage());
            }

        }


        return ft8Messages;
    }

    // ---- Decoder backend dispatch -----------------------------------------------------
    // FT2 and FT4 receive run on the from-source decoder (ft8af_usb, *Ft2 entry points);
    // FT8 runs on the prebuilt (ft8af). The decode loop above stays backend-agnostic by
    // routing every native op through these helpers on the per-cycle `fromSource` flag
    // (= ModeProfile.usesFromSourceDecoder()). The from-source init takes the ftx protocol
    // so it configures the monitor for FT2 vs FT4 symbol timing.

    /**
     * @param mode the RECORD-TIME mode snapshot: selects the entry-point family AND the
     *             protocol/flags. Must never read {@code GeneralVariables.currentMode()} —
     *             the late full-slot pass inits its decoder well after the slot boundary
     *             (possibly into the next slot), by which time the user may have switched
     *             modes, and initializing with the new mode's protocol against the old
     *             mode's buffer would decode garbage.
     */
    private long initDecoder(long utc, int numSamples, ModeProfile mode) {
        if (mode.usesFromSourceDecoder()) {
            return InitDecoderFt2(utc, FT8Common.SAMPLE_RATE, numSamples, mode.ftxProtocol());
        }
        return InitDecoder(utc, FT8Common.SAMPLE_RATE, numSamples, mode.isFt8);
    }

    private void pressFloatDecode(float[] data, long decoder, boolean fromSource) {
        if (fromSource) DecoderFt2MonitorPressFloat(data, decoder);
        else DecoderMonitorPressFloat(data, decoder);
    }

    private void setDeepDecode(long decoder, boolean isDeep, boolean fromSource) {
        if (fromSource) setDecodeModeFt2(decoder, isDeep);
        else setDecodeMode(decoder, isDeep);
    }

    private int findSyncDecode(long decoder, boolean fromSource) {
        return fromSource ? DecoderFt2FindSync(decoder) : DecoderFt8FindSync(decoder);
    }

    /**
     * Serialized: DecoderFt8Analysis reads/writes the process-global cross-slot cache
     * (ft8_xslot.c), and the late full-slot pass can overlap the next slot's decode
     * thread — no two analysis calls may ever run concurrently. The gate is asymmetric
     * so the late pass can never stall the time-critical early path: early/primary calls
     * ({@code lateAbort == null}) block unconditionally (bounded by one candidate), late
     * calls only try briefly and on contention trip {@code lateAbort} and bail — the
     * caller's candidate loop then aborts. See {@link LateDecode.AnalysisGate}.
     *
     * @return true if the candidate decoded; false if it didn't OR the late path yielded
     *         (in which case {@code lateAbort} is tripped)
     */
    private boolean analysisDecode(int idx, long decoder, Ft8Message msg, boolean fromSource,
                                   LateDecode.LateAbort lateAbort) {
        if (lateAbort == null) {
            ANALYSIS_GATE.acquireBlocking();
        } else if (!ANALYSIS_GATE.tryAcquireForLate()) {
            lateAbort.trip();
            return false;
        }
        try {
            return fromSource ? DecoderFt2Analysis(idx, decoder, msg)
                    : DecoderFt8Analysis(idx, decoder, msg);
        } finally {
            ANALYSIS_GATE.release();
        }
    }

    private byte[] getA91Decode(long decoder, boolean fromSource) {
        return fromSource ? DecoderFt2GetA91(decoder) : DecoderGetA91(decoder);
    }

    private void deleteDecoder(long decoder, boolean fromSource) {
        if (fromSource) DeleteDecoderFt2(decoder);
        else DeleteDecoder(decoder);
    }

    private void subtractDecode(long decoder, A91List list, boolean fromSource) {
        if (fromSource) ReBuildSignal.subtractSignalFt2(decoder, list);
        else ReBuildSignal.subtractSignal(decoder, list);
    }

    /**
     * Merge {@code newMsg} into {@code allMsg}, the slot-wide dedup scope: messages whose
     * text is already in {@code allMsg} are REMOVED from {@code newMsg} (upgrading the
     * kept copy's SNR where better, see {@link #checkMessageSame}); the rest are appended
     * to {@code allMsg}. After the call {@code newMsg} holds only the genuinely new
     * messages — which is what makes the late full-slot pass deliver only messages the
     * early passes missed. Static (no instance state) so it is unit-testable.
     *
     * @param allMsg message list
     * @param newMsg new messages
     */
    static void addMsgToList(ArrayList<Ft8Message> allMsg, ArrayList<Ft8Message> newMsg) {
        for (int i = newMsg.size() - 1; i >= 0; i--) {
            if (checkMessageSame(allMsg, newMsg.get(i))) {
                newMsg.remove(i);
            } else {
                allMsg.add(newMsg.get(i));
            }
        }
    }

    /**
     * Check if the same message content already exists in the message list.
     *
     * @param ft8Messages message list
     * @param ft8Message  message
     * @return boolean
     */
    static boolean checkMessageSame(ArrayList<Ft8Message> ft8Messages, Ft8Message ft8Message) {
        for (Ft8Message msg : ft8Messages) {
            if (msg.getMessageText().equals(ft8Message.getMessageText())) {
                // Prefer known SNR over unknown; when both are known, keep the higher value.
                if (!msg.hasSnr() && ft8Message.hasSnr()) {
                    msg.snr = ft8Message.snr;
                } else if (msg.hasSnr() && ft8Message.hasSnr() && msg.snr < ft8Message.snr) {
                    msg.snr = ft8Message.snr;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected void finalize() throws Throwable {
        //DeleteDecoder(ft8Decoder);
        super.finalize();
    }

    public OnWaveDataListener getOnWaveDataListener() {
        return onWaveDataListener;
    }

    public void setOnWaveDataListener(OnWaveDataListener onWaveDataListener) {
        this.onWaveDataListener = onWaveDataListener;
    }


    public String getCacheFileName(String fileName) {
        return GeneralVariables.getMainContext().getCacheDir() + "/" + fileName;
    }

    public float[] ints2floats(int data[][]) {
        float temp[] = new float[data[0].length];
        for (int i = 0; i < data[0].length; i++) {
            temp[i] = data[0][i] / 32768.0f;
        }
        return temp;
    }

    public int[] floats2ints(float data[]) {
        int temp[] = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            temp[i] = (int) (data[i] * 32767.0f);
        }
        return temp;
    }

    /**
     * Decode step 1: initialize the decoder and get the decoder address.
     *
     * @param utcTime     UTC time
     * @param sampleRat   sample rate, 12000
     * @param num_samples length of buffer data
     * @param isFt8       whether it is an FT8 signal
     * @return the decoder address
     */
    public native long InitDecoder(long utcTime, int sampleRat, int num_samples, boolean isFt8);

    /**
     * Decode step 2: read WAV data.
     *
     * @param buffer  WAV data buffer
     * @param decoder decoder data address
     */
    public native void DecoderMonitorPress(int[] buffer, long decoder);

    public native void DecoderMonitorPressFloat(float[] buffer, long decoder);


    /**
     * Decode step 3: synchronize data.
     *
     * @param decoder decoder address
     * @return number of candidate signals
     */
    public native int DecoderFt8FindSync(long decoder);

    /**
     * Decode step 4: analyze and extract messages (must be called in a loop).
     *
     * @param idx        index of the candidate signal
     * @param decoder    decoder address
     * @param ft8Message the decoded message
     * @return boolean
     */
    public native boolean DecoderFt8Analysis(int idx, long decoder, Ft8Message ft8Message);

    /**
     * Final decode step: delete the decoder data.
     *
     * @param decoder decoder data address
     */
    public native void DeleteDecoder(long decoder);

    public native void DecoderFt8Reset(long decoder, long utcTime, int num_samples);

    public native byte[] DecoderGetA91(long decoder);// get the a91 data of the current message

    public native void setDecodeMode(long decoder, boolean isDeep);// set decode mode: isDeep=true for multi-iteration, =false for fast iteration

    // ---- FT2 decoder (from-source ft8_lib in libft8af_usb.so) -------------------------
    // Distinct entry points so they never collide with the prebuilt's InitDecoder/etc.
    // The from-source decoder serves FT2 and FT4; the protocol (FTX_PROTOCOL_FT2 / _FT4) is
    // passed in via the `protocol` arg. See cpp/ft8af_glue/ft2_decode_jni.cpp.
    public native long InitDecoderFt2(long utcTime, int sampleRate, int num_samples, int protocol);

    public native void DecoderFt2MonitorPressFloat(float[] buffer, long decoder);

    public native int DecoderFt2FindSync(long decoder);

    public native boolean DecoderFt2Analysis(int idx, long decoder, Ft8Message ft8Message);

    public native byte[] DecoderFt2GetA91(long decoder);

    public native void DeleteDecoderFt2(long decoder);

    public native void setDecodeModeFt2(long decoder, boolean isDeep);
}
