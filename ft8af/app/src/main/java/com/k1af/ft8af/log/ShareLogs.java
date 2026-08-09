package com.k1af.ft8af.log;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.ui.ToastMessage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class ShareLogs {
    private static final String TAG = "ShareLogs";
    private boolean isCancel=false;

    /** Request cancellation of an in-flight export. */
    public void cancelShare() {
        isCancel = true;
    }

    private String makeSQL(int queryFilter, String dateStart, String dateEnd) {
        String filterStr;
        switch (queryFilter) {
            case 1:
                filterStr = "and((q.isQSL =1)or(q.isLotW_QSL =1))\n";
                break;
            case 2:
                filterStr = "and((q.isQSL =0)and(q.isLotW_QSL =0))\n";
                break;
            default:
                filterStr = "";
        }
        String dateClause = "";
        if (dateStart != null && !dateStart.isEmpty()) {
            dateClause += " and q.qso_date >= ?\n";
        }
        if (dateEnd != null && !dateEnd.isEmpty()) {
            dateClause += " and q.qso_date <= ?\n";
        }
        return " FROM QSLTable AS q \n" +
                "WHERE ((CALL LIKE ?)OR(station_callsign LIKE ?))\n" +
                filterStr + dateClause;
    }

    private String[] buildArgs(String queryKey, String dateStart, String dateEnd) {
        String key = "%" + queryKey + "%";
        ArrayList<String> args = new ArrayList<>();
        args.add(key);
        args.add(key);
        if (dateStart != null && !dateStart.isEmpty()) args.add(dateStart);
        if (dateEnd != null && !dateEnd.isEmpty()) args.add(dateEnd);
        return args.toArray(new String[0]);
    }

    @SuppressLint("Range")
    public int getCount(SQLiteDatabase db, String queryKey, int queryFilter,
                        String dateStart, String dateEnd) {
        String sql = makeSQL(queryFilter, dateStart, dateEnd);
        Cursor cursor = db.rawQuery("SELECT COUNT(*) AS C " + sql,
                buildArgs(queryKey, dateStart, dateEnd));
        cursor.moveToFirst();
        int count = cursor.getInt(cursor.getColumnIndex("C"));
        cursor.close();
        return count;
    }

    private Cursor getData(SQLiteDatabase db, String queryKey, int queryFilter,
                           String dateStart, String dateEnd) {
        String sql = makeSQL(queryFilter, dateStart, dateEnd);
        return db.rawQuery("SELECT * " + sql,
                buildArgs(queryKey, dateStart, dateEnd));
    }

    /**
     * Write log data to a file for sharing and other processing
     *
     * @param db             database
     * @param queryKey       keyword
     * @param queryFilter    filter condition
     * @param adiFile        temporary file
     * @param isSWL          whether in SWL mode
     * @param onGetShareLogs callback
     */
    @SuppressLint({"DefaultLocale", "Range"})
    public void downQSLTableToFile(SQLiteDatabase db, String queryKey, int queryFilter, File adiFile
            , boolean isSWL
            , OnShareLogEvents onGetShareLogs) {
        downQSLTableToFile(db, queryKey, queryFilter, null, null, adiFile, isSWL, onGetShareLogs);
    }

    @SuppressLint({"DefaultLocale", "Range"})
    public void downQSLTableToFile(SQLiteDatabase db, String queryKey, int queryFilter,
                                    String dateStart, String dateEnd, File adiFile,
                                    boolean isSWL, OnShareLogEvents onGetShareLogs) {
        final int count = getCount(db, queryKey, queryFilter, dateStart, dateEnd);

        if (onGetShareLogs != null) {
            onGetShareLogs.onShareStart(count, String.format(
                    GeneralVariables.getStringFromResource(R.string.total_logs)
                    , count));
        }
        Cursor cursor = getData(db, queryKey, queryFilter, dateStart, dateEnd);
        FileOutputStream fileOutputStream = null;
        int position = 0;
        try {
            fileOutputStream = new FileOutputStream(adiFile, true);
            fileOutputStream.write(AdifRecord.HEADER.getBytes(StandardCharsets.UTF_8));
            cursor.moveToPosition(-1);
            while (cursor.moveToNext()) {
                position++;
                if (onGetShareLogs != null) {
                   if (!onGetShareLogs.onShareProgress(count, position
                            , String.format(GeneralVariables.getStringFromResource(R.string.get_log_no)
                                    , position))){
                       break;
                   };
                }
                // Build the record through the shared AdifRecord emitter so the bulk export and
                // the incremental ft8af_log.adi append (AdifLogFile) can never drift.
                fileOutputStream.write(recordFromCursor(cursor, isSWL).toBytes());
            }


        } catch (IOException e) {
            Log.e(TAG,String.format("Error writing file: %s",e.getMessage()));
            ToastMessage.show(String.format(GeneralVariables
                    .getStringFromResource(R.string.write_file_error), e.getMessage()));
        } finally {
            try {
                if (fileOutputStream != null) {
                    fileOutputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, String.format("Error closing file writer: %s", e.getMessage()));
                ToastMessage.show(String.format(GeneralVariables
                        .getStringFromResource(R.string.write_file_error), e.getMessage()));
            }
            cursor.close();
        }

        if (onGetShareLogs != null) {
            onGetShareLogs.afterGet(count, String.format(
                    GeneralVariables.getStringFromResource(R.string.total_logs)
                    , position));
        }
        Log.d(TAG, String.format("Wrote %d records", position));
    }

    /**
     * Share file
     *
     * @param context Context
     * @param file    file object
     * @param title   title
     */
    public void doShareLogs(Context context, File file, String title
            , SQLiteDatabase db, String queryKey, int queryFilter, File adiFile
            , boolean isSWL
            , OnShareLogEvents onGetShareLogs) {
        doShareLogs(context, file, title, db, queryKey, queryFilter, null, null,
                adiFile, isSWL, onGetShareLogs);
    }

    public void doShareLogs(Context context, File file, String title
            , SQLiteDatabase db, String queryKey, int queryFilter
            , String dateStart, String dateEnd, File adiFile
            , boolean isSWL
            , OnShareLogEvents onGetShareLogs) {

        isCancel=false;

        downQSLTableToFile(db, queryKey, queryFilter, dateStart, dateEnd, adiFile, false, new OnShareLogEvents() {
            @Override
            public void onPreparing(String info) {
                if (onGetShareLogs!=null){
                    onGetShareLogs.onPreparing(info);
                }
            }

            @Override
            public void onShareStart(int count, String info) {
                if (onGetShareLogs != null) {
                    onGetShareLogs.onShareStart(count, info);
                }
            }

            @Override
            public boolean onShareProgress(int count, int position, String info) {
                if (onGetShareLogs != null) {
                    boolean temp=onGetShareLogs.onShareProgress(count, position, info);
                    isCancel=!temp;
                   return temp;
                }
                return true;
            }

            @Override
            public void afterGet( int count, String info) {
                if (onGetShareLogs != null) {
                    onGetShareLogs.afterGet( count, info);
                }
                if (!isCancel) {
                    Intent sharingIntent = new Intent(Intent.ACTION_SEND);
                    Uri fileUri = FileProvider.getUriForFile(context.getApplicationContext()
                            , "radio.ks3ckc.ft8af.fileprovider", file);
                    sharingIntent.setType("application/octet-stream");
                    sharingIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                    sharingIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    context.startActivity(Intent.createChooser(sharingIntent, title));
                }
            }

            @Override
            public void onShareFailed(String info) {
                if (onGetShareLogs!=null){
                    onGetShareLogs.onShareFailed(info);
                }
            }
        });


    }

    /**
     * Copy {@code source} into the user's Downloads/FT8AF directory.
     * Returns the user-visible relative path on success, or null on failure.
     */
    public static String saveToDownloads(Context context, File source, String displayName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/FT8AF");
                Uri uri = context.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return null;
                try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                     FileInputStream is = new FileInputStream(source)) {
                    if (os == null) return null;
                    copyStream(is, os);
                }
                return "Download/FT8AF/" + displayName;
            } else {
                File downloads = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "FT8AF");
                if (!downloads.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    downloads.mkdirs();
                }
                File out = new File(downloads, displayName);
                try (FileInputStream is = new FileInputStream(source);
                     FileOutputStream os = new FileOutputStream(out)) {
                    copyStream(is, os);
                }
                return "Download/FT8AF/" + displayName;
            }
        } catch (IOException e) {
            Log.e(TAG, "saveToDownloads failed: " + e.getMessage());
            return null;
        } catch (SecurityException se) {
            Log.e(TAG, "saveToDownloads denied: " + se.getMessage());
            return null;
        }
    }

    private static void copyStream(java.io.InputStream in, OutputStream out) throws IOException {
        if (in == null || out == null) return;
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    /**
     * Build the {@link AdifRecord} for the cursor's current row. Shared with the incremental
     * {@code ft8af_log.adi} append so bulk export and real-time export emit identical records.
     */
    @SuppressLint("Range")
    static AdifRecord recordFromCursor(android.database.Cursor cursor, boolean isSWL) {
        AdifRecord record = new AdifRecord()
                .call(col(cursor, "call"))
                .swl(isSWL)
                .gridsquare(col(cursor, "gridsquare"))
                .mode(col(cursor, "mode"))
                .rstSent(col(cursor, "rst_sent"))
                .rstRcvd(col(cursor, "rst_rcvd"))
                .qsoDate(col(cursor, "qso_date"))
                .timeOn(col(cursor, "time_on"))
                .qsoDateOff(col(cursor, "qso_date_off"))
                .timeOff(col(cursor, "time_off"))
                .band(col(cursor, "band"))
                .freq(col(cursor, "freq"))
                .stationCallsign(col(cursor, "station_callsign"))
                .myGridsquare(col(cursor, "my_gridsquare"))
                .operator(col(cursor, "operator"))
                // POTA fields. Only emitted when populated so non-POTA QSOs stay clean.
                .mySig(col(cursor, "my_sig"))
                .mySigInfo(col(cursor, "my_sig_info"))
                .sig(col(cursor, "sig"))
                .sigInfo(col(cursor, "sig_info"))
                // Operator position, when the QSO was logged with one.
                .myLat(colDouble(cursor, "my_lat"))
                .myLon(colDouble(cursor, "my_lon"))
                // comment is always emitted (null renders as an empty field), as before.
                .comment(col(cursor, "comment"));
        if (!isSWL) {
            record.lotwQsl(colInt(cursor, "isLotW_QSL") == 1)
                    .manualQsl(colInt(cursor, "isQSL") == 1);
        }
        return record;
    }

    /** Cursor string value, or null when the column is absent (pre-migration DB) or null. */
    private static String col(android.database.Cursor cursor, String column) {
        int idx = cursor.getColumnIndex(column);
        if (idx < 0) return null;
        return cursor.getString(idx);
    }

    /** Nullable double read — absent column and SQL NULL both mean "no position". */
    private static Double colDouble(android.database.Cursor cursor, String column) {
        int idx = cursor.getColumnIndex(column);
        if (idx < 0 || cursor.isNull(idx)) return null;
        return cursor.getDouble(idx);
    }

    /** Cursor int value, or 0 when the column is absent. */
    private static int colInt(android.database.Cursor cursor, String column) {
        int idx = cursor.getColumnIndex(column);
        if (idx < 0) return 0;
        return cursor.getInt(idx);
    }
}
