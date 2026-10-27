package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    public void bandStringConstructor_trimsTrailingCrOnWavelength() {
        // waveLength is the LAST field on a plain (no mode tag) line, so a CRLF-checked-out
        // bands.txt (every Windows build with no .gitattributes override, before this fix)
        // leaves a trailing "\r" here -- "40m\r" never matches "40m" anywhere waveLength is
        // compared, so the Band & Mode sheet's curated list came up empty on every such build.
        OperationBand.Band b = new OperationBand.Band(" :7074000:40m\r");
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
        // an out-of-range index falls back to the 20m default.
        assertThat(OperationBand.getBandFreq(99)).isEqualTo(14_074_000L);
    }

    @Test
    public void getAllWaveLengths_returnsDistinctLowestFrequencyFirst() {
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(14_080_000L, "20m")); // dup wavelength
        OperationBand.bandList.add(new OperationBand.Band(7_074_000L, "40m"));
        // Ordered by frequency, not file order (issue #929).
        assertThat(OperationBand.getAllWaveLengths()).containsExactly("40m", "20m").inOrder();
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
        assertThat(b.mode).isEqualTo(com.k1af.ft8af.FT8Common.FT8_MODE);
    }

    @Test
    public void bandStringConstructor_fourthFieldTagsFt4Mode() {
        OperationBand.Band b = new OperationBand.Band("*:14080000:20m:FT4");
        assertThat(b.band).isEqualTo(14_080_000L);
        assertThat(b.waveLength).isEqualTo("20m");
        assertThat(b.marked).isTrue();
        assertThat(b.mode).isEqualTo(com.k1af.ft8af.FT8Common.FT4_MODE);
    }

    @Test
    public void bandStringConstructor_fourthFieldTagsFt2Mode() {
        // The mode tag resolves via ModeProfile.displayName, so "FT2" maps to FT2_MODE.
        OperationBand.Band b = new OperationBand.Band("*:14074000:20m:FT2");
        assertThat(b.band).isEqualTo(14_074_000L);
        assertThat(b.waveLength).isEqualTo("20m");
        assertThat(b.marked).isTrue();
        assertThat(b.mode).isEqualTo(com.k1af.ft8af.FT8Common.FT2_MODE);
    }

    @Test
    public void bandStringConstructor_unknownModeTagFallsBackToFt8() {
        OperationBand.Band b = new OperationBand.Band("*:14074000:20m:FTX");
        assertThat(b.mode).isEqualTo(com.k1af.ft8af.FT8Common.FT8_MODE);
    }

    @Test
    public void elevenMeterFt8_isMarked27265AsFt8() {
        // Issue #378: 27.265 MHz is the 11m FT8 calling frequency, shipped as a
        // marked (default) FT8 entry in assets/bands.txt.
        OperationBand.Band b = new OperationBand.Band("*:27265000:11m");
        assertThat(b.marked).isTrue();
        assertThat(b.band).isEqualTo(27_265_000L);
        assertThat(b.waveLength).isEqualTo("11m");
        assertThat(b.mode).isEqualTo(com.k1af.ft8af.FT8Common.FT8_MODE);
        assertThat(b.getBandInfo()).isEqualTo("* 27.265 MHz (11m)");
    }

    @Test
    public void elevenMeterFt8_resolvesAsFt8BandDial() {
        // The 11m entry must be selectable as the FT8 dial for the 11m band.
        OperationBand.bandList.add(new OperationBand.Band("*:27265000:11m"));
        assertThat(OperationBand.getModeBandFreq("11m", com.k1af.ft8af.FT8Common.FT8_MODE))
                .isEqualTo(27_265_000L);
        assertThat(OperationBand.getIndexByFreq(27_265_000L)).isEqualTo(0);
    }

    @Test
    public void getModeBandFreq_returnsModeSpecificDial() {
        OperationBand.bandList.add(new OperationBand.Band("*:14074000:20m"));
        OperationBand.bandList.add(new OperationBand.Band("*:14080000:20m:FT4"));
        OperationBand.bandList.add(new OperationBand.Band("*:14070000:20m:FT2"));
        assertThat(OperationBand.getModeBandFreq("20m", com.k1af.ft8af.FT8Common.FT8_MODE))
                .isEqualTo(14_074_000L);
        assertThat(OperationBand.getModeBandFreq("20m", com.k1af.ft8af.FT8Common.FT4_MODE))
                .isEqualTo(14_080_000L);
        assertThat(OperationBand.getModeBandFreq("20m", com.k1af.ft8af.FT8Common.FT2_MODE))
                .isEqualTo(14_070_000L);
    }

    @Test
    public void getModeBandFreq_noEntryForBandInMode_returnsMinusOne() {
        // 160m has an FT8 dial but no FT4 dial -> -1 so callers keep the current freq.
        OperationBand.bandList.add(new OperationBand.Band("*:1840000:160m"));
        assertThat(OperationBand.getModeBandFreq("160m", com.k1af.ft8af.FT8Common.FT4_MODE))
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
        int ft2 = com.k1af.ft8af.FT8Common.FT2_MODE;
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
            // Build the canonical "*:dial:waveLength:FT2" line so the entries are
            // genuinely FT2-tagged (the long/String constructor would default to FT8).
            OperationBand.bandList.add(new OperationBand.Band(
                    "*:" + dials[i] + ":" + waveLengths[i] + ":FT2"));
        }
        assertThat(OperationBand.getAllWaveLengths()).hasSize(18);
        // Every entry resolves in FT2 mode, confirming the FT2 tag round-tripped.
        int ft2 = com.k1af.ft8af.FT8Common.FT2_MODE;
        for (int i = 0; i < waveLengths.length; i++) {
            assertThat(OperationBand.getModeBandFreq(waveLengths[i], ft2)).isEqualTo(dials[i]);
        }
    }

    // ---- isBandLine ---------------------------------------------------------
    // The bands loader must parse only real band entries; comment/blank lines are
    // skipped so a comment containing a colon can't reach Long.parseLong and crash.

    @Test
    public void isBandLine_parsesMarkedAndUnmarkedEntries() {
        assertThat(OperationBand.isBandLine("*:1843000:160m:FT2")).isTrue();
        assertThat(OperationBand.isBandLine(" :7074000:40m")).isTrue();
    }

    @Test
    public void isBandLine_skipsCommentsAndBlanks() {
        assertThat(OperationBand.isBandLine("# FT2 dial frequencies")).isFalse();
        assertThat(OperationBand.isBandLine("")).isFalse();
        assertThat(OperationBand.isBandLine("   ")).isFalse();
        assertThat(OperationBand.isBandLine(null)).isFalse();
        // Plain text with no colon is not a band entry.
        assertThat(OperationBand.isBandLine("just a note")).isFalse();
    }

    @Test
    public void isBandLine_skipsCommentEvenWhenItContainsAColon() {
        // Regression: the QO-100 comment contains a colon. Before the loader skipped
        // '#' lines, this was parsed as a band and crashed band loading via
        // Long.parseLong(" 2.4 GHz uplink / 10.489 GHz downlink.").
        assertThat(OperationBand.isBandLine(
                "# QO-100 geostationary satellite (Es'hail-2): 2.4 GHz uplink / 10.489 GHz downlink."))
                .isFalse();
    }

    // ---- index bounds hardening --------------------------------------------
    // Index accessors must reject negative indices and indices at/after the end
    // of bandList. getBandFreq previously used an off-by-one `index > size`
    // guard, so index == size fell through into bandList.get(index) and threw
    // IndexOutOfBounds; getBandInfo did bandList.get(0) on an empty list.

    @Test
    public void isValidBandIndex_acceptsInRangeRejectsNegativeAndEnd() {
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(7_074_000L, "40m"));
        assertThat(OperationBand.isValidBandIndex(0)).isTrue();
        assertThat(OperationBand.isValidBandIndex(1)).isTrue();
        assertThat(OperationBand.isValidBandIndex(-1)).isFalse();
        assertThat(OperationBand.isValidBandIndex(2)).isFalse();   // == size (the off-by-one)
        assertThat(OperationBand.isValidBandIndex(99)).isFalse();
    }

    @Test
    public void getBandFreq_indexEqualToSize_returnsDefault() {
        // The off-by-one: index == size passed the old `index > size` guard and
        // dereferenced past the end. It must now fall back to the default band.
        OperationBand.bandList.add(new OperationBand.Band(7_074_000L, "40m"));
        assertThat(OperationBand.getBandFreq(1)).isEqualTo(OperationBand.getDefaultBand());
    }

    @Test
    public void getBandFreq_negativeIndex_returnsDefault() {
        OperationBand.bandList.add(new OperationBand.Band(7_074_000L, "40m"));
        assertThat(OperationBand.getBandFreq(-1)).isEqualTo(OperationBand.getDefaultBand());
    }

    @Test
    public void getBandInfo_emptyList_returnsDefaultInsteadOfCrashing() {
        // bandList empty (bands.txt missing/corrupt): the old code did
        // bandList.get(0) and threw. Now it formats the default band.
        assertThat(OperationBand.bandList).isEmpty();
        assertThat(OperationBand.getBandInfo(0)).isEqualTo("  14.074 MHz (20m)");
    }

    @Test
    public void getBandInfo_negativeIndex_fallsBackToFirst() {
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        assertThat(OperationBand.getBandInfo(-1)).isEqualTo("  14.074 MHz (20m)");
    }

    // ---- parseBandLines -----------------------------------------------------
    // A single malformed line must be logged and skipped, not abort the whole
    // band-list load and leave the app with no bands.

    @Test
    public void parseBandLines_keepsValidSkipsCommentsAndBlanks() {
        String[] lines = {"*:14074000:20m", "# a comment", "", " :7074000:40m"};
        java.util.ArrayList<OperationBand.Band> bands = OperationBand.parseBandLines(lines);
        assertThat(bands).hasSize(2);
        assertThat(bands.get(0).band).isEqualTo(14_074_000L);
        assertThat(bands.get(1).band).isEqualTo(7_074_000L);
    }

    @Test
    public void parseBandLines_skipsTruncatedLineButKeepsNeighbours() {
        // "20m:" has a colon (so isBandLine accepts it) but splits to a single
        // field, so the Band constructor throws AIOOBE. It must be skipped while
        // the surrounding valid lines still load.
        String[] lines = {"*:14074000:20m", "20m:", " :7074000:40m"};
        java.util.ArrayList<OperationBand.Band> bands = OperationBand.parseBandLines(lines);
        assertThat(bands).hasSize(2);
        assertThat(bands.get(0).waveLength).isEqualTo("20m");
        assertThat(bands.get(1).waveLength).isEqualTo("40m");
    }

    @Test
    public void parseBandLines_skipsNonNumericFrequency() {
        String[] lines = {"*:notanumber:20m", " :7074000:40m"};
        java.util.ArrayList<OperationBand.Band> bands = OperationBand.parseBandLines(lines);
        assertThat(bands).hasSize(1);
        assertThat(bands.get(0).band).isEqualTo(7_074_000L);
    }

    @Test
    public void parseBandLines_nullInput_returnsEmpty() {
        assertThat(OperationBand.parseBandLines(null)).isEmpty();
    }

    // ---- custom (user-defined) dial frequencies (issue #470) ----------------
    // Operators can add off-plan dials (e.g. DXpedition frequencies); the
    // parse/validate/serialize/merge logic is pure and covered here. Persistence
    // (SharedPreferences) is covered by OperationBandCustomBandsTest.

    @Test
    public void parseCustomFreq_acceptsWholeHz() {
        assertThat(OperationBand.parseCustomFreq("14090000")).isEqualTo(14_090_000L);
        assertThat(OperationBand.parseCustomFreq("  7090000 ")).isEqualTo(7_090_000L);
    }

    @Test
    public void parseCustomFreq_rejectsBlankAndNonNumeric() {
        assertThat(OperationBand.parseCustomFreq(null)).isEqualTo(-1L);
        assertThat(OperationBand.parseCustomFreq("")).isEqualTo(-1L);
        assertThat(OperationBand.parseCustomFreq("   ")).isEqualTo(-1L);
        assertThat(OperationBand.parseCustomFreq("14.090")).isEqualTo(-1L); // not whole Hz
        assertThat(OperationBand.parseCustomFreq("abc")).isEqualTo(-1L);
    }

    @Test
    public void isValidCustomFreq_enforcesRange() {
        assertThat(OperationBand.isValidCustomFreq(OperationBand.MIN_CUSTOM_FREQ)).isTrue();
        assertThat(OperationBand.isValidCustomFreq(OperationBand.MAX_CUSTOM_FREQ)).isTrue();
        assertThat(OperationBand.isValidCustomFreq(14_090_000L)).isTrue();
        assertThat(OperationBand.isValidCustomFreq(OperationBand.MIN_CUSTOM_FREQ - 1)).isFalse();
        assertThat(OperationBand.isValidCustomFreq(OperationBand.MAX_CUSTOM_FREQ + 1)).isFalse();
    }

    @Test
    public void toCustomLine_ft8OmitsModeTag_nonFt8IncludesIt() {
        OperationBand.Band ft8 = new OperationBand.Band(14_090_000L, "3B9 DX");
        assertThat(OperationBand.toCustomLine(ft8)).isEqualTo(" :14090000:3B9 DX");

        OperationBand.Band ft4 = new OperationBand.Band(14_090_000L, "3B9 DX");
        ft4.mode = com.k1af.ft8af.FT8Common.FT4_MODE;
        assertThat(OperationBand.toCustomLine(ft4)).isEqualTo(" :14090000:3B9 DX:FT4");
    }

    @Test
    public void customBands_serializeParseRoundTrip() {
        OperationBand.Band a = new OperationBand.Band(14_090_000L, "3B9 DX");
        OperationBand.Band b = new OperationBand.Band(7_090_000L, "40m DX");
        b.mode = com.k1af.ft8af.FT8Common.FT4_MODE;
        String blob = OperationBand.serializeCustomBands(java.util.Arrays.asList(a, b));

        ArrayList<OperationBand.Band> parsed = OperationBand.parseCustomBands(blob);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).band).isEqualTo(14_090_000L);
        assertThat(parsed.get(0).waveLength).isEqualTo("3B9 DX");
        assertThat(parsed.get(0).mode).isEqualTo(com.k1af.ft8af.FT8Common.FT8_MODE);
        assertThat(parsed.get(0).custom).isTrue();
        assertThat(parsed.get(1).band).isEqualTo(7_090_000L);
        assertThat(parsed.get(1).mode).isEqualTo(com.k1af.ft8af.FT8Common.FT4_MODE);
        assertThat(parsed.get(1).custom).isTrue();
    }

    @Test
    public void parseCustomBands_emptyOrNull_returnsEmpty() {
        assertThat(OperationBand.parseCustomBands("")).isEmpty();
        assertThat(OperationBand.parseCustomBands(null)).isEmpty();
    }

    @Test
    public void appendCustomBands_appendsNonDuplicatesFlaggedCustom() {
        ArrayList<OperationBand.Band> target = new ArrayList<>();
        target.add(new OperationBand.Band(14_074_000L, "20m")); // built-in

        ArrayList<OperationBand.Band> customs = new ArrayList<>();
        OperationBand.Band dx = new OperationBand.Band(14_090_000L, "3B9 DX");
        dx.custom = true;
        customs.add(dx);

        OperationBand.appendCustomBands(target, customs);
        assertThat(target).hasSize(2);
        assertThat(target.get(1).band).isEqualTo(14_090_000L);
        assertThat(target.get(1).custom).isTrue();
    }

    @Test
    public void appendCustomBands_skipsDuplicateFreqAndModeOfBuiltin() {
        ArrayList<OperationBand.Band> target = new ArrayList<>();
        target.add(new OperationBand.Band(14_074_000L, "20m")); // built-in FT8 20m

        ArrayList<OperationBand.Band> customs = new ArrayList<>();
        OperationBand.Band dup = new OperationBand.Band(14_074_000L, "dup"); // same freq+mode
        dup.custom = true;
        customs.add(dup);

        OperationBand.appendCustomBands(target, customs);
        assertThat(target).hasSize(1); // duplicate skipped
    }

    @Test
    public void appendCustomBands_sameFreqDifferentModeIsNotDuplicate() {
        ArrayList<OperationBand.Band> target = new ArrayList<>();
        target.add(new OperationBand.Band(14_074_000L, "20m")); // FT8

        ArrayList<OperationBand.Band> customs = new ArrayList<>();
        OperationBand.Band ft4 = new OperationBand.Band(14_074_000L, "20m FT4");
        ft4.mode = com.k1af.ft8af.FT8Common.FT4_MODE;
        ft4.custom = true;
        customs.add(ft4);

        OperationBand.appendCustomBands(target, customs);
        assertThat(target).hasSize(2); // different mode -> kept
    }

    @Test
    public void getBandInfo_customBand_appendsStarMarker() {
        OperationBand.Band b = new OperationBand.Band(14_090_000L, "3B9 DX");
        b.custom = true;
        assertThat(b.getBandInfo()).isEqualTo("  14.090 MHz (3B9 DX) ★");
    }

    // --- Enabled Bands count / toggles (issue #929) -------------------------

    @Test
    public void getIndexByFreq_marksAppendedEntryAdHoc() {
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        int i = OperationBand.getIndexByFreq(14_076_500L);
        assertThat(OperationBand.bandList.get(i).adHoc).isTrue();
        assertThat(OperationBand.bandList.get(0).adHoc).isFalse();
    }

    @Test
    public void getAllWaveLengths_skipsAdHocEntries() {
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        OperationBand.getIndexByFreq(23_000_000L); // off-plan rig frequency -> "13m"
        assertThat(OperationBand.getAllWaveLengths()).containsExactly("20m");
    }

    @Test
    public void getAllWaveLengths_keepsCustomDials() {
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        OperationBand.Band c = new OperationBand.Band(14_090_000L, "3B9 DX");
        c.custom = true;
        OperationBand.bandList.add(c);
        assertThat(OperationBand.getAllWaveLengths()).containsExactly("20m", "3B9 DX").inOrder();
    }

    @Test
    public void getAllWaveLengths_orderedByFrequencyAndDistinct() {
        // FT2 extras appended at the end of bands.txt (e.g. 1.25m) must sort into place.
        OperationBand.bandList.add(new OperationBand.Band(14_074_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(1_840_000L, "160m"));
        OperationBand.bandList.add(new OperationBand.Band(144_174_000L, "2m"));
        OperationBand.bandList.add(new OperationBand.Band(14_080_000L, "20m"));
        OperationBand.bandList.add(new OperationBand.Band(222_177_000L, "1.25m"));
        OperationBand.bandList.add(new OperationBand.Band(7_074_000L, "40m"));
        assertThat(OperationBand.getAllWaveLengths())
                .containsExactly("160m", "40m", "20m", "2m", "1.25m").inOrder();
    }

    @Test
    public void countEnabledBands_ignoresStaleExcludedNames() {
        List<String> waves = Arrays.asList("80m", "40m", "20m", "15m");
        // Two listed bands hidden plus three names no longer in the list.
        Set<String> excluded = new HashSet<>(Arrays.asList("80m", "15m", "16m", "9m", "100m"));
        assertThat(OperationBand.countEnabledBands(waves, excluded)).isEqualTo(2);
    }

    @Test
    public void toggleExcludedBand_disablePrunesStaleNames() {
        List<String> waves = Arrays.asList("80m", "40m", "20m");
        Set<String> excluded = new HashSet<>(Arrays.asList("80m", "16m", "9m"));
        Set<String> updated = OperationBand.toggleExcludedBand(waves, excluded, "40m", false);
        assertThat(updated).containsExactly("80m", "40m");
        // The input set is left untouched.
        assertThat(excluded).containsExactly("80m", "16m", "9m");
    }

    @Test
    public void toggleExcludedBand_enableRemovesName() {
        List<String> waves = Arrays.asList("80m", "40m");
        Set<String> excluded = new HashSet<>(Arrays.asList("80m"));
        assertThat(OperationBand.toggleExcludedBand(waves, excluded, "80m", true)).isEmpty();
    }

    @Test
    public void toggleExcludedBand_staleNamesDoNotBlockDisabling() {
        // Old guard compared excluded.size() to the list size: 2 stale + 1 real
        // exclusion out of 4 listed blocked turning a 2nd band off with 3 still on.
        List<String> waves = Arrays.asList("80m", "40m", "20m", "15m");
        Set<String> excluded = new HashSet<>(Arrays.asList("80m", "16m", "9m"));
        assertThat(OperationBand.toggleExcludedBand(waves, excluded, "40m", false))
                .containsExactly("80m", "40m");
    }

    @Test
    public void toggleExcludedBand_refusesToHideLastBand() {
        List<String> waves = Arrays.asList("80m", "40m");
        Set<String> excluded = new HashSet<>(Arrays.asList("80m"));
        assertThat(OperationBand.toggleExcludedBand(waves, excluded, "40m", false)).isNull();
    }
}
