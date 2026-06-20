package com.k1af.ft8af.bluetooth;

/**
 * Bluetooth serial port callback interface
 * BG7YOZ
 * 2023-03
 */
public interface BluetoothSerialListener {
    void onSerialConnect      ();
    void onSerialConnectError (Exception e);
    void onSerialRead         (byte[] data);
    void onSerialIoError      (Exception e);
}
