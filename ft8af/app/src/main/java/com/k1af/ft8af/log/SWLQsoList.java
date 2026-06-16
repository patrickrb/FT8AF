package com.k1af.ft8af.log;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.timer.UtcTimer;

import java.util.ArrayList;

/**
 * Used for calculating and processing QSO records from SWL messages.
 * QSO calculation method: The 6 stages of an FT8 QSO are divided into 3 parts:
 * 1.CQ C1 grid
 * 2.C1 C2 grid
 * ---------Part 1---
 * 3.C2 C1 report
 * 4.C1 C2 r-report
 * --------Part 2----
 * 5.C2 C1 RR73(RRR)
 * 6.C1 C2 73
 * --------Part 3----
 * <p>
 * A basic QSO must have its own end point (Part 3), signal reports from both parties (determined in Part 2), and grid reports are optional (Part 1).
 * RR73, RRR, and 73 are used as checkpoints, matching Parts 1 and 2 above.
 * swlQsoList is a dual-key HashMap used to prevent duplicate QSO records.
 * The order of C1 and C2 differs, representing different calling parties, reflected in the station_callsign and call fields.
 *
 * @author BG7YOZ
 * @date 2023-03-07
 */
public class SWLQsoList {
    private static final String TAG = "SWLQsoList";
    //Successful QSO list to prevent duplicates; the two KEYs are station_callsign and call in order; Boolean=true means already QSO'd
    private final HashTable qsoList =new HashTable();

    public SWLQsoList() {
    }

    /**
     * Check for QSO messages
     *
     * @param newMessages   new FT8 messages
     * @param allMessages   all FT8 messages
     * @param onFoundSwlQso callback when a QSO is found
     */
    public void findSwlQso(ArrayList<Ft8Message> newMessages, ArrayList<Ft8Message> allMessages
            , OnFoundSwlQso onFoundSwlQso) {

        for (int i = 0; i < newMessages.size(); i++) {
            Ft8Message msg = newMessages.get(i);
            if (msg.inMyCall()) continue;//Skip messages that contain my own callsign

            if (GeneralVariables.checkFun4_5(msg.extraInfo)//End markers RRR, RR73, 73
                    && !qsoList.contains(msg.callsignFrom, msg.callsignTo)) {//No existing QSO record

                QSLRecord qslRecord = new QSLRecord(msg);

                if (checkPart2(allMessages, qslRecord)) {//Find signal reports from both parties; a basic QSO requires signal reports from both sides

                    checkPart1(allMessages, qslRecord);//Find grid reports from both parties; also update time_on

                    if (onFoundSwlQso != null) {//Trigger callback for recording to the database
                        qsoList.put(msg.callsignFrom, msg.callsignTo, true);//Save the QSO record
                        onFoundSwlQso.doFound(qslRecord);//Trigger the QSO found action
                    }
                }
            }
        }
    }

    /**
     * Check if Part 2 exists and save signal reports to QSLRecord
     *
     * @param allMessages message list
     * @param record      QSLRecord
     * @return return value: not found = false, exists = true
     */
    private boolean checkPart2(ArrayList<Ft8Message> allMessages, QSLRecord record) {
        boolean foundFromReport = false;
        boolean foundToReport = false;
        long time_on = System.currentTimeMillis();//Use current time as the earliest time initially
        for (int i = allMessages.size() - 1; i >= 0; i--) {
            Ft8Message msg = allMessages.get(i);
            //if (msg.callsignFrom.equals(record.getMyCallsign())
            if (GeneralVariables.checkIsMyCallsign(msg.callsignFrom)
                    && msg.callsignTo.equals(record.getToCallsign())
                    && !foundFromReport) {//Signal report sent by callsignFrom
                int report = GeneralVariables.checkFun2_3(msg.extraInfo);

                if (time_on > msg.utcTime) time_on = msg.utcTime;//Take the earliest time
                if (report != -100) {
                    record.setSendReport(report);
                    foundFromReport = true;
                }
            }

            if (msg.callsignFrom.equals(record.getToCallsign())
                    //&& msg.callsignTo.equals(record.getMyCallsign())
                    && GeneralVariables.checkIsMyCallsign(msg.callsignTo)
                    && !foundToReport) {//Signal report sent by callsignTo
                int report = GeneralVariables.checkFun2_3(msg.extraInfo);
                if (time_on > msg.utcTime) time_on = msg.utcTime;//Take the earliest time
                if (report != -100) {
                    record.setReceivedReport(report);
                    foundToReport = true;
                }
            }
            if (foundToReport && foundFromReport) {//If signal reports from both parties are found, exit the loop
                record.setQso_date(UtcTimer.getYYYYMMDD(time_on));
                record.setTime_on(UtcTimer.getTimeHHMMSS(time_on));
                break;
            }
        }
        return foundToReport && foundFromReport;//Both parties' signal reports must exist for it to count as a QSO
    }

    /**
     * Check if Part 1 exists and save grid reports to QSLRecord
     *
     * @param allMessages message list
     * @param record      QSLRecord
     */
    private void checkPart1(ArrayList<Ft8Message> allMessages, QSLRecord record) {
        boolean foundFromGrid = false;
        boolean foundToGrid = false;
        long time_on = System.currentTimeMillis();//Use current time as the earliest time initially
        for (int i = allMessages.size() - 1; i >= 0; i--) {
            Ft8Message msg = allMessages.get(i);
            if (!foundFromGrid
                    //&& msg.callsignFrom.equals(record.getMyCallsign())
                    && GeneralVariables.checkIsMyCallsign(msg.callsignFrom)
                    && (msg.callsignTo.equals(record.getToCallsign()) || msg.checkIsCQ())) {//Grid report from callsignFrom

                if (GeneralVariables.checkFun1_6(msg.extraInfo)) {
                    record.setMyMaidenGrid(msg.extraInfo.trim());
                    foundFromGrid = true;
                }
                if (time_on > msg.utcTime) time_on = msg.utcTime;//Take the earliest time
            }

            if (!foundToGrid
                    && msg.callsignFrom.equals(record.getToCallsign())
                    //&& (msg.callsignTo.equals(record.getMyCallsign())
                    && (GeneralVariables.checkIsMyCallsign(msg.callsignTo)
                    || msg.checkIsCQ())) {//Grid report sent by callsignTo
                if (GeneralVariables.checkFun1_6(msg.extraInfo)) {
                    record.setToMaidenGrid(msg.extraInfo.trim());
                    foundToGrid = true;
                }
                if (time_on > msg.utcTime) time_on = msg.utcTime;//Take the earliest time
            }
            if (foundToGrid && foundFromGrid) {//If grid reports from both parties are found, exit the loop
                break;
            }
        }

        if (foundFromGrid || foundToGrid) {//Grid report found from at least one direction
            record.setQso_date(UtcTimer.getYYYYMMDD(time_on));
            record.setTime_on(UtcTimer.getTimeHHMMSS(time_on));
        }
    }

    public interface OnFoundSwlQso {
        void doFound(QSLRecord record);
    }
}
