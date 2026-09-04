package com.k1af.ft8af.wave;

/**
 * How many channels the audio device the operator picked actually has, and what
 * that means for {@link AudioChannelSelect}.
 *
 * <p>A left/right selection is meaningless on a mono device: there is one
 * channel, so Mix, Left and Right all produce the same audio. Offering the
 * choice there is worse than not offering it — an operator who picks "Right" on
 * a mono interface and hears no difference has no way to tell whether the
 * setting is broken or simply inapplicable. So the settings screen greys the
 * selector out and pins it to {@link AudioChannelSelect#BOTH} (the one channel)
 * whenever the device reports mono, and the TX path does the same before
 * deciding whether to open a stereo sink.
 *
 * <p>Channel counts come from {@code AudioDeviceInfo.getChannelCounts()} for
 * framework-routed devices and from the endpoint descriptor for the USB-direct
 * paths. The framework array is documented as possibly empty, meaning "not
 * known / anything goes" rather than "mono" — treating that as mono would grey
 * the selector out on devices that do have two channels, so {@link #UNKNOWN}
 * is deliberately optimistic and leaves the choice available.
 */
public final class AudioChannelCapability {
    /** The device did not report a channel count; assume a selection may apply. */
    public static final int UNKNOWN = 0;

    private AudioChannelCapability() {
    }

    /**
     * Largest channel count in a {@code AudioDeviceInfo.getChannelCounts()}
     * array. Framework devices advertise every configuration they support (e.g.
     * {@code [1, 2]} for a USB codec that can also be opened mono), and it is
     * the maximum that says whether a left/right choice can be honoured.
     *
     * @param counts the reported counts; null or empty means unreported
     * @return the maximum count, or {@link #UNKNOWN} when nothing usable was
     *         reported (non-positive entries are ignored as malformed)
     */
    public static int maxChannelCount(int[] counts) {
        if (counts == null) return UNKNOWN;
        int max = UNKNOWN;
        for (int c : counts) {
            if (c > max) max = c;
        }
        return Math.max(max, UNKNOWN);
    }

    /**
     * Whether a left/right selection can do anything on a device with this
     * channel count. True for stereo or better, and true for {@link #UNKNOWN} —
     * see the class note on why an unreported count must not read as mono.
     */
    public static boolean stereoCapable(int maxChannels) {
        return maxChannels == UNKNOWN || maxChannels >= 2;
    }

    /**
     * The selection to actually apply on a device with this channel count: the
     * operator's stored choice where it can be honoured, {@link
     * AudioChannelSelect#BOTH} on a mono device.
     *
     * <p>The stored preference is deliberately not rewritten when it is
     * overridden this way — plugging a stereo interface back in restores the
     * operator's choice rather than silently having reset it to Mix while a
     * mono device happened to be selected.
     */
    public static int effectiveSelection(int stored, int maxChannels) {
        return stereoCapable(maxChannels)
                ? AudioChannelSelect.clamp(stored)
                : AudioChannelSelect.BOTH;
    }
}
