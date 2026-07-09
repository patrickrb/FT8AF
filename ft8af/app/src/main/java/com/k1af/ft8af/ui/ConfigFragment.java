package com.k1af.ft8af.ui;
/**
 * Settings fragment.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;

import com.k1af.ft8af.wave.UsbAudioDevice;

import com.k1af.ft8af.FAQActivity;
import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.database.OperationBand;
import com.k1af.ft8af.database.RigNameList;
import com.k1af.ft8af.databinding.FragmentConfigBinding;
import com.k1af.ft8af.ft8signal.FT8Package;
import com.k1af.ft8af.log.ThirdPartyService;
import com.k1af.ft8af.maidenhead.MaidenheadGrid;
import com.k1af.ft8af.rigs.InstructionSet;
import com.k1af.ft8af.timer.UtcTimer;

import java.io.IOException;

import radio.ks3ckc.ft8af.UsbPermissionIntentsKt;

/**
 * A simple {@link Fragment} subclass.
 * create an instance of this fragment.
 */
public class ConfigFragment extends Fragment {
    private static final String TAG = "ConfigFragment";
    private MainViewModel mainViewModel;
    private FragmentConfigBinding binding;
    private BandsSpinnerAdapter bandsSpinnerAdapter;
    private BauRateSpinnerAdapter bauRateSpinnerAdapter;
    private SerialDataBitsSpinnerAdapter dataBitsSpinnerAdapter;
    private SerialParityBitsSpinnerAdapter parityBitsSpinnerAdapter;
    private SerialStopBitsSpinnerAdapter stopBitsSpinnerAdapter;
    private RigNameSpinnerAdapter rigNameSpinnerAdapter;
    private LaunchSupervisionSpinnerAdapter launchSupervisionSpinnerAdapter;
    private PttDelaySpinnerAdapter pttDelaySpinnerAdapter;
    private NoReplyLimitSpinnerAdapter noReplyLimitSpinnerAdapter;
    private AudioDeviceSpinnerAdapter audioInputDeviceAdapter;
    private AudioDeviceSpinnerAdapter audioOutputDeviceAdapter;
    private AudioManager audioManager;
    private AudioDeviceCallback audioDeviceCallback;
    //private SerialPortSpinnerAdapter serialPortSpinnerAdapter;

    public ConfigFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    // My grid location
    private final TextWatcher onGridEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            //String s = "";
            StringBuilder s=new StringBuilder();
            for (int j = 0; j < binding.inputMyGridEdit.getText().length(); j++) {
                if (j < 2) {
                    //s = s + Character.toUpperCase(binding.inputMyGridEdit.getText().charAt(j));
                    s.append(Character.toUpperCase(binding.inputMyGridEdit.getText().charAt(j)));
                } else {
                    //s = s + Character.toLowerCase(binding.inputMyGridEdit.getText().charAt(j));
                    s.append(Character.toLowerCase(binding.inputMyGridEdit.getText().charAt(j)));
                }
            }
            writeConfig("grid", s.toString());
            GeneralVariables.setMyMaidenheadGrid(s.toString());
        }
    };
    // My callsign
    private final TextWatcher onMyCallEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            writeConfig("callsign", editable.toString().toUpperCase().trim());
            String callsign = editable.toString().toUpperCase().trim();
            if (callsign.length() > 0) {
                Ft8Message.hashList.addHash(FT8Package.getHash22(callsign), callsign);
                Ft8Message.hashList.addHash(FT8Package.getHash12(callsign), callsign);
                Ft8Message.hashList.addHash(FT8Package.getHash10(callsign), callsign);
            }
            GeneralVariables.myCallsign = (editable.toString().toUpperCase().trim());
        }
    };
    // Transmit frequency
    private final TextWatcher onFreqEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            setfreq(editable.toString());
        }
    };
    // Transmit delay time
    private final TextWatcher onTransDelayEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            int transDelay = 1000;
            if (editable.toString().matches("^\\d{1,4}$")) {
                transDelay = Integer.parseInt(editable.toString());
            }
            GeneralVariables.transmitDelay = transDelay;
            mainViewModel.ft8TransmitSignal.setTimer_sec(GeneralVariables.transmitDelay);
            writeConfig("transDelay", Integer.toString(transDelay));
        }
    };


    // Cloudlog address
    private final TextWatcher onCloudlogAddressChanged=new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            GeneralVariables.cloudlogServerAddress = editable.toString();
            writeConfig("cloudlogServerAddress", GeneralVariables.getCloudlogServerAddress());
        }
    };

    // Cloudlog APIKEY
    private final TextWatcher onCloudlogApiKeyChanged=new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            GeneralVariables.cloudlogApiKey = editable.toString();
            writeConfig("cloudlogApiKey", GeneralVariables.getCloudlogServerApiKey());
        }
    };

    // Cloudlog Station ID
    private final TextWatcher onCloudlogStationIDChanged=new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            GeneralVariables.cloudlogStationID = editable.toString();
            writeConfig("cloudlogStationID", GeneralVariables.getCloudlogStationID());
        }
    };

    // QRZ API key
    private final TextWatcher onQrzApiKeyChanged=new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            GeneralVariables.qrzApiKey = editable.toString();
            writeConfig("qrzApiKey", GeneralVariables.getQrzApiKey());
        }
    };


    // Excluded callsign prefixes
    private final TextWatcher onExcludedCallsigns=new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            GeneralVariables.addExcludedCallsigns(editable.toString());
            writeConfig("excludedCallsigns", GeneralVariables.getExcludeCallsigns());
        }
    };

    // Modifier
    private final TextWatcher onModifierEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            if (editable.toString().toUpperCase().trim().matches("[0-9]{3}|[A-Z]{1,4}")
                    ||editable.toString().trim().length()==0){
                binding.modifierEdit.setTextColor(requireContext().getColor(R.color.text_view_color));
                GeneralVariables.toModifier=editable.toString().toUpperCase().trim();
                writeConfig("toModifier", GeneralVariables.toModifier);
            }else{
                binding.modifierEdit.setTextColor(requireContext().getColor(R.color.text_view_error_color));
            }
        }
    };

    // CI-V address
    private final TextWatcher onCIVAddressEditorChanged = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

        }

        @Override
        public void afterTextChanged(Editable editable) {
            if (editable.toString().length() < 2) {
                return;
            }
            String s = "0x" + editable.toString();
            if (s.matches("\\b0[xX][0-9a-fA-F]+\\b")) {// Match hexadecimal
                String temp = editable.toString().substring(0, 2).toUpperCase();
                writeConfig("civ", temp);
                GeneralVariables.civAddress = Integer.parseInt(temp, 16);
                mainViewModel.setCivAddress();
            }
        }
    };


    @SuppressLint("DefaultLocale")
    private void setfreq(String sFreq) {
        float freq;
        try {
            freq = Float.parseFloat(sFreq);
            if (freq < 100) {
                freq = 100;
            }
            if (freq > 2900) {
                freq = 2900;
            }
        } catch (Exception e
        ) {
            freq = 1000;
        }


        writeConfig("freq", String.format("%.0f", freq));
        GeneralVariables.setBaseFrequency(freq);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mainViewModel = MainViewModel.getInstance(this);
        binding = FragmentConfigBinding.inflate(inflater, container, false);


        // Set time offset
        setUtcTimeOffsetSpinner();

        // Set PTT delay
        setPttDelaySpinner();

        // Set operating band
        setBandsSpinner();

        // Set baud rate list
        setBauRateSpinner();

        // Set data bits list
        setDataBitsSpinner();

        // Set parity bits
        setParityBitsSpinner();

        // Set stop bits
        setStopBitsSpinner();

        // Set rig name and parameter list
        setRigNameSpinner();

        // Set decode mode
        setDecodeMode();

        // Set audio output bit depth
        setAudioOutputBitsMode();

        // Set audio output sample rate
        setAudioOutputRateMode();

        // Set audio input device
        setAudioInputDeviceSpinner();

        // Set audio output device
        setAudioOutputDeviceSpinner();

        // Listen for audio device hot-plug events
        registerAudioDeviceCallback();

        // Set message display mode
        setMessageMode();

        // Set control mode (VOX/CAT)
        setControlMode();

        // Set connection mode
        setConnectMode();

        // Set transmit supervision list
        setLaunchSupervision();

        // Set help dialog
        setHelpDialog();

        // Set no-reply limit
        setNoReplyLimitSpinner();

        // Set OnItemSelected events for all spinners
        setSpinnerOnItemSelected();

        // Show scroll arrows
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                setScrollImageVisible();
            }
        }, 1000);
        binding.scrollView3.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View view, int i, int i1, int i2, int i3) {
                setScrollImageVisible();
            }
        });

        // FAQ button onClick
        binding.faqButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(requireContext(), FAQActivity.class);
                startActivity(intent);
            }
        });

        // Maidenhead grid
        binding.inputMyGridEdit.removeTextChangedListener(onGridEditorChanged);
        binding.inputMyGridEdit.setText(GeneralVariables.getMyMaidenheadGrid());
        binding.inputMyGridEdit.addTextChangedListener(onGridEditorChanged);

        // My callsign
        binding.inputMycallEdit.removeTextChangedListener(onMyCallEditorChanged);
        binding.inputMycallEdit.setText(GeneralVariables.myCallsign);
        binding.inputMycallEdit.addTextChangedListener(onMyCallEditorChanged);

        // Modifier
        binding.modifierEdit.removeTextChangedListener(onModifierEditorChanged);
        binding.modifierEdit.setText(GeneralVariables.toModifier);
        binding.modifierEdit.addTextChangedListener(onModifierEditorChanged);

        // Transmit frequency
        binding.inputFreqEditor.removeTextChangedListener(onFreqEditorChanged);
        binding.inputFreqEditor.setText(GeneralVariables.getBaseFrequencyStr());
        binding.inputFreqEditor.addTextChangedListener(onFreqEditorChanged);



        // CI-V address
        binding.civAddressEdit.removeTextChangedListener(onCIVAddressEditorChanged);
        binding.civAddressEdit.setText(GeneralVariables.getCivAddressStr());
        binding.civAddressEdit.addTextChangedListener(onCIVAddressEditorChanged);

        // Transmit delay
        binding.inputTransDelayEdit.removeTextChangedListener(onTransDelayEditorChanged);
        binding.inputTransDelayEdit.setText(GeneralVariables.getTransmitDelayStr());
        binding.inputTransDelayEdit.addTextChangedListener(onTransDelayEditorChanged);

        binding.excludedCallsignEdit.removeTextChangedListener(onExcludedCallsigns);
        binding.excludedCallsignEdit.setText(GeneralVariables.getExcludeCallsigns());
        binding.excludedCallsignEdit.addTextChangedListener(onExcludedCallsigns);

        // Cloudlog configuration
        binding.cloudlogServerAddressEdit.removeTextChangedListener(onCloudlogAddressChanged);
        binding.cloudlogServerAddressEdit.setText(GeneralVariables.getCloudlogServerAddress());
        binding.cloudlogServerAddressEdit.addTextChangedListener(onCloudlogAddressChanged);

        binding.cloudlogServerApiKeyEdit.removeTextChangedListener(onCloudlogApiKeyChanged);
        binding.cloudlogServerApiKeyEdit.setText(GeneralVariables.getCloudlogServerApiKey());
        binding.cloudlogServerApiKeyEdit.addTextChangedListener(onCloudlogApiKeyChanged);

        binding.cloudlogStationIdEdit.removeTextChangedListener(onCloudlogStationIDChanged);
        binding.cloudlogStationIdEdit.setText(GeneralVariables.getCloudlogStationID());
        binding.cloudlogStationIdEdit.addTextChangedListener(onCloudlogStationIDChanged);

        // QRZ configuration
        binding.qrzApiKeyTextEdit.removeTextChangedListener(onQrzApiKeyChanged);
        binding.qrzApiKeyTextEdit.setText(GeneralVariables.getQrzApiKey());
        binding.qrzApiKeyTextEdit.addTextChangedListener(onQrzApiKeyChanged);


        // Set same-frequency transmit switch
        binding.synFrequencySwitch.setOnCheckedChangeListener(null);
        binding.synFrequencySwitch.setChecked(GeneralVariables.synFrequency);
        setSyncFreqText();// Set switch text
        binding.synFrequencySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                if (binding.synFrequencySwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("synFreq", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("synFreq", "0", null);
                    setfreq(binding.inputFreqEditor.getText().toString());
                }
                GeneralVariables.synFrequency = binding.synFrequencySwitch.isChecked();
                setSyncFreqText();
                binding.inputFreqEditor.setEnabled(!binding.synFrequencySwitch.isChecked());
            }
        });

        // Set PTT delay
        binding.pttDelayOffsetSpinner.setSelection(GeneralVariables.pttDelay / 10);
        // Get operating band
        binding.operationBandSpinner.setSelection(GeneralVariables.bandListIndex);
        // Get rig model
        binding.rigNameSpinner.setSelection(GeneralVariables.modelNo);
        // Serial data bits
        binding.dataBitsSpinner.setSelection(dataBitsSpinnerAdapter.getPosition(GeneralVariables.serialDataBits));
        // Serial stop bits
        binding.stopBitsSpinner.setSelection(stopBitsSpinnerAdapter.getPosition(GeneralVariables.serialStopBits));
        binding.parityBitsSpinner.setSelection(parityBitsSpinnerAdapter.getPosition(GeneralVariables.serialParity));
        // Get baud rate
        binding.baudRateSpinner.setSelection(bauRateSpinnerAdapter.getPosition(
                GeneralVariables.baudRate));
        // Set transmit supervision
        binding.launchSupervisionSpinner.setSelection(launchSupervisionSpinnerAdapter
                .getPosition(GeneralVariables.launchSupervision));
        // Set no-reply cutoff
        binding.noResponseCountSpinner.setSelection(GeneralVariables.noReplyLimit);


        // Set auto-follow CQ
        binding.followCQSwitch.setOnCheckedChangeListener(null);
        binding.followCQSwitch.setChecked(GeneralVariables.autoFollowCQ);
        setAutoFollowCQText();
        binding.followCQSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.autoFollowCQ = binding.followCQSwitch.isChecked();
                if (binding.followCQSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("autoFollowCQ", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("autoFollowCQ", "0", null);
                }
                setAutoFollowCQText();
            }
        });

        // Set SWR alarm switch
        binding.swrAlarmSwitch.setOnCheckedChangeListener(null);
        binding.swrAlarmSwitch.setChecked(GeneralVariables.swr_switch_on);
        setSwrAlarmSwitchText();
        binding.swrAlarmSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.swr_switch_on = binding.swrAlarmSwitch.isChecked();
                if (binding.swrAlarmSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("swrSwitch", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("swrSwitch", "0", null);
                }
                setSwrAlarmSwitchText();
            }
        });

        // Set ALC alarm switch
        binding.alcAlarmSwitch.setOnCheckedChangeListener(null);
        binding.alcAlarmSwitch.setChecked(GeneralVariables.alc_switch_on);
        setAlcAlarmSwitchText();
        binding.alcAlarmSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.alc_switch_on = binding.alcAlarmSwitch.isChecked();
                if (binding.alcAlarmSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("alcSwitch", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("alcSwitch", "0", null);
                }
                setAlcAlarmSwitchText();
            }
        });



        // Set auto-call watched callsigns
        binding.autoCallfollowSwitch.setOnCheckedChangeListener(null);
        binding.autoCallfollowSwitch.setChecked(GeneralVariables.autoCallFollow);
        setAutoCallFollow();
        binding.autoCallfollowSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.autoCallFollow = binding.autoCallfollowSwitch.isChecked();
                if (binding.autoCallfollowSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("autoCallFollow", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("autoCallFollow", "0", null);
                }
                setAutoCallFollow();
            }
        });

        // Set save SWL option
        binding.saveSWLSwitch.setOnCheckedChangeListener(null);
        binding.saveSWLSwitch.setChecked(GeneralVariables.saveSWLMessage);
        setSaveSwl();
        binding.saveSWLSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.saveSWLMessage = binding.saveSWLSwitch.isChecked();
                if (binding.saveSWLSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("saveSWL", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("saveSWL", "0", null);
                }
                setSaveSwl();
            }
        });

        // Set save SWL QSO option
        binding.saveSWLQSOSwitch.setOnCheckedChangeListener(null);
        binding.saveSWLQSOSwitch.setChecked(GeneralVariables.saveSWLMessage);
        setSaveSwlQSO();
        binding.saveSWLQSOSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.saveSWL_QSO = binding.saveSWLQSOSwitch.isChecked();
                if (binding.saveSWLQSOSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("saveSWLQSO", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("saveSWLQSO", "0", null);
                }
                setSaveSwlQSO();
            }
        });

        // Set enable Cloudlog option
        binding.enableCloudlogSwitch.setOnCheckedChangeListener(null);
        binding.enableCloudlogSwitch.setChecked(GeneralVariables.enableCloudlog);
        binding.enableCloudlogSwitch.setText(GeneralVariables.getStringFromResource(
                R.string.config_enable_cloudlog)
                +(GeneralVariables.enableCloudlog?"(On)":"(Off)"));
        binding.enableCloudlogSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.enableCloudlog = binding.enableCloudlogSwitch.isChecked();
                if (binding.enableCloudlogSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("enableCloudlog", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("enableCloudlog", "0", null);
                }
                binding.enableCloudlogSwitch.setText(GeneralVariables.getStringFromResource(
                        R.string.config_enable_cloudlog)
                        +(GeneralVariables.enableCloudlog?"(On)":"(Off)"));
            }
        });

        // Set enable QRZ option
        binding.enableQrzSwitch.setOnCheckedChangeListener(null);
        binding.enableQrzSwitch.setChecked(GeneralVariables.enableQRZ);
        binding.enableQrzSwitch.setText(
                GeneralVariables.getStringFromResource(R.string.config_enable_qrz)
                        +(GeneralVariables.enableQRZ?"(On)":"(Off)"));
        binding.enableQrzSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                GeneralVariables.enableQRZ = binding.enableQrzSwitch.isChecked();
                if (binding.enableQrzSwitch.isChecked()) {
                    mainViewModel.databaseOpr.writeConfig("enableQRZ", "1", null);
                } else {
                    mainViewModel.databaseOpr.writeConfig("enableQRZ", "0", null);
                }
                binding.enableQrzSwitch.setText(
                        GeneralVariables.getStringFromResource(R.string.config_enable_qrz)
                                +(GeneralVariables.enableQRZ?"(On)":"(Off)"));
            }
        });


        // Get Maidenhead grid
        binding.configGetGridImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String grid = MaidenheadGrid.getMyMaidenheadGrid(getContext());
                if (!grid.equals("")) {
                    binding.inputMyGridEdit.setText(grid);
                }
            }
        });

        // Serial port default values reset button
        binding.serialDefaultButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GeneralVariables.serialParity = 0;
                GeneralVariables.serialDataBits = 8;
                GeneralVariables.serialStopBits = 1;
                requireActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        binding.parityBitsSpinner.setSelection(
                                parityBitsSpinnerAdapter.getPosition(GeneralVariables.serialParity));
                        binding.dataBitsSpinner.setSelection(
                                dataBitsSpinnerAdapter.getPosition(GeneralVariables.serialDataBits));
                        binding.stopBitsSpinner.setSelection(
                                stopBitsSpinnerAdapter.getPosition(GeneralVariables.serialStopBits));
                    }
                });

            }
        });


        return binding.getRoot();
    }

    /**
     * Set OnItemSelected events for all spinners to prevent duplicate config writes when entering the main view.
     */
    private void setSpinnerOnItemSelected(){
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                binding.pttDelayOffsetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.pttDelay = i * 10;
                        writeConfig("pttDelay", String.valueOf(GeneralVariables.pttDelay));
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {

                    }
                });

                binding.operationBandSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.bandListIndex = i;
                        GeneralVariables.band = OperationBand.getBandFreq(i);// Save the current band frequency

                        mainViewModel.databaseOpr.getAllQSLCallsigns();// Load successfully contacted callsigns
                        writeConfig("bandFreq", String.valueOf(GeneralVariables.band));
                        if (GeneralVariables.controlMode == ControlMode.CAT// Control rig in CAT/RTS/DTR mode
                                || GeneralVariables.controlMode == ControlMode.RTS
                                || GeneralVariables.controlMode == ControlMode.DTR) {
                            // If in CAT/RTS mode, change the rig frequency
                            mainViewModel.setOperationBand();
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {

                    }
                });

                binding.rigNameSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.modelNo = i;
                        writeConfig("model", String.valueOf(i));
                        setAddrAndBauRate(rigNameSpinnerAdapter.getRigName(i));

                        // Instruction set
                        GeneralVariables.instructionSet = rigNameSpinnerAdapter.getRigName(i).instructionSet;
                        writeConfig("instruction", String.valueOf(GeneralVariables.instructionSet));
                        // FT-710 requires CAT control: auto-switch if user picks it in another mode.
                        if (GeneralVariables.instructionSet == InstructionSet.YAESU_FT710
                                && GeneralVariables.controlMode != ControlMode.CAT) {
                            GeneralVariables.controlMode = ControlMode.CAT;
                            writeConfig("ctrMode", String.valueOf(GeneralVariables.controlMode));
                            mainViewModel.setControlMode();
                            setControlMode();
                            setConnectMode();
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {
                    }
                });


                binding.baudRateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.baudRate = bauRateSpinnerAdapter.getValue(i);
                        writeConfig("baudRate", String.valueOf(GeneralVariables.baudRate));
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {
                    }
                });


                binding.launchSupervisionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.launchSupervision = LaunchSupervisionSpinnerAdapter.getTimeOut(i);
                        writeConfig("launchSupervision", String.valueOf(GeneralVariables.launchSupervision));
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {

                    }
                });


                binding.noResponseCountSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.noReplyLimit = i;
                        writeConfig("noReplyLimit", String.valueOf(GeneralVariables.noReplyLimit));
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {

                    }
                });
                // Serial data bits
                binding.dataBitsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.serialDataBits =  dataBitsSpinnerAdapter.getValue(i);
                        writeConfig("dataBits", String.valueOf(GeneralVariables.serialDataBits));
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {

                    }
                });

                // Serial stop bits
                binding.stopBitsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.serialStopBits =  stopBitsSpinnerAdapter.getValue(i);
                        writeConfig("stopBits", String.valueOf(GeneralVariables.serialStopBits));
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {

                    }
                });

                // Parity bits
                binding.parityBitsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.serialParity =  parityBitsSpinnerAdapter.getValue(i);
                        writeConfig("parityBits", String.valueOf(GeneralVariables.serialParity));
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {

                    }
                });

                // Audio input device
                binding.audioInputDeviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        int deviceId = audioInputDeviceAdapter.getDeviceId(i);
                        GeneralVariables.audioInputDeviceId = deviceId;
                        writeConfig("audioInputDevice", String.valueOf(deviceId));

                        // Handle USB audio device selection
                        UsbAudioDevice.UsbAudioDeviceInfo usbInfo =
                                audioInputDeviceAdapter.getUsbAudioDeviceInfo(i);
                        if (usbInfo != null) {
                            GeneralVariables.usbAudioInputVendorId = usbInfo.device.getVendorId();
                            GeneralVariables.usbAudioInputProductId = usbInfo.device.getProductId();
                            writeConfig("usbAudioInputVid",
                                    String.valueOf(GeneralVariables.usbAudioInputVendorId));
                            writeConfig("usbAudioInputPid",
                                    String.valueOf(GeneralVariables.usbAudioInputProductId));
                            requestUsbPermissionIfNeeded(usbInfo.device);
                        } else if (deviceId != -1) {
                            // Clear USB audio settings if non-USB device selected
                            GeneralVariables.usbAudioInputVendorId = 0;
                            GeneralVariables.usbAudioInputProductId = 0;
                            writeConfig("usbAudioInputVid", "0");
                            writeConfig("usbAudioInputPid", "0");
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {
                    }
                });

                // Audio output device
                binding.audioOutputDeviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        int deviceId = audioOutputDeviceAdapter.getDeviceId(i);
                        GeneralVariables.audioOutputDeviceId = deviceId;
                        writeConfig("audioOutputDevice", String.valueOf(deviceId));

                        // Handle USB audio device selection
                        UsbAudioDevice.UsbAudioDeviceInfo usbInfo =
                                audioOutputDeviceAdapter.getUsbAudioDeviceInfo(i);
                        if (usbInfo != null) {
                            GeneralVariables.usbAudioOutputVendorId = usbInfo.device.getVendorId();
                            GeneralVariables.usbAudioOutputProductId = usbInfo.device.getProductId();
                            writeConfig("usbAudioOutputVid",
                                    String.valueOf(GeneralVariables.usbAudioOutputVendorId));
                            writeConfig("usbAudioOutputPid",
                                    String.valueOf(GeneralVariables.usbAudioOutputProductId));
                            requestUsbPermissionIfNeeded(usbInfo.device);
                        } else if (deviceId != -1) {
                            GeneralVariables.usbAudioOutputVendorId = 0;
                            GeneralVariables.usbAudioOutputProductId = 0;
                            writeConfig("usbAudioOutputVid", "0");
                            writeConfig("usbAudioOutputPid", "0");
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {
                    }
                });

            }
        }, 1000);
    }

    /**
     * Set address, baud rate, and instruction set.
     *
     * @param rigName Rig model
     */
    private void setAddrAndBauRate(RigNameList.RigName rigName) {
        GeneralVariables.civAddress = rigName.address;
        mainViewModel.setCivAddress();
        GeneralVariables.baudRate = rigName.bauRate;
        binding.civAddressEdit.setText(String.format("%X", rigName.address));
        binding.baudRateSpinner.setSelection(
                bauRateSpinnerAdapter.getPosition(rigName.bauRate));
    }


    /**
     * Set the display text for the same-frequency transmit switch.
     */
    private void setSyncFreqText() {
        if (binding.synFrequencySwitch.isChecked()) {
            binding.synFrequencySwitch.setText(getString(R.string.freq_syn));
        } else {
            binding.synFrequencySwitch.setText(getString(R.string.freq_asyn));
        }
    }

    /**
     * Set the text for the auto-follow CQ switch.
     */
    private void setAutoFollowCQText() {
        if (binding.followCQSwitch.isChecked()) {
            binding.followCQSwitch.setText(getString(R.string.auto_follow_cq));
        } else {
            binding.followCQSwitch.setText(getString(R.string.not_concerned_about_CQ));
        }
    }

    /**
     * Set the text for the SWR alarm switch.
     */
    private void setSwrAlarmSwitchText(){
        if (binding.swrAlarmSwitch.isChecked()){
            binding.swrAlarmSwitch.setText(R.string.swr_switch_on);
        }else {
            binding.swrAlarmSwitch.setText(R.string.swr_switch_off);
        }
    }

    /**
     * Set the text for the ALC alarm switch.
     */
    private void setAlcAlarmSwitchText(){
        if (binding.alcAlarmSwitch.isChecked()){
            binding.alcAlarmSwitch.setText(R.string.alc_switch_on);
        }else {
            binding.alcAlarmSwitch.setText(R.string.alc_switch_off);
        }
    }

    // Set auto-call watched callsigns
    private void setAutoCallFollow() {
        if (binding.autoCallfollowSwitch.isChecked()) {
            binding.autoCallfollowSwitch.setText(getString(R.string.automatic_call_following));
        } else {
            binding.autoCallfollowSwitch.setText(getString(R.string.do_not_call_the_following_callsign));
        }
    }
    private void setSaveSwl() {
        if (binding.saveSWLSwitch.isChecked()) {
            binding.saveSWLSwitch.setText(getString(R.string.config_save_swl));
        } else {
            binding.saveSWLSwitch.setText(getString(R.string.config_donot_save_swl));
        }
    }
    private void setSaveSwlQSO() {
        if (binding.saveSWLQSOSwitch.isChecked()) {
            binding.saveSWLQSOSwitch.setText(getString(R.string.config_save_swl_qso));
        } else {
            binding.saveSWLQSOSwitch.setText(getString(R.string.config_donot_save_swl_qso));
        }
    }
    /**
     * Set the UTC time offset spinner.
     */
    private void setUtcTimeOffsetSpinner() {
        UtcOffsetSpinnerAdapter adapter = new UtcOffsetSpinnerAdapter(requireContext());

        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                binding.utcTimeOffsetSpinner.setAdapter(adapter);
                adapter.notifyDataSetChanged();
                binding.utcTimeOffsetSpinner.setSelection((UtcTimer.delay / 100 + 75) / 5);
            }
        });
        binding.utcTimeOffsetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                UtcTimer.delay = i * 500 - 7500;// Set delay
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });
    }

    /**
     * Set the operating band spinner.
     */
    private void setBandsSpinner() {
        GeneralVariables.mutableBandChange.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                binding.operationBandSpinner.setSelection(integer);
            }
        });


        bandsSpinnerAdapter = new BandsSpinnerAdapter(requireContext());
        binding.operationBandSpinner.setAdapter(bandsSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                bandsSpinnerAdapter.notifyDataSetChanged();
            }
        });

    }

    /**
     * Set the baud rate list.
     */
    private void setBauRateSpinner() {
        bauRateSpinnerAdapter = new BauRateSpinnerAdapter(requireContext());
        binding.baudRateSpinner.setAdapter(bauRateSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                bauRateSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set the audio input device list.
     */
    private void setAudioInputDeviceSpinner() {
        audioInputDeviceAdapter = new AudioDeviceSpinnerAdapter(requireContext(),
                AudioManager.GET_DEVICES_INPUTS);
        binding.audioInputDeviceSpinner.setAdapter(audioInputDeviceAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                audioInputDeviceAdapter.notifyDataSetChanged();
                binding.audioInputDeviceSpinner.setSelection(
                        audioInputDeviceAdapter.getPositionByDeviceId(GeneralVariables.audioInputDeviceId));
            }
        });
    }

    /**
     * Set the audio output device list.
     */
    private void setAudioOutputDeviceSpinner() {
        audioOutputDeviceAdapter = new AudioDeviceSpinnerAdapter(requireContext(),
                AudioManager.GET_DEVICES_OUTPUTS);
        binding.audioOutputDeviceSpinner.setAdapter(audioOutputDeviceAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                audioOutputDeviceAdapter.notifyDataSetChanged();
                binding.audioOutputDeviceSpinner.setSelection(
                        audioOutputDeviceAdapter.getPositionByDeviceId(GeneralVariables.audioOutputDeviceId));
            }
        });
    }

    /**
     * Register audio device hot-plug callback to refresh Spinner list on device changes.
     */
    private void registerAudioDeviceCallback() {
        audioManager = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        audioDeviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                refreshAudioDeviceSpinners();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                refreshAudioDeviceSpinners();
            }
        };
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, new Handler(Looper.getMainLooper()));
    }

    /**
     * Refresh audio device Spinner lists, preserving current selection.
     */
    private void refreshAudioDeviceSpinners() {
        if (audioInputDeviceAdapter != null) {
            audioInputDeviceAdapter.refreshDevices();
            audioInputDeviceAdapter.notifyDataSetChanged();
            binding.audioInputDeviceSpinner.setSelection(
                    audioInputDeviceAdapter.getPositionByDeviceId(GeneralVariables.audioInputDeviceId));
        }
        if (audioOutputDeviceAdapter != null) {
            audioOutputDeviceAdapter.refreshDevices();
            audioOutputDeviceAdapter.notifyDataSetChanged();
            binding.audioOutputDeviceSpinner.setSelection(
                    audioOutputDeviceAdapter.getPositionByDeviceId(GeneralVariables.audioOutputDeviceId));
        }
    }

    private static final String ACTION_USB_AUDIO_PERMISSION =
            "com.k1af.ft8af.USB_AUDIO_PERMISSION";

    /**
     * Request USB audio device permission if needed.
     */
    private void requestUsbPermissionIfNeeded(UsbDevice device) {
        if (device == null) return;
        UsbManager usbManager = (UsbManager) requireContext().getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "USB audio device already has permission");
            return;
        }

        Log.d(TAG, "Requesting USB permission for audio device: " + device.getProductName());
        PendingIntent permissionIntent = UsbPermissionIntentsKt.createUsbPermissionIntent(
                requireContext(), ACTION_USB_AUDIO_PERMISSION);

        BroadcastReceiver permReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_USB_AUDIO_PERMISSION.equals(intent.getAction())) {
                    boolean granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    Log.d(TAG, "USB audio permission " + (granted ? "granted" : "denied"));
                    try { context.unregisterReceiver(this); } catch (Exception ignored) {}
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(permReceiver,
                    new IntentFilter(ACTION_USB_AUDIO_PERMISSION),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(permReceiver,
                    new IntentFilter(ACTION_USB_AUDIO_PERMISSION));
        }
        usbManager.requestPermission(device, permissionIntent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (audioManager != null && audioDeviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
        }
    }

    /**
     * Set the data bits list.
     */
    private void setDataBitsSpinner(){
        dataBitsSpinnerAdapter = new SerialDataBitsSpinnerAdapter(requireContext());
        binding.dataBitsSpinner.setAdapter(dataBitsSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                dataBitsSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set the parity bits list.
     */
    private void setParityBitsSpinner(){
        parityBitsSpinnerAdapter = new SerialParityBitsSpinnerAdapter(requireContext());
        binding.parityBitsSpinner.setAdapter(parityBitsSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                parityBitsSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }
    /**
     * Set the stop bits list.
     */
    private void setStopBitsSpinner(){
        stopBitsSpinnerAdapter = new SerialStopBitsSpinnerAdapter(requireContext());
        binding.stopBitsSpinner.setAdapter(stopBitsSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stopBitsSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }



    /**
     * Set the no-reply limit spinner.
     */
    private void setNoReplyLimitSpinner() {
        noReplyLimitSpinnerAdapter = new NoReplyLimitSpinnerAdapter(requireContext());
        binding.noResponseCountSpinner.setAdapter(noReplyLimitSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                noReplyLimitSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set the transmit supervision list.
     */
    private void setLaunchSupervision() {
        launchSupervisionSpinnerAdapter = new LaunchSupervisionSpinnerAdapter(requireContext());
        binding.launchSupervisionSpinner.setAdapter(launchSupervisionSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                launchSupervisionSpinnerAdapter.notifyDataSetChanged();
            }
        });
    }

    /**
     * Set rig name and parameter list.
     */
    private void setRigNameSpinner() {
        rigNameSpinnerAdapter = new RigNameSpinnerAdapter(requireContext());
        binding.rigNameSpinner.setAdapter(rigNameSpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                rigNameSpinnerAdapter.notifyDataSetChanged();
            }
        });

    }

    /**
     * Set PTT delay.
     */
    private void setPttDelaySpinner() {
        pttDelaySpinnerAdapter = new PttDelaySpinnerAdapter(requireContext());
        binding.pttDelayOffsetSpinner.setAdapter(pttDelaySpinnerAdapter);
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                pttDelaySpinnerAdapter.notifyDataSetChanged();
                binding.pttDelayOffsetSpinner.setSelection(GeneralVariables.pttDelay / 10);
            }
        });


    }


    private void setDecodeMode() {
        binding.decodeModeRadioGroup.clearCheck();
        binding.fastDecodeRadioButton.setChecked(!GeneralVariables.deepDecodeMode);
        binding.deepDecodeRadioButton.setChecked(GeneralVariables.deepDecodeMode);

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int buttonId = binding.decodeModeRadioGroup.getCheckedRadioButtonId();
                GeneralVariables.deepDecodeMode= buttonId ==binding.deepDecodeRadioButton.getId();
                writeConfig("deepMode", GeneralVariables.deepDecodeMode? "1" : "0");
            }
        };

        binding.fastDecodeRadioButton.setOnClickListener(listener);
        binding.deepDecodeRadioButton.setOnClickListener(listener);

    }


    /**
     * Set audio output bit depth.
     */
    private void setAudioOutputBitsMode() {
        //binding.controlModeRadioGroup.setOnCheckedChangeListener(null);
        binding.audioBitsRadioGroup.clearCheck();
        binding.audio32BitsRadioButton.setChecked(GeneralVariables.audioOutput32Bit);
        binding.audio16BitsRadioButton.setChecked(!GeneralVariables.audioOutput32Bit);



        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int buttonId = binding.audioBitsRadioGroup.getCheckedRadioButtonId();
                GeneralVariables.audioOutput32Bit= buttonId ==binding.audio32BitsRadioButton.getId();
                writeConfig("audioBits", GeneralVariables.audioOutput32Bit? "1" : "0");
            }
        };

        binding.audio32BitsRadioButton.setOnClickListener(listener);
        binding.audio16BitsRadioButton.setOnClickListener(listener);

    }

    /**
     * Set audio output sample rate.
     */
    private void setAudioOutputRateMode() {
        binding.audioRateRadioGroup.clearCheck();
        binding.audio12kRadioButton.setChecked(GeneralVariables.audioSampleRate==12000);
        binding.audio24kRadioButton.setChecked(GeneralVariables.audioSampleRate==24000);
        binding.audio48kRadioButton.setChecked(GeneralVariables.audioSampleRate==48000);



        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (binding.audio12kRadioButton.isChecked()) GeneralVariables.audioSampleRate=12000;
                if (binding.audio24kRadioButton.isChecked()) GeneralVariables.audioSampleRate=24000;
                if (binding.audio48kRadioButton.isChecked()) GeneralVariables.audioSampleRate=48000;
                writeConfig("audioRate", String.valueOf(GeneralVariables.audioSampleRate));
            }
        };

        binding.audio12kRadioButton.setOnClickListener(listener);
        binding.audio24kRadioButton.setOnClickListener(listener);
        binding.audio48kRadioButton.setOnClickListener(listener);

    }



    /**
     * Set message list display mode.
     */
    private void setMessageMode() {
        binding.messageModeRadioGroup.clearCheck();
        if (GeneralVariables.simpleCallItemMode){
            binding.msgSimpleRadioButton.setChecked(true);
        }else {
            binding.msgStandardRadioButton.setChecked(true);
        }



        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int buttonId = binding.messageModeRadioGroup.getCheckedRadioButtonId();
                GeneralVariables.simpleCallItemMode=
                        binding.messageModeRadioGroup.getCheckedRadioButtonId()
                                ==binding.msgSimpleRadioButton.getId();

                writeConfig("msgMode", GeneralVariables.simpleCallItemMode?"1":"0");
            }
        };

        binding.msgStandardRadioButton.setOnClickListener(listener);
        binding.msgSimpleRadioButton.setOnClickListener(listener);
    }




    /**
     * Set control mode (VOX/CAT).
     */
    private void setControlMode() {
        //binding.controlModeRadioGroup.setOnCheckedChangeListener(null);
        binding.controlModeRadioGroup.clearCheck();

        switch (GeneralVariables.controlMode) {
            case ControlMode.CAT:
            case ConnectMode.NETWORK:
                binding.ctrCATradioButton.setChecked(true);
                break;
            case ControlMode.RTS:
                binding.ctrRTSradioButton.setChecked(true);
                break;
            case ControlMode.DTR:
                binding.ctrDTRradioButton.setChecked(true);
                break;
            default:
                binding.ctrVOXradioButton.setChecked(true);
        }


        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int buttonId = binding.controlModeRadioGroup.getCheckedRadioButtonId();

                if (buttonId == binding.ctrVOXradioButton.getId()) {
                    GeneralVariables.controlMode = ControlMode.VOX;
                } else if (buttonId == binding.ctrCATradioButton.getId()) {// CAT mode
                    GeneralVariables.controlMode = ControlMode.CAT;
                } else if (buttonId == binding.ctrRTSradioButton.getId()) {// RTS mode
                    GeneralVariables.controlMode = ControlMode.RTS;
                } else if (buttonId == binding.ctrDTRradioButton.getId()) {// DTR mode
                    GeneralVariables.controlMode = ControlMode.DTR;
                }
                mainViewModel.setControlMode();// Notify that rig control mode has changed
                // Whether CAT or RTS, CI-V commands are still valid via serial port
                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (!mainViewModel.isRigConnected()) {
                        mainViewModel.getUsbDevice();
                    } else {
                        mainViewModel.setOperationBand();
                    }
                }
                writeConfig("ctrMode", String.valueOf(GeneralVariables.controlMode));
                setConnectMode();
            }
        };

        binding.ctrCATradioButton.setOnClickListener(listener);
        binding.ctrVOXradioButton.setOnClickListener(listener);
        binding.ctrRTSradioButton.setOnClickListener(listener);
        binding.ctrDTRradioButton.setOnClickListener(listener);
    }

    /**
     * Set connection mode: USB cable, Bluetooth, or Network.
     */
    private void setConnectMode() {
        if ((GeneralVariables.controlMode == ControlMode.CAT)
                //&& BluetoothConstants.checkBluetoothIsOpen()
            ) {
            // Change to VISIBLE here
            binding.connectModeLayout.setVisibility(View.VISIBLE);
            binding.serialLayout.setVisibility(View.VISIBLE);
        } else {
            binding.connectModeLayout.setVisibility(View.GONE);
            binding.serialLayout.setVisibility(View.GONE);
        }
        binding.connectModeRadioGroup.clearCheck();
        switch (GeneralVariables.connectMode) {
            case ConnectMode.USB_CABLE:
                binding.cableConnectRadioButton.setChecked(true);
                break;
            case ConnectMode.BLUE_TOOTH:
                binding.bluetoothConnectRadioButton.setChecked(true);
                break;
            case ConnectMode.NETWORK:
                binding.networkConnectRadioButton.setChecked(true);
                break;
        }
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int buttonId = binding.connectModeRadioGroup.getCheckedRadioButtonId();
                if (buttonId == binding.cableConnectRadioButton.getId()) {
                    GeneralVariables.connectMode = ConnectMode.USB_CABLE;
                } else if (buttonId == binding.bluetoothConnectRadioButton.getId()) {
                    GeneralVariables.connectMode = ConnectMode.BLUE_TOOTH;
                }else if (buttonId==binding.networkConnectRadioButton.getId()){
                    GeneralVariables.connectMode=ConnectMode.NETWORK;
                }
                // Show Bluetooth device list, select, then establish Bluetooth connection
                if (GeneralVariables.connectMode == ConnectMode.BLUE_TOOTH) {
                    // For Android 12+, check Bluetooth permissions:
                    new SelectBluetoothDialog(requireContext(), mainViewModel).show();
                }

                // Show network radios (currently FlexRadio)
                if (GeneralVariables.connectMode==ConnectMode.NETWORK){
                    // Open network radio list dialog
                    if (GeneralVariables.instructionSet== InstructionSet.FLEX_NETWORK) {
                        new SelectFlexRadioDialog(requireContext(), mainViewModel).show();
                    }else if (GeneralVariables.instructionSet== InstructionSet.HAMLIB_NET) {
                        new HamlibConnectDialog(requireContext(), mainViewModel).show();
                    }else if (GeneralVariables.instructionSet== InstructionSet.XIEGU_6100_FT8CNS) {
                        new SelectXieguRadioDialog(requireContext(), mainViewModel).show();
                    }
                    else if(GeneralVariables.instructionSet== InstructionSet.ICOM
                            ||GeneralVariables.instructionSet== InstructionSet.XIEGU_6100
                            ||GeneralVariables.instructionSet== InstructionSet.XIEGUG90S) {
                        new LoginIcomRadioDialog(requireContext(), mainViewModel).show();
                    }else {
                        ToastMessage.show(GeneralVariables.getStringFromResource(R.string.only_flex_supported));
                    }
                }

            }
        };
        binding.cableConnectRadioButton.setOnClickListener(listener);
        binding.bluetoothConnectRadioButton.setOnClickListener(listener);
        binding.networkConnectRadioButton.setOnClickListener(listener);
    }


    /**
     * Write configuration to database.
     *
     * @param KeyName Key name
     * @param Value   Value
     */
    private void writeConfig(String KeyName, String Value) {
        mainViewModel.databaseOpr.writeConfig(KeyName, Value, null);
    }

    private void setHelpDialog() {
        // Callsign help
        binding.callsignHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.callsign_help)
                            , true).show();
            }
        });

        // Cloudlog help
        binding.cloudlogSettingsImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.cloudlog_help)
                        , true).show();
            }
        });
        // QRZ help
        binding.qrzSettingsImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.qrz_help)
                        , true).show();
            }
        });

        // Maidenhead grid help
        binding.maidenGridImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.maidenhead_help)
                            , true).show();
            }
        });
        // Transmit frequency help
        binding.frequencyImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.frequency_help)
                            , true).show();
            }
        });
        // Transmit delay help
        binding.transDelayImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.transDelay_help)
                            , true).show();
            }
        });
        // Time offset help
        binding.timeOffsetImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.timeoffset_help)
                            , true).show();
            }
        });
        // PTT delay help
        binding.pttDelayImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.pttdelay_help)
                            , true).show();
            }
        });
        // Serial port settings help
        binding.serialHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity()
                        , GeneralVariables.getStringFromResource(R.string.serial_setting_help)
                        , true).show();
            }
        });

        // List display mode
        binding.messageModeeHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(),requireActivity()
                        ,GeneralVariables.getStringFromResource(R.string.message_mode_help)
                        ,true).show();
            }
        });

        // About
        binding.aboutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity(), "readme.txt", true).show();
            }
        });
        // Operating band
        binding.operationHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.operationBand_help)
                            , true).show();
            }
        });
        // Control mode
        binding.controlModeHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.controlMode_help)
                            , true).show();
            }
        });
        // CI-V address and baud rate help
        binding.baudRateHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.civ_help)
                            , true).show();
            }
        });
        // Rig model list
        binding.rigNameHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.rig_model_help)
                            , true).show();
            }
        });
        // Transmit supervision
        binding.launchSupervisionImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.launch_supervision_help)
                            , true).show();
            }
        });
        // No-reply count
        binding.noResponseCountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.no_response_help)
                            , true).show();
            }
        });
        // Auto call
        binding.autoFollowCountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.auto_follow_help)
                            , true).show();
            }
        });
        // Connection mode
        binding.connectModeHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.connectMode_help)
                            , true).show();
            }
        });
        // Exclude option
        binding.excludedHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.excludeCallsign_help)
                            , true).show();
            }
        });

        binding.swlHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.swlMode_help)
                            , true).show();
            }
        });

        // Decode mode
        binding.decodeModeHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(),requireActivity()
                        ,GeneralVariables.getStringFromResource(R.string.deep_mode_help)
                        ,true).show();
            }
        });

        // Audio device help
        binding.audioDeviceHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.audio_device_help)
                            , true).show();
            }
        });

        // Audio output help
        binding.audioOutputImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.audio_output_help)
                            , true).show();
            }
        });

        // Clear cache
        binding.clearCacheHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.clear_cache_data_help)
                            , true).show();
            }
        });

        // Cloudlog test...
        binding.testCloudlogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.testCloudlogButton.setEnabled(false);
                binding.testCloudlogButton.setText(getResources().getString(R.string.testing));
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean result = ThirdPartyService.CheckCloudlogConnection();
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (result) {
                                    binding.testCloudlogButton.setText(getResources().getString(R.string.pass));
                                } else {
                                    binding.testCloudlogButton.setText(getResources().getString(R.string.fail));
                                }
                                // Clear text
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        binding.testCloudlogButton.setEnabled(true);
                                        binding.testCloudlogButton.setText(getResources().getString(R.string.test));
                                    }
                                }, 3000);
                            }
                        });
                    }
                }).start();}
        });
        // QRZ test...
        binding.testQrzButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.testQrzButton.setEnabled(false);
                binding.testQrzButton.setText(getResources().getString(R.string.testing));
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean result = ThirdPartyService.CheckQRZConnection();
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (result) {
                                    binding.testQrzButton.setText(getResources().getString(R.string.pass));
                                } else {
                                    binding.testQrzButton.setText(getResources().getString(R.string.fail));
                                }
                                // Clear text
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        binding.testQrzButton.setEnabled(true);
                                        binding.testQrzButton.setText(getResources().getString(R.string.test));
                                    }
                                }, 3000);
                            }
                        });
                    }
                }).start();}
        });

        binding.clearFollowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new ClearCacheDataDialog(requireContext(), requireActivity()
                        ,mainViewModel.databaseOpr
                        ,ClearCacheDataDialog.CACHE_MODE.FOLLOW_DATA).show();
            }
        });
        binding.clearLogCacheButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new ClearCacheDataDialog(requireContext(), requireActivity()
                        ,mainViewModel.databaseOpr
                        ,ClearCacheDataDialog.CACHE_MODE.SWL_MSG).show();
            }
        });
        binding.clearSWlQsoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new ClearCacheDataDialog(requireContext(), requireActivity()
                        ,mainViewModel.databaseOpr
                        ,ClearCacheDataDialog.CACHE_MODE.SWL_QSO).show();
            }
        });
        // Delete shared temporary files
        binding.clearShareDataButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        GeneralVariables.clearCache(binding.getRoot().getContext());
                    }
                }).start();
            }
        });

        binding.synTImeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                UtcTimer.syncTime(new UtcTimer.AfterSyncTime() {
                    @Override
                    public void doAfterSyncTimer(int secTime) {
                        setUtcTimeOffsetSpinner();
                        if (secTime>100) {// Positive value means clock is slow
                            ToastMessage.show(String.format(GeneralVariables
                                    .getStringFromResource(R.string.utc_time_sync_delay_slow), secTime));
                        }else if (secTime<-100){
                            ToastMessage.show(String.format(GeneralVariables
                                    .getStringFromResource(R.string.utc_time_sync_delay_faster), -secTime));
                        }else {
                            ToastMessage.show(GeneralVariables
                                    .getStringFromResource(R.string.config_clock_is_accurate));
                        }
                    }

                    @Override
                    public void syncFailed(IOException e) {
                        ToastMessage.show(e.getMessage());
                    }
                });

            }
        });

    }

    /**
     * Set the scroll up/down indicator icons on the interface.
     */
    private void setScrollImageVisible() {

        if (binding.scrollView3.getScrollY() == 0) {
            binding.configScrollUpImageView.setVisibility(View.GONE);
        } else {
            binding.configScrollUpImageView.setVisibility(View.VISIBLE);
        }

        if (binding.scrollView3.getHeight() + binding.scrollView3.getScrollY()
                < binding.scrollLinearLayout.getMeasuredHeight()) {
            binding.configScrollDownImageView.setVisibility(View.VISIBLE);
        } else {
            binding.configScrollDownImageView.setVisibility(View.GONE);
        }
    }


}