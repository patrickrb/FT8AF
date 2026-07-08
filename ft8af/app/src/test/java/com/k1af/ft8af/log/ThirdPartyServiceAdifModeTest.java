package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;

/**
 * Verifies the mode/submode ADIF mapping in {@link ThirdPartyService#QSLRecordToADIF}, the payload
 * shared by the Cloudlog and QRZ uploaders. FT2/FT4 are ADIF submodes of MFSK, so a bare
 * {@code <mode>FT2}/{@code <mode>FT4} is rejected as invalid by those consumers; they must be
 * emitted as {@code MODE=MFSK} + {@code SUBMODE}. FT8 stays a standalone mode. Runs under
 * Robolectric because {@link QSLRecord} and the exporter touch Android {@code Log}.
 */
@RunWith(RobolectricTestRunner.class)
public class ThirdPartyServiceAdifModeTest {

    private static QSLRecord recordWithMode(String mode) {
        HashMap<String, String> map = new HashMap<>();
        map.put("CALL", "W1AW");
        map.put("STATION_CALLSIGN", "K1AF");
        map.put("MODE", mode);
        return new QSLRecord(map);
    }

    @Test
    public void ft2ExportsAsMfskSubmode() {
        String adif = ThirdPartyService.QSLRecordToADIF(recordWithMode("FT2"), ServiceType.QRZ);
        assertThat(adif).contains("<mode:4>MFSK <submode:3>FT2 ");
        assertThat(adif).doesNotContain("<mode:3>FT2 ");
    }

    @Test
    public void ft4ExportsAsMfskSubmode() {
        String adif = ThirdPartyService.QSLRecordToADIF(recordWithMode("FT4"), ServiceType.Cloudlog);
        assertThat(adif).contains("<mode:4>MFSK <submode:3>FT4 ");
        assertThat(adif).doesNotContain("<mode:3>FT4 ");
    }

    @Test
    public void ft8StaysStandaloneMode() {
        String adif = ThirdPartyService.QSLRecordToADIF(recordWithMode("FT8"), ServiceType.QRZ);
        assertThat(adif).contains("<mode:3>FT8 ");
        assertThat(adif).doesNotContain("submode");
    }
}
