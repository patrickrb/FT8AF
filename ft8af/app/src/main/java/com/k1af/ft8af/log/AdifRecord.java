package com.k1af.ft8af.log;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Builds a single ADIF QSO record (one {@code <eor>}-terminated line) from a set of
 * field values, so every writer of an FT8AF ADIF file emits byte-identical records.
 *
 * <p>This is the shared source of truth for ADIF record formatting: the bulk export in
 * {@link ShareLogs#downQSLTableToFile} and the incremental real-time export in
 * {@link AdifLogFile} both build their records here so the two can never drift.
 *
 * <p>Formatting rules baked in:
 * <ul>
 *   <li>The {@code CALL} field goes through {@link AdifFormat#callField} so hashed/compound
 *       callsigns rendered as {@code <DK4RH>} become the bare {@code DK4RH} (LoTW/eQSL/LOG4OM
 *       reject bracketed calls).</li>
 *   <li>FT4/FT2 are emitted as {@code MODE=MFSK} + {@code SUBMODE} via
 *       {@link AdifFormat#mfskSubmode} (pota.app rejects a bare {@code <mode>FT2}).</li>
 *   <li>Every {@code <field:len>value } length is the number of UTF-8 <em>bytes</em> of the
 *       value, not UTF-16 chars, so non-ASCII content (comments, park names) stays aligned.</li>
 * </ul>
 *
 * <p>Fields left {@code null} are omitted entirely; a field set to {@code ""} is still
 * emitted (as a zero-length field) to match the historical export behaviour. The POTA
 * fields ({@code MY_SIG}, {@code MY_SIG_INFO}, {@code SIG}, {@code SIG_INFO}) are the
 * exception: they are emitted only when non-null <em>and</em> non-empty so ordinary
 * non-POTA contacts stay clean.
 */
public final class AdifRecord {

    /** Header (with {@code <eoh>}) written once at the top of a fresh ADIF file. */
    public static final String HEADER = "FT8AF ADIF Export<eoh>\n";

    private String call;
    private boolean swl;
    private boolean lotwQsl;
    private boolean manualQsl;
    private String gridsquare;
    private String mode;
    private String rstSent;
    private String rstRcvd;
    private String qsoDate;
    private String timeOn;
    private String qsoDateOff;
    private String timeOff;
    private String band;
    private String freq;
    private String stationCallsign;
    private String myGridsquare;
    private String operator;
    private String mySig;
    private String mySigInfo;
    private String sig;
    private String sigInfo;
    private String comment;

    public AdifRecord call(String v) { this.call = v; return this; }

    /** SWL record: emits {@code <swl:1>Y } instead of the QSL_RCVD/QSL_MANUAL pair. */
    public AdifRecord swl(boolean v) { this.swl = v; return this; }

    public AdifRecord lotwQsl(boolean v) { this.lotwQsl = v; return this; }

    public AdifRecord manualQsl(boolean v) { this.manualQsl = v; return this; }

    public AdifRecord gridsquare(String v) { this.gridsquare = v; return this; }

    public AdifRecord mode(String v) { this.mode = v; return this; }

    public AdifRecord rstSent(String v) { this.rstSent = v; return this; }

    public AdifRecord rstRcvd(String v) { this.rstRcvd = v; return this; }

    public AdifRecord qsoDate(String v) { this.qsoDate = v; return this; }

    public AdifRecord timeOn(String v) { this.timeOn = v; return this; }

    public AdifRecord qsoDateOff(String v) { this.qsoDateOff = v; return this; }

    public AdifRecord timeOff(String v) { this.timeOff = v; return this; }

    public AdifRecord band(String v) { this.band = v; return this; }

    public AdifRecord freq(String v) { this.freq = v; return this; }

    public AdifRecord stationCallsign(String v) { this.stationCallsign = v; return this; }

    public AdifRecord myGridsquare(String v) { this.myGridsquare = v; return this; }

    public AdifRecord operator(String v) { this.operator = v; return this; }

    public AdifRecord mySig(String v) { this.mySig = v; return this; }

    public AdifRecord mySigInfo(String v) { this.mySigInfo = v; return this; }

    public AdifRecord sig(String v) { this.sig = v; return this; }

    public AdifRecord sigInfo(String v) { this.sigInfo = v; return this; }

    public AdifRecord comment(String v) { this.comment = v; return this; }

    /**
     * Render this record as an ADIF line, ending in {@code <eor>\n}. The field order matches
     * the historical {@link ShareLogs} export so existing consumers see no change.
     */
    public String build() {
        StringBuilder sb = new StringBuilder();
        sb.append(AdifFormat.callField(call));
        if (swl) {
            sb.append("<swl:1>Y ");
        } else {
            sb.append(lotwQsl ? "<QSL_RCVD:1>Y " : "<QSL_RCVD:1>N ");
            sb.append(manualQsl ? "<QSL_MANUAL:1>Y " : "<QSL_MANUAL:1>N ");
        }
        appendIfNotNull(sb, "gridsquare", gridsquare);
        if (mode != null) {
            // FT4/FT2 are ADIF submodes of MFSK, not standalone modes — a bare <mode>FT2 is
            // rejected as invalid by pota.app and other ADIF consumers.
            String submode = AdifFormat.mfskSubmode(mode);
            if (submode != null) {
                sb.append(String.format(Locale.US, "<mode:4>MFSK <submode:%d>%s ",
                        utf8Length(submode), submode));
            } else {
                appendField(sb, "mode", mode);
            }
        }
        appendIfNotNull(sb, "rst_sent", rstSent);
        appendIfNotNull(sb, "rst_rcvd", rstRcvd);
        appendIfNotNull(sb, "qso_date", qsoDate);
        appendIfNotNull(sb, "time_on", timeOn);
        appendIfNotNull(sb, "qso_date_off", qsoDateOff);
        appendIfNotNull(sb, "time_off", timeOff);
        appendIfNotNull(sb, "band", band);
        appendIfNotNull(sb, "freq", freq);
        appendIfNotNull(sb, "station_callsign", stationCallsign);
        appendIfNotNull(sb, "my_gridsquare", myGridsquare);
        appendIfNotNull(sb, "operator", operator);
        // POTA fields. Only emit when populated so non-POTA QSOs stay clean.
        appendIfNotEmpty(sb, "MY_SIG", mySig);
        appendIfNotEmpty(sb, "MY_SIG_INFO", mySigInfo);
        appendIfNotEmpty(sb, "SIG", sig);
        appendIfNotEmpty(sb, "SIG_INFO", sigInfo);
        String c = comment == null ? "" : comment;
        sb.append(String.format(Locale.US, "<comment:%d>%s <eor>\n", utf8Length(c), c));
        return sb.toString();
    }

    /** UTF-8 encoding of {@link #build()}, ready to append to the ADIF file. */
    public byte[] toBytes() {
        return build().getBytes(StandardCharsets.UTF_8);
    }

    /** Emit a field when the value is non-null (an empty value still emits a zero-length field). */
    private static void appendIfNotNull(StringBuilder sb, String name, String value) {
        if (value == null) return;
        appendField(sb, name, value);
    }

    /** Emit a field only when the value is non-null and non-empty (POTA fields). */
    private static void appendIfNotEmpty(StringBuilder sb, String name, String value) {
        if (value == null || value.isEmpty()) return;
        appendField(sb, name, value);
    }

    private static void appendField(StringBuilder sb, String name, String value) {
        sb.append(String.format(Locale.US, "<%s:%d>%s ", name, utf8Length(value), value));
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
