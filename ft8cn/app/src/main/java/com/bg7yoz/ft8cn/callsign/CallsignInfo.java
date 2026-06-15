package com.bg7yoz.ft8cn.callsign;
/**
 * Callsign information class, used for location lookup
 *
 * @author BG7YOZ
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.NonNull;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;

public class CallsignInfo {
    public static String TAG="CallsignInfo";
    public String CallSign; // Callsign
    public String CountryNameEn; // Country name in English
    public String CountryNameCN; // Country name in Chinese
    public int CQZone; // CQ zone
    public int ITUZone; // ITU zone
    public String Continent; // Continent abbreviation
    public float Latitude; // Latitude in degrees, + indicates north
    public float Longitude; // Longitude in degrees, + indicates west
    public float GMT_offset; // Local time offset from GMT
    public String DXCC; // DXCC prefix

    @SuppressLint("DefaultLocale")
    @NonNull
    @Override
    public String toString() {
        return String.format(GeneralVariables.getStringFromResource(R.string.callsign_info)
                , CallSign, CountryNameEn, CQZone, ITUZone, Continent, Longitude, Latitude, GMT_offset, DXCC);
    }


    public CallsignInfo(String callSign, String countryNameEn,
                        String countryNameCN, int CQZone, int ITUZone,
                        String continent, float latitude, float longitude,
                        float GMT_offset, String DXCC) {
        CallSign = callSign;
        CountryNameEn = countryNameEn;
        CountryNameCN = countryNameCN;
        this.CQZone = CQZone;
        this.ITUZone = ITUZone;
        Continent = continent;
        Latitude = latitude;
        Longitude = longitude;
        this.GMT_offset = GMT_offset;
        this.DXCC = DXCC;
    }

    public CallsignInfo(String s) {
        String[] info = s.split(":");
        if (info.length<9){
            Log.e(TAG,"Callsign data format error! "+s);
            return;
        }
        try {
            CountryNameEn = info[0].replace("\n", "").trim();
            CQZone = Integer.parseInt(info[1].replace("\n", "").replace(" ", ""));
            ITUZone = Integer.parseInt(info[2].replace("\n", "").replace(" ", ""));
            Continent = info[3].replace("\n", "").replace(" ", "");
            Latitude = Float.parseFloat(info[4].replace("\n", "").replace(" ", ""));
            Longitude = Float.parseFloat(info[5].replace("\n", "").replace(" ", ""));
            GMT_offset = Float.parseFloat(info[6].replace("\n", "").replace(" ", ""));
            DXCC = info[7].replace("\n", "").replace(" ", "");
            CallSign= info[8].replace("\n", "").replace(" ", "");
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing callsign info fields: " + s, e);
        }
    }
}

