package com.bg7yoz.ft8cn.log;

import android.util.Log;

import com.bg7yoz.ft8cn.html.ImportTaskList;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

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
            byte[] bytes = new byte[logFileStream.available()];
            logFileStream.read(bytes);
            fileContext = new String(bytes);
        }
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
                HashMap<String, String> record = new HashMap<>();//Create a record
                String[] fields = s.split("<");//Split each field of the record

                for (String field : fields) {//Parse each raw record

                    if (field.length() > 1) {//If it can be parsed
                        String[] values = field.split(">");//Split field name and value

                        if (values.length > 1) {//If it can be parsed
                            if (values[0].contains(":")) {//Split field name and field length; field name before colon, length after
                                String[] ttt = values[0].split(":");
                                if (ttt.length > 1) {
                                    String name = ttt[0];//Field name
                                    int valueLen = Integer.parseInt(ttt[1]);//Field length
                                    if (valueLen > 0) {
                                        if (values[1].length() < valueLen) {
                                            valueLen = values[1].length();
                                        }
                                        if (valueLen > 0) {
                                            String value = values[1].substring(0, valueLen);//Field value
                                            record.put(name.toUpperCase(), value);//Save field, key must be uppercase
                                        }
                                    }
                                }

                            }
                        }
                    }
                }
                records.add(record);//Save record
            }catch (Exception e){
                errorLines.put(count,s.replace("<","&lt;"));//Save the erroneous content.
                importTask.readErrorCount=errorLines.size();
            }
        }
        return records;
    }
    public int getErrorCount(){
        return errorLines.size();
    }
    public HashMap<Integer,String> getErrorLines(){
        return errorLines;
    }
}
