package com.k1af.ft8af.log;

import android.util.Log;

import com.k1af.ft8af.html.ImportTaskList;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Log file import.
 * The constructor requires a log file name; the file here is posted from NanoHTTPd's session.
 * getFileContext returns the entire file content.
 * getLogBody returns all raw record content from the log file, i.e., all data after the &lt;eoh&gt; tag.
 * getLogRecords returns a list of all parsed records stored as HashMaps, where the Key is the field name (uppercase) and the value is the actual value.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

public class LogFileImport {
    private static final String TAG = "LogFileImport";

    // Defensive cap on a single import. This path is reachable from the built-in web-logger
    // HTTP upload (LogHttpServer -> new LogFileImport), so an unbounded read of a huge/hostile
    // upload could OOM the app. 50 MB comfortably fits a real-world lifetime ADIF log, matching
    // the sibling ImportSharedLogs.MAX_IMPORT_BYTES cap.
    static final int MAX_IMPORT_BYTES = 50 * 1024 * 1024;

    private final String fileContext;
    private final HashMap<Integer,String> errorLines=new HashMap<>();
    private ImportTaskList.ImportTask importTask;

    /**
     * Constructor; requires a file name. If an error occurs while reading the file, the exception is rethrown.
     *
     * @param logFileName log file name
     * @throws IOException rethrown exception
     */
    public LogFileImport(ImportTaskList.ImportTask task, String logFileName) throws IOException {
        importTask=task;
        try (FileInputStream logFileStream = new FileInputStream(logFileName)) {
            fileContext = readFully(logFileStream);
        }
    }

    /**
     * Read a stream to its end into a UTF-8 string.
     *
     * <p>The previous implementation sized a single buffer from {@link InputStream#available()}
     * and ignored the return value of a lone {@code read(byte[])} call. Neither is a reliable
     * measure of a stream's length: {@code available()} is only an estimate, and one
     * {@code read} is permitted to return fewer bytes than requested. On a large ADIF log this
     * left the tail of the buffer as NUL bytes, silently garbling or dropping the last records.
     * Reading in a loop until EOF (mirroring {@link ImportSharedLogs}) fixes this. The loop is
     * bounded by {@link #MAX_IMPORT_BYTES}: a stream larger than the cap throws {@link IOException}
     * rather than growing the buffer unbounded, since this path is reachable from an HTTP upload.
     *
     * @param stream input to drain (not closed here — the caller owns it)
     * @return the full stream contents decoded as UTF-8
     * @throws IOException if the underlying read fails
     */
    static String readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = stream.read(chunk)) > 0) {
            total += n;
            if (total > MAX_IMPORT_BYTES) {
                throw new IOException("log file exceeds "
                        + (MAX_IMPORT_BYTES / (1024 * 1024)) + " MB limit");
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    /**
     * Get the entire content of the log file
     *
     * @return full text
     */
    public String getFileContext() {
        return fileContext;
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
        String[] temp = getLogBody().split("[<][Ee][Oo][Rr][>]");//Extract the raw content of each record
        ArrayList<HashMap<String, String>> records = new ArrayList<>();
        int count=0;//Parsing counter
        for (String s : temp) {//Parse each raw record content
            count++;
            if (!s.contains("<")) {
                continue;
            }//No tags found, skip parsing
            try {
                records.add(parseRecord(s));//Save record
            }catch (Exception e){
                errorLines.put(count,s.replace("<","&lt;"));//Save the erroneous content.
                importTask.readErrorCount=errorLines.size();
            }
        }
        return records;
    }

    /**
     * Parse one raw ADIF record (the text between two {@code <eor>} markers) into a
     * field map keyed by upper-case field name.
     *
     * <p>Extracted as a static, Android-free helper so the field parsing can be
     * unit-tested without the file-reading constructor. Each field is
     * {@code <NAME:LEN[:TYPE]>VALUE}; the value is sliced to the declared LEN.
     * Splitting only at the <em>first</em> {@code '>'} (rather than on every
     * {@code '>'} and keeping the second token) means a value that legally
     * contains {@code '>'} — free-text COMMENT/NOTES and similar — is no longer
     * truncated at its first interior {@code '>'}.
     *
     * @param rawRecord raw record text, e.g. {@code <call:5>K1ABC<mode:3>FT8}
     * @return the parsed fields (may be empty when nothing parses)
     */
    static HashMap<String, String> parseRecord(String rawRecord) {
        HashMap<String, String> record = new HashMap<>();//Create a record
        String[] fields = rawRecord.split("<");//Split each field of the record

        for (String field : fields) {//Parse each raw record

            if (field.length() > 1) {//If it can be parsed
                int gt = field.indexOf('>');//End of the field header

                if (gt > 0) {//If it can be parsed
                    String header = field.substring(0, gt);//NAME:LEN[:TYPE]
                    String rawValue = field.substring(gt + 1);//Everything after the header
                    if (header.contains(":")) {//Split field name and field length; field name before colon, length after
                        String[] ttt = header.split(":");
                        if (ttt.length > 1) {
                            String name = ttt[0];//Field name
                            int valueLen = Integer.parseInt(ttt[1]);//Field length (UTF-8 bytes)
                            if (valueLen > 0) {
                                // LEN counts UTF-8 bytes (see AdifFormat.utf8Length), not
                                // UTF-16 chars: slicing by chars over-reads past a
                                // non-ASCII value into the whitespace that follows it.
                                String value = AdifFormat.sliceByUtf8Length(rawValue, valueLen);//Field value
                                if (!value.isEmpty()) {
                                    record.put(name.toUpperCase(Locale.US), value);//Save field, key must be uppercase
                                }
                            }
                        }

                    }
                }
            }
        }
        return record;
    }
    public int getErrorCount(){
        return errorLines.size();
    }
    public HashMap<Integer,String> getErrorLines(){
        return errorLines;
    }
}
