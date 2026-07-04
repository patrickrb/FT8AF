package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Byte-exact coverage for the ATU-start CAT commands (issue #425). These are
 * radio-facing wire bytes — a wrong sub-command on CI-V (0x00 is PTT!) or a
 * wrong AC parameter on the ASCII rigs would key the transmitter instead of
 * the tuner, so the exact frames are pinned here.
 */
public class AtuTuneCommandTest {

    @Test
    public void icomAtuStart_isCiv1c01_02() {
        //FE FE <rig> <ctr> 1C 01 02 FD — tuner start, NOT 1C 00 (PTT)
        byte[] cmd = IcomRigConstant.startAtuTune(0xE0, 0xA4);
        assertThat(cmd).isEqualTo(new byte[]{
                (byte) 0xfe, (byte) 0xfe, (byte) 0xa4, (byte) 0xe0,
                (byte) 0x1c, (byte) 0x01, (byte) 0x02, (byte) 0xfd});
    }

    @Test
    public void icomAtuStart_usesGivenAddresses() {
        byte[] cmd = IcomRigConstant.startAtuTune(0x00, 0x70);//XieGu-style addresses
        assertThat(cmd[2]).isEqualTo((byte) 0x70);
        assertThat(cmd[3]).isEqualTo((byte) 0x00);
    }

    @Test
    public void yaesu3AtuStart_isAC002() {
        assertThat(new String(Yaesu3RigConstant.startAtuTune())).isEqualTo("AC002;");
    }

    @Test
    public void kenwoodAtuStart_isAC111() {
        assertThat(new String(KenwoodTK90RigConstant.startAtuTune())).isEqualTo("AC111;");
    }

    @Test
    public void baseRig_defaultsToNoAtuSupport() {
        BaseRig rig = new BaseRig() {
            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public void setUsbModeToRig() {
            }

            @Override
            public void setFreqToRig() {
            }

            @Override
            public void onReceiveData(byte[] data) {
            }

            @Override
            public void readFreqFromRig() {
            }

            @Override
            public String getName() {
                return "test";
            }
        };
        assertThat(rig.supportsAtuTune()).isFalse();
        rig.startAtuTune();//default is a no-op — must not throw with no connector
    }
}
