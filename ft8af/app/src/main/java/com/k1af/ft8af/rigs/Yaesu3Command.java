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
        return parseMeterValue(command.data.substring(1, 4));
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
        return parseMeterValue(command.data.substring(1, 4));
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
        if (command.data.length() < 5) return 0;
        return parseMeterValue(command.data.substring(1, 5));
    }

    /**
     * Parse a numeric CAT meter token, returning 0 ("no reading") for any
     * non-numeric value. The rig's meter replies are read on the main thread
     * with no surrounding try/catch on the Bluetooth SPP path, so a garbled or
     * non-numeric byte sequence must not be allowed to throw
     * {@link NumberFormatException} and crash the app.
     */
    private static int parseMeterValue(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Non-numeric CAT meter value: " + token);
            return 0;
        }
    }

    public static boolean is590MeterALC(Yaesu3Command command){
        if (command.data.length() < 5) return false;
        // Kenwood "RM" reply is "mvvvv": meter-type selector at index 0
        // (3 = ALC), 4-digit value at indices 1..4. Reading the selector at
        // index 2 (a value digit) mis-detected the meter type -- see get590ALCOrSWR.
        return command.data.charAt(0) == '3';
    }
    public static boolean is590MeterSWR(Yaesu3Command command){
        if (command.data.length() < 5) return false;
        // Meter-type selector is at index 0 (1 = SWR); see is590MeterALC.
        return command.data.charAt(0) == '1';
    }



}