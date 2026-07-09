package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.connector.ConnectMode;

import org.junit.Test;

/**
 * The TX audio router decides whether samples go over the rig's network control
 * link or through the local sound path. A Hamlib NET rigctl rig carries only CAT
 * over TCP, so its audio must play locally — routing it to the network branch
 * would leave the air silent (the exact failure the {@code usesNetworkAudio}
 * flag guards against).
 */
public class TransmitAudioRoutingTest {

    @Test
    public void networkAudioRig_inNetworkMode_transmitsOverNetwork() {
        assertThat(FT8TransmitSignal.transmitAudioOverNetwork(ConnectMode.NETWORK, true))
                .isTrue();
    }

    @Test
    public void hamlibRig_inNetworkMode_playsLocally() {
        // rigUsesNetworkAudio == false for Hamlib NET rigctl (CAT-only link).
        assertThat(FT8TransmitSignal.transmitAudioOverNetwork(ConnectMode.NETWORK, false))
                .isFalse();
    }

    @Test
    public void wiredModes_alwaysPlayLocally() {
        assertThat(FT8TransmitSignal.transmitAudioOverNetwork(ConnectMode.USB_CABLE, true))
                .isFalse();
        assertThat(FT8TransmitSignal.transmitAudioOverNetwork(ConnectMode.BLUE_TOOTH, true))
                .isFalse();
    }
}
