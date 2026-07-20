package com.k1af.ft8af.ft8transmit;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.k1af.ft8af.GeneralVariables;

/**
 * Holds transient-exclusive audio focus for the duration of one transmission.
 *
 * The AudioTrack TX path plays through Android's shared mixer, so any other
 * app's sound (touch clicks, notifications, media) routed to the same output
 * device gets mixed into the audio feeding the rig and transmitted on air.
 * AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE asks the system to suppress notification
 * sounds and other apps to pause while we hold it. Focus is advisory — a
 * denied request must never block TX, so acquire() failures are log-only.
 *
 * The direct-USB (libusb) TX path bypasses the mixer entirely and does not
 * need this.
 */
public class TxAudioFocus {

    private final Object lock = new Object();
    private AudioManager audioManager;      // non-null only while focus is held
    private AudioFocusRequest focusRequest; // API 26+ handle, valid while held

    // Focus loss is log-only: an in-flight FT8 transmission must complete on
    // the cycle boundary no matter what another app requests.
    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
            focusChange -> GeneralVariables.fileLog(
                    "TxAudioFocus: focus change during TX: " + focusChange);

    // acquire() is called from worker threads (e.g. the TuneTone `new Thread(...)`
    // path) that have no Looper. Both the AudioFocusRequest.Builder listener and
    // the legacy requestAudioFocus() register their callback on a Handler; without
    // an explicit one they can bind to the calling thread's (missing) Looper. Pin
    // the callbacks to the main looper so acquire() is safe from any thread.
    private final Handler focusListenerHandler = new Handler(Looper.getMainLooper());

    /**
     * Request transient-exclusive focus. Idempotent while held.
     *
     * @return true if focus is now held; false if the context/service is
     * unavailable or the system denied the request (TX proceeds regardless).
     */
    public boolean acquire(Context context) {
        synchronized (lock) {
            if (audioManager != null) {
                return true; // already held
            }
            if (context == null) {
                return false;
            }
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return false;
            }
            int result;
            AudioFocusRequest request = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                request = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusChangeListener, focusListenerHandler)
                        .build();
                result = am.requestAudioFocus(request);
            } else {
                result = am.requestAudioFocus(focusChangeListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioManager = am;
                focusRequest = request;
                return true;
            }
            return false;
        }
    }

    /** Abandon focus if held; safe no-op otherwise. */
    public void release() {
        synchronized (lock) {
            if (audioManager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(focusChangeListener);
            }
            audioManager = null;
            focusRequest = null;
        }
    }

    /** Whether focus is currently held. */
    public boolean isHeld() {
        synchronized (lock) {
            return audioManager != null;
        }
    }
}
