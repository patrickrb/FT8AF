package com.k1af.ft8af.wave;

/**
 * Which side of a stereo audio path the app uses, independently for receive
 * and transmit.
 *
 * <p>Rigs and interfaces are routinely wired to only one side of a stereo
 * codec — a splitter cable feeding two radios, a dual-receiver rig putting
 * main/sub on L/R, or an interface that leaves the unused channel floating and
 * noisy. On receive, averaging L+R (the historical behaviour, kept as
 * {@link #BOTH}) then buries the wanted signal under 3 dB of the other
 * channel's noise, or mixes a second receiver's traffic into the decoder. On
 * transmit, duplicating the waveform onto both channels (also the historical
 * behaviour, again {@link #BOTH}) drives whichever rig the other side of a
 * splitter cable is wired to.
 *
 * <p>{@link #BOTH} means "mix L+R" on receive and "send the same audio to both
 * channels" on transmit — in each direction it is exactly what every build
 * before the channel selector did, which is why it is the default on both
 * sides. The UI labels it "Mix" for RX and "Both" for TX; the code uses the one
 * constant.
 *
 * <p>The operator picks a channel per direction in Settings &gt; Radio &amp;
 * Audio &gt; Audio. The three RX capture paths (Android {@code AudioRecord},
 * the direct-USB {@code UsbRequest} loop, and the libusb native capture) fold a
 * stereo frame to mono through this class, and the two TX paths (the
 * {@code AudioTrack} sound-card sink and the direct-USB writer) expand mono to
 * stereo through it, so every path behaves identically.
 */
public final class AudioChannelSelect {
    /**
     * Use both channels — the default in both directions, and what every build
     * before this did. RX averages L+R; TX sends the same samples to each.
     */
    public static final int BOTH = 0;
    /** Left channel only; the right channel is discarded (RX) or silent (TX). */
    public static final int LEFT = 1;
    /** Right channel only; the left channel is discarded (RX) or silent (TX). */
    public static final int RIGHT = 2;

    /** Config-table key the receive selection is persisted under. */
    public static final String RX_CONFIG_KEY = "rxAudioChannel";
    /** Config-table key the transmit selection is persisted under. */
    public static final String TX_CONFIG_KEY = "txAudioChannel";

    /** Channel index of the left channel in an interleaved stereo frame. */
    public static final int CHANNEL_LEFT = 0;
    /** Channel index of the right channel in an interleaved stereo frame. */
    public static final int CHANNEL_RIGHT = 1;

    private AudioChannelSelect() {
    }

    /** Clamp an arbitrary int to a valid selection, defaulting to {@link #BOTH}. */
    public static int clamp(int selection) {
        return (selection == LEFT || selection == RIGHT) ? selection : BOTH;
    }

    /**
     * Parse a persisted config value. The config table holds free-form strings
     * and settings import (#382) can feed a corrupted one through at startup, so
     * anything non-numeric or out of range falls back to {@link #BOTH} rather
     * than throwing on the load path.
     */
    public static int parse(String value) {
        if (value == null) return BOTH;
        try {
            return clamp(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return BOTH;
        }
    }

    /**
     * Whether this selection needs both channels delivered to us on capture.
     * Only true for {@link #LEFT}/{@link #RIGHT}: {@link #BOTH} is exactly what
     * the audio framework produces from a mono open, so the default path is
     * untouched.
     */
    public static boolean needsStereoCapture(int selection) {
        return clamp(selection) != BOTH;
    }

    /**
     * Whether this selection needs a stereo playback open. Only true for
     * {@link #LEFT}/{@link #RIGHT}: a mono open is already duplicated to both
     * channels by the framework and by every UAC device, which is what
     * {@link #BOTH} means, so the default TX path keeps its historical mono
     * {@code AudioTrack} untouched. Changing that path is not free — see the FT8
     * TX audio pipeline notes in CLAUDE.md — so it only happens when the
     * operator actually asks for one side.
     */
    public static boolean needsStereoPlayback(int selection) {
        return clamp(selection) != BOTH;
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
     * @param selection   one of {@link #BOTH}/{@link #LEFT}/{@link #RIGHT}
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

    /**
     * Whether the given channel of an interleaved output frame carries the
     * waveform for this selection. The excluded channel is written as digital
     * silence rather than left untouched — a UAC device plays exactly the bytes
     * it is handed, so stale buffer contents would go out as noise on the
     * channel the operator asked to keep quiet.
     *
     * @param selection    one of {@link #BOTH}/{@link #LEFT}/{@link #RIGHT}
     * @param channelIndex {@link #CHANNEL_LEFT} or {@link #CHANNEL_RIGHT}; any
     *                     other index (a device with more channels than we
     *                     drive) is silent
     */
    public static boolean writesChannel(int selection, int channelIndex) {
        switch (clamp(selection)) {
            case LEFT:
                return channelIndex == CHANNEL_LEFT;
            case RIGHT:
                return channelIndex == CHANNEL_RIGHT;
            default:
                return channelIndex == CHANNEL_LEFT || channelIndex == CHANNEL_RIGHT;
        }
    }

    /**
     * Expand a mono TX buffer into interleaved stereo, silencing the channel the
     * operator excluded. Used by the {@code AudioTrack} sound-card path, which
     * must hand the framework a full interleaved frame once it opens stereo.
     *
     * @param mono      source samples
     * @param count     valid samples in {@code mono}
     * @param selection one of {@link #BOTH}/{@link #LEFT}/{@link #RIGHT}
     * @param out       destination, must hold at least {@code count * 2}
     * @return number of interleaved samples written to {@code out} (2 per frame)
     */
    public static int expandToStereo(float[] mono, int count, int selection, float[] out) {
        if (mono == null || out == null || count <= 0) return 0;
        int frames = Math.min(count, mono.length);
        if (frames * 2 > out.length) frames = out.length / 2;
        int sel = clamp(selection);
        boolean left = writesChannel(sel, CHANNEL_LEFT);
        boolean right = writesChannel(sel, CHANNEL_RIGHT);
        for (int i = 0; i < frames; i++) {
            float s = mono[i];
            out[i * 2] = left ? s : 0f;
            out[i * 2 + 1] = right ? s : 0f;
        }
        return frames * 2;
    }
}
