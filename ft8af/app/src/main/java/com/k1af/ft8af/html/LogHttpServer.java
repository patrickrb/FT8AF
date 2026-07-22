package com.k1af.ft8af.html;
/**
 * HTTP service content implementation. Database access does not require async operations.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import static com.k1af.ft8af.html.HtmlContext.HTML_STRING;

import android.annotation.SuppressLint;
import android.database.Cursor;
import android.util.Log;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.connector.CableSerialPort;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.database.AfterInsertQSLData;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.database.RigNameList;
import com.k1af.ft8af.log.LogFileImport;
import com.k1af.ft8af.log.QSLRecord;
import com.k1af.ft8af.maidenhead.MaidenheadGrid;
import com.k1af.ft8af.rigs.BaseRigOperation;
import com.k1af.ft8af.timer.UtcTimer;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import fi.iki.elonen.NanoHTTPD;

@SuppressWarnings("ConstantConditions")
public class LogHttpServer extends NanoHTTPD {
    private final MainViewModel mainViewModel;
    public static int DEFAULT_PORT = 7050;
    private static final String TAG = "LOG HTTP";

    private final ImportTaskList importTaskList = new ImportTaskList();//log import task list


    public LogHttpServer(MainViewModel viewModel, int port) {
        super(port);
        this.mainViewModel = viewModel;

    }

    /**
     * Number of pages needed to show {@code recordCount} rows at {@code pageSize} rows per page.
     *
     * <p>This is a ceiling division: {@code n} pages hold up to {@code n * pageSize} rows. The
     * old {@code Math.round(rc / pageSize + 0.5f)} form actually evaluated to
     * {@code floor(rc / pageSize) + 1} (because {@link Math#round(float)} is itself
     * {@code floor(x + 0.5)}, so the {@code + 0.5f} added a whole extra page), so an exact
     * multiple — e.g. 100 rows at the default 100 rows/page — reported one page too many and
     * the web logbook rendered a trailing empty page whose "last page" link led nowhere.
     *
     * <p>Always returns at least 1 so the page controls still render over an empty log (matching
     * the old behaviour for 0 rows), and a non-positive {@code pageSize} (a malformed
     * {@code ?pageSize=} query value) collapses to a single page instead of dividing by zero.
     */
    static int pageCount(int recordCount, int pageSize) {
        if (pageSize <= 0 || recordCount <= 0) {
            return 1;
        }
        return (recordCount + pageSize - 1) / pageSize;
    }

    /**
     * Parse an integer query-parameter value, falling back to {@code fallback} when the
     * value is {@code null} or not a valid integer.
     *
     * <p>The web-logbook handlers read {@code ?page=}, {@code ?pageSize=} and
     * {@code ?session=} straight off the request. NanoHTTPD's {@code ClientHandler}
     * swallows any exception thrown out of {@link #serve} — it logs it and closes the
     * socket — so a raw {@code Integer.parseInt} of a malformed value (a hand-edited URL,
     * a stale bookmark, or a truncated link) surfaced to the browser as an aborted/empty
     * response instead of the requested view. Routing every parse through here keeps a bad
     * value from throwing: the handler just uses its default (page 1, the default page
     * size, or an id that matches no import task).
     */
    static int parseQueryInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Clamp a 1-based page index to {@code [1, pageCount]}.
     *
     * <p>The three list handlers clamped only the high side
     * ({@code if (pageIndex > pageCount) pageIndex = pageCount}), so {@code ?page=0} or a
     * negative page left {@code pageIndex < 1} and the paged query's
     * {@code LIMIT (pageIndex - 1) * pageSize, pageSize} offset went negative — a wrong or
     * empty result set numbered from a negative "No." column. {@link #pageCount(int, int)}
     * always returns at least 1, so the clamped index is always a page that exists.
     */
    static int clampPageIndex(int pageIndex, int pageCount) {
        if (pageIndex < 1) {
            return 1;
        }
        return Math.min(pageIndex, pageCount);
    }

    /**
     * Clamp a page size parsed from a {@code ?pageSize=} query value to a safe positive size.
     *
     * <p>{@link #pageCount(int, int)} tolerates a non-positive size, but the raw value also
     * drives the paged {@code LIMIT (offset),(pageSize)} query and every navigation link. A
     * {@code pageSize} of 0 forces {@code LIMIT ...,0} (an always-empty table) and a negative
     * value hands SQLite an undefined/unbounded limit — and either leaks into the links. Fall
     * back to the default so the count, the query, and the links all agree on one sane size.
     */
    static int normalizePageSize(int pageSize) {
        return pageSize > 0 ? pageSize : 100;
    }

    /**
     * The request path segment at {@code index} (from {@code getUri().split("/")}),
     * or {@code null} when the request has no such segment.
     *
     * <p>Every {@code uriList[2]} read in {@link #serve} routes through this so a
     * request that omits the trailing segment falls back to the default page instead
     * of throwing {@link ArrayIndexOutOfBoundsException}. {@code GET /SHOWQSL} split to
     * {@code ["", "SHOWQSL"]} (length 2), so the old bare {@code uriList[2]} threw — and
     * for SHOWQSL that read sat <em>outside</em> the try/catch, so it escaped
     * {@code serve} uncaught; for the DOWNQSL / DOWNQSLNOQSL {@code Content-Disposition}
     * header it threw inside the try and returned an opaque HTTP 500 even though the
     * body branch had already fallen back correctly. Mirrors the {@code uriList.length
     * >= 3} guard the DELFOLLOW / DELQSL / DELQSLCALLSIGN branches already use.
     */
    static String uriSegment(String[] uriList, int index) {
        if (uriList == null || index < 0 || index >= uriList.length) {
            return null;
        }
        String segment = uriList[index];
        // Treat a present-but-empty segment (e.g. the "" that a double slash like
        // "/SHOWQSL//" splits to) as absent, so it falls back to the default page
        // rather than flowing an empty month into showQSLByMonth/downQSLByMonth
        // (where month.length() == 0 could match unintended rows).
        return segment == null || segment.isEmpty() ? null : segment;
    }

    /**
     * {@code Content-Disposition} filename for a per-month QSL download. Falls back to
     * {@code log.adi} when the month segment is absent (see {@link #uriSegment}), rather
     * than reading a missing path segment.
     */
    static String downloadFileName(String month) {
        return String.format("log%s.adi", month != null ? month : "");
    }

    /**
     * The temp-file path NanoHTTPD stored for the {@code file1} upload part of a POST/PUT
     * to {@code /IMPORTLOGDATA}, or {@code null} when the request carried no such part.
     *
     * <p>{@link IHTTPSession#parseBody} fills the supplied map with one entry per uploaded
     * file, keyed by the form field name. A malformed or non-form request — or a bare LAN
     * probe of the always-on logbook port — leaves the map without {@code file1}. The
     * handler used to dereference the missing value directly
     * ({@code files.get("file1").hashCode()}), so a request without the part threw a
     * {@link NullPointerException}. That throw escaped {@link #serve} <em>before</em> its
     * try/catch (the {@code IMPORTLOGDATA} branch runs in the leading dispatch chain), so
     * NanoHTTPD's worker aborted the request with no useful response. Callers must treat
     * {@code null} as "no upload" and reject the request the way a non-POST method already
     * does. Mirrors the {@link #uriSegment} / {@link #parseQueryInt} guards that already
     * harden the sibling handlers in this file.
     */
    static String uploadedFilePath(Map<String, String> files) {
        return files == null ? null : files.get("file1");
    }

    @Override
    public Response serve(IHTTPSession session) {
        String[] uriList = session.getUri().split("/");
        String uri = "";
        String msg;
        Log.i(TAG, "serve uri: " + session.getUri());

        if (uriList.length >= 2) {
            uri = uriList[1];
        }

        if (uri.equalsIgnoreCase("CONFIG")) {//Query configuration info
            msg = HTML_STRING(getConfig());
        } else if (uri.equalsIgnoreCase("showQSLCallsigns")) {//Show QSO callsigns, including last contact time
            msg = HTML_STRING(showQslCallsigns(session));
        } else if (uri.equalsIgnoreCase("DEBUG")) {//Query QSO callsigns
            msg = HTML_STRING(showDebug());
        } else if (uri.equalsIgnoreCase("SHOWHASH")) {//Query callsign hash table
            msg = HTML_STRING(showCallsignHash());
        } else if (uri.equalsIgnoreCase("NEWMESSAGE")) {//Query current cycle QSO message table
            msg = HTML_STRING(getNewMessages());
        } else if (uri.equalsIgnoreCase("MESSAGE")) {//Query saved SWL QSO message table
            return getMessages(session);
        } else if (uri.equalsIgnoreCase("QSOSWLMSG")) {//Query SWL QSO contact message table
            return getSWLQsoMessages(session);
        } else if (uri.equalsIgnoreCase("QSOLogs")) {//Query QSO logs
            return getQsoLogs(session);
        } else if (uri.equalsIgnoreCase("CALLSIGNGRID")) {//Query callsign-to-grid mapping
            msg = HTML_STRING(showCallGridList());
        } else if (uri.equalsIgnoreCase("GETCALLSIGNQTH")) {
            msg = HTML_STRING(getCallsignQTH(session));
        } else if (uri.equalsIgnoreCase("ALLTABLE")) {//Query all tables
            msg = HTML_STRING(getAllTableName());
        } else if (uri.equalsIgnoreCase("FOLLOWCALLSIGNS")) {//Query tracked callsigns
            msg = HTML_STRING(getFollowCallsigns());
        } else if (uri.equalsIgnoreCase("DELFOLLOW")) {//Delete tracked callsign
            if (uriList.length >= 3) {
                deleteFollowCallSign(uriList[2].replace("_", "/"));
            }
            msg = HTML_STRING(getFollowCallsigns());
        } else if (uri.equalsIgnoreCase("DELQSL")) {
            if (uriList.length >= 3) {
                deleteQSLByMonth(uriList[2].replace("_", "/"));
            }
            msg = HTML_STRING(showQSLTable());
        } else if (uri.equalsIgnoreCase("QSLCALLSIGNS")) {//Query QSO callsigns
            msg = HTML_STRING(getQSLCallsigns());
        } else if (uri.equalsIgnoreCase("QSLTABLE")) {
            msg = HTML_STRING(showQSLTable());
        } else if (uri.equalsIgnoreCase("IMPORTLOG")) {
            msg = HTML_STRING(showImportLog());
        } else if (uri.equalsIgnoreCase("GETIMPORTTASK")) {//URI for real-time import status retrieval
            msg = HTML_STRING(makeGetImportTaskHTML(session));
        } else if (uri.equalsIgnoreCase("CANCELTASK")) {//URI for canceling an import
            msg = HTML_STRING(doCancelImport(session));
        } else if (uri.equalsIgnoreCase("IMPORTLOGDATA")) {
            msg = HTML_STRING(doImportLogFile(session));
        } else if (uri.equalsIgnoreCase("SHOWALLQSL")) {
            msg = HTML_STRING(showAllQSL());
        } else if (uri.equalsIgnoreCase("SHOWQSL")) {
            String month = uriSegment(uriList, 2);
            msg = month != null ? HTML_STRING(showQSLByMonth(month)) : HtmlContext.DEFAULT_HTML();
        } else if (uri.equalsIgnoreCase("DELQSLCALLSIGN")) {//Delete a QSO callsign
            if (uriList.length >= 3) {
                deleteQSLCallSign(uriList[2].replace("_", "/"));
            }
            msg = HTML_STRING(getQSLCallsigns());
        } else {
            msg = HtmlContext.DEFAULT_HTML();
        }
        //return newFixedLengthResponse(msg);

        try {
            Response response;
            if (uri.equalsIgnoreCase("DOWNALLQSL")) {//Download logs
                msg = downAllQSl();
                response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", msg);
                response.addHeader("Content-Disposition", "attachment;filename=All_log.adi");
            } else if (uri.equalsIgnoreCase("DOWNQSL")) {
                String month = uriSegment(uriList, 2);
                if (month != null) {
                    msg = downQSLByMonth(month, true);
                    response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", msg);
                    response.addHeader("Content-Disposition", "attachment;filename=" + downloadFileName(month));
                } else {
                    // No month segment: serve the normal HTML page instead of a
                    // text/plain .adi attachment whose body is actually HTML.
                    response = newFixedLengthResponse(HtmlContext.DEFAULT_HTML());
                }

            } else if (uri.equalsIgnoreCase("DOWNQSLNOQSL")) {
                String month = uriSegment(uriList, 2);
                if (month != null) {
                    msg = downQSLByMonth(month, false);
                    response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", msg);
                    response.addHeader("Content-Disposition", "attachment;filename=" + downloadFileName(month));
                } else {
                    response = newFixedLengthResponse(HtmlContext.DEFAULT_HTML());
                }

            } else {
                response = newFixedLengthResponse(msg);
            }
            return response;//
        } catch (Exception exception) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, exception.getMessage());
        }
    }


    @SuppressLint("DefaultLocale")
    private String doImportLogFile(IHTTPSession session) {
        //Check if this is a POST log file request
        if (session.getMethod().equals(Method.POST)
                || session.getMethod().equals(Method.PUT)) {
            Map<String, String> files = new HashMap<>();
            //Map<String, String> header = session.getHeaders();
            try {
                session.parseBody(files);

                Log.e(TAG, "doImportLogFile: information:" + files.toString());
                String param = uploadedFilePath(files);//temp-file path of the file1 upload part (null if absent)
                if (param == null) {
                    // No file1 part: reject like an illegal command instead of NPE-ing on
                    // the missing upload (see uploadedFilePath).
                    return GeneralVariables.getStringFromResource(R.string.html_illegal_command);
                }

                ImportTaskList.ImportTask task = importTaskList.addTask(param.hashCode());//create a new task

                LogFileImport logFileImport = new LogFileImport(task, param);


                //Run the submitted data in a separate thread to prevent the web page from stalling too long
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        doImportADI(task, logFileImport);
                    }
                }).start();

                //Redirect to the real-time import status page
                return String.format("<head>\n<meta http-equiv=\"Refresh\" content=\"0; URL=getImportTask?session=%d\" /></head><body></body>"
                        , param.hashCode());

            } catch (IOException | ResponseException e) {
                e.printStackTrace();
                return String.format(GeneralVariables.getStringFromResource(R.string.html_import_failed)
                        , e.getMessage());
            }
        }
        return GeneralVariables.getStringFromResource(R.string.html_illegal_command);
    }


    private String makeGetImportTaskHTML(IHTTPSession session) {
        String script = "";
        script = "\n<script language=\"JavaScript\">\n" +
                "function refreshTask(){\n" +
                "window.location.reload();\n" +
                "}\n" +
                "setTimeout('refreshTask()',1000);\n" +
                " </script>\n";
        Map<String, String> pars = session.getParms();
        if (pars.get("session") != null) {
            int id = parseQueryInt(pars.get("session"), -1);
            if (!importTaskList.checkTaskIsRunning(id)) {//If the task has stopped, no need to refresh
                script = "";
            }
            return script + importTaskList.getTaskHTML(id);
        }

        return script;
    }

    @SuppressLint("DefaultLocale")
    private String doCancelImport(IHTTPSession session) {
        Map<String, String> pars = session.getParms();
        Log.e(TAG, "doCancelImport: " + pars.toString());
        if (pars.get("session") != null) {
            int id = parseQueryInt(pars.get("session"), -1);
            importTaskList.cancelTask(id);
            return String.format("<head>\n<meta http-equiv=\"Refresh\" content=\"0; URL=getImportTask?session=%d\" /></head><body></body>"
                    , id);
        }
        return "";
    }

    @SuppressLint("DefaultLocale")
    private void doImportADI(ImportTaskList.ImportTask task, LogFileImport logFileImport) {
        task.setStatus(ImportTaskList.ImportState.IMPORTING);
        ArrayList<HashMap<String, String>> recordList = logFileImport.getLogRecords();//Split lines using regex: [<][Ee][Oo][Rr][>]
        task.importedCount = 0;
        task.count = recordList.size();//total number of lines
        for (HashMap<String, String> record : recordList) {
            if (task.status == ImportTaskList.ImportState.CANCELED) break;//Check if import was canceled

            QSLRecord qslRecord = new QSLRecord(record);
            task.processCount++;
            if (mainViewModel.databaseOpr.doInsertQSLData(qslRecord, new AfterInsertQSLData() {
                @Override
                public void doAfterInsert(boolean isInvalid, boolean isNewQSL) {
                    if (isInvalid) {
                        task.invalidCount++;
                        return;
                    }
                    if (isNewQSL) {
                        task.newCount++;
                    } else {
                        task.updateCount++;
                    }
                }
            })) {
                task.importedCount++;
            }
        }


        //Display erroneous data here
        StringBuilder temp = new StringBuilder();
        if (logFileImport.getErrorCount() > 0) {
            temp.append("<table>");
            temp.append(String.format("<tr><th></th><th>%d malformed logs</th></tr>\n", logFileImport.getErrorCount()));
            for (int key : logFileImport.getErrorLines().keySet()) {
                temp.append(String.format("<tr><td><pre>%d</pre></td><td><pre >%s</pre></td></tr>\n"
                        , key, logFileImport.getErrorLines().get(key)));
            }

            temp.append("</table>");
        }

        task.errorMsg = temp.toString();
        if (task.status!= ImportTaskList.ImportState.CANCELED) {
            task.setStatus(ImportTaskList.ImportState.FINISHED);
        }
        mainViewModel.databaseOpr.getQslDxccToMap();//Refresh the map of contacted zones
    }


    /**
     * Get configuration information
     *
     * @return config table content
     */
    private String getConfig() {
        Cursor cursor = mainViewModel.databaseOpr.getDb()
                .rawQuery("select KeyName,Value from config", null);
        return HtmlContext.ListTableContext(cursor, true, 4, false);
    }

    /**
     * Get QSO callsigns, including: callsign, last time, band, wavelength, grid
     *
     * @return config table content
     */
    private String showQslCallsigns(IHTTPSession session) {
        String callsign = "";
        //Read query parameters
        Map<String, String> pars = session.getParms();
        if (pars.get("callsign") != null) {
            callsign = Objects.requireNonNull(pars.get("callsign"));
        }
        String where = String.format("%%%s%%", callsign);

        String html = String.format("<form >%s<input type=text name=callsign value=\"%s\">" +
                        "<input type=submit value=\"%s\"></form><br>\n"
                , GeneralVariables.getStringFromResource(R.string.html_callsign)
                , HtmlContext.htmlEscape(callsign)
                , GeneralVariables.getStringFromResource(R.string.html_message_query));

        Cursor cursor = mainViewModel.databaseOpr.getDb()
                .rawQuery("select q.[call] as callsign ,q.gridsquare,q.band||\"(\"||q.freq||\")\" as band \n" +
                        ",q.qso_date||\"-\"||q.time_on as last_time from QSLTable q \n" +
                        "inner join QSLTable q2 ON q.id =q2.id \n" +
                        "where q.[call] like ?\n" +
                        "group by q.[call] ,q.gridsquare,q.freq ,q.qso_date,q.time_on,q.band\n" +
                        "HAVING q.qso_date||q.time_on =MAX(q2.qso_date||q2.time_on) \n", new String[]{where});
        return html + HtmlContext.ListTableContext(cursor, true, 3, false);

    }

    /**
     * Get all table names
     *
     * @return html
     */
    private String getAllTableName() {
        Cursor cursor = mainViewModel.databaseOpr.getDb()
                .rawQuery("select * from sqlite_master where type='table'", null);
        return HtmlContext.ListTableContext(cursor, true, 4, true);
    }

    @SuppressLint({"Range", "DefaultLocale"})
    private String getCallsignQTH(IHTTPSession session) {
        String callsign = "";
        String grid = "";
        //Read query parameters
        Map<String, String> pars = session.getParms();

        if (pars.get("callsign") != null) {
            callsign = Objects.requireNonNull(pars.get("callsign"));
        }
        if (pars.get("grid") != null) {
            grid = Objects.requireNonNull(pars.get("grid"));
        }
        String whereCallsign = String.format("%%%s%%", callsign.toUpperCase());
        String whereGrid = String.format("%%%s%%", grid.toUpperCase());
        Cursor cursor = mainViewModel.databaseOpr.getDb()
                .rawQuery("select callsign ,grid,updateTime from CallsignQTH where (callsign like ?) and (grid like ?)"
                        , new String[]{whereCallsign, whereGrid});
        StringBuilder result = new StringBuilder();
        HtmlContext.tableBegin(result, true, 2, false).append("\n");

        result.append(String.format("<form >%s<input type=text name=callsign value=\"%s\"><br>\n" +
                        "%s<input type=text name=grid value=\"%s\">\n" +
                        "<input type=submit value=\"%s\"></form><br><br>\n"
                , GeneralVariables.getStringFromResource(R.string.html_callsign)
                , HtmlContext.htmlEscape(callsign)
                , GeneralVariables.getStringFromResource(R.string.html_qsl_grid)
                , HtmlContext.htmlEscape(grid)
                , GeneralVariables.getStringFromResource(R.string.html_message_query)));
        //Write column names
        HtmlContext.tableRowBegin(result).append("\n");
        HtmlContext.tableCellHeader(result
                , GeneralVariables.getStringFromResource(R.string.html_callsign)
                , GeneralVariables.getStringFromResource(R.string.html_qsl_grid)
                , GeneralVariables.getStringFromResource(R.string.html_distance)
                , GeneralVariables.getStringFromResource(R.string.html_update_time)
        ).append("\n");

        HtmlContext.tableRowEnd(result).append("\n");

        @SuppressLint("SimpleDateFormat") SimpleDateFormat formatTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int order = 0;
        while (cursor.moveToNext()) {
            HtmlContext.tableRowBegin(result, true, order % 2 != 0);
            Date date = new Date(cursor.getLong(cursor.getColumnIndex("updateTime")));

            HtmlContext.tableCell(result
                    , HtmlContext.htmlEscape(cursor.getString(cursor.getColumnIndex("callsign")))
                    , HtmlContext.htmlEscape(cursor.getString(cursor.getColumnIndex("grid")))
                    , MaidenheadGrid.getDistStr(GeneralVariables.getMyMaidenhead4Grid()
                            , cursor.getString(cursor.getColumnIndex("grid")))
                    , formatTime.format(date)
            ).append("\n");
            HtmlContext.tableRowEnd(result).append("\n");
            order++;
        }
        HtmlContext.tableEnd(result).append("<br>\n");
        result.append(String.format("%d", order));
        cursor.close();
        return result.toString();
    }


    /**
     * Get tracked callsigns
     *
     * @return HTML
     */
    private String getFollowCallsigns() {
        Cursor cursor = mainViewModel.databaseOpr.getDb().rawQuery("select * from followCallsigns", null);
        StringBuilder result = new StringBuilder();
        HtmlContext.tableBegin(result, true, 3, false).append("\n");

        //Write column names
        HtmlContext.tableRowBegin(result).append("\n");
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            HtmlContext.tableCellHeader(result
                    , GeneralVariables.getStringFromResource(R.string.html_callsign)
                    , GeneralVariables.getStringFromResource(R.string.html_operation)).append("\n");
        }
        HtmlContext.tableRowEnd(result).append("\n");
        int order = 0;
        while (cursor.moveToNext()) {
            HtmlContext.tableRowBegin(result, true, order % 2 != 0).append("\n");
            for (int i = 0; i < cursor.getColumnCount(); i++) {
                HtmlContext.tableCell(result
                        , HtmlContext.htmlEscape(cursor.getString(i))
                        , String.format("<a href=\"/delfollow/%s\">%s</a>"
                                // Path segment: percent-encode, so a space/?/#/& in the
                                // callsign can't break the link or change the route.
                                , HtmlContext.urlPathSegment(cursor.getString(i).replace("/", "_"))
                                , GeneralVariables.getStringFromResource(R.string.html_delete))
                ).append("\n");
            }
            HtmlContext.tableRowEnd(result).append("\n");
            order++;
        }
        HtmlContext.tableEnd(result).append("\n");
        cursor.close();
        return result.toString();
    }

    /**
     * Delete a tracked callsign
     *
     * @param callsign the tracked callsign
     */
    private void deleteFollowCallSign(String callsign) {
        mainViewModel.databaseOpr.getDb().execSQL("delete from followCallsigns where callsign=?", new String[]{callsign});
    }

    private void deleteQSLByMonth(String month) {
        mainViewModel.databaseOpr.getDb().execSQL("delete from QSLTable where SUBSTR(qso_date,1,6)=? \n"
                , new String[]{month});
    }


    /**
     * Query QSO callsigns
     *
     * @return HTML
     */
    @SuppressLint("Range")
    private String getQSLCallsigns() {
        Cursor cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                "select * from QslCallsigns order by ID desc", null);
        StringBuilder result = new StringBuilder();
        HtmlContext.tableBegin(result, false, 0, true).append("\n");

        //Write column names
        HtmlContext.tableRowBegin(result).append("\n");
        HtmlContext.tableCellHeader(result
                , GeneralVariables.getStringFromResource(R.string.html_qsl_start_time)
                , GeneralVariables.getStringFromResource(R.string.html_qsl_end_time)
                , GeneralVariables.getStringFromResource(R.string.html_callsign)
                , GeneralVariables.getStringFromResource(R.string.html_qsl_mode)
                , GeneralVariables.getStringFromResource(R.string.html_qsl_grid)
                , GeneralVariables.getStringFromResource(R.string.html_qsl_band)
                , GeneralVariables.getStringFromResource(R.string.html_qsl_freq)
                , GeneralVariables.getStringFromResource(R.string.html_qsl_manual_confirmation)
                , GeneralVariables.getStringFromResource(R.string.html_qsl_lotw_confirmation)
                , GeneralVariables.getStringFromResource(R.string.html_qsl_data_source)
                , GeneralVariables.getStringFromResource(R.string.html_operation)).append("\n");
        HtmlContext.tableRowEnd(result).append("\n");
        int order = 0;
        while (cursor.moveToNext()) {
            HtmlContext.tableRowBegin(result, true, order % 2 != 0);
            HtmlContext.tableCell(result
                    , cursor.getString(cursor.getColumnIndex("startTime"))
                    , cursor.getString(cursor.getColumnIndex("finishTime"))
                    , HtmlContext.htmlEscape(cursor.getString(cursor.getColumnIndex("callsign")))
                    , HtmlContext.htmlEscape(cursor.getString(cursor.getColumnIndex("mode")))
                    , HtmlContext.htmlEscape(cursor.getString(cursor.getColumnIndex("grid")))
                    , HtmlContext.htmlEscape(cursor.getString(cursor.getColumnIndex("band")))
                    , cursor.getString(cursor.getColumnIndex("band_i")) + "Hz"
                    , (cursor.getInt(cursor.getColumnIndex("isQSL")) == 1)
                            ? "<font color=green>√</font>" : "<font color=red>×</font>"
                    , (cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1)
                            ? "<font color=green>√</font>" : "<font color=red>×</font>"
                    , (cursor.getInt(cursor.getColumnIndex("isLotW_import")) == 1)
                            ? GeneralVariables.getStringFromResource(R.string.html_qsl_import_data_from_external)
                            : GeneralVariables.getStringFromResource(R.string.html_qsl_native_data)
                    , String.format("<a href=/delQslCallsign/%s>%s</a>"
                            , cursor.getString(cursor.getColumnIndex("ID"))
                            , GeneralVariables.getStringFromResource(R.string.html_delete))).append("\n");
            HtmlContext.tableRowEnd(result).append("\n");
            order++;
        }
        HtmlContext.tableEnd(result).append("\n");
        cursor.close();
        return result.toString();
    }

    private void deleteQSLCallSign(String callsign) {
        mainViewModel.databaseOpr.getDb().execSQL("delete from QslCallsigns where id=?", new String[]{callsign});
    }

    /**
     * Show callsign-to-grid mapping
     *
     * @return html
     */
    @SuppressLint("DefaultLocale")
    private String showCallGridList() {
        StringBuilder result = new StringBuilder();
        result.append("<script language=\"JavaScript\">\n" +
                "function myrefresh(){\n" +
                "window.location.reload();\n" +
                "}\n" +
                "setTimeout('myrefresh()',5000); //Refresh every 5 seconds; customizable (1000 = 1 second)\n" +
                "</script>");
        result.append(String.format(GeneralVariables.getStringFromResource(R.string.html_callsign_grid_total)
                , GeneralVariables.callsignAndGrids.size()));
        HtmlContext.tableBegin(result, true, 1, false);
        HtmlContext.tableRowBegin(result);
        result.append(String.format("<th>%s</th>", GeneralVariables.getStringFromResource(R.string.html_callsign)));
        result.append(String.format("<th>%s</th>", GeneralVariables.getStringFromResource(R.string.html_qsl_grid)));
        HtmlContext.tableRowEnd(result).append("\n");

        result.append(GeneralVariables.getCallsignAndGridToHTML());
        HtmlContext.tableEnd(result).append("<br>\n");

        return result.toString();
    }

    /**
     * Show debug information
     *
     * @return html
     */
    @SuppressLint("DefaultLocale")
    private String showDebug() {
        StringBuilder result = new StringBuilder();
        result.append("<script language=\"JavaScript\">\n" +
                "function myrefresh(){\n" +
                "window.location.reload();\n" +
                "}\n" +
                "setTimeout('myrefresh()',5000);\n " +////Refresh every 5 seconds; customizable (1000 = 1 second)\n" +
                "</script>");

        HtmlContext.tableBegin(result, true, 5, false).append("\n");

        HtmlContext.tableRowBegin(result);
        HtmlContext.tableCellHeader(result
                , GeneralVariables.getStringFromResource(R.string.html_variable)
                , GeneralVariables.getStringFromResource(R.string.html_value)).append("\n");

        HtmlContext.tableKeyRow(result, false, "UTC"
                , UtcTimer.getTimeStr(mainViewModel.timerSec.getValue()));

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_my_callsign)
                , GeneralVariables.myCallsign);

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_my_grid)
                , GeneralVariables.getMyMaidenheadGrid());

        HtmlContext.tableKeyRow(result, true//Max cached message count
                , GeneralVariables.getStringFromResource(R.string.html_max_message_cache)
                , String.format("%d", GeneralVariables.MESSAGE_COUNT));

        HtmlContext.tableKeyRow(result, false//Volume level
                , GeneralVariables.getStringFromResource(R.string.signal_strength)
                , String.format("%.0f%%\n", GeneralVariables.volumePercent * 100f));


        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_audio_bits)
                , GeneralVariables.audioOutput32Bit ?
                        GeneralVariables.getStringFromResource(R.string.audio32_bit)
                        : GeneralVariables.getStringFromResource(R.string.audio16_bit));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_audio_rate)
                , String.format("%dHz", GeneralVariables.audioSampleRate));

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_decodes_in_this_cycle)
                , String.format("%d", mainViewModel.getCurrentDecodeCount()));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.decode_mode_text)
                , GeneralVariables.deepDecodeMode
                        ? GeneralVariables.getStringFromResource(R.string.deep_mode)
                        : GeneralVariables.getStringFromResource(R.string.fast_mode));


        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_total_number_of_decodes)
                , String.format("%d", mainViewModel.ft8Messages.size()));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_in_recording_state)
                , Boolean.TRUE.equals(mainViewModel.mutableIsRecording.getValue())
                        ? GeneralVariables.getStringFromResource(R.string.html_recording)
                        : GeneralVariables.getStringFromResource(R.string.html_no_recording));

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_time_consuming_for_this_decoding)
                , String.format(GeneralVariables.getStringFromResource(R.string.html_milliseconds)
                        , mainViewModel.ft8SignalListener.decodeTimeSec.getValue()));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_average_delay_time_of_this_cycle)
                , String.format(GeneralVariables.getStringFromResource(R.string.html_seconds)
                        , mainViewModel.mutableTimerOffset.getValue()));

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_sound_frequency)
                , String.format("%.0fHz", GeneralVariables.getBaseFrequency()));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_transmission_delay_time)
                , String.format(GeneralVariables.getStringFromResource(R.string.html_milliseconds)
                        , GeneralVariables.transmitDelay));

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_launch_supervision)
                , String.format(GeneralVariables.getStringFromResource(R.string.html_milliseconds)
                        , GeneralVariables.launchSupervision));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_automatic_program_run_time)
                , String.format(GeneralVariables.getStringFromResource(R.string.html_milliseconds)
                        , GeneralVariables.launchSupervisionCount()));

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_no_reply_limit)
                , GeneralVariables.noReplyLimit);

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_no_reply_count)
                , GeneralVariables.noReplyCount);

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.follow_cq)
                , String.valueOf(GeneralVariables.autoFollowCQ));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.auto_call_follow)
                , String.valueOf(GeneralVariables.autoCallFollow));

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_target_callsign)
                , (mainViewModel.ft8TransmitSignal.mutableToCallsign.getValue() != null)
                        ? mainViewModel.ft8TransmitSignal.mutableToCallsign.getValue().callsign : "");

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_sequential)
                , mainViewModel.ft8TransmitSignal.sequential);

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.synFrequency)
                , String.valueOf(GeneralVariables.synFrequency));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.tran_delay)
                , GeneralVariables.transmitDelay);

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.ptt_delay)
                , GeneralVariables.pttDelay);

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.rig_name)
                , RigNameList.getInstance(
                        GeneralVariables.getMainContext()).getRigNameByIndex(GeneralVariables.modelNo).modelName);

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_mark_message)
                , String.format("%s", mainViewModel.markMessage
                        ? GeneralVariables.getStringFromResource(R.string.html_marking_message)
                        : GeneralVariables.getStringFromResource(R.string.html_do_not_mark_message)));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_operation_mode)
                , ControlMode.getControlModeStr(GeneralVariables.controlMode));

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_civ_address)
                , String.format("0x%2X", GeneralVariables.civAddress));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_baud_rate)
                , GeneralVariables.baudRate);

        result.append("<tr>");
        result.append("<td class=\"default\" >");
        result.append(GeneralVariables.getStringFromResource(R.string.html_available_serial_ports));
        result.append("</td>\n");
        result.append("<td class=\"default\" >");
        if (mainViewModel.mutableSerialPorts != null) {
            if (mainViewModel.mutableSerialPorts.getValue().size() == 0) {
                result.append("-");
            }
        }
        for (CableSerialPort.SerialPort serialPort : Objects.requireNonNull(mainViewModel.mutableSerialPorts.getValue())) {
            result.append(serialPort.information()).append("<br>\n");
        }
        result.append("</td>");
        result.append("</tr>\n");


        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_instruction_set)
                , (mainViewModel.baseRig != null) ? mainViewModel.baseRig.getName() : "-");

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_connect_mode)
                , (GeneralVariables.controlMode == ControlMode.VOX) ? "-"
                        : ConnectMode.getModeStr(GeneralVariables.connectMode));

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_baud_rate)
                , GeneralVariables.baudRate);

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_carrier_frequency_band)
                , GeneralVariables.getBandString());

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_radio_frequency)
                , (mainViewModel.baseRig != null)
                        ? BaseRigOperation.getFrequencyStr(mainViewModel.baseRig.getFreq()) : "-");

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.html_flex_max_rf_power)
                , String.format("%d W", GeneralVariables.flexMaxRfPower));

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.html_atu_tune_power)
                , String.format("%d W", GeneralVariables.flexMaxTunePower));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.be_excluded_callsigns)
                , GeneralVariables.getExcludeCallsigns());

        HtmlContext.tableKeyRow(result, true
                , GeneralVariables.getStringFromResource(R.string.config_save_swl)
                , String.valueOf(GeneralVariables.saveSWLMessage));

        HtmlContext.tableKeyRow(result, false
                , GeneralVariables.getStringFromResource(R.string.config_save_swl_qso)
                , String.valueOf(GeneralVariables.saveSWL_QSO));

        HtmlContext.tableEnd(result).append("<br>\n");

        HtmlContext.tableBegin(result, false, 0, true).append("\n");
        result.append(String.format("<tr><th>%s</th></tr>\n"
                , String.format(GeneralVariables.getStringFromResource(R.string.html_successful_callsign)
                        , GeneralVariables.getBandString())));

        result.append("<tr><td class=\"default\" >");
        for (int i = 0; i < GeneralVariables.QSL_Callsign_list.size(); i++) {
            result.append(GeneralVariables.QSL_Callsign_list.get(i));
            result.append(",&nbsp;");
            if (((i + 1) % 10) == 0) {
                result.append("</td></tr><tr><td class=\"default\" >\n");
            }
        }
        result.append("</td></tr>\n");
        HtmlContext.tableEnd(result).append("<br>\n");

        HtmlContext.tableBegin(result, false, 0, true).append("\n");
        result.append(String.format("<tr><th>%s</th></tr>\n"
                , GeneralVariables.getStringFromResource(R.string.html_tracking_callsign)));

        result.append("<tr><td class=\"default\" >");
        for (int i = 0; i < GeneralVariables.followCallsign.size(); i++) {
            result.append(GeneralVariables.followCallsign.get(i));
            result.append(",&nbsp;");
            if (((i + 1) % 10) == 0) {
                result.append("</td></tr><tr><td class=\"default\" >\n");
            }
        }
        result.append("</td></tr>\n");
        HtmlContext.tableEnd(result).append("\n");

        HtmlContext.tableBegin(result, false, 0, true).append("\n");
        result.append(String.format("<tr><th>%s</th></tr>\n"
                , GeneralVariables.getStringFromResource(R.string.html_tracking_qso_information)));

        result.append("<tr><td class=\"default\" >");
        result.append(GeneralVariables.qslRecordList.toHTML());
        result.append("</td></tr>\n");
        HtmlContext.tableEnd(result).append("\n");

        return result.toString();
    }

    /**
     * Show callsign hash table
     *
     * @return html
     */
    private String showCallsignHash() {
        StringBuilder result = new StringBuilder();
        result.append("<script language=\"JavaScript\">\n" +
                "function myrefresh(){\n" +
                "window.location.reload();\n" +
                "}\n" +
                "setTimeout('myrefresh()',5000); //Refresh every 5 seconds; customizable (1000 = 1 second)\n" +
                "</script>");
        HtmlContext.tableBegin(result, true, 3, false).append("\n");
        HtmlContext.tableRowBegin(result);
        //Table header
        HtmlContext.tableCellHeader(result, GeneralVariables.getStringFromResource(R.string.html_callsign)
                , GeneralVariables.getStringFromResource(R.string.html_hash_value));

        HtmlContext.tableRowEnd(result).append("\n");


        int order = 0;
        for (Map.Entry<Long, String> entry : Ft8Message.hashList.entrySet()) {
            HtmlContext.tableRowBegin(result, false, (order / 3) % 2 != 0);

            HtmlContext.tableCell(result, entry.getValue());
            HtmlContext.tableCell(result, String.format(" 0x%x ", entry.getKey()));
            HtmlContext.tableRowEnd(result).append("\n");

            order++;
        }
        HtmlContext.tableEnd(result).append("\n");

        return result.toString();
    }

    @SuppressLint("Range")
    private Response exportSWLMessage(String exportFile, String callsign, String start_date, String end_date) {
        Response response;
        StringBuilder fileName = new StringBuilder();
        fileName.append("message");
        if (callsign.length() > 0) {
            fileName.append("_");
            fileName.append(callsign.replace("/", "_")
                    .replace("\\", "_")
                    .replace(":", "_")
                    .replace("?", "_")
                    .replace("*", "_")
                    .replace("|", "_")
                    .replace("\"", "_")
                    .replace("'", "_")
                    .replace("<", "_")
                    .replace(".", "_")
                    .replace(">", "_"));
        }
        if (start_date.length() > 0) {
            fileName.append(String.format("_%s", start_date));
        }
        if (end_date.length() > 0) {
            fileName.append(String.format("_%s", end_date));
        }
        fileName.append(".").append(exportFile);


        Cursor cursor;
        StringBuilder dateSql = new StringBuilder();
        if (!start_date.equals("")) {
            dateSql.append(String.format(" AND (SUBSTR(UTC,1,8)>=\"%s\") "
                    , start_date.replace("-", "")));
        }
        if (!end_date.equals("")) {
            dateSql.append(String.format(" AND (SUBSTR(UTC,1,8)<=\"%s\") "
                    , end_date.replace("-", "")));
        }
        String whereStr = String.format("%%%s%%", callsign);
        cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                "select * from SWLMessages where ((CALL_TO LIKE ?)OR(CALL_FROM LIKE ?)) " +
                        dateSql +
                        " order by ID "
                , new String[]{whereStr, whereStr});

        StringBuilder result = new StringBuilder();

        String formatStr;
        if (exportFile.equalsIgnoreCase("CSV")) {
            formatStr = "%s,%.3f,Rx,%s,%d,%.1f,%d,%s\n";
        } else {
            formatStr = "%s %12.3f Rx %s %6d %4.1f %4d %s\n";
        }

        while (cursor.moveToNext()) {
            String utcTime = cursor.getString(cursor.getColumnIndex("UTC"));
            int dB = cursor.getInt(cursor.getColumnIndex("SNR"));
            float dt = cursor.getFloat(cursor.getColumnIndex("TIME_SEC"));
            int freq = cursor.getInt(cursor.getColumnIndex("FREQ"));
            String callTo = cursor.getString(cursor.getColumnIndex("CALL_TO"));
            String protocol = cursor.getString(cursor.getColumnIndex("Protocol"));
            String callFrom = cursor.getString(cursor.getColumnIndex("CALL_FROM"));
            String extra = cursor.getString(cursor.getColumnIndex("EXTRAL"));
            long band = cursor.getLong(cursor.getColumnIndex("BAND"));

            result.append(String.format(formatStr
                    , utcTime, (band / 1000f / 1000f), protocol, dB, dt, freq, String.format("%s %s %s", callTo, callFrom, extra)));
        }
        cursor.close();


        response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", result.toString());
        response.addHeader("Content-Disposition"
                , String.format("attachment;filename=%s", fileName));

        return response;
    }

    /**
     * Query SWL message table
     *
     * @return html
     */
    @SuppressLint({"DefaultLocale", "Range"})
    private Response getMessages(IHTTPSession session) {
        int pageSize = 100;
        String callsign = "";


        StringBuilder result = new StringBuilder();
        String startDate = "";
        String endDate = "";
        String exportFile = "";

        //Read query parameters
        Map<String, String> pars = session.getParms();
        int pageIndex = 1;
        pageIndex = parseQueryInt(pars.get("page"), pageIndex);
        pageSize = parseQueryInt(pars.get("pageSize"), pageSize);
        // Normalize before it feeds pageCount(), the SQL LIMIT, and the nav links, so a
        // malformed 0/negative ?pageSize= can't force an empty page or an undefined limit.
        pageSize = normalizePageSize(pageSize);
        if (pars.get("callsign") != null) {
            callsign = Objects.requireNonNull(pars.get("callsign"));
        }
        if (pars.get("start_date") != null) {
            startDate = Objects.requireNonNull(pars.get("start_date"));
        }
        if (pars.get("end_date") != null) {
            endDate = Objects.requireNonNull(pars.get("end_date"));
        }
        String whereStr = String.format("%%%s%%", callsign);

        if (pars.get("exportFile") != null) {
            exportFile = Objects.requireNonNull(pars.get("exportFile"));
        }

        //Export to file
        if (exportFile.equalsIgnoreCase("CSV")
                || exportFile.equalsIgnoreCase("TXT")) {
            return exportSWLMessage(exportFile, callsign, startDate, endDate);
        }

        HtmlContext.tableBegin(result, false, 0, true).append("\n");
        HtmlContext.tableRowBegin(result).append("<td>");


        result.append(String.format("<a href=\"message?callsign=%s&start_date=%s&end_date=%s&exportFile=csv\">%s</a>" +
                        " , <a href=\"message?callsign=%s&start_date=%s&end_date=%s&exportFile=txt\">%s</a><br>"
                , callsign, startDate, endDate, GeneralVariables.getStringFromResource(R.string.html_export_csv)
                , callsign, startDate, endDate, GeneralVariables.getStringFromResource(R.string.html_export_text)));

        Cursor cursor;
        StringBuilder dateSql = new StringBuilder();
        if (!startDate.equals("")) {
            dateSql.append(String.format(" AND (SUBSTR(UTC,1,8)>=\"%s\") "
                    , startDate.replace("-", "")));
        }
        if (!endDate.equals("")) {
            dateSql.append(String.format(" AND (SUBSTR(UTC,1,8)<=\"%s\") "
                    , endDate.replace("-", "")));
        }
        //Calculate total record count
        cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                "select count(*) as rc from SWLMessages " +
                        "where ((CALL_TO LIKE ?)OR(CALL_FROM LIKE ?))" + dateSql
                , new String[]{whereStr, whereStr});
        cursor.moveToFirst();
        int pageCount = pageCount(cursor.getInt(cursor.getColumnIndex("rc")), pageSize);
        pageIndex = clampPageIndex(pageIndex, pageCount);
        cursor.close();

        //Query and per-page message count settings
        result.append(String.format("<form >%s , %s" +
                        "<input type=number name=pageSize style=\"width:80px\" value=%d>" +//Page number and page size
                        "<br>\n%s&nbsp;<input type=text name=callsign value=\"%s\">" +//Callsign
                        "&nbsp;&nbsp;<input type=submit value=\"%s\"><br>\n" +
                        "<br>\n%s&nbsp;<input type=date name=\"start_date\" value=\"%s\" onchange=\"javascript:form.submit();\">" +//Start date
                        "&nbsp;\n%s&nbsp;<input type=date name=end_date value=\"%s\" onchange=\"javascript:form.submit();\"><br>" //End date

                , String.format(GeneralVariables.getStringFromResource(R.string.html_message_page_count), pageCount)
                , GeneralVariables.getStringFromResource(R.string.html_message_page_size)
                , pageSize
                , GeneralVariables.getStringFromResource(R.string.html_callsign)
                , callsign
                , GeneralVariables.getStringFromResource(R.string.html_message_query)
                , GeneralVariables.getStringFromResource(R.string.html_start_date_swl_message)
                , startDate
                , GeneralVariables.getStringFromResource(R.string.html_end_date_swl_message)
                , endDate));


        //Page navigation: first, previous, next, last
        result.append(String.format("<a href=\"message?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s\">|&lt;</a>" +
                        "&nbsp;&nbsp;<a href=\"message?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s\">&lt;&lt;</a>" +
                        "<input type=\"number\" name=\"page\" value=%d style=\"width:50px\">" +
                        "<a href=\"message?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s\">&gt;&gt;</a>" +
                        "&nbsp;&nbsp;<a href=\"message?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s\">&gt;|</a></form>\n"
                , 1, pageSize, callsign, startDate, endDate
                , pageIndex - 1 == 0 ? 1 : pageIndex - 1, pageSize, callsign, startDate, endDate
                , pageIndex
                , pageIndex == pageCount ? pageCount : pageIndex + 1, pageSize, callsign, startDate, endDate
                , pageCount, pageSize, callsign, startDate, endDate));
        result.append("</td>");
        HtmlContext.tableRowEnd(result);
        HtmlContext.tableEnd(result).append("\n");


        cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                String.format(
                        "select * from SWLMessages where ((CALL_TO LIKE ?)OR(CALL_FROM LIKE ?)) " +
                                dateSql +
                                " order by ID LIMIT(%d),%d "
                        , (pageIndex - 1) * pageSize, pageSize), new String[]{whereStr, whereStr});

        //result.append("<table bgcolor=#a1a1a1 border=\"0\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\">\n");
        HtmlContext.tableBegin(result, false, true).append("\n");
        HtmlContext.tableRowBegin(result).append("\n");
        HtmlContext.tableCellHeader(result, "No.");
        HtmlContext.tableCellHeader(result, GeneralVariables.getStringFromResource(R.string.html_protocol));
        HtmlContext.tableCellHeader(result, "i3.n3", "UTC", "dB", "Δt");
        HtmlContext.tableCellHeader(result, GeneralVariables.getStringFromResource(R.string.html_qsl_freq));
        HtmlContext.tableCellHeader(result, GeneralVariables.getStringFromResource(R.string.message));
        HtmlContext.tableCellHeader(result, GeneralVariables.getStringFromResource(R.string.html_carrier_frequency_band)).append("\n");
        HtmlContext.tableRowEnd(result).append("\n");


        int order = 0;
        while (cursor.moveToNext()) {
            HtmlContext.tableRowBegin(result, true, order % 2 != 0);

            int i3 = cursor.getInt(cursor.getColumnIndex("I3"));
            int n3 = cursor.getInt(cursor.getColumnIndex("N3"));
            String utcTime = cursor.getString(cursor.getColumnIndex("UTC"));
            int dB = cursor.getInt(cursor.getColumnIndex("SNR"));
            float dt = cursor.getFloat(cursor.getColumnIndex("TIME_SEC"));
            int freq = cursor.getInt(cursor.getColumnIndex("FREQ"));
            String protocol = cursor.getString(cursor.getColumnIndex("Protocol"));
            String callTo = cursor.getString(cursor.getColumnIndex("CALL_TO"));
            String callFrom = cursor.getString(cursor.getColumnIndex("CALL_FROM"));
            String extra = cursor.getString(cursor.getColumnIndex("EXTRAL"));
            long band = cursor.getLong(cursor.getColumnIndex("BAND"));

            HtmlContext.tableCell(result, String.format("%d", order + 1 + pageSize * (pageIndex - 1)));
            HtmlContext.tableCell(result, protocol, Ft8Message.getCommandInfoByI3N3(i3, n3));
            HtmlContext.tableCell(result, utcTime);
            HtmlContext.tableCell(result, String.format("%d", dB));
            HtmlContext.tableCell(result, String.format("%.1f", dt));
            HtmlContext.tableCell(result, String.format("%dHz", freq));
            HtmlContext.tableCell(result, String.format("<b><a href=\"message?&pageSize=%d&callsign=%s\">" +
                            "%s</a>&nbsp;&nbsp;" +
                            "<a href=\"message?&pageSize=%d&callsign=%s\">%s</a>&nbsp;&nbsp;%s</b>"
                    // href query values are percent-encoded (entity resolution would turn an
                    // escaped &amp; back into a parameter separator); link text is HTML-escaped.
                    , pageSize, HtmlContext.urlQueryValue(callTo)
                    , HtmlContext.htmlEscape(callTo)
                    , pageSize, HtmlContext.urlQueryValue(callFrom)
                    , HtmlContext.htmlEscape(callFrom), HtmlContext.htmlEscape(extra)));
            HtmlContext.tableCell(result, BaseRigOperation.getFrequencyStr(band)).append("\n");
            HtmlContext.tableRowEnd(result).append("\n");

            order++;
        }
        cursor.close();
        HtmlContext.tableEnd(result).append("<br>\n");
        //result.append("</table><br>");


        return newFixedLengthResponse(HtmlContext.HTML_STRING(result.toString()));
        //return result.toString();

    }


    /**
     * Export SWL QSO logs to file
     *
     * @param exportFile file name
     * @param callsign   callsign
     * @param start_date start date
     * @param end_date   end date
     * @return data
     */
    @SuppressLint("Range")
    private Response exportSWLQSOMessage(String exportFile, String callsign, String start_date, String end_date) {
        Response response;
        StringBuilder fileName = new StringBuilder();
        fileName.append("swl_qso");
        if (callsign.length() > 0) {
            fileName.append("_");
            fileName.append(callsign.replace("/", "_")
                    .replace("\\", "_")
                    .replace(":", "_")
                    .replace("?", "_")
                    .replace("*", "_")
                    .replace("|", "_")
                    .replace("\"", "_")
                    .replace("'", "_")
                    .replace("<", "_")
                    .replace(".", "_")
                    .replace(">", "_"));
        }
        if (start_date.length() > 0) {
            fileName.append(String.format("_%s", start_date));
        }
        if (end_date.length() > 0) {
            fileName.append(String.format("_%s", end_date));
        }
        fileName.append(".").append(exportFile);


        Cursor cursor;
        StringBuilder dateSql = new StringBuilder();
        if (!start_date.equals("")) {
            dateSql.append(String.format(" AND (SUBSTR(qso_date_off,1,8)>=\"%s\") "
                    , start_date.replace("-", "")));
        }
        if (!end_date.equals("")) {
            dateSql.append(String.format(" AND (SUBSTR(qso_date_off,1,8)<=\"%s\") "
                    , end_date.replace("-", "")));
        }
        String whereStr = String.format("%%%s%%", callsign);

        cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                String.format(
                        "select * from SWLQSOTable where (([call] LIKE ?)OR(station_callsign LIKE ?)) " +
                                dateSql +
                                " order by qso_date desc,time_on desc "), new String[]{whereStr, whereStr});


        response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain"
                , mainViewModel.databaseOpr.downQSLTable(cursor, true));
        response.addHeader("Content-Disposition"
                , String.format("attachment;filename=%s", fileName));

        return response;
    }


    /**
     * Query SWL logs
     *
     * @param session session
     * @return html
     */
    @SuppressLint({"DefaultLocale", "Range"})
    private Response getSWLQsoMessages(IHTTPSession session) {
        int pageSize = 100;
        String callsign = "";


        StringBuilder result = new StringBuilder();
        String startDate = "";
        String endDate = "";
        String exportFile = "";

        //Read query parameters
        Map<String, String> pars = session.getParms();
        int pageIndex = 1;
        pageIndex = parseQueryInt(pars.get("page"), pageIndex);
        pageSize = parseQueryInt(pars.get("pageSize"), pageSize);
        // Normalize before it feeds pageCount(), the SQL LIMIT, and the nav links, so a
        // malformed 0/negative ?pageSize= can't force an empty page or an undefined limit.
        pageSize = normalizePageSize(pageSize);
        if (pars.get("callsign") != null) {
            callsign = Objects.requireNonNull(pars.get("callsign"));
        }
        if (pars.get("start_date") != null) {
            startDate = Objects.requireNonNull(pars.get("start_date"));
        }
        if (pars.get("end_date") != null) {
            endDate = Objects.requireNonNull(pars.get("end_date"));
        }
        String whereStr = String.format("%%%s%%", callsign);

        if (pars.get("exportFile") != null) {
            exportFile = Objects.requireNonNull(pars.get("exportFile"));
        }

        //Export to file
        if (exportFile.equalsIgnoreCase("ADI")) {
            return exportSWLQSOMessage(exportFile, callsign, startDate, endDate);
        }

        HtmlContext.tableBegin(result, false, 0, true).append("\n");
        HtmlContext.tableRowBegin(result).append("\n");
        result.append("<td>");

        result.append(String.format("<a href=\"QSOSWLMSG?callsign=%s&start_date=%s&end_date=%s&exportFile=adi\">%s</a>"
                , callsign, startDate, endDate, GeneralVariables.getStringFromResource(R.string.html_export_adi)));

        Cursor cursor;
        StringBuilder dateSql = new StringBuilder();
        if (!startDate.equals("")) {
            dateSql.append(String.format(" AND (SUBSTR(qso_date_off,1,8)>=\"%s\") "
                    , startDate.replace("-", "")));
        }
        if (!endDate.equals("")) {
            dateSql.append(String.format(" AND (SUBSTR(qso_date_off,1,8)<=\"%s\") "
                    , endDate.replace("-", "")));
        }
        //Calculate total record count
        cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                "select count(*) as rc from SWLQSOTable " +
                        "where (([call] LIKE ?)OR(station_callsign LIKE ?))" + dateSql
                , new String[]{whereStr, whereStr});
        cursor.moveToFirst();
        int pageCount = pageCount(cursor.getInt(cursor.getColumnIndex("rc")), pageSize);
        pageIndex = clampPageIndex(pageIndex, pageCount);
        cursor.close();

        //Query and per-page message count settings
        result.append(String.format("<form >%s , %s" +
                        "<input type=number name=pageSize style=\"width:80px\" value=%d>" +//Page number and page size
                        "<br>\n%s&nbsp;<input type=text name=callsign value=\"%s\">" +//Callsign
                        "&nbsp;&nbsp;<input type=submit value=\"%s\"><br>\n" +
                        "<br>\n%s&nbsp;<input type=date name=\"start_date\" value=\"%s\" onchange=\"javascript:form.submit();\">" +//Start date
                        "&nbsp;\n%s&nbsp;<input type=date name=end_date value=\"%s\" onchange=\"javascript:form.submit();\"><br>" //End date

                , String.format(GeneralVariables.getStringFromResource(R.string.html_message_page_count), pageCount)
                , GeneralVariables.getStringFromResource(R.string.html_message_page_size)
                , pageSize
                , GeneralVariables.getStringFromResource(R.string.html_callsign)
                , callsign
                , GeneralVariables.getStringFromResource(R.string.html_message_query)
                , GeneralVariables.getStringFromResource(R.string.html_start_date_swl_message)
                , startDate
                , GeneralVariables.getStringFromResource(R.string.html_end_date_swl_message)
                , endDate));


        //Page navigation: first, previous, next, last
        result.append(String.format("<a href=\"QSOSWLMSG?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s\">|&lt;</a>" +
                        "&nbsp;&nbsp;<a href=\"QSOSWLMSG?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s\">&lt;&lt;</a>" +
                        "<input type=\"number\" name=\"page\" value=%d style=\"width:50px\">" +
                        "<a href=\"QSOSWLMSG?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s\">&gt;&gt;</a>" +
                        "&nbsp;&nbsp;<a href=\"QSOSWLMSG?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s\">&gt;|</a></form>\n"

                , 1, pageSize, callsign, startDate, endDate
                , pageIndex - 1 == 0 ? 1 : pageIndex - 1, pageSize, callsign, startDate, endDate
                , pageIndex
                , pageIndex == pageCount ? pageCount : pageIndex + 1, pageSize, callsign, startDate, endDate
                , pageCount, pageSize, callsign, startDate, endDate));

        result.append("</td>");
        HtmlContext.tableRowEnd(result);
        HtmlContext.tableEnd(result).append("\n");

        cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                String.format(
                        "select * from SWLQSOTable where (([call] LIKE ?)OR(station_callsign LIKE ?)) " +
                                dateSql +
                                " order by qso_date desc,time_on desc LIMIT(%d),%d "
                        , (pageIndex - 1) * pageSize, pageSize), new String[]{whereStr, whereStr});

        HtmlContext.tableBegin(result, false, true).append("\n");

        HtmlContext.tableRowBegin(result);
        HtmlContext.tableCellHeader(result, "No."
                        , GeneralVariables.getStringFromResource(R.string.html_callsign)//"call"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_grid)//"gridsquare"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_mode)//"mode"
                        , GeneralVariables.getStringFromResource(R.string.html_rst_sent)//"rst_sent"
                        , GeneralVariables.getStringFromResource(R.string.html_rst_rcvd)//"rst_rcvd"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_start_day)//"qso date"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_start_time)//"time_on"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_end_date)//qso date off
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_end_time)//"time_off"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_band)//"band"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_freq)//"freq"
                        , GeneralVariables.getStringFromResource(R.string.html_callsign)//"station_callsign"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_grid)//"my_gridsquare"
                        , "Operator"//"my_gridsquare"
                        , GeneralVariables.getStringFromResource(R.string.html_comment))//"comment")
                .append("\n");
        HtmlContext.tableRowEnd(result).append("\n");
        int order = 0;
        while (cursor.moveToNext()) {
            HtmlContext.tableRowBegin(result, true, order % 2 != 0).append("\n");

            String call = cursor.getString(cursor.getColumnIndex("call"));
            String gridsquare = cursor.getString(cursor.getColumnIndex("gridsquare"));
            String mode = cursor.getString(cursor.getColumnIndex("mode"));
            String rst_sent = cursor.getString(cursor.getColumnIndex("rst_sent"));
            String rst_rcvd = cursor.getString(cursor.getColumnIndex("rst_rcvd"));
            String qso_date = cursor.getString(cursor.getColumnIndex("qso_date"));
            String time_on = cursor.getString(cursor.getColumnIndex("time_on"));
            String qso_date_off = cursor.getString(cursor.getColumnIndex("qso_date_off"));
            String time_off = cursor.getString(cursor.getColumnIndex("time_off"));
            String band = cursor.getString(cursor.getColumnIndex("band"));
            String freq = cursor.getString(cursor.getColumnIndex("freq"));
            String station_callsign = cursor.getString(cursor.getColumnIndex("station_callsign"));
            String my_gridsquare = cursor.getString(cursor.getColumnIndex("my_gridsquare"));
            String operator = cursor.getString(cursor.getColumnIndex("operator"));
            String comment = cursor.getString(cursor.getColumnIndex("comment"));


            //Generate one row of the data table
            HtmlContext.tableCell(result, String.format("%d", order + 1 + pageSize * (pageIndex - 1)));
            HtmlContext.tableCell(result, String.format("<a href=\"QSOSWLMSG?&pageSize=%d&callsign=%s\">%s</a>"
                    , pageSize, HtmlContext.urlQueryValue(call)
                    , HtmlContext.htmlEscape(call)));
            HtmlContext.tableCell(result, gridsquare == null ? "" : HtmlContext.htmlEscape(gridsquare));
            // Straight DB columns: imported ADIF can put anything in these, so escape them too.
            HtmlContext.tableCellEscaped(result, mode, rst_sent, rst_rcvd, qso_date, time_on, qso_date_off, time_off);
            HtmlContext.tableCellEscaped(result, band, freq);
            HtmlContext.tableCell(result, String.format("<a href=\"QSOSWLMSG?&pageSize=%d&callsign=%s\">%s</a>"
                    , pageSize
                    , HtmlContext.urlQueryValue(station_callsign)
                    , HtmlContext.htmlEscape(station_callsign)));

            HtmlContext.tableCell(result, my_gridsquare == null ? "" : HtmlContext.htmlEscape(my_gridsquare));
            HtmlContext.tableCell(result, operator == null ? "" : HtmlContext.htmlEscape(operator), HtmlContext.htmlEscape(comment)).append("\n");
            HtmlContext.tableRowEnd(result).append("\n");
            order++;
        }
        cursor.close();
        HtmlContext.tableEnd(result).append("<br>\n");

        return newFixedLengthResponse(HtmlContext.HTML_STRING(result.toString()));
    }


    /**
     * Export QSO logs to file
     *
     * @param exportFile file name
     * @param callsign   callsign
     * @param start_date start date
     * @param end_date   end date
     * @return data
     */
    @SuppressLint("Range")
    private Response exportQSOLogs(String exportFile, String callsign, String start_date, String end_date, String extWhere) {
        Response response;
        StringBuilder fileName = new StringBuilder();
        fileName.append("qso_log");
        if (callsign.length() > 0) {
            fileName.append("_");
            fileName.append(callsign.replace("/", "_")
                    .replace("\\", "_")
                    .replace(":", "_")
                    .replace("?", "_")
                    .replace("*", "_")
                    .replace("|", "_")
                    .replace("\"", "_")
                    .replace("'", "_")
                    .replace("<", "_")
                    .replace(".", "_")
                    .replace(">", "_"));
        }
        if (start_date.length() > 0) {
            fileName.append(String.format("_%s", start_date));
        }
        if (end_date.length() > 0) {
            fileName.append(String.format("_%s", end_date));
        }
        fileName.append(".").append(exportFile);


        Cursor cursor;
        String whereStr = String.format("%%%s%%", callsign);

        cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                String.format(
                        "select * from QSLTable where (([call] LIKE ?)OR(station_callsign LIKE ?)) " +
                                extWhere +
                                " order by qso_date desc,time_on desc "), new String[]{whereStr, whereStr});


        response = newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain"
                , mainViewModel.databaseOpr.downQSLTable(cursor, false));
        response.addHeader("Content-Disposition"
                , String.format("attachment;filename=%s", fileName));

        return response;
    }

    /**
     * Query QSO logs
     *
     * @param session session
     * @return html
     */
    @SuppressLint({"DefaultLocale", "Range"})
    private Response getQsoLogs(IHTTPSession session) {
        int pageSize = 100;
        String callsign = "";


        StringBuilder result = new StringBuilder();
        String startDate = "";
        String endDate = "";
        String exportFile = "";
        String qIsQSL = "";
        String qIsImported = "";

        //Read query parameters
        Map<String, String> pars = session.getParms();
        int pageIndex = 1;
        pageIndex = parseQueryInt(pars.get("page"), pageIndex);
        pageSize = parseQueryInt(pars.get("pageSize"), pageSize);
        // Normalize before it feeds pageCount(), the SQL LIMIT, and the nav links, so a
        // malformed 0/negative ?pageSize= can't force an empty page or an undefined limit.
        pageSize = normalizePageSize(pageSize);
        if (pars.get("callsign") != null) {
            callsign = Objects.requireNonNull(pars.get("callsign"));
        }
        if (pars.get("start_date") != null) {
            startDate = Objects.requireNonNull(pars.get("start_date"));
        }
        if (pars.get("end_date") != null) {
            endDate = Objects.requireNonNull(pars.get("end_date"));
        }
        String whereStr = String.format("%%%s%%", callsign);

        if (pars.get("exportFile") != null) {
            exportFile = Objects.requireNonNull(pars.get("exportFile"));
        }
        if (pars.get("QSL") != null) {
            qIsQSL = Objects.requireNonNull(pars.get("QSL"));
        }
        if (pars.get("Imported") != null) {
            qIsImported = Objects.requireNonNull(pars.get("Imported"));
        }

        result.append("<form >\n");
        HtmlContext.tableBegin(result, false, 0, true).append("\n");
        HtmlContext.tableRowBegin(result).append("<td>");
        result.append(String.format("<a href=\"QSOLogs?callsign=%s&start_date=%s&end_date=%s&QSL=%s&Imported=%s&exportFile=adi\">%s</a>\n"
                , callsign, startDate, endDate, qIsQSL, qIsImported
                , GeneralVariables.getStringFromResource(R.string.html_export_adi)));

        Cursor cursor;
        StringBuilder dateSql = new StringBuilder();
        if (!startDate.equals("")) {
            dateSql.append(String.format(" AND (SUBSTR(qso_date_off,1,8)>=\"%s\") "
                    , startDate.replace("-", "")));
        }
        if (!endDate.equals("")) {
            dateSql.append(String.format(" AND (SUBSTR(qso_date_off,1,8)<=\"%s\") "
                    , endDate.replace("-", "")));
        }
        if (!qIsQSL.equals("")) {
            dateSql.append(String.format(" AND ((isQSl = %s) %s (isLotW_QSL = %s))"
                    , qIsQSL, qIsQSL.equals("1") ? "OR" : "AND", qIsQSL));
        }
        if (!qIsImported.equals("")) {
            dateSql.append(String.format(" AND (isLotW_import = %s)", qIsImported));
        }


        //Export to file
        if (exportFile.equalsIgnoreCase("ADI")) {
            return exportQSOLogs(exportFile, callsign, startDate, endDate, dateSql.toString());
        }

        //Calculate total record count
        cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                "select count(*) as rc from QSLTable " +
                        "where (([call] LIKE ?)OR(station_callsign LIKE ?))" + dateSql
                , new String[]{whereStr, whereStr});
        cursor.moveToFirst();
        int pageCount = pageCount(cursor.getInt(cursor.getColumnIndex("rc")), pageSize);
        pageIndex = clampPageIndex(pageIndex, pageCount);
        cursor.close();

        //Query and per-page message count settings
        result.append("</td>");
        HtmlContext.tableRowEnd(result);
        HtmlContext.tableRowBegin(result).append("<td>\n");

        result.append(String.format("%s , %s" +
                        "<input type=number name=pageSize style=\"width:80px\" value=%d>" +//Page number and page size
                        "&nbsp;&nbsp;<input type=submit value=\"%s\"><br>\n" +
                        "<br>\n%s&nbsp;<input type=text name=callsign value=\"%s\">" +//Callsign
                        "\n%s&nbsp;<input type=date name=\"start_date\" value=\"%s\" onchange=\"javascript:form.submit();\">" +//Start date
                        "\n%s&nbsp;<input type=date name=end_date value=\"%s\" onchange=\"javascript:form.submit();\">\n" +//End date
                        " </td></tr></table>\n" +
                        " <table bgcolor=#a1a1a1 border=\"0\" cellpadding=\"0\" cellspacing=\"0\">\n" +
                        "<tr><td class=\"default\">\n" +

                        "<fieldset onclick=\"javascript:form.submit();\"> \n" +
                        "    <legend>QSL</legend>" +
                        "<div>\n" +
                        "<input type=\"radio\" name=\"QSL\" value=\"\" %s/>\n" +
                        "<label >" + GeneralVariables.getStringFromResource(R.string.html_qso_all) + "</label>\n" +
                        "<input type=\"radio\" name=\"QSL\" value=\"0\" %s />\n" +
                        "<label >" + GeneralVariables.getStringFromResource(R.string.html_qso_unconfirmed) + "</label>\n" +
                        "<input type=\"radio\" name=\"QSL\" value=\"1\" %s />\n" +
                        "<label >" + GeneralVariables.getStringFromResource(R.string.html_qso_confirmed) + "</label>\n" +
                        "</div>" +
                        "</fieldset>\n" +
                        "</td> <td class=\"default\">\n" +
                        "<fieldset onclick=\"javascript:form.submit();\"> \n" +
                        "    <legend>" + GeneralVariables.getStringFromResource(R.string.html_qso_source) + "</legend>" +
                        "<div>\n" +
                        "      <input type=\"radio\" name=\"Imported\" value=\"\" %s />\n" +
                        "      <label >" + GeneralVariables.getStringFromResource(R.string.html_qso_all) + "</label>\n" +
                        "      <input type=\"radio\" name=\"Imported\" value=\"0\" %s />\n" +
                        "      <label >" + GeneralVariables.getStringFromResource(R.string.html_qso_raw_log) + "</label>\n" +
                        "      <input type=\"radio\" name=\"Imported\" value=\"1\" %s />\n" +
                        "      <label >" + GeneralVariables.getStringFromResource(R.string.html_qso_external_logs) + "</label>\n" +
                        "    </div>" +
                        "</fieldset>" +
                        "</td></tr>  </table>" +
                        "<table bgcolor=#a1a1a1 border=\"0\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%%\">\n" +
                        "        <tr><td class=\"default\">"


                , String.format(GeneralVariables.getStringFromResource(R.string.html_message_page_count), pageCount)
                , GeneralVariables.getStringFromResource(R.string.html_message_page_size)
                , pageSize
                , GeneralVariables.getStringFromResource(R.string.html_message_query)

                , GeneralVariables.getStringFromResource(R.string.html_callsign)
                , callsign
                , GeneralVariables.getStringFromResource(R.string.html_start_date_swl_message)
                , startDate
                , GeneralVariables.getStringFromResource(R.string.html_end_date_swl_message)
                , endDate

                , qIsQSL.equals("") ? "checked=\"true\"" : ""
                , qIsQSL.equals("0") ? "checked=\"true\"" : ""
                , qIsQSL.equals("1") ? "checked=\"true\"" : ""


                , qIsImported.equals("") ? "checked=\"true\"" : ""
                , qIsImported.equals("0") ? "checked=\"true\"" : ""
                , qIsImported.equals("1") ? "checked=\"true\"" : ""
        ));


        //Page navigation: first, previous, next, last
        result.append(String.format("<a href=\"QSOLogs?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s&QSL=%s&Imported=%s\">|&lt;</a>" +
                        "&nbsp;&nbsp;<a href=\"QSOLogs?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s&QSL=%s&Imported=%s\">&lt;&lt;</a>" +
                        "<input type=\"number\" name=\"page\" value=%d style=\"width:50px\">" +
                        "<a href=\"QSOLogs?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s&QSL=%s&Imported=%s\">&gt;&gt;</a>\n" +
                        "&nbsp;&nbsp;<a href=\"QSOLogs?page=%d&pageSize=%d&callsign=%s&start_date=%s&end_date=%s&QSL=%s&Imported=%s\">&gt;|</a>\n"

                , 1, pageSize, callsign, startDate, endDate, qIsQSL, qIsImported
                , pageIndex - 1 == 0 ? 1 : pageIndex - 1, pageSize, callsign, startDate, endDate, qIsQSL, qIsImported
                , pageIndex
                , pageIndex == pageCount ? pageCount : pageIndex + 1, pageSize, callsign, startDate, endDate, qIsQSL, qIsImported
                , pageCount, pageSize, callsign, startDate, endDate, qIsQSL, qIsImported));
        result.append("</td>");
        HtmlContext.tableRowEnd(result);
        HtmlContext.tableEnd(result);
        result.append("</form>");
        //" </td></tr> </table></form>\n"


        cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                String.format(
                        "select * from QSLTable where (([call] LIKE ?)OR(station_callsign LIKE ?)) " +
                                dateSql +
                                " order by qso_date desc,time_on desc LIMIT(%d),%d "
                        , (pageIndex - 1) * pageSize, pageSize), new String[]{whereStr, whereStr});

        HtmlContext.tableBegin(result, false, true).append("\n");

        //Table header
        HtmlContext.tableRowBegin(result).append("\n");
        HtmlContext.tableCellHeader(result, "No.", "QSL"
                        , GeneralVariables.getStringFromResource(R.string.html_qso_source)
                        , GeneralVariables.getStringFromResource(R.string.html_callsign)
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_grid)
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_mode)
                        , GeneralVariables.getStringFromResource(R.string.html_rst_sent)
                        , GeneralVariables.getStringFromResource(R.string.html_rst_rcvd)
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_start_day)//"qso date"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_start_time)//"time_on"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_end_date)//qso date off
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_end_time)//"time_off"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_band)
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_freq)
                        , GeneralVariables.getStringFromResource(R.string.html_callsign)//"station_callsign"
                        , GeneralVariables.getStringFromResource(R.string.html_qsl_grid)//"my_gridsquare"
                        , GeneralVariables.getStringFromResource(R.string.html_comment))//"comment")
                .append("\n");
        HtmlContext.tableRowEnd(result).append("\n");

        //Table content
        int order = 0;
        while (cursor.moveToNext()) {
            HtmlContext.tableRowBegin(result, true, order % 2 != 0).append("\n");

            String call = cursor.getString(cursor.getColumnIndex("call"));
            boolean isQSL = cursor.getInt(cursor.getColumnIndex("isQSL")) == 1;
            boolean isLotW_Import = cursor.getInt(cursor.getColumnIndex("isLotW_import")) == 1;
            boolean isLotW_QSL = cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1;
            String gridsquare = cursor.getString(cursor.getColumnIndex("gridsquare"));
            String mode = cursor.getString(cursor.getColumnIndex("mode"));
            String rst_sent = cursor.getString(cursor.getColumnIndex("rst_sent"));
            String rst_rcvd = cursor.getString(cursor.getColumnIndex("rst_rcvd"));
            String qso_date = cursor.getString(cursor.getColumnIndex("qso_date"));
            String time_on = cursor.getString(cursor.getColumnIndex("time_on"));
            String qso_date_off = cursor.getString(cursor.getColumnIndex("qso_date_off"));
            String time_off = cursor.getString(cursor.getColumnIndex("time_off"));
            String band = cursor.getString(cursor.getColumnIndex("band"));
            String freq = cursor.getString(cursor.getColumnIndex("freq"));
            String station_callsign = cursor.getString(cursor.getColumnIndex("station_callsign"));
            String my_gridsquare = cursor.getString(cursor.getColumnIndex("my_gridsquare"));
            String comment = cursor.getString(cursor.getColumnIndex("comment"));


            HtmlContext.tableCell(result, String.format("%d", order + 1 + pageSize * (pageIndex - 1)));
            HtmlContext.tableCell(result, (isQSL || isLotW_QSL) ? "<font color=green>√</font>" : "<font color=red>✗</font>");
            HtmlContext.tableCell(result, isLotW_Import ?
                    String.format("<font color=red>%s</font>"
                            , GeneralVariables.getStringFromResource(R.string.html_qso_external))
                    : String.format("<font color=green>%s</font>"
                    , GeneralVariables.getStringFromResource(R.string.html_qso_raw)));//Whether it was imported
            HtmlContext.tableCell(result, String.format("<a href=\"QSOLogs?&pageSize=%d&callsign=%s\">%s</a>"
                    , pageSize
                    , HtmlContext.urlQueryValue(call)
                    , HtmlContext.htmlEscape(call)));
            // Straight DB columns: imported ADIF can put anything in these, so escape them too.
            HtmlContext.tableCellEscaped(result, gridsquare == null ? "" : gridsquare, mode, rst_sent, rst_rcvd
                    , qso_date, time_on, qso_date_off, time_off, band, freq);
            HtmlContext.tableCell(result, String.format("<a href=\"QSOLogs?&pageSize=%d&callsign=%s\">%s</a>"
                    , pageSize
                    , HtmlContext.urlQueryValue(station_callsign)
                    , HtmlContext.htmlEscape(station_callsign)));
            HtmlContext.tableCell(result, my_gridsquare == null ? "" : HtmlContext.htmlEscape(my_gridsquare)
                    , HtmlContext.htmlEscape(comment)).append("\n");

            HtmlContext.tableRowEnd(result).append("\n");

            order++;
        }
        cursor.close();
        HtmlContext.tableEnd(result).append("<br>\n");

        return newFixedLengthResponse(HtmlContext.HTML_STRING(result.toString()));
    }

    /**
     * Get all QSO logs
     *
     * @return HTML
     */
    private String showAllQSL() {
        Cursor cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                "select * from QSLTable order by ID DESC ", null);
        return HtmlContext.ListTableContext(cursor, true);
    }

    /**
     * Get logs by month
     *
     * @param month month in yyyymm format
     * @return HTML
     */
    private String showQSLByMonth(String month) {
        Cursor cursor = mainViewModel.databaseOpr.getDb().rawQuery(
                "select * from QSLTable  WHERE SUBSTR(qso_date,1,?)=? \n" +
                        "order by ID DESC ", new String[]{String.valueOf(month.length()), month});
        return HtmlContext.ListTableContext(cursor, true);
    }

    /**
     * Query latest decoded messages
     *
     * @return html
     */
    private String getNewMessages() {
        StringBuilder result = new StringBuilder();
        result.append("<script language=\"JavaScript\">\n" +
                "function myrefresh(){\n" +
                "window.location.reload();\n" +
                "}\n" +
                "setTimeout('myrefresh()',5000); //Refresh every 5 seconds; customizable (1000 = 1 second)\n" +
                "</script>");
        HtmlContext.tableBegin(result, false, true).append("\n");

        HtmlContext.tableRowBegin(result);
        HtmlContext.tableCellHeader(result, "UTC", "dB", "Δt"
                , GeneralVariables.getStringFromResource(R.string.html_qsl_freq)
                , GeneralVariables.getStringFromResource(R.string.message)
                , GeneralVariables.getStringFromResource(R.string.html_carrier_frequency_band));
        HtmlContext.tableRowEnd(result).append("\n");

        int order = 0;
        ArrayList<Ft8Message> currentMessages = mainViewModel.getCurrentMessages();
        if (currentMessages != null) {
            for (Ft8Message message : currentMessages) {
                HtmlContext.tableRowBegin(result, true, order % 2 != 0)
                        .append("\n").append(message.toHtml());
                HtmlContext.tableRowEnd(result).append("\n");
                order++;
            }
        }
        HtmlContext.tableEnd(result).append("<br>\n");
        return result.toString();
    }

    /**
     * Show HTML for importing FT8CN log files
     *
     * @return HTML
     */
    private String showImportLog() {
        StringBuilder result = new StringBuilder();
        HtmlContext.tableBegin(result, false, 0, true).append("\n");
        HtmlContext.tableRowBegin(result, false, true);
        HtmlContext.tableCell(result, String.format("%s<font size=5 color=red>%s</font>%s"
                , GeneralVariables.getStringFromResource(R.string.html_please_select)
                , GeneralVariables.getStringFromResource(R.string.html_adi_format)
                , GeneralVariables.getStringFromResource(R.string.html_file_in_other_format)));

        HtmlContext.tableRowEnd(result).append("\n");
        HtmlContext.tableRowBegin(result).append("\n");

        result.append("<td class=\"default\"><br><form action=\"importLogData\" method=\"post\"\n" +
                "            enctype=\"multipart/form-data\">\n" +
                "            <input type=\"file\" name=\"file1\" id=\"file1\" title=\"select ADI file\" accept=\".adi,.txt\" />\n" +
                "            <input type=\"submit\" value=\"Upload\" />\n" +
                "        </form></td>");
        HtmlContext.tableRowEnd(result).append("\n");
        HtmlContext.tableEnd(result).append("\n");
        return result.toString();
    }

    @SuppressLint("Range")
    private String showQSLTable() {

        StringBuilder result = new StringBuilder();
        HtmlContext.tableBegin(result, false, 1, true).append("\n");
        HtmlContext.tableRowBegin(result).append("\n");

        HtmlContext.tableCellHeader(result
                , GeneralVariables.getStringFromResource(R.string.html_time)
                , GeneralVariables.getStringFromResource(R.string.html_total)
                , GeneralVariables.getStringFromResource(R.string.html_operation)
                , GeneralVariables.getStringFromResource(R.string.html_operation)
                , GeneralVariables.getStringFromResource(R.string.html_operation)
        ).append("\n");

        HtmlContext.tableRowEnd(result).append("\n");

        Cursor cursor = mainViewModel.databaseOpr.getDb().rawQuery("select count(*) as b from QSLTable"
                , null);
        cursor.moveToFirst();

        result.append(String.format("<tr><td align=center class=\"default\"><a href=\"/showAllQsl\">%s</a></td>"
                , GeneralVariables.getStringFromResource(R.string.html_all_logs)));
        result.append(String.format("<td align=center class=\"default\">%s</td>", cursor.getString(cursor.getColumnIndex("b"))));
        result.append(String.format("<td align=center class=\"default\"><a href=\"/downAllQsl\">%s</a></td>"
                , GeneralVariables.getStringFromResource(R.string.html_download)));
        result.append("<td align=center class=\"default\"></td><td></td></tr>");
        cursor.close();

        cursor = mainViewModel.databaseOpr.getDb().rawQuery("select count(*) as b from QSLTable\n" +
                "WHERE SUBSTR(qso_date,1,8)=?", new String[]{UtcTimer.getYYYYMMDD(UtcTimer.getSystemTime())});
        cursor.moveToFirst();

        HtmlContext.tableRowBegin(result, true, true).append("\n");
        HtmlContext.tableCell(result, String.format("<a href=\"/showQsl/%s\">%s</a>"
                , UtcTimer.getYYYYMMDD(UtcTimer.getSystemTime())
                , GeneralVariables.getStringFromResource(R.string.html_today_log)));
        HtmlContext.tableCell(result, cursor.getString(cursor.getColumnIndex("b")));
        HtmlContext.tableCell(result, String.format("<a href=\"/downQsl/%s\">%s</a>"
                , UtcTimer.getYYYYMMDD(UtcTimer.getSystemTime())
                , GeneralVariables.getStringFromResource(R.string.html_download_all)));
        HtmlContext.tableCell(result, String.format("<a href=\"/downQslNoQSL/%s\">%s</a>"
                , UtcTimer.getYYYYMMDD(UtcTimer.getSystemTime())
                , GeneralVariables.getStringFromResource(R.string.html_download_unconfirmed)));
        HtmlContext.tableCell(result, String.format("<a href=\"/delQsl/%s\">%s</a>"
                , UtcTimer.getYYYYMMDD(UtcTimer.getSystemTime())
                , GeneralVariables.getStringFromResource(R.string.html_delete))).append("\n");

        HtmlContext.tableRowEnd(result).append("\n");

        cursor.close();

        int order = 1;
        cursor = mainViewModel.databaseOpr.getDb()
                .rawQuery("select SUBSTR(qso_date,1,6) as a,count(*) as b from QSLTable\n" +
                        "group by SUBSTR(qso_date,1,6)", null);
        while (cursor.moveToNext()) {
            HtmlContext.tableRowBegin(result, true, order % 2 == 0);

            HtmlContext.tableCell(result, String.format("<a href=\"/showQsl/%s\">%s</a>"
                    , cursor.getString(cursor.getColumnIndex("a"))
                    , cursor.getString(cursor.getColumnIndex("a"))));

            HtmlContext.tableCell(result, cursor.getString(cursor.getColumnIndex("b")));
            HtmlContext.tableCell(result, String.format("<a href=\"/downQsl/%s\">%s</a>"
                    , cursor.getString(cursor.getColumnIndex("a"))
                    , GeneralVariables.getStringFromResource(R.string.html_download_all)));
            HtmlContext.tableCell(result, String.format("<a href=\"/downQslNoQSL/%s\">%s</a>"
                    , cursor.getString(cursor.getColumnIndex("a"))
                    , GeneralVariables.getStringFromResource(R.string.html_download_unconfirmed)));
            HtmlContext.tableCell(result, String.format("<a href=\"/delQsl/%s\">%s</a>"
                    , cursor.getString(cursor.getColumnIndex("a"))
                    , GeneralVariables.getStringFromResource(R.string.html_delete))).append("\n");

            HtmlContext.tableRowEnd(result).append("\n");
            order++;
        }
        HtmlContext.tableEnd(result).append("\n");
        cursor.close();
        return result.toString();
    }

    private String downQSLByMonth(String month, boolean downall) {
        Cursor cursor;
        if (downall) {
            cursor = mainViewModel.databaseOpr.getDb().rawQuery("select * from QSLTable \n" +
                            "WHERE (SUBSTR(qso_date,1,?)=?)"
                    , new String[]{String.valueOf(month.length()), month});
        } else {
            cursor = mainViewModel.databaseOpr.getDb().rawQuery("select * from QSLTable \n" +
                            "WHERE (SUBSTR(qso_date,1,?)=?)and(isLotW_QSL=0 and isQSL=0)"
                    , new String[]{String.valueOf(month.length()), month});

        }
        return mainViewModel.databaseOpr.downQSLTable(cursor, false);
    }

    /**
     * Download all logs
     *
     * @return String
     */
    private String downAllQSl() {
        Cursor cursor = mainViewModel.databaseOpr.getDb().rawQuery("select * from QSLTable", null);
        return mainViewModel.databaseOpr.downQSLTable(cursor, false);
    }

    /**
     * Generate QSL record text
     *
     * @return log content
     */
//    @SuppressLint({"Range", "DefaultLocale"})
//    private String downQSLTable(Cursor cursor, boolean isSWL) {
//        StringBuilder logStr = new StringBuilder();
//
//        logStr.append("FT8CN ADIF Export<eoh>\n");
//        while (cursor.moveToNext()) {
//            logStr.append(String.format("<call:%d>%s "
//                    , cursor.getString(cursor.getColumnIndex("call")).length()
//                    , cursor.getString(cursor.getColumnIndex("call"))));
//            if (!isSWL) {
//                if (cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1) {
//                    logStr.append("<QSL_RCVD:1>Y ");
//                } else {
//                    logStr.append("<QSL_RCVD:1>N ");
//                }
//                if (cursor.getInt(cursor.getColumnIndex("isQSL")) == 1) {
//                    logStr.append("<QSL_MANUAL:1>Y ");
//                } else {
//                    logStr.append("<QSL_MANUAL:1>N ");
//                }
//            } else {
//                logStr.append("<swl:1>Y ");
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("gridsquare")) != null) {
//                logStr.append(String.format("<gridsquare:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("gridsquare")).length()
//                        , cursor.getString(cursor.getColumnIndex("gridsquare"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("mode")) != null) {
//                logStr.append(String.format("<mode:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("mode")).length()
//                        , cursor.getString(cursor.getColumnIndex("mode"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("rst_sent")) != null) {
//                logStr.append(String.format("<rst_sent:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("rst_sent")).length()
//                        , cursor.getString(cursor.getColumnIndex("rst_sent"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("rst_rcvd")) != null) {
//                logStr.append(String.format("<rst_rcvd:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("rst_rcvd")).length()
//                        , cursor.getString(cursor.getColumnIndex("rst_rcvd"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("qso_date")) != null) {
//                logStr.append(String.format("<qso_date:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("qso_date")).length()
//                        , cursor.getString(cursor.getColumnIndex("qso_date"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("time_on")) != null) {
//                logStr.append(String.format("<time_on:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("time_on")).length()
//                        , cursor.getString(cursor.getColumnIndex("time_on"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("qso_date_off")) != null) {
//                logStr.append(String.format("<qso_date_off:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("qso_date_off")).length()
//                        , cursor.getString(cursor.getColumnIndex("qso_date_off"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("time_off")) != null) {
//                logStr.append(String.format("<time_off:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("time_off")).length()
//                        , cursor.getString(cursor.getColumnIndex("time_off"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("band")) != null) {
//                logStr.append(String.format("<band:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("band")).length()
//                        , cursor.getString(cursor.getColumnIndex("band"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("freq")) != null) {
//                logStr.append(String.format("<freq:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("freq")).length()
//                        , cursor.getString(cursor.getColumnIndex("freq"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("station_callsign")) != null) {
//                logStr.append(String.format("<station_callsign:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("station_callsign")).length()
//                        , cursor.getString(cursor.getColumnIndex("station_callsign"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("my_gridsquare")) != null) {
//                logStr.append(String.format("<my_gridsquare:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("my_gridsquare")).length()
//                        , cursor.getString(cursor.getColumnIndex("my_gridsquare"))));
//            }
//
//            if (cursor.getColumnIndex("operator") != -1) {
//                if (cursor.getString(cursor.getColumnIndex("operator")) != null) {
//                    logStr.append(String.format("<operator:%d>%s "
//                            , cursor.getString(cursor.getColumnIndex("operator")).length()
//                            , cursor.getString(cursor.getColumnIndex("operator"))));
//                }
//            }
//
//            String comment = cursor.getString(cursor.getColumnIndex("comment"));
//
//            //<comment:15>Distance: 99 km <eor>
//            //When writing to DB, must append " km"
//            logStr.append(String.format("<comment:%d>%s <eor>\n"
//                    , comment.length()
//                    , comment));
//        }
//
//        cursor.close();
//        return logStr.toString();
//    }

}
