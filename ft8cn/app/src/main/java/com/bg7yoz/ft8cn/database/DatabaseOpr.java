package com.bg7yoz.ft8cn.database;
/**
 * Class for database operations. Most operations are asynchronous (except HTTP-related ones).
 * The database has gone through multiple versions, hence the onUpgrade method.
 * Configuration info is also stored in the database.
 *
 * @author BGY70Z
 * @date 2023-03-20
 *
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.util.Log;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.callsign.CallsignDatabase;
import com.bg7yoz.ft8cn.callsign.CallsignInfo;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.ft8signal.FT8Package;
import com.bg7yoz.ft8cn.log.OnQueryQSLCallsign;
import com.bg7yoz.ft8cn.log.OnQueryQSLRecordCallsign;
import com.bg7yoz.ft8cn.log.QSLCallsignRecord;
import com.bg7yoz.ft8cn.log.QSLRecord;
import com.bg7yoz.ft8cn.log.QSLRecordStr;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;

public class DatabaseOpr extends SQLiteOpenHelper {
    private static final String TAG = "DatabaseOpr";
    @SuppressLint("StaticFieldLeak")
    private static DatabaseOpr instance;
    private final Context context;
    private SQLiteDatabase db;


    public static synchronized DatabaseOpr getInstance(@Nullable Context context, @Nullable String databaseName) {
        if (instance == null) {
            instance = new DatabaseOpr(context, databaseName, null, 18);
        }
        return instance;
    }

    public DatabaseOpr(@Nullable Context context, @Nullable String name,
                       @androidx.annotation.Nullable SQLiteDatabase.CursorFactory factory, int version) {
        super(context, name, factory, version);
        this.context = context;

        //Connect to database; if the physical database doesn't exist, onCreate will be called to initialize it
        db = this.getWritableDatabase();
    }

    /**
     * Called when the physical database does not exist. Create data and add files here.
     *
     * @param sqLiteDatabase the database to connect to
     */
    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        Log.d(TAG, "Create database.");
        db = sqLiteDatabase;//Save the database connection
        createTables(sqLiteDatabase);//Create data tables
        //Create QSO log table
        createQSLTable(sqLiteDatabase);

        //Create DXCC tables
        createDxccTables(sqLiteDatabase);

        //Create ITU tables
        createItuTables(sqLiteDatabase);

        //Create CQ Zone tables
        createCqZoneTables(sqLiteDatabase);

        //Create callsign-to-grid mapping table
        createCallsignQTHTables(sqLiteDatabase);

        //Create SWL-related tables
        createSWLTables(sqLiteDatabase);

        //Create POTA activation history table
        createPotaTables(sqLiteDatabase);

        //Create indexes
        createIndex(sqLiteDatabase);

    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int i, int i1) {
        //Create QSO log table version 2
        createQSLTable(sqLiteDatabase);

        //Create DXCC tables
        createDxccTables(sqLiteDatabase);

        //Create ITU tables
        createItuTables(sqLiteDatabase);

        //Create CQ Zone tables
        createCqZoneTables(sqLiteDatabase);

        //Create callsign-to-grid mapping table
        createCallsignQTHTables(sqLiteDatabase);

        //Create SWL-related tables
        createSWLTables(sqLiteDatabase);

        //Create POTA activation history table
        createPotaTables(sqLiteDatabase);

        //Create indexes
        createIndex(sqLiteDatabase);

        //Delete equals signs from DXCC callsign list
        //deleteDxccPrefixEqual(sqLiteDatabase);
    }


    public SQLiteDatabase getDb() {
        return db;
    }

    private void createTables(SQLiteDatabase sqLiteDatabase) {
        try {
            //Create configuration table
            sqLiteDatabase.execSQL("CREATE TABLE config (KeyName TEXT,Value TEXT,\n" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT)");

            //Create followed callsigns table. UNIQUE means no duplicates; use INSERT OR IGNORE INTO
            sqLiteDatabase.execSQL("CREATE TABLE followCallsigns (callsign  TEXT UNIQUE)");

        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * Add a column to a table
     *
     * @param db        database
     * @param tableName table name
     * @param fieldName column name
     * @param sql       column definition SQL
     */
    private void alterTable(SQLiteDatabase db, String tableName, String fieldName, String sql) {
        // Identifiers are interpolated into ALTER TABLE and PRAGMA because SQLite cannot bind them;
        // restrict to simple SQL identifiers so a stray caller can never inject statements.
        if (!SQL_IDENTIFIER.matcher(tableName).matches()
                || !SQL_IDENTIFIER.matcher(fieldName).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier");
        }
        // Query the live schema. The previous LIKE-on-sqlite_master.sql approach was broken
        // two ways: sqlite_master.sql freezes the original CREATE TABLE text and never
        // reflects ALTER TABLE ADD COLUMN, and the unanchored substring match treated
        // `sig` as already present whenever `my_sig` was in the original CREATE — so the
        // `sig`/`sig_info` columns were never added on upgraded installs and POTA QSO
        // inserts crashed with "no column named sig".
        boolean exists = false;
        try (Cursor cursor = db.rawQuery(
                String.format("PRAGMA table_info(%s)", tableName), null)) {
            int nameIdx = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                if (fieldName.equals(cursor.getString(nameIdx))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            db.execSQL(String.format("ALTER TABLE %s ADD COLUMN %s", tableName, sql));
        }
    }

    private static final java.util.regex.Pattern SQL_IDENTIFIER =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * Check if a table exists
     *
     * @param db        database
     * @param tableName table name
     * @return whether it exists
     */
    private boolean checkTableExists(SQLiteDatabase db, String tableName) {
        Cursor cursor = db.rawQuery("select * from sqlite_master where type = 'table' and name = ?"
                , new String[]{tableName});
        try {
            return cursor.moveToNext();
        } finally {
            cursor.close();
        }
    }

    /**
     * Check if an index exists
     * @param db
     * @param indexName
     * @return
     */
    private boolean checkIndexExists(SQLiteDatabase db, String indexName) {
        Cursor cursor = db.rawQuery("select * from sqlite_master where type = 'index' and name = ?"
                , new String[]{indexName});
        try {
            return cursor.moveToNext();
        } finally {
            cursor.close();
        }
    }
    private void deleteDxccPrefixEqual(SQLiteDatabase db) {
        db.execSQL("DELETE from dxcc_prefix where prefix LIKE \"=%\"");
    }

    /**
     * Create QSO log table
     */
    private void createQSLTable(SQLiteDatabase sqLiteDatabase) {
        if (checkTableExists(sqLiteDatabase, "QSLTable")) {
            alterTable(sqLiteDatabase, "QSLTable", "isQSL"
                    , "isQSL INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QSLTable", "isLotW_import"
                    , "isLotW_import INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QSLTable", "isLotW_QSL"
                    , "isLotW_QSL INTEGER DEFAULT 0");
            // Per-service upload state. 1 = the record has been accepted by the
            // remote logging service at least once. Existing rows default to 0 so the
            // catch-up sync button can pick them up.
            alterTable(sqLiteDatabase, "QSLTable", "synced_cloudlog"
                    , "synced_cloudlog INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QSLTable", "synced_qrz"
                    , "synced_qrz INTEGER DEFAULT 0");
            // POTA ADIF fields. MY_SIG/MY_SIG_INFO are the activator's program/park ref;
            // SIG/SIG_INFO are the worked station's. Empty for non-POTA contacts.
            alterTable(sqLiteDatabase, "QSLTable", "my_sig"
                    , "my_sig TEXT");
            alterTable(sqLiteDatabase, "QSLTable", "my_sig_info"
                    , "my_sig_info TEXT");
            alterTable(sqLiteDatabase, "QSLTable", "sig"
                    , "sig TEXT");
            alterTable(sqLiteDatabase, "QSLTable", "sig_info"
                    , "sig_info TEXT");

        } else {
            sqLiteDatabase.execSQL("CREATE TABLE QSLTable (\n" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "isQSL INTEGER DEFAULT 0,\n" +//Whether QSL is confirmed
                    "isLotW_import INTEGER DEFAULT 0,\n" +//Whether it's a LoTW import
                    "isLotW_QSL INTEGER DEFAULT 0,\n" +
                    "synced_cloudlog INTEGER DEFAULT 0,\n" +//Uploaded to Cloudlog/Wavelog/Nextlog
                    "synced_qrz INTEGER DEFAULT 0,\n" +//Uploaded to QRZ


                    "call TEXT,\n" +
                    "gridsquare TEXT,\n" +
                    "mode TEXT,\n" +
                    "rst_sent TEXT,\n" +
                    "rst_rcvd TEXT,\n" +
                    "qso_date TEXT,\n" +
                    "time_on TEXT,\n" +
                    "qso_date_off TEXT,\n" +
                    "time_off TEXT,\n" +
                    "band TEXT,\n" +
                    "freq TEXT,\n" +
                    "station_callsign TEXT,\n" +
                    "my_gridsquare TEXT,\n" +
                    "comment TEXT,\n" +
                    "my_sig TEXT,\n" +//POTA: activator's program ("POTA")
                    "my_sig_info TEXT,\n" +//POTA: activator's park ref
                    "sig TEXT,\n" +//POTA: worked station's program
                    "sig_info TEXT)");//POTA: worked station's park ref
        }


        if (checkTableExists(sqLiteDatabase, "QslCallsigns")) {
            alterTable(sqLiteDatabase, "QslCallsigns", "isQSL"
                    , "isQSL INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QslCallsigns", "isLotW_import"
                    , "isLotW_import INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QslCallsigns", "isLotW_QSL"
                    , "isLotW_QSL INTEGER DEFAULT 0");
            alterTable(sqLiteDatabase, "QslCallsigns", "startTime"
                    , "startTime TEXT DEFAULT \"0\"");
        } else {
            sqLiteDatabase.execSQL("CREATE TABLE QslCallsigns (" +
                    "ID INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "isQSL INTEGER DEFAULT 0,\n" +
                    "isLotW_import INTEGER DEFAULT 0,\n" +
                    "isLotW_QSL INTEGER DEFAULT 0,\n" +

                    "callsign TEXT, startTime TEXT," +
                    "finishTime TEXT, mode TEXT," +
                    "grid TEXT,\n" +
                    "band TEXT,band_i INTEGER)");
        }

        if (!checkTableExists(sqLiteDatabase, "Messages")) {
            sqLiteDatabase.execSQL("CREATE TABLE Messages (\n" +
                    "ID INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "I3 INTEGER,\n" +
                    "N3 INTEGER,\n" +
                    "Protocol TEXT,\n" +
                    "UTC INTEGER,\n" +
                    "SNR INTEGER,\n" +
                    "TIME_SEC REAL,\n" +
                    "FREQ INTEGER,\n" +
                    "CALL_TO TEXT,\n" +
                    "CALL_FROM TEXT,\n" +
                    "EXTRAL TEXT,\n" +
                    "REPORT INTEGER,\n" +
                    "BAND INTEGER)");
        }
    }


    /**
     * Create DXCC-related data tables: dxccList, dxcc_prefix, dxcc_grid
     */
    private void createDxccTables(SQLiteDatabase sqLiteDatabase) {
        if (!checkTableExists(sqLiteDatabase, "dxccList")) {
            sqLiteDatabase.execSQL("CREATE TABLE dxccList (\n" +
                    "id INTEGER ," +
                    "\tdxcc INTEGER,\n" +
                    "\tcc TEXT,\n" +
                    "\tccc TEXT,\n" +
                    "\tname TEXT,\n" +
                    "\tcontinent TEXT,\n" +
                    "\tituzone TEXT,\n" +
                    "\tcqzone TEXT,\n" +
                    "\ttimezone INTEGER,\n" +
                    "\tccode INTEGER,\n" +
                    "\taname TEXT,\n" +
                    "\tpp TEXT,\n" +
                    "\tlat REAL,\n" +
                    "\tlon REAL\n" +
                    ");");

            sqLiteDatabase.execSQL("CREATE TABLE dxcc_prefix (\n" +
                    "\tdxcc INTEGER,\n" +
                    "\tprefix TEXT\n" +
                    ");");

            sqLiteDatabase.execSQL("CREATE TABLE dxcc_grid (\n" +
                    "\tdxcc INTEGER,\n" +
                    "\tgrid TEXT\n" +
                    ");");


            //Import DXCC mapping table data into the database
            new Thread(new Runnable() {
                @Override
                public void run() {
                    ArrayList<DxccObject> dxccObjects = loadDxccDataFromFile();
                    for (DxccObject obj : dxccObjects) {
                        obj.insertToDb(sqLiteDatabase);
                    }
                }
            }).start();
        }

    }

    /**
     * Import ITU zone mapping table into the database
     *
     * @param sqLiteDatabase database
     */
    private void createItuTables(SQLiteDatabase sqLiteDatabase) {
        if (!checkTableExists(sqLiteDatabase, "ituList")) {
            sqLiteDatabase.execSQL("CREATE TABLE ituList (itu INTEGER,grid TEXT)");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    loadItuDataFromFile(sqLiteDatabase);
                }
            }).start();
        }
    }

    private void createCqZoneTables(SQLiteDatabase sqLiteDatabase) {
        if (!checkTableExists(sqLiteDatabase, "cqzoneList")) {
            sqLiteDatabase.execSQL("CREATE TABLE cqzoneList (cqzone INTEGER,grid TEXT)");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    loadICqZoneDataFromFile(sqLiteDatabase);
                }
            }).start();
        }
    }

    /**
     * Create callsign-to-grid mapping table
     *
     * @param sqLiteDatabase db
     */
    private void createCallsignQTHTables(SQLiteDatabase sqLiteDatabase) {
        if (!checkTableExists(sqLiteDatabase, "CallsignQTH")) {
            sqLiteDatabase.execSQL("CREATE TABLE CallsignQTH(callsign text, grid text" +
                    ",updateTime Int ,PRIMARY KEY(callsign))");
        }
    }

    private void createSWLTables(SQLiteDatabase sqLiteDatabase) {
        //Log.e(TAG,"upgrade database.");
        if (!checkTableExists(sqLiteDatabase, "SWLMessages")) {
            sqLiteDatabase.execSQL("CREATE TABLE SWLMessages (\n" +
                    "\tID INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "\tI3 INTEGER,\n" +
                    "\tN3 INTEGER,\n" +
                    "\tProtocol TEXT,\n" +
                    "\tUTC TEXT,\n" +
                    "\tSNR INTEGER,\n" +
                    "\tTIME_SEC REAL,\n" +
                    "\tFREQ INTEGER,\n" +
                    "\tCALL_TO TEXT,\n" +
                    "\tCALL_FROM TEXT,\n" +
                    "\tEXTRAL TEXT,\n" +
                    "\tREPORT INTEGER,\n" +
                    "\tBAND INTEGER\n" +
                    ")");
            sqLiteDatabase.execSQL("CREATE INDEX SWLMessages_CALL_TO_IDX " +
                    "ON SWLMessages (CALL_TO,CALL_FROM)");
            sqLiteDatabase.execSQL("CREATE INDEX SWLMessages_UTC_IDX ON SWLMessages (UTC)");
        }

        if (!checkTableExists(sqLiteDatabase, "SWLQSOTable")) {
            sqLiteDatabase.execSQL("CREATE TABLE SWLQSOTable (\n" +
                    "\tid INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "\t\"call\" TEXT,\n" +
                    "\tgridsquare TEXT,\n" +
                    "\tmode TEXT,\n" +
                    "\trst_sent TEXT,\n" +
                    "\trst_rcvd TEXT,\n" +
                    "\tqso_date TEXT,\n" +
                    "\ttime_on TEXT,\n" +
                    "\tqso_date_off TEXT,\n" +
                    "\ttime_off TEXT,\n" +
                    "\tband TEXT,\n" +
                    "\tfreq TEXT,\n" +
                    "\tstation_callsign TEXT,\n" +
                    "\tmy_gridsquare TEXT,\n" +
                    "\toperator TEXT,\n" +
                    "\tcomment TEXT)");
        }else {
            alterTable(sqLiteDatabase, "SWLQSOTable", "operator"
                    , "operator TEXT");
        }
    }


    /**
     * Create POTA activation history table. Each row is one activation session
     * (start to end) so users can re-export a single activation's ADIF later.
     */
    private void createPotaTables(SQLiteDatabase sqLiteDatabase) {
        if (!checkTableExists(sqLiteDatabase, "pota_activation")) {
            sqLiteDatabase.execSQL("CREATE TABLE pota_activation (\n" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT,\n" +
                    "park_ref TEXT NOT NULL,\n" +
                    "operator TEXT,\n" +
                    "started_at INTEGER NOT NULL,\n" +//epoch millis
                    "ended_at INTEGER,\n" +//null while in progress
                    "qso_count INTEGER DEFAULT 0,\n" +
                    "notes TEXT)");
        }
    }

    /**
     * Create indexes to improve import speed
     * @param sqLiteDatabase database
     */
    private void createIndex(SQLiteDatabase sqLiteDatabase) {
        if (!checkIndexExists(sqLiteDatabase, "QslCallsigns_callsign_IDX")) {
            sqLiteDatabase.execSQL("CREATE INDEX QslCallsigns_callsign_IDX ON QslCallsigns (callsign,startTime,finishTime,mode)");
        }
        if (!checkIndexExists(sqLiteDatabase, "QSLTable_call_IDX")) {
            sqLiteDatabase.execSQL("CREATE INDEX QSLTable_call_IDX ON QSLTable (\"call\",qso_date,time_on,mode)");
        }
    }


    public void loadItuDataFromFile(SQLiteDatabase db) {
        AssetManager assetManager = context.getAssets();
        InputStream inputStream;
        db.execSQL("delete from ituList");

        String insertSQL = "INSERT INTO ituList (itu,grid)" +
                "VALUES(?,?)";
        try {
            inputStream = assetManager.open("ituzone.json");
            byte[] bytes = new byte[inputStream.available()];
            inputStream.read(bytes);
            JSONObject jsonObject = new JSONObject(new String(bytes));
            JSONArray array = jsonObject.names();
            for (int i = 0; i < array.length(); i++) {
                JSONObject ituObject = new JSONObject(jsonObject.getString(array.getString(i)));
                JSONArray mh = ituObject.getJSONArray("mh");
                for (int j = 0; j < mh.length(); j++) {
                    db.execSQL(insertSQL, new Object[]{array.getString(i), mh.getString(j)});
                }
            }
            inputStream.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            Log.e(TAG, "loadDataFromFile: " + e.getMessage());
        }
    }

    public void loadICqZoneDataFromFile(SQLiteDatabase db) {
        AssetManager assetManager = context.getAssets();
        InputStream inputStream;
        db.execSQL("delete from cqzoneList");
        String insertSQL = "INSERT INTO cqzoneList (cqzone,grid)" +
                "VALUES(?,?)";
        try {
            inputStream = assetManager.open("cqzone.json");
            byte[] bytes = new byte[inputStream.available()];
            inputStream.read(bytes);
            JSONObject jsonObject = new JSONObject(new String(bytes));
            JSONArray array = jsonObject.names();
            for (int i = 0; i < array.length(); i++) {
                JSONObject ituObject = new JSONObject(jsonObject.getString(array.getString(i)));
                JSONArray mh = ituObject.getJSONArray("mh");
                for (int j = 0; j < mh.length(); j++) {
                    db.execSQL(insertSQL, new Object[]{array.getString(i), mh.getString(j)});
                }
            }
            inputStream.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            Log.e(TAG, "loadDataFromFile: " + e.getMessage());
        }
    }


    public ArrayList<DxccObject> loadDxccDataFromFile() {
        AssetManager assetManager = context.getAssets();
        InputStream inputStream;
        ArrayList<DxccObject> dxccObjects = new ArrayList<>();
        try {
            inputStream = assetManager.open("dxcc_list.json");
            byte[] bytes = new byte[inputStream.available()];
            inputStream.read(bytes);
            JSONObject jsonObject = new JSONObject(new String(bytes));
            JSONArray array = jsonObject.names();

            for (int i = 0; i < array.length(); i++) {
                if (array.getString(i).equals("-1")) continue;
                JSONObject dxccObject = new JSONObject(jsonObject.getString(array.getString(i)));
                DxccObject dxcc = new DxccObject();
                dxcc.id = Integer.parseInt(array.getString(i));
                dxcc.dxcc = dxccObject.getInt("dxcc");
                dxcc.cc = dxccObject.getString("cc");
                dxcc.ccc = dxccObject.getString("ccc");
                dxcc.name = dxccObject.getString("name");
                dxcc.continent = dxccObject.getString("continent");
                dxcc.ituZone = dxccObject.getString("ituzone")
                        .replace("[", "")
                        .replace("]", "")
                        .replace("\"", "");
                dxcc.cqZone = dxccObject.getString("cqzone")
                        .replace("[", "")
                        .replace("]", "")
                        .replace("\"", "");
                dxcc.timeZone = dxccObject.getInt("timezone");
                dxcc.cCode = dxccObject.getInt("ccode");
                dxcc.aName = dxccObject.getString("aname");
                dxcc.pp = dxccObject.getString("pp");
                dxcc.lat = dxccObject.getDouble("lat");
                dxcc.lon = dxccObject.getDouble("lon");

                JSONArray mh = dxccObject.getJSONArray("mh");
                for (int j = 0; j < mh.length(); j++) {
                    dxcc.grid.add(mh.getString(j));
                }
                JSONArray prefix = dxccObject.getJSONArray("prefix");
                for (int j = 0; j < prefix.length(); j++) {
                    dxcc.prefix.add(prefix.getString(j));
                }
                dxccObjects.add(dxcc);
                //Log.e(TAG, "loadDataFromFile: id:" + dxcc.id + " dxcc:" + dxcc.dxcc);
            }

            inputStream.close();
        } catch (IOException | JSONException e) {
            e.printStackTrace();
            Log.e(TAG, "loadDataFromFile: " + e.getMessage());
        }
        return dxccObjects;
    }


    /**
     * Write callsign-to-grid mapping into the table
     *
     * @param callsign callsign
     * @param grid     grid
     */
    public void addCallsignQTH(String callsign, String grid) {
        if (grid.trim().length() < 4) return;
        new AddCallsignQTH(db).execute(callsign, grid);
        Log.d(TAG, String.format("addCallsignQTH: callsign:%s,grid:%s", callsign, grid));
    }

    //Query configuration info.
    public void getConfigByKey(String KeyName, OnAfterQueryConfig onAfterQueryConfig) {
        new QueryConfig(db, KeyName, onAfterQueryConfig).execute();
    }

    public void getCallSign(String callsign, String fieldName, String tableName, OnGetCallsign getCallsign) {
        new QueryCallsign(db, tableName, fieldName, callsign, getCallsign).execute();
    }

    /**
     * Write configuration info, async operation
     */
    public void writeConfig(String KeyName, String Value, OnAfterWriteConfig onAfterWriteConfig) {
        Log.d(TAG, "writeConfig: Value:" + Value);
        new WriteConfig(db, KeyName, Value, onAfterWriteConfig).execute();
    }

    public void writeMessage(ArrayList<Ft8Message> messages) {
        new WriteMessages(db, messages).execute();
    }

    /**
     * Read the list of followed callsigns
     *
     * @param onAffterQueryFollowCallsigns callback function
     */
    public void getFollowCallsigns(OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns) {
        new GetFollowCallSigns(db, onAffterQueryFollowCallsigns).execute();
    }

    /**
     * Query SWL MESSAGE count per band
     * @param onAfterQueryFollowCallsigns callback
     */
    public void getMessageLogTotal(OnAfterQueryFollowCallsigns onAfterQueryFollowCallsigns) {
        new GetMessageLogTotal(db, onAfterQueryFollowCallsigns).execute();
    }

    /**
     * Query SWL QSO count per month
     * @param onAfterQueryFollowCallsigns callback
     */
    public void getSWLQsoLogTotal(OnAfterQueryFollowCallsigns onAfterQueryFollowCallsigns) {
        new GetSWLQsoTotal(db, onAfterQueryFollowCallsigns).execute();
    }


    /**
     * Add a followed callsign to the database
     *
     * @param callsign callsign
     */
    public void addFollowCallsign(String callsign) {
        new AddFollowCallSign(db, callsign).execute();
    }

    /**
     * Clear all followed callsigns
     */
    public void clearFollowCallsigns() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                db.execSQL("delete from followCallsigns ");
            }
        }).start();
    }

    /**
     * Delete QSO log cache data
     */
    public void clearLogCacheData() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                db.execSQL("delete from SWLMessages ");
            }
        }).start();
    }

    /**
     * Delete SWL QSO logs
     */
    public void clearSWLQsoData() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                db.execSQL("delete from SWLQSOTable ");
            }
        }).start();
    }
    /**
     * Write successful QSO log and callsign to the database
     *
     * @param qslRecord QSO record
     */
    public void addQSL_Callsign(QSLRecord qslRecord) {
        new AddQSL_Info(this, qslRecord).execute();
    }

    /**
     * Save SWL QSO to the database. SWL QSO criteria: must have signal reports from both parties; does not include own callsign.
     * @param qslRecord QSO log record
     */
    public void addSWL_QSO(QSLRecord qslRecord) {
        new Add_SWL_QSO_Info(this, qslRecord).execute();
    }

    //Delete a followed callsign from the database
    public void deleteFollowCallsign(String callsign) {
        new DeleteFollowCallsign(db, callsign).execute();
    }

    //Get all configuration parameters
    public void getAllConfigParameter(OnAfterQueryConfig onAfterQueryConfig) {
        new GetAllConfigParameter(db, onAfterQueryConfig).execute();
    }

    /**
     * Query all successfully contacted callsigns, filtered by QSO frequency
     */
    public void getAllQSLCallsigns() {
        new LoadAllQSLCallsigns(db).execute();
    }


    /**
     * Find QSL callsign records by callsign
     *
     * @param callsign           callsign
     * @param onQueryQSLCallsign callback
     */
    public void getQSLCallsignsByCallsign(boolean showAll,int offset,String callsign, int filter, OnQueryQSLCallsign onQueryQSLCallsign) {
        new GetQLSCallsignByCallsign(showAll,offset,db, callsign, filter, onQueryQSLCallsign).execute();
    }

    /**
     * Query grids that have been QSO'd. Mainly used in GridTracker
     * to determine which grids are QSO and which are QSL.
     *
     * @param onGetQsoGrids event after the query completes
     */
    public void getQsoGridQuery(OnGetQsoGrids onGetQsoGrids) {
        new GetQsoGrids(db, onGetQsoGrids).execute();
    }

    /**
     * Query QSL records by callsign
     *
     * @param callsign                 callsign
     * @param onQueryQSLRecordCallsign callback
     */
    public void getQSLRecordByCallsign(boolean showAll,int offset,String callsign, int filter, OnQueryQSLRecordCallsign onQueryQSLRecordCallsign) {
        new GetQSLByCallsign(showAll,offset,db, callsign, filter, onQueryQSLRecordCallsign).execute();
    }

    /**
     * Delete QSO callsign
     *
     * @param id ID
     */
    public void deleteQSLCallsign(int id) {
        new DeleteQSLCallsignByID(db, id).execute();
    }

    /**
     * Delete log entry
     *
     * @param id ID
     */
    public void deleteQSLByID(int id) {
        new DeleteQSLByID(db, id).execute();
    }

    /**
     * Set manual QSL confirmation for a log entry
     *
     * @param isQSL whether confirmed
     * @param id    ID
     */
    public void setQSLTableIsQSL(boolean isQSL, int id) {
        new SetQSLTableIsQSL(db, id, isQSL).execute();
    }

    /**
     * Update an existing QSL record by id with the supplied column values.
     * Runs on a background thread; SQLite handles concurrent reads.
     */
    public void updateQSLRecord(final int id, final android.content.ContentValues values) {
        if (values == null || values.size() == 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    db.update("QSLTable", values, "id=?", new String[]{String.valueOf(id)});
                } catch (Exception e) {
                    Log.e(TAG, "updateQSLRecord failed: " + e.getMessage());
                }
            }
        }).start();
    }

    public void setQSLCallsignIsQSL(boolean isQSL, int id) {
        new SetQSLCallsignIsQSL(db, id, isQSL).execute();
    }

    /**
     * Look up callsign-to-grid mapping in the database; results are written to GeneralVariables.callsignAndGrids
     *
     * @param callsign callsign
     */
    public void getCallsignQTH(String callsign) {
        new GetCallsignQTH(db).execute(callsign);
    }


//    /**
//     * Write string to file
//     * @param file
//     * @param data
//     */
//    private void writeStrToFile(File file, String data) {
//        FileOutputStream fileOutputStream = null;
//        try {
//            fileOutputStream = new FileOutputStream(file, true);
//            fileOutputStream.write(data.getBytes());
//        } catch (IOException e) {
//            Log.e(TAG, String.format("Error writing file: %s", e.getMessage()));
//        } finally {
//            try {
//                if (fileOutputStream != null) {
//                    fileOutputStream.close();
//                }
//            } catch (IOException e) {
//                Log.e(TAG, String.format("Error closing file: %s", e.getMessage()));
//            }
//        }
//    }

//    /**
//     * Write log data to file for sharing and other purposes
//     * @param cursor cursor
//     * @param isSWL whether in SWL mode
//     */
//    @SuppressLint({"DefaultLocale", "Range"})
//    public void downQSLTableToFile(File adiFile, Cursor cursor, boolean isSWL){
//
//        writeStrToFile(adiFile,"FT8CN ADIF Export<eoh>\n");
//        int count =0;
//        cursor.moveToPosition(-1);
//        while (cursor.moveToNext()) {
//            count++;
//            writeStrToFile(adiFile,String.format("<call:%d>%s "
//                    , cursor.getString(cursor.getColumnIndex("call")).length()
//                    , cursor.getString(cursor.getColumnIndex("call"))));
//            if (!isSWL) {
//                if (cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1) {
//                    writeStrToFile(adiFile,"<QSL_RCVD:1>Y ");
//                } else {
//                    writeStrToFile(adiFile,"<QSL_RCVD:1>N ");
//                }
//                if (cursor.getInt(cursor.getColumnIndex("isQSL")) == 1) {
//                    writeStrToFile(adiFile,"<QSL_MANUAL:1>Y ");
//                } else {
//                    writeStrToFile(adiFile,"<QSL_MANUAL:1>N ");
//                }
//            } else {
//                writeStrToFile(adiFile,"<swl:1>Y ");
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("gridsquare")) != null) {
//                writeStrToFile(adiFile,String.format("<gridsquare:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("gridsquare")).length()
//                        , cursor.getString(cursor.getColumnIndex("gridsquare"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("mode")) != null) {
//                writeStrToFile(adiFile,String.format("<mode:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("mode")).length()
//                        , cursor.getString(cursor.getColumnIndex("mode"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("rst_sent")) != null) {
//                writeStrToFile(adiFile,String.format("<rst_sent:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("rst_sent")).length()
//                        , cursor.getString(cursor.getColumnIndex("rst_sent"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("rst_rcvd")) != null) {
//                writeStrToFile(adiFile,String.format("<rst_rcvd:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("rst_rcvd")).length()
//                        , cursor.getString(cursor.getColumnIndex("rst_rcvd"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("qso_date")) != null) {
//                writeStrToFile(adiFile,String.format("<qso_date:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("qso_date")).length()
//                        , cursor.getString(cursor.getColumnIndex("qso_date"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("time_on")) != null) {
//                writeStrToFile(adiFile,String.format("<time_on:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("time_on")).length()
//                        , cursor.getString(cursor.getColumnIndex("time_on"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("qso_date_off")) != null) {
//                writeStrToFile(adiFile,String.format("<qso_date_off:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("qso_date_off")).length()
//                        , cursor.getString(cursor.getColumnIndex("qso_date_off"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("time_off")) != null) {
//                writeStrToFile(adiFile,String.format("<time_off:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("time_off")).length()
//                        , cursor.getString(cursor.getColumnIndex("time_off"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("band")) != null) {
//                writeStrToFile(adiFile,String.format("<band:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("band")).length()
//                        , cursor.getString(cursor.getColumnIndex("band"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("freq")) != null) {
//                writeStrToFile(adiFile,String.format("<freq:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("freq")).length()
//                        , cursor.getString(cursor.getColumnIndex("freq"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("station_callsign")) != null) {
//                writeStrToFile(adiFile,String.format("<station_callsign:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("station_callsign")).length()
//                        , cursor.getString(cursor.getColumnIndex("station_callsign"))));
//            }
//
//            if (cursor.getString(cursor.getColumnIndex("my_gridsquare")) != null) {
//                writeStrToFile(adiFile,String.format("<my_gridsquare:%d>%s "
//                        , cursor.getString(cursor.getColumnIndex("my_gridsquare")).length()
//                        , cursor.getString(cursor.getColumnIndex("my_gridsquare"))));
//            }
//
//            if (cursor.getColumnIndex("operator") != -1) {
//                if (cursor.getString(cursor.getColumnIndex("operator")) != null) {
//                    writeStrToFile(adiFile,String.format("<operator:%d>%s "
//                            , cursor.getString(cursor.getColumnIndex("operator")).length()
//                            , cursor.getString(cursor.getColumnIndex("operator"))));
//                }
//            }
//            String comment = cursor.getString(cursor.getColumnIndex("comment"));
//
//            //<comment:15>Distance: 99 km <eor>
//            //When writing to db, must append " km"
//            writeStrToFile(adiFile,String.format("<comment:%d>%s <eor>\n"
//                    , comment.length()
//                    , comment));
//        }
//        Log.e(TAG,String.format("Wrote %d records",count));
//
//        cursor.close();
//    }

    /**
     * Generate ADIF text content
     * @param cursor cursor
     * @param isSWL whether in SWL mode
     * @return ADIF text content
     */
    @SuppressLint({"Range", "DefaultLocale"})
    public String downQSLTable(Cursor cursor, boolean isSWL) {
        StringBuilder logStr = new StringBuilder();

        logStr.append("FT8AF ADIF Export<eoh>\n");
        cursor.moveToPosition(-1);
        while (cursor.moveToNext()) {
            logStr.append(String.format("<call:%d>%s "
                    , cursor.getString(cursor.getColumnIndex("call")).length()
                    , cursor.getString(cursor.getColumnIndex("call"))));
            if (!isSWL) {
                if (cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1) {
                    logStr.append("<QSL_RCVD:1>Y ");
                } else {
                    logStr.append("<QSL_RCVD:1>N ");
                }
                if (cursor.getInt(cursor.getColumnIndex("isQSL")) == 1) {
                    logStr.append("<QSL_MANUAL:1>Y ");
                } else {
                    logStr.append("<QSL_MANUAL:1>N ");
                }
            } else {
                logStr.append("<swl:1>Y ");
            }

            if (cursor.getString(cursor.getColumnIndex("gridsquare")) != null) {
                logStr.append(String.format("<gridsquare:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("gridsquare")).length()
                        , cursor.getString(cursor.getColumnIndex("gridsquare"))));
            }

            if (cursor.getString(cursor.getColumnIndex("mode")) != null) {
                logStr.append(String.format("<mode:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("mode")).length()
                        , cursor.getString(cursor.getColumnIndex("mode"))));
            }

            if (cursor.getString(cursor.getColumnIndex("rst_sent")) != null) {
                logStr.append(String.format("<rst_sent:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("rst_sent")).length()
                        , cursor.getString(cursor.getColumnIndex("rst_sent"))));
            }

            if (cursor.getString(cursor.getColumnIndex("rst_rcvd")) != null) {
                logStr.append(String.format("<rst_rcvd:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("rst_rcvd")).length()
                        , cursor.getString(cursor.getColumnIndex("rst_rcvd"))));
            }

            if (cursor.getString(cursor.getColumnIndex("qso_date")) != null) {
                logStr.append(String.format("<qso_date:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("qso_date")).length()
                        , cursor.getString(cursor.getColumnIndex("qso_date"))));
            }

            if (cursor.getString(cursor.getColumnIndex("time_on")) != null) {
                logStr.append(String.format("<time_on:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("time_on")).length()
                        , cursor.getString(cursor.getColumnIndex("time_on"))));
            }

            if (cursor.getString(cursor.getColumnIndex("qso_date_off")) != null) {
                logStr.append(String.format("<qso_date_off:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("qso_date_off")).length()
                        , cursor.getString(cursor.getColumnIndex("qso_date_off"))));
            }

            if (cursor.getString(cursor.getColumnIndex("time_off")) != null) {
                logStr.append(String.format("<time_off:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("time_off")).length()
                        , cursor.getString(cursor.getColumnIndex("time_off"))));
            }

            if (cursor.getString(cursor.getColumnIndex("band")) != null) {
                logStr.append(String.format("<band:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("band")).length()
                        , cursor.getString(cursor.getColumnIndex("band"))));
            }

            if (cursor.getString(cursor.getColumnIndex("freq")) != null) {
                logStr.append(String.format("<freq:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("freq")).length()
                        , cursor.getString(cursor.getColumnIndex("freq"))));
            }

            if (cursor.getString(cursor.getColumnIndex("station_callsign")) != null) {
                logStr.append(String.format("<station_callsign:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("station_callsign")).length()
                        , cursor.getString(cursor.getColumnIndex("station_callsign"))));
            }

            if (cursor.getString(cursor.getColumnIndex("my_gridsquare")) != null) {
                logStr.append(String.format("<my_gridsquare:%d>%s "
                        , cursor.getString(cursor.getColumnIndex("my_gridsquare")).length()
                        , cursor.getString(cursor.getColumnIndex("my_gridsquare"))));
            }

            if (cursor.getColumnIndex("operator") != -1) {
                if (cursor.getString(cursor.getColumnIndex("operator")) != null) {
                    logStr.append(String.format("<operator:%d>%s "
                            , cursor.getString(cursor.getColumnIndex("operator")).length()
                            , cursor.getString(cursor.getColumnIndex("operator"))));
                }
            }

            // POTA ADIF fields — emitted only when populated so non-POTA rows
            // remain byte-identical to the prior upload format.
            appendPotaField(logStr, cursor, "my_sig", "MY_SIG");
            appendPotaField(logStr, cursor, "my_sig_info", "MY_SIG_INFO");
            appendPotaField(logStr, cursor, "sig", "SIG");
            appendPotaField(logStr, cursor, "sig_info", "SIG_INFO");

            String comment = cursor.getString(cursor.getColumnIndex("comment"));

            //<comment:15>Distance: 99 km <eor>
            //When writing to db, must append " km"
            logStr.append(String.format("<comment:%d>%s <eor>\n"
                    , comment.length()
                    , comment));
        }

        cursor.close();
        return logStr.toString();
    }

    /** Append a POTA ADIF field if the column exists and is non-empty. */
    private static void appendPotaField(StringBuilder sb, Cursor cursor, String column, String adifName) {
        int idx = cursor.getColumnIndex(column);
        if (idx < 0) return;
        String value = cursor.getString(idx);
        if (value == null || value.isEmpty()) return;
        sb.append(String.format("<%s:%d>%s ", adifName, value.length(), value));
    }

    /**
     * Populate the "already worked" DXCC / CQ-zone / ITU-zone sets from the logbook.
     *
     * <p>Worked status is derived from the logged station's <b>callsign</b>
     * (QSLTable.call), looked up against the callsign-prefix database — the same
     * resolution used for live decodes ({@link CallsignDatabase#getMessagesLocation})
     * and for QSOs completed in-app ({@code MainViewModel} -> {@code addDxcc}). This
     * keeps the bulk loader consistent with those paths.
     *
     * <p>Previously this joined QSLTable to {@code dxcc_grid}/{@code cqzoneList}/
     * {@code ituList} on the QSO's grid square. That dropped every QSO logged without
     * a gridsquare (common for FT8 report-only exchanges) and any grid missing from the
     * lookup tables, so worked entities — including the operator's own DXCC — could be
     * absent and every decode would show "NEW DXCC".
     */
    @SuppressLint("Range")
    public void getQslDxccToMap() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "run: starting zone import...");

                CallsignDatabase callsignDatabase = GeneralVariables.callsignDatabase;
                if (callsignDatabase == null) {
                    Log.w(TAG, "run: callsign database not ready, skipping zone import");
                    return;
                }

                Cursor cursor = db.rawQuery(
                        "SELECT DISTINCT \"call\" FROM QSLTable WHERE \"call\" IS NOT NULL AND \"call\" <> ''",
                        null);
                int count = 0;
                while (cursor.moveToNext()) {
                    String call = cursor.getString(cursor.getColumnIndex("call"));
                    if (call == null) continue;
                    call = call.replace("<", "").replace(">", "").trim();
                    if (call.isEmpty()) continue;

                    CallsignInfo info = callsignDatabase.getCallInfo(call);
                    if (info == null) continue;

                    if (info.DXCC != null && !info.DXCC.isEmpty()) {
                        GeneralVariables.addDxcc(info.DXCC);
                    }
                    GeneralVariables.addCqZone(info.CQZone);
                    GeneralVariables.addItuZone(info.ITUZone);
                    count++;
                }
                cursor.close();

                Log.d(TAG, "run: zone import complete, resolved " + count + " logged callsigns");
            }
        }).start();

    }


    /**
     * Check if the QSO callsign exists; if it does, return TRUE and update isLotW_QSL
     *
     * @param record record
     * @return whether it exists
     */
    @SuppressLint("Range")
    public boolean checkQSLCallsign(QSLRecord record) {
        QSLRecord newRecord = record;
        newRecord.id = -1;
        //Check if the callsign already exists
        String querySQL = "select * from QslCallsigns WHERE (callsign=?)" +
                "and (startTime=?) and(finishTime=?)" +
                "and(mode=?)";

        Cursor cursor = db.rawQuery(querySQL, new String[]{
                record.getToCallsign()
                , record.getStartTime()
                , record.getEndTime()
                , record.getMode()});
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            newRecord.isLotW_QSL = cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1
                    || record.isLotW_QSL;
            newRecord.id = cursor.getLong(cursor.getColumnIndex("ID"));
        }
        cursor.close();
//        if (newRecord.id != -1) {//Record already exists
//            querySQL = "UPDATE   QslCallsigns set isLotW_QSL=? WHERE ID=?";
//            db.execSQL(querySQL, new Object[]{newRecord.isLotW_QSL ? "1" : "0", newRecord.id});
//        }
        return newRecord.id != -1;//
    }

    @SuppressLint("Range")
    public boolean checkIsQSL(QSLRecord record) {
        QSLRecord newRecord = record;
        newRecord.id = -1;
        //Check if the log record already exists
        String querySQL = "select * from QSLTable WHERE (call=?)" +
                "and (qso_date=?) and(time_on=?)" +
                "and(mode=?)";

        Cursor cursor = db.rawQuery(querySQL, new String[]{
                record.getToCallsign()
                , record.getQso_date()
                , record.getTime_on()
                , record.getMode()});
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            newRecord.isLotW_QSL = cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1
                    || record.isLotW_QSL;
            newRecord.id = cursor.getLong(cursor.getColumnIndex("id"));
        }
        cursor.close();

//        if (newRecord.id != -1) {//Record already exists
//            querySQL = "UPDATE   QSLTable set isLotW_QSL=? WHERE ID=?";
//            db.execSQL(querySQL, new Object[]{newRecord.isLotW_QSL ? "1" : "0", newRecord.id});
//        }
        return newRecord.id != -1;//
    }

    @SuppressLint("Range")
    public boolean doInsertQSLData(QSLRecord record,AfterInsertQSLData afterInsertQSLData) {
        if (record.getToCallsign() == null) {
            if (afterInsertQSLData!=null){
                afterInsertQSLData.doAfterInsert(true,true);//Invalid QSL
            }
            return false;
        }
        // POTA: if an activation is running, stamp MY_SIG/MY_SIG_INFO; if the worked
        // station is currently spotted on pota.app, stamp SIG/SIG_INFO too (P2P case).
        // No-op when no activation/spot, so non-POTA contacts are unaffected.
        radio.ks3ckc.ft8us.pota.PotaSessionManager.stampQso(
                record,
                radio.ks3ckc.ft8us.pota.PotaSpotsRepository.parkRefFor(record.getToCallsign()));

        String querySQL;
        if (!checkQSLCallsign(record)) {//If record doesn't exist, add it
            querySQL = "INSERT INTO  QslCallsigns (callsign" +
                    ",isQSL,isLotW_import,isLotW_QSL" +
                    ",startTime,finishTime,mode,grid,band,band_i)" +
                    "values(?,?,?,?,?,?,?,?,?,?)";
            db.execSQL(querySQL, new Object[]{record.getToCallsign()
                    , record.isQSL ? 1 : 0//Whether manually confirmed
                    , record.isLotW_import ? 1 : 0//Whether LoTW import
                    , record.isLotW_QSL ? 1 : 0//Whether LoTW confirmed
                    , record.getStartTime()
                    , record.getEndTime()
                    , record.getMode()
                    , record.getToMaidenGrid()
                    , BaseRigOperation.getFrequencyAllInfo(record.getBandFreq())
                    , record.getBandFreq()});
        } else {
            if (record.isQSL) {
                db.execSQL("UPDATE  QslCallsigns  SET isQSL=? " +
                                "WHERE  (callsign=?)AND(startTime=?)AND(finishTime=?)AND(mode=?)"
                        , new Object[]{1, record.getToCallsign(), record.getStartTime()
                                , record.getEndTime(), record.getMode()});
            }
            if (record.isLotW_import) {
                db.execSQL("UPDATE  QslCallsigns  SET isLotW_import=? " +
                                "WHERE  (callsign=?)AND(startTime=?)AND(finishTime=?)AND(mode=?)"
                        , new Object[]{1, record.getToCallsign(), record.getStartTime()
                                , record.getEndTime(), record.getMode()});
            }

            if (record.isLotW_QSL) {
                db.execSQL("UPDATE  QslCallsigns  SET isLotW_QSL=? " +
                                "WHERE  (callsign=?)AND(startTime=?)AND(finishTime=?)AND(mode=?)"
                        , new Object[]{1, record.getToCallsign(), record.getStartTime()
                                , record.getEndTime(), record.getMode()});
            }
            if (record.getToMaidenGrid().length() >= 4) {
                db.execSQL("UPDATE  QslCallsigns  SET grid=? " +
                                "WHERE  (callsign=?)AND(startTime=?)AND(finishTime=?)AND(mode=?)"
                        , new Object[]{record.getToMaidenGrid(), record.getToCallsign(), record.getStartTime()
                                , record.getEndTime(), record.getMode()});
            }

        }


        if (!checkIsQSL(record)) {//If log data doesn't exist, add it
            querySQL = "INSERT INTO QSLTable(call, isQSL,isLotW_import,isLotW_QSL,gridsquare, mode, rst_sent, rst_rcvd, qso_date, " +
                    "time_on, qso_date_off, time_off, band, freq, station_callsign, my_gridsquare," +
                    "comment,my_sig,my_sig_info,sig,sig_info)VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

            db.execSQL(querySQL, new String[]{record.getToCallsign()
                    , String.valueOf(record.isQSL ? 1 : 0)
                    , String.valueOf(record.isLotW_import ? 1 : 0)
                    , String.valueOf(record.isLotW_QSL ? 1 : 0)
                    , record.getToMaidenGrid()
                    , record.getMode()
                    , String.valueOf(record.getSendReport())
                    , String.valueOf(record.getReceivedReport())
                    , record.getQso_date()
                    , record.getTime_on()

                    , record.getQso_date_off()
                    , record.getTime_off()
                    , record.getBandLength()//band length//RigOperationConstant.getMeterFromFreq(qslRecord.getBandFreq())
                    , BaseRigOperation.getFrequencyFloat(record.getBandFreq())
                    , record.getMyCallsign()
                    , record.getMyMaidenGrid()
                    , record.getComment()
                    , record.getMySig()
                    , record.getMySigInfo()
                    , record.getSig()
                    , record.getSigInfo()});
            // If this QSO was logged during an active POTA activation, bump its qso_count.
            if (record.getMySigInfo() != null && !record.getMySigInfo().isEmpty()) {
                db.execSQL("UPDATE pota_activation SET qso_count = qso_count + 1 "
                        + "WHERE park_ref = ? AND ended_at IS NULL"
                        , new Object[]{record.getMySigInfo()});
            }
            if (afterInsertQSLData!=null){
                afterInsertQSLData.doAfterInsert(false,true);//New QSL
            }

        } else {
            if (record.isQSL) {
                db.execSQL("UPDATE  QSLTable  SET isQSL=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{1, record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.isLotW_import) {
                db.execSQL("UPDATE  QSLTable  SET isLotW_import=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{1, record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.isLotW_QSL) {
                db.execSQL("UPDATE  QSLTable  SET isLotW_QSL=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{1, record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.getToMaidenGrid().length() >= 4) {
                db.execSQL("UPDATE  QSLTable  SET gridsquare=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{record.getToMaidenGrid(), record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.getMyMaidenGrid().length() >= 4) {
                db.execSQL("UPDATE  QSLTable  SET my_gridsquare=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{record.getMyMaidenGrid(), record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.getSendReport() > -100) {
                db.execSQL("UPDATE  QSLTable  SET rst_sent=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{record.getSendReport(), record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.getReceivedReport() > -100) {
                db.execSQL("UPDATE  QSLTable  SET rst_rcvd=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{record.getReceivedReport(), record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }

            if (afterInsertQSLData!=null){
                afterInsertQSLData.doAfterInsert(false,false);//Already exists, QSL needs updating
            }
        }
        return true;
    }


    /**
     * Class for querying configuration info
     */
    static class QueryConfig extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final String KeyName;
        private final OnAfterQueryConfig afterQueryConfig;

        public QueryConfig(SQLiteDatabase db, String keyName, OnAfterQueryConfig afterQueryConfig) {
            this.db = db;
            KeyName = keyName;
            this.afterQueryConfig = afterQueryConfig;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (afterQueryConfig != null) {
                afterQueryConfig.doOnBeforeQueryConfig(KeyName);
            }
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "select keyName,Value from config where KeyName =?";
            Cursor cursor = db.rawQuery(querySQL, new String[]{KeyName.toString()});
            if (cursor.moveToFirst()) {
                if (afterQueryConfig != null) {
                    afterQueryConfig.doOnAfterQueryConfig(KeyName, cursor.getString(cursor.getColumnIndex("Value")));
                }
            } else {
                if (afterQueryConfig != null) {
                    afterQueryConfig.doOnAfterQueryConfig(KeyName, "");
                }
            }
            cursor.close();
            return null;
        }
    }

    static class QueryCallsign extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final String tableName;
        private final String fieldName;
        private final String callSign;
        private OnGetCallsign onGetCallsign;

        public QueryCallsign(SQLiteDatabase db, String tableName, String fieldName
                , String callSign, OnGetCallsign onGetCallsign) {
            this.db = db;
            this.tableName = tableName;
            this.fieldName = fieldName;
            this.callSign = callSign;
            this.onGetCallsign = onGetCallsign;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String sql = String.format("select count(%s) as a FROM %s where %s=\"%s\" limit 1"
                    , fieldName, tableName, fieldName, callSign);
            Cursor cursor = db.rawQuery(sql, null);
            if (cursor.moveToFirst()) {
                if (onGetCallsign != null) {
                    onGetCallsign.doOnAfterGetCallSign(cursor.getInt(cursor.getColumnIndex("a")) > 0);
                }
            } else {
                if (onGetCallsign != null) {
                    onGetCallsign.doOnAfterGetCallSign(false);
                }

            }
            cursor.close();
            return null;
        }
    }

    /**
     * Class for writing configuration info
     */
    static class WriteConfig extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final String KeyName;
        private final String Value;
        private final OnAfterWriteConfig afterWriteConfig;

        public WriteConfig(SQLiteDatabase db, String keyName, String Value, OnAfterWriteConfig afterWriteConfig) {
            this.db = db;
            this.KeyName = keyName;
            this.afterWriteConfig = afterWriteConfig;
            this.Value = Value;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "DELETE FROM config where KeyName =?";
            db.execSQL(querySQL, new String[]{KeyName.toString()});
            querySQL = "INSERT INTO config (KeyName,Value)Values(?,?)";
            db.execSQL(querySQL, new String[]{KeyName.toString(), Value.toString()});
            if (afterWriteConfig != null) {
                afterWriteConfig.doOnAfterWriteConfig(true);
            }
            return null;
        }
    }

    /**
     * Write messages to the database
     */
    static class WriteMessages extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private ArrayList<Ft8Message> messages;

        public WriteMessages(SQLiteDatabase db, ArrayList<Ft8Message> messages) {
            this.db = db;
            this.messages = messages;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            String sql = "INSERT INTO SWLMessages(I3,N3,Protocol,UTC,SNR,TIME_SEC,FREQ,CALL_FROM" +
                    ",CALL_TO,EXTRAL,REPORT,BAND)\n" +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
            for (Ft8Message message : messages) {//Only save messages related to me
                db.execSQL(sql, new Object[]{message.i3, message.n3, "FT8"
                        ,UtcTimer.getDatetimeYYYYMMDD_HHMMSS(message.utcTime)
                        , message.snr, message.time_sec, Math.round(message.freq_hz)
                        , message.callsignFrom, message.callsignTo, message.extraInfo
                        , message.report, message.band});

            }
            return null;
        }
    }

    /**
     * Write followed callsigns to the database
     */
    static class AddFollowCallSign extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final String callSign;

        public AddFollowCallSign(SQLiteDatabase db, String callSign) {
            this.db = db;
            this.callSign = callSign;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "INSERT OR IGNORE INTO  followCallsigns (callsign)values(?)";
            db.execSQL(querySQL, new String[]{callSign});
            return null;
        }
    }

    /**
     * Write data to the callsign-grid mapping table. AsyncTask String params are multi-param, passed as array to doInBackground.
     * First element is callsign, second is grid.
     */
    static class AddCallsignQTH extends AsyncTask<String, Void, Void> {
        private final SQLiteDatabase db;

        public AddCallsignQTH(SQLiteDatabase db) {
            this.db = db;
        }

        @Override
        protected Void doInBackground(String... strings) {
            if (strings.length == 2) {
                String querySQL = "INSERT OR REPLACE  INTO  CallsignQTH  (callsign,grid,updateTime)" +
                        "VALUES (Upper(?),?,?)";
                db.execSQL(querySQL, new Object[]{strings[0], strings[1], System.currentTimeMillis()});
            }
            return null;
        }
    }

    static class Add_SWL_QSO_Info extends AsyncTask<Void, Void, Void>{
        private final DatabaseOpr databaseOpr;
        private QSLRecord qslRecord;
        public Add_SWL_QSO_Info(DatabaseOpr opr, QSLRecord qslRecord) {
            this.databaseOpr = opr;
            this.qslRecord = qslRecord;
        }
        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL;
            //Delete duplicate records first
            querySQL = "DELETE FROM  SWLQSOTable where ([call]=?) and (station_callsign=?) and (qso_date=?) and(time_on=?) and (freq=?)";
            databaseOpr.db.execSQL(querySQL, new String[]{
                             qslRecord.getToCallsign()
                            , qslRecord.getMyCallsign()
                            , qslRecord.getQso_date()
                            , qslRecord.getTime_on()
                            , BaseRigOperation.getFrequencyFloat(qslRecord.getBandFreq())
                    });
            //Add record
            querySQL = "INSERT INTO SWLQSOTable([call], gridsquare, mode, rst_sent, rst_rcvd, qso_date, " +
                    "time_on, qso_date_off, time_off, band, freq, station_callsign, my_gridsquare,operator,comment)\n" +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

            databaseOpr.db.execSQL(querySQL, new String[]{qslRecord.getToCallsign()
                    , qslRecord.getToMaidenGrid()
                    , qslRecord.getMode()
                    , String.valueOf(qslRecord.getSendReport())
                    , String.valueOf(qslRecord.getReceivedReport())
                    , qslRecord.getQso_date()
                    , qslRecord.getTime_on()

                    , qslRecord.getQso_date_off()
                    , qslRecord.getTime_off()
                    , qslRecord.getBandLength()//band length//RigOperationConstant.getMeterFromFreq(qslRecord.getBandFreq())
                    , BaseRigOperation.getFrequencyFloat(qslRecord.getBandFreq())
                    , qslRecord.getMyCallsign()
                    , qslRecord.getMyMaidenGrid()
                    , GeneralVariables.myCallsign//My callsign, not the other party's callsign
                    , qslRecord.getComment()});


            return null;
        }

    }

    /**
     * Write successfully QSL'd callsigns to the database
     */
    static class AddQSL_Info extends AsyncTask<Void, Void, Void> {
        //private final SQLiteDatabase db;
        private final DatabaseOpr databaseOpr;
        private QSLRecord qslRecord;

        public AddQSL_Info(DatabaseOpr opr, QSLRecord qslRecord) {
            this.databaseOpr = opr;
            this.qslRecord = qslRecord;
        }


        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            databaseOpr.doInsertQSLData(qslRecord,null);//Insert log and successfully contacted callsign
            return null;
        }
    }


    /**
     * Delete a followed callsign from the database
     */
    static class DeleteFollowCallsign extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final String callSign;

        public DeleteFollowCallsign(SQLiteDatabase db, String callSign) {
            this.db = db;
            this.callSign = callSign;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "DELETE  from followCallsigns  WHERE callsign=?";
            db.execSQL(querySQL, new String[]{callSign});
            return null;
        }
    }

    /**
     * Look up grid from the callsign-grid mapping table; parameter is callsign
     */
    static class GetCallsignQTH extends AsyncTask<String, Void, Void> {
        private final SQLiteDatabase db;

        GetCallsignQTH(SQLiteDatabase db) {
            this.db = db;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(String... strings) {
            if (strings.length == 0) return null;
            String querySQL = "select grid from CallsignQTH cq \n" +
                    "WHERE callsign =?";
            Cursor cursor = db.rawQuery(querySQL, new String[]{strings[0]});
            if (cursor.moveToFirst()) {
                GeneralVariables.addCallsignAndGrid(strings[0]
                        , cursor.getString(cursor.getColumnIndex("grid")));
            }
            cursor.close();

            return null;
        }
    }

    static class GetMessageLogTotal extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns;

        public GetMessageLogTotal(SQLiteDatabase db, OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns) {
            this.db = db;
            this.onAffterQueryFollowCallsigns = onAffterQueryFollowCallsigns;
        }

        @Override
        @SuppressLint({"Range", "DefaultLocale"})
        protected Void doInBackground(Void... voids) {
            String querySQL = "SELECT BAND ,count(*) as c from SWLMessages m group by BAND order by BAND ";
            Cursor cursor = db.rawQuery(querySQL, new String[]{});
            ArrayList<String> callsigns = new ArrayList<>();
            callsigns.add(GeneralVariables.getStringFromResource(R.string.band_total));
            callsigns.add("---------------------------------------");
            int sum = 0;
            while (cursor.moveToNext()) {
                long s = cursor.getLong(cursor.getColumnIndex("BAND")); //Get band
                int total = cursor.getInt(cursor.getColumnIndex("c")); //Get count
                callsigns.add(String.format("%.3fMHz \t %d", s / 1000000f, total));
                sum = sum + total;
            }
            callsigns.add(String.format("-----------Total %d -----------", sum));
            cursor.close();
            if (onAffterQueryFollowCallsigns != null) {
                onAffterQueryFollowCallsigns.doOnAfterQueryFollowCallsigns(callsigns);
            }
            return null;
        }
    }


    static class GetSWLQsoTotal extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns;

        public GetSWLQsoTotal(SQLiteDatabase db, OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns) {
            this.db = db;
            this.onAffterQueryFollowCallsigns = onAffterQueryFollowCallsigns;
        }

        @Override
        @SuppressLint({"Range", "DefaultLocale"})
        protected Void doInBackground(Void... voids) {
            String querySQL = "select count(*) as c,substr(qso_date_off,1,6) as t \n" +
                    "from SWLQSOTable s\n" +
                    "group by substr(qso_date_off,1,6)";
            Cursor cursor = db.rawQuery(querySQL, new String[]{});
            ArrayList<String> callsigns = new ArrayList<>();
            //callsigns.add(GeneralVariables.getStringFromResource(R.string.band_total));
            callsigns.add("---------------------------------------");
            int sum = 0;
            while (cursor.moveToNext()) {
                String date = cursor.getString(cursor.getColumnIndex("t")); //Get date
                int total = cursor.getInt(cursor.getColumnIndex("c")); //Get count
                callsigns.add(String.format("%s \t %d ", date, total));
                sum = sum + total;
            }
            callsigns.add(String.format("-----------Total %d -----------", sum));
            cursor.close();
            if (onAffterQueryFollowCallsigns != null) {
                onAffterQueryFollowCallsigns.doOnAfterQueryFollowCallsigns(callsigns);
            }
            return null;
        }
    }



    /**
     * Get followed callsigns from the database
     */
    static class GetFollowCallSigns extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns;

        public GetFollowCallSigns(SQLiteDatabase db, OnAfterQueryFollowCallsigns onAffterQueryFollowCallsigns) {
            this.db = db;
            this.onAffterQueryFollowCallsigns = onAffterQueryFollowCallsigns;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL = "select callsign from followCallsigns";
            Cursor cursor = db.rawQuery(querySQL, new String[]{});
            ArrayList<String> callsigns = new ArrayList<>();
            while (cursor.moveToNext()) {
                @SuppressLint("Range")
                String s = cursor.getString(cursor.getColumnIndex("callsign")); //Get the first column value (index starts at 0)
                if (s != null) {
                    callsigns.add(s);
                }
            }
            cursor.close();
            if (onAffterQueryFollowCallsigns != null) {
                onAffterQueryFollowCallsigns.doOnAfterQueryFollowCallsigns(callsigns);
            }
            return null;
        }
    }

    public static class GetCallsignMapGrid extends AsyncTask<Void, Void, Void> {
        SQLiteDatabase db;

        public GetCallsignMapGrid(SQLiteDatabase db) {
            this.db = db;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {

            String querySQL = "select DISTINCT callsign,grid from QslCallsigns qc \n" +
                    "where LENGTH(grid)>3\n" +
                    "order by ID ";
            Cursor cursor = db.rawQuery(querySQL, null);
            while (cursor.moveToNext()) {
                GeneralVariables.addCallsignAndGrid(cursor.getString(cursor.getColumnIndex("callsign"))
                        , cursor.getString(cursor.getColumnIndex("grid")));

            }
            cursor.close();
            return null;
        }
    }

    public interface OnGetQsoGrids {
        void onAfterQuery(HashMap<String, Boolean> grids);
    }


    static class GetQsoGrids extends AsyncTask<Void, Void, Void> {
        SQLiteDatabase db;
        HashMap<String, Boolean> grids = new HashMap<>();
        OnGetQsoGrids onGetQsoGrids;

        public GetQsoGrids(SQLiteDatabase db, OnGetQsoGrids onGetQsoGrids) {
            this.db = db;
            this.onGetQsoGrids = onGetQsoGrids;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {

            String querySQL = "select qc.gridsquare ,count(*) as cc,SUM(isQSL)+SUM(isLotW_QSL)as isQSL\n" +
                    "from QSLTable  qc\n" +
                    "WHERE LENGTH (qc.gridsquare)>2 \n" +
                    "group by qc.gridsquare\n" +
                    "ORDER by SUM(isQSL)+SUM(isLotW_QSL) desc";
            Cursor cursor = db.rawQuery(querySQL, null);

            while (cursor.moveToNext()) {
                grids.put(cursor.getString(cursor.getColumnIndex("gridsquare"))
                        , cursor.getInt(cursor.getColumnIndex("isQSL")) != 0);

            }
            cursor.close();
            if (onGetQsoGrids != null) {
                onGetQsoGrids.onAfterQuery(grids);
            }
            return null;
        }
    }

    static class GetQSLByCallsign extends AsyncTask<Void, Void, Void> {
        boolean showAll;
        int offset;
        SQLiteDatabase db;
        String callsign;
        int filter;
        OnQueryQSLRecordCallsign onQueryQSLRecordCallsign;

        public GetQSLByCallsign(boolean showAll,int offset,SQLiteDatabase db, String callsign, int queryFilter, OnQueryQSLRecordCallsign onQueryQSLRecordCallsign) {
            this.showAll=showAll;
            this.offset=offset;
            this.db = db;
            this.callsign = callsign;
            this.filter = queryFilter;
            this.onQueryQSLRecordCallsign = onQueryQSLRecordCallsign;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String filterStr;
            switch (filter) {
                case 1:
                    filterStr = "and((isQSL =1)or(isLotW_QSL =1))\n";
                    break;
                case 2:
                    filterStr = "and((isQSL =0)and(isLotW_QSL =0))\n";
                    break;
                default:
                    filterStr = "";
            }
            String limitStr="";
            if (!showAll){
                limitStr="limit 100 offset "+offset;
            }
            String querySQL = "select * from QSLTable where ([call] like ?) \n" +
                    filterStr +
                    " ORDER BY qso_date DESC, time_off DESC\n"+
                    //" order by ID desc\n"+
                    limitStr;
            Cursor cursor = db.rawQuery(querySQL, new String[]{"%" + callsign + "%"});
            ArrayList<QSLRecordStr> records = new ArrayList<>();
            while (cursor.moveToNext()) {
                QSLRecordStr record = new QSLRecordStr();
                record.id = cursor.getInt(cursor.getColumnIndex("id"));
                record.setCall(cursor.getString(cursor.getColumnIndex("call")));
                record.isQSL = cursor.getInt(cursor.getColumnIndex("isQSL")) == 1;
                record.isLotW_import = cursor.getInt(cursor.getColumnIndex("isLotW_import")) == 1;
                record.isLotW_QSL = cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1;
                record.setGridsquare(cursor.getString(cursor.getColumnIndex("gridsquare")));
                record.setMode(cursor.getString(cursor.getColumnIndex("mode")));
                record.setRst_sent(cursor.getString(cursor.getColumnIndex("rst_sent")));
                record.setRst_rcvd(cursor.getString(cursor.getColumnIndex("rst_rcvd")));
                record.setTime_on(String.format("%s-%s"
                        , cursor.getString(cursor.getColumnIndex("qso_date"))
                        , cursor.getString(cursor.getColumnIndex("time_on"))));

                record.setTime_off(String.format("%s-%s"
                        , cursor.getString(cursor.getColumnIndex("qso_date_off"))
                        , cursor.getString(cursor.getColumnIndex("time_off"))));
                record.setBand(cursor.getString(cursor.getColumnIndex("band")));//Band wavelength
                record.setFreq(cursor.getString(cursor.getColumnIndex("freq")));//Frequency
                record.setStation_callsign(cursor.getString(cursor.getColumnIndex("station_callsign")));
                record.setMy_gridsquare(cursor.getString(cursor.getColumnIndex("my_gridsquare")));
                record.setComment(cursor.getString(cursor.getColumnIndex("comment")));
                records.add(record);
            }
            cursor.close();
            if (onQueryQSLRecordCallsign != null) {
                onQueryQSLRecordCallsign.afterQuery(records);
            }
            return null;
        }
    }

    /**
     * Query successfully contacted callsigns by callsign
     */
    static class GetQLSCallsignByCallsign extends AsyncTask<Void, Void, Void> {
        SQLiteDatabase db;
        String callsign;
        int filter;
        OnQueryQSLCallsign onQueryQSLCallsign;
        int offset;
        boolean showAll;

        public GetQLSCallsignByCallsign(boolean showAll,int offset,SQLiteDatabase db, String callsign, int queryFilter, OnQueryQSLCallsign onQueryQSLCallsign) {
            this.showAll=showAll;
            this.offset=offset;
            this.db = db;
            this.callsign = callsign;
            this.filter = queryFilter;
            this.onQueryQSLCallsign = onQueryQSLCallsign;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String filterStr;
            switch (filter) {
                case 1:
                    filterStr = "and((q.isQSL =1)or(q.isLotW_QSL =1))\n";
                    break;
                case 2:
                    filterStr = "and((q.isQSL =0)and(q.isLotW_QSL =0))\n";
                    break;
                default:
                    filterStr = "";
            }
            String limitStr="";
            if (!showAll){
                limitStr="limit 100 offset "+offset;
            }
            String querySQL = "select max(q.id) as id, q.[call] as callsign ,q.gridsquare as grid" +
                    ",q.band||\"(\"||q.freq||\" MHz)\" as band \n" +
                    ",q.qso_date as last_time ,q.mode ,q.isQSL,q.isLotW_QSL\n" +
                    ",max(q.synced_cloudlog) as synced_cloudlog\n" +
                    ",max(q.synced_qrz) as synced_qrz\n" +
                    "from QSLTable q inner join QSLTable q2 ON q.id =q2.id \n" +
                    "where (q.[call] like ?)\n" +
                    filterStr +
                    "group by q.[call] ,q.gridsquare,q.freq ,q.qso_date,q.band\n" +
                    ",q.mode,q.isQSL,q.isLotW_QSL\n" +
                    "HAVING q.qso_date =MAX(q2.qso_date) \n" +
                    "order by q.qso_date desc\n"+
                    limitStr;


            Cursor cursor = db.rawQuery(querySQL, new String[]{"%" + callsign + "%"});
            ArrayList<QSLCallsignRecord> records = new ArrayList<>();
            while (cursor.moveToNext()) {
                QSLCallsignRecord record = new QSLCallsignRecord();
                record.id = cursor.getInt(cursor.getColumnIndex("id"));
                record.setCallsign(cursor.getString(cursor.getColumnIndex("callsign")));
                record.isQSL = cursor.getInt(cursor.getColumnIndex("isQSL")) == 1;
                record.isLotW_QSL = cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1;
                int idxCl = cursor.getColumnIndex("synced_cloudlog");
                int idxQrz = cursor.getColumnIndex("synced_qrz");
                record.syncedCloudlog = idxCl >= 0 && cursor.getInt(idxCl) == 1;
                record.syncedQrz = idxQrz >= 0 && cursor.getInt(idxQrz) == 1;
                record.setLastTime(cursor.getString(cursor.getColumnIndex("last_time")));
                record.setMode(cursor.getString(cursor.getColumnIndex("mode")));
                record.setGrid(cursor.getString(cursor.getColumnIndex("grid")));
                record.setBand(cursor.getString(cursor.getColumnIndex("band")));
                records.add(record);
            }
            cursor.close();
            if (onQueryQSLCallsign != null) {
                onQueryQSLCallsign.afterQuery(records);
            }
            return null;
        }
    }


    /**
     * Get all previously contacted callsigns
     */
    @SuppressLint("DefaultLocale")
    static class GetAllQSLCallsign {
        public static void get(SQLiteDatabase db) {

            //String querySQL = "select distinct [call] from QSLTable where freq=?";
            //Changed to get contacted callsigns by band wavelength
            String querySQL = "select distinct [call] from QSLTable where band=?";
            Cursor cursor = db.rawQuery(querySQL, new String[]{
                    BaseRigOperation.getMeterFromFreq(GeneralVariables.band)});
            ArrayList<String> callsigns = new ArrayList<>();
            while (cursor.moveToNext()) {
                @SuppressLint("Range")
                String s = cursor.getString(cursor.getColumnIndex("call"));
                if (s != null) {
                    callsigns.add(s);
                }
            }
            cursor.close();
            GeneralVariables.QSL_Callsign_list = callsigns;

            querySQL = "select distinct [call] from QSLTable where band<>?";
            cursor = db.rawQuery(querySQL, new String[]{
                    BaseRigOperation.getMeterFromFreq(GeneralVariables.band)});

            ArrayList<String> other_callsigns = new ArrayList<>();
            while (cursor.moveToNext()) {
                @SuppressLint("Range")
                String s = cursor.getString(cursor.getColumnIndex("call"));
                if (s != null) {
                    other_callsigns.add(s);
                }
            }
            cursor.close();
            GeneralVariables.QSL_Callsign_list_other_band = other_callsigns;

            // Load distinct 4-char worked grids (any band) into in-memory set
            querySQL = "select distinct upper(substr(gridsquare,1,4)) as g from QSLTable" +
                    " where gridsquare is not null and length(gridsquare) >= 4";
            cursor = db.rawQuery(querySQL, null);
            java.util.HashSet<String> grids = new java.util.HashSet<>();
            while (cursor.moveToNext()) {
                @SuppressLint("Range")
                String g = cursor.getString(cursor.getColumnIndex("g"));
                if (g != null && g.length() >= 4) {
                    grids.add(g);
                }
            }
            cursor.close();
            GeneralVariables.QSL_Grid_list = grids;
        }

    }


    /**
     * Delete a contacted callsign by ID
     */
    static class DeleteQSLCallsignByID extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final int id;

        public DeleteQSLCallsignByID(SQLiteDatabase db, int id) {
            this.db = db;
            this.id = id;
        }


        @Override
        protected Void doInBackground(Void... voids) {
            db.execSQL("delete from QslCallsigns where id=?", new Object[]{id});
            return null;
        }
    }


    /**
     * Delete a log entry by ID
     */
    static class DeleteQSLByID extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final int id;

        public DeleteQSLByID(SQLiteDatabase db, int id) {
            this.db = db;
            this.id = id;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            db.execSQL("delete from QSLTable where id=?", new Object[]{id});
            return null;
        }
    }

    static class SetQSLCallsignIsQSL extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final int id;
        private final boolean isQSL;

        public SetQSLCallsignIsQSL(SQLiteDatabase db, int id, boolean isQSL) {
            this.db = db;
            this.id = id;
            this.isQSL = isQSL;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            db.execSQL("UPDATE QslCallsigns SET isQSL=? where id=?", new Object[]{isQSL ? "1" : "0", id});
            return null;
        }
    }

    /**
     * Set manual QSL confirmation for a log entry
     */
    static class SetQSLTableIsQSL extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final int id;
        private final boolean isQSL;

        public SetQSLTableIsQSL(SQLiteDatabase db, int id, boolean isQSL) {
            this.db = db;
            this.id = id;
            this.isQSL = isQSL;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            db.execSQL("UPDATE QSLTable SET isQSL=? where id=?", new Object[]{isQSL ? "1" : "0", id});
            return null;
        }
    }


    /**
     * Query all successfully contacted callsigns, filtered by the operating band
     */
    static class LoadAllQSLCallsigns extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;

        public LoadAllQSLCallsigns(SQLiteDatabase db) {
            this.db = db;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            GetAllQSLCallsign.get(db);//Get previously contacted callsigns
            return null;
        }
    }

    static class GetAllConfigParameter extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private OnAfterQueryConfig onAfterQueryConfig;

        public GetAllConfigParameter(SQLiteDatabase db, OnAfterQueryConfig onAfterQueryConfig) {
            this.db = db;
            this.onAfterQueryConfig = onAfterQueryConfig;
        }

        @SuppressLint("Range")
        private String getConfigByKey(String KeyName) {
            String querySQL = "select keyName,Value from config where KeyName =?";
            Cursor cursor = db.rawQuery(querySQL, new String[]{KeyName});
            String result = "";
            if (cursor.moveToFirst()) {
                result = cursor.getString(cursor.getColumnIndex("Value"));
            }
            cursor.close();
            return result;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {

            String querySQL = "select keyName,Value from config ";
            Cursor cursor = db.rawQuery(querySQL, null);
            while (cursor.moveToNext()) {
                @SuppressLint("Range")
                //String result = "";
                String result = cursor.getString(cursor.getColumnIndex("Value"));
                String name = cursor.getString(cursor.getColumnIndex("KeyName"));

                if (name.equalsIgnoreCase("grid")) {
                    GeneralVariables.setMyMaidenheadGrid(result);
                }
                if (name.equalsIgnoreCase("callsign")) {
                    GeneralVariables.myCallsign = result;
                    String callsign = GeneralVariables.myCallsign;
                    if (callsign.length() > 0) {
                        Ft8Message.hashList.addHash(FT8Package.getHash22(callsign), callsign);
                        Ft8Message.hashList.addHash(FT8Package.getHash12(callsign), callsign);
                        Ft8Message.hashList.addHash(FT8Package.getHash10(callsign), callsign);
                        if (callsign.contains("/")) {
                            String shortCallsign = GeneralVariables.getShortCallsign(callsign);
                            Ft8Message.hashList.addHash(FT8Package.getHash22(shortCallsign), shortCallsign);
                            Ft8Message.hashList.addHash(FT8Package.getHash12(shortCallsign), shortCallsign);
                            Ft8Message.hashList.addHash(FT8Package.getHash10(shortCallsign), shortCallsign);
                        }
                    }
                }
                if (name.equalsIgnoreCase("toModifier")) {
                    GeneralVariables.toModifier = result;
                }
                if (name.equalsIgnoreCase("freq")) {
                    float freq = 1000;
                    try {
                        freq = Float.parseFloat(result);
                    } catch (Exception e) {
                        Log.e(TAG, "doInBackground: " + e.getMessage());
                    }
                    //GeneralVariables.setBaseFrequency(result.equals("") ? 1000 : Float.parseFloat(result));
                    GeneralVariables.setBaseFrequency(freq);
                }
                if (name.equalsIgnoreCase("synFreq")) {
                    GeneralVariables.synFrequency = !(result.equals("") || result.equals("0"));
                }
                if (name.equalsIgnoreCase("transDelay")) {
                    if (result.matches("^\\d{1,4}$")) {//Regex: 1-4 digit number
                        GeneralVariables.transmitDelay = Integer.parseInt(result);
                    } else {
                        GeneralVariables.transmitDelay = FT8Common.FT8_TRANSMIT_DELAY;
                    }
                }

                if (name.equalsIgnoreCase("civ")) {
                    GeneralVariables.civAddress = result.equals("") ? 0xa4 : Integer.parseInt(result, 16);
                }
                if (name.equalsIgnoreCase("baudRate")) {
                    GeneralVariables.baudRate = result.equals("") ? 19200 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("bandFreq")) {
                    GeneralVariables.band = result.equals("") ? 14074000 : Long.parseLong(result);
                    GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(GeneralVariables.band);
                }

                if (name.equalsIgnoreCase("msgMode")) {
                    GeneralVariables.simpleCallItemMode = result.equals("1") ;
                }

                if (name.equalsIgnoreCase("ctrMode")) {
                    GeneralVariables.controlMode = result.equals("") ? ControlMode.VOX : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("connectMode")) {
                    GeneralVariables.connectMode = result.equals("") ? ConnectMode.USB_CABLE : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("model")) {//Radio model
                    GeneralVariables.modelNo = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("instruction")) {//Instruction set
                    GeneralVariables.instructionSet = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("launchSupervision")) {//Transmit supervision
                    GeneralVariables.launchSupervision = result.equals("") ?
                            GeneralVariables.DEFAULT_LAUNCH_SUPERVISION : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("noReplyLimit")) {//
                    GeneralVariables.noReplyLimit = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("autoFollowCQ")) {//Auto-follow CQ
                    GeneralVariables.autoFollowCQ = (result.equals("") || result.equals("1"));
                }
                if (name.equalsIgnoreCase("autoCallFollow")) {//Auto-call followed stations
                    GeneralVariables.autoCallFollow = (result.equals("") || result.equals("1"));
                }
                if (name.equalsIgnoreCase("autoGridFromGPS")) {//Auto-update grid from GPS
                    GeneralVariables.autoUpdateGridFromGPS = result.equals("1");
                }
                if (name.equalsIgnoreCase("pttDelay")) {//PTT delay setting
                    GeneralVariables.pttDelay = result.equals("") ? 100 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("lateStartTolerance")) {//Late-start tolerance, ms (0-4000)
                    try {
                        int v = result.equals("") ? 2000 : Integer.parseInt(result);
                        if (v < 0) v = 0;
                        if (v > 4000) v = 4000;
                        GeneralVariables.lateStartTolerance = v;
                    } catch (NumberFormatException nfe) {
                        GeneralVariables.lateStartTolerance = 2000;
                    }
                }
                if (name.equalsIgnoreCase("icomIp")) {//ICOM IP address
                    GeneralVariables.icomIp = result.equals("") ? "255.255.255.255" : result;
                }
                if (name.equalsIgnoreCase("icomPort")) {//ICOM port
                    GeneralVariables.icomUdpPort = result.equals("") ? 50001 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("icomUserName")) {//ICOM username
                    GeneralVariables.icomUserName = result.equals("") ? "ic705" : result;
                }
                if (name.equalsIgnoreCase("icomPassword")) {//ICOM password
                    GeneralVariables.icomPassword = result;
                }
                if (name.equalsIgnoreCase("volumeValue")) {//Output volume level
                    GeneralVariables.volumePercent = result.equals("") ? 1.0f : Float.parseFloat(result) / 100f;
                }
                if (name.equalsIgnoreCase("excludedCallsigns")) {//Excluded callsigns
                    GeneralVariables.addExcludedCallsigns(result);
                }
                if (name.equalsIgnoreCase("flexMaxRfPower")) {//Flex max RF power
                    GeneralVariables.flexMaxRfPower = result.equals("") ? 10 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("flexMaxTunePower")) {//Flex max tune power
                    GeneralVariables.flexMaxTunePower = result.equals("") ? 10 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("saveSWL")) {//Save decoded messages
                    GeneralVariables.saveSWLMessage = result.equals("1");
                }
                if (name.equalsIgnoreCase("saveSWLQSO")) {//Save SWL QSO data
                    GeneralVariables.saveSWL_QSO = result.equals("1");
                }
                if (name.equalsIgnoreCase("audioBits")) {//Output audio 32-bit float
                    GeneralVariables.audioOutput32Bit = result.equals("1");
                }
                if (name.equalsIgnoreCase("audioRate")) {//Output audio sample rate
                    GeneralVariables.audioSampleRate =Integer.parseInt( result);
                }
                if (name.equalsIgnoreCase("audioInputDevice")) {//Audio input device ID
                    GeneralVariables.audioInputDeviceId = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("audioOutputDevice")) {//Audio output device ID
                    GeneralVariables.audioOutputDeviceId = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("usbAudioInputVid")) {
                    GeneralVariables.usbAudioInputVendorId = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("usbAudioInputPid")) {
                    GeneralVariables.usbAudioInputProductId = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("usbAudioOutputVid")) {
                    GeneralVariables.usbAudioOutputVendorId = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("usbAudioOutputPid")) {
                    GeneralVariables.usbAudioOutputProductId = result.equals("") ? 0 : Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("deepMode")) {//Deep decode mode
                    GeneralVariables.deepDecodeMode =result.equals("1");
                }
                if (name.equalsIgnoreCase("debugModeEnabled")) {//Hidden debug screen unlock
                    GeneralVariables.debugModeEnabled = result.equals("1");
                }
                if (name.equalsIgnoreCase("dataBits")) {//Serial data bits
                    GeneralVariables.serialDataBits =Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("stopBits")) {//Serial stop bits
                    GeneralVariables.serialStopBits =Integer.parseInt(result);
                }
                if (name.equalsIgnoreCase("parityBits")) {//Serial parity bits
                    GeneralVariables.serialParity =Integer.parseInt(result);
                }

                // cloudlogs
                if (name.equalsIgnoreCase("enableCloudlog")) {
                    GeneralVariables.enableCloudlog = result.equals("1");
                }
                if (name.equalsIgnoreCase("cloudlogServerAddress")) {
                    GeneralVariables.cloudlogServerAddress = result;
                }
                if (name.equalsIgnoreCase("cloudlogApiKey")) {
                    GeneralVariables.cloudlogApiKey = result;
                }
                if (name.equalsIgnoreCase("cloudlogStationID")) {
                    GeneralVariables.cloudlogStationID = result;
                }

                //QRZ
                if (name.equalsIgnoreCase("enableQRZ")) {
                    GeneralVariables.enableQRZ = result.equals("1");
                }
                if (name.equalsIgnoreCase("qrzApiKey")) {
                    GeneralVariables.qrzApiKey = result;
                }
                if (name.equalsIgnoreCase("enablePskReporter")) {
                    GeneralVariables.enablePskReporter = result.equals("1");
                }
                if (name.equalsIgnoreCase("qrzXmlUsername")) {
                    GeneralVariables.qrzXmlUsername = result;
                }
                if (name.equalsIgnoreCase("qrzXmlPassword")) {
                    GeneralVariables.qrzXmlPassword = result;
                }
                if (name.equalsIgnoreCase("pskOverlayEnabled")) {
                    GeneralVariables.pskOverlayEnabled = result.equals("1");
                }

                if (name.equalsIgnoreCase("swrSwitch")) {
                    GeneralVariables.swr_switch_on = result.equals("1");
                }
                if (name.equalsIgnoreCase("alcSwitch")) {
                    GeneralVariables.alc_switch_on = result.equals("1");
                }
                if (name.equalsIgnoreCase("spectrumWidth")) {
                    GeneralVariables.setSpectrumWidth(result.equals("") ? 3500 : Integer.parseInt(result));
                }

            }

            cursor.close();

            GetAllQSLCallsign.get(db);//Get previously contacted callsigns

            if (onAfterQueryConfig != null) {
                onAfterQueryConfig.doOnAfterQueryConfig(null, null);
            }

            return null;
        }
    }


}
