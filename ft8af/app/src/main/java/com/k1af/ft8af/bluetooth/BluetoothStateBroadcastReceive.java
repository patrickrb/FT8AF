package com.k1af.ft8af.bluetooth;
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
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.ui.ToastMessage;

public class BluetoothStateBroadcastReceive extends BroadcastReceiver {
    private static final String TAG="BluetoothStateBroadcastReceive";
    private Context context;
    private MainViewModel mainViewModel;

    /** How long after a profile change to re-evaluate headset mode for non-Bluetooth rigs. */
    static final long PROFILE_REFRESH_DELAY_MS = 500;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshHeadsetMode = () -> mainViewModel.refreshBluetoothHeadsetMode();

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
            case BluetoothAdapter.EXTRA_STATE: {
                boolean profileConnected = headset == BluetoothProfile.STATE_CONNECTED
                        || a2dp == BluetoothProfile.STATE_CONNECTED;
                switch (ScoPolicy.profileChangeAction(GeneralVariables.connectMode,
                        profileConnected)) {
                    case ScoPolicy.PROFILE_ENTER:
                        mainViewModel.setBlueToothOn();
                        break;
                    case ScoPolicy.PROFILE_LEAVE:
                        mainViewModel.setBlueToothOff();
                        break;
                    case ScoPolicy.PROFILE_REFRESH:
                        // USB/VOX rig with a BT headset selected as mic/speaker (#723): a
                        // headset that comes back after the SCO retry budget ran out must
                        // re-enter headset mode, and one that goes away must leave it. The
                        // refresh is selection-aware and idempotent, so a car merely paired
                        // for music still isn't touched. Deferred a beat: the profile
                        // broadcast can land before AudioManager lists the SCO endpoint the
                        // refresh looks the selected device id up against.
                        refreshHandler.removeCallbacks(refreshHeadsetMode);
                        refreshHandler.postDelayed(refreshHeadsetMode, PROFILE_REFRESH_DELAY_MS);
                        break;
                    default:
                        break;
                }
                break;
            }

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

            case AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED:
                // Registered since day one but never handled: the app had no idea
                // whether its startBluetoothSco() ever produced a link, so a
                // failed/dropped SCO was never retried and the AudioRecord was
                // never rebuilt on top of it (issue #759). Not gated on connect
                // mode: the tracker only acts when the app asked for SCO, and
                // the transitions are worth having in debug.log regardless.
                mainViewModel.onScoAudioStateUpdated(
                        intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                                AudioManager.SCO_AUDIO_STATE_ERROR),
                        intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE,
                                AudioManager.SCO_AUDIO_STATE_ERROR));
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
