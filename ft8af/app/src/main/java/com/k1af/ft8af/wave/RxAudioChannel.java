package com.k1af.ft8af.wave;

/**
 * RX audio channel selection for stereo inputs.
 *
 * <p>Rigs and interfaces are routinely wired to only one side of a stereo
 * codec — a splitter cable feeding two radios, a dual-receiver rig putting
 * main/sub on L/R, or an interface that leaves the unused channel floating and
 * noisy. Averaging L+R (the historical behaviour, kept as {@link #MIX}) then
 * buries the wanted signal under 3 dB of the other channel's noise, or mixes a
 * second receiver's traffic into the decoder.
 *
 * <p>The operator picks a channel in Settings &gt; Audio; the three RX paths
 * (Android {@code AudioRecord}, the direct-USB {@code UsbRequest} loop, and the
 * libusb native capture) all fold a stereo frame to mono through this class so
 * they behave identically.
 */
public final class RxAudioChannel {
    /** Average both channels — the default, and what every build before this did. */
    public static final int MIX = 0;
    /** Decode the left channel only; the right channel is discarded. */
    public static final int LEFT = 1;
    /** Decode the right channel only; the left channel is discarded. */
    public static final int RIGHT = 2;

    /** Config-table key the selection is persisted under. */
    public static final String CONFIG_KEY = "rxAudioChannel";

    private RxAudioChannel() {
    }

    /** Clamp an arbitrary int to a valid selection, defaulting to {@link #MIX}. */
    public static int clamp(int selection) {
        return (selection == LEFT || selection == RIGHT) ? selection : MIX;
    }

    /**
     * Parse a persisted config value. The config table holds free-form strings
     * and settings import (#382) can feed a corrupted one through at startup, so
     * anything non-numeric or out of range falls back to {@link #MIX} rather
     * than throwing on the load path.
     */
    public static int parse(String value) {
        if (value == null) return MIX;
        try {
            return clamp(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return MIX;
        }
    }

    /**
     * Whether this selection needs both channels delivered to us. Only true for
     * {@link #LEFT}/{@link #RIGHT}: {@link #MIX} is exactly what the audio
     * framework produces from a mono open, so the default path is untouched.
     */
    public static boolean needsStereoCapture(int selection) {
        return clamp(selection) != MIX;
    }

    /** Fold one stereo float frame to mono per {@code selection}. */
    public static float foldFrame(float left, float right, int selection) {
        switch (clamp(selection)) {
            case LEFT:
                return left;
            case RIGHT:
                return right;
            default:
                return (left + right) * 0.5f;
        }
    }

    /**
     * Fold one 16-bit stereo PCM frame to a float in [-1, 1). Used by both USB
     * capture paths, which see raw int16 rather than floats.
     */
    public static float foldPcmFrame(short left, short right, int selection) {
        switch (clamp(selection)) {
            case LEFT:
                return left / 32768.0f;
            case RIGHT:
                return right / 32768.0f;
            default:
                return (left + right) * (0.5f / 32768.0f);
        }
    }

    /**
     * Fold an interleaved stereo float buffer down to mono.
     *
     * @param interleaved L,R,L,R... samples
     * @param sampleCount valid samples in {@code interleaved} (not frames); an
     *                    odd count means a torn frame, whose trailing sample is
     *                    dropped rather than paired with the next read's
     *                    left sample
     * @param selection   one of {@link #MIX}/{@link #LEFT}/{@link #RIGHT}
     * @param out         destination, must hold at least {@code sampleCount / 2}
     * @return number of mono samples written to {@code out}
     */
    public static int foldToMono(float[] interleaved, int sampleCount, int selection,
                                 float[] out) {
        if (interleaved == null || out == null || sampleCount <= 1) return 0;
        int frames = Math.min(sampleCount, interleaved.length) / 2;
        if (frames > out.length) frames = out.length;
        int sel = clamp(selection);
        for (int i = 0; i < frames; i++) {
            out[i] = foldFrame(interleaved[i * 2], interleaved[i * 2 + 1], sel);
        }
        return frames;
    }
}
