package com.k1af.ft8af.wave;
/**
 * Operations for recording using the microphone.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.ui.ToastMessage;

public class MicRecorder {
    private static final String TAG = "MicRecorder";
    private int bufferSize = 0;//minimum buffer size
    private static final int sampleRateInHz = 12000;//sampling rate
    private static final int channelConfig = AudioFormat.CHANNEL_IN_MONO; //mono
    //private static final int audioFormat = AudioFormat.ENCODING_PCM_16BIT; //quantization bit depth
    private static final int audioFormat = AudioFormat.ENCODING_PCM_FLOAT; //quantization bit depth

    private AudioRecord audioRecord = null;//AudioRecord object
    private UsbAudioDevice usbAudioDevice = null; // USB audio device
    private boolean useUsbAudio = false;

    private volatile boolean isRunning = false;//whether currently in recording state
    private OnDataListener onDataListener;

    // AudioRecord capture-thread handoff. reinitialize() can be called from
    // several threads at once (USB onCaptureStopped, one usbDetach event per
    // interface of a composite device, the reader thread's DEAD_OBJECT path) —
    // a real cable unplug fires three within ~150ms. Each used to release/null
    // the shared audioRecord field and spawn a fresh reader thread while the
    // previous one was still blocked in read(), which is exactly the NPE at
    // read() and the fatal native 'releaseBuffer: mUnreleased out of range'
    // abort seen in the field. Now: lifecycle methods are synchronized, the
    // reader thread only ever touches the AudioRecord instance it was started
    // with, checks its generation each pass, and reinitialize() stops + joins
    // the old thread before releasing the old AudioRecord.
    private Thread captureThread = null;
    private volatile int captureGeneration = 0;
    private static final long CAPTURE_JOIN_TIMEOUT_MS = 1000;

    // USB-audio death-loop guard. A libusb-direct capture session can end
    // prematurely two ways: (1) the host can't drive iso at all and it dies in
    // ~10ms with no data (car-dash Android 11 tablets), or (2) it delivers a
    // little audio then retires every iso transfer ~100ms in (Pixel 8 + C-Media
    // 0D8C:0012 in the field). Both, left unguarded, make MicRecorder
    // reinitialize() in a tight loop that freezes the waterfall and churns
    // libusb_init/exit into a native crash.
    //
    // Guard: UsbCaptureRetryPolicy counts a session as failed when it saw no
    // data OR stayed alive too briefly to be useful, and hands back an
    // exponential, capped backoff. We keep retrying USB at that cadence and
    // never silently switch to the phone's built-in mic — an operator wants
    // radio audio or none.
    private long lastReinitMs = 0;
    private int consecutiveUsbFailures = 0;
    private volatile boolean usbAudioSawData = false;
    // When the current USB capture session started, to measure how long it
    // stayed alive. SystemClock.elapsedRealtime() (monotonic) — not wall clock:
    // a duration must be immune to NTP/user time jumps, which would otherwise
    // yield a negative/huge aliveMs and misclassify the session. 0 = no session
    // started yet.
    private volatile long usbCaptureStartMs = 0;
    private static final long MIN_REINIT_INTERVAL_MS = 2000;
    static final int FALLBACK_BUFFER_SIZE = 4096;

    public interface OnDataListener{
        void onDataReceived(float[] data,int len);
    }

    @SuppressLint("MissingPermission")
    public MicRecorder(){
        GeneralVariables.fileLog(String.format(
                "MicRecorder: init audioInputDeviceId=%d usbVidPid=%04X:%04X",
                GeneralVariables.audioInputDeviceId,
                GeneralVariables.usbAudioInputVendorId,
                GeneralVariables.usbAudioInputProductId));

        // Check if USB audio input is selected
        if (GeneralVariables.audioInputDeviceId == -1
                && GeneralVariables.usbAudioInputVendorId != 0) {
            try {
                usbAudioDevice = openUsbAudioInput();
            } catch (Exception e) {
                GeneralVariables.fileLog(
                        "MicRecorder: USB audio open CRASHED: " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
                Log.e(TAG, "USB audio open failed", e);
                usbAudioDevice = null;
            }
            if (usbAudioDevice != null) {
                useUsbAudio = true;
                UsbAudioDevice.setActiveInputDevice(usbAudioDevice);
                GeneralVariables.fileLog("MicRecorder: using USB audio (direct) input");
                return; // Skip AudioRecord setup
            }
            GeneralVariables.fileLog(
                    "MicRecorder: USB audio open FAILED, falling back to AudioRecord");
        }

        //calculate minimum buffer size
        bufferSize = safeBufferSize(
                AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat));

        int audioSource = chooseAudioSource(Build.VERSION.SDK_INT, isUnprocessedSupported());
        String srcName = audioSourceName(audioSource);
        try {
            audioRecord = new AudioRecord(audioSource, sampleRateInHz
                    , channelConfig, audioFormat, bufferSize);//create AudioRecorder object
        } catch (Exception e) {
            GeneralVariables.fileLog(
                    "MicRecorder: AudioRecord init CRASHED: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
            Log.e(TAG, "AudioRecord init failed", e);
            return; // audioRecord stays null — start() will be a no-op
        }

        //set preferred input device
        if (GeneralVariables.audioInputDeviceId > 0) {
            AudioDeviceInfo deviceInfo = findAudioDeviceById(
                    GeneralVariables.audioInputDeviceId, AudioManager.GET_DEVICES_INPUTS);
            boolean ok = audioRecord.setPreferredDevice(deviceInfo); // null resets to default
            GeneralVariables.fileLog(String.format(
                    "MicRecorder: AudioRecord(%s) preferredDevice id=%d ok=%b deviceFound=%b",
                    srcName, GeneralVariables.audioInputDeviceId, ok, deviceInfo != null));
        } else {
            GeneralVariables.fileLog(
                    "MicRecorder: AudioRecord(" + srcName + ") using system default mic");
        }
    }

    /**
     * Open and configure a USB audio device for input.
     */
    private UsbAudioDevice openUsbAudioInput() {
        Context context = GeneralVariables.getMainContext();
        if (context == null) {
            GeneralVariables.fileLog("openUsbAudioInput: no main context");
            return null;
        }

        UsbDevice device = UsbAudioDevice.findDeviceByVidPid(context,
                GeneralVariables.usbAudioInputVendorId,
                GeneralVariables.usbAudioInputProductId);
        if (device == null) {
            GeneralVariables.fileLog(String.format(
                    "openUsbAudioInput: device not found by VID:PID %04X:%04X",
                    GeneralVariables.usbAudioInputVendorId,
                    GeneralVariables.usbAudioInputProductId));
            return null;
        }
        GeneralVariables.fileLog(String.format(
                "openUsbAudioInput: found device %04X:%04X name=%s",
                device.getVendorId(), device.getProductId(),
                device.getProductName()));

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            GeneralVariables.fileLog("openUsbAudioInput: UsbManager is null");
            return null;
        }
        if (!usbManager.hasPermission(device)) {
            GeneralVariables.fileLog(
                    "openUsbAudioInput: NO USB permission for audio device "
                            + "(grant via unplug/replug or re-select in Settings)");
            return null;
        }

        UsbAudioDevice usbDev = new UsbAudioDevice();
        if (!usbDev.open(context, device)) {
            GeneralVariables.fileLog(
                    "openUsbAudioInput: UsbAudioDevice.open() FAILED "
                            + "(descriptor parse or claimInterface failed)");
            return null;
        }

        if (!usbDev.hasInput()) {
            GeneralVariables.fileLog(
                    "openUsbAudioInput: device has no input endpoint after open");
            usbDev.close();
            return null;
        }

        if (!usbDev.activateInput(48000)) {
            GeneralVariables.fileLog(
                    "openUsbAudioInput: activateInput(48000) FAILED "
                            + "(alt-setting select or endpoint setup failed)");
            usbDev.close();
            return null;
        }

        GeneralVariables.fileLog("openUsbAudioInput: SUCCESS at "
                + usbDev.getInputSampleRate() + " Hz");
        return usbDev;
    }

    /**
     * Find AudioDeviceInfo by device ID
     */
    private AudioDeviceInfo findAudioDeviceById(int deviceId, int deviceType) {
        Context context = GeneralVariables.getMainContext();
        if (context == null) return null;
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return null;
        AudioDeviceInfo[] devices = audioManager.getDevices(deviceType);
        for (AudioDeviceInfo device : devices) {
            if (device.getId() == deviceId) {
                return device;
            }
        }
        return null;
    }

    public synchronized void start(){
        if (isRunning) return;

        if (useUsbAudio && usbAudioDevice != null) {
            isRunning = true;
            startUsbCapture();
        } else if (audioRecord != null) {
            isRunning = true;
            startAudioRecordCapture();
        } else {
            GeneralVariables.fileLog("MicRecorder: start() skipped — no audio source available");
        }
    }

    private void startUsbCapture() {
        final MicRecorder self = this;
        usbAudioSawData = false;
        usbCaptureStartMs = SystemClock.elapsedRealtime();
        usbAudioDevice.startCapture(sampleRateInHz, new UsbAudioDevice.AudioInputCallback() {
            @Override
            public void onAudioData(float[] data, int length) {
                if (length > 0 && !usbAudioSawData) {
                    usbAudioSawData = true;
                    // NB: do not reset consecutiveUsbFailures here. A session
                    // that dies right after delivering a few samples is still a
                    // failure (see UsbCaptureRetryPolicy); the tally is only
                    // reset in onCaptureStopped() when a session stayed alive
                    // long enough to be useful.
                    GeneralVariables.fileLog(
                            "startUsbCapture: first audio data received, "
                                    + "USB stream is live");
                }
                if (isRunning && onDataListener != null) {
                    onDataListener.onDataReceived(data, length);
                }
            }

            @Override
            public void onCaptureStopped() {
                // The libusb-direct capture session ended without an explicit
                // stop. Decide whether it counts as a failure by how long it
                // stayed alive: a session that dies before it can carry a
                // fraction of an FT8 cycle is useless even if a few samples
                // arrived first (the Pixel 8 + C-Media field mode: ~100ms of
                // audio, then every iso transfer retires). The old rule only
                // counted "no data at all", so that mode never tripped the tally
                // and churned a fresh libusb_init/exit every 2s forever — the
                // waterfall freeze, and the churn that raced libusb into a
                // native SIGSEGV.
                long now = SystemClock.elapsedRealtime();
                long aliveMs = now - usbCaptureStartMs;
                boolean failure = UsbCaptureRetryPolicy.isFailure(usbAudioSawData, aliveMs);
                if (failure) {
                    consecutiveUsbFailures++;
                } else {
                    consecutiveUsbFailures = 0;
                }
                GeneralVariables.fileLog(String.format(
                        "startUsbCapture: capture STOPPED (sawData=%b aliveMs=%d "
                                + "failure=%b consecFailures=%d)",
                        usbAudioSawData, aliveMs, failure, consecutiveUsbFailures));

                // Exponential, capped backoff before the next attempt. We keep
                // retrying USB and never silently switch to the phone's built-in
                // mic — an operator wants radio audio or none. A persistently
                // dead adapter is still retried at the cap in case it recovers,
                // without spinning the bus or libusb global state.
                long backoff = UsbCaptureRetryPolicy.backoffMs(consecutiveUsbFailures);
                if (backoff > 0) {
                    GeneralVariables.fileLog(
                            "startUsbCapture: backing off " + backoff
                                    + "ms before USB reinit (consecFailures="
                                    + consecutiveUsbFailures + ")");
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                // A stop may have landed during the backoff (app teardown, or a
                // real unplug already drove reinitialize elsewhere). Don't
                // re-arm capture when we are no longer supposed to be running.
                if (!isRunning) {
                    GeneralVariables.fileLog(
                            "startUsbCapture: not re-arming USB capture "
                                    + "(isRunning=false)");
                    return;
                }
                lastReinitMs = System.currentTimeMillis();
                self.reinitialize();
            }
        });
    }

    private void startAudioRecordCapture() {
        // The reader thread works exclusively on these locals. reinitialize()
        // releases and reassigns the audioRecord/bufferSize *fields* from other
        // threads; reading the fields from the loop is what crashed (NPE at
        // read(), native releaseBuffer abort from two threads on one instance).
        final AudioRecord rec = audioRecord;
        final int myGeneration = captureGeneration;
        float[] buffer = new float[bufferSize];
        try {
            rec.startRecording();//start recording
        }catch (Exception e){
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                    R.string.recorder_cannot_record),e.getMessage()));
            Log.d(TAG, "startRecord: "+e.getMessage() );
        }

        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (captureLoopShouldContinue(isRunning, myGeneration, captureGeneration)) {
                    //check if in recording state; state!=3 means not in recording state
                    if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        // A stale generation means reinitialize() owns the lifecycle
                        // now; only a current-generation thread may stop the capture.
                        if (myGeneration == captureGeneration) {
                            isRunning = false;
                        }
                        Log.d(TAG, String.format("Recording failed, state code: %d", rec.getRecordingState()));
                        break;
                    }

                    //read recording data
                    int bufferReadResult = rec.read(buffer, 0, buffer.length,AudioRecord.READ_BLOCKING);

                    if (bufferReadResult < 0) {
                        // Error codes (ERROR_INVALID_OPERATION=-3, ERROR_BAD_VALUE=-2,
                        // ERROR_DEAD_OBJECT=-6) must never reach onDataReceived as a
                        // length — downstream VoiceDataMonitors would silently stall.
                        GeneralVariables.fileLog(
                                "MicRecorder: AudioRecord.read error " + bufferReadResult);
                        if (bufferReadResult == AudioRecord.ERROR_DEAD_OBJECT) {
                            // A stale thread must not drive lifecycle — a reinit
                            // already replaced it and killing its AudioRecord here
                            // is how the double-reader abort happened. Just exit.
                            if (myGeneration != captureGeneration) {
                                break;
                            }
                            // Route audio server death through the same throttled
                            // reinitialize used by the USB capture-stopped path.
                            isRunning = false;
                            long sinceLast = System.currentTimeMillis() - lastReinitMs;
                            if (sinceLast >= MIN_REINIT_INTERVAL_MS) {
                                lastReinitMs = System.currentTimeMillis();
                                reinitialize();
                            }
                            break;
                        }
                        // Persistent non-fatal errors would otherwise retry in a
                        // tight loop, spamming the log and burning CPU.
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }

                    if (onDataListener!=null && bufferReadResult > 0){
                        onDataListener.onDataReceived(buffer,bufferReadResult);
                    }
                }
                try {
                    if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        rec.stop();//stop recording
                    }
                }catch (Exception e){
                    ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                            R.string.recorder_stop_record_error),e.getMessage()));
                    Log.d(TAG, "startRecord: "+e.getMessage() );
                }
            }
        });
        captureThread.start();
    }

    /**
     * Stop recording. When recording stops, all monitors in the listener list are removed.
     */
    public synchronized void stopRecord() {
        isRunning = false;
        if (useUsbAudio && usbAudioDevice != null) {
            usbAudioDevice.stopCapture();
        }
    }

    public OnDataListener getOnDataListener() {
        return onDataListener;
    }

    public void setOnDataListener(OnDataListener onDataListener) {
        this.onDataListener = onDataListener;
    }

    /**
     * Reinitialize the audio input device. Stops current audio capture,
     * re-checks for USB audio device availability, and restarts if it was running.
     * This handles the case where a USB audio device is connected after MicRecorder
     * was originally constructed.
     */
    @SuppressLint("MissingPermission")
    public synchronized void reinitialize() {
        boolean wasRunning = isRunning;
        OnDataListener savedListener = onDataListener;

        // Supersede the running reader thread's generation, then stop capture.
        captureGeneration++;
        stopRecord();

        // Release old AudioRecord (stopRecord only sets isRunning=false). The
        // reader thread may still be inside a blocking read() on it: stop()
        // unblocks that read, and we must wait for the thread to exit before
        // release() — releasing mid-read is a fatal native abort
        // ('releaseBuffer: mUnreleased out of range'), not a catchable exception.
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                Log.d(TAG, "reinitialize: error stopping AudioRecord: " + e.getMessage());
            }
            joinCaptureThread();
            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.d(TAG, "reinitialize: error releasing AudioRecord: " + e.getMessage());
            }
            audioRecord = null;
        }

        // Close old USB audio device
        if (usbAudioDevice != null) {
            try {
                usbAudioDevice.close();
            } catch (Exception e) {
                Log.d(TAG, "reinitialize: error closing USB audio device: " + e.getMessage());
            }
        }

        // Reset state
        useUsbAudio = false;
        usbAudioDevice = null;

        // Re-check for USB audio input
        if (GeneralVariables.audioInputDeviceId == -1
                && GeneralVariables.usbAudioInputVendorId != 0) {
            try {
                usbAudioDevice = openUsbAudioInput();
            } catch (Exception e) {
                GeneralVariables.fileLog(
                        "MicRecorder: reinit USB audio CRASHED: " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
                Log.e(TAG, "reinitialize: USB audio open failed", e);
                usbAudioDevice = null;
            }
            if (usbAudioDevice != null) {
                useUsbAudio = true;
                UsbAudioDevice.setActiveInputDevice(usbAudioDevice);
                Log.d(TAG, "reinitialize: Using USB audio input device");
            } else {
                Log.w(TAG, "reinitialize: USB audio device not available, falling back to default");
            }
        }

        // Set up standard AudioRecord if not using USB audio
        if (!useUsbAudio) {
            bufferSize = safeBufferSize(
                    AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat));
            int audioSource = chooseAudioSource(Build.VERSION.SDK_INT, isUnprocessedSupported());
            GeneralVariables.fileLog(
                    "MicRecorder: reinit AudioRecord(" + audioSourceName(audioSource) + ")");
            try {
                audioRecord = new AudioRecord(audioSource, sampleRateInHz,
                        channelConfig, audioFormat, bufferSize);
            } catch (Exception e) {
                GeneralVariables.fileLog(
                        "MicRecorder: reinit AudioRecord CRASHED: " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
                Log.e(TAG, "reinitialize: AudioRecord init failed", e);
                // audioRecord stays null — start() will be a no-op
            }

            if (audioRecord != null && GeneralVariables.audioInputDeviceId > 0) {
                AudioDeviceInfo deviceInfo = findAudioDeviceById(
                        GeneralVariables.audioInputDeviceId, AudioManager.GET_DEVICES_INPUTS);
                audioRecord.setPreferredDevice(deviceInfo);
            }
        }

        // Restore listener and restart if it was running
        onDataListener = savedListener;
        if (wasRunning) {
            start();
        }
    }

    /**
     * Wait for the AudioRecord reader thread to exit before its AudioRecord is
     * released. Bounded by {@link #CAPTURE_JOIN_TIMEOUT_MS} so a thread stuck
     * waiting to enter this object's monitor (the reader's DEAD_OBJECT path
     * re-enters reinitialize()) can never deadlock the reinit.
     */
    private void joinCaptureThread() {
        Thread t = captureThread;
        if (!shouldJoinCaptureThread(t, Thread.currentThread())) {
            return;
        }
        try {
            t.join(CAPTURE_JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            GeneralVariables.fileLog("MicRecorder: capture thread still alive after "
                    + CAPTURE_JOIN_TIMEOUT_MS + "ms join; releasing anyway");
        }
    }

    /**
     * Whether the AudioRecord reader loop may run another pass: recording must
     * still be on AND no reinitialize() has superseded this thread's generation.
     * The generation check is what stops a stale thread that never observed
     * {@code isRunning == false} (it was blocked in read() across a full
     * stop/start cycle) from sharing the *new* AudioRecord with the replacement
     * thread — two concurrent readers trip a fatal native abort in libaudioclient.
     *
     * <p>Package-visible for testing.
     */
    static boolean captureLoopShouldContinue(boolean running, int loopGeneration,
                                             int currentGeneration) {
        return running && loopGeneration == currentGeneration;
    }

    /**
     * Whether reinitialize() must wait for the capture thread before releasing
     * the AudioRecord: only when a live reader thread exists and it isn't the
     * calling thread itself — the reader's DEAD_OBJECT path calls
     * reinitialize() from inside the loop, and joining yourself never returns.
     *
     * <p>Package-visible for testing.
     */
    static boolean shouldJoinCaptureThread(Thread capture, Thread current) {
        return capture != null && capture != current && capture.isAlive();
    }

    /**
     * Choose the least-processed recording source available. FT8 (like all
     * digital modes) needs raw audio: the OEM voice DSP behind
     * {@code AudioSource.DEFAULT}/{@code MIC} runs automatic gain control, noise
     * suppression, and multi-mic noise cancellation. That noise canceller uses
     * the phone's *second built-in mic* to subtract what it thinks is background
     * noise, mangling a weak line-in FT8 tone — the parasitic "internal mic"
     * artifact reported in issue #298.
     *
     * <p>{@code UNPROCESSED} (API 24+, only when the device advertises support)
     * guarantees no processing. {@code VOICE_RECOGNITION} is the floor: it
     * disables AGC/NS on most devices and is available back to API 3, so it's
     * safe for our minSdk 23 and for devices lacking UNPROCESSED support.
     *
     * <p>Package-visible for testing.
     */
    static int chooseAudioSource(int sdkInt, boolean unprocessedSupported) {
        if (sdkInt >= Build.VERSION_CODES.N && unprocessedSupported) {
            return MediaRecorder.AudioSource.UNPROCESSED;
        }
        return MediaRecorder.AudioSource.VOICE_RECOGNITION;
    }

    /**
     * Whether the device advertises support for the UNPROCESSED audio source.
     * Returns false on any error or when the property is unknown/unsupported.
     */
    private static boolean isUnprocessedSupported() {
        Context context = GeneralVariables.getMainContext();
        if (context == null) return false;
        AudioManager audioManager =
                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return false;
        String supported = audioManager.getProperty(
                AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
        return "true".equalsIgnoreCase(supported);
    }

    /** Human-readable name for an AudioSource constant, for debug logging. */
    private static String audioSourceName(int source) {
        if (source == MediaRecorder.AudioSource.UNPROCESSED) return "UNPROCESSED";
        if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE_RECOGNITION";
        return String.valueOf(source);
    }

    /**
     * Returns a safe buffer size for AudioRecord. If {@code rawSize} is an
     * error value ({@code <= 0}), returns {@link #FALLBACK_BUFFER_SIZE}.
     *
     * <p>Package-visible for testing.
     */
    static int safeBufferSize(int rawSize) {
        if (rawSize <= 0) {
            Log.e(TAG, "getMinBufferSize returned " + rawSize + ", using fallback");
            return FALLBACK_BUFFER_SIZE;
        }
        return rawSize;
    }

    /**
     * Renders the "time since last reinit" token for the capture-STOPPED log.
     * {@code lastReinitMs == 0} means no reinit has happened yet this session,
     * so the raw {@code now - 0} would print the wall-clock epoch
     * (~1.78e12 ms ≈ 56,000 years) and make the log line useless. Show a
     * sentinel in that case instead of a nonsensical duration.
     *
     * <p>Package-visible for testing.
     */
    static String formatSinceReinit(long now, long lastReinitMs) {
        if (lastReinitMs == 0) {
            return "n/a (no reinit yet)";
        }
        return (now - lastReinitMs) + "ms";
    }
}
