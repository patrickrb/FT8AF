package com.bg7yoz.ft8cn.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;

import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;

public class ShareLogsProgressDialog extends Dialog {
    private static final String TAG = "ShareLogsProgressDialog";

    private final MainViewModel mainViewModel;
    private TextView shareDataInfoTextView,shareProgressTextView;
    private ProgressBar shareFileDataProgressBar;
    private final boolean isImportMode;
    //private final int progressMax;

    private Observer<String> shareInfoObserver;
    private Observer<Integer> sharePositionObserver;
    private Observer<Boolean> importShareRunningObserver;
    private Observer<Boolean> shareRunningObserver;
    private Observer<Integer> shareCountObserver;

    public ShareLogsProgressDialog(@NonNull Context context
            , MainViewModel projectsViewModel, boolean isImportMode) {
        super(context, R.style.ShareProgressDialog);
        this.mainViewModel = projectsViewModel;
        this.isImportMode = isImportMode;
        //this.progressMax =progressMax;
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.share_file_progress_dialog);
        setCancelable(false);//Prevent dismissal by tapping outside
        shareDataInfoTextView = findViewById(R.id.shareDataInfoTextView);
        shareProgressTextView = findViewById(R.id.shareProgressTextView);
        if (isImportMode){
            shareProgressTextView.setText(R.string.share_import_log_data);
        }else {
            shareProgressTextView.setText(R.string.preparing_log_data);
        }

        shareFileDataProgressBar = findViewById(R.id.shareFileDataProgressBar);
        Button cancelShareButton = findViewById(R.id.cancelShareButton);


        shareInfoObserver = new Observer<String>() {
            @Override
            public void onChanged(String s) {
                shareDataInfoTextView.setText(s);
            }
        };
        mainViewModel.mutableShareInfo.observeForever(shareInfoObserver);

        sharePositionObserver = new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                shareFileDataProgressBar.setProgress(integer);
            }
        };
        mainViewModel.mutableSharePosition.observeForever(sharePositionObserver);

        importShareRunningObserver = new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (!aBoolean) {
                    dismiss();
                }
            }
        };
        mainViewModel.mutableImportShareRunning.observeForever(importShareRunningObserver);

        shareRunningObserver = new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (!aBoolean) {
                    dismiss();
                }
            }
        };
        mainViewModel.mutableShareRunning.observeForever(shareRunningObserver);

        shareCountObserver = new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                shareFileDataProgressBar.setMax(integer);
            }
        };
        mainViewModel.mutableShareCount.observeForever(shareCountObserver);

        cancelShareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isImportMode) {
                    mainViewModel.mutableImportShareRunning.postValue(false);
                } else {
                    mainViewModel.mutableShareRunning.postValue(false);
                }
            }
        });

    }

    @Override
    protected void onStop() {
        super.onStop();
        removeObservers();
    }

    private void removeObservers() {
        if (shareInfoObserver != null) {
            mainViewModel.mutableShareInfo.removeObserver(shareInfoObserver);
        }
        if (sharePositionObserver != null) {
            mainViewModel.mutableSharePosition.removeObserver(sharePositionObserver);
        }
        if (importShareRunningObserver != null) {
            mainViewModel.mutableImportShareRunning.removeObserver(importShareRunningObserver);
        }
        if (shareRunningObserver != null) {
            mainViewModel.mutableShareRunning.removeObserver(shareRunningObserver);
        }
        if (shareCountObserver != null) {
            mainViewModel.mutableShareCount.removeObserver(shareCountObserver);
        }
    }
}
