package com.bg7yoz.ft8cn.ft8transmit;
/**
 * Callsign information recorded during the calling process.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;

public class TransmitCallsign {
    private static final String TAG="TransmitCallsign";
    public String callsign;
    public float frequency;
    public int sequential;
    public int snr;
    public int i3;
    public int n3;
    public String dxcc;
    public int cqZone;
    public int itu;

    public TransmitCallsign(int i3,int n3,String callsign, int sequential) {
        this.callsign = callsign;
        this.sequential = sequential;
        this.i3=i3;
        this.n3=n3;
    }

    public TransmitCallsign(int i3,int n3,String callsign, float frequency
            , int sequential, int snr) {
        this.callsign = callsign;
        this.frequency = frequency;
        this.sequential = sequential;
        this.snr = snr;
        this.i3=i3;
        this.n3=n3;

    }

    /**
     * When the target callsign is null or "CQ", there is no target callsign.
     * @return whether there is a target callsign
     */
    public boolean haveTargetCallsign(){
        if (callsign==null){
            return false;
        }
        return !callsign.equals("CQ");
    }

    @SuppressLint("DefaultLocale")
    public String getSnr(){
        int s = (snr == com.bg7yoz.ft8cn.Ft8Message.SNR_UNKNOWN) ? 0 : snr;
        if (s>0){
            return String.format("+%d",s);
        }else {
            return String.format("%d",s);
        }
    }
}
