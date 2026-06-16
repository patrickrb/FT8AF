package com.bg7yoz.ft8cn.bluetooth;
/**
 * Bluetooth state broadcast class. Handles connection, disconnection, and state changes.
 * @writer bg7yoz
 * @date 2022-07-22
 */

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.ui.ToastMessage;

public class BluetoothStateBroadcastReceive extends BroadcastReceiver {
    private static final String TAG="BluetoothStateBroadcastReceive";
    private Context context;
    private MainViewModel mainViewModel;

    public BluetoothStateBroadcastReceive(Context context, MainViewModel mainViewModel) {
        this.context = context;
        this.mainViewModel = mainViewModel;
    }

    // Only react to Bluetooth audio routing / toasts when the user has actually
    // selected the Bluetooth connect mode. Otherwise users in USB cable mode see
    // spurious "BT connected/disconnected" toasts and the audio plumbing gets
    // bounced around. Matches FT8CN PR #168.
    private boolean shouldHandleBluetoothAudioRouting() {
        return GeneralVariables.connectMode == ConnectMode.BLUE_TOOTH;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onReceive(Context context, Intent intent) {
        this.context=context;
        String action = intent.getAction();

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        BluetoothAdapter blueAdapter = BluetoothAdapter.getDefaultAdapter();
        int headset=-1;
        int a2dp=-1;
        // On Android 12+, getProfileConnectionState requires BLUETOOTH_CONNECT. Skip the
        // profile probe (and the state-change branch below) until the user grants it, so a
        // broadcast that arrives before the permission prompt resolves doesn't crash the app.
        boolean hasBtConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
        if (blueAdapter != null && hasBtConnect) {
            try {
                headset = blueAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
                a2dp = blueAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
            } catch (SecurityException se) {
                // Permission revoked between the check above and the call — fall through with -1.
            }
        }
        if (action == null) return;
        switch (action) {
            case BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED:
            case BluetoothAdapter.EXTRA_CONNECTION_STATE:
            case BluetoothAdapter.EXTRA_STATE:
                if (shouldHandleBluetoothAudioRouting()) {
                    if(headset == BluetoothProfile.STATE_CONNECTED ||a2dp==BluetoothProfile.STATE_CONNECTED){
                    //if(headset == BluetoothProfile.STATE_CONNECTED){
                    //if(a2dp==BluetoothProfile.STATE_CONNECTED){
                        mainViewModel.setBlueToothOn();
                    }else {
                        mainViewModel.setBlueToothOff();
                    }
                }
                break;

            case BluetoothDevice.ACTION_ACL_CONNECTED:
                if (shouldHandleBluetoothAudioRouting() && device!=null) {
                    ToastMessage.show(String.format(
                            GeneralVariables.getStringFromResource(R.string.bluetooth_is_connected)
                            ,device.getName()));
                }
                break;

            case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                if (shouldHandleBluetoothAudioRouting() && device!=null) {
                    ToastMessage.show(String.format(
                            GeneralVariables.getStringFromResource(R.string.bluetooth_is_diconnected)
                            ,device.getName()));
                }
                break;

            case AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                if (shouldHandleBluetoothAudioRouting()) {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.sound_source_switched));
                }
                break;


            case BluetoothAdapter.ACTION_STATE_CHANGED:
                if (shouldHandleBluetoothAudioRouting()) {
                    int blueState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0);
                    switch (blueState) {
                        case BluetoothAdapter.STATE_OFF:
                            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.bluetooth_turn_off));
                            break;
                        case BluetoothAdapter.STATE_ON:
                            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.bluetooth_turn_on));
                            break;
                    }
                }
                break;

        }
    }

//    static final int PROFILE_HEADSET = 0;
//    static final int PROFILE_A2DP  = 1;
//    static final int PROFILE_OPP  = 2;
//    static final int PROFILE_HID = 3;
//    static final int PROFILE_PANU  = 4;
//    static final int PROFILE_NAP  = 5;
//    static final int PROFILE_A2DP_SINK  = 6;
//
//    private boolean checkBluetoothClass(BluetoothClass bluetoothClass,int proFile){
//        if (proFile==PROFILE_A2DP){
//            bluetoothClass.hasService(BluetoothClass.Service.RENDER);
//            return true;
//        }
//    }
}
