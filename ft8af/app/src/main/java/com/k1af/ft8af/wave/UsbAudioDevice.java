package com.k1af.ft8af.wave;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * USB Audio Class 1.0 device handler for direct USB audio communication.
 * Bypasses Android's audio framework to work with USB audio devices
 * (e.g., DigiRig CM108) that the kernel audio driver doesn't support.
 */
public class UsbAudioDevice {
    private static final String TAG = "UsbAudioDevice";

    public static final int USB_CLASS_AUDIO = 0x01;
    public static final int USB_SUBCLASS_AUDIOCONTROL = 0x01;
    public static final int USB_SUBCLASS_AUDIOSTREAMING = 0x02;

    private static final int SET_CUR = 0x01;
    private static final int SAMPLING_FREQ_CONTROL = 0x01;

    // Number of URBs for isochronous ring buffer
    private static final int NUM_URBS = 16;

    private UsbDevice usbDevice;
    private UsbDeviceConnection connection;

    // Input (microphone)
    private UsbInterface streamingInterfaceIn;
    private UsbEndpoint endpointIn;
    private int inputSampleRate = 48000;
    private int inputChannels = 1;
    private volatile boolean capturing = false;
    // Non-zero when the libusb-backed capture session is live; in that case
    // captureLoop() is bypassed and stopCapture() routes through native.
    private volatile long nativeCaptureHandle = 0;

    // Output (speaker)
    private UsbInterface streamingInterfaceOut;
    private UsbEndpoint endpointOut;
    private int outputSampleRate = 48000;
    private int outputChannels = 2;

    // Singleton active device for use by MicRecorder / FT8TransmitSignal
    private static UsbAudioDevice activeInputDevice;
    private static UsbAudioDevice activeOutputDevice;

    public interface AudioInputCallback {
        void onAudioData(float[] data, int length);
        /**
         * Fired on a worker thread when the capture loop exits without
         * stopCapture() being called — e.g. the USB device was disconnected
         * or the kernel returned a null URB. Default is a no-op so existing
         * callers compile unchanged.
         */
        default void onCaptureStopped() {}
    }

    /**
     * Scan for USB devices with USB Audio Class streaming interfaces.
     */
    public static List<UsbAudioDeviceInfo> findUsbAudioDevices(Context context) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return Collections.emptyList();

        List<UsbAudioDeviceInfo> result = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            boolean hasInput = false;
            boolean hasOutput = false;

            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                if (iface.getInterfaceClass() == USB_CLASS_AUDIO
                        && iface.getInterfaceSubclass() == USB_SUBCLASS_AUDIOSTREAMING
                        && iface.getEndpointCount() > 0) {

                    for (int j = 0; j < iface.getEndpointCount(); j++) {
                        UsbEndpoint ep = iface.getEndpoint(j);
                        if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                            if (ep.getDirection() == UsbConstants.USB_DIR_IN) hasInput = true;
                            if (ep.getDirection() == UsbConstants.USB_DIR_OUT) hasOutput = true;
                        }
                    }
                }
            }

            if (hasInput || hasOutput) {
                result.add(new UsbAudioDeviceInfo(device, hasInput, hasOutput));
            }
        }
        return result;
    }

    /**
     * Find a USB audio device by vendor and product ID.
     */
    public static UsbDevice findDeviceByVidPid(Context context, int vendorId, int productId) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getVendorId() == vendorId && device.getProductId() == productId) {
                for (int i = 0; i < device.getInterfaceCount(); i++) {
                    UsbInterface iface = device.getInterface(i);
                    if (iface.getInterfaceClass() == USB_CLASS_AUDIO) {
                        return device;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Open the USB audio device and discover audio endpoints.
     */
    public boolean open(Context context, UsbDevice device) {
        this.usbDevice = device;
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return false;

        if (!usbManager.hasPermission(device)) {
            Log.e(TAG, "No USB permission for audio device");
            return false;
        }

        this.connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Failed to open USB audio device");
            return false;
        }

        findEndpoints();
        return endpointIn != null || endpointOut != null;
    }

    private void findEndpoints() {
        for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
            UsbInterface iface = usbDevice.getInterface(i);
            if (iface.getInterfaceClass() != USB_CLASS_AUDIO
                    || iface.getInterfaceSubclass() != USB_SUBCLASS_AUDIOSTREAMING
                    || iface.getEndpointCount() == 0) {
                continue;
            }

            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN && endpointIn == null) {
                        streamingInterfaceIn = iface;
                        endpointIn = ep;
                        Log.d(TAG, "Found audio input endpoint: addr=0x"
                                + Integer.toHexString(ep.getAddress())
                                + " maxPacket=" + ep.getMaxPacketSize());
                    } else if (ep.getDirection() == UsbConstants.USB_DIR_OUT && endpointOut == null) {
                        streamingInterfaceOut = iface;
                        endpointOut = ep;
                        Log.d(TAG, "Found audio output endpoint: addr=0x"
                                + Integer.toHexString(ep.getAddress())
                                + " maxPacket=" + ep.getMaxPacketSize());
                    }
                }
            }
        }

        // Detect channel count from max packet size
        // USB audio at 48kHz, 16-bit: mono=96 bytes/ms, stereo=192 bytes/ms
        if (endpointIn != null) {
            int mps = endpointIn.getMaxPacketSize();
            // samples_per_ms = sampleRate / 1000 = 48
            // bytes_per_ms = samples_per_ms * channels * 2 (16-bit)
            inputChannels = mps / (48 * 2);
            if (inputChannels < 1) inputChannels = 1;
            if (inputChannels > 2) inputChannels = 2;
            Log.d(TAG, "Input channels detected: " + inputChannels);
        }
        if (endpointOut != null) {
            int mps = endpointOut.getMaxPacketSize();
            outputChannels = mps / (48 * 2);
            if (outputChannels < 1) outputChannels = 1;
            if (outputChannels > 2) outputChannels = 2;
            Log.d(TAG, "Output channels detected: " + outputChannels);
        }
    }

    /**
     * Activate the audio input (microphone) stream.
     */
    public boolean activateInput(int sampleRate) {
        if (streamingInterfaceIn == null || endpointIn == null) return false;

        if (!connection.claimInterface(streamingInterfaceIn, true)) {
            Log.e(TAG, "Failed to claim input interface");
            return false;
        }

        connection.setInterface(streamingInterfaceIn);

        inputSampleRate = sampleRate;
        setSampleRate(endpointIn.getAddress(), sampleRate);
        // If setSampleRate fails, the device may use its default rate (usually 48kHz)
        // which is fine — we resample anyway

        Log.d(TAG, "Input activated at " + inputSampleRate + " Hz");
        return true;
    }

    /**
     * Activate the audio output (speaker) stream.
     */
    public boolean activateOutput(int sampleRate) {
        if (streamingInterfaceOut == null || endpointOut == null) return false;

        if (!connection.claimInterface(streamingInterfaceOut, true)) {
            Log.e(TAG, "Failed to claim output interface");
            return false;
        }

        connection.setInterface(streamingInterfaceOut);

        outputSampleRate = sampleRate;
        setSampleRate(endpointOut.getAddress(), sampleRate);

        Log.d(TAG, "Output activated at " + outputSampleRate + " Hz");
        return true;
    }

    private void setSampleRate(int endpointAddress, int sampleRate) {
        byte[] data = new byte[3];
        data[0] = (byte) (sampleRate & 0xFF);
        data[1] = (byte) ((sampleRate >> 8) & 0xFF);
        data[2] = (byte) ((sampleRate >> 16) & 0xFF);

        int result = connection.controlTransfer(
                0x22, // USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_ENDPOINT
                SET_CUR,
                SAMPLING_FREQ_CONTROL << 8,
                endpointAddress,
                data, data.length, 1000);

        if (result < 0) {
            Log.w(TAG, "setSampleRate failed for rate " + sampleRate
                    + " endpoint 0x" + Integer.toHexString(endpointAddress)
                    + " result=" + result);
        }
    }

    /**
     * Start continuous audio capture. Data is delivered to the callback
     * as mono float at targetSampleRate, matching MicRecorder's format.
     */
    public void startCapture(int targetSampleRate, AudioInputCallback callback) {
        if (endpointIn == null) return;
        capturing = true;

        // Prefer the libusb-backed native path. On hosts where Android's
        // UsbRequest can't drive iso transfers (notably automotive Android
        // 11 tablets), the UsbRequest fallback below throws
        // IllegalStateException on first queue. libusb talks to USBDEVFS
        // directly and works where UsbRequest doesn't.
        if (UsbAudioNative.isAvailable() && connection != null
                && streamingInterfaceIn != null) {
            int fd = connection.getFileDescriptor();
            int ifaceNum = streamingInterfaceIn.getId();
            int altSet = streamingInterfaceIn.getAlternateSetting();
            int epAddr = endpointIn.getAddress();
            int maxPkt = endpointIn.getMaxPacketSize();

            com.k1af.ft8af.GeneralVariables.fileLog(String.format(
                    "UsbAudioDevice: trying libusb native capture "
                            + "fd=%d iface=%d alt=%d ep=0x%02x maxPkt=%d "
                            + "inputRate=%d ch=%d targetRate=%d",
                    fd, ifaceNum, altSet, epAddr, maxPkt,
                    inputSampleRate, inputChannels, targetSampleRate));

            final AudioInputCallback javaCb = callback;
            long handle = UsbAudioNative.nativeStart(
                    fd, ifaceNum, altSet, epAddr, maxPkt,
                    inputSampleRate, inputChannels, /*bytesPerSample=*/2,
                    targetSampleRate,
                    new UsbAudioNative.AudioInputCallback() {
                        @Override
                        public void onAudioData(float[] data, int length) {
                            if (capturing && javaCb != null) {
                                javaCb.onAudioData(data, length);
                            }
                        }

                        @Override
                        public void onCaptureStopped(int code) {
                            com.k1af.ft8af.GeneralVariables.fileLog(
                                    "UsbAudioDevice: libusb capture stopped, "
                                            + "code=" + code);
                            nativeCaptureHandle = 0;
                            capturing = false;
                            if (javaCb != null) javaCb.onCaptureStopped();
                        }
                    });

            if (handle != 0) {
                nativeCaptureHandle = handle;
                com.k1af.ft8af.GeneralVariables.fileLog(
                        "UsbAudioDevice: libusb capture started OK");
                return;
            }

            com.k1af.ft8af.GeneralVariables.fileLog(
                    "UsbAudioDevice: libusb start FAILED, "
                            + "falling back to UsbRequest path");
        }

        // Fallback: original UsbRequest-based loop. Kept so devices that work
        // with Android's iso path don't regress on the new native lib.
        final int packetSize = endpointIn.getMaxPacketSize();
        final int ratio = inputSampleRate / targetSampleRate;

        new Thread(() -> {
            captureLoop(targetSampleRate, ratio, packetSize, callback);
        }, "USB-Audio-Capture").start();
    }

    @SuppressWarnings("deprecation")
    private void captureLoop(int targetRate, int ratio, int packetSize,
                             AudioInputCallback callback) {
        ByteBuffer[] buffers = new ByteBuffer[NUM_URBS];
        UsbRequest[] requests = new UsbRequest[NUM_URBS];

        try {
            // Initialize and queue URBs
            for (int i = 0; i < NUM_URBS; i++) {
                buffers[i] = ByteBuffer.allocateDirect(packetSize);
                buffers[i].order(ByteOrder.LITTLE_ENDIAN);
                requests[i] = new UsbRequest();
                requests[i].initialize(connection, endpointIn);
                requests[i].setClientData(i);
                buffers[i].clear();
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    requests[i].queue(buffers[i]);
                } else {
                    requests[i].queue(buffers[i], packetSize);
                }
            }

            // Anti-aliased decimation (same windowed-sinc FIR as the native libusb
            // path) — the old per-window average was a box filter whose ~12 dB
            // stopband folded hiss into the FT8 passband whenever this fallback ran.
            // One clamped ratio drives both the FIR and the mismatch warning, so a
            // degenerate ratio (0/negative) is reported with the value actually used.
            final int decimRatio = Math.max(1, ratio);
            FirDecimator decimator = new FirDecimator(decimRatio);
            if (decimRatio * targetRate != inputSampleRate) {
                com.k1af.ft8af.GeneralVariables.fileLog(String.format(
                        "UsbAudio.captureLoop: input %d Hz is not %dx target %d Hz "
                                + "(raw ratio %d) — decode may suffer from the "
                                + "resulting rate error",
                        inputSampleRate, decimRatio, targetRate, ratio));
            }
            float[] monoBuffer = new float[1];
            float[] outputBuffer = new float[1];

            int iterations = 0;
            while (capturing) {
                UsbRequest completed = connection.requestWait();
                if (completed == null) {
                    if (capturing) {
                        com.k1af.ft8af.GeneralVariables.fileLog(String.format(
                                "UsbAudio.captureLoop: requestWait returned null "
                                        + "after %d iterations (target=%dHz "
                                        + "input=%dHz channels=%d packetSize=%d "
                                        + "ratio=%d) — host likely cannot drive "
                                        + "isochronous transfers via UsbRequest",
                                iterations, targetRate, inputSampleRate,
                                inputChannels, packetSize, ratio));
                    }
                    break;
                }
                iterations++;

                int bufIndex = (int) completed.getClientData();
                ByteBuffer buf = buffers[bufIndex];

                // Process received audio data (16-bit PCM)
                buf.flip();
                int bytesReceived = buf.remaining();
                int totalSamples = bytesReceived / 2; // 16-bit = 2 bytes

                // int16 -> float mono (average L+R when stereo, matching the
                // native libusb path — the old code dropped the right channel).
                int monoSamples = (inputChannels == 2) ? totalSamples / 2 : totalSamples;
                if (monoBuffer.length < monoSamples) {
                    monoBuffer = new float[monoSamples];
                }
                int monoCount = 0;
                if (inputChannels == 2) {
                    while (buf.remaining() >= 4 && monoCount < monoSamples) {
                        short l = buf.getShort();
                        short r = buf.getShort();
                        monoBuffer[monoCount++] = (l + r) * (0.5f / 32768.0f);
                    }
                } else {
                    while (buf.remaining() >= 2 && monoCount < monoSamples) {
                        monoBuffer[monoCount++] = buf.getShort() / 32768.0f;
                    }
                }

                // FIR-decimate and deliver; the decimator carries state across
                // packets, so no leftover-sample bookkeeping is needed.
                if (monoCount > 0) {
                    int maxOut = monoCount / decimator.ratio() + 1;
                    if (outputBuffer.length < maxOut) {
                        outputBuffer = new float[maxOut];
                    }
                    int outputSamples = decimator.process(monoBuffer, monoCount, outputBuffer);
                    if (outputSamples > 0) {
                        callback.onAudioData(outputBuffer, outputSamples);
                    }
                }

                // Re-queue the URB
                buf.clear();
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    completed.queue(buffers[bufIndex]);
                } else {
                    completed.queue(buffers[bufIndex], packetSize);
                }
            }
        } catch (Exception e) {
            com.k1af.ft8af.GeneralVariables.fileLog(
                    "UsbAudio.captureLoop: exception — "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            for (int i = 0; i < NUM_URBS; i++) {
                if (requests[i] != null) {
                    try { requests[i].cancel(); } catch (Exception ignored) {}
                    try { requests[i].close(); } catch (Exception ignored) {}
                }
            }
            // If `capturing` is still true here, we exited without an explicit
            // stopCapture() — the device died on us. Notify upstream so the
            // recorder can rebind (e.g. fall back to the built-in mic) instead
            // of stalling on a dead handle forever.
            boolean abnormalExit = capturing;
            capturing = false;
            if (abnormalExit && callback != null) {
                final AudioInputCallback cb = callback;
                new Thread(() -> {
                    try { cb.onCaptureStopped(); } catch (Exception ignored) {}
                }, "USB-Audio-Capture-Stopped").start();
            }
        }
    }

    public void stopCapture() {
        capturing = false;
        long h = nativeCaptureHandle;
        if (h != 0) {
            nativeCaptureHandle = 0;
            try {
                UsbAudioNative.nativeStop(h);
            } catch (Throwable t) {
                Log.w(TAG, "nativeStop threw: " + t.getMessage());
            }
        }
    }

    /**
     * Write audio data to the USB output device.
     * Handles sample rate conversion and format conversion.
     *
     * <p>The {@code audioData} is full-scale (TX volume is no longer baked in by
     * the caller). Gain is applied live as the buffer drains so a slider move
     * takes effect mid-over: the native path multiplies each sample in its drain
     * loop (see {@code nativeSetTxVolume}); the UsbRequest fallback below scales
     * each chunk by the current {@link GeneralVariables#volumePercent} as it
     * sends. This is the rig-protection path — pulling the slider down attenuates
     * the in-progress transmission within tens of ms.
     *
     * @param audioData        Float PCM mono data, full-scale (no volume baked in)
     * @param sourceSampleRate Sample rate of the input data
     * @return true if successful
     */
    @SuppressWarnings("deprecation")
    public boolean writeAudio(float[] audioData, int sourceSampleRate) {
        if (endpointOut == null || connection == null) return false;

        // Start this transmission with a clear cancel flag. STOP sets it (via
        // UsbAudioNative.cancelWrite) to abort either the native or fallback path.
        UsbAudioNative.resetCancel();
        // Seed the native drain loop with the current level before it starts, so
        // the first packet is already at the right gain (the live observer keeps
        // it updated thereafter).
        UsbAudioNative.setTxVolume(com.k1af.ft8af.GeneralVariables.volumePercent);

        // Resample to device's output rate
        float[] resampled;
        if (sourceSampleRate != outputSampleRate) {
            resampled = resample(audioData, sourceSampleRate, outputSampleRate);
        } else {
            resampled = audioData;
        }

        // Convert mono float to 16-bit PCM (stereo if device is stereo)
        int samplesPerChannel = resampled.length;
        byte[] pcmData = new byte[samplesPerChannel * outputChannels * 2];
        ByteBuffer bb = ByteBuffer.wrap(pcmData);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < samplesPerChannel; i++) {
            short s = (short) Math.max(-32768, Math.min(32767, resampled[i] * 32768.0f));
            bb.putShort(s); // left (or mono)
            if (outputChannels == 2) {
                bb.putShort(s); // right = same as left
            }
        }

        // Prefer the libusb-backed native path. Same reason as input: on hosts
        // where Android's UsbRequest can't drive iso (notably car-dash kernels)
        // request.initialize() returns false and writeAudio fails immediately,
        // which aborts the TX after ~200ms. libusb talks to USBDEVFS directly.
        if (UsbAudioNative.isAvailable() && streamingInterfaceOut != null) {
            int fd = connection.getFileDescriptor();
            int ifaceNum = streamingInterfaceOut.getId();
            int altSet = streamingInterfaceOut.getAlternateSetting();
            int epAddr = endpointOut.getAddress();
            int maxPkt = endpointOut.getMaxPacketSize();

            com.k1af.ft8af.GeneralVariables.fileLog(String.format(
                    "UsbAudioDevice: trying libusb native write "
                            + "fd=%d iface=%d alt=%d ep=0x%02x maxPkt=%d "
                            + "bytes=%d outputRate=%d ch=%d",
                    fd, ifaceNum, altSet, epAddr, maxPkt,
                    pcmData.length, outputSampleRate, outputChannels));

            int rc = UsbAudioNative.nativeWrite(
                    fd, ifaceNum, altSet, epAddr, maxPkt,
                    outputSampleRate, outputChannels, /*bytesPerSample=*/2,
                    pcmData);

            if (rc == 0) {
                com.k1af.ft8af.GeneralVariables.fileLog(
                        "UsbAudioDevice: libusb native write OK");
                return true;
            }
            com.k1af.ft8af.GeneralVariables.fileLog(
                    "UsbAudioDevice: libusb native write FAILED ("
                            + describeLibusbWriteError(rc)
                            + "), trying UsbRequest fallback");
        }

        // Fallback: original UsbRequest-based write. Kept so devices that work
        // through Android's iso path don't regress on the new native lib.
        // Write in chunks matching max packet size
        int packetSize = endpointOut.getMaxPacketSize();
        int offset = 0;

        while (offset < pcmData.length) {
            // STOP pressed mid-transmission: abort between chunks so audio halts
            // immediately instead of draining the full ~12.6s message.
            if (UsbAudioNative.writeCancelled) {
                Log.d(TAG, "writeAudio cancelled at offset " + offset);
                return false;
            }
            int chunkSize = Math.min(packetSize, pcmData.length - offset);
            ByteBuffer buf = ByteBuffer.allocateDirect(chunkSize);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            // Apply the current TX gain to this chunk as we copy it (pcmData is
            // full-scale). Read once per chunk (~1ms of audio) so dragging the
            // slider attenuates the in-progress TX almost immediately. Unity is
            // a straight copy.
            float vol = com.k1af.ft8af.GeneralVariables.volumePercent;
            if (vol >= 0.999f) {
                buf.put(pcmData, offset, chunkSize);
            } else {
                int pairs = chunkSize / 2;
                for (int p = 0; p < pairs; p++) {
                    int b = offset + p * 2;
                    short s = (short) ((pcmData[b] & 0xFF) | (pcmData[b + 1] << 8));
                    int scaled = (int) (s * vol);
                    if (scaled > 32767) scaled = 32767;
                    else if (scaled < -32768) scaled = -32768;
                    buf.put((byte) (scaled & 0xFF));
                    buf.put((byte) ((scaled >> 8) & 0xFF));
                }
                // Odd trailing byte (not expected for 16-bit audio) — copy as-is.
                if ((chunkSize & 1) == 1) {
                    buf.put(pcmData[offset + chunkSize - 1]);
                }
            }
            buf.flip();

            // Whole iteration is guarded: if the underlying USB connection has
            // been torn down (cable yanked, kernel renumeration, selective
            // suspend), initialize() returns false and queue()/requestWait()
            // throw IllegalStateException. Catching here turns a fatal process
            // crash into a clean TX abort that the caller already handles.
            UsbRequest request = new UsbRequest();
            try {
                if (!request.initialize(connection, endpointOut)) {
                    Log.e(TAG, "request.initialize returned false at offset " + offset
                            + " (USB connection likely closed)");
                    try { request.close(); } catch (Exception ignored) {}
                    return false;
                }

                boolean queued;
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    queued = request.queue(buf);
                } else {
                    queued = request.queue(buf, chunkSize);
                }
                if (!queued) {
                    Log.e(TAG, "Failed to queue output URB at offset " + offset);
                    try { request.close(); } catch (Exception ignored) {}
                    return false;
                }

                UsbRequest completed = connection.requestWait();
                if (completed != null) {
                    try { completed.close(); } catch (Exception ignored) {}
                }
            } catch (IllegalStateException | NullPointerException e) {
                Log.e(TAG, "writeAudio aborting at offset " + offset + ": " + e.getMessage());
                try { request.close(); } catch (Exception ignored) {}
                return false;
            }

            offset += chunkSize;
        }

        return true;
    }

    /**
     * Linear interpolation resampler.
     */
    private float[] resample(float[] input, int fromRate, int toRate) {
        if (fromRate == toRate) return input;

        double ratio = (double) toRate / fromRate;
        int outputLen = (int) (input.length * ratio);
        float[] output = new float[outputLen];

        for (int i = 0; i < outputLen; i++) {
            double srcIndex = i / ratio;
            int idx = (int) srcIndex;
            double frac = srcIndex - idx;

            if (idx + 1 < input.length) {
                output[i] = (float) (input[idx] * (1 - frac) + input[idx + 1] * frac);
            } else if (idx < input.length) {
                output[i] = input[idx];
            }
        }

        return output;
    }

    public void close() {
        stopCapture();
        if (connection != null) {
            try {
                if (streamingInterfaceIn != null) {
                    connection.releaseInterface(streamingInterfaceIn);
                }
            } catch (Exception ignored) {}
            try {
                if (streamingInterfaceOut != null) {
                    connection.releaseInterface(streamingInterfaceOut);
                }
            } catch (Exception ignored) {}
            connection.close();
            connection = null;
        }
    }

    /**
     * Maps a {@link UsbAudioNative#nativeWrite} return code to a readable label
     * for the debug log. {@code nativeWrite} returns 0 on success or a libusb
     * {@code libusb_error} code (always negative) on failure; naming the code
     * makes a dropped TX cycle diagnosable instead of an opaque {@code rc=-4}.
     * A device-level error ({@code NO_DEVICE}, {@code NOT_FOUND}) means the
     * UsbRequest fallback below will almost certainly fail too.
     *
     * <p>Any value outside the known enum (e.g. an unexpected positive code) is
     * labelled {@code UNKNOWN} rather than guessed at. Package-visible for
     * testing.
     */
    static String describeLibusbWriteError(int rc) {
        String name;
        switch (rc) {
            case 0:   name = "SUCCESS"; break;
            case -1:  name = "IO"; break;
            case -2:  name = "INVALID_PARAM"; break;
            case -3:  name = "ACCESS"; break;
            case -4:  name = "NO_DEVICE"; break;
            case -5:  name = "NOT_FOUND"; break;
            case -6:  name = "BUSY"; break;
            case -7:  name = "TIMEOUT"; break;
            case -8:  name = "OVERFLOW"; break;
            case -9:  name = "PIPE"; break;
            case -10: name = "INTERRUPTED"; break;
            case -11: name = "NO_MEM"; break;
            case -12: name = "NOT_SUPPORTED"; break;
            case -99: name = "OTHER"; break;
            default:  name = "UNKNOWN"; break;
        }
        return "rc=" + rc + " " + name;
    }

    public boolean hasInput() { return endpointIn != null; }
    public boolean hasOutput() { return endpointOut != null; }
    public UsbDevice getUsbDevice() { return usbDevice; }
    public int getInputSampleRate() { return inputSampleRate; }
    public int getOutputSampleRate() { return outputSampleRate; }

    // --- Singleton management for active devices ---

    public static void setActiveInputDevice(UsbAudioDevice device) {
        if (activeInputDevice != null && activeInputDevice != device) {
            activeInputDevice.stopCapture();
        }
        activeInputDevice = device;
    }

    public static UsbAudioDevice getActiveInputDevice() {
        return activeInputDevice;
    }

    public static void setActiveOutputDevice(UsbAudioDevice device) {
        activeOutputDevice = device;
    }

    public static UsbAudioDevice getActiveOutputDevice() {
        return activeOutputDevice;
    }

    public static void closeActiveDevices() {
        if (activeInputDevice != null) {
            activeInputDevice.close();
            activeInputDevice = null;
        }
        if (activeOutputDevice != null) {
            activeOutputDevice.close();
            activeOutputDevice = null;
        }
    }

    /**
     * Describes a discovered USB audio device.
     */
    public static class UsbAudioDeviceInfo {
        public final UsbDevice device;
        public final boolean hasInput;
        public final boolean hasOutput;

        public UsbAudioDeviceInfo(UsbDevice device, boolean hasInput, boolean hasOutput) {
            this.device = device;
            this.hasInput = hasInput;
            this.hasOutput = hasOutput;
        }

        public String getDisplayName() {
            String name = device.getProductName();
            if (name == null || name.isEmpty()) {
                name = String.format("USB Audio [%04X:%04X]",
                        device.getVendorId(), device.getProductId());
            }
            return name + " (USB direct)";
        }
    }
}
