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
}
