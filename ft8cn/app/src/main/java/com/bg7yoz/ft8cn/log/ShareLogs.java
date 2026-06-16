package com.bg7yoz.ft8cn.log;

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

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.ui.ToastMessage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
            fileOutputStream.write("FT8AF ADIF Export<eoh>\n".getBytes());
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
                String call = cursor.getString(cursor.getColumnIndex("call"));
                fileOutputStream.write(AdifFormat.callField(call).getBytes());
                if (!isSWL) {
                    if (cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1) {
                        fileOutputStream.write("<QSL_RCVD:1>Y ".getBytes());
                    } else {
                        fileOutputStream.write("<QSL_RCVD:1>N ".getBytes());
                    }
                    if (cursor.getInt(cursor.getColumnIndex("isQSL")) == 1) {
                        fileOutputStream.write("<QSL_MANUAL:1>Y ".getBytes());
                    } else {
                        fileOutputStream.write("<QSL_MANUAL:1>N ".getBytes());
                    }
                } else {
                    fileOutputStream.write("<swl:1>Y ".getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("gridsquare")) != null) {
                    fileOutputStream.write(String.format("<gridsquare:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("gridsquare")).length()
                            , cursor.getString(cursor.getColumnIndex("gridsquare"))).getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("mode")) != null) {
                    fileOutputStream.write(String.format("<mode:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("mode")).length()
                            , cursor.getString(cursor.getColumnIndex("mode"))).getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("rst_sent")) != null) {
                    fileOutputStream.write(String.format("<rst_sent:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("rst_sent")).length()
                            , cursor.getString(cursor.getColumnIndex("rst_sent"))).getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("rst_rcvd")) != null) {
                    fileOutputStream.write(String.format("<rst_rcvd:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("rst_rcvd")).length()
                            , cursor.getString(cursor.getColumnIndex("rst_rcvd"))).getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("qso_date")) != null) {
                    fileOutputStream.write(String.format("<qso_date:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("qso_date")).length()
                            , cursor.getString(cursor.getColumnIndex("qso_date"))).getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("time_on")) != null) {
                    fileOutputStream.write(String.format("<time_on:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("time_on")).length()
                            , cursor.getString(cursor.getColumnIndex("time_on"))).getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("qso_date_off")) != null) {
                    fileOutputStream.write( String.format("<qso_date_off:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("qso_date_off")).length()
                            , cursor.getString(cursor.getColumnIndex("qso_date_off"))).getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("time_off")) != null) {
                    fileOutputStream.write(String.format("<time_off:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("time_off")).length()
                            , cursor.getString(cursor.getColumnIndex("time_off"))).getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("band")) != null) {
                    fileOutputStream.write(String.format("<band:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("band")).length()
                            , cursor.getString(cursor.getColumnIndex("band"))).getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("freq")) != null) {
                    fileOutputStream.write(String.format("<freq:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("freq")).length()
                            , cursor.getString(cursor.getColumnIndex("freq"))).getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("station_callsign")) != null) {
                    fileOutputStream.write(String.format("<station_callsign:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("station_callsign")).length()
                            , cursor.getString(cursor.getColumnIndex("station_callsign"))).getBytes());
                }

                if (cursor.getString(cursor.getColumnIndex("my_gridsquare")) != null) {
                    fileOutputStream.write(String.format("<my_gridsquare:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("my_gridsquare")).length()
                            , cursor.getString(cursor.getColumnIndex("my_gridsquare"))).getBytes());
                }

                if (cursor.getColumnIndex("operator") != -1) {
                    if (cursor.getString(cursor.getColumnIndex("operator")) != null) {
                        fileOutputStream.write(String.format("<operator:%d>%s "
                                , cursor.getString(cursor.getColumnIndex("operator")).length()
                                , cursor.getString(cursor.getColumnIndex("operator"))).getBytes());
                    }
                }
                // POTA fields. Only emit when populated so non-POTA QSOs stay
                // byte-identical to the prior export format.
                writePotaField(fileOutputStream, cursor, "my_sig", "MY_SIG");
                writePotaField(fileOutputStream, cursor, "my_sig_info", "MY_SIG_INFO");
                writePotaField(fileOutputStream, cursor, "sig", "SIG");
                writePotaField(fileOutputStream, cursor, "sig_info", "SIG_INFO");
                String comment = cursor.getString(cursor.getColumnIndex("comment"));
                if (comment == null) comment = "";

                //<comment:15>Distance: 99 km <eor>
                //When writing to the database, be sure to append " km"
                fileOutputStream.write(String.format("<comment:%d>%s <eor>\n"
                        , comment.length()
                        , comment).getBytes());
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
     * Emit a single ADIF field from a cursor column. Silently skips when the
     * column doesn't exist (pre-migration DB) or the value is null/empty so
     * POTA fields stay invisible for ordinary non-POTA contacts.
     */
    private static void writePotaField(
            FileOutputStream out, android.database.Cursor cursor,
            String column, String adifName) throws IOException {
        int idx = cursor.getColumnIndex(column);
        if (idx < 0) return;
        String value = cursor.getString(idx);
        if (value == null || value.isEmpty()) return;
        // ADIF length is in bytes; value.length() (UTF-16 code units) would mis-tag any
        // non-ASCII content and misalign the following field.
        byte[] valueBytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write(String.format("<%s:%d>%s ", adifName, valueBytes.length, value)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
