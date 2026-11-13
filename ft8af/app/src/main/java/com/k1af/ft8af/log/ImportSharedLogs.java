package com.k1af.ft8af.log;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class ImportSharedLogs {
    private static final String TAG = "ImportSharedLogs";
    // Reject individual ADIF field lengths above this to avoid pathological allocations in
    // substring() when a record claims a multi-megabyte field.
    private static final int MAX_ADIF_FIELD_LEN = 64 * 1024;
    private final MainViewModel mainViewModel;

    public ImportSharedLogs(MainViewModel mainViewModel) throws IOException {
        this.mainViewModel = mainViewModel;
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
                records.add(parseSingleRecord(s));
            } catch (Exception e) {
                //errorLines.put(count,s);//Save the erroneous content.
                //importTask.readErrorCount=errorLines.size();
            }
        }
        return records;
    }

    /**
     * Parse one raw ADIF record (the text between two {@code <eor>} markers) into a
     * field map keyed by upper-case field name. Shared by {@link #parseLogRecords}
     * (whole-body-at-once) and {@link AdifStreamReader} (streaming, one record at a
     * time) -- extracted so both paths parse a record identically.
     *
     * @param rawRecord raw record text, e.g. {@code <call:5>K1ABC<mode:3>FT8}
     * @return the parsed fields (may be empty when nothing parses)
     */
    static HashMap<String, String> parseSingleRecord(String rawRecord) {
        HashMap<String, String> record = new HashMap<>();//Create a record
        String[] fields = rawRecord.split("<");//Split each field of the record

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
                                record.put(name.toUpperCase(Locale.US), value);//Save field, key must be uppercase
                            }
                        }

                    }
                }
            }
        }
        return record;
    }

    /**
     * Slice an ADIF field value to its declared length, clamping the length down to what is
     * actually present. ADIF stores a field as {@code <NAME:LEN>VALUE} where LEN counts UTF-8
     * <em>bytes</em> (see {@link AdifFormat#utf8Length}), and the declared LEN can exceed the
     * value that follows when a record is truncated or the writer padded the length. In that
     * case the whole (shorter) value must be kept — the previous
     * {@code values[1].length() - 1} clamp silently dropped the last character (turning a value
     * such as "FN31" into "FN3"), and reduced a single-character value to "". Slicing by
     * {@link String#length()} chars instead of bytes over-read past non-ASCII values into the
     * whitespace that follows them. This mirrors the slicing used by
     * {@link LogFileImport#parseRecord}.
     *
     * @param raw         the raw field value (everything after the first {@code >} up to the
     *                    next {@code <})
     * @param declaredLen the UTF-8 byte length declared in the field header (already known to
     *                    be &gt; 0)
     * @return the value clamped to the declared byte length or to the raw length, whichever is
     *         smaller
     */
    static String extractFieldValue(String raw, int declaredLen) {
        return AdifFormat.sliceByUtf8Length(raw, declaredLen);
    }

    public void doImport(StreamOpener streamOpener, OnShareLogEvents onShareLogEvents) {
        doImport(streamOpener, onShareLogEvents, null);
    }

    /**
     * Import with an optional per-record insert observer. {@code afterInsertQSLData} is passed
     * straight through to {@link com.k1af.ft8af.database.DatabaseOpr#doInsertQSLData}, whose
     * callback distinguishes added / updated / invalid records — the in-app import UI uses it
     * to tally a summary. Callbacks fire on the import thread.
     *
     * <p>Streams the source twice via {@code streamOpener} (re-opening it, e.g. a {@code
     * content://} URI, rather than buffering it) -- once to count records for {@link
     * OnShareLogEvents#onShareStart}'s progress total, once to actually parse and insert each
     * record as it's read. Neither pass ever holds more than roughly one record's worth of text
     * in memory (see {@link AdifStreamReader}), unlike the previous whole-file-then-split
     * approach, which needed several times the raw file size in peak heap and could not fit a
     * real 87 MB ADIF log under this app's ~256 MB heap on a real device.
     */
    public void doImport(StreamOpener streamOpener, OnShareLogEvents onShareLogEvents,
                         com.k1af.ft8af.database.AfterInsertQSLData afterInsertQSLData) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (onShareLogEvents != null) {
                    onShareLogEvents.onPreparing(GeneralVariables.getStringFromResource(R.string.preparing_import_logs));
                }

                int count;
                try (InputStream counting = streamOpener.open();
                     AdifStreamReader counter = new AdifStreamReader(counting)) {
                    int c = 0;
                    while (counter.nextRecord() != null) {
                        c++;
                    }
                    count = c;
                } catch (IOException e) {
                    if (onShareLogEvents != null) {
                        onShareLogEvents.onShareFailed(String.format(
                                GeneralVariables.getStringFromResource(R.string.import_share_failed), e.getMessage()));
                    }
                    return;
                }

                if (onShareLogEvents != null) {
                    onShareLogEvents.onShareStart(count, String.format(
                            GeneralVariables.getStringFromResource(R.string.total_logs), count));
                }

                int position = 0;
                try (InputStream parsing = streamOpener.open();
                     AdifStreamReader records = new AdifStreamReader(parsing)) {
                    HashMap<String, String> record;
                    while ((record = records.nextRecord()) != null) {
                        position++;
                        QSLRecord qslRecord = new QSLRecord(record);

                        // Bulk import: don't append to ft8af_log.adi. These records typically
                        // originated elsewhere and re-appending would double-count them if the
                        // same rows are later re-exported. (Real-time mirroring is for on-air /
                        // web-logged QSOs only.)
                        mainViewModel.databaseOpr.doInsertQSLData(qslRecord, afterInsertQSLData, false);

                        if (onShareLogEvents != null) {
                            if (!onShareLogEvents.onShareProgress(count, position
                                    , String.format(GeneralVariables.getStringFromResource(R.string.share_logs_been_read)
                                            , position))) {
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    if (onShareLogEvents != null) {
                        onShareLogEvents.onShareFailed(String.format(
                                GeneralVariables.getStringFromResource(R.string.import_share_failed), e.getMessage()));
                    }
                    return;
                }

                if (onShareLogEvents != null) {
                    onShareLogEvents.afterGet(count, String.format(
                            GeneralVariables.getStringFromResource(R.string.total_logs)
                            , position));
                }
            }
        }).start();
    }
}
