package com.k1af.ft8af.log;

/**
 * Log of contacted callsigns.
 * @author BGY70Z
 * @date 2023-03-20
 */
public class QSLCallsignRecord {
    // QSLTable.id of a representative underlying QSO row for this grouped record.
    // Populated by GetQLSCallsignByCallsign so callers (e.g. logbook edit/delete UI)
    // can identify the row in QSLTable. 0 means "no id available".
    public int id = 0;
    private String callsign;
    private String mode;
    private String grid;
    private String band;
    private String lastTime;
    // Latest UTC time (HHMMSS) of the underlying QSO for this grouped row. Paired
    // with lastTime (YYYYMMDD) it gives a sortable date+time so the recent list can
    // order same-day QSOs by time. Empty when unknown (e.g. legacy/ADIF rows).
    private String timeOn = "";
    public String where=null;
    public String dxccStr="";
    public boolean isQSL=false;//Whether manually confirmed
    public boolean isLotW_QSL = false;//Whether confirmed via LoTW
    public boolean syncedCloudlog = false;//Whether at least one underlying row was accepted by Cloudlog/Wavelog/Nextlog
    public boolean syncedQrz = false;//Whether at least one underlying row was accepted by QRZ

    public String getCallsign() {
        return callsign;
    }

    public void setCallsign(String callsign) {
        if (callsign!=null) {
            this.callsign = callsign;
        }else {
            this.callsign="";
        }
    }

    public String getLastTime() {
        return lastTime;
    }

    public void setLastTime(String lastTime) {
        if (lastTime!=null) {
            this.lastTime = lastTime;
        }else {
            this.lastTime="";
        }
    }

    public String getTimeOn() {
        return timeOn;
    }

    public void setTimeOn(String timeOn) {
        this.timeOn = timeOn != null ? timeOn : "";
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

    public String getGrid() {
        return grid;
    }

    public void setGrid(String grid) {
        if (grid!=null) {
            this.grid = grid;
        }else {
            this.grid="";
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
}
