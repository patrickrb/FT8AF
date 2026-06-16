package com.k1af.ft8af.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.log.OnShareLogEvents;
import com.k1af.ft8af.log.ShareLogs;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Sheet for exporting QSO records to ADIF, then either sharing via the system
 * share sheet or saving directly to Download/FT8AF/. Replaces the legacy
 * ShareLogsProgressDialog flow.
 */
public class ExportLogSheet extends Dialog {

    private final MainViewModel mainViewModel;
    private boolean working = false;
    private volatile boolean cancelled = false;

    public ExportLogSheet(Context context, MainViewModel mainViewModel) {
        super(context, R.style.HelpDialog);
        this.mainViewModel = mainViewModel;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_export_log);

        final TextView summary = findViewById(R.id.exportSummaryTextView);
        final EditText dateStart = findViewById(R.id.exportDateStart);
        final EditText dateEnd = findViewById(R.id.exportDateEnd);
        final TextView progressText = findViewById(R.id.exportProgressTextView);
        final ProgressBar progressBar = findViewById(R.id.exportProgressBar);
        Button cancel = findViewById(R.id.exportCancelButton);
        final Button save = findViewById(R.id.exportSaveButton);
        final Button share = findViewById(R.id.exportShareButton);

        final ShareLogs shareLogs = new ShareLogs();
        final String queryKey = mainViewModel.queryKey == null ? "" : mainViewModel.queryKey;
        int count = shareLogs.getCount(mainViewModel.databaseOpr.getDb(),
                queryKey, mainViewModel.queryFilter, null, null);
        String filterLabel = filterLabel(mainViewModel.queryFilter);
        summary.setText(String.format(Locale.US,
                "%d record%s matching '%s'%s",
                count, count == 1 ? "" : "s",
                queryKey.isEmpty() ? "all" : queryKey,
                filterLabel.isEmpty() ? "" : " [" + filterLabel + "]"));

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                cancelled = true;
                if (working) shareLogs.cancelShare();
                dismiss();
            }
        });

        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (working) return;
                working = true;
                share.setEnabled(false);
                save.setEnabled(false);
                progressBar.setVisibility(View.VISIBLE);
                final File adi = generateTempAdi();
                if (adi == null) return;
                final String startDate = nullIfEmpty(dateStart.getText().toString());
                final String endDate = nullIfEmpty(dateEnd.getText().toString());
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        shareLogs.doShareLogs(getContext(),
                                adi,
                                GeneralVariables.getStringFromResource(R.string.share_logs),
                                mainViewModel.databaseOpr.getDb(),
                                queryKey,
                                mainViewModel.queryFilter,
                                startDate, endDate,
                                adi,
                                false,
                                progressEvents(progressText, progressBar, share, save, true));
                    }
                }).start();
            }
        });

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (working) return;
                working = true;
                share.setEnabled(false);
                save.setEnabled(false);
                progressBar.setVisibility(View.VISIBLE);
                final File adi = generateTempAdi();
                if (adi == null) return;
                final String startDate = nullIfEmpty(dateStart.getText().toString());
                final String endDate = nullIfEmpty(dateEnd.getText().toString());
                final String displayName = "ft8af-log-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                        + ".adi";
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        shareLogs.downQSLTableToFile(mainViewModel.databaseOpr.getDb(),
                                queryKey,
                                mainViewModel.queryFilter,
                                startDate, endDate,
                                adi,
                                false,
                                progressEvents(progressText, progressBar, share, save, false));
                        if (cancelled) {
                            working = false;
                            return;
                        }
                        String result = ShareLogs.saveToDownloads(getContext().getApplicationContext(),
                                adi, displayName);
                        if (result != null) {
                            ToastMessage.show("Saved to " + result);
                        } else {
                            ToastMessage.show("Save to Downloads failed");
                        }
                        working = false;
                        progressBar.post(new Runnable() {
                            @Override public void run() { dismiss(); }
                        });
                    }
                }).start();
            }
        });
    }

    private File generateTempAdi() {
        File adi = GeneralVariables.writeToTempFile(getContext(), "FT8AF-", ".adi", "");
        if (adi == null) {
            ToastMessage.show("Could not create temp file");
            working = false;
            dismiss();
        }
        return adi;
    }

    private OnShareLogEvents progressEvents(final TextView progressText,
                                             final ProgressBar progressBar,
                                             final Button share,
                                             final Button save,
                                             final boolean dismissAfter) {
        return new OnShareLogEvents() {
            @Override public void onPreparing(String info) { progressText.post(() -> progressText.setText(info)); }
            @Override public void onShareStart(int count, String info) {
                progressBar.post(() -> {
                    progressBar.setMax(Math.max(count, 1));
                    progressBar.setProgress(0);
                    progressText.setText(info);
                });
            }
            @Override public boolean onShareProgress(int count, int position, String info) {
                progressBar.post(() -> {
                    progressBar.setMax(Math.max(count, 1));
                    progressBar.setProgress(position);
                    progressText.setText(info);
                });
                return !cancelled;
            }
            @Override public void afterGet(int count, String info) {
                progressBar.post(() -> {
                    progressBar.setProgress(progressBar.getMax());
                    progressText.setText(info);
                    share.setEnabled(true);
                    save.setEnabled(true);
                    working = false;
                    if (dismissAfter) dismiss();
                });
            }
            @Override public void onShareFailed(String info) {
                progressText.post(() -> {
                    progressText.setText(info);
                    share.setEnabled(true);
                    save.setEnabled(true);
                    working = false;
                });
            }
        };
    }

    private static String nullIfEmpty(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static String filterLabel(int queryFilter) {
        switch (queryFilter) {
            case 1: return "confirmed only";
            case 2: return "unconfirmed only";
            default: return "";
        }
    }

    @Override
    public void show() {
        super.show();
        WindowManager.LayoutParams params = getWindow().getAttributes();
        int width = getWindow().getWindowManager().getDefaultDisplay().getWidth();
        params.width = (int) (width * 0.9);
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        getWindow().setAttributes(params);
    }
}
