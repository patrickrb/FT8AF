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
 * <p>Fields that are {@code null} <em>or</em> empty are omitted entirely. Emitting a
 * zero-length field ({@code <gridsquare:0> }) instead — as this class used to for empty
 * strings — is not portable: several ADIF importers treat a length-0 field as malformed
 * and reject the whole record rather than reading it as "absent". An absent optional
 * field means the same thing to every parser, so that is what we write.
 */
public final class AdifRecord {

    /** Header (with {@code <eoh>}) written once at the top of a fresh ADIF file. */
    public static final String HEADER = "FT8AF ADIF Export<eoh>\n";

    /**
     * Application-defined field carrying the "manually confirmed" flag. ADIF has no
     * {@code QSL_MANUAL} field — that bare name (which FT8AF wrote until this change)
     * is non-conformant. {@code APP_<PROGRAMID>_<FIELD>} is the spec's escape hatch for
     * program-specific data. {@link QSLRecord} still reads the legacy bare name so ADIF
     * files exported by older builds keep round-tripping.
     */
    public static final String APP_QSL_MANUAL = "APP_FT8AF_QSL_MANUAL";

    /**
     * Application-defined fields carrying the operator's position as a plain decimal
     * degree, alongside the standard {@code MY_LAT}/{@code MY_LON}.
     *
     * <p>Both are written because they serve different readers. ADIF's own location
     * datatype is {@code XDDD MM.MMM} — degrees and decimal minutes — which every
     * conventional logbook understands but which rounds to about 1.8 m and has to be
     * re-parsed. rtota.app prefers these decimal twins, so a route replay gets back
     * exactly the coordinate the phone recorded rather than a rounded reconstruction.
     */
    public static final String APP_RTOTA_LAT = "APP_RTOTA_LAT";
    public static final String APP_RTOTA_LON = "APP_RTOTA_LON";

    /** Legacy, non-conformant name for {@link #APP_QSL_MANUAL}. Read-only: never emitted. */
    public static final String LEGACY_QSL_MANUAL = "QSL_MANUAL";

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
    /** Operator's position at QSO time; null when the app had no fix to record. */
    private Double myLat;
    private Double myLon;

    public AdifRecord call(String v) { this.call = v; return this; }

    /** SWL record: emits {@code <swl:1>Y } instead of the QSL_RCVD/APP_FT8AF_QSL_MANUAL pair. */
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
    public AdifRecord myLat(Double v) { this.myLat = v; return this; }
    public AdifRecord myLon(Double v) { this.myLon = v; return this; }

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
            // QSL_MANUAL is not an ADIF field. The spec reserves the APP_<PROGRAMID>_
            // prefix for application-defined fields, so a strict importer can skip ours
            // instead of erroring on an unrecognised bare name. QSL_RCVD above *is*
            // standard and keeps its name.
            sb.append(manualQsl
                    ? "<" + APP_QSL_MANUAL + ":1>Y "
                    : "<" + APP_QSL_MANUAL + ":1>N ");
        }
        appendIfNotEmpty(sb, "gridsquare", gridsquare);
        // Empty as well as null: mode has its own branch (for the MFSK submode split) and
        // so bypasses appendIfNotEmpty, which is exactly how it kept emitting <mode:0>.
        if (mode != null && !mode.isEmpty()) {
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
        appendIfNotEmpty(sb, "rst_sent", rstSent);
        appendIfNotEmpty(sb, "rst_rcvd", rstRcvd);
        appendIfNotEmpty(sb, "qso_date", qsoDate);
        appendIfNotEmpty(sb, "time_on", timeOn);
        appendIfNotEmpty(sb, "qso_date_off", qsoDateOff);
        appendIfNotEmpty(sb, "time_off", timeOff);
        appendIfNotEmpty(sb, "band", band);
        appendIfNotEmpty(sb, "freq", freq);
        appendIfNotEmpty(sb, "station_callsign", stationCallsign);
        appendIfNotEmpty(sb, "my_gridsquare", myGridsquare);
        appendIfNotEmpty(sb, "operator", operator);
        // POTA fields. Only emit when populated so non-POTA QSOs stay clean.
        appendIfNotEmpty(sb, "MY_SIG", mySig);
        appendIfNotEmpty(sb, "MY_SIG_INFO", mySigInfo);
        appendIfNotEmpty(sb, "SIG", sig);
        appendIfNotEmpty(sb, "SIG_INFO", sigInfo);
        // Operator position, standard field first then the exact decimal twin. Both
        // are emitted or neither: a lone longitude is worse than no position at all.
        if (myLat != null && myLon != null) {
            appendIfNotEmpty(sb, "MY_LAT", AdifFormat.location(myLat, true));
            appendIfNotEmpty(sb, "MY_LON", AdifFormat.location(myLon, false));
            appendIfNotEmpty(sb, APP_RTOTA_LAT, AdifFormat.decimalDegrees(myLat));
            appendIfNotEmpty(sb, APP_RTOTA_LON, AdifFormat.decimalDegrees(myLon));
        }
        appendIfNotEmpty(sb, "comment", comment);
        sb.append("<eor>\n");
        return sb.toString();
    }

    /** UTF-8 encoding of {@link #build()}, ready to append to the ADIF file. */
    public byte[] toBytes() {
        return build().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Emit a field only when the value is non-null and non-empty. Omitting an empty
     * optional field is the portable way to say "no value"; a zero-length field is not
     * (see the class javadoc).
     */
    private static void appendIfNotEmpty(StringBuilder sb, String name, String value) {
        if (value == null || value.isEmpty()) return;
        appendField(sb, name, value);
    }

    private static void appendField(StringBuilder sb, String name, String value) {
        sb.append(String.format(Locale.US, "<%s:%d>%s ", name, utf8Length(value), value));
    }

    private static int utf8Length(String value) {
        return AdifFormat.utf8Length(value);
    }
}
