package com.k1af.ft8af.ui;
/**
 * Dialog for setting signal output level.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;

public class SetVolumeDialog extends Dialog {
    private static final String TAG = "SetVolumeDialog";
    private TextView volumeValueMessage;
    private SeekBar volumeSeekBar;
    private final MainViewModel mainViewModel;
    private VolumeProgress volumeProgress;

    public SetVolumeDialog(@NonNull Context context, MainViewModel mainViewModel) {
        super(context);
        this.mainViewModel = mainViewModel;
    }


    @SuppressLint({"NotifyDataSetChanged", "MissingInflatedId"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.set_volume_dialog);
        volumeValueMessage = (TextView) findViewById(R.id.volumeValueMessage);
        volumeSeekBar = (SeekBar) findViewById(R.id.volumeSeekBar);
        volumeProgress=(VolumeProgress) findViewById(R.id.volumeProgress);
        volumeProgress.setAlarmValue(1.1f);
        volumeProgress.setValueColor(getContext().getColor(R.color.volume_progress_value));//White
        setVolumeText(GeneralVariables.volumePercent);
        volumeSeekBar.setProgress((int) (GeneralVariables.volumePercent*100));

        GeneralVariables.mutableVolumePercent.observeForever(new Observer<Float>() {
            @Override
            public void onChanged(Float aFloat) {
                setVolumeText(aFloat);
            }
        });


        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                GeneralVariables.volumePercent=i/100f;
                GeneralVariables.mutableVolumePercent.postValue(i/100f);
                mainViewModel.databaseOpr.writeConfig("volumeValue",String.valueOf(i),null);
                if (mainViewModel.baseRig!=null){
                    if (mainViewModel.baseRig.getConnector()!=null) {
                        mainViewModel.baseRig.getConnector().setRFVolume(i);
                        Log.e(TAG,String.format("set volume:%d",i));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });

    }

    private void setVolumeText(float vol){
        volumeValueMessage.setText(String.format(
                GeneralVariables.getStringFromResource(R.string.volume_percent)
                , vol*100f));
        volumeProgress.setPercent(vol);

    }

    /**
     * Write configuration to the database
     *
     * @param Value value
     */
    private void writeConfig(String Value) {
        mainViewModel.databaseOpr.writeConfig("volumeValue", Value, null);
    }

    @Override
    public void show() {
        super.show();
        WindowManager.LayoutParams params = getWindow().getAttributes();
        //Set dialog size as a percentage
        int height = getWindow().getWindowManager().getDefaultDisplay().getHeight();
        int width = getWindow().getWindowManager().getDefaultDisplay().getWidth();
        if (width > height) {
            params.width = (int) (width * 0.7);
            //params.height = (int) (height * 0.6);
        } else {
            params.width = (int) (width * 0.95);
            //params.height = (int) (height * 0.5);
        }
        getWindow().setAttributes(params);
    }


}
