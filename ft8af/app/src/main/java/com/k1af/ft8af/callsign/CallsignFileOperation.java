package com.k1af.ft8af.callsign;
/**
 * File operations for preprocessing the callsign database. Callsign data source is CTY.DAT.
 * @author BG7YOZ
 * @date 2023-03-20
 */

import android.content.Context;
import android.content.res.AssetManager;

import com.k1af.ft8af.util.Streams;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class CallsignFileOperation {
    public static String TAG="CallsignFileOperation";
    /**
     * Reads the callsign-to-country/region assignment list from cty.dat in the assets directory.
     * The callsign string contains multiple entries separated by commas.
     * @param context used to call the getAssets() method.
     * @return ArrayList<CallsignInfo> a list of CallsignInfo objects
     */
    public static ArrayList<CallsignInfo> getCallSingInfoFromFile(Context context){
        ArrayList<CallsignInfo> callsignInfos=new ArrayList<>();

        AssetManager assetManager = context.getAssets();
        try {
            InputStream inputStream= assetManager.open("cty.dat");
            String[] st=getLinesFromInputStream(inputStream,";");
            for (int i = 0; i <st.length ; i++) {
                if (!st[i].contains(":")){
                    continue;
                }
                CallsignInfo callsignInfo=new CallsignInfo(st[i]);
                callsignInfos.add(callsignInfo);
            }

            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return callsignInfos;
    }


    /**
     * Reads strings from an InputStream.
     * @param inputStream the input stream
     * @param deLimited the delimiter for each line of data.
     * @return String array of lines, or null on failure
     */
    public static String[] getLinesFromInputStream(InputStream inputStream, String deLimited) {
        try {
            byte[] bytes = Streams.readAllBytes(inputStream);
            return (new String(bytes)).split(deLimited);
        }catch (IOException e){
            return null;
        }

    }

    public static Set<String> getCallsigns(String s){
        String[] ls=s.replace("\n","").split(",");
        Set<String> callsigns=new HashSet<>();
        for (int i = 0; i < ls.length ; i++) {
            if (ls[i].contains("(")) {
                //Log.d(TAG,ls[i]);
                ls[i] = ls[i].substring(0, ls[i].indexOf("("));
                //Log.d(TAG,ls[i]+"     (((");
            }
            if (ls[i].contains("[")) {
                //Log.d(TAG,ls[i]);
                ls[i] = ls[i].substring(0, ls[i].indexOf("["));
                //Log.d(TAG,ls[i]+"     【【【");
            }
            callsigns.add(ls[i].trim());
        }

        return callsigns;
    }
}
