package com.k1af.ft8af.ft8signal;
/**
 * Pack symbols according to the FT8 protocol.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.ft8transmit.GenerateFT8;

public class FT8Package {
    private static final String TAG = "FT8Package";
    public static final int NTOKENS = 2063592;
    public static final int MAX22 = 4194304;
    public static final int MAXGRID4 = 32400;


    private static final String A1 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String A2 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String A3 = "0123456789";
    private static final String A4 = " ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String A5 = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/";

    static {
        try {
            System.loadLibrary("ft8af");
        } catch (UnsatisfiedLinkError e) {
            // Best-effort load: JVM unit tests don't have libft8af.so on
            // java.library.path. The native methods themselves will throw if
            // actually invoked without the library; the pure-Java helpers on
            // this class (e.g. getStdCall) stay available either way. Mirrors
            // the same guard in GenerateFT8.
            Log.w(TAG, "ft8af native library not loaded: " + e.getMessage());
        }
    }


    /**
     * Generate a 77-bit data packet for non-standard messages with i3=4.
     * @param message the message
     * @return data packet
     */
    public static byte[] generatePack77_i4(Ft8Message message) {

        String toCall = message.callsignTo.replace("<", "").replace(">", "");
        String fromCall = message.callsignFrom.replace("<", "").replace(">", "");
        int hash12;
        if (message.checkIsCQ()) {// if CQ, add our own callsign's hash
            hash12 = getHash12(fromCall);
        } else {
            hash12 = getHash12(toCall);
        }
        if (fromCall.length() > 11) {// non-standard callsign length must not exceed 11 characters
            fromCall = fromCall.substring(0, 11);
        }

        byte[] data = new byte[10];
        long n58 = 0;
        for (int i = 0; i < fromCall.length(); i++) {
            n58 = n58 * 38 + A5.indexOf(fromCall.charAt(i));
        }
        //n58=3479529522318088L;

        data[0] = (byte) ((hash12 & 0x00000fff) >> 4);
        data[1] = (byte) ((hash12 & 0x0000000f) << 4);
        data[1] = (byte) (data[1] | ((n58 & 0x0fff_ffff_ffff_ffffL) >> 54));
        data[2] = (byte) (((n58 & 0x00ff_ffff_ffff_ffffL) >> 54 - 8));
        data[3] = (byte) (((n58 & 0x0000_ffff_ffff_ffffL) >> 54 - 8 - 8));
        data[4] = (byte) (((n58 & 0x0000_00ff_ffff_ffffL) >> 54 - 8 - 8 - 8));
        data[5] = (byte) (((n58 & 0x0000_0000_ffff_ffffL) >> 54 - 8 - 8 - 8 - 8));
        data[6] = (byte) (((n58 & 0x0000_0000_00ff_ffffL) >> 54 - 8 - 8 - 8 - 8 - 8));
        data[7] = (byte) (((n58 & 0x0000_0000_0000_ffffL) >> 54 - 48));
        data[8] = (byte) (((n58 & 0x0000_0000_0000_00ffL) << 2));
        //RRR=1,RR73=2,73=3,""=0
        if (message.checkIsCQ()) {
            data[9] = (byte) 0x60;
        } else {
            data[9] = (byte) 0x20;
            switch (message.extraInfo) {
                case "RRR": //r2=1
                    data[8] = (byte) (data[8] & 0xfe);
                    data[9] = (byte) (data[9] | 0x80);
                    break;
                case "RR73": //r2=2
                    data[8] = (byte) (data[8] | 0x01);
                    //data[9] = (byte) (data[9] | 0x00);//data[9] does not need to change
                    break;
                case "73": //r2=3
                    data[8] = (byte) (data[8] | 0x01);
                    data[9] = (byte) (data[9] | 0x80);
                    break;
            }
        }

        return data;
    }

    /**
     * Extract the standard callsign from a compound callsign (a callsign containing "/").
     * Use case: when both parties have compound callsigns, the sender (our side) must use
     * the standard callsign. The extraction logic splits on "/", matches against the FT8
     * standard callsign regex, and falls back to the longest part if no match is found.
     * @param compoundCallsign compound callsign
     * @return standard callsign
     */
    public static String getStdCall(String compoundCallsign) {
        if (!compoundCallsign.contains("/")) return compoundCallsign;
        String[] callsigns = compoundCallsign.split("/");
        for (String callsign : callsigns) {// extract standard callsign using regex
            // FT8 definition: a standard amateur callsign consists of a one or two character prefix (at least one must be a letter), followed by a decimal digit and up to three letter suffix.
            if (callsign.matches("[A-Z0-9]?[A-Z0-9][0-9][A-Z][A-Z0-9]?[A-Z]?")) {
                return callsign;
            }
        }
        // when unable to extract a standard callsign, use the longest segment
        int len = 0;
        int index = 0;
        for (int i = 0; i < callsigns.length; i++) {
            if (callsigns[i].length() > len) {
                len = callsigns[i].length();
                index = i;
            }
        }
        return callsigns[index];
    }

    /**
     * i1=1 and i1=2 are defined in the FT8 protocol as standard messages and EU VHF messages
     * respectively. The only difference is: i1=1 messages can carry /R, i1=2 messages can carry /P.
     * Therefore, these two message types can be merged into one.
     *
     * @param message original message
     * @return packet77
     */
    public static byte[] generatePack77_i1(Ft8Message message) {
        String toCall = message.callsignTo.replace("<", "").replace(">", "");
        String fromCall = message.callsignFrom.replace("<", "").replace(">", "");

        if (message.checkIsCQ() && message.modifier != null) {// add the modifier
            if (message.modifier.length() > 0) {
                toCall = toCall + " " + message.modifier;
            }
        }

        // if the callsign ends with /P or /R, strip the /P or /R
        if (toCall.endsWith("/P") || toCall.endsWith("/R")) {
            toCall = toCall.substring(0, toCall.length() - 2);
        }

        if (fromCall.endsWith("/P") || fromCall.endsWith("/R")) {
            fromCall = fromCall.substring(0, fromCall.length() - 2);
//            fromCall = message.callsignFrom.substring(0, message.callsignFrom.length() - 2);
        }

        // when both parties have compound or non-standard callsigns (containing /), convert our callsign to standard
        if ((toCall.contains("/")) && fromCall.contains("/")) {
            fromCall = getStdCall(fromCall);// extract standard callsign from compound callsign
            // fromCall = fromCall.substring(0, fromCall.indexOf("/"));
        }
        byte r1_p1=pack_r1_p1(message.callsignTo);

        byte r2_p2;
        // if both parties have suffixes but of different types, cancel the r1 or p1 flag; the sender's suffix takes precedence
        if ((message.callsignFrom.endsWith("/R")&&message.callsignTo.endsWith("/P"))
            ||(message.callsignFrom.endsWith("/P")&&message.callsignTo.endsWith("/R"))){
            r2_p2=0;
        }else {
            r2_p2 = pack_r1_p1(message.getCallsignFrom());
        }


        byte[] data = new byte[12];
        data[0] = (byte) ((pack_c28(toCall) & 0x0fffffff) >> 20);
        data[1] = (byte) ((pack_c28(toCall) & 0x00ffffff) >> 12);
        data[2] = (byte) ((pack_c28(toCall) & 0x0000ffff) >> 4);
        data[3] = (byte) ((pack_c28(toCall) & 0x0000000f) << 4);
        //data[3] = (byte) (data[3] | (pack_r1_p1(message.callsignTo) << 3));
        data[3] = (byte) (data[3] | (r1_p1 << 3));
        data[3] = (byte) (data[3] | (pack_c28(fromCall) & 0x00fffffff) >> 25);


        data[4] = (byte) ((pack_c28(fromCall) & 0x003ffffff) >> 25 - 8);
        data[5] = (byte) ((pack_c28(fromCall) & 0x00003ffff) >> 25 - 8 - 8);
        data[6] = (byte) ((pack_c28(fromCall) & 0x0000003ff) >> 25 - 8 - 8 - 8);
        data[7] = (byte) ((pack_c28(fromCall) & 0x0000000ff) << 7);


        data[7] = (byte) (data[7] | (r2_p2) << 6);
        //data[7] = (byte) (data[7] | (pack_r1_p1(message.getCallsignFrom())) << 6);
        data[7] = (byte) (data[7] | (pack_R1_g15(message.extraInfo) & 0x0ffff) >> 10);
        data[8] = (byte) ((pack_R1_g15(message.extraInfo) & 0x0003fff) >> 2);
        data[9] = (byte) ((pack_R1_g15(message.extraInfo) & 0x00000ff) << 6);
        data[9] = (byte) (data[9] | (message.i3 & 0x3) << 3);
        return data;
    }

    /**
     * Generate R1+g15 data (grid or signal report). Actually 16 bits including R1.
     * e.g., R-17: R1=1, -17: R1=0
     *
     * @param grid4 grid or signal report
     * @return R1+g15 data
     */
    public static int pack_R1_g15(String grid4) {
        if (grid4 == null)// only two callsigns, no signal report or grid
        {
            return MAXGRID4 + 1;
        }
        if (grid4.length() == 0) {// only two callsigns, no signal report or grid
            return MAXGRID4 + 1;
        }

        // special reports: RRR, RR73, 73
        if (grid4.equals("RRR"))
            return MAXGRID4 + 2;
        if (grid4.equals("RR73"))
            return MAXGRID4 + 3;
        if (grid4.equals("73"))
            return MAXGRID4 + 4;


        // check if it is a standard 4-character grid
        if (grid4.matches("[A-Z][A-Z][0-9][0-9]")) {
            int igrid4 = grid4.charAt(0) - 'A';
            igrid4 = igrid4 * 18 + (grid4.charAt(1) - 'A');
            igrid4 = igrid4 * 10 + (grid4.charAt(2) - '0');
            igrid4 = igrid4 * 10 + (grid4.charAt(3) - '0');
            return igrid4;
        }


        // check if it is a signal report: +dd / -dd / R+dd / R-dd
        // signal report range: -30 to 99 dB
        // signal report regex: [R]?[+-][0-9]{1,2}
        try {
            String s = grid4;
            if (grid4.charAt(0) == 'R') {
                s = grid4.substring(1);
                int irpt = 35 + Integer.parseInt(s);
                return (MAXGRID4 + irpt) | 0x8000; // R1 = 1
            } else {
                int irpt = 35 + Integer.parseInt(grid4);
                return (MAXGRID4 + irpt); // R1 = 0
            }
        } catch (NumberFormatException e) {
            return MAXGRID4 + 1;
        }

    }

    public static byte pack_r1_p1(String callsign) {
        if (callsign == null || callsign.length() < 2) {
            return 0;
        }
        String s = callsign.substring(callsign.length() - 2);
        if (s.equals("/R") || s.equals("/P")) {
            return 1;
        } else {
            return 0;
        }
    }

    /**
     * Generate c28 data from a callsign. For standard callsigns, without /R or /P.
     * If the callsign is non-standard, use hash22+2063592.
     *
     * @param callsign callsign
     * @return c28 data
     */
    public static int pack_c28(String callsign) {
        //byte[] data=new byte[]{(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00};
        switch (callsign) {
            case "DE":
                return 0;
            case "QRZ":
                return 1;
            case "CQ":
                return 2;
        }

        // check for modifier: 000-999, A-Z, AA-ZZ, AAA-ZZZ, AAAA-ZZZZ
        if (callsign.startsWith("CQ ") && callsign.length() > 3) {
            String temp = callsign.substring(3).trim().toUpperCase();
            if (temp.matches("[0-9]{3}")) {
                int i = Integer.parseInt(temp);
                return i + 3;
            }
            if (temp.matches("[A-Z]{1,4}")) {

                int a0 = 0;
                int a1 = 0;
                int a2 = 0;
                int a3 = 0;
                if (temp.length() == 1) {//A-Z
                    a0 = (int) temp.charAt(0) - 65;
                    return a0 + 1004;
                }
                if (temp.length() == 2) {//AA-ZZ
                    a0 = (int) temp.charAt(0) - 65;
                    a1 = (int) temp.charAt(1) - 65;
                    return a0 * 27 + a1 + 1031;
                }
                if (temp.length() == 3) {//AAA-ZZZ
                    a0 = (int) temp.charAt(0) - 65;
                    a1 = (int) temp.charAt(1) - 65;
                    a2 = (int) temp.charAt(2) - 65;
                    return a0 * 27 * 27 + a1 * 27 + a2 + 1760;
                }
                if (temp.length() == 4) {//AAAA-ZZZZ
                    a0 = (int) temp.charAt(0) - 65;
                    a1 = (int) temp.charAt(1) - 65;
                    a2 = (int) temp.charAt(2) - 65;
                    a3 = (int) temp.charAt(3) - 65;
                    return a0 * 27 * 27 * 27 + a1 * 27 * 27 + a2 * 27 + a3 + 21443;
                }
            }
        }


        // format into a standard callsign: 6 characters, 3rd character is a digit
        // c6 can also be a non-standard callsign; anything longer than 6 characters is non-standard
        String c6 = formatCallsign(callsign);
        // check if it is a standard callsign
        if (!GenerateFT8.checkIsStandardCallsign(callsign)) {// generate HASH22+2063592
            return NTOKENS + getHash22(callsign);
        }

        // extract values from the 6-character callsign
        int i0, i1, i2, i3, i4, i5;
        i0 = A1.indexOf(c6.substring(0, 1));
        i1 = A2.indexOf(c6.substring(1, 2));
        i2 = A3.indexOf(c6.substring(2, 3));
        i3 = A4.indexOf(c6.substring(3, 4));
        i4 = A4.indexOf(c6.substring(4, 5));
        i5 = A4.indexOf(c6.substring(5, 6));

        int n28 = i0;
        n28 = n28 * 36 + i1;
        n28 = n28 * 10 + i2;
        n28 = n28 * 27 + i3;
        n28 = n28 * 27 + i4;
        n28 = n28 * 27 + i5;


        return NTOKENS + MAX22 + n28;

    }


    /**
     * Normalize a callsign into the 6-character {@code c6} field used by
     * {@link #pack_c28}.
     * <p>
     * {@code pack_c28} calls this on the raw callsign <em>before</em> deciding
     * whether the callsign is standard, so the input is not guaranteed to be a
     * well-formed standard callsign — short/junk tokens (e.g. a CRC-collision
     * false decode) reach here too and must pass through without crashing.
     * Formatting rules (applied only when the input matches):
     * 1. Swaziland callsign prefix issue: 3DA0XYZ -> 3D0XYZ
     * 2. Guinea callsign prefix issue: 3XA0XYZ -> QA0XYZ
     * 3. Callsigns with a digit in position 2 are left-padded with a space: A0XYZ -> " A0XYZ"
     * 4. Results shorter than 6 characters are right-padded with spaces: BA2BI -> "BA2BI "
     *
     * @param callsign callsign (may be a short/non-standard token)
     * @return the space-padded 6-character {@code c6} string
     */
    // Package-private (not private) so FT8PackagePackingTest can exercise the
    // short-callsign guard below directly: pack_c28 routes a non-standard
    // callsign into the native getHash22 path, which a plain-JVM unit test
    // cannot load, so the crash-safety of this formatter has to be tested here.
    static String formatCallsign(String callsign) {
        String c6 = callsign;
        // fix Swaziland callsign prefix issue: 3DA0XYZ -> 3D0XYZ
        if (callsign.length() > 3 && callsign.substring(0, 4).equals("3DA0") && callsign.length() <= 7) {
            c6 = "3D0" + callsign.substring(4);
            // fix Guinea callsign prefix issue: 3XA0XYZ -> QA0XYZ
        } else if (callsign.length() > 3 && callsign.substring(0, 3).matches("3X[A-Z]") && callsign.length() <= 7) {
            c6 = "Q" + callsign.substring(2);
        } else {
            // if position 2 is a digit and position 3 is a letter, left-pad with a space: A0XYZ -> " A0XYZ" (except A6 prefix)
            // The length>=3 guard is required: pack_c28 calls formatCallsign on
            // the raw callsign BEFORE the standard-callsign check, and a decoder
            // CRC-collision false decode can render a 1-2 char junk token into a
            // callsign field (the same garbage getCallsignTo() already guards
            // against). Without the guard, substring(0, 3) threw
            // StringIndexOutOfBoundsException on the TX-encode path (e.g. calling
            // a station whose decoded callsign came back as "W1").
            if (callsign.length() >= 3 && callsign.substring(0, 3).matches("[A-Z][0-9][A-Z]")) {
                c6 = " " + callsign;
            }
        }

        while (c6.length() < 6) {// if length is less than 6, right-pad with spaces
            c6 = c6 + " ";
        }

        return c6;
    }

    /**
     * 86-entry ARRL/RAC section table for Field Day messages,
     * matching the exact order used by WSJT-X (packjt77.f90 csec array).
     */
    public static final String[] ARRL_SECTIONS = {
        "AB","AK","AL","AR","AZ","BC","CO","CT","DE","EB",
        "EMA","ENY","EPA","EWA","GA","GH","IA","ID","IL","IN",
        "KS","KY","LA","LAX","NS","MB","MDC","ME","MI","MN",
        "MO","MS","MT","NC","ND","NE","NFL","NH","NL","NLI",
        "NM","NNJ","NNY","TER","NTX","NV","OH","OK","ONE","ONN",
        "ONS","OR","ORG","PAC","PR","QC","RI","SB","SC","SCV",
        "SD","SDG","SF","SFL","SJV","SK","SNJ","STX","SV","TN",
        "UT","VA","VI","VT","WCF","WI","WMA","WNY","WPA","WTX",
        "WV","WWA","WY","DX","PE","NB"
    };

    /**
     * Look up the 0-based index of an ARRL/RAC section code.
     * @return 0-85 on match, -1 if unknown
     */
    public static int sectionIndex(String section) {
        for (int i = 0; i < ARRL_SECTIONS.length; i++) {
            if (ARRL_SECTIONS[i].equals(section)) return i;
        }
        return -1;
    }

    /**
     * Generate a 77-bit data packet for Field Day messages (i3=0, n3=3 or n3=4).
     * Bit layout: c28 c28 R1 n4 k3 S7 n3 i3 = 77 bits packed into 10 bytes.
     *
     * Unlike i1 messages, the two c28 fields are directly adjacent (no r1/p1
     * suffix bit between them). R1 sits at bit 56, followed by the FD-specific
     * payload fields.
     *
     * @param msg the Ft8Message with FD fields populated:
     *            callsignTo, callsignFrom, r_flag (0 or 1),
     *            eu_serial (transmitter count 1-16), arrl_class ("A"-"F"),
     *            arrl_rac (section code), n3 (3 or 4)
     * @return 10-byte packed payload (77 bits, padded to byte boundary)
     */
    public static byte[] generatePack77_fd(Ft8Message msg) {
        String toCall = msg.callsignTo.replace("<", "").replace(">", "");
        String fromCall = msg.callsignFrom.replace("<", "").replace(">", "");

        if (msg.checkIsCQ() && msg.modifier != null && msg.modifier.length() > 0) {
            toCall = toCall + " " + msg.modifier;
        }

        long n28a = pack_c28(toCall) & 0x0fffffffL;
        long n28b = pack_c28(fromCall) & 0x0fffffffL;
        int R1 = msg.r_flag & 1;
        int n4 = ((msg.eu_serial - 1) & 0x0F);  // 1-16 → 0-15 (clamp to 4 bits)
        int k3 = 0;
        if (msg.arrl_class != null && msg.arrl_class.length() > 0) {
            k3 = (msg.arrl_class.charAt(0) - 'A') & 0x07;  // A=0..F=5
        }
        int S7 = 0;
        if (msg.arrl_rac != null) {
            int idx = sectionIndex(msg.arrl_rac);
            S7 = (idx >= 0 ? idx : 0) & 0x7F;
        }
        int n3 = msg.n3 & 0x07;  // 3 or 4
        int i3 = 0;              // Field Day is always i3=0

        // Pack 77 bits into 10 bytes:
        // bits  0-27:  n28a (c28 for callsignTo)
        // bits 28-55:  n28b (c28 for callsignFrom)
        // bit  56:     R1
        // bits 57-60:  n4   (4 bits)
        // bits 61-63:  k3   (3 bits)
        // bits 64-70:  S7   (7 bits)
        // bits 71-73:  n3   (3 bits)
        // bits 74-76:  i3   (3 bits)
        // bits 77-79:  padding (zeros)

        // Assemble as a single 80-bit value using longs for the upper and lower halves.
        // Upper 48 bits = n28a(28) + top 20 of n28b(28)
        // Lower 32 bits = bottom 8 of n28b + R1(1) + n4(4) + k3(3) + S7(7) + n3(3) + i3(3) + pad(3)

        byte[] data = new byte[10];

        // Byte 0-3: n28a (28 bits) + top 4 bits of n28b
        data[0] = (byte) ((n28a >> 20) & 0xFF);
        data[1] = (byte) ((n28a >> 12) & 0xFF);
        data[2] = (byte) ((n28a >> 4) & 0xFF);
        data[3] = (byte) (((n28a & 0x0F) << 4) | ((n28b >> 24) & 0x0F));

        // Byte 4-6: middle 24 bits of n28b
        data[4] = (byte) ((n28b >> 16) & 0xFF);
        data[5] = (byte) ((n28b >> 8) & 0xFF);
        data[6] = (byte) (n28b & 0xFF);

        // Byte 7: R1(1) + n4(4) + k3(3) = 8 bits
        data[7] = (byte) ((R1 << 7) | (n4 << 3) | k3);

        // Byte 8: S7(7) + n3 top bit(1) = 8 bits
        data[8] = (byte) ((S7 << 1) | ((n3 >> 2) & 0x01));

        // Byte 9: n3 bottom 2 bits(2) + i3(3) + padding(3) = 8 bits
        data[9] = (byte) (((n3 & 0x03) << 6) | (i3 << 3));

        return data;
    }

    public static native int getHash12(String callsign);


    public static native int getHash10(String callsign);

    public static native int getHash22(String callsign);
}
