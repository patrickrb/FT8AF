package com.k1af.ft8af.ft8transmit;
/**
 * List of QSO records.
 * @author BGY70Z
 * @date 2023-03-20
 */

import com.k1af.ft8af.log.QSLRecord;

import java.util.ArrayList;

public class QslRecordList extends ArrayList<QSLRecord> {

    /**
     * Check if a QSO record exists for the given callsign.
     * @param callsign callsign
     * @return the record, or null if not found
     */
    public QSLRecord getRecordByCallsign(String callsign){
        for (int i = this.size()-1; i >=0 ; i--) {
            if (this.get(i).getToCallsign().equals(callsign)){
                return this.get(i);
            }
        }
        return null;
    }

    /**
     * Look up by callsign whether a QSO record exists and has been saved.
     * If no record exists, it is treated as not saved.
     * @param callsign callsign
     * @return whether it has been saved
     */
    public boolean getSavedRecByCallsign(String callsign){
        QSLRecord record=getRecordByCallsign(callsign);
        if (record==null){
            return false;
        }else {
            return record.saved;
        }
    }

    /**
     * Add a QSO record; if it already exists, update the record.
     * @param record QSO record
     * @return QSO record
     */
    public QSLRecord addQSLRecord(QSLRecord record){
        if (record.getToCallsign().equals("CQ")) return null;
        // remove already saved QSO records
        //for (int i = this.size()-1; i >=0 ; i--) {
        //    if (this.get(i).getToCallsign().equals(record.getToCallsign())){
        //        if (this.get(i).saved){
        //            this.remove(i);
        //        }
        //    }
        //}
        // check if there is a record already in the list that has not been saved yet
        QSLRecord oldRecord= getRecordByCallsign(record.getToCallsign());
        if (oldRecord==null){
            this.add(record);
            return record;
        }else {
            oldRecord.update(record);
        }
        return oldRecord;
    }

    /**
     * Delete records for callsigns that have already been saved.
     * @param record
     */
    public void deleteIfSaved(QSLRecord record){
        // remove already saved QSO records
        for (int i = this.size()-1; i >=0 ; i--) {
            if (this.get(i).getToCallsign().equals(record.getToCallsign())){
                if (this.get(i).saved){
                    this.remove(i);
                }
            }
        }
    }

    public String toHTML(){
        StringBuilder html=new StringBuilder();
        for (int i = 0; i < this.size(); i++) {
            if (i%2==0) {
                html.append("<tr>");
                html.append(String.format("<td class=\"default\" >%s</td>", this.get(i).toHtmlString()));
                html.append("<br>\n</tr>\n");
            }else {
                html.append("<tr  class=\"bbb\">>");
                html.append(String.format("<td class=\"default\" >%s</td>", this.get(i).toHtmlString()));
                html.append("<br>\n</tr>\n");
            }

        }
        return html.toString();
    }

}
