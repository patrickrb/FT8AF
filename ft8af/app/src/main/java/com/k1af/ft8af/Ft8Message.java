package com.k1af.ft8af;
/**
 * Ft8Message class represents the parsed result of an FT8 signal.
 * Includes UTC time, SNR, time offset, frequency, score, message text, message hash.
 * ----2022.5.6-----
 * time_sec may be the time offset; this is not fully confirmed yet and needs further investigation.
 * 1. For convenient display in lists, each element returns a String result via Get methods.
 * -----2022.5.13---
 * 2. Added i3, n3 message type content.
 * @author BG7YOZ
 * @date 2022.5.6
 */

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.NonNull;

import com.k1af.ft8af.database.DatabaseOpr;
import com.k1af.ft8af.ft8signal.FT8Package;
import com.k1af.ft8af.ft8transmit.GenerateFT8;
import com.k1af.ft8af.ft8transmit.TransmitCallsign;
import com.k1af.ft8af.maidenhead.MaidenheadGrid;
import com.k1af.ft8af.rigs.BaseRigOperation;
import com.k1af.ft8af.timer.UtcTimer;
import com.google.android.gms.maps.model.LatLng;

import java.text.SimpleDateFormat;
import java.util.ArrayList;

public class Ft8Message {
    private static String TAG = "Ft8Message";

    /** Sentinel value meaning "the decoder did not set an SNR for this candidate". */
    public static final int SNR_UNKNOWN = Integer.MIN_VALUE;

    public int i3 = 0;
    public int n3 = 0;
    public int signalFormat = FT8Common.FT8_MODE;//whether this is an FT8 format message
    public long utcTime;//UTC time
    public boolean isValid;//whether this is valid information
    public int snr = SNR_UNKNOWN;//signal-to-noise ratio
    public float time_sec = 0;//time offset (seconds)
    public float freq_hz = 0;//frequency
    public int score = 0;//score
    public int messageHash;//message hash

    public String callsignFrom = null;//callsign initiating the call
    public String callsignTo = null;//callsign receiving the call

    public String modifier = null;//modifier for target callsign, e.g. POTA in "CQ POTA BG7YOZ OL50"

    public String extraInfo = null;
    public String maidenGrid = null;

    public String rtty_state =null;//RTTY RU (i3=3 type) state name, two-letter code e.g.: CA, AL
    public int r_flag=0;//RTTY RU, WWROF (i3=3, i3=5 type) R flag
    public int rtty_tu;//RTTY RU, WWROF (i3=3, i3=5 type) TU; flag
    public int eu_serial;//Field Day num_tx, or serial number
    public String arrl_rac;//Field day message, ARRL RAC
    public String arrl_class;//Field day transmit class
    public String dx_call_to2;//DXpedition message second receiving callsign

    public int report = -100;//when -100, means no signal report
    public long callFromHash10 = 0;//10-bit hash code
    public long callFromHash12 = 0;//12-bit hash code
    public long callFromHash22 = 0;//22-bit hash code
    public long callToHash10 = 0;//10-bit hash code
    public long callToHash12 = 0;//12-bit hash code
    public long callToHash22 = 0;//22-bit hash code
    //private boolean isCallMe = false;//whether this is a message calling me
    public long band;//carrier frequency

    public String fromWhere = null;//used for displaying address/location
    public String toWhere = null;//used for displaying address/location

    public String continent = null;//sender's continent abbrev (NA/SA/EU/AF/AS/OC/AN); used by decode filters

    public boolean isQSL_Callsign = false;//whether this callsign has been previously contacted

    public static MessageHashMap hashList = new MessageHashMap();


    public boolean fromDxcc = false;
    public boolean fromItu = false;
    public boolean fromCq = false;
    public boolean toDxcc = false;
    public boolean toItu = false;
    public boolean toCq = false;

    // US state of the sender, derived from maidenGrid via GeneralVariables.stateForGrid
    // (null when not a US grid). fromNewState = that state is not yet in the worked set.
    public String fromState = null;
    public boolean fromNewState = false;

    public LatLng fromLatLng = null;
    public LatLng toLatLng = null;

    public boolean isWeakSignal=false;

    /**
     * True when this decode is our own transmission heard back — set by
     * {@link OwnTxEchoFilter} on the echoes it separates out, which reach the
     * displayed list only under full-duplex monitoring ({@link FullDuplexMonitor}).
     * Every consumer of the message list that acts on a decode (map markers, the
     * chip filters, the SWL scan, tap-to-call) checks this one flag rather than
     * re-deriving "is this us?" from the callsign, so the display-only promise
     * holds in one place.
     */
    public boolean isOwnEcho = false;

    /** @return true when the decoder actually set an SNR value for this message. */
    public boolean hasSnr() {
        return snr != SNR_UNKNOWN;
    }



    @NonNull
    @SuppressLint({"SimpleDateFormat", "DefaultLocale"})
    @Override
    public String toString() {
        return String.format("%s %s %+4.2f %4.0f  ~  %s Hash : %#06X",
                new SimpleDateFormat("HHmmss").format(utcTime),
                hasSnr() ? String.valueOf(snr) : "--", time_sec, freq_hz, getMessageText(), messageHash);
    }

    /**
     * Create a decoded message object; the signal format must be specified.
     *
     * @param signalFormat
     */
    public Ft8Message(int signalFormat) {
        this.signalFormat = signalFormat;
    }

    public Ft8Message(String callTo, String callFrom, String extraInfo) {
        //If free text, callTo=CQ, callFrom=MyCall, extraInfo=freeText
        this.callsignTo = callTo.toUpperCase();
        this.callsignFrom = callFrom.toUpperCase();
        this.extraInfo = extraInfo.toUpperCase();
    }

    public Ft8Message(int i3, int n3, String callTo, String callFrom, String extraInfo) {
        this.callsignTo = callTo;
        this.callsignFrom = callFrom;
        this.extraInfo = extraInfo;
        this.i3 = i3;
        this.n3 = n3;
        this.utcTime = UtcTimer.getSystemTime();//used for displaying TX
    }

    /**
     * Create a decoded message object.
     *
     * @param message If message is not null, create a decoded message object with the same content as message.
     */
    public Ft8Message(Ft8Message message) {
        if (message != null) {

            signalFormat = message.signalFormat;
            utcTime = message.utcTime;
            isValid = message.isValid;
            snr = message.snr;
            time_sec = message.time_sec;
            freq_hz = message.freq_hz;
            score = message.score;
            band = message.band;

            messageHash = message.messageHash;

            //Look up "<...>" in the hash list, widest hash first: a 22-bit match
            //identifies one callsign, while the 10-bit hash has only 1024 buckets
            //and could hit an entry belonging to a different callsign.
            if (message.callsignFrom.equals("<...>")) {
                callsignFrom = hashList.getCallsign(new long[]{message.callFromHash22, message.callFromHash12, message.callFromHash10});
            } else {
                callsignFrom = message.callsignFrom;
            }

            if (message.callsignTo.equals("<...>")) {
                callsignTo = hashList.getCallsign(new long[]{message.callToHash22, message.callToHash12, message.callToHash10});
            } else {
                callsignTo = message.callsignTo;
            }
            if (message.i3 == 4) {
                hashList.addHash(FT8Package.getHash22(message.callsignFrom), message.callsignFrom);
                hashList.addHash(FT8Package.getHash12(message.callsignFrom), message.callsignFrom);
                hashList.addHash(FT8Package.getHash10(message.callsignFrom), message.callsignFrom);
            }

            extraInfo = message.extraInfo;
            maidenGrid = message.maidenGrid;
            report = message.report;
            callToHash10 = message.callToHash10;
            callToHash12 = message.callToHash12;
            callToHash22 = message.callToHash22;
            callFromHash10 = message.callFromHash10;
            callFromHash12 = message.callFromHash12;
            callFromHash22 = message.callFromHash22;


            i3 = message.i3;
            n3 = message.n3;

            //Save the hash-to-callsign mapping to the list
            hashList.addHash(callToHash10, callsignTo);
            hashList.addHash(callToHash12, callsignTo);
            hashList.addHash(callToHash22, callsignTo);
            hashList.addHash(callFromHash10, callsignFrom);
            hashList.addHash(callFromHash12, callsignFrom);
            hashList.addHash(callFromHash22, callsignFrom);

            //Added for RTTY RU (i3=3) messages
            rtty_tu = message.rtty_tu;
            rtty_state = message.rtty_state;
            r_flag =message.r_flag;
            eu_serial =message.eu_serial;
            //Added for Field Day
            arrl_class = message.arrl_class;
            arrl_rac = message.arrl_rac;
            dx_call_to2 = message.dx_call_to2;


            //Log.d(TAG, String.format("i3:%d,n3:%d,From:%s,To:%s", i3, n3, getCallsignFrom(), getCallsignTo()));
        }
    }

    /**
     * Return the frequency used by the decoded message.
     *
     * @return String For convenient display, the return value is a string.
     */
    @SuppressLint("DefaultLocale")
    public String getFreq_hz() {
        return String.format("%04.0f", freq_hz);
    }

    public String getMessageText(boolean showWeekSignal){
        if (isWeakSignal && showWeekSignal){
            return "*"+getMessageText();
        }else {
            return getMessageText();
        }
    }

    /**
     * Return the text content of the decoded message.
     *
     * @return String
     */
    @SuppressLint("DefaultLocale")
    public String getMessageText() {

        if (i3 == 0 && n3 == 0) {//this is free text
            if (extraInfo.length() < 13) {
                return String.format("%-13s", extraInfo.toUpperCase());
            } else {
                return extraInfo.toUpperCase().substring(0, 13);
            }
        }
        if (i3 == 0 && (n3 == 3 || n3 == 4)) {//this is Field Day
            return String.format("%s %s %s%d%s %s"
                    ,callsignTo
                    ,callsignFrom
                    ,r_flag==0?"":"R "
                    ,eu_serial
                    ,arrl_class
                    ,arrl_rac
            );
        }

        if (i3 == 0 && (n3 == 1)) {//this is DXpedition
            // Fox combo: "<acked> RR73; <invited> <foxHash> <report>". The report
            // is already signed, so format its magnitude after the sign char —
            // otherwise a negative report renders a double minus ("--18"). A zero
            // report formats as "+0" (>= 0), matching WSJT-X, not "-0".
            return String.format("%s RR73; %s %s %s%d"
                    ,callsignTo
                    ,dx_call_to2
                    ,hashList.getCallsign(new long[]{callFromHash10})
                    ,report >= 0 ? "+" : "-"
                    ,Math.abs(report)
            );
        }

        if (i3 == 3){//this is an RTTY RU message
            return String.format("%s%s %s %s%d %s"
                    ,rtty_tu==0?"":"TU; "
                    ,callsignTo
                    ,callsignFrom
                    ,r_flag==0?"":"R "
                    ,report
                    ,rtty_state);
        }

        if (i3 == 5){//this is WWROF contest
            return String.format("%s%s %s %s%s%d %s"
                    , rtty_tu == 0 ? "" : "TU; "
                    , callsignTo
                    , callsignFrom
                    , r_flag == 0 ? "" : "R"
                    , report >= 0 ? "+" : ""
                    , report
                    , maidenGrid
                    ).trim();
        }

        if (modifier != null && checkIsCQ()) {//modifier
            if (modifier.matches("[0-9]{3}|[A-Z]{1,4}")) {
                return String.format("%s %s %s %s", callsignTo, modifier, callsignFrom, extraInfo).trim();
            }
        }
        return String.format("%s %s %s", callsignTo, callsignFrom, extraInfo).trim();
    }


    /**
     * Return the time delay of the message. May not be accurate; needs confirmation after the decode algorithm is fully understood.
     *
     * @return String For convenient display, the return value is a string.
     */
    @SuppressLint("DefaultLocale")
    public String getDt() {
        return String.format("%.1f", time_sec);
    }

    /**
     * Return the SNR dB value of the decoded message. The calculation method is not yet finalized; temporarily using 000 as placeholder.
     *
     * @return String For convenient display, the return value is a string.
     */
    public String getdB() {
        return hasSnr() ? String.valueOf(snr) : "--";
    }

    /**
     * Check whether the message is in an odd or even sequence.
     *
     * @return boolean True for even sequence; seconds 0 and 30 are true.
     */
    public boolean isEvenSequence() {
        if (signalFormat == FT8Common.FT8_MODE) {
            return (utcTime / 1000) % 15 == 0;
        } else {
            return (utcTime / 100) % 75 == 0;
        }
    }

    /**
     * The slot (cycle) index since the epoch for this message's mode.
     *
     * <p>FT8 keeps its legacy 15s math and FT4 its 7.5s; FT2 uses its own 3.75s
     * slot from {@link ModeProfile}, <em>not</em> FT4's 7.5s. Reusing FT4's
     * period for FT2 collapsed two adjacent 3.75s slots into a single parity, so
     * the QSO auto-sequencer ({@link com.k1af.ft8af.ft8transmit.FT8TransmitSignal})
     * saw the other station's reply as being in our own slot and skipped it —
     * stalling after a report instead of advancing to RR73 (issue #205). The
     * small {@code +slot/20} nudge mirrors the legacy FT8 (+750ms) / FT4 (+370ms)
     * early-skew correction.
     *
     * @return slot index (monotonic, mod into 2 or 4 for the sequence helpers)
     */
    private long slotIndex() {
        if (signalFormat == FT8Common.FT8_MODE) {
            return (utcTime + 750) / 1000 / 15;
        } else if (signalFormat == FT8Common.FT2_MODE) {
            int slot = ModeProfile.FT2.slotMillis; // 3750ms
            return (utcTime + slot / 20) / slot;
        } else {
            return (utcTime + 370) / 100 / 75;
        }
    }

    /**
     * Show which time sequence the current message belongs to.
     *
     * @return String Result is the modulo of the time cycle.
     */
    @SuppressLint("DefaultLocale")
    public int getSequence() {
        return (int) (slotIndex() % 2);
    }

    @SuppressLint("DefaultLocale")
    public int getSequence4() {
        return (int) (slotIndex() % 4);
    }

    /**
     * Check if the message contains my callsign.
     *
     * @return boolean
     */
    public boolean inMyCall() {
        //Check if it's me; sometimes my callsign has /P or /R suffix, and the other party might drop that suffix
//        if (GeneralVariables.myCallsign.length() == 0) return false;

         return GeneralVariables.checkIsMyCallsign(this.callsignFrom)
                 ||GeneralVariables.checkIsMyCallsign(this.callsignTo);
//        return this.callsignFrom.contains(GeneralVariables.myCallsign)
//                || this.callsignTo.contains(GeneralVariables.myCallsign);
    }
/*
i3.n3 Type   Purpose              Example                                    Bit Field Labels
0.0          Free Text            TNX BOB 73 GL                              f71
0.1          DXpedition           K1ABC RR73; W9XYZ <KH1/KH7Z> -08          c28 c28 h10 r5
0.3          Field Day            K1ABC W9XYZ 6A WI                          c28 c28 R1 n4 k3 S7
0.4          Field Day            W9XYZ K1ABC R 17B EMA                      c28 c28 R1 n4 k3 S7
0.5          Telemetry            123456789ABCDEF012                          t71
1.           Standard Msg         K1ABC/R W9XYZ/R R EN37                     c28 r1 c28 r1 R1 g15
2.           EU VHF               G4ABC/P PA9XYZ JO22                        c28 p1 c28 p1 R1 g15
3.           RTTY RU              K1ABC W9XYZ 579 WI                         t1 c28 c28 R1 r3 s13
4.           NonStd Call          <W9XYZ> PJ4/K1ABC RRR                      h12 c58 h1 r2 c1
5.           EU VHF               <G4ABC> <PA9XYZ> R 570007 JO22DB           h12 h22 R1 r3 s11 g25
*/
/*
Label   Information Conveyed
c1      First callsign is CQ; h12 is ignored
c28     Standard callsign, CQ, DE, QRZ, or 22-bit hash
c58     Non-standard callsign, up to 11 characters
f71     Free text, up to 13 characters
g15     4-char grid, report, RRR, RR73, 73, or blank
g25     6-char grid
h1      Hashed callsign is the second callsign
h10     Hashed callsign, 10 bits
h12     Hashed callsign, 12 bits
h22     Hashed callsign, 22 bits
k3      Field Day class: A, B, ...F
n4      Number of transmitters: 1-16, 17-32
p1      Callsign suffix /P
r1      Callsign suffix /R
r2      RRR, RR73, 73, or blank
r3      Report: 2-9, displayed as 529-599 or 52-59
R1      R
r5      Report: -30 to +30, even numbers only
s11     Serial number (0-2047)
s13     Serial number (0-7999) or state/province
S7      ARRL/RAC section
t1      TU;
t71     Telemetry data, up to 18 hex digits

*/


    /**
     * Get the sender's callsign. The final solution for fromTo needs to be resolved in decode.c ---TO DO----
     * Message types that can provide the sender's callsign: i1, i2, i3, i4, i5, i0.1, i0.3, i0.4
     *
     * @return String Returns the callsign.
     */
    public String getCallsignFrom() {
        if (callsignFrom == null) {
            return "";
        }
        return callsignFrom.replace("<", "").replace(">", "");
    }

    /**
     * Get the receiving callsign from the QSO information.
     *
     * @return
     */
    public String getCallsignTo() {
        if (callsignTo == null) {
            return "";
        }
        // A calling token (CQ / CQ DX / DE / QRZ) is not an addressable "to"
        // callsign. Use startsWith rather than substring(0, n): the previous
        // length<2 guard did not cover the substring(0, 3) "QRZ" comparison, so a
        // callsignTo of exactly length 2 that was neither "CQ" nor "DE" threw
        // StringIndexOutOfBoundsException. The decoder can render a short junk
        // token into callsignTo on a CRC-collision false decode, and this method
        // runs on essentially every decoded message, so that crashed the
        // decode-handling path. startsWith is safe for any length.
        if (callsignTo.startsWith("CQ") || callsignTo.startsWith("DE")
                || callsignTo.startsWith("QRZ")) {
            return "";
        }
        return callsignTo.replace("<", "").replace(">", "");
    }

    /**
     * Get the Maidenhead grid information from the message.
     *
     * @return String, Maidenhead grid; returns "" if not found.
     */
    public String getMaidenheadGrid(DatabaseOpr db) {
        if (i3 != 1 && i3 != 2) {//Generally only i3=1 or i3=2 (standard messages, VHF messages) contain grid
            return GeneralVariables.getGridByCallsign(callsignFrom, db);//look up grid in the mapping table
        } else {
            String[] msg = getMessageText().split(" ");
            if (msg.length < 1) {
                return GeneralVariables.getGridByCallsign(callsignFrom, db);//look up grid in the mapping table
            }
            String s = msg[msg.length - 1];
            if (MaidenheadGrid.checkMaidenhead(s)) {
                return s;
            } else {//not grid info, likely a signal report
                return GeneralVariables.getGridByCallsign(callsignFrom, db);//look up grid in the mapping table
            }
        }
    }

    public String getToMaidenheadGrid(DatabaseOpr db) {
        if (checkIsCQ()) return "";
        return GeneralVariables.getGridByCallsign(callsignTo, db);
    }

    /**
     * Check if the message is CQ.
     *
     * @return boolean Returns true if CQ.
     */
    public boolean checkIsCQ() {
        // callsignTo defaults to null and stays null for free-text/telemetry
        // frames and unresolved-hash decodes that still reach the decode list.
        // The Compose decode screen calls this unconditionally on the main
        // thread (DecodeRow / resolveQsoStatus / filterMessages), so a missing
        // destination must be treated as "not a CQ" rather than NPE on trim().
        // (The previous `s == null` check was dead: String.split()[0] is never
        // null; the deref that actually throws is callsignTo above.)
        if (callsignTo == null) {
            return false;
        }
        String s = callsignTo.trim().split(" ")[0];
        return (s.equals("CQ") || s.equals("DE") || s.equals("QRZ"));
    }

    /**
     * Decide whether a callsign field could be a real callsign.
     *
     * <p>A real callsign field uses only the {@code [A-Z0-9/]} alphabet,
     * optionally wrapped in {@code <...>} (a hashed / non-standard call), or is
     * the bare {@code <...>} placeholder shown before a hash resolves. Anything
     * else — stray angle brackets, embedded spaces, dots — is the junk a
     * CRC-collision false decode renders into the sender field (e.g. {@code .<. >}).
     *
     * @param callsign the raw sender field (may be null, may be {@code <...>}-wrapped)
     * @return true if it could be a real callsign or the unresolved-hash placeholder
     */
    public static boolean isPlausibleCallsign(String callsign) {
        if (callsign == null) {
            return false;
        }
        String s = callsign.trim();
        if (s.isEmpty()) {
            return false;
        }
        if (s.equals("<...>")) {//unresolved hashed-call placeholder
            return true;
        }
        //Strip a single matching <...> wrapper around a hashed / non-standard call.
        if (s.startsWith("<") && s.endsWith(">") && s.length() > 2) {
            s = s.substring(1, s.length() - 1);
        }
        //No leftover angle brackets, spaces, or dots — just the call alphabet.
        //Manual scan keeps this off the regex compiler on the decode hot-path.
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean inAlphabet = (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '/';
            if (!inAlphabet) {
                return false;
            }
        }
        return true;
    }

    /**
     * Detect a junk/false decode by its sender callsign.
     *
     * <p>FT8 guards each 77-bit frame with only a 14-bit CRC, so roughly one
     * noise event in 16k slips through as a "valid" decode. When that garbage
     * lands in a callsign-bearing frame, the corrupt sender field renders as
     * junk like {@code .<. >}. A structured decode is junk when its sender isn't
     * a {@link #isPlausibleCallsign plausible callsign}. Free text
     * ({@code i3=0,n3=0}) and telemetry ({@code i3=0,n3=5}) legitimately carry
     * non-callsign text in that field, so they are never treated as junk here.
     *
     * @return true if this decode should be dropped as garbage
     */
    public boolean isJunkDecode() {
        boolean freeText = (i3 == 0 && n3 == 0);
        boolean telemetry = (i3 == 0 && n3 == 5);
        if (freeText || telemetry) {
            return false;
        }
        return !isPlausibleCallsign(callsignFrom);
    }

    /**
     * Get the message type (i3.n3).
     *
     * @return Message type
     */

    public String getCommandInfo() {
        return getCommandInfoByI3N3(i3, n3);
    }

    /**
     * Get the message type (i3.n3).
     *
     * @param i i3
     * @param n n3
     * @return Message type
     */
    @SuppressLint("DefaultLocale")
    public static String getCommandInfoByI3N3(int i, int n) {
        String format = "%d.%d:%s";
        switch (i) {
            case 1:
            case 2:
                return String.format(format, i, 0, GeneralVariables.getStringFromResource(R.string.std_msg));
            case 5:
                return String.format(format, i, 0, "EU VHF");
            case 3:
                return String.format(format, i, 0, GeneralVariables.getStringFromResource(R.string.rtty_ru_msg));
            case 4:
                return String.format(format, i, 0, GeneralVariables.getStringFromResource(R.string.none_std_msg));
            case 0:
                switch (n) {
                    case 0:
                        return String.format(format, i, n, GeneralVariables.getStringFromResource(R.string.free_text));
                    case 1:
                        return String.format(format, i, n, GeneralVariables.getStringFromResource(R.string.dXpedition));
                    case 3:
                    case 4:
                        return String.format(format, i, n, GeneralVariables.getStringFromResource(R.string.field_day));
                    case 5:
                        return String.format(format, i, n, GeneralVariables.getStringFromResource(R.string.telemetry));
                }
        }
        return "";
    }

    //Get the sender's transmit callsign object
    public TransmitCallsign getFromCallTransmitCallsign() {
        return new TransmitCallsign(this.i3, this.n3, this.callsignFrom, freq_hz
                , this.getSequence()
                , hasSnr() ? snr : 0);
    }

    //Get the receiver's transmit callsign object. NOTE: the sequence is opposite to the sender's!!!
    public TransmitCallsign getToCallTransmitCallsign() {
        if (report == -100) {//if no signal report in the message, use the sender's SNR instead
            return new TransmitCallsign(this.i3, this.n3, this.callsignTo, freq_hz, (this.getSequence() + 1) % 2, hasSnr() ? snr : 0);
        } else {
            return new TransmitCallsign(this.i3, this.n3, this.callsignTo, freq_hz, (this.getSequence() + 1) % 2, report);
        }
    }

    @SuppressLint("DefaultLocale")
    public String toHtml() {
        StringBuilder result = new StringBuilder();

        result.append("<td class=\"default\" >");
        result.append(UtcTimer.getDatetimeStr(utcTime));
        result.append("</td>\n");

        result.append("<td class=\"default\" >");
        result.append(getdB());
        result.append("</td>\n");

        result.append("<td class=\"default\" >");
        result.append(String.format("%.1f", time_sec));
        result.append("</td>\n");

        result.append("<td class=\"default\" >");
        result.append(String.format("%.0f", freq_hz));
        result.append("</td>\n");

        result.append("<td class=\"default\" >");
        result.append(getMessageText());
        result.append("</td>\n");

        result.append("<td class=\"default\" >");
        result.append(BaseRigOperation.getFrequencyStr(band));
        result.append("</td>\n");

        return result.toString();
    }
}
