package com.k1af.ft8af.database;
/**
 * List of rig models. Data file is rigaddress.txt
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.util.Streams;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

public class RigNameList {
    private static final String TAG="RigNameList";
    private Context context;
    private static RigNameList rigNameList = null;


    public ArrayList<RigName> rigList = new ArrayList<>();

    public RigNameList(Context context) {
        this.context = context;
        //Load rig data into memory
        getRigNamesFromFile();
    }

    public static RigNameList getInstance(Context context) {
        if (rigNameList == null) {
            return new RigNameList(context);
        } else {
            return rigNameList;
        }
    }

    /**
     * Gets rig parameter data by list index; returns default empty value if not found
     * @param index index
     * @return rig parameters
     */
    public RigName getRigNameByIndex(int index){
        if (index==-1||index>=rigList.size()){
            return new RigName("",0xA4,19200,0);
        }else {
            return rigList.get(index);
        }
    }

    /**
     * Reads rig parameter list from the rigaddress.txt file.
     */
    public void getRigNamesFromFile(){
        AssetManager assetManager = context.getAssets();
        try {
            InputStream inputStream= assetManager.open("rigaddress.txt");
            String[] st=getLinesFromInputStream(inputStream,"\n");
            rigList.add(new RigName("",0xA4,19200,0));
            for (int i = 0; i <st.length ; i++) {
                if (!st[i].contains(",")){
                    continue;
                }
               rigList.add(new RigName(st[i]));
            }
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Error extracting data from address list file: "+e.getMessage() );
        }
    }
    public String getRigNameInfo(int index){
        return rigList.get(index).getName();
    }
    public int getIndexByAddress(int addr){
        int index=-1;
        for (int i = 1; i <rigList.size() ; i++) {
            if (rigList.get(i).address==addr){
                index=i;
                break;
            }
        }
        if (index==-1){//If not found, return the first one, "empty"
            return 0;
        }else {
            return index;
        }
    }

    /**
     * Reads strings from an InputStream
     * @param inputStream input stream
     * @param deLimited delimiter for each line of data
     * @return String returns a string, or null if it fails
     */
    public static String[] getLinesFromInputStream(InputStream inputStream, String deLimited) {
        try {
            byte[] bytes = Streams.readAllBytes(inputStream);
            return (new String(bytes)).split(deLimited);
        }catch (IOException e){
            return null;
        }

    }


    public static class RigName {
        public String modelName;
        public int address;//address
        public int bauRate;//baud rate
        public int instructionSet;//command set 0:icom, 1:yaesu gen 2, 2:yaesu gen 3

        public RigName(String modelName, int address, int bauRate,int instructionSet) {
            this.modelName = modelName;
            this.address = address;
            this.bauRate = bauRate;
            this.instructionSet=instructionSet;
        }

        /**
         * Converts string format data to rig model, e.g. ICOM IC-705,A4,19200
         * @param s
         */
        public RigName(String s) {
            String[] info=s.split(",");
            if (info.length<4){
                modelName="";
                address=0xA4;
                bauRate=19200;
                instructionSet=0;
                return;
            }
            modelName= info[0].trim();
            try {
                address=Integer.parseInt(info[1].trim(),16);
                bauRate=Integer.parseInt(info[2].trim());
                instructionSet=Integer.parseInt(info[3].trim());
            } catch (NumberFormatException e) {
                Log.e(TAG, "Error parsing rig parameters: " + s, e);
                address=0xA4;
                bauRate=19200;
                instructionSet=0;
            }
        }

        public String getName(){
            if (modelName.equals("")) {
                return GeneralVariables.getStringFromResource(R.string.none);
            }else {
                return modelName;
            }
        }
    }
}
