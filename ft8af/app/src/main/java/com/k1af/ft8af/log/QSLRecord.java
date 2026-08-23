package com.k1af.ft8af.log;

import android.util.Log;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.maidenhead.MaidenheadGrid;
import com.k1af.ft8af.rigs.BaseRigOperation;
import com.k1af.ft8af.timer.UtcTimer;

import java.util.HashMap;
import java.util.Objects;

/**
 * Class for recording successful QSO information. A successful QSO means FT8 completed the 6-message exchange, not mutual confirmation.
 * isLotW_import indicates whether the data was imported externally, since users may have made QSOs using software like JTDX and can import those results into FT8CN.
 * isLotW_QSL indicates whether the QSO was confirmed by a platform (e.g., LoTW).
 * isQSL indicates whether the QSO was manually confirmed.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */
public class QSLRecord {
    private static final String TAG = "QSLRecord";
    public long id = -1;
    //private long startTime;//Start time
    private String qso_date;
    private String time_on;
    private String qso_date_off;
    private String time_off;
    //private long endTime;//End time

    private final String myCallsign;//My callsign
    private String myMaidenGrid;//My grid
    private String toCallsign;//Other party's callsign
    private String toMaidenGrid;//Other party's grid
    private int sendReport;//Report received by other party (i.e., signal strength I sent)
    private int receivedReport;//Report I received from other party (i.e., SNR)
    private String mode = "FT8";
    private String bandLength = "";
    private long bandFreq;//Transmit band frequency
    private int wavFrequency;//Transmit audio frequency
    private String comment;
    // POTA ADIF fields. mySig/mySigInfo describe my own activation (e.g. "POTA"/"K-1234");
    // sig/sigInfo describe the worked station's activation if it was a hunt or P2P.
    private String mySig;
    private String mySigInfo;
    private String sig;
    private String sigInfo;
    public boolean isQSL = false;//Manual confirmation
    public boolean isLotW_import = false;//Whether imported from external data; requires database comparison to determine
    public boolean isLotW_QSL = false;//Whether confirmed via LoTW

    public boolean saved = false;//Whether saved to the database

    public boolean isInvalid=false;//Whether parsing encountered an error
    public String errorMSG="";//Error message if parsing failed

    /**
     * Used for SWL QSO records. The condition for recording an SWL QSO is hearing signal reports from both parties.
     *
     * @param msg FT8 message
     */
    public QSLRecord(Ft8Message msg) {
        this.qso_date_off = UtcTimer.getYYYYMMDD(msg.utcTime);
        this.time_off = UtcTimer.getTimeHHMMSS(msg.utcTime);
        this.myCallsign = msg.callsignFrom;
        this.toCallsign = msg.callsignTo;
        wavFrequency = Math.round(msg.freq_hz);
        sendReport = -100;
        receivedReport = -100;
        bandLength = BaseRigOperation.getMeterFromFreq(GeneralVariables.band);//Get wavelength
        bandFreq = GeneralVariables.band;
        comment = "SWL By FT8AF";
    }

    /**
     * Construct a successful QSO object
     *
     * @param startTime      start time
     * @param endTime        end time
     * @param myCallsign     my callsign
     * @param myMaidenGrid   my grid
     * @param toCallsign     other party's callsign
     * @param toMaidenGrid   other party's grid
     * @param sendReport     sent report
     * @param receivedReport received report
     * @param mode           mode, default FT8
     * @param bandFreq       carrier frequency
     * @param wavFrequency   audio frequency
     */
    public QSLRecord(long startTime, long endTime, String myCallsign, String myMaidenGrid
            , String toCallsign, String toMaidenGrid, int sendReport, int receivedReport
            , String mode, long bandFreq, int wavFrequency) {
        //this.startTime = startTime;
        this.qso_date = UtcTimer.getYYYYMMDD(startTime);
        this.time_on = UtcTimer.getTimeHHMMSS(startTime);
        this.qso_date_off = UtcTimer.getYYYYMMDD(endTime);
        this.time_off = UtcTimer.getTimeHHMMSS(endTime);
        this.myCallsign = myCallsign;
        this.myMaidenGrid = myMaidenGrid;
        this.toCallsign = toCallsign;
        this.toMaidenGrid = toMaidenGrid;
        this.sendReport = sendReport;
        this.receivedReport = receivedReport;
        this.mode = mode;
        this.bandLength = BaseRigOperation.getMeterFromFreq(bandFreq);//Get wavelength
        this.bandFreq = bandFreq;
        this.wavFrequency = wavFrequency;
        String distance = "";
        if (!myMaidenGrid.equals("") && !toMaidenGrid.equals("")) {
            distance = MaidenheadGrid.getDistStrEN(myMaidenGrid, toMaidenGrid);
        }
        this.comment =
                distance.equals("") ? "QSO by FT8AF"
                        : String.format("Distance: %s, QSO by FT8AF", distance);
    }

    public void update(QSLRecord record) {
        this.qso_date_off = record.qso_date_off;
        this.time_off = record.time_off;
        this.toMaidenGrid = record.toMaidenGrid;
        this.sendReport = record.sendReport;
        this.receivedReport = record.receivedReport;
    }

    public QSLRecord(HashMap<String, String> map) {
        isLotW_import = true;//Indicates externally imported data
        if (map.containsKey("CALL")) {//Other party's callsign
            toCallsign = map.get("CALL");
        }
        if (map.containsKey("STATION_CALLSIGN")) {//My callsign
            myCallsign = map.get("STATION_CALLSIGN");
        } else {
            myCallsign = "";
        }
        if (map.containsKey("BAND")) {//Carrier wavelength
            bandLength = map.get("BAND");
        } else {
            bandLength = "";
        }

        if (map.containsKey("FREQ")) {//Carrier frequency
            try {//Convert MHz to Hz. Parse as double: a 32-bit float only holds
                //~7 significant digits, so UHF/microwave dials (e.g. 432.174 or
                //1296.174 MHz) would drift by tens of Hz once scaled to Hz.
                double freq = Double.parseDouble(Objects.requireNonNull(map.get("FREQ")));
                bandFreq = Math.round(freq * 1000000);
            } catch (NumberFormatException e) {
                isInvalid=true;
                errorMSG="freq:"+e.getMessage();
                e.printStackTrace();
                Log.e(TAG, "QSLRecord: freq" + e.getMessage());
            }
        }
        if (map.containsKey("MODE")) {//Mode
            // FT4/FT2 are ADIF SUBMODEs of the generic MFSK MODE (see AdifFormat.mfskSubmode),
            // so they arrive as MODE=MFSK + SUBMODE=FT4. Resolve that back to the specific
            // mode; reading MODE alone would store "MFSK" and lose the FT4/FT2 distinction.
            mode = AdifFormat.resolveImportMode(map.get("MODE"), map.get("SUBMODE"));
        } else {
            mode = "";
        }
        if (map.containsKey("QSO_DATE")) {//QSO date
            qso_date = map.get("QSO_DATE");
        } else {
            qso_date = "";
        }
        if (map.containsKey("TIME_ON")) {//QSO start time
            time_on = map.get("TIME_ON");
        } else {
            time_on = "";
        }
        if (map.containsKey("QSO_DATE_OFF")) {//QSO end date; this field only exists in JTDX.
            qso_date_off = map.get("QSO_DATE_OFF");
        } else {
            qso_date_off = qso_date;
        }
        if (map.containsKey("TIME_OFF")) {//QSO end time; present in N1MM, Log32, JTDX, but not in LoTW
            time_off = map.get("TIME_OFF");
        } else {
            time_off = "";
        }
        if (map.containsKey("QSL_RCVD")) {//QSO confirmation; present in LoTW.
            isLotW_QSL = Objects.requireNonNull(map.get("QSL_RCVD")).equalsIgnoreCase("Y");
        }
        if (map.containsKey("LOTW_QSL_RCVD")) {//QSO confirmation; present in Log32.
            isLotW_QSL = Objects.requireNonNull(map.get("LOTW_QSL_RCVD")).equalsIgnoreCase("Y");
        }
        // Manual QSO confirmation. We now export this as APP_FT8AF_QSL_MANUAL (ADIF has
        // no QSL_MANUAL field), but files written by older FT8AF builds — and by other
        // loggers that copied the bare name — must keep importing, so accept both. The
        // conformant name wins when a file somehow carries both.
        if (map.containsKey(AdifRecord.LEGACY_QSL_MANUAL)) {
            isQSL = Objects.requireNonNull(
                    map.get(AdifRecord.LEGACY_QSL_MANUAL)).equalsIgnoreCase("Y");
        }
        if (map.containsKey(AdifRecord.APP_QSL_MANUAL)) {
            isQSL = Objects.requireNonNull(
                    map.get(AdifRecord.APP_QSL_MANUAL)).equalsIgnoreCase("Y");
        }

        if (map.containsKey("MY_GRIDSQUARE")) {//My grid (present in LoTW/Log32, may be absent depending on LoTW settings); N1MM has no grid
            myMaidenGrid = map.get("MY_GRIDSQUARE");
        } else {
            myMaidenGrid = "";
        }

        if (map.containsKey("GRIDSQUARE")) {//Other party's grid (present in LoTW/Log32, may be absent depending on LoTW settings); N1MM has no grid
            toMaidenGrid = map.get("GRIDSQUARE");
        } else {
            toMaidenGrid = "";
        }


        if (map.containsKey("RST_RCVD")) {//Received report. Signal report present in N1MM, Log32, JTDX, but not in LoTW
            try {//Convert float to Long
                receivedReport = Integer.parseInt(Objects.requireNonNull(map.get("RST_RCVD").trim()));
            } catch (NumberFormatException e) {
                isInvalid=true;
                errorMSG="RST_RCVD:"+e.getMessage();
                e.printStackTrace();
                Log.e(TAG, "QSLRecord: RST_RCVD:" + e.getMessage());
            }
        } else {
            receivedReport = -120;
        }

        if (map.containsKey("RST_SENT")) {//Sent report. Signal report present in N1MM, Log32, JTDX, but not in LoTW
            try {//Convert float to Long
                sendReport = Integer.parseInt(Objects.requireNonNull(map.get("RST_SENT").trim()));
            } catch (NumberFormatException e) {
                isInvalid=true;
                errorMSG="RST_SENT:"+e.getMessage();
                e.printStackTrace();
                Log.e(TAG, "QSLRecord: RST_SENT:" + e.getMessage());
            }
        } else {
            sendReport = -120;
        }
        if (map.containsKey("COMMENT")) {//Comment; present in JTDX
            comment = map.get("COMMENT");
        } else {
            comment = String.format(GeneralVariables.getStringFromResource(R.string.qsl_record_import_time)
                    , UtcTimer.getDatetimeStr(UtcTimer.getSystemTime()));
        }

        // POTA activation/hunt metadata on import. pota.app exports use these field names.
        if (map.containsKey("MY_SIG")) mySig = map.get("MY_SIG");
        if (map.containsKey("MY_SIG_INFO")) mySigInfo = map.get("MY_SIG_INFO");
        if (map.containsKey("SIG")) sig = map.get("SIG");
        if (map.containsKey("SIG_INFO")) sigInfo = map.get("SIG_INFO");
    }

    /**
     * SWL QSO notification
     *
     * @return notification string
     */
    public String swlQSOInfo() {
        return String.format("QSO of SWL:%s<--%s", toCallsign, myCallsign);
    }

    @Override
    public String toString() {
        return "QSLRecord{" +
                "id=" + id +
                ", qso_date='" + qso_date + '\'' +
                ", time_on='" + time_on + '\'' +
                ", qso_date_off='" + qso_date_off + '\'' +
                ", time_off='" + time_off + '\'' +
                ", myCallsign='" + myCallsign + '\'' +
                ", myMaidenGrid='" + myMaidenGrid + '\'' +
                ", toCallsign='" + toCallsign + '\'' +
                ", toMaidenGrid='" + toMaidenGrid + '\'' +
                ", sendReport=" + sendReport +
                ", receivedReport=" + receivedReport +
                ", mode='" + mode + '\'' +
                ", bandLength='" + bandLength + '\'' +
                ", bandFreq=" + bandFreq +
                ", wavFrequency=" + wavFrequency +
                ", isQSL=" + isQSL +
                ", isLotW_import=" + isLotW_import +
                ", isLotW_QSL=" + isLotW_QSL +
                ", saved=" + saved +
                ", comment='" + comment + '\'' +
                '}';
    }

    public String toHtmlString() {
        String ss = saved ? "<font color=red>, saved=true</font>" : ", saved=false";
        return "QSLRecord{" +
                "id=" + id +
                ", qso_date='" + qso_date + '\'' +
                ", time_on='" + time_on + '\'' +
                ", qso_date_off='" + qso_date_off + '\'' +
                ", time_off='" + time_off + '\'' +
                ", myCallsign='" + myCallsign + '\'' +
                ", myMaidenGrid='" + myMaidenGrid + '\'' +
                ", toCallsign='" + toCallsign + '\'' +
                ", toMaidenGrid='" + toMaidenGrid + '\'' +
                ", sendReport=" + sendReport +
                ", receivedReport=" + receivedReport +
                ", mode='" + mode + '\'' +
                ", bandLength='" + bandLength + '\'' +
                ", bandFreq=" + bandFreq +
                ", wavFrequency=" + wavFrequency +
                ", isQSL=" + isQSL +
                ", isLotW_import=" + isLotW_import +
                ", isLotW_QSL=" + isLotW_QSL +
                ss +
                ", comment='" + comment + '\'' +
                '}';
    }

    public String getBandLength() {
        return bandLength;
    }

    public String getToCallsign() {
        return toCallsign;
    }

    public String getToMaidenGrid() {
        return toMaidenGrid;
    }

    public String getMode() {
        return mode;
    }

    public long getBandFreq() {
        return bandFreq;
    }

    public int getWavFrequency() {
        return wavFrequency;
    }


    public String getMyCallsign() {
        return myCallsign;
    }

    public String getMyMaidenGrid() {
        return myMaidenGrid;
    }

    public void setMyMaidenGrid(String myMaidenGrid) {
        this.myMaidenGrid = myMaidenGrid;
    }

    public int getSendReport() {
        return sendReport;
    }

    public int getReceivedReport() {
        return receivedReport;
    }

    public String getQso_date() {
        return qso_date;
    }

    public String getTime_on() {
        return time_on;
    }

    public String getQso_date_off() {
        return qso_date_off;
    }

    public String getTime_off() {
        return time_off;
    }

    public String getStartTime() {
        return qso_date + "-" + time_on;
    }

    public String getEndTime() {
        return qso_date_off + "-" + time_off;
    }

    public String getComment() {
        return comment;
    }


    public void setToMaidenGrid(String toMaidenGrid) {
        this.toMaidenGrid = toMaidenGrid;
    }

    public void setSendReport(int sendReport) {
        this.sendReport = sendReport;
    }

    public void setReceivedReport(int receivedReport) {
        this.receivedReport = receivedReport;
    }

    public void setQso_date(String qso_date) {
        this.qso_date = qso_date;
    }

    public void setTime_on(String time_on) {
        this.time_on = time_on;
    }

    public String getMySig() {
        return mySig;
    }

    public void setMySig(String mySig) {
        this.mySig = mySig;
    }

    public String getMySigInfo() {
        return mySigInfo;
    }

    public void setMySigInfo(String mySigInfo) {
        this.mySigInfo = mySigInfo;
    }

    public String getSig() {
        return sig;
    }

    public void setSig(String sig) {
        this.sig = sig;
    }

    public String getSigInfo() {
        return sigInfo;
    }

    public void setSigInfo(String sigInfo) {
        this.sigInfo = sigInfo;
    }
}
