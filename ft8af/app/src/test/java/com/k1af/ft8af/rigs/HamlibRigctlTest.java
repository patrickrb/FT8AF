package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Wire-format and reply-parsing coverage for the Hamlib {@code rigctld} network
 * protocol. These bytes go to a daemon that keys a real transmitter, so the
 * exact command strings (and the frequency-vs-report parsing that drives the
 * dial and the CAT-liveness watchdog) are pinned here.
 */
public class HamlibRigctlTest {

    private static String str(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Test
    public void setFreq_buildsFCommandInHz() {
        assertThat(str(HamlibRigctl.setFreq(14_074_000L))).isEqualTo("F 14074000\n");
    }

    @Test
    public void readFreq_buildsLowercaseF() {
        assertThat(str(HamlibRigctl.readFreq())).isEqualTo("f\n");
    }

    @Test
    public void setPtt_onAndOff() {
        assertThat(str(HamlibRigctl.setPtt(true))).isEqualTo("T 1\n");
        assertThat(str(HamlibRigctl.setPtt(false))).isEqualTo("T 0\n");
    }

    @Test
    public void setMode_buildsModeAndPassband() {
        assertThat(str(HamlibRigctl.setMode("USB", 2400))).isEqualTo("M USB 2400\n");
    }

    @Test
    public void setDataUsbMode_usesPktUsbWithDefaultPassband() {
        // FT8 is data/packet USB; a wrong mode would push the tones off WSJT-X's grid.
        assertThat(str(HamlibRigctl.setDataUsbMode())).isEqualTo("M PKTUSB 0\n");
    }

    @Test
    public void parseFreqReply_parsesBareNumber() {
        assertThat(HamlibRigctl.parseFreqReply("14074000")).isEqualTo(14_074_000L);
    }

    @Test
    public void parseFreqReply_trimsWhitespace() {
        assertThat(HamlibRigctl.parseFreqReply("  7074000  ")).isEqualTo(7_074_000L);
    }

    @Test
    public void parseFreqReply_rejectsPttStatusAndTinyNumbers() {
        // rigctld reports PTT/split as bare 0/1 — must not be read as a frequency.
        assertThat(HamlibRigctl.parseFreqReply("0")).isEqualTo(-1);
        assertThat(HamlibRigctl.parseFreqReply("1")).isEqualTo(-1);
        assertThat(HamlibRigctl.parseFreqReply("999")).isEqualTo(-1);
    }

    @Test
    public void parseFreqReply_rejectsNonNumericAndReports() {
        assertThat(HamlibRigctl.parseFreqReply("RPRT 0")).isEqualTo(-1);
        assertThat(HamlibRigctl.parseFreqReply("PKTUSB")).isEqualTo(-1);
        assertThat(HamlibRigctl.parseFreqReply("")).isEqualTo(-1);
        assertThat(HamlibRigctl.parseFreqReply(null)).isEqualTo(-1);
        assertThat(HamlibRigctl.parseFreqReply("-14074000")).isEqualTo(-1);
    }

    @Test
    public void parseReport_success() {
        assertThat(HamlibRigctl.parseReport("RPRT 0")).isEqualTo(0);
    }

    @Test
    public void parseReport_errorCode() {
        assertThat(HamlibRigctl.parseReport("RPRT -1")).isEqualTo(-1);
    }

    @Test
    public void parseReport_notAReportReturnsSentinel() {
        assertThat(HamlibRigctl.parseReport("14074000")).isEqualTo(HamlibRigctl.NO_REPORT);
        assertThat(HamlibRigctl.parseReport("")).isEqualTo(HamlibRigctl.NO_REPORT);
        assertThat(HamlibRigctl.parseReport(null)).isEqualTo(HamlibRigctl.NO_REPORT);
    }

    @Test
    public void parseReport_malformedCodeReturnsSentinel() {
        assertThat(HamlibRigctl.parseReport("RPRT x")).isEqualTo(HamlibRigctl.NO_REPORT);
    }

    @Test
    public void scanLines_splitsCompleteLinesAndKeepsRemainder() {
        HamlibRigctl.Scan scan = HamlibRigctl.scanLines("14074000\nRPRT 0\n707");
        assertThat(scan.lines).containsExactly("14074000", "RPRT 0").inOrder();
        assertThat(scan.remainder).isEqualTo("707");
    }

    @Test
    public void scanLines_noNewlineIsAllRemainder() {
        HamlibRigctl.Scan scan = HamlibRigctl.scanLines("1407");
        assertThat(scan.lines).isEmpty();
        assertThat(scan.remainder).isEqualTo("1407");
    }

    @Test
    public void scanLines_trailingNewlineLeavesEmptyRemainder() {
        HamlibRigctl.Scan scan = HamlibRigctl.scanLines("14074000\n");
        assertThat(scan.lines).containsExactly("14074000");
        assertThat(scan.remainder).isEmpty();
    }

    @Test
    public void scanLines_emptyOrNull() {
        assertThat(HamlibRigctl.scanLines("").lines).isEmpty();
        assertThat(HamlibRigctl.scanLines(null).lines).isEmpty();
        assertThat(HamlibRigctl.scanLines(null).remainder).isEmpty();
    }
}
