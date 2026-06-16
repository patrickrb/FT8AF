package com.k1af.ft8af.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.k1af.ft8af.R;
import com.k1af.ft8af.wave.UsbAudioDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * Audio device selection list adapter for input/output device Spinners.
 * Lists both AudioManager-recognized devices and USB audio devices discovered via UsbManager.
 */
public class AudioDeviceSpinnerAdapter extends BaseAdapter {
    private final Context mContext;
    private final int deviceType; // AudioManager.GET_DEVICES_INPUTS or GET_DEVICES_OUTPUTS
    private final boolean isInput;

    // AudioManager recognized devices
    private final List<AudioDeviceInfo> audioDeviceList = new ArrayList<>();
    // USB audio devices discovered via UsbManager (not in AudioManager)
    private final List<UsbAudioDevice.UsbAudioDeviceInfo> usbAudioDeviceList = new ArrayList<>();

    /**
     * @param context    context
     * @param deviceType AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS
     */
    public AudioDeviceSpinnerAdapter(Context context, int deviceType) {
        mContext = context;
        this.deviceType = deviceType;
        this.isInput = (deviceType == AudioManager.GET_DEVICES_INPUTS);
        refreshDevices();
    }

    /**
     * Re-enumerate audio devices (AudioManager + USB)
     */
    public void refreshDevices() {
        audioDeviceList.clear();
        usbAudioDeviceList.clear();

        // 1. Enumerate AudioManager devices
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            AudioDeviceInfo[] devices = audioManager.getDevices(deviceType);
            for (AudioDeviceInfo device : devices) {
                audioDeviceList.add(device);
            }
        }

        // 2. Scan for USB Audio Class devices and list them as a separate "direct" entry.
        // We intentionally do NOT dedupe against AudioManager-listed USB devices: on
        // automotive Android skins the AudioManager entry routes through AudioRecord +
        // setPreferredDevice(), which the in-car audio policy silently overrides back to
        // the built-in mic. The raw USB path bypasses that policy entirely, so users on
        // those systems need both entries visible and labeled distinctly.
        try {
            List<UsbAudioDevice.UsbAudioDeviceInfo> usbDevices =
                    UsbAudioDevice.findUsbAudioDevices(mContext);
            for (UsbAudioDevice.UsbAudioDeviceInfo usbDev : usbDevices) {
                boolean matchesDirection = isInput ? usbDev.hasInput : usbDev.hasOutput;
                if (!matchesDirection) continue;
                usbAudioDeviceList.add(usbDev);
            }
        } catch (Exception e) {
            // Ignore USB enumeration errors
        }
    }

    @Override
    public int getCount() {
        // +1 for "Default" entry
        return 1 + audioDeviceList.size() + usbAudioDeviceList.size();
    }

    @Override
    public Object getItem(int position) {
        if (position == 0) return null; // Default
        int adPos = position - 1;
        if (adPos < audioDeviceList.size()) {
            return audioDeviceList.get(adPos);
        }
        int usbPos = adPos - audioDeviceList.size();
        if (usbPos < usbAudioDeviceList.size()) {
            return usbAudioDeviceList.get(usbPos);
        }
        return null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    /**
     * Get the device ID at the specified position.
     * Positive = AudioManager device ID
     * 0 = Default
     * -1 = USB audio device
     */
    public int getDeviceId(int position) {
        if (position == 0) return 0;
        int adPos = position - 1;
        if (adPos < audioDeviceList.size()) {
            return audioDeviceList.get(adPos).getId();
        }
        // USB audio device
        return -1;
    }

    /**
     * Get the USB audio device info at the given spinner position, or null.
     */
    public UsbAudioDevice.UsbAudioDeviceInfo getUsbAudioDeviceInfo(int position) {
        if (position == 0) return null;
        int adPos = position - 1;
        if (adPos < audioDeviceList.size()) return null;
        int usbPos = adPos - audioDeviceList.size();
        if (usbPos < usbAudioDeviceList.size()) {
            return usbAudioDeviceList.get(usbPos);
        }
        return null;
    }

    /**
     * Get the list position for a given device ID.
     * deviceId == 0 → 0 (Default)
     * deviceId > 0 → search AudioManager devices
     * deviceId == -1 → search USB audio devices by VID:PID
     */
    public int getPositionByDeviceId(int deviceId) {
        if (deviceId == 0) return 0;

        if (deviceId > 0) {
            for (int i = 0; i < audioDeviceList.size(); i++) {
                if (audioDeviceList.get(i).getId() == deviceId) {
                    return i + 1;
                }
            }
        }

        // For USB audio (deviceId == -1), match by VID:PID
        if (deviceId == -1) {
            int vid = isInput
                    ? com.k1af.ft8af.GeneralVariables.usbAudioInputVendorId
                    : com.k1af.ft8af.GeneralVariables.usbAudioOutputVendorId;
            int pid = isInput
                    ? com.k1af.ft8af.GeneralVariables.usbAudioInputProductId
                    : com.k1af.ft8af.GeneralVariables.usbAudioOutputProductId;

            for (int i = 0; i < usbAudioDeviceList.size(); i++) {
                UsbAudioDevice.UsbAudioDeviceInfo info = usbAudioDeviceList.get(i);
                if (info.device.getVendorId() == vid && info.device.getProductId() == pid) {
                    return 1 + audioDeviceList.size() + i;
                }
            }
            // VID:PID not matched but we have USB audio devices — select first one
            if (!usbAudioDeviceList.isEmpty()) {
                return 1 + audioDeviceList.size();
            }
        }

        return 0; // fallback to Default
    }

    @SuppressLint({"ViewHolder", "InflateParams"})
    @Override
    public View getView(int position, View view, ViewGroup viewGroup) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        view = inflater.inflate(R.layout.bau_rate_spinner_item, null);
        if (view != null) {
            TextView textView = view.findViewById(R.id.bauRateItemTextView);
            textView.setText(getDeviceDisplayName(position));
        }
        return view;
    }

    public String getDeviceDisplayName(int position) {
        if (position == 0) {
            return mContext.getString(R.string.audio_device_default);
        }

        int adPos = position - 1;
        if (adPos < audioDeviceList.size()) {
            AudioDeviceInfo device = audioDeviceList.get(adPos);
            String productName = device.getProductName() != null
                    ? device.getProductName().toString() : "";
            String typeName = getDeviceTypeName(device.getType());
            if (productName.isEmpty()) {
                return typeName;
            }
            return productName + " (" + typeName + ")";
        }

        int usbPos = adPos - audioDeviceList.size();
        if (usbPos < usbAudioDeviceList.size()) {
            return usbAudioDeviceList.get(usbPos).getDisplayName();
        }

        return "Unknown";
    }

    private String getDeviceTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Built-in Mic";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "Built-in Speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "Earpiece";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired Headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "Wired Headphones";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB Audio";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return "USB Accessory";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB Headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth SCO";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_HDMI:
                return "HDMI";
            case AudioDeviceInfo.TYPE_HDMI_ARC:
                return "HDMI ARC";
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
                return "Line Analog";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return "Line Digital";
            case AudioDeviceInfo.TYPE_TELEPHONY:
                return "Telephony";
            case AudioDeviceInfo.TYPE_AUX_LINE:
                return "Aux Line";
            case AudioDeviceInfo.TYPE_FM_TUNER:
                return "FM Tuner";
            case AudioDeviceInfo.TYPE_DOCK:
                return "Dock";
            case AudioDeviceInfo.TYPE_IP:
                return "IP";
            case AudioDeviceInfo.TYPE_BUS:
                return "Bus";
            default:
                return "Audio Device";
        }
    }
}
