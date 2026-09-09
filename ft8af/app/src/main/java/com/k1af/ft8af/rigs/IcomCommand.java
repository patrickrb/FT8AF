package com.k1af.ft8af.rigs;


import android.util.Log;

public class IcomCommand {
    private static final String TAG = "RigCommand";
    private byte[] rawData;

    /**
     * Get the main command
     *
     * @return main command value
     */
    public int getCommandID() {//get main command
        if (rawData.length < 5) {
            return -1;
        }
        return rawData[4];
    }

    /**
     * Get the sub-command. Note that some commands have no sub-command.
     *
     * @return sub-command
     */
    public int getSubCommand() {//get sub-command
        if (rawData.length < 7) {
            return -1;
        }
        return rawData[5];
    }

    /**
     * Get the 2-byte sub-command. Note that some commands have no sub-command, and some have only 1 byte.
     * @return sub-command
     */
    public int getSubCommand2() {//get sub-command
        if (rawData.length < 8) {
            return -1;
        }
        return readShortData(rawData,6);
    }
    /**
     * Get the 3-byte sub-command. Note that some commands have no sub-command, and some have only 1 byte.
     * @return sub-command
     */
    public int getSubCommand3() {//get sub-command
        if (rawData.length < 9) {
            return -1;
        }
        return  ((int) rawData[7] & 0xff)
                | ((int) rawData[6] & 0xff) << 8
                | ((int) rawData[5] & 0xff) << 16;


    }

    /**
     * Get the data section. Some commands have sub-commands, some do not. Sub-command occupies one byte.
     *
     * @param hasSubCommand whether command has a sub-command
     * @return data section
     */
    public byte[] getData(boolean hasSubCommand) {
        int pos;

        if (hasSubCommand) {
            pos = 6;
        } else {
            pos = 5;
        }
        if (rawData.length < pos + 1) {//no data section
            return null;
        }

        byte[] data = new byte[rawData.length - pos];

        for (int i = 0; i < rawData.length - pos; i++) {
            data[i] = rawData[pos + i];
        }
        return data;
    }

    public byte[] getData2Sub() {
        if (rawData.length < 9) {//no data section
            return null;
        }

        byte[] data = new byte[rawData.length - 8];

        System.arraycopy(rawData, 8, data, 0, rawData.length - 8);
        return data;
    }
    //parse received command

    /**
     * Parse command data from serial port data: FE FE E0 A4 Cn Sc data FD
     *
     * @param ctrAddr controller address, default E0 or 00
     * @param rigAddr rig address, IC-705 default is A4
     * @param buffer  data received from serial port
     * @return rig command object, or null if data does not match command format.
     */
    public static IcomCommand getCommand(int ctrAddr, int rigAddr, byte[] buffer) {
        Log.d(TAG, "getCommand: "+BaseRig.byteToStr(buffer) );
        if (buffer.length <= 5) {//command length cannot be <= 5
            return null;
        }
        int position = -1;//command position
        for (int i = 0; i < buffer.length; i++) {
            if (i + 6 > buffer.length) {//command not found
                return null;
            }
            if (buffer[i] == (byte) 0xfe
                    && buffer[i + 1] == (byte) 0xfe//command header 0xfe 0xfe
                    && (buffer[i + 2] == (byte) ctrAddr || (buffer[i + 2] == (byte) 0x00))//controller address default E0 or 00
                    && buffer[i + 3] == (byte) rigAddr) {//rig address, IC-705 default A4, XieGu is 70
                position = i;
                break;
            }
        }
        //not found
        if (position == -1) {
            return null;
        }

        int dataEnd = -1;
        //search from after the command header, so i=position
        for (int i = position; i < buffer.length; i++) {
            if (buffer[i] == (byte) 0xfd) {//check if end reached
                dataEnd = i;
                break;
            }
        }
        if (dataEnd == -1) {//end marker not found
            return null;
        }

        IcomCommand icomCommand = new IcomCommand();
        icomCommand.rawData = new byte[dataEnd - position];
        int pos = 0;
        for (int i = position; i < dataEnd; i++) {//copy command data to rawData
            //icomCommand.rawData[i] = buffer[i];
            icomCommand.rawData[pos] = buffer[i];//fixed index bug
            pos++;
        }
        return icomCommand;
    }


    /**
     * Calculate frequency from BCD-encoded data section
     *
     * @param hasSubCommand whether command contains a sub-command
     * @return frequency value
     */
    public long getFrequency(boolean hasSubCommand) {
        byte[] data = getData(hasSubCommand);
        if (data == null || data.length < 5) {//short/garbled frame with no data section
            return -1;
        }
        return (int) (data[0] & 0x0f)//ones digit 1Hz
                + ((int) (data[0] >> 4) & 0xf) * 10//tens digit 10Hz
                + (int) (data[1] & 0x0f) * 100//hundreds 100Hz
                + ((int) (data[1] >> 4) & 0xf) * 1000//thousands 1kHz
                + (int) (data[2] & 0x0f) * 10000//ten-thousands 10kHz
                + ((int) (data[2] >> 4) & 0xf) * 100000//hundred-thousands 100kHz
                + (int) (data[3] & 0x0f) * 1000000//millions 1MHz
                + ((int) (data[3] >> 4) & 0xf) * 10000000//ten-millions 10MHz
                + (int) (data[4] & 0x0f) * 100000000//hundred-millions 100MHz
                + ((int) (data[4] >> 4) & 0xf) * 1000000000L;//billions 1GHz
    }


    /**
     * Convert bytes to short, without little-endian conversion!!
     *
     * @param data byte data
     * @return short
     */
    public static short readShortData(byte[] data, int start) {
        if (data.length - start < 2) return 0;
        return (short) ((short) data[start + 1] & 0xff
                | ((short) data[start] & 0xff) << 8);
    }


}