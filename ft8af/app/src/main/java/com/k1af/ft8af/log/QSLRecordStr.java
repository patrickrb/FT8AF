package com.k1af.ft8af.log;

import android.os.Parcel;
import android.os.Parcelable;

import com.k1af.ft8af.Ft8Message;

import java.io.Serializable;

/**
 * Used for displaying content in the ADAPTER; this data is generated from database queries
 * @author BGY70Z
 * @date 2023-03-20
 */
public class QSLRecordStr implements Serializable {
    public int id;
    private String call="";
    private String gridsquare="";
    private String mode="";
    private String rst_sent="";
    private String rst_rcvd="";
    private String time_on="";//This time includes the date (composed of QSO_DATE+TIME_ON)
    private String time_off="";//This time includes the date (composed of QSO_DATE_OFF+TIME_OFF)
    private String band="";//Band wavelength
    private String freq="";
    private String station_callsign="";
    private String my_gridsquare="";
    private String comment;
    public String where = null;
    public boolean isQSL = false;//Manual confirmation
    public boolean isLotW_import = false;//Whether imported from LoTW
    public boolean isLotW_QSL = false;//Whether confirmed via LoTW


    public String getCall() {
        return call;
    }

    public void setCall(String call) {
        if (call!=null) {
            this.call = call;
        }else {
            this.call="";
        }
    }

    public String getGridsquare() {
        return gridsquare;
    }

    public void setGridsquare(String gridsquare) {
        if (gridsquare!=null) {
            this.gridsquare = gridsquare;
        }else {
            this.gridsquare="";
        }
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        if (mode!=null) {
            this.mode = mode;
        }else {
            this.mode="";
        }
    }

    public String getRst_sent() {
        return rst_sent;
    }

    public void setRst_sent(String rst_sent) {
        if (rst_sent!=null) {
            this.rst_sent = rst_sent;
        }else {
            this.rst_sent="";
        }
    }

    public String getRst_rcvd() {
        return rst_rcvd;
    }

    public void setRst_rcvd(String rst_rcvd) {
        if (rst_rcvd!=null) {
            this.rst_rcvd = rst_rcvd;
        }else {
            this.rst_rcvd="";
        }
    }

    public String getTime_on() {
        return time_on;
    }

    public void setTime_on(String time_on) {
        if (time_on!=null) {
            this.time_on = time_on;
        }else {
            this.time_on="";
        }
    }

    public String getTime_off() {
        return time_off;
    }

    public void setTime_off(String time_off) {
        if (time_off!=null) {
            this.time_off = time_off;
        }else {
            this.time_off="";
        }
    }

    public String getBand() {
        return band;
    }

    public void setBand(String band) {
        if (band!=null) {
            this.band = band;
        }else {
            this.band="";
        }
    }

    public String getFreq() {
        return freq;
    }

    public void setFreq(String freq) {
        if (freq!=null) {
            this.freq = freq;
        }else {
            this.freq="";
        }
    }

    public String getStation_callsign() {
        return station_callsign;
    }

    public void setStation_callsign(String station_callsign) {
        if (station_callsign!=null) {
            this.station_callsign = station_callsign;
        }else {
            this.station_callsign="";
        }
    }

    public String getMy_gridsquare() {
        return my_gridsquare;
    }

    public void setMy_gridsquare(String my_gridsquare) {
        if (my_gridsquare!=null) {
            this.my_gridsquare = my_gridsquare;
        }else {
            this.my_gridsquare="";
        }
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        if (comment!=null) {
            this.comment = comment;
        }else {
            this.comment="";
        }
    }


}
