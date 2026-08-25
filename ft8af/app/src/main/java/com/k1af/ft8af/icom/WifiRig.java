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
        void onLoginResult(boolean ok);

        void onSendError();

        void onClosed();
    }

    public OnLinkStateChanged onLinkStateChanged;

    public void setOnLinkStateChanged(OnLinkStateChanged onLinkStateChanged) {
        this.onLinkStateChanged = onLinkStateChanged;
    }

    /** Concrete rigs call these from their stream-event handlers; null-safe. */
    protected void notifyLoginResult(boolean ok) {
        if (onLinkStateChanged != null) onLinkStateChanged.onLoginResult(ok);
    }

    protected void notifySendError() {
        if (onLinkStateChanged != null) onLinkStateChanged.onSendError();
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
