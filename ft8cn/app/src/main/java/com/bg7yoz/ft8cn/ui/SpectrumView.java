package com.bg7yoz.ft8cn.ui;
/**
 * Custom view containing waterfall, frequency bar chart, and ruler.
 * @author BGY70Z
 * @date 2023-03-20
 */

import static android.view.MotionEvent.ACTION_UP;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.timer.UtcTimer;

public class SpectrumView extends ConstraintLayout {
    private MainViewModel mainViewModel;
    private ColumnarView columnarView;
    private Switch controlDeNoiseSwitch;
    private Switch controlShowMessageSwitch;
    private WaterfallView waterfallView;
    private RulerFrequencyView rulerFrequencyView;
    private Fragment fragment;


    private int frequencyLineTimeOut = 0;//Frequency line display duration

    static {
        System.loadLibrary("ft8af");
    }



    public SpectrumView(@NonNull Context context) {
        super(context);
    }

    public SpectrumView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        View view = (View) View.inflate(context, R.layout.spectrum_layout,this);
    }


    @SuppressLint("ClickableViewAccessibility")
    public void run(MainViewModel mainViewModel , Fragment fragment){
        this.mainViewModel = MainViewModel.getInstance(null);
        this.fragment=fragment;
        columnarView=findViewById(R.id.controlColumnarView);
        controlDeNoiseSwitch=findViewById(R.id.controlDeNoiseSwitch);
        waterfallView=findViewById(R.id.controlWaterfallView);
        rulerFrequencyView=findViewById(R.id.controlRulerFrequencyView);
        controlShowMessageSwitch=findViewById(R.id.controlShowMessageSwitch);


        setDeNoiseSwitchState();
        setMarkMessageSwitchState();

        rulerFrequencyView.setFreq(Math.round(GeneralVariables.getBaseFrequency()));
        mainViewModel.currentMessages=null;


        //Raw spectrum switch
        controlDeNoiseSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                mainViewModel.deNoise = b;
                setDeNoiseSwitchState();
                mainViewModel.currentMessages=null;
            }
        });
        //Mark message switch
        controlShowMessageSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                mainViewModel.markMessage = b;
                setMarkMessageSwitchState();
            }
        });

        //Draw spectrum when audio changes
        mainViewModel.spectrumListener.mutableDataBuffer.observe(fragment.getViewLifecycleOwner(), new Observer<float[]>() {
            @Override
            public void onChanged(float[] ints) {
                drawSpectrum(ints);
            }
        });


        //Observe decode state changes
        mainViewModel.mutableIsDecoding.observe(fragment.getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                waterfallView.setDrawMessage(!aBoolean);//aBoolean==false means decoding is complete
            }
        });

        //Action when touching the spectrum
        View.OnTouchListener touchListener = new View.OnTouchListener() {
            @SuppressLint("DefaultLocale")
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {

                frequencyLineTimeOut = 60;//Frequency line display duration: 60*0.16

                waterfallView.setTouch_x(Math.round(motionEvent.getX()));
                columnarView.setTouch_x(Math.round(motionEvent.getX()));


                if (!mainViewModel.ft8TransmitSignal.isSynFrequency()
                        && (waterfallView.getFreq_hz() > 0)
                        && (motionEvent.getAction() == ACTION_UP)
                ) {//If split frequency transmit
                    mainViewModel.databaseOpr.writeConfig("freq",
                            String.valueOf(waterfallView.getFreq_hz()),
                            null);
                    mainViewModel.ft8TransmitSignal.setBaseFrequency(
                            (float) waterfallView.getFreq_hz());

                    rulerFrequencyView.setFreq(waterfallView.getFreq_hz());

                    fragment.requireActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            ToastMessage.show(String.format(
                                    GeneralVariables.getStringFromResource(R.string.sound_frequency_is_set_to)
                                    , waterfallView.getFreq_hz()),true);
                        }
                    });
                }
                return false;
            }
        };

        waterfallView.setOnTouchListener(touchListener);
        columnarView.setOnTouchListener(touchListener);


    }
    private void setDeNoiseSwitchState() {
        if (mainViewModel==null) return;
        controlDeNoiseSwitch.setChecked(mainViewModel.deNoise);
        if (mainViewModel.deNoise) {
            controlDeNoiseSwitch.setText(GeneralVariables.getStringFromResource(R.string.de_noise));
        } else {
            controlDeNoiseSwitch.setText(GeneralVariables.getStringFromResource(R.string.raw_spectrum_data));
        }
    }
    private void setMarkMessageSwitchState(){
        if (mainViewModel.markMessage) {
            controlShowMessageSwitch.setText(GeneralVariables.getStringFromResource(R.string.markMessage));
        } else {
            controlShowMessageSwitch.setText(GeneralVariables.getStringFromResource(R.string.unMarkMessage));
        }
    }




    public void drawSpectrum(float[] buffer) {
        if (buffer.length <= 0) {
            return;
        }
        int[] fft = new int[buffer.length / 2];
        if (mainViewModel.deNoise) {
            getFFTDataFloat(buffer, fft);
        } else {
            getFFTDataRawFloat(buffer, fft);
        }
        frequencyLineTimeOut--;
        if (frequencyLineTimeOut < 0) {
            frequencyLineTimeOut = 0;
        }
        //When display duration is reached, cancel the frequency line
        if (frequencyLineTimeOut == 0) {
            waterfallView.setTouch_x(-1);
            columnarView.setTouch_x(-1);
        }
        columnarView.setWaveData(fft);
        if (mainViewModel.markMessage) {//Whether to mark messages
            waterfallView.setWaveData(fft, mainViewModel.currentMessages);
        } else {
            waterfallView.setWaveData(fft, null);
        }
    }


    public native void getFFTData(int[] data, int fftData[]);
    public native void getFFTDataFloat(float[] data, int fftData[]);

    public native void getFFTDataRaw(int[] data, int fftData[]);
    public native void getFFTDataRawFloat(float[] data, int fftData[]);


}
