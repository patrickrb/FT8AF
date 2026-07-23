package com.k1af.ft8af.log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;

public class ImportSharedLogs {
    private static final String TAG = "ImportSharedLogs";
    // Cap shared file size so a hostile (or just oversized) .adi opened via the VIEW intent
    // cannot OOM the app. 50 MB comfortably fits a real-world lifetime ADIF log.
    private static final int MAX_IMPORT_BYTES = 50 * 1024 * 1024;
    // Reject individual ADIF field lengths above this to avoid pathological allocations in
    // substring() when a record claims a multi-megabyte field.
    private static final int MAX_ADIF_FIELD_LEN = 64 * 1024;
    //private final Uri uri;
    private String fileContext;
    private final MainViewModel mainViewModel;
    private InputStream logFileStream = null;
    //private final HashMap<Integer,String> errorLines=new HashMap<>();

    public ImportSharedLogs(MainViewModel mainViewModel) throws IOException {
        this.mainViewModel = mainViewModel;
    }

    private boolean loadData(OnShareLogEvents onShareLogEvents) {
        if (logFileStream == null) {
            fileContext = "";
            return false;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        try {
            int n;
            while ((n = logFileStream.read(chunk)) > 0) {
                total += n;
                if (total > MAX_IMPORT_BYTES) {
                    if (onShareLogEvents != null) {
                        onShareLogEvents.onShareFailed(String.format(
                                GeneralVariables.getStringFromResource(R.string.import_share_failed)
                                , "file exceeds " + (MAX_IMPORT_BYTES / (1024 * 1024)) + " MB limit"));
                    }
                    return false;
                }
                buffer.write(chunk, 0, n);
            }
            fileContext = buffer.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            if (onShareLogEvents != null) {
                onShareLogEvents.onShareFailed(String.format(
                        GeneralVariables.getStringFromResource(R.string.import_share_failed)
                        , e.getMessage()));
            }
            return false;
        }
        return true;
    }

    public String getLogBody() {
        String[] temp = fileContext.split("[<][Ee][Oo][Hh][>]");
        if (temp.length > 1) {
            return temp[temp.length - 1];
        } else {
            return "";
        }
    }

    /**
     * Gets all records from the log file. Each record is stored as a HashMap where the Key is the field name (uppercase) and the Value is the value.
     *
     * @return Record list. ArrayList
     */
    public ArrayList<HashMap<String, String>> getLogRecords() {
        return parseLogRecords(getLogBody());
    }

    /**
     * Parse ADIF records from a log body (the text following the header's
     * {@code <EOH>}). Each record becomes a HashMap keyed by upper-case field
     * name.
     *
     * <p>Extracted as a static, Android-free helper so the field-length
     * clamping can be unit-tested directly. It mirrors {@link LogFileImport}'s
     * parser: when a field declares a length longer than the value actually
     * present (a truncated or hand-edited record), the length is clamped to the
     * value that is there and only then used for {@code substring}. The previous
     * code clamped to {@code length() - 1}, which silently dropped the last
     * character of such values and — for a zero-length value — made
     * {@code substring(0, -1)} throw {@link StringIndexOutOfBoundsException},
     * discarding the whole record.
     *
     * @param logBody raw record text (after {@code <EOH>})
     * @return parsed records
     */
    static ArrayList<HashMap<String, String>> parseLogRecords(String logBody) {
        String[] temp = logBody.split("[<][Ee][Oo][Rr][>]");//Extract the raw content of each record
        ArrayList<HashMap<String, String>> records = new ArrayList<>();
        for (String s : temp) {//Parse each raw record content
            if (!s.contains("<")) {
                continue;
            }//No tags found, skip parsing
            try {
                HashMap<String, String> record = new HashMap<>();//Create a record
                String[] fields = s.split("<");//Split each field of the record

                for (String field : fields) {//Parse each raw record

                    if (field.length() > 1) {//If it can be parsed
                        // Split at the FIRST '>' only: <NAME:LEN[:TYPE]> then the value.
                        // The value's length is governed by the declared LEN, so it may
                        // itself contain '>' (e.g. free-text COMMENT/NOTES); splitting on
                        // every '>' truncated such values at the first interior one.
                        int gt = field.indexOf('>');//End of the field header

                        if (gt > 0) {//If it can be parsed
                            String header = field.substring(0, gt);//NAME:LEN[:TYPE]
                            String rawValue = field.substring(gt + 1);//Everything after the header
                            if (header.contains(":")) {//Split field name and field length; field name before colon, length after
                                String[] ttt = header.split(":");
                                if (ttt.length > 1) {
                                    String name = ttt[0];//Field name
                                    int valueLen = Integer.parseInt(ttt[1]);//Field length
                                    if (valueLen > MAX_ADIF_FIELD_LEN) {
                                        continue;//Skip pathological field lengths
                                    }
                                    if (valueLen > 0) {
                                        String value = extractFieldValue(rawValue, valueLen);//Field value
                                        record.put(name.toUpperCase(), value);//Save field, key must be uppercase
                                    }
                                }

                            }
                        }
                    }
                }
                records.add(record);//Save record
            } catch (Exception e) {
                //errorLines.put(count,s);//Save the erroneous content.
                //importTask.readErrorCount=errorLines.size();
            }
        }
        return records;
    }

    /**
     * Slice an ADIF field value to its declared length, clamping the length down to the
     * characters actually present. ADIF stores a field as {@code <NAME:LEN>VALUE}, and the
     * declared LEN can exceed the value that follows when a record is truncated or the writer
     * padded the length. In that case the whole (shorter) value must be kept — the previous
     * {@code values[1].length() - 1} clamp silently dropped the last character (turning a value
     * such as "FN31" into "FN3"), and reduced a single-character value to "". This mirrors the
     * clamp used by {@link LogFileImport#getLogRecords()}.
     *
     * @param raw         the raw field value (everything after the first {@code >} up to the
     *                    next {@code <})
     * @param declaredLen the length declared in the field header (already known to be &gt; 0)
     * @return the value clamped to the declared length or to the raw length, whichever is smaller
     */
    static String extractFieldValue(String raw, int declaredLen) {
        int len = Math.min(declaredLen, raw.length());
        return len > 0 ? raw.substring(0, len) : "";
    }

    public void doImport(InputStream logFileStream, OnShareLogEvents onShareLogEvents) {
        this.logFileStream = logFileStream;

        new Thread(new Runnable() {
            @Override
            public void run() {
                //Load data
                if (onShareLogEvents != null) {
                    onShareLogEvents.onPreparing(GeneralVariables.getStringFromResource(R.string.preparing_import_logs));
                }
                if (!loadData(onShareLogEvents)) {
                    return;
                }

                int position = 0;
                ArrayList<HashMap<String, String>> recordList = getLogRecords();//Split by regex: [<][Ee][Oo][Rr][>]
                int count = recordList.size();

                if (onShareLogEvents != null) {
                    onShareLogEvents.onShareStart(count, String.format(
                            GeneralVariables.getStringFromResource(R.string.total_logs)
                            , count));
                }


                for (HashMap<String, String> record : recordList) {
                    position++;
                    QSLRecord qslRecord = new QSLRecord(record);

                    // Bulk import: don't append to ft8af_log.adi. These records typically
                    // originated elsewhere and re-appending would double-count them if the
                    // same rows are later re-exported. (Real-time mirroring is for on-air /
                    // web-logged QSOs only.)
                    mainViewModel.databaseOpr.doInsertQSLData(qslRecord, null, false);

                    if (onShareLogEvents != null) {
                        if (!onShareLogEvents.onShareProgress(count, position
                                , String.format(GeneralVariables.getStringFromResource(R.string.share_logs_been_read)
                                        , position))) {
                            break;
                        }
                    }


                }

                if (onShareLogEvents != null) {
                    onShareLogEvents.afterGet(count, String.format(
                            GeneralVariables.getStringFromResource(R.string.total_logs)
                            , position));
                }

            }
        }).start();
    }


    public String getFileContext() {
        return fileContext;
    }
}
