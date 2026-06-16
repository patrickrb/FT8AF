package com.k1af.ft8af.ui;

import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;

import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.log.QSLRecordStr;

/**
 * Dialog for editing a single QSO record. All ADIF fields stored in QSLTable
 * are editable; on Save the row is updated by id.
 */
public class EditQSLDialog extends Dialog {

    public interface OnSaved {
        void onSaved(QSLRecordStr updated);
    }

    private final MainViewModel mainViewModel;
    private final QSLRecordStr record;
    private final OnSaved onSaved;

    public EditQSLDialog(Context context, MainViewModel mainViewModel, QSLRecordStr record, OnSaved onSaved) {
        super(context, R.style.HelpDialog);
        this.mainViewModel = mainViewModel;
        this.record = record;
        this.onSaved = onSaved;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_edit_qsl);

        final EditText editCall = findViewById(R.id.editCall);
        final EditText editGridsquare = findViewById(R.id.editGridsquare);
        final EditText editMode = findViewById(R.id.editMode);
        final EditText editRstSent = findViewById(R.id.editRstSent);
        final EditText editRstRcvd = findViewById(R.id.editRstRcvd);
        final EditText editQsoDate = findViewById(R.id.editQsoDate);
        final EditText editTimeOn = findViewById(R.id.editTimeOn);
        final EditText editQsoDateOff = findViewById(R.id.editQsoDateOff);
        final EditText editTimeOff = findViewById(R.id.editTimeOff);
        final EditText editBand = findViewById(R.id.editBand);
        final EditText editFreq = findViewById(R.id.editFreq);
        final EditText editStationCallsign = findViewById(R.id.editStationCallsign);
        final EditText editMyGridsquare = findViewById(R.id.editMyGridsquare);
        final EditText editComment = findViewById(R.id.editComment);
        final CheckBox editIsQsl = findViewById(R.id.editIsQsl);
        Button cancelButton = findViewById(R.id.editCancelButton);
        Button saveButton = findViewById(R.id.editSaveButton);

        // QSLRecordStr stores time_on as "YYYYMMDD-HHMMSS" — split into separate fields.
        String[] onParts = splitDateTime(record.getTime_on());
        String[] offParts = splitDateTime(record.getTime_off());

        editCall.setText(record.getCall());
        editGridsquare.setText(record.getGridsquare());
        editMode.setText(record.getMode());
        editRstSent.setText(safeReport(record.getRst_sent()));
        editRstRcvd.setText(safeReport(record.getRst_rcvd()));
        editQsoDate.setText(onParts[0]);
        editTimeOn.setText(onParts[1]);
        editQsoDateOff.setText(offParts[0]);
        editTimeOff.setText(offParts[1]);
        editBand.setText(record.getBand());
        editFreq.setText(record.getFreq());
        editStationCallsign.setText(record.getStation_callsign());
        editMyGridsquare.setText(record.getMy_gridsquare());
        editComment.setText(record.getComment() == null ? "" : record.getComment());
        editIsQsl.setChecked(record.isQSL);

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dismiss();
            }
        });

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ContentValues values = new ContentValues();
                values.put("call", editCall.getText().toString().trim().toUpperCase());
                values.put("gridsquare", editGridsquare.getText().toString().trim());
                values.put("mode", editMode.getText().toString().trim().toUpperCase());
                values.put("rst_sent", editRstSent.getText().toString().trim());
                values.put("rst_rcvd", editRstRcvd.getText().toString().trim());
                values.put("qso_date", editQsoDate.getText().toString().trim());
                values.put("time_on", editTimeOn.getText().toString().trim());
                values.put("qso_date_off", editQsoDateOff.getText().toString().trim());
                values.put("time_off", editTimeOff.getText().toString().trim());
                values.put("band", editBand.getText().toString().trim());
                values.put("freq", editFreq.getText().toString().trim());
                values.put("station_callsign", editStationCallsign.getText().toString().trim().toUpperCase());
                values.put("my_gridsquare", editMyGridsquare.getText().toString().trim());
                values.put("comment", editComment.getText().toString());
                values.put("isQSL", editIsQsl.isChecked() ? 1 : 0);

                mainViewModel.databaseOpr.updateQSLRecord(record.id, values);

                // Update the in-memory record so the row reflects the change without a full re-query.
                record.setCall(values.getAsString("call"));
                record.setGridsquare(values.getAsString("gridsquare"));
                record.setMode(values.getAsString("mode"));
                record.setRst_sent(values.getAsString("rst_sent"));
                record.setRst_rcvd(values.getAsString("rst_rcvd"));
                record.setTime_on(values.getAsString("qso_date") + "-" + values.getAsString("time_on"));
                record.setTime_off(values.getAsString("qso_date_off") + "-" + values.getAsString("time_off"));
                record.setBand(values.getAsString("band"));
                record.setFreq(values.getAsString("freq"));
                record.setStation_callsign(values.getAsString("station_callsign"));
                record.setMy_gridsquare(values.getAsString("my_gridsquare"));
                record.setComment(values.getAsString("comment"));
                record.isQSL = editIsQsl.isChecked();

                if (onSaved != null) onSaved.onSaved(record);
                dismiss();
            }
        });
    }

    private static String[] splitDateTime(String combined) {
        if (combined == null) return new String[]{"", ""};
        int dash = combined.indexOf('-');
        if (dash < 0) return new String[]{combined, ""};
        return new String[]{combined.substring(0, dash), combined.substring(dash + 1)};
    }

    private static String safeReport(String s) {
        if (s == null) return "";
        if (s.equals("-120") || s.equals("-100")) return "";
        return s;
    }

    @Override
    public void show() {
        super.show();
        WindowManager.LayoutParams params = getWindow().getAttributes();
        int width = getWindow().getWindowManager().getDefaultDisplay().getWidth();
        int height = getWindow().getWindowManager().getDefaultDisplay().getHeight();
        params.width = (int) (width * 0.9);
        params.height = (int) (height * 0.85);
        getWindow().setAttributes(params);
    }
}
