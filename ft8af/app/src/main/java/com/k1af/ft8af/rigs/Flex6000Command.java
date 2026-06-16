package com.k1af.ft8af.rigs;


import android.util.Log;

public class Flex6000Command {
    private static final String TAG = "Flex6000Command";
    private final String commandID;
    private final String data;
    /**
     * Get the command ID (two-character string)
     *
     * @return main command value
     */
    public String getCommandID() {//get main command
        return commandID;
    }

    /**
     * Get command data as a string, without semicolon
     *
     * @return command data
     */
    public String getData() {//get command data
        return data;
    }

    public Flex6000Command(String commandID, String data) {
        this.commandID = commandID;
        this.data = data;
    }
    //parse received command

    /**
     * Parse command data from serial port data: command header + content + semicolon
     *
     * @param buffer  data received from serial port
     * @return rig command object, or null if data does not match command format.
     */
    public static Flex6000Command getCommand(String buffer) {
        if (buffer.length() < 4) {//command length must be >= 4, e.g. ZZFA
            return null;
        }
        if (buffer.substring(0,4).matches("[a-zA-Z][a-zA-Z][a-zA-Z][a-zA-Z]")) {
                return new Flex6000Command(buffer.substring(0, 4), buffer.substring(4));
        }
        return null;
    }


    /**
     * Calculate frequency
     * @param command command
     * @return frequency
     */
    public static long getFrequency(Flex6000Command command) {
        try {
            if(command.getCommandID().equals("ZZFA")||command.getCommandID().equals("ZZFB")) {
                return Long.parseLong(command.getData());
            }else {
                return 0;
            }
        }catch (Exception e){
            Log.e(TAG, "Failed to get frequency: "+command.getData()+"\n"+e.getMessage() );
        }
       return 0;
    }




}