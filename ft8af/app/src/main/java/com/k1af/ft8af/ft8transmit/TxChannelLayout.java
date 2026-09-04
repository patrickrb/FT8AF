package com.k1af.ft8af.ft8transmit;

import android.media.AudioFormat;

import com.k1af.ft8af.wave.AudioChannelCapability;
import com.k1af.ft8af.wave.AudioChannelSelect;

/**
 * The channel layout an {@code AudioTrack} transmit opens with, and the frame
 * arithmetic that has to follow it.
 *
 * <p>Shared by the FT8/FT4 over ({@code playViaAudioTrack}) and the Tune
 * carrier ({@code playTuneTone}) so both honour the operator's TX channel
 * selection the same way — a splitter cable that only wants one rig keyed is
 * just as wrong keying the other rig with a tune carrier as with an over.
 *
 * <p>Two things live here rather than inline because getting them wrong
 * produces a transmission that sounds fine and decodes nowhere:
 *
 * <ul>
 *   <li>{@code AudioTrack.write()} counts <em>samples</em> and
 *       {@code getPlaybackHeadPosition()} counts <em>frames</em>. The tail-drain
 *       wait compares the two, so every count crossing that boundary goes
 *       through {@link #framesFromSamples}.
 *   <li>The byte budget for the ~200 ms streaming buffer must scale with the
 *       channel count or a stereo open only holds ~100 ms and blocks on every
 *       chunk.
 * </ul>
 *
 * <p>The mono layout is the historical one and is left byte-for-byte alone:
 * {@link #layOut} hands the caller's buffer straight back, the pad is 8 shorts,
 * and the buffer budget is what it always was. Only an explicit Left/Right on a
 * sink we can actually see takes the stereo branch — see {@link #resolve}.
 */
public final class TxChannelLayout {
    /** The operator's selection after the sink's capabilities were applied. */
    public final int selection;
    /** 1 or 2. */
    public final int channels;
    /** {@code AudioFormat.CHANNEL_OUT_MONO} or {@code CHANNEL_OUT_STEREO}. */
    public final int channelMask;

    private TxChannelLayout(int selection) {
        this.selection = selection;
        boolean stereo = AudioChannelSelect.needsStereoPlayback(selection);
        this.channels = stereo ? 2 : 1;
        this.channelMask = stereo ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
    }

    /**
     * Decide the layout for this transmission.
     *
     * <p>A Left/Right choice is only honoured on a sink we picked explicitly and
     * can therefore ask about. On Android's "Default" sink we cannot see what
     * the OS will route to; opening stereo with one side zeroed there and having
     * the framework downmix it onto a mono route (a mono UAC HAL, a Bluetooth
     * SCO rig link) halves the on-air drive with no indication in the UI. The
     * settings screen greys the selector out for Default for the same reason,
     * so an operator who needs a side picks the device by name.
     *
     * @param stored          the operator's persisted selection
     * @param explicitSink    whether an output device was chosen by id (not
     *                        Default)
     * @param sinkMaxChannels the chosen sink's reported channel count, or
     *                        {@link AudioChannelCapability#UNKNOWN}; ignored
     *                        when {@code explicitSink} is false
     */
    public static TxChannelLayout resolve(int stored, boolean explicitSink, int sinkMaxChannels) {
        if (!explicitSink) {
            return new TxChannelLayout(AudioChannelSelect.BOTH);
        }
        return new TxChannelLayout(
                AudioChannelCapability.effectiveSelection(stored, sinkMaxChannels));
    }

    /** Whether this layout opens the track stereo. */
    public boolean isStereo() {
        return channels == 2;
    }

    /**
     * Byte size for the ~200 ms streaming buffer at this layout: the historical
     * {@code (rate / 5) * bytesPerSample} scaled by the channel count.
     */
    public int bufferBytes(int sampleRate, int bytesPerSample) {
        return (sampleRate / 5) * bytesPerSample * channels;
    }

    /** Samples {@code write()} must be handed for {@code frames} frames of audio. */
    public int samplesForFrames(int frames) {
        return frames * channels;
    }

    /**
     * Frames represented by a {@code write()} return value. Integer division is
     * exact for a blocking write, which always commits whole frames.
     */
    public int framesFromSamples(int samples) {
        return samples / channels;
    }

    /**
     * The 8-frame zero tail appended after the last chunk on the int16 path
     * (QP-7C RP2040 audio-detection compatibility): 8 shorts mono, 16 stereo.
     */
    public short[] zeroPad() {
        return new short[8 * channels];
    }

    /**
     * The buffer to hand {@code write()} for one mono chunk. Mono returns
     * {@code mono} itself — no copy, the historical path. Stereo expands into
     * {@code scratch} (which the caller allocates once, sized for
     * {@link #samplesForFrames} of the largest chunk) with the excluded side
     * silenced, and returns {@code scratch}. Either way the number of samples
     * to write is {@link #samplesForFrames}{@code (count)}.
     */
    public float[] layOut(float[] mono, int count, float[] scratch) {
        if (!isStereo()) {
            return mono;
        }
        AudioChannelSelect.expandToStereo(mono, count, selection, scratch);
        return scratch;
    }
}
