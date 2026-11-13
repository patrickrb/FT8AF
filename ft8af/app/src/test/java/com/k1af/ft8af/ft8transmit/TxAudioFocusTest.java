package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.media.AudioManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowAudioManager;

@RunWith(RobolectricTestRunner.class)
public class TxAudioFocusTest {

    private Context context;
    private ShadowAudioManager shadowAudioManager;
    private TxAudioFocus focus;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        shadowAudioManager = shadowOf(am);
        focus = new TxAudioFocus();
    }

    @Test
    public void acquire_granted_isHeld() {
        assertThat(focus.acquire(context)).isTrue();
        assertThat(focus.isHeld()).isTrue();
    }

    @Test
    public void acquire_requestsTransientExclusiveGain() {
        focus.acquire(context);

        assertThat(shadowAudioManager.getLastAudioFocusRequest().durationHint)
                .isEqualTo(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
    }

    @Test
    public void acquire_fromLooperlessWorkerThread_isHeld() throws Exception {
        // acquire() runs on worker threads with no Looper (e.g. the TuneTone
        // thread). The focus-change listener is registered on an explicit
        // main-looper Handler so this can't depend on the calling thread's
        // Looper. Drive acquire() from a raw Thread and confirm it succeeds.
        final boolean[] granted = {false};
        final Throwable[] thrown = {null};
        Thread worker = new Thread(() -> {
            try {
                granted[0] = focus.acquire(context);
            } catch (Throwable t) {
                thrown[0] = t;
            }
        });
        worker.start();
        worker.join();

        assertThat(thrown[0]).isNull();
        assertThat(granted[0]).isTrue();
        assertThat(focus.isHeld()).isTrue();
    }

    @Test
    public void acquire_denied_notHeld() {
        shadowAudioManager.setNextFocusRequestResponse(
                AudioManager.AUDIOFOCUS_REQUEST_FAILED);

        assertThat(focus.acquire(context)).isFalse();
        assertThat(focus.isHeld()).isFalse();
    }

    @Test
    public void acquire_nullContext_notHeld() {
        assertThat(focus.acquire(null)).isFalse();
        assertThat(focus.isHeld()).isFalse();
    }

    @Test
    public void acquire_whileHeld_isIdempotent() {
        assertThat(focus.acquire(context)).isTrue();
        assertThat(focus.acquire(context)).isTrue();

        // A single release fully drops the focus — the second acquire must not
        // have stacked a second request.
        focus.release();
        assertThat(focus.isHeld()).isFalse();
    }

    @Test
    public void release_abandonsTheHeldRequest() {
        focus.acquire(context);

        focus.release();

        assertThat(focus.isHeld()).isFalse();
        assertThat(shadowAudioManager.getLastAbandonedAudioFocusRequest()).isNotNull();
    }

    @Test
    public void release_withoutAcquire_isNoOp() {
        focus.release(); // must not throw

        assertThat(focus.isHeld()).isFalse();
        assertThat(shadowAudioManager.getLastAbandonedAudioFocusRequest()).isNull();
    }

    @Test
    public void release_thenReacquire_works() {
        focus.acquire(context);
        focus.release();

        assertThat(focus.acquire(context)).isTrue();
        assertThat(focus.isHeld()).isTrue();
    }

    @Test
    public void deniedAcquire_thenRelease_abandonsNothing() {
        shadowAudioManager.setNextFocusRequestResponse(
                AudioManager.AUDIOFOCUS_REQUEST_FAILED);
        focus.acquire(context);

        focus.release();

        assertThat(shadowAudioManager.getLastAbandonedAudioFocusRequest()).isNull();
    }
}
