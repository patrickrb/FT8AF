package com.k1af.ft8af.ui;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityViewCommand;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.log.OnShareLogEvents;
import com.k1af.ft8af.log.ShareLogs;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

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
        setupDatePicker(dateStart, "exportDatePickerStart");
        setupDatePicker(dateEnd, "exportDatePickerEnd");
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
                if (!validateDate(dateStart, "From") || !validateDate(dateEnd, "To")) return;
                final String startDate = nullIfEmpty(dateStart.getText().toString());
                final String endDate = nullIfEmpty(dateEnd.getText().toString());
                working = true;
                share.setEnabled(false);
                save.setEnabled(false);
                progressBar.setVisibility(View.VISIBLE);
                final File adi = generateTempAdi();
                if (adi == null) return;
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
                if (!validateDate(dateStart, "From") || !validateDate(dateEnd, "To")) return;
                final String startDate = nullIfEmpty(dateStart.getText().toString());
                final String endDate = nullIfEmpty(dateEnd.getText().toString());
                working = true;
                share.setEnabled(false);
                save.setEnabled(false);
                progressBar.setVisibility(View.VISIBLE);
                final File adi = generateTempAdi();
                if (adi == null) return;
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

    /**
     * Wire up an editable date field so tapping its trailing calendar icon opens a
     * {@link MaterialDatePicker}. The field stays fully editable by keyboard; the
     * picker just fills in a {@code YYYYMMDD} string, keeping the two input paths
     * in sync and producing the exact format {@code ShareLogs} expects.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupDatePicker(final EditText field, final String tag) {
        field.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() != MotionEvent.ACTION_UP) return false;
                Drawable end = field.getCompoundDrawablesRelative()[2];
                if (end == null) return false;
                int hit = field.getWidth() - field.getPaddingEnd() - end.getBounds().width();
                if (event.getX() >= hit) {
                    field.performClick();
                    openDatePicker(field, tag);
                    return true;
                }
                return false;
            }
        });
        // The trailing calendar icon is a touch-only hit target, so also expose the
        // picker as a custom accessibility action — TalkBack users get an "Open
        // calendar" entry in the field's actions menu rather than only being able
        // to type.
        ViewCompat.addAccessibilityAction(field, "Open calendar date picker",
                new AccessibilityViewCommand() {
                    @Override
                    public boolean perform(View view, AccessibilityViewCommand.CommandArguments args) {
                        openDatePicker(field, tag);
                        return true;
                    }
                });
    }

    private void openDatePicker(final EditText field, String tag) {
        FragmentManager fm = fragmentManager();
        if (fm == null || fm.isStateSaved() || fm.findFragmentByTag(tag) != null) return;
        Long preselect = parseYyyyMmddUtc(field.getText().toString());
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker();
        builder.setSelection(preselect != null ? preselect
                : MaterialDatePicker.todayInUtcMilliseconds());
        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(new com.google.android.material.datepicker.MaterialPickerOnPositiveButtonClickListener<Long>() {
            @Override
            public void onPositiveButtonClick(Long selection) {
                if (selection != null) field.setText(formatYyyyMmddUtc(selection));
            }
        });
        picker.show(fm, tag);
    }

    /** Unwrap {@link #getContext()} to the hosting {@link FragmentActivity}, if any. */
    private FragmentManager fragmentManager() {
        Context c = getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof FragmentActivity) {
                return ((FragmentActivity) c).getSupportFragmentManager();
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /**
     * Validate a date field before export. An empty field is valid ("no bound").
     * A non-empty field must be a strict {@code YYYYMMDD} date; otherwise a toast
     * is shown and {@code false} returned so the caller aborts the export.
     */
    private boolean validateDate(EditText field, String label) {
        if (isValidOptionalDate(field.getText().toString())) return true;
        ToastMessage.show("Invalid '" + label + "' date — use YYYYMMDD (e.g. 20260722)");
        return false;
    }

    /** True when {@code s} is empty/null (no bound) or a strict {@code YYYYMMDD} date. */
    static boolean isValidOptionalDate(String s) {
        if (s == null) return true;
        s = s.trim();
        return s.isEmpty() || parseYyyyMmddUtc(s) != null;
    }

    /**
     * Parse a strict 8-digit {@code YYYYMMDD} string to UTC-midnight millis (the
     * form {@link MaterialDatePicker} uses), or {@code null} if it is not a real
     * calendar date. Used both to validate typed input and to preselect the picker.
     */
    static Long parseYyyyMmddUtc(String s) {
        if (s == null) return null;
        s = s.trim();
        if (!s.matches("\\d{8}")) return null;
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        fmt.setLenient(false);
        try {
            Date d = fmt.parse(s);
            // Round-trip so out-of-band values SimpleDateFormat still accepts
            // (e.g. year 0000) are rejected rather than silently normalized.
            if (d == null || !fmt.format(d).equals(s)) return null;
            return d.getTime();
        } catch (ParseException e) {
            return null;
        }
    }

    /** Format {@link MaterialDatePicker}'s UTC-millis selection as {@code YYYYMMDD}. */
    static String formatYyyyMmddUtc(long utcMillis) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(utcMillis));
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
