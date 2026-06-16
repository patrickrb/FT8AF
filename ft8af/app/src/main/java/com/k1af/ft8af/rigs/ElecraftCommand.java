package com.k1af.ft8af.rigs;


import android.util.Log;

public class ElecraftCommand {
    private static final String TAG = "ElecraftCommand";
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

    public ElecraftCommand(String commandID, String data) {
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
    public static ElecraftCommand getCommand(String buffer) {
        if (buffer.length() < 2) {//command length must be >= 2
            return null;
        }
        if (buffer.substring(0,2).matches("[a-zA-Z][a-zA-Z]")) {
                return new ElecraftCommand(buffer.substring(0, 2), buffer.substring(2));
        }
        return null;
    }


    /**
     * Calculate frequency
     * @param command command
     * @return frequency
     */
    public static long getFrequency(ElecraftCommand command) {
        try {
            if(command.getCommandID().equals("FA")||command.getCommandID().equals("FB")) {
                return Long.parseLong(command.getData());
            }else {
                return 0;
            }
        }catch (Exception e){
            Log.e(TAG, "Failed to get frequency: "+command.getData()+"\n"+e.getMessage() );
        }
       return 0;
    }

    public static boolean isSWRMeter(ElecraftCommand command) {
        return  command.data.length() >= 3;
    }

    public static int getSWRMeter(ElecraftCommand command) {
        return  Integer.parseInt(command.data.substring(0,3));
    }


}