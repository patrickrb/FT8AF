package com.k1af.ft8af.voice;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import com.k1af.ft8af.GeneralVariables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin Android TextToSpeech wrapper for the voice assistant. Owned by
 * {@code DxAlertNotifier} (the same object that already sees every decode
 * batch and QSO completion).
 *
 * <p>The one hard rule (see the TX-audio hazards in the project docs): TTS
 * must NEVER play while the rig is transmitting — the utterance would be
 * mixed into the TX audio and go out over the air. Two layers enforce it:
 * <ul>
 *   <li>{@link #speak} refuses to start an utterance while the
 *       {@link TransmitGate} reports TX active, and</li>
 *   <li>MainViewModel observes {@code mutableIsTransmitting} and calls
 *       {@link #stopNow()} the instant it flips true, cutting off anything
 *       already speaking.</li>
 * </ul>
 *
 * <p>TextToSpeech init is lazy (first {@link #speak}) so no TTS engine is
 * spun up for users who never enable a voice toggle. Utterances queued while
 * the engine is still initializing are buffered (bounded) and flushed from
 * onInit — re-checking the transmit gate at flush time.
 */
public class VoiceAnnouncer {

    /** Runtime TX check, wired to {@code FT8TransmitSignal.isTransmitting()}. */
    public interface TransmitGate {
        boolean isTransmitting();
    }

    private static final int MAX_PENDING = 5;

    private final Context appContext;
    private final Object initLock = new Object();
    private volatile TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile boolean ttsFailed = false;
    // Utterances requested before onInit landed. Guarded by initLock.
    private final List<String[]> pending = new ArrayList<>();
    // Per-session spoken dedup keys (see VoiceAnnouncementDecisions.claim).
    private final Set<String> spoken = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile TransmitGate transmitGate = null;

    public VoiceAnnouncer(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    public void setTransmitGate(TransmitGate gate) {
        this.transmitGate = gate;
    }

    /** The per-session dedup key set, for {@code VoiceAnnouncementDecisions.claim}. */
    public Set<String> spokenKeys() {
        return spoken;
    }

    /** False while the rig is transmitting — speaking then would go out over the air. */
    public boolean canSpeakNow() {
        TransmitGate gate = transmitGate;
        return gate == null || !gate.isTransmitting();
    }

    /**
     * Queue an utterance (QUEUE_ADD — announcements never cut each other off).
     * Silently dropped while transmitting; the caller's dedup claim should be
     * gated on {@link #canSpeakNow()} so the key isn't burned in that case.
     */
    public void speak(String text, String utteranceId) {
        if (appContext == null || text == null || text.isEmpty()) return;
        if (!canSpeakNow()) return;

        synchronized (initLock) {
            if (ttsFailed) return;
            if (!ttsReady) {
                if (tts == null) {
                    initTts();
                }
                if (pending.size() < MAX_PENDING) {
                    pending.add(new String[]{text, utteranceId});
                }
                return;
            }
        }
        speakNow(text, utteranceId);
    }

    /** Cut off the current utterance and drop anything queued. TX just started. */
    public void stopNow() {
        synchronized (initLock) {
            pending.clear();
        }
        TextToSpeech engine = tts;
        if (engine != null && ttsReady) {
            try {
                engine.stop();
            } catch (Exception ignored) {
                // Engine died — nothing is speaking, which is all stop wanted.
            }
        }
    }

    public void shutdown() {
        TextToSpeech engine;
        synchronized (initLock) {
            pending.clear();
            engine = tts;
            tts = null;
            ttsReady = false;
        }
        if (engine != null) {
            try {
                engine.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    // Must be called with initLock held.
    private void initTts() {
        tts = new TextToSpeech(appContext, status -> {
            List<String[]> toFlush = null;
            synchronized (initLock) {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    try {
                        tts.setLanguage(Locale.US);
                    } catch (Exception ignored) {
                        // Engine keeps its default language; still usable.
                    }
                    ttsReady = true;
                    toFlush = new ArrayList<>(pending);
                } else {
                    ttsFailed = true;
                    GeneralVariables.fileLog("VoiceAnnouncer: TTS init failed status=" + status);
                }
                pending.clear();
            }
            if (toFlush != null) {
                for (String[] entry : toFlush) {
                    // Re-check the gate: TX may have started while init ran.
                    if (!canSpeakNow()) break;
                    speakNow(entry[0], entry[1]);
                }
            }
        });
    }

    private void speakNow(String text, String utteranceId) {
        TextToSpeech engine = tts;
        if (engine == null) return;
        try {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null,
                    utteranceId == null ? String.valueOf(text.hashCode()) : utteranceId);
        } catch (Exception e) {
            GeneralVariables.fileLog("VoiceAnnouncer: speak failed: " + e.getMessage());
        }
    }
}
