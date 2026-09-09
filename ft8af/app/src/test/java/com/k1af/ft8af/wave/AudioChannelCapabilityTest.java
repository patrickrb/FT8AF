package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link AudioChannelCapability}, which decides whether the
 * left/right selector applies to the device the operator picked.
 *
 * <p>The interesting case is the empty channel-count array. Android documents
 * {@code AudioDeviceInfo.getChannelCounts()} as possibly empty, meaning "not
 * reported", and several USB codecs do exactly that. Reading empty as mono
 * would grey the selector out on the stereo interfaces the feature exists for,
 * so it has to read as "unknown, leave the choice available".
 */
public class AudioChannelCapabilityTest {

    @Test
    public void maxChannelCount_takesTheLargestReportedConfiguration() {
        // A codec that can be opened mono or stereo advertises both; it is the
        // maximum that says whether a side can be selected.
        assertThat(AudioChannelCapability.maxChannelCount(new int[] {1, 2})).isEqualTo(2);
        assertThat(AudioChannelCapability.maxChannelCount(new int[] {2, 1})).isEqualTo(2);
        assertThat(AudioChannelCapability.maxChannelCount(new int[] {1})).isEqualTo(1);
        assertThat(AudioChannelCapability.maxChannelCount(new int[] {2, 4, 6})).isEqualTo(6);
    }

    @Test
    public void maxChannelCount_unreportedIsUnknown() {
        assertThat(AudioChannelCapability.maxChannelCount(null))
                .isEqualTo(AudioChannelCapability.UNKNOWN);
        assertThat(AudioChannelCapability.maxChannelCount(new int[0]))
                .isEqualTo(AudioChannelCapability.UNKNOWN);
    }

    @Test
    public void maxChannelCount_ignoresMalformedEntries() {
        // Negative/zero counts are nonsense; they must not become the maximum.
        assertThat(AudioChannelCapability.maxChannelCount(new int[] {0, -3}))
                .isEqualTo(AudioChannelCapability.UNKNOWN);
        assertThat(AudioChannelCapability.maxChannelCount(new int[] {-3, 2})).isEqualTo(2);
    }

    @Test
    public void stereoCapable_trueForStereoAndForUnknown() {
        assertThat(AudioChannelCapability.stereoCapable(2)).isTrue();
        assertThat(AudioChannelCapability.stereoCapable(6)).isTrue();
        assertThat(AudioChannelCapability.stereoCapable(AudioChannelCapability.UNKNOWN)).isTrue();
    }

    @Test
    public void stereoCapable_falseOnlyForAReportedMonoDevice() {
        assertThat(AudioChannelCapability.stereoCapable(1)).isFalse();
    }

    @Test
    public void effectiveSelection_honoursTheChoiceOnAStereoDevice() {
        assertThat(AudioChannelCapability.effectiveSelection(AudioChannelSelect.LEFT, 2))
                .isEqualTo(AudioChannelSelect.LEFT);
        assertThat(AudioChannelCapability.effectiveSelection(AudioChannelSelect.RIGHT, 2))
                .isEqualTo(AudioChannelSelect.RIGHT);
        assertThat(AudioChannelCapability.effectiveSelection(AudioChannelSelect.BOTH, 2))
                .isEqualTo(AudioChannelSelect.BOTH);
    }

    @Test
    public void effectiveSelection_collapsesToBothOnAMonoDevice() {
        // One channel: Left, Right and Mix are all the same audio, so the app uses
        // the one channel and the settings screen greys the control out.
        assertThat(AudioChannelCapability.effectiveSelection(AudioChannelSelect.LEFT, 1))
                .isEqualTo(AudioChannelSelect.BOTH);
        assertThat(AudioChannelCapability.effectiveSelection(AudioChannelSelect.RIGHT, 1))
                .isEqualTo(AudioChannelSelect.BOTH);
    }

    @Test
    public void effectiveSelection_keepsTheChoiceWhenTheCountIsUnknown() {
        // Android's "Default" sink and codecs that report nothing: refusing the
        // selection there would silently ignore the setting on routed-USB setups.
        assertThat(AudioChannelCapability.effectiveSelection(
                AudioChannelSelect.RIGHT, AudioChannelCapability.UNKNOWN))
                .isEqualTo(AudioChannelSelect.RIGHT);
    }

    @Test
    public void effectiveSelection_clampsGarbage() {
        assertThat(AudioChannelCapability.effectiveSelection(42, 2))
                .isEqualTo(AudioChannelSelect.BOTH);
        assertThat(AudioChannelCapability.effectiveSelection(42, 1))
                .isEqualTo(AudioChannelSelect.BOTH);
    }
}
