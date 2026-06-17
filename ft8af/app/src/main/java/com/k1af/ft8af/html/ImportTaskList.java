package com.k1af.ft8af.html;

import android.annotation.SuppressLint;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;

import java.util.HashMap;

public class ImportTaskList extends HashMap<Integer, ImportTaskList.ImportTask> {

    /**
     * Get the upload task by session key
     *
     * @param session session
     * @return task HTML
     */
    public String getTaskHTML(int session) {
        ImportTask task = this.get(session);
        if (task == null) {
            return GeneralVariables.getStringFromResource(R.string.null_task_html);
        }
        return task.getHtml();
    }
    public void cancelTask(int session){
        ImportTask task = this.get(session);
        if (task != null) {
           task.setStatus(ImportState.CANCELED);
        }
    }

    /**
     * Check if a task is running.
     *
     * @param session task ID
     * @return false if no task exists or the task has ended
     */
    public boolean checkTaskIsRunning(int session) {
        ImportTask task = this.get(session);
        if (task == null) {
            return false;
        } else {
            return task.status == ImportState.STARTING || task.status == ImportState.IMPORTING;
        }
    }

    /**
     * Add a task to the list; must be thread-safe
     *
     * @param session session
     * @param task    task
     */
    public synchronized ImportTask addTask(int session, ImportTask task) {
        this.put(session, task);
        return task;
    }

    public ImportTask addTask(int session) {
        return addTask(session, new ImportTask(session));
    }


    enum ImportState {
        STARTING, IMPORTING, FINISHED, CANCELED
    }

    public static class ImportTask {


        int session;//session, used to track the upload session, is a hash value
        public int count = 0;//total number of parsed records
        public int importedCount = 0;//number of imported records
        public int readErrorCount = 0;//number of read errors
        public int processCount = 0;
        public int updateCount = 0;//number of updated records
        public int invalidCount = 0;//invalid QSL records
        public int newCount = 0;//number of newly imported records
        public ImportState status = ImportState.STARTING;//status: 0=starting, 1=running, 2=finished, 3=canceled
        String message = "";//task message description
        String errorMsg = "";

        @SuppressLint("DefaultLocale")
        public String getHtml() {
            String htmlHeader = "<table bgcolor=#a1a1a1 border=\"0\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\">\n";
            String htmlEnder = "</table>\n";
            String progress = String.format("<FONT COLOR=\"BLUE\">%s %.1f%%(%d/%d)</FONT>\n", GeneralVariables.getStringFromResource(R.string.import_progress_html)
                    , count == 0 ? 0 : processCount * 100f / count, processCount, count);
            String cell = "<tr><td>%s</td></tr>\n";
            String errorHtml = status == ImportState.FINISHED || status == ImportState.CANCELED ? errorMsg : "";
            String doCancelButton = status == ImportState.FINISHED || status == ImportState.CANCELED ? ""
                    : String.format("<br><a href=\"cancelTask?session=%d\"><button>%s</button></a><br>"
                       , session,GeneralVariables.getStringFromResource(R.string.import_cancel_button));
            return htmlHeader
                    + String.format(cell, progress)
                    + String.format(cell, String.format(GeneralVariables.getStringFromResource(R.string.import_read_error_count_html), readErrorCount))
                    + String.format(cell, String.format(GeneralVariables.getStringFromResource(R.string.import_new_count_html), newCount))
                    + String.format(cell, String.format(GeneralVariables.getStringFromResource(R.string.import_update_count_html), updateCount))
                    + String.format(cell, String.format(GeneralVariables.getStringFromResource(R.string.import_invalid_count_html), invalidCount))
                    + String.format(cell, String.format(GeneralVariables.getStringFromResource(R.string.import_readed_html), importedCount))
                    + String.format(cell, message)
                    + String.format(cell, errorHtml)
                    + htmlEnder
                    + doCancelButton;
        }

        public ImportTask(int session) {
            this.session = session;
        }

        public void setStatus(ImportState status) {
            this.status = status;
            setStateMSG(status);
        }

        private void setStateMSG(ImportState state) {
            switch (state) {
                case IMPORTING:
                    this.message = String.format("<FONT COLOR=\"BLUE\"><B>%s</B></FONT>"
                            ,GeneralVariables.getStringFromResource(R.string.log_importing_html));
                    break;
                case FINISHED:
                    this.message = String.format("<FONT COLOR=\"GREEN\"><B>%s</B></FONT>"
                            ,GeneralVariables.getStringFromResource(R.string.log_import_finished_html));
                    break;
                case CANCELED:
                    this.message = String.format("<FONT COLOR=\"RED\"><B>%s</B></FONT>"
                            ,GeneralVariables.getStringFromResource(R.string.import_canceled_html));
                    break;
            }
        }


    }
}
