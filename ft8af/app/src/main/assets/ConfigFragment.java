package com.k1af.ft8af.ui;
/**
 * Settings screen.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;

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
import com.k1af.ft8af.maidenhead.MaidenheadGrid;
import com.k1af.ft8af.rigs.InstructionSet;
import com.k1af.ft8af.timer.UtcTimer;

import java.io.IOException;

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
    private RigNameSpinnerAdapter rigNameSpinnerAdapter;
    private LaunchSupervisionSpinnerAdapter launchSupervisionSpinnerAdapter;
    private PttDelaySpinnerAdapter pttDelaySpinnerAdapter;
    private NoReplyLimitSpinnerAdapter noReplyLimitSpinnerAdapter;
    //private SerialPortSpinnerAdapter serialPortSpinnerAdapter;

    public ConfigFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    //my grid location
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
    //my callsign
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
    //transmit frequency
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
    //transmit delay time
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

    //excluded callsign prefixes
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

    //modifier
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

    //CI-V address
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
            if (s.matches("\\b0[xX][0-9a-fA-F]+\\b")) {//match hexadecimal
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

        //issue collection only for China
//        if (GeneralVariables.isChina) {
//            binding.faqButton.setVisibility(View.VISIBLE);
//        } else {
//            binding.faqButton.setVisibility(View.GONE);
//        }


        //set time offset
        setUtcTimeOffsetSpinner();

        //set PTT delay
        setPttDelaySpinner();

        //set operating band
        setBandsSpinner();

        //set baud rate list
        setBauRateSpinner();

        //set rig name and parameter list
        setRigNameSpinner();

        //set decode mode
        setDecodeMode();

        //set audio output bit depth
        setAudioOutputBitsMode();

        //set audio output sampling rate
        setAudioOutputRateMode();

        //set message display mode
        setMessageMode();

        //set control mode VOX CAT
        setControlMode();

        //set connection method
        setConnectMode();

        //set transmit supervision list
        setLaunchSupervision();

        //set help dialogs
        setHelpDialog();

        //set no-reply limit
        setNoReplyLimitSpinner();

        //show scroll arrows
        new Handler().postDelayed(new Runnable() {
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

        //FAQ button onClick
        binding.faqButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(requireContext(), FAQActivity.class);
                startActivity(intent);
            }
        });

        //Maidenhead grid
        binding.inputMyGridEdit.removeTextChangedListener(onGridEditorChanged);
        binding.inputMyGridEdit.setText(GeneralVariables.getMyMaidenheadGrid());
        binding.inputMyGridEdit.addTextChangedListener(onGridEditorChanged);

        //my callsign
        binding.inputMycallEdit.removeTextChangedListener(onMyCallEditorChanged);
        binding.inputMycallEdit.setText(GeneralVariables.myCallsign);
        binding.inputMycallEdit.addTextChangedListener(onMyCallEditorChanged);

        //modifier
        binding.modifierEdit.removeTextChangedListener(onModifierEditorChanged);
        binding.modifierEdit.setText(GeneralVariables.toModifier);
        binding.modifierEdit.addTextChangedListener(onModifierEditorChanged);

        //transmit frequency
        binding.inputFreqEditor.removeTextChangedListener(onFreqEditorChanged);
        binding.inputFreqEditor.setText(GeneralVariables.getBaseFrequencyStr());
        binding.inputFreqEditor.addTextChangedListener(onFreqEditorChanged);



        //CIV address
        binding.civAddressEdit.removeTextChangedListener(onCIVAddressEditorChanged);
        binding.civAddressEdit.setText(GeneralVariables.getCivAddressStr());
        binding.civAddressEdit.addTextChangedListener(onCIVAddressEditorChanged);

        //transmit delay
        binding.inputTransDelayEdit.removeTextChangedListener(onTransDelayEditorChanged);
        binding.inputTransDelayEdit.setText(GeneralVariables.getTransmitDelayStr());
        binding.inputTransDelayEdit.addTextChangedListener(onTransDelayEditorChanged);

        binding.excludedCallsignEdit.removeTextChangedListener(onExcludedCallsigns);
        binding.excludedCallsignEdit.setText(GeneralVariables.getExcludeCallsigns());
        binding.excludedCallsignEdit.addTextChangedListener(onExcludedCallsigns);


        //set same-frequency transmit switch
        binding.synFrequencySwitch.setOnCheckedChangeListener(null);
        binding.synFrequencySwitch.setChecked(GeneralVariables.synFrequency);
        setSyncFreqText();//set switch text
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

        //set PTT delay
        binding.pttDelayOffsetSpinner.setOnItemSelectedListener(null);
        binding.pttDelayOffsetSpinner.setSelection(GeneralVariables.pttDelay / 10);
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


        //get the operating band
        binding.operationBandSpinner.setOnItemSelectedListener(null);
        binding.operationBandSpinner.setSelection(GeneralVariables.bandListIndex);
        binding.operationBandSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                GeneralVariables.bandListIndex = i;
                GeneralVariables.band = OperationBand.getBandFreq(i);//save the current band

                mainViewModel.databaseOpr.getAllQSLCallsigns();//read out successfully contacted callsigns
                writeConfig("bandFreq", String.valueOf(GeneralVariables.band));
                if (GeneralVariables.controlMode == ControlMode.CAT//control radio in CAT, RTS, DTR mode
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    //if in CAT or RTS mode, change the radio's frequency
                    mainViewModel.setOperationBand();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });


        //get rig model
        binding.rigNameSpinner.setOnItemSelectedListener(null);
        binding.rigNameSpinner.setSelection(GeneralVariables.modelNo);
        new Handler().postDelayed(new Runnable() {//delay 2 seconds to modify OnItemSelectedListener
            @Override
            public void run() {
                binding.rigNameSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        GeneralVariables.modelNo = i;
                        writeConfig("model", String.valueOf(i));
                        setAddrAndBauRate(rigNameSpinnerAdapter.getRigName(i));

                        //instruction set
                        GeneralVariables.instructionSet = rigNameSpinnerAdapter.getRigName(i).instructionSet;
                        writeConfig("instruction", String.valueOf(GeneralVariables.instructionSet));
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {
                    }
                });
            }
        }, 2000);


        //get baud rate
        binding.baudRateSpinner.setOnItemSelectedListener(null);
        binding.baudRateSpinner.setSelection(bauRateSpinnerAdapter.getPosition(
                GeneralVariables.baudRate));
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

        //set transmit supervision
        binding.launchSupervisionSpinner.setOnItemSelectedListener(null);
        binding.launchSupervisionSpinner.setSelection(launchSupervisionSpinnerAdapter
                .getPosition(GeneralVariables.launchSupervision));
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

        //set no-reply cutoff
        binding.noResponseCountSpinner.setOnItemSelectedListener(null);
        binding.noResponseCountSpinner.setSelection(GeneralVariables.noReplyLimit);
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


        //set auto-follow CQ
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



        //set auto-call for followed callsigns
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

        //set save SWL option
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

        //set save SWL option
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


        //get Maidenhead grid
        binding.configGetGridImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String grid = MaidenheadGrid.getMyMaidenheadGrid(getContext());
                if (!grid.equals("")) {
                    binding.inputMyGridEdit.setText(grid);
                }
            }
        });


        return binding.getRoot();
    }

    /**
     * Set address, baud rate, and instruction set
     *
     * @param rigName rig model
     */
    private void setAddrAndBauRate(RigNameList.RigName rigName) {
        //mainViewModel.setCivAddress(rigName.address);
        GeneralVariables.civAddress = rigName.address;
        mainViewModel.setCivAddress();
        GeneralVariables.baudRate = rigName.bauRate;
        binding.civAddressEdit.setText(String.format("%X", rigName.address));
        binding.baudRateSpinner.setSelection(
                bauRateSpinnerAdapter.getPosition(rigName.bauRate));
    }


    /**
     * Set the display text for the same-frequency transmit switch
     */
    private void setSyncFreqText() {
        if (binding.synFrequencySwitch.isChecked()) {
            binding.synFrequencySwitch.setText(getString(R.string.freq_syn));
        } else {
            binding.synFrequencySwitch.setText(getString(R.string.freq_asyn));
        }
    }

    /**
     * Set the text for the auto-follow CQ switch
     */
    private void setAutoFollowCQText() {
        if (binding.followCQSwitch.isChecked()) {
            binding.followCQSwitch.setText(getString(R.string.auto_follow_cq));
        } else {
            binding.followCQSwitch.setText(getString(R.string.not_concerned_about_CQ));
        }
    }

    //set auto-call for followed callsigns
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
     * Set the UTC time offset spinner
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
                UtcTimer.delay = i * 500 - 7500;//set delay
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });
    }

    /**
     * Set the operating band spinner
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
     * Set the baud rate list
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
     * Set the no-reply count limit
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
     * Set the transmit supervision list
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
     * Set the rig name and parameter list
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
     * Set PTT delay
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
     * Set the audio output bit depth
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
     * Set the output audio sampling rate
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
     * Set the message list display mode
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
     * Set control mode VOX/CAT
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
                } else if (buttonId == binding.ctrCATradioButton.getId()) {//CAT mode
                    GeneralVariables.controlMode = ControlMode.CAT;
                } else if (buttonId == binding.ctrRTSradioButton.getId()) {//RTS mode
                    GeneralVariables.controlMode = ControlMode.RTS;
                } else if (buttonId == binding.ctrDTRradioButton.getId()) {//DTR mode
                    GeneralVariables.controlMode = ControlMode.DTR;
                }
                mainViewModel.setControlMode();//notify that radio control mode has changed
                //whether CAT or RTS, CI-V commands are still valid, both use serial port
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
     * Set the connection method, can be USB or Bluetooth
     */
    private void setConnectMode() {
        if (GeneralVariables.controlMode == ControlMode.CAT
                //&& BluetoothConstants.checkBluetoothIsOpen()
            ) {
            //should be changed to VISIBLE here
            binding.connectModeLayout.setVisibility(View.VISIBLE);
        } else {
            binding.connectModeLayout.setVisibility(View.GONE);
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
                //------show Bluetooth list, select, then establish Bluetooth connection
                if (GeneralVariables.connectMode == ConnectMode.BLUE_TOOTH) {
                    //for Android 12+, need to check Bluetooth permissions:
                    new SelectBluetoothDialog(requireContext(), mainViewModel).show();
                }

                //-----show network radios, currently Flex radios-------------------
                if (GeneralVariables.connectMode==ConnectMode.NETWORK){
                    //open network radio list dialog
                    if (GeneralVariables.instructionSet== InstructionSet.FLEX_NETWORK) {
                        new SelectFlexRadioDialog(requireContext(), mainViewModel).show();
                    }else if(GeneralVariables.instructionSet== InstructionSet.ICOM
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
     * Write configuration to the database
     *
     * @param KeyName key name
     * @param Value   value
     */
    private void writeConfig(String KeyName, String Value) {
        mainViewModel.databaseOpr.writeConfig(KeyName, Value, null);
    }

    private void setHelpDialog() {
        //callsign help
        binding.callsignHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.callsign_help)
                            , true).show();
            }
        });
        //Maidenhead grid help
        binding.maidenGridImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.maidenhead_help)
                            , true).show();
            }
        });
        //transmit frequency help
        binding.frequencyImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.frequency_help)
                            , true).show();
            }
        });
        //transmit delay help
        binding.transDelayImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.transDelay_help)
                            , true).show();
            }
        });
        //time offset help
        binding.timeOffsetImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.timeoffset_help)
                            , true).show();
            }
        });
        //PTT delay help
        binding.pttDelayImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.pttdelay_help)
                            , true).show();
            }
        });
        //list display mode
        binding.messageModeeHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(),requireActivity()
                        ,GeneralVariables.getStringFromResource(R.string.message_mode_help)
                        ,true).show();
            }
        });

        //set up ABOUT
        binding.aboutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(), requireActivity(), "readme.txt", true).show();
            }
        });
        //set operating band
        binding.operationHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.operationBand_help)
                            , true).show();
            }
        });
        //set control mode
        binding.controlModeHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.controlMode_help)
                            , true).show();
            }
        });
        //CI-V address and baud rate help
        binding.baudRateHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.civ_help)
                            , true).show();
            }
        });
        //rig model list
        binding.rigNameHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.rig_model_help)
                            , true).show();
            }
        });
        //transmit supervision
        binding.launchSupervisionImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.launch_supervision_help)
                            , true).show();
            }
        });
        //no-reply count
        binding.noResponseCountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.no_response_help)
                            , true).show();
            }
        });
        //auto call
        binding.autoFollowCountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.auto_follow_help)
                            , true).show();
            }
        });
        //connection mode
        binding.connectModeHelpImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.connectMode_help)
                            , true).show();
            }
        });
        //exclusion options
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

        //decode mode
        binding.decodeModeHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new HelpDialog(requireContext(),requireActivity()
                        ,GeneralVariables.getStringFromResource(R.string.deep_mode_help)
                        ,true).show();
            }
        });

        //audio output help
        binding.audioOutputImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.audio_output_help)
                            , true).show();
            }
        });

        //clear cache
        binding.clearCacheHelpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.clear_cache_data_help)
                            , true).show();
            }
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

        binding.synTImeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                UtcTimer.syncTime(new UtcTimer.AfterSyncTime() {
                    @Override
                    public void doAfterSyncTimer(int secTime) {
                        setUtcTimeOffsetSpinner();
                        if (secTime>100) {//positive value means clock is slow
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
     * Set the visibility of the scroll up/down arrow icons
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