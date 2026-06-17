package com.k1af.ft8af.ft8transmit;

/**
 * Class for recording QSO data, used for saving to the database.
 * @author BGY70Z
 * @date 2023-03-20
 */
public class QSLRecord {
    private long startTime;// start time
    private long endTime;// end time

    private String myCallsign;// my callsign
    private String myMaidenGrid;// my grid
    private String toCallsign;// other party's callsign
    private String toMaidenGrid;// other party's grid
    private int sendReport;// report received by the other party (i.e., signal strength I sent)
    private int receivedReport;// report I received from the other party (i.e., SNR)
    private String mode="FT8";

    private long bandFreq;// transmit band
    private int  frequency;// transmit frequency


    public QSLRecord(long startTime, long endTime, String myCallsign, String myMaidenGrid
            , String toCallsign, String toMaidenGrid, int sendReport, int receivedReport
            , String mode, long bandFreq, int frequency) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.myCallsign = myCallsign;
        this.myMaidenGrid = myMaidenGrid;
        this.toCallsign = toCallsign;
        this.toMaidenGrid = toMaidenGrid;
        this.sendReport = sendReport;
        this.receivedReport = receivedReport;
        this.mode = mode;
        this.bandFreq = bandFreq;
        this.frequency = frequency;
    }

    @Override
    public String toString() {
        return "QSLRecord{" +
                "startTime=" + startTime +
                ", endTime=" + endTime +
                ", myCallsign='" + myCallsign + '\'' +
                ", myMaidenGrid='" + myMaidenGrid + '\'' +
                ", toCallsign='" + toCallsign + '\'' +
                ", toMaidenGrid='" + toMaidenGrid + '\'' +
                ", sendReport=" + sendReport +
                ", receivedReport=" + receivedReport +
                ", mode='" + mode + '\'' +
                ", bandFreq=" + bandFreq +
                ", frequency=" + frequency +
                '}';
    }

    public long getEndTime() {
        return endTime;
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

    public int getFrequency() {
        return frequency;
    }

    public long getStartTime() {
        return startTime;
    }

    public String getMyCallsign() {
        return myCallsign;
    }

    public String getMyMaidenGrid() {
        return myMaidenGrid;
    }

    public int getSendReport() {
        return sendReport;
    }

    public int getReceivedReport() {
        return receivedReport;
    }
}
