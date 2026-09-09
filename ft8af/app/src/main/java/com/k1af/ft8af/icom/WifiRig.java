package com.k1af.ft8af.icom;
/**
 * WiFi mode iCom radio operations.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.icom.IcomUdpBase.IcomUdpStyle;
import com.k1af.ft8af.ui.ToastMessage;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class WifiRig {
    private static final String TAG = "WifiRig";

    public interface OnDataEvents {
        void onReceivedCivData(byte[] data);

        void onReceivedWaveData(byte[] data);
    }

    /**
     * Link lifecycle events, surfaced so the connector can drive the CAT status chip and
     * connect/disconnect toasts (issue #754). Fired from the concrete rig's stream-event
     * handlers via the {@code notify*} helpers below.
     */
    public interface OnLinkStateChanged {
        /**
         * {@link #start()} opened a new UDP session. Fired from inside
         * {@link #beginLinkSession()} — i.e. at the same instant the session counter
         * advances — so a listener that resets per-attempt state here can't be handed an
         * event from the previous session afterwards (every event carries its session id).
         */
        void onSessionBegin(int session);

        void onLoginResult(int session, boolean ok);

        void onSendError(int session);

        void onClosed();
    }

    public OnLinkStateChanged onLinkStateChanged;

    public void setOnLinkStateChanged(OnLinkStateChanged onLinkStateChanged) {
        this.onLinkStateChanged = onLinkStateChanged;
    }

    // Monotonic id of the current UDP session. start() creates a new ControlUdp every
    // attempt but the previous one's stream-event handlers still point at this rig, so a
    // late login response or send failure from the old sockets would otherwise be applied
    // to the new attempt's link state. Concrete rigs capture beginLinkSession() in start()
    // and pass it back through the session-checked notify* overloads.
    private final AtomicInteger linkSession = new AtomicInteger();

    /**
     * Called at the top of {@link #start()}; returns the id the new handlers must carry.
     * Announces the new session to the link-state listener in the same step so its reset
     * and the session advance are one edge (Copilot review on #778: resetting the connector
     * state <em>before</em> {@code start()} left a window where a late event from the old
     * session still matched the current id and landed on the freshly reset state).
     */
    protected int beginLinkSession() {
        int session = linkSession.incrementAndGet();
        if (onLinkStateChanged != null) onLinkStateChanged.onSessionBegin(session);
        return session;
    }

    /** Whether an event tagged {@code session} belongs to the current attempt. */
    protected boolean isCurrentSession(int session) {
        return isCurrentSession(session, linkSession.get());
    }

    /**
     * Gate for a stream-event callback: returns true when {@code session} is current and the
     * callback should run as usual. For a stale session it runs {@code staleTeardown} (the
     * orphaned sockets' own close — never the rig's current {@code controlUdp}) and returns
     * false so the caller skips its toasts, notifications and {@code close()} (Copilot review
     * on #778: a late send error or failed login from the old sockets used to call
     * {@code close()} / {@code controlUdp.closeAll()} on the <em>new</em> session's field).
     */
    protected boolean admitSessionEvent(int session, Runnable staleTeardown) {
        if (isCurrentSession(session)) return true;
        if (staleTeardown != null) {
            try {
                staleTeardown.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "Stale link session " + session + " teardown failed", e);
            }
        }
        return false;
    }

    /** Package-visible for tests. */
    int currentLinkSession() {
        return linkSession.get();
    }

    /** Package-visible for tests: whether an event tagged {@code session} is still current. */
    static boolean isCurrentSession(int session, int current) {
        return session == current;
    }

    /**
     * Concrete rigs call these from their stream-event handlers; null-safe. The unsessioned
     * forms apply to whatever session is current (for callers that have no id to carry).
     */
    protected void notifyLoginResult(boolean ok) {
        notifyLoginResult(linkSession.get(), ok);
    }

    /** Dropped here when {@code session} is stale; the listener re-checks under its own lock. */
    protected void notifyLoginResult(int session, boolean ok) {
        if (!isCurrentSession(session)) return;
        if (onLinkStateChanged != null) onLinkStateChanged.onLoginResult(session, ok);
    }

    protected void notifySendError() {
        notifySendError(linkSession.get());
    }

    /** Dropped here when {@code session} is stale; the listener re-checks under its own lock. */
    protected void notifySendError(int session) {
        if (!isCurrentSession(session)) return;
        if (onLinkStateChanged != null) onLinkStateChanged.onSendError(session);
    }

    protected void notifyClosed() {
        if (onLinkStateChanged != null) onLinkStateChanged.onClosed();
    }

    public ControlUdp controlUdp;
    // volatile: the UDP receive worker reads this to play RX audio while another
    // thread (UI disconnect, or a send-side network error routed through
    // OnUdpSendIOException -> close()) may release and null it in closeAudio().
    public volatile AudioTrack audioTrack = null;
    public final String ip;
    public final int port;
    public final String userName;
    public final String password;
    public boolean opened = false;
    public boolean isPttOn = false;

    public OnDataEvents onDataEvents;


    public WifiRig(String ip, int port, String userName, String password) {
        this.ip = ip;
        this.port = port;
        this.userName = userName;
        this.password = password;
    }


    public abstract void start();

    public abstract void setPttOn(boolean on);

    public abstract void sendCivData(byte[] data);

    public abstract void sendWaveData(float[] data);

    /**
     * Close all connections and audio
     */
    public abstract void close();


    /**
     * Open audio in streaming mode. Play data when audio stream is received
     */
    public void openAudio() {
        if (audioTrack != null) closeAudio();

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat myFormat = new AudioFormat.Builder().setSampleRate(IComPacketTypes.AUDIO_SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        int mySession = 0;

        audioTrack = new AudioTrack(attributes, myFormat
                , IComPacketTypes.AUDIO_SAMPLE_RATE * 4, AudioTrack.MODE_STREAM
                , mySession);
        audioTrack.play();
    }

    /**
     * Play a chunk of received RX audio to the speaker, tolerating a concurrent
     * {@link #closeAudio()} on another thread.
     *
     * <p>The UDP receive worker calls this for every audio packet. A disconnect —
     * either the user tapping disconnect or a send-side network error routed through
     * {@code OnUdpSendIOException -> close() -> closeAudio()} — can release the
     * {@link AudioTrack} while a chunk is in flight. Writing to a released AudioTrack
     * throws {@link IllegalStateException}; uncaught on the receive worker (its loop
     * catches only {@code IOException}) that crashed the whole app. Swallow it and
     * drop the chunk instead.
     */
    public void writeAudio(byte[] audioData) {
        try {
            writeAudioToTrack(audioData);
        } catch (IllegalStateException e) {
            // closeAudio() released the track on another thread between the null-check
            // and the write (disconnect while RX audio was still streaming). Log the
            // throwable for diagnosability. We deliberately do NOT null audioTrack here:
            // closeAudio() already nulls it on the disconnect thread, and clearing it
            // from the RX worker would race a reconnect's fresh AudioTrack and silently
            // clobber it.
            Log.w(TAG, "Dropped RX audio chunk: AudioTrack released mid-write", e);
        }
    }

    // Isolated from writeAudio() so a unit test can drive the released-track path
    // deterministically without emulating AudioTrack's native lifecycle.
    void writeAudioToTrack(byte[] audioData) {
        AudioTrack track = audioTrack; // single volatile read
        if (track == null) return;
        track.write(audioData, 0, audioData.length, AudioTrack.WRITE_NON_BLOCKING);
    }

    /**
     * Close audio
     */
    public void closeAudio() {
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
    }

    public void setOnDataEvents(OnDataEvents onDataEvents) {
        this.onDataEvents = onDataEvents;
    }
}
