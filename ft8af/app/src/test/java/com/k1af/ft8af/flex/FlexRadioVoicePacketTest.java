package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link FlexRadio#fillVoicePacket(float[], int, float[])},
 * the packetiser step of the Flex DAX TX-audio stream.
 *
 * <p>{@code sendWaveData} walks the interleaved-stereo waveform in fixed 256-value
 * packets. The historic inline loop read {@code temp[count]} and only checked the
 * bound <em>after</em> the read, so when the total number of stereo values was not
 * a whole multiple of 256 the final packet read one element past the array and
 * threw an uncaught {@link ArrayIndexOutOfBoundsException} on the TX send thread —
 * crashing the whole app. FT2 at 24 kHz produces 60480 samples (120960 interleaved
 * values, {@code 120960 % 256 == 128}), so every FT2 transmit over a network Flex
 * rig hit it. These tests pin the bounded, zero-padded behaviour.
 */
public class FlexRadioVoicePacketTest {

    /** Packet size used by {@code sendWaveData} (256 interleaved stereo floats — 128 L/R frames). */
    private static final int PACKET = 256;

    @Test
    public void fillsFullPacketAndAdvancesByPacketSize() {
        float[] stereo = new float[PACKET * 2];
        for (int i = 0; i < stereo.length; i++) {
            stereo[i] = i;
        }
        float[] packet = new float[PACKET];

        int next = FlexRadio.fillVoicePacket(stereo, 0, packet);

        assertThat(next).isEqualTo(PACKET);
        assertThat(packet[0]).isEqualTo(0f);
        assertThat(packet[PACKET - 1]).isEqualTo(PACKET - 1);
    }

    @Test
    public void partialFinalPacketIsZeroPadded_notOutOfBounds() {
        // 120960 = FT2 @ 24 kHz stereo value count; not a multiple of 256.
        float[] stereo = new float[120960];
        for (int i = 0; i < stereo.length; i++) {
            stereo[i] = 1f;
        }
        float[] packet = new float[PACKET];

        // The final packet starts here with only 128 values left (120960 - 120832).
        int from = 120832;
        int next = FlexRadio.fillVoicePacket(stereo, from, packet); // must not throw

        assertThat(next).isEqualTo(120960);
        // First 128 slots are real samples, the remaining 128 are silence padding.
        assertThat(packet[127]).isEqualTo(1f);
        assertThat(packet[128]).isEqualTo(0f);
        assertThat(packet[PACKET - 1]).isEqualTo(0f);
    }

    @Test
    public void reusedPacketBufferIsFullyOverwritten() {
        // A caller reusing a dirty buffer must still see the tail zeroed.
        float[] stereo = {5f, 6f, 7f};
        float[] packet = new float[PACKET];
        for (int i = 0; i < packet.length; i++) {
            packet[i] = 99f;
        }

        int next = FlexRadio.fillVoicePacket(stereo, 0, packet);

        assertThat(next).isEqualTo(3);
        assertThat(packet[0]).isEqualTo(5f);
        assertThat(packet[2]).isEqualTo(7f);
        assertThat(packet[3]).isEqualTo(0f);
        assertThat(packet[PACKET - 1]).isEqualTo(0f);
    }

    @Test
    public void fromAtEndProducesSilentPacketAndDoesNotAdvance() {
        float[] stereo = new float[512];
        float[] packet = new float[PACKET];

        int next = FlexRadio.fillVoicePacket(stereo, stereo.length, packet);

        assertThat(next).isEqualTo(stereo.length);
        assertThat(packet[0]).isEqualTo(0f);
        assertThat(packet[PACKET - 1]).isEqualTo(0f);
    }

    @Test
    public void walksExactMultipleToCompletionWithoutOverrun() {
        // 512 stereo values = exactly two packets; the loop must terminate cleanly.
        float[] stereo = new float[PACKET * 2];
        float[] packet = new float[PACKET];

        int count = 0;
        int packets = 0;
        while (count < stereo.length) {
            count = FlexRadio.fillVoicePacket(stereo, count, packet);
            packets++;
            if (count >= stereo.length) break;
        }

        assertThat(packets).isEqualTo(2);
        assertThat(count).isEqualTo(stereo.length);
    }
}
