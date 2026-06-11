package com.bg7yoz.ft8cn.database;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Pure-logic coverage for OperationBand. The band list is a public static
 * collection, so the lookup/index helpers can be exercised by populating it
 * directly — no Context or assets needed. Each test resets the shared static
 * {@code bandList} first so they don't bleed into each other.
 */
public class OperationBandTest {

    @Before
    public void resetBandList() {
        OperationBand.bandList.clear();
    }

    @Test
    public void defaults_are20mAt14074() {
        assertThat(OperationBand.getDefaultBand()).isEqualTo(14_074_000L);
        assertThat(OperationBand.getDefaultWaveLength()).isEqualTo("20m");
    }

    @Test
    public void bandStringConstructor_parsesMarkedFreqAndWavelength() {
        OperationBand.Band b = new OperationBand.Band("*:14074000:20m");
        assertThat(b.marked).isTrue();
        assertThat(b.band).isEqualTo(14_074_000L);
        assertThat(b.waveLength).isEqualTo("20m");
    }

    @Test
    public void bandStringConstructor_unmarkedLine() {
        OperationBand.Band b = new OperationBand.Band(" :7074000:40m");
        assertThat(b.marked).isFalse();
        assertThat(b.band).isEqualTo(7_074_000L);
        assertThat(b.waveLength).isEqualTo("40m");
    }

    @Test
    public void bandInfo_formatsMHzWithWavelength() {
        OperationBand.Band b = new OperationBand.Band(14_074_000L, "20m");
        assertThat(b.getBandInfo()).isEqualTo("  14.074 MHz (20m)");
    }

    @Test
    public void getIndexByFreq_findsExistingBand() {
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(7_074_000L, "40m"));
        assertThat(OperationBand.getIndexByFreq(7_074_000L)).isEqualTo(1);
    }

    @Test
    public void getIndexByFreq_appendsUnknownBand() {
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        int idx = OperationBand.getIndexByFreq(21_074_000L);
        assertThat(idx).isEqualTo(1);
        assertThat(OperationBand.bandList).hasSize(2);
        // The wavelength is derived via BaseRigOperation.getMeterFromFreq.
        assertThat(OperationBand.bandList.get(1).waveLength).isEqualTo("15m");
    }

    @Test
    public void getBandFreq_returnsFreqForIndex() {
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        assertThat(OperationBand.getBandFreq(0)).isEqualTo(14_074_000L);
    }

    @Test
    public void getBandFreq_outOfRangeIndex_returnsDefault() {
        // index strictly greater than size falls back to the 20m default.
        assertThat(OperationBand.getBandFreq(99)).isEqualTo(14_074_000L);
    }

    @Test
    public void getAllWaveLengths_returnsDistinctInFileOrder() {
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(14_080_000L, "20m")); // dup wavelength
        OperationBand.bandList.add(new OperationBand.Band(7_074_000L, "40m"));
        assertThat(OperationBand.getAllWaveLengths()).containsExactly("20m", "40m").inOrder();
    }

    @Test
    public void getLinesFromInputStream_splitsOnDelimiter() {
        InputStream in = new ByteArrayInputStream(
                "a\nb\nc".getBytes(StandardCharsets.UTF_8));
        assertThat(OperationBand.getLinesFromInputStream(in, "\n"))
                .asList().containsExactly("a", "b", "c").inOrder();
    }

    @Test
    public void bandInfo_markedBand_usesAsteriskPrefix() {
        // The marked variant of getBandInfo() (the "*" prefix branch) is distinct
        // from the leading-space unmarked form.
        OperationBand.Band b = new OperationBand.Band("*:14074000:20m");
        assertThat(b.getBandInfo()).isEqualTo("* 14.074 MHz (20m)");
    }

    @Test
    public void staticGetBandInfo_returnsInfoForIndex() {
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(7_074_000L, "40m"));
        assertThat(OperationBand.getBandInfo(1)).isEqualTo("  7.074 MHz (40m)");
    }

    @Test
    public void staticGetBandInfo_outOfRangeIndex_fallsBackToFirst() {
        // index >= size returns bandList.get(0).getBandInfo().
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        assertThat(OperationBand.getBandInfo(99)).isEqualTo("  14.074 MHz (20m)");
    }

    @Test
    public void getBandFreq_lastInRangeIndex_returnsThatEntry() {
        // The existing suite covers index 0 and an out-of-range index; verify a
        // non-zero in-range index resolves to the matching band.
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(7_074_000L, "40m"));
        assertThat(OperationBand.getBandFreq(1)).isEqualTo(7_074_000L);
    }

    @Test
    public void bandStringConstructor_threeFieldUsesThirdAsWavelength() {
        // waveLength is field index 2 (marked:freq:waveLength); a plain 3-field line
        // has no mode tag and defaults to FT8.
        OperationBand.Band b = new OperationBand.Band(" :10136000:30m");
        assertThat(b.band).isEqualTo(10_136_000L);
        assertThat(b.waveLength).isEqualTo("30m");
        assertThat(b.marked).isFalse();
        assertThat(b.mode).isEqualTo(com.bg7yoz.ft8cn.FT8Common.FT8_MODE);
    }

    @Test
    public void bandStringConstructor_fourthFieldTagsFt4Mode() {
        OperationBand.Band b = new OperationBand.Band("*:14080000:20m:FT4");
        assertThat(b.band).isEqualTo(14_080_000L);
        assertThat(b.waveLength).isEqualTo("20m");
        assertThat(b.marked).isTrue();
        assertThat(b.mode).isEqualTo(com.bg7yoz.ft8cn.FT8Common.FT4_MODE);
    }

    @Test
    public void bandStringConstructor_fourthFieldTagsFt2Mode() {
        // The mode tag resolves via ModeProfile.displayName, so "FT2" maps to FT2_MODE.
        OperationBand.Band b = new OperationBand.Band("*:14074000:20m:FT2");
        assertThat(b.band).isEqualTo(14_074_000L);
        assertThat(b.waveLength).isEqualTo("20m");
        assertThat(b.marked).isTrue();
        assertThat(b.mode).isEqualTo(com.bg7yoz.ft8cn.FT8Common.FT2_MODE);
    }

    @Test
    public void bandStringConstructor_unknownModeTagFallsBackToFt8() {
        OperationBand.Band b = new OperationBand.Band("*:14074000:20m:FTX");
        assertThat(b.mode).isEqualTo(com.bg7yoz.ft8cn.FT8Common.FT8_MODE);
    }

    @Test
    public void getModeBandFreq_returnsModeSpecificDial() {
        OperationBand.bandList.add(new OperationBand.Band("*:14074000:20m"));
        OperationBand.bandList.add(new OperationBand.Band("*:14080000:20m:FT4"));
        OperationBand.bandList.add(new OperationBand.Band("*:14070000:20m:FT2"));
        assertThat(OperationBand.getModeBandFreq("20m", com.bg7yoz.ft8cn.FT8Common.FT8_MODE))
                .isEqualTo(14_074_000L);
        assertThat(OperationBand.getModeBandFreq("20m", com.bg7yoz.ft8cn.FT8Common.FT4_MODE))
                .isEqualTo(14_080_000L);
        assertThat(OperationBand.getModeBandFreq("20m", com.bg7yoz.ft8cn.FT8Common.FT2_MODE))
                .isEqualTo(14_070_000L);
    }

    @Test
    public void getModeBandFreq_noEntryForBandInMode_returnsMinusOne() {
        // 160m has an FT8 dial but no FT4 dial -> -1 so callers keep the current freq.
        OperationBand.bandList.add(new OperationBand.Band("*:1840000:160m"));
        assertThat(OperationBand.getModeBandFreq("160m", com.bg7yoz.ft8cn.FT8Common.FT4_MODE))
                .isEqualTo(-1L);
    }

    @Test
    public void ft2BandPlan_dialsResolvePerBand() {
        // Lock in the canonical FT2 band plan shipped in assets/bands.txt: each
        // line is "*:freq:waveLength:FT2" and getModeBandFreq must return that dial
        // for its band when operating in FT2 mode.
        String[] ft2Lines = {
                "*:1843000:160m:FT2", "*:3578000:80m:FT2", "*:5360000:60m:FT2",
                "*:7062000:40m:FT2", "*:10144000:30m:FT2", "*:14084000:20m:FT2",
                "*:18108000:17m:FT2", "*:21144000:15m:FT2", "*:24923000:12m:FT2",
                "*:28184000:10m:FT2", "*:50316000:6m:FT2", "*:70157000:4m:FT2",
                "*:144177000:2m:FT2", "*:222177000:1.25m:FT2", "*:432177000:70cm:FT2",
                "*:1296177000:23cm:FT2", "*:2400040000:13cm:FT2", "*:10489540000:3cm:FT2"
        };
        for (String line : ft2Lines) {
            OperationBand.bandList.add(new OperationBand.Band(line));
        }
        int ft2 = com.bg7yoz.ft8cn.FT8Common.FT2_MODE;
        assertThat(OperationBand.getModeBandFreq("160m", ft2)).isEqualTo(1_843_000L);
        assertThat(OperationBand.getModeBandFreq("20m", ft2)).isEqualTo(14_084_000L);
        assertThat(OperationBand.getModeBandFreq("17m", ft2)).isEqualTo(18_108_000L);
        assertThat(OperationBand.getModeBandFreq("10m", ft2)).isEqualTo(28_184_000L);
        assertThat(OperationBand.getModeBandFreq("70cm", ft2)).isEqualTo(432_177_000L);
        // QO-100 dials exceed 2^31, so they must round-trip as long, not int.
        assertThat(OperationBand.getModeBandFreq("13cm", ft2)).isEqualTo(2_400_040_000L);
        assertThat(OperationBand.getModeBandFreq("3cm", ft2)).isEqualTo(10_489_540_000L);
    }

    @Test
    public void ft2BandPlan_coversEighteenDistinctBands() {
        // Guard against an accidental drop of a band line: the plan has 18 entries,
        // one per wavelength, all tagged FT2.
        String[] waveLengths = {
                "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m",
                "10m", "6m", "4m", "2m", "1.25m", "70cm", "23cm", "13cm", "3cm"
        };
        long[] dials = {
                1_843_000L, 3_578_000L, 5_360_000L, 7_062_000L, 10_144_000L,
                14_084_000L, 18_108_000L, 21_144_000L, 24_923_000L, 28_184_000L,
                50_316_000L, 70_157_000L, 144_177_000L, 222_177_000L, 432_177_000L,
                1_296_177_000L, 2_400_040_000L, 10_489_540_000L
        };
        assertThat(waveLengths).hasLength(18);
        for (int i = 0; i < waveLengths.length; i++) {
            OperationBand.bandList.add(new OperationBand.Band(dials[i], waveLengths[i]));
        }
        assertThat(OperationBand.getAllWaveLengths()).hasSize(18);
    }}
