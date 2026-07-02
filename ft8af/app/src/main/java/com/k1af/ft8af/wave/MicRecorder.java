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

    // USB-audio death-loop guard. On some hosts (notably car-dash Android
    // tablets) UsbRequest.requestWait() returns null on first iteration and
    // onCaptureStopped() fires immediately. Without this, MicRecorder would
    // reinitialize() in a ~30ms-per-cycle tight loop. We rate-limit the
    // self-rebind and after MAX_CONSECUTIVE_USB_FAILURES failed cycles give up
    // on the raw USB path and fall back to AudioRecord+default mic so the app
    // keeps running (read-only) instead of spinning the USB bus.
    private long lastReinitMs = 0;
    private int consecutiveUsbFailures = 0;
    private volatile boolean usbAudioSawData = false;
    private static final long MIN_REINIT_INTERVAL_MS = 2000;
    private static final int MAX_CONSECUTIVE_USB_FAILURES = 3;
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

        GeneralVariables.fileLog("openUsbAudioInput: SUCCESS at 48000 Hz");
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

    public void start(){
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
        usbAudioDevice.startCapture(sampleRateInHz, new UsbAudioDevice.AudioInputCallback() {
            @Override
            public void onAudioData(float[] data, int length) {
                if (length > 0 && !usbAudioSawData) {
                    usbAudioSawData = true;
                    consecutiveUsbFailures = 0;
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
                // The USB audio capture loop exited without an explicit stop.
                // Two distinct cases we need to handle differently:
                //   1. Genuine device-gone / temporary glitch — rebind once,
                //      hope the next attempt sticks.
                //   2. Host can't drive isochronous transfers and requestWait()
                //      returns null on the first iteration — capture dies in
                //      ~10ms. Without a guard, reinitialize -> open -> start ->
                //      die -> reinitialize... spins forever at ~30ms per cycle
                //      (saw this on a car-dash Android 11 tablet).
                // Strategy: rate-limit to one reinit per MIN_REINIT_INTERVAL_MS,
                // and after MAX_CONSECUTIVE_USB_FAILURES cycles without ever
                // seeing audio data, force-fall-back to AudioRecord. The app
                // can't transmit through USB then but at least stops thrashing.
                if (!usbAudioSawData) {
                    consecutiveUsbFailures++;
                }
                long now = System.currentTimeMillis();
                long sinceLast = now - lastReinitMs;
                GeneralVariables.fileLog(String.format(
                        "startUsbCapture: capture STOPPED (sawData=%b "
                                + "consecFailures=%d sinceLastReinit=%s)",
                        usbAudioSawData, consecutiveUsbFailures,
                        formatSinceReinit(now, lastReinitMs)));

                if (consecutiveUsbFailures >= MAX_CONSECUTIVE_USB_FAILURES
                        && !usbAudioSawData) {
                    GeneralVariables.fileLog(
                            "startUsbCapture: giving up on raw USB after "
                                    + consecutiveUsbFailures
                                    + " failures, forcing fallback to AudioRecord");
                    // Temporarily blank the USB selection so reinitialize()
                    // takes the AudioRecord branch instead of looping.
                    GeneralVariables.audioInputDeviceId = 0;
                    self.reinitialize();
                    return;
                }

                if (sinceLast < MIN_REINIT_INTERVAL_MS) {
                    GeneralVariables.fileLog(
                            "startUsbCapture: throttling reinit (last was "
                                    + sinceLast + "ms ago)");
                    try {
                        Thread.sleep(MIN_REINIT_INTERVAL_MS - sinceLast);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                lastReinitMs = System.currentTimeMillis();
                self.reinitialize();
            }
        });
    }

    private void startAudioRecordCapture() {
        float[] buffer = new float[bufferSize];
        try {
            audioRecord.startRecording();//start recording
        }catch (Exception e){
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                    R.string.recorder_cannot_record),e.getMessage()));
            Log.d(TAG, "startRecord: "+e.getMessage() );
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    //check if in recording state; state!=3 means not in recording state
                    if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        isRunning = false;
                        Log.d(TAG, String.format("Recording failed, state code: %d", audioRecord.getRecordingState()));
                        break;
                    }

                    //read recording data
                    int bufferReadResult = audioRecord.read(buffer, 0, bufferSize,AudioRecord.READ_BLOCKING);

                    if (bufferReadResult < 0) {
                        // Error codes (ERROR_INVALID_OPERATION=-3, ERROR_BAD_VALUE=-2,
                        // ERROR_DEAD_OBJECT=-6) must never reach onDataReceived as a
                        // length — downstream VoiceDataMonitors would silently stall.
                        GeneralVariables.fileLog(
                                "MicRecorder: AudioRecord.read error " + bufferReadResult);
                        if (bufferReadResult == AudioRecord.ERROR_DEAD_OBJECT) {
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
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();//stop recording
                    }
                }catch (Exception e){
                    ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                            R.string.recorder_stop_record_error),e.getMessage()));
                    Log.d(TAG, "startRecord: "+e.getMessage() );
                }
            }
        }).start();
    }

    /**
     * Stop recording. When recording stops, all monitors in the listener list are removed.
     */
    public void stopRecord() {
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
    public void reinitialize() {
        boolean wasRunning = isRunning;
        OnDataListener savedListener = onDataListener;

        // Stop current capture
        stopRecord();

        // Release old AudioRecord (stopRecord only sets isRunning=false)
        if (audioRecord != null) {
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
