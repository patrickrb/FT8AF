package com.k1af.ft8af.rigs;


import android.util.Log;

public class Yaesu3Command {
    private static final String TAG = "Yaesu3Command";
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

    public Yaesu3Command(String commandID, String data) {
        this.commandID = commandID;
        this.data = data;
    }
    //parse received command

    /**
     * Parse command data from serial port data: command header + content + semicolon
     *
     * @param buffer data received from serial port
     * @return rig command object, or null if data does not match command format.
     */
    public static Yaesu3Command getCommand(String buffer) {
        if (buffer.length() < 2) {//command length must be >= 2
            return null;
        }
        if (buffer.substring(0, 2).matches("[a-zA-Z][a-zA-Z]")) {
            return new Yaesu3Command(buffer.substring(0, 2), buffer.substring(2));
        }
        return null;
    }


    /**
     * Calculate frequency
     *
     * @param command command
     * @return frequency
     */
    public static long getFrequency(Yaesu3Command command) {
        try {
            if (command.getCommandID().equals("FA") || command.getCommandID().equals("FB")) {
                return Long.parseLong(command.getData());
            } else {
                return 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get frequency: " + command.getData() + "\n" + e.getMessage());
        }
        return 0;
    }


    /**
     * Get SWR for YAESU 950
     *
     * @param command command
     * @return value
     */
    public static int getALCOrSWR38(Yaesu3Command command) {
        if (command.data.length() < 7) return 0;
        return Integer.parseInt(command.data.substring(1, 4));
    }

    public static boolean isSWRMeter38(Yaesu3Command command) {
        if (command.data.length() < 7) return false;
        return (command.data.charAt(0) == '6');
    }

    public static boolean isALCMeter38(Yaesu3Command command) {
        if (command.data.length() < 7) return false;
        return (command.data.charAt(0) == '4');
    }


    /**
     * Get SWR for YAESU 891
     *
     * @param command command
     * @return value
     */
    public static int getSWROrALC39(Yaesu3Command command) {
        if (command.data.length() < 4) return 0;
        return Integer.parseInt(command.data.substring(1, 4));
    }

    public static boolean isSWRMeter39(Yaesu3Command command) {
        if (command.data.length() < 4) return false;
        return (command.data.charAt(0) == '6');
    }

    public static boolean isALCMeter39(Yaesu3Command command) {
        if (command.data.length() < 4) return false;
        return (command.data.charAt(0) == '4');
    }

    /**
     * Get ALC/SWR for TS-590
     *
     * @param command command
     * @return value
     */
    public static int get590ALCOrSWR(Yaesu3Command command) {
        return Integer.parseInt(command.data.substring(1, 5));
    }

    public static boolean is590MeterALC(Yaesu3Command command){
        if (command.data.length() < 5) return false;
        return command.data.charAt(2) == '3';
    }
    public static boolean is590MeterSWR(Yaesu3Command command){
        if (command.data.length() < 5) return false;
        return command.data.charAt(2) == '1';
    }

    /**
     * Lab599 Discovery TX-500 SWR meter reply. Unlike the TS-590 layout (meter
     * selector at index 2), the TX-500 answers {@code RM;} with {@code RM1vvvv}
     * where the leading digit is the meter selector ({@code '1'} = SWR) and the
     * four following digits are the meter value (0000-0030). Issue #599.
     *
     * @param command RM command
     * @return true when the reply carries the SWR meter
     */
    public static boolean isTX500MeterSWR(Yaesu3Command command) {
        if (command.data.length() < 5) return false;
        return command.data.charAt(0) == '1';
    }

    /**
     * Value of the TX-500 SWR meter reply: the four digits after the selector
     * ({@code substring(1,5)}), a raw 0000-0030 field. Callers map it to an SWR
     * ratio; see {@link DiscoveryTX500Rig#tx500CatToSwrRatio(int)}.
     *
     * @param command RM command in {@code RM1vvvv} form
     * @return the raw 0-30 meter value, or 0 when the reply is too short
     */
    public static int getTX500MeterValue(Yaesu3Command command) {
        if (command.data.length() < 5) return 0;
        return Integer.parseInt(command.data.substring(1, 5));
    }


}