package com.k1af.ft8af.rigs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-logic helper for talking to a <a href="https://hamlib.github.io/">Hamlib</a>
 * {@code rigctld} daemon over TCP. {@code rigctld} exposes Hamlib's full rig
 * catalogue (200+ radios) through a tiny line-based text protocol on port
 * {@value #DEFAULT_PORT}; this is exactly how WSJT-X / JTDX "use Hamlib" for
 * network rig control. Running rigctld on a PC or Raspberry Pi next to the radio
 * lets the Android app drive any Hamlib-supported rig without bundling the
 * native library.
 *
 * <p>Only the handful of commands this app needs are modelled — set/read
 * frequency, set PTT and set the FT8 data mode — plus parsers for the replies.
 * Everything here is deliberately free of Android and I/O dependencies so the
 * wire format and reply parsing can be unit-tested directly; {@link HamlibNetRig}
 * and {@code HamlibConnector} are thin wrappers that only move bytes.</p>
 *
 * <p>The rigctld single-shot protocol used here:</p>
 * <pre>
 *   set frequency   "F 14074000\n"      -&gt; "RPRT 0\n"
 *   get frequency   "f\n"               -&gt; "14074000\n"
 *   set PTT         "T 1\n" / "T 0\n"   -&gt; "RPRT 0\n"
 *   set mode        "M PKTUSB 0\n"      -&gt; "RPRT 0\n"
 * </pre>
 */
public final class HamlibRigctl {

    /** Default TCP port a {@code rigctld} instance listens on. */
    public static final int DEFAULT_PORT = 4532;

    /**
     * FT8 is upper-sideband data. rigctld's packet/data-USB mode ("PKTUSB")
     * keeps the rig on the tone grid WSJT-X expects, the same reason the wired
     * drivers select DATA-U rather than plain USB.
     */
    public static final String MODE_DATA_USB = "PKTUSB";

    /**
     * Passband width argument for a mode change. {@code 0} tells rigctld to keep
     * the rig's current/default passband for that mode instead of forcing one.
     */
    public static final int DEFAULT_PASSBAND = 0;

    /** Sentinel returned by {@link #parseReport(String)} when a line is not an RPRT reply. */
    public static final int NO_REPORT = Integer.MIN_VALUE;

    /**
     * rigctld reports PTT and split status as a bare {@code 0}/{@code 1}. A real
     * dial frequency in Hz is always far larger, so this threshold lets a
     * frequency reply be told apart from those other bare-number replies.
     */
    static final long MIN_PLAUSIBLE_FREQ_HZ = 1000L;

    private HamlibRigctl() {
    } // utility class

    /** Build the "set frequency" command line ({@code F <hz>}). */
    public static byte[] setFreq(long hz) {
        return line("F " + hz);
    }

    /** Build the "read frequency" command line ({@code f}). */
    public static byte[] readFreq() {
        return line("f");
    }

    /** Build the "set PTT" command line ({@code T 1} / {@code T 0}). */
    public static byte[] setPtt(boolean on) {
        return line("T " + (on ? "1" : "0"));
    }

    /** Build a "set mode" command line ({@code M <mode> <passbandHz>}). */
    public static byte[] setMode(String mode, int passbandHz) {
        return line("M " + mode + " " + passbandHz);
    }

    /** Build the FT8 data-USB mode command ({@code M PKTUSB 0}). */
    public static byte[] setDataUsbMode() {
        return setMode(MODE_DATA_USB, DEFAULT_PASSBAND);
    }

    /** Terminate a command with the newline rigctld expects and encode as ASCII. */
    static byte[] line(String command) {
        return (command + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Parse a single response line as a frequency reply.
     *
     * @param line one line of rigctld output (newline already stripped)
     * @return the frequency in Hz, or {@code -1} when the line is not a bare
     * number that looks like a dial frequency (e.g. an {@code RPRT} report, a
     * mode name, PTT {@code 0}/{@code 1}, or empty)
     */
    public static long parseFreqReply(String line) {
        if (line == null) {
            return -1;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return -1;
            }
        }
        try {
            long value = Long.parseLong(trimmed);
            return value >= MIN_PLAUSIBLE_FREQ_HZ ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parse a single response line as an {@code RPRT} report.
     *
     * @param line one line of rigctld output (newline already stripped)
     * @return the report code ({@code 0} success, negative Hamlib error), or
     * {@link #NO_REPORT} when the line is not an {@code RPRT} reply
     */
    public static int parseReport(String line) {
        if (line == null) {
            return NO_REPORT;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("RPRT")) {
            return NO_REPORT;
        }
        try {
            return Integer.parseInt(trimmed.substring(4).trim());
        } catch (NumberFormatException e) {
            return NO_REPORT;
        }
    }

    /**
     * Split a streamed, possibly-partial buffer into complete newline-terminated
     * lines plus a trailing remainder. TCP hands data over in arbitrary chunks,
     * so a reply can arrive split across reads or several replies can arrive
     * together; the caller keeps {@link Scan#remainder} and prepends it to the
     * next chunk.
     */
    public static Scan scanLines(String buffered) {
        List<String> lines = new ArrayList<>();
        if (buffered == null || buffered.isEmpty()) {
            return new Scan(lines, "");
        }
        int start = 0;
        int idx;
        while ((idx = buffered.indexOf('\n', start)) >= 0) {
            lines.add(buffered.substring(start, idx));
            start = idx + 1;
        }
        return new Scan(lines, buffered.substring(start));
    }

    /** Result of {@link #scanLines(String)}: complete lines and any partial tail. */
    public static final class Scan {
        public final List<String> lines;
        public final String remainder;

        Scan(List<String> lines, String remainder) {
            this.lines = lines;
            this.remainder = remainder;
        }
    }
}
