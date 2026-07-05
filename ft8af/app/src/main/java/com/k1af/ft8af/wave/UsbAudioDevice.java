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
    private static final int GET_CUR = 0x81;
    private static final int SAMPLING_FREQ_CONTROL = 0x01;

    // The FT8 decoder's sample rate; used to rank fallback rates when a device
    // can't stream the preferred 48 kHz (integer multiples of this avoid the
    // rational resampling stage entirely).
    private static final int FT8_DECODER_RATE = 12000;

    // Number of URBs for isochronous ring buffer
    private static final int NUM_URBS = 16;

    private UsbDevice usbDevice;
    private UsbDeviceConnection connection;

    // Input (microphone)
    private UsbInterface streamingInterfaceIn;
    private UsbEndpoint endpointIn;
    private int inputSampleRate = 48000;
    private int inputChannels = 1;
    // True when inputChannels came from parsed UAC descriptors (authoritative); false
    // while it is only the wMaxPacketSize-derived guess, which must be re-derived once
    // the real sample rate is known (issue #400).
    private boolean inputChannelsFromDescriptors = false;
    // UAC format descriptors parsed during selectInputAltSetting(); consulted again
    // when SET_CUR fails to work out what rate the device is actually running.
    private List<UacAudioFormats.StreamFormat> inputFormats = Collections.emptyList();
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

        // Detect channel count from max packet size, provisionally assuming the default
        // 48 kHz until a stream is activated — activateInput()/activateOutput() re-derive
        // the count from the rate actually in use (issue #400: a 44.1 kHz stereo endpoint
        // judged against the 48 kHz frame size counts as mono).
        if (endpointIn != null) {
            inputChannels = channelsForMaxPacketSize(endpointIn.getMaxPacketSize(), 48000);
            inputChannelsFromDescriptors = false;
            Log.d(TAG, "Input channels detected: " + inputChannels);
        }
        if (endpointOut != null) {
            outputChannels = channelsForMaxPacketSize(endpointOut.getMaxPacketSize(), 48000);
            Log.d(TAG, "Output channels detected: " + outputChannels);
        }
    }

    /**
     * Channel count implied by an isochronous audio endpoint's {@code wMaxPacketSize} at a
     * given sample rate (16-bit samples, full-speed USB: one packet per 1 ms frame, sized
     * to carry {@code rate/1000} samples per channel). Rounded, not truncated: a 44.1 kHz
     * endpoint carries 44/45 samples per frame, so its packet size is not an exact multiple
     * of the per-channel byte rate. Clamped to [1, 2] — this pipeline handles mono/stereo.
     *
     * <p>An implausible rate (see {@link #isPlausibleRate}: outside the 8-768 kHz UAC
     * hardware range, e.g. from garbage descriptor data) fails safe to mono rather than
     * letting a tiny divisor round up to "stereo".
     *
     * <p>Package-visible for tests.
     */
    static int channelsForMaxPacketSize(int maxPacketSize, int sampleRateHz) {
        if (!isPlausibleRate(sampleRateHz)) return 1;
        double bytesPerFramePerChannel = (sampleRateHz / 1000.0) * 2.0;
        int channels = (int) Math.round(maxPacketSize / bytesPerFramePerChannel);
        if (channels < 1) return 1;
        if (channels > 2) return 2;
        return channels;
    }

    /**
     * Activate the audio input (microphone) stream.
     *
     * <p>{@code preferredRate} (48 kHz in practice) is a preference, not an assumption
     * (issue #364): the device's UAC format descriptors are parsed and an alt setting that
     * actually supports the preferred rate is selected when one exists. When the device can't
     * do it at 16-bit, the best rate it really supports is negotiated instead and
     * {@link #getInputSampleRate()} reports it, so the capture path can resample rationally
     * (e.g. 44.1 kHz -> 12 kHz via a 40/147 polyphase) rather than mislabeling the stream.
     */
    public boolean activateInput(int preferredRate) {
        if (streamingInterfaceIn == null || endpointIn == null) return false;

        int negotiatedRate = selectInputAltSetting(preferredRate);

        if (!connection.claimInterface(streamingInterfaceIn, true)) {
            Log.e(TAG, "Failed to claim input interface");
            return false;
        }

        connection.setInterface(streamingInterfaceIn);

        if (setSampleRate(endpointIn.getAddress(), negotiatedRate)) {
            inputSampleRate = negotiatedRate;
        } else {
            // SET_CUR failed, so the device is not necessarily running negotiatedRate —
            // and the downstream resample ratio is derived from inputSampleRate, so
            // assuming wrong here re-creates exactly the mislabeled-stream failure of
            // issue #364. Fall back to the most defensible rate: ask the device via
            // GET_CUR; else, if the active alt setting supports exactly one rate, the
            // device must be running that (SET_CUR is often unimplemented precisely
            // because there is nothing to choose); else keep the negotiated assumption.
            int reportedRate = getCurrentSampleRate(endpointIn.getAddress());
            int[] altRates = UacAudioFormats.discreteRatesFor(
                    inputFormats, streamingInterfaceIn.getId(),
                    streamingInterfaceIn.getAlternateSetting());
            inputSampleRate = resolveRateAfterSetCurFailure(
                    negotiatedRate, reportedRate, altRates);
            com.k1af.ft8af.GeneralVariables.fileLog(String.format(
                    "UsbAudioDevice: SET_CUR %d Hz FAILED on ep 0x%02x — GET_CUR says %d, "
                            + "alt setting offers %s — assuming %d Hz for resampling",
                    negotiatedRate, endpointIn.getAddress(), reportedRate,
                    java.util.Arrays.toString(altRates), inputSampleRate));
        }

        // The findEndpoints() channel-count guess divides wMaxPacketSize by the 48 kHz
        // frame size. When descriptor parsing yielded no channel count (the legacy
        // fallback paths above) and the stream actually runs at another rate, that guess
        // is wrong — a 44.1 kHz stereo endpoint (~180-byte packets) counts as mono and
        // the capture loop de-interleaves garbage (issue #400). Re-derive from the rate
        // we just settled on.
        if (!inputChannelsFromDescriptors) {
            int detected = channelsForMaxPacketSize(endpointIn.getMaxPacketSize(), inputSampleRate);
            if (detected != inputChannels) {
                com.k1af.ft8af.GeneralVariables.fileLog(String.format(
                        "UsbAudioDevice: input channel guess corrected %d -> %d "
                                + "(wMaxPacketSize=%d at %d Hz)",
                        inputChannels, detected, endpointIn.getMaxPacketSize(), inputSampleRate));
                inputChannels = detected;
            }
        }

        Log.d(TAG, "Input activated at " + inputSampleRate + " Hz");
        return true;
    }

    /**
     * Decides the sample rate to report for the input stream after the
     * SAMPLING_FREQ_CONTROL SET_CUR request failed, i.e. when the rate we asked for was
     * not acknowledged by the device. Ranked by how much each source can be trusted:
     *
     * <ol>
     *   <li>{@code reportedRate} — what the device itself says it is running (GET_CUR on
     *       the sampling-frequency control), when the readback succeeded and the value is
     *       plausible. A live answer from the device beats any inference.</li>
     *   <li>The active alt setting's sole supported rate, when its descriptors list
     *       exactly one: a single-rate endpoint runs that rate no matter what — such
     *       devices commonly omit SAMPLING_FREQ_CONTROL entirely, which is why the
     *       SET_CUR (and GET_CUR) fail in the first place.</li>
     *   <li>{@code requestedRate} — with multiple candidate rates and no readback the
     *       truth is genuinely unknown; keep the negotiated assumption (it at least came
     *       from the device's own descriptors) and let the caller log loudly.</li>
     * </ol>
     *
     * <p>Package-visible for testing.
     *
     * @param requestedRate    the rate SET_CUR tried to set (from descriptor negotiation)
     * @param reportedRate     GET_CUR readback, or negative when the readback failed
     * @param altSettingRates  the active alt setting's known discrete rates (empty when
     *                         unknown or continuous)
     */
    static int resolveRateAfterSetCurFailure(int requestedRate, int reportedRate,
                                             int[] altSettingRates) {
        if (isPlausibleRate(reportedRate)) {
            return reportedRate;
        }
        if (altSettingRates != null && altSettingRates.length == 1
                && isPlausibleRate(altSettingRates[0])) {
            return altSettingRates[0];
        }
        return requestedRate;
    }

    /**
     * Whether a GET_CUR readback (or descriptor value) looks like a real audio sample
     * rate. UAC 1.0 hardware spans 8 kHz telephony codecs to 384/768 kHz DACs; anything
     * outside that is a failed or garbage control transfer, not a rate. Package-visible
     * for testing.
     */
    static boolean isPlausibleRate(int rate) {
        return rate >= 8000 && rate <= 768000;
    }

    /**
     * Parse the device's UAC descriptors and prefer an alt setting of the input streaming
     * interface that supports {@code preferredRate}; otherwise fall back to the best rate it
     * does support. May reassign {@link #streamingInterfaceIn}/{@link #endpointIn} to a
     * different alt setting of the same interface, and {@link #inputChannels} to the
     * descriptor-reported channel count (more reliable than the packet-size guess in
     * {@link #findEndpoints()}, which assumes 48 kHz).
     *
     * @return the sample rate to negotiate; when descriptor parsing yields nothing usable,
     *     returns {@code preferredRate} and leaves the selection untouched (legacy behavior).
     */
    private int selectInputAltSetting(int preferredRate) {
        byte[] raw = null;
        try {
            raw = connection.getRawDescriptors();
        } catch (Exception e) {
            Log.w(TAG, "getRawDescriptors threw: " + e.getMessage());
        }
        List<UacAudioFormats.StreamFormat> formats = UacAudioFormats.parse(raw);
        inputFormats = formats;
        final int ifaceId = streamingInterfaceIn.getId();
        final int currentAlt = streamingInterfaceIn.getAlternateSetting();

        UacAudioFormats.Choice choice = UacAudioFormats.choose(
                formats, ifaceId, currentAlt, preferredRate, FT8_DECODER_RATE);
        if (choice == null) {
            com.k1af.ft8af.GeneralVariables.fileLog(String.format(
                    "UsbAudioDevice: no usable UAC format descriptors for iface %d — "
                            + "assuming %d Hz (legacy behavior)", ifaceId, preferredRate));
            return preferredRate;
        }

        if (choice.altSetting != currentAlt) {
            UsbInterface target = null;
            UsbEndpoint targetEp = null;
            for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                UsbInterface iface = usbDevice.getInterface(i);
                if (iface.getId() != ifaceId
                        || iface.getAlternateSetting() != choice.altSetting) {
                    continue;
                }
                for (int j = 0; j < iface.getEndpointCount(); j++) {
                    UsbEndpoint ep = iface.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC
                            && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        targetEp = ep;
                        break;
                    }
                }
                if (targetEp != null) target = iface;
                break;
            }
            if (target != null) {
                streamingInterfaceIn = target;
                endpointIn = targetEp;
            } else {
                // Android's view doesn't expose the alt setting the raw descriptors
                // promised; re-choose among the current alt setting only.
                final int wantedAlt = choice.altSetting;
                List<UacAudioFormats.StreamFormat> currentOnly = new ArrayList<>();
                for (UacAudioFormats.StreamFormat f : formats) {
                    if (f.interfaceId == ifaceId && f.altSetting == currentAlt) {
                        currentOnly.add(f);
                    }
                }
                choice = UacAudioFormats.choose(
                        currentOnly, ifaceId, currentAlt, preferredRate, FT8_DECODER_RATE);
                if (choice == null) {
                    com.k1af.ft8af.GeneralVariables.fileLog(String.format(
                            "UsbAudioDevice: alt %d not visible via UsbManager and current "
                                    + "alt %d has no usable format — assuming %d Hz (legacy)",
                            wantedAlt, currentAlt, preferredRate));
                    return preferredRate;
                }
            }
        }

        if (choice.channels == 1 || choice.channels == 2) {
            inputChannels = choice.channels;
            inputChannelsFromDescriptors = true;
        }
        com.k1af.ft8af.GeneralVariables.fileLog(String.format(
                "UsbAudioDevice: UAC alt-setting select — iface %d alt %d rate %d ch %d "
                        + "(preferred %d, was alt %d)",
                ifaceId, streamingInterfaceIn.getAlternateSetting(), choice.sampleRate,
                inputChannels, preferredRate, currentAlt));
        return choice.sampleRate;
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

        // Same correction as the input side (issue #400): the findEndpoints() guess
        // assumed 48 kHz; re-derive the channel count from the rate actually requested.
        outputChannels = channelsForMaxPacketSize(endpointOut.getMaxPacketSize(), sampleRate);

        Log.d(TAG, "Output activated at " + outputSampleRate + " Hz");
        return true;
    }

    /** @return true when the device acknowledged the SET_CUR request. */
    private boolean setSampleRate(int endpointAddress, int sampleRate) {
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
        return result >= 0;
    }

    /**
     * Reads back the endpoint's current sampling frequency (UAC 1.0 GET_CUR on the
     * sampling-frequency control).
     *
     * @return the device-reported rate in Hz, or -1 when the request failed or returned
     *     fewer than the 3 bytes the control is defined to carry
     */
    private int getCurrentSampleRate(int endpointAddress) {
        byte[] data = new byte[3];
        int result = connection.controlTransfer(
                0xA2, // USB_DIR_IN | USB_TYPE_CLASS | USB_RECIP_ENDPOINT
                GET_CUR,
                SAMPLING_FREQ_CONTROL << 8,
                endpointAddress,
                data, data.length, 1000);
        if (result < 3) return -1;
        return (data[0] & 0xFF) | ((data[1] & 0xFF) << 8) | ((data[2] & 0xFF) << 16);
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
                                            + "code=" + code + " ("
                                            + describeCaptureStopCode(code) + ")");
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

        new Thread(() -> {
            captureLoop(targetSampleRate, packetSize, callback);
        }, "USB-Audio-Capture").start();
    }

    @SuppressWarnings("deprecation")
    private void captureLoop(int targetRate, int packetSize,
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

            // Rate conversion (same DSP as the native libusb path): integer FIR
            // decimation when the input rate is an exact multiple of the target
            // (48k -> 12k), otherwise a true rational polyphase resampler (e.g.
            // 44.1k -> 12k = interpolate 40 / decimate 147). Flooring the ratio
            // here used to mislabel 14.7 kHz audio as 12 kHz and shift every FT8
            // tone off the 6.25 Hz grid (issue #364).
            final boolean rational = targetRate > 0 && inputSampleRate > targetRate
                    && inputSampleRate % targetRate != 0;
            final FirDecimator decimator;
            final RationalResampler resampler;
            if (rational) {
                int[] lm = RationalResampler.ratioFor(inputSampleRate, targetRate);
                resampler = new RationalResampler(lm[0], lm[1]);
                decimator = null;
                com.k1af.ft8af.GeneralVariables.fileLog(String.format(
                        "UsbAudio.captureLoop: input %d Hz is not an integer multiple "
                                + "of target %d Hz — using rational polyphase "
                                + "resampler L/M=%d/%d",
                        inputSampleRate, targetRate, lm[0], lm[1]));
            } else {
                final int decimRatio =
                        FirDecimator.decimationRatioFor(inputSampleRate, targetRate);
                decimator = new FirDecimator(decimRatio);
                resampler = null;
                if (decimRatio * targetRate != inputSampleRate) {
                    com.k1af.ft8af.GeneralVariables.fileLog(String.format(
                            "UsbAudio.captureLoop: degenerate rates (input %d Hz, "
                                    + "target %d Hz) — passing audio through at "
                                    + "ratio %d, decode may suffer",
                            inputSampleRate, targetRate, decimRatio));
                }
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
                                        + "rational=%b) — host likely cannot drive "
                                        + "isochronous transfers via UsbRequest",
                                iterations, targetRate, inputSampleRate,
                                inputChannels, packetSize, rational));
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

                // Resample and deliver; both stages carry state across packets,
                // so no leftover-sample bookkeeping is needed.
                if (monoCount > 0) {
                    int maxOut = (resampler != null)
                            ? resampler.maxOutputFor(monoCount)
                            : monoCount / decimator.ratio() + 1;
                    if (outputBuffer.length < maxOut) {
                        outputBuffer = new float[maxOut];
                    }
                    int outputSamples = (resampler != null)
                            ? resampler.process(monoBuffer, monoCount, outputBuffer)
                            : decimator.process(monoBuffer, monoCount, outputBuffer);
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

            // Monotonic clock: wall time can jump (NTP set, user change) and a
            // backwards step would fake a fast failure, wrongly allowing the
            // restart-from-zero fallback after audio already went to air.
            long writeStartMs = android.os.SystemClock.elapsedRealtime();
            int rc = UsbAudioNative.nativeWrite(
                    fd, ifaceNum, altSet, epAddr, maxPkt,
                    outputSampleRate, outputChannels, /*bytesPerSample=*/2,
                    pcmData);
            long writeElapsedMs = android.os.SystemClock.elapsedRealtime() - writeStartMs;

            if (rc == 0) {
                com.k1af.ft8af.GeneralVariables.fileLog(
                        "UsbAudioDevice: libusb native write OK");
                return true;
            }
            if (!shouldFallbackToUsbRequest(rc, writeElapsedMs)) {
                // Mid-stream death (part of the message already went to air) or
                // the device left the bus: the UsbRequest loop below restarts
                // the message from byte 0, which this deep into the slot would
                // transmit an off-grid, overlapping mess — drop the cycle
                // instead. The QSO sequencer self-corrects next cycle.
                com.k1af.ft8af.GeneralVariables.fileLog(
                        "UsbAudioDevice: libusb native write FAILED ("
                                + describeLibusbWriteError(rc)
                                + ") after " + writeElapsedMs
                                + "ms, no UsbRequest fallback (mid-stream or device gone)");
                return false;
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
     * for the debug log. {@code nativeWrite} returns 0 on success, a libusb
     * {@code libusb_error} code (negative) for setup failures, or — when a
     * transfer dies after streaming started — the failing transfer's
     * {@code libusb_transfer_status} (positive, stored by
     * {@code onOutputComplete} in {@code usb_audio_capture.cpp}). The positive
     * statuses were previously logged as {@code UNKNOWN}, which hid the actual
     * field failure mode: {@code rc=5 TRANSFER_NO_DEVICE}, the device falling
     * off the bus mid-transmission (typically RF into the USB link at TX
     * power). Naming the code makes a dropped TX cycle diagnosable.
     *
     * <p>Package-visible for testing.
     */
    /**
     * Decodes the {@code onCaptureStopped(code)} reason set by the native capture
     * event loop ({@code usb_audio_capture.cpp}, see {@code recordStopReason}) into
     * a human-readable phrase for {@code debug.log}. This is the diagnostic that
     * pins down why isochronous capture retires on a given host+adapter combo,
     * which the native {@code ft8af_usb_capture} logcat tag does not reliably
     * surface in the field. Encoding:
     *
     * <ul>
     *   <li>{@code 0} — clean stop (an explicit {@code nativeStop})</li>
     *   <li>{@code 1} — all transfers retired with no terminal cause recorded</li>
     *   <li>{@code 1000+status} — a transfer completed with terminal
     *       {@code libusb_transfer_status} (e.g. {@code 1005} = NO_DEVICE)</li>
     *   <li>{@code 2000+(-err)} — {@code libusb_submit_transfer} refused to
     *       re-arm a transfer (e.g. {@code 2004} = NO_DEVICE)</li>
     *   <li>{@code 3000+(-err)} — {@code libusb_handle_events} failed</li>
     * </ul>
     *
     * <p>Package-visible for testing.
     */
    static String describeCaptureStopCode(int code) {
        if (code == 0) return "clean stop (nativeStop)";
        if (code == 1) return "all transfers retired (no terminal cause)";
        if (code >= 1000 && code < 2000) {
            return "transfer terminal status " + transferStatusName(code - 1000);
        }
        if (code >= 2000 && code < 3000) {
            return "resubmit failed: " + describeLibusbWriteError(-(code - 2000));
        }
        if (code >= 3000 && code < 4000) {
            return "handle_events failed: " + describeLibusbWriteError(-(code - 3000));
        }
        return "unknown code";
    }

    /** {@code libusb_transfer_status} name. Package-visible for testing. */
    static String transferStatusName(int status) {
        switch (status) {
            case 0:  return "COMPLETED";
            case 1:  return "ERROR";
            case 2:  return "TIMED_OUT";
            case 3:  return "CANCELLED";
            case 4:  return "STALL";
            case 5:  return "NO_DEVICE";
            case 6:  return "OVERFLOW";
            default: return "UNKNOWN(" + status + ")";
        }
    }

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
            // libusb_transfer_status values (positive) from a mid-stream death:
            case 1:   name = "TRANSFER_ERROR"; break;
            case 2:   name = "TRANSFER_TIMED_OUT"; break;
            case 3:   name = "TRANSFER_CANCELLED"; break;
            case 4:   name = "TRANSFER_STALL"; break;
            case 5:   name = "TRANSFER_NO_DEVICE"; break;
            case 6:   name = "TRANSFER_OVERFLOW"; break;
            default:  name = "UNKNOWN"; break;
        }
        return "rc=" + rc + " " + name;
    }

    /**
     * Whether a failed libusb native write should be retried through the
     * Android {@code UsbRequest} fallback loop.
     *
     * <p>The fallback restarts the message from byte 0. That is only sane when
     * the native attempt failed <em>before anything went to air</em> — e.g. the
     * libusb context/wrap/submit failed immediately on a kernel where libusb
     * can't run (the fallback's original purpose). Once the native write has
     * streamed for a while ({@code elapsedMs} beyond
     * {@link #MAX_FALLBACK_ELAPSED_MS}), part of the FT8 message has already
     * been transmitted; restarting from the beginning mid-slot would key an
     * off-grid, overlapping signal that no receiver can decode — worse than
     * dropping the cycle. Device-gone ({@code NO_DEVICE}/{@code
     * TRANSFER_NO_DEVICE}) and cancelled ({@code TRANSFER_CANCELLED}, the user
     * pressed STOP) failures never retry either, regardless of timing.
     *
     * <p>Package-visible for testing.
     *
     * @param rc        non-zero {@link UsbAudioNative#nativeWrite} return code
     * @param elapsedMs how long the native write ran before failing
     */
    static boolean shouldFallbackToUsbRequest(int rc, long elapsedMs) {
        if (rc == 0) return false;// success — nothing to fall back from
        if (rc == -4 || rc == 5) return false;// device left the bus
        if (rc == 3) return false;// cancelled: the user stopped the TX
        return elapsedMs <= MAX_FALLBACK_ELAPSED_MS;
    }

    /**
     * Longest a failed native write may have run and still be treated as
     * "failed before streaming": ~1 s is far below the earliest point where
     * restarted audio could still produce a decodable message, and far above
     * any immediate setup failure.
     */
    static final long MAX_FALLBACK_ELAPSED_MS = 1_000;

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
