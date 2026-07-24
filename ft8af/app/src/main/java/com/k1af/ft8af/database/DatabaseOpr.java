package com.k1af.ft8af.database;
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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import android.util.Log;

import com.k1af.ft8af.FT8Common;
import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.callsign.CallsignDatabase;
import com.k1af.ft8af.callsign.CallsignInfo;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.ft8signal.FT8Package;
import com.k1af.ft8af.log.AdifFormat;
import com.k1af.ft8af.log.OnQueryQSLCallsign;
import com.k1af.ft8af.log.OnQueryQSLRecordCallsign;
import com.k1af.ft8af.log.QSLCallsignRecord;
import com.k1af.ft8af.log.QSLRecord;
import com.k1af.ft8af.log.QSLRecordStr;
import com.k1af.ft8af.rigs.BaseRigOperation;
import com.k1af.ft8af.timer.UtcTimer;
import com.k1af.ft8af.util.Streams;
import com.k1af.ft8af.wave.InputAudioLevel;

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
import java.util.Locale;

public class DatabaseOpr extends SQLiteOpenHelper {
    private static final String TAG = "DatabaseOpr";
    @SuppressLint("StaticFieldLeak")
    private static DatabaseOpr instance;
    private final Context context;
    private SQLiteDatabase db;


    public static synchronized DatabaseOpr getInstance(@Nullable Context context, @Nullable String databaseName) {
        if (instance == null) {
            instance = new DatabaseOpr(context, databaseName, null, 20);
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

        //Create per-location Wavelog station cache table (issue #437)
        createLocationStationTables(sqLiteDatabase);

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

        //Create per-location Wavelog station cache table (issue #437)
        createLocationStationTables(sqLiteDatabase);

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
            // Device lat/lon captured at QSO time, decimal degrees; NULL when location was
            // unavailable. Exported as ADIF MY_LAT/MY_LON.
            alterTable(sqLiteDatabase, "QSLTable", "my_lat"
                    , "my_lat REAL");
            alterTable(sqLiteDatabase, "QSLTable", "my_lon"
                    , "my_lon REAL");

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
                    "sig_info TEXT,\n" +//POTA: worked station's park ref
                    "my_lat REAL,\n" +//device lat at QSO time, decimal degrees; NULL if unavailable
                    "my_lon REAL)");//device lon at QSO time, decimal degrees; NULL if unavailable
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
     * Create the per-location Wavelog station cache table (issue #437). Maps a
     * canonical {@link com.k1af.ft8af.log.LocationSignature} string to the
     * {@code station_profile_id} that covers that location, so revisiting a place
     * reuses its station profile instead of creating a duplicate. Persisted across
     * sessions. Idempotent (guarded by {@link #checkTableExists}) so it is safe to
     * call from both onCreate and onUpgrade.
     */
    private void createLocationStationTables(SQLiteDatabase sqLiteDatabase) {
        if (!checkTableExists(sqLiteDatabase, "location_station_cache")) {
            sqLiteDatabase.execSQL("CREATE TABLE location_station_cache (\n" +
                    "signature TEXT PRIMARY KEY,\n" +
                    "station_profile_id TEXT NOT NULL,\n" +
                    "updated_at INTEGER NOT NULL)");//epoch millis
        }
    }

    /**
     * Upsert a {@code signature -> station_profile_id} mapping. Thin DAO wrapper;
     * the signature must already be canonicalized by
     * {@link com.k1af.ft8af.log.LocationSignature#signature()}. No-op on null args.
     * Part of the dark issue-#437 foundation — not yet called from any live path.
     */
    public void putStationForSignature(String signature, String profileId) {
        if (db == null || signature == null || profileId == null) {
            return;
        }
        try {
            db.execSQL("INSERT OR REPLACE INTO location_station_cache "
                            + "(signature, station_profile_id, updated_at) VALUES (?,?,?)",
                    new Object[]{signature, profileId, System.currentTimeMillis()});
        } catch (Exception e) {
            Log.w(TAG, "putStationForSignature failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Look up the cached {@code station_profile_id} for a canonical signature, or
     * null when there is no mapping (or on error / null input).
     */
    public String getStationForSignature(String signature) {
        if (db == null || signature == null) {
            return null;
        }
        try (Cursor cursor = db.rawQuery(
                "SELECT station_profile_id FROM location_station_cache WHERE signature = ?",
                new String[]{signature})) {
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "getStationForSignature failed: " + e.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * Every cached {@code signature -> station_profile_id} mapping, ordered by
     * signature for a deterministic read-back. Primarily for inspection/testing.
     */
    public java.util.List<com.k1af.ft8af.log.LocationStationCacheEntry> getAllStationSignatures() {
        java.util.List<com.k1af.ft8af.log.LocationStationCacheEntry> out = new ArrayList<>();
        if (db == null) {
            return out;
        }
        try (Cursor cursor = db.rawQuery(
                "SELECT signature, station_profile_id FROM location_station_cache "
                        + "ORDER BY signature", null)) {
            while (cursor.moveToNext()) {
                out.add(new com.k1af.ft8af.log.LocationStationCacheEntry(
                        cursor.getString(0), cursor.getString(1)));
            }
        } catch (Exception e) {
            Log.w(TAG, "getAllStationSignatures failed: " + e.getClass().getSimpleName());
        }
        return out;
    }

    /**
     * Every prior logged QSO with {@code callsign}, oldest first, as lightweight
     * {@link com.k1af.ft8af.log.PriorQso} rows (date/time/band/mode only). Powers
     * the decode sheet's "Worked before" card. Returns an empty list when the
     * station has never been worked, on a blank callsign, or on any read error.
     *
     * <p>The exact-match on {@code "call"} is served by the {@code QSLTable_call_IDX}
     * index, so this stays cheap even on a large log. Decoded callsigns are already
     * upper-cased (as are stored ones), so no case-folding is needed — folding here
     * would only defeat the index.
     */
    public java.util.List<com.k1af.ft8af.log.PriorQso> getPriorQsos(String callsign) {
        java.util.List<com.k1af.ft8af.log.PriorQso> out = new ArrayList<>();
        if (db == null || callsign == null) {
            return out;
        }
        String c = callsign.trim();
        if (c.isEmpty()) {
            return out;
        }
        try (Cursor cursor = db.rawQuery(
                "SELECT qso_date, time_on, band, mode FROM QSLTable "
                        + "WHERE \"call\" = ? ORDER BY qso_date, time_on",
                new String[]{c})) {
            while (cursor.moveToNext()) {
                out.add(new com.k1af.ft8af.log.PriorQso(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3)));
            }
        } catch (Exception e) {
            Log.w(TAG, "getPriorQsos failed: " + e.getClass().getSimpleName());
        }
        return out;
    }

    /** Drop every cached signature mapping (e.g. on server/logbook switch). */
    public void clearLocationStationCache() {
        if (db == null) {
            return;
        }
        try {
            db.execSQL("DELETE FROM location_station_cache");
        } catch (Exception e) {
            Log.w(TAG, "clearLocationStationCache failed: " + e.getClass().getSimpleName());
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
            byte[] bytes = Streams.readAllBytes(inputStream);
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
            byte[] bytes = Streams.readAllBytes(inputStream);
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
            byte[] bytes = Streams.readAllBytes(inputStream);
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
     * Parses a config-table int value, falling back when the stored string is
     * empty or not a number (a hand-edited or stale backup must not crash
     * startup hydration). Range clamping is the caller's (setter's) job.
     * Package-private static so it is unit-testable without a database.
     */
    static int parseConfigInt(String value, int fallback) {
        return parseConfigInt(value, fallback, 10);
    }

    /**
     * Radix-aware variant of {@link #parseConfigInt(String, int)} for keys
     * stored in a non-decimal base (e.g. the ICOM CI-V address, hex). Same
     * empty/non-numeric fallback contract.
     */
    static int parseConfigInt(String value, int fallback, int radix) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim(), radix);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * {@code long} counterpart of {@link #parseConfigInt(String, int)} for
     * config values that exceed {@code int} range (e.g. the band frequency in
     * Hz). Same empty/non-numeric fallback contract.
     */
    static long parseConfigLong(String value, long fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * {@code float} counterpart of {@link #parseConfigInt(String, int)} for
     * config values stored as a decimal (e.g. the output volume percent). Same
     * empty/non-numeric fallback contract. Any scaling the caller applied to
     * the old raw parse (e.g. {@code / 100f}) must be applied to the return
     * value so the empty/garbage fallback lands on the intended default.
     */
    static float parseConfigFloat(String value, float fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Read every config key/value pair synchronously into an insertion-ordered map.
     * Backs the settings-export feature (issue #357). Must be called off the main
     * thread (it touches SQLite directly).
     */
    public java.util.LinkedHashMap<String, String> getAllConfigSync() {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        // ORDER BY: SQLite guarantees no row order without it, so the map's insertion
        // order (which this method promises) would otherwise be nondeterministic.
        Cursor cursor = db.rawQuery("select KeyName,Value from config order by KeyName", null);
        try {
            int keyIdx = cursor.getColumnIndexOrThrow("KeyName");
            int valueIdx = cursor.getColumnIndexOrThrow("Value");
            while (cursor.moveToNext()) {
                // The schema allows NULL Value; coerce to "" so a backup export keeps the
                // key (JSONObject.put(key, null) drops it) and matches writeConfigSync's
                // null->"" import semantics.
                String value = cursor.isNull(valueIdx) ? "" : cursor.getString(valueIdx);
                map.put(cursor.getString(keyIdx), value);
            }
        } finally {
            cursor.close();
        }
        return map;
    }

    /**
     * Upsert every entry of {@code config} synchronously (same delete-then-insert as
     * {@link WriteConfig}). Backs the settings-import feature (issue #357). Must be
     * called off the main thread. After calling, run {@link #getAllConfigParameter}
     * to re-hydrate {@link GeneralVariables} from the freshly written values.
     */
    public void writeConfigSync(java.util.Map<String, String> config) {
        // One transaction: an interrupted import can't leave the table half-updated,
        // and batching the statements is much faster than autocommitting each one.
        db.beginTransaction();
        try {
            for (java.util.Map.Entry<String, String> entry : config.entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue();
                db.execSQL("DELETE FROM config where KeyName =?", new String[]{entry.getKey()});
                db.execSQL("INSERT INTO config (KeyName,Value)Values(?,?)",
                        new String[]{entry.getKey(), value});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
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
    @SuppressLint("Range")
    public String downQSLTable(Cursor cursor, boolean isSWL) {
        StringBuilder logStr = new StringBuilder();

        logStr.append("FT8AF ADIF Export<eoh>\n");
        cursor.moveToPosition(-1);
        while (cursor.moveToNext()) {
            logStr.append(com.k1af.ft8af.log.AdifFormat.callField(
                    cursor.getString(cursor.getColumnIndex("call"))));
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
                logStr.append(String.format(Locale.US, "<gridsquare:%d>%s "
                        , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("gridsquare")))
                        , cursor.getString(cursor.getColumnIndex("gridsquare"))));
            }

            if (cursor.getString(cursor.getColumnIndex("mode")) != null) {
                // FT4/FT2 are ADIF submodes of MFSK, not standalone modes — a bare
                // <mode>FT2 is rejected as invalid by pota.app and other ADIF consumers.
                String mode = cursor.getString(cursor.getColumnIndex("mode"));
                String submode = AdifFormat.mfskSubmode(mode);
                if (submode != null) {
                    logStr.append(String.format(Locale.US, "<mode:4>MFSK <submode:%d>%s "
                            , AdifFormat.utf8Length(submode), submode));
                } else {
                    logStr.append(String.format(Locale.US, "<mode:%d>%s "
                            , AdifFormat.utf8Length(mode), mode));
                }
            }

            if (cursor.getString(cursor.getColumnIndex("rst_sent")) != null) {
                logStr.append(String.format(Locale.US, "<rst_sent:%d>%s "
                        , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("rst_sent")))
                        , cursor.getString(cursor.getColumnIndex("rst_sent"))));
            }

            if (cursor.getString(cursor.getColumnIndex("rst_rcvd")) != null) {
                logStr.append(String.format(Locale.US, "<rst_rcvd:%d>%s "
                        , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("rst_rcvd")))
                        , cursor.getString(cursor.getColumnIndex("rst_rcvd"))));
            }

            if (cursor.getString(cursor.getColumnIndex("qso_date")) != null) {
                logStr.append(String.format(Locale.US, "<qso_date:%d>%s "
                        , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("qso_date")))
                        , cursor.getString(cursor.getColumnIndex("qso_date"))));
            }

            if (cursor.getString(cursor.getColumnIndex("time_on")) != null) {
                logStr.append(String.format(Locale.US, "<time_on:%d>%s "
                        , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("time_on")))
                        , cursor.getString(cursor.getColumnIndex("time_on"))));
            }

            if (cursor.getString(cursor.getColumnIndex("qso_date_off")) != null) {
                logStr.append(String.format(Locale.US, "<qso_date_off:%d>%s "
                        , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("qso_date_off")))
                        , cursor.getString(cursor.getColumnIndex("qso_date_off"))));
            }

            if (cursor.getString(cursor.getColumnIndex("time_off")) != null) {
                logStr.append(String.format(Locale.US, "<time_off:%d>%s "
                        , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("time_off")))
                        , cursor.getString(cursor.getColumnIndex("time_off"))));
            }

            if (cursor.getString(cursor.getColumnIndex("band")) != null) {
                logStr.append(String.format(Locale.US, "<band:%d>%s "
                        , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("band")))
                        , cursor.getString(cursor.getColumnIndex("band"))));
            }

            if (cursor.getString(cursor.getColumnIndex("freq")) != null) {
                logStr.append(String.format(Locale.US, "<freq:%d>%s "
                        , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("freq")))
                        , cursor.getString(cursor.getColumnIndex("freq"))));
            }

            if (cursor.getString(cursor.getColumnIndex("station_callsign")) != null) {
                logStr.append(String.format(Locale.US, "<station_callsign:%d>%s "
                        , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("station_callsign")))
                        , cursor.getString(cursor.getColumnIndex("station_callsign"))));
            }

            if (cursor.getString(cursor.getColumnIndex("my_gridsquare")) != null) {
                logStr.append(String.format(Locale.US, "<my_gridsquare:%d>%s "
                        , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("my_gridsquare")))
                        , cursor.getString(cursor.getColumnIndex("my_gridsquare"))));
            }

            if (cursor.getColumnIndex("operator") != -1) {
                if (cursor.getString(cursor.getColumnIndex("operator")) != null) {
                    logStr.append(String.format(Locale.US, "<operator:%d>%s "
                            , AdifFormat.utf8Length(cursor.getString(cursor.getColumnIndex("operator")))
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
            if (comment == null) {
                comment = "";
            }

            //<comment:15>Distance: 99 km <eor>
            //When writing to db, must append " km"
            logStr.append(String.format(Locale.US, "<comment:%d>%s <eor>\n"
                    , AdifFormat.utf8Length(comment)
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
        sb.append(String.format(Locale.US, "<%s:%d>%s ", adifName, AdifFormat.utf8Length(value), value));
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
                    // Leave zoneMapReady false — opening the gate with empty maps
                    // would make every entity appear "new". The caller can retry
                    // once the callsign database finishes loading.
                    return;
                }

                // The in-memory callsign database imports CTY.DAT asynchronously.
                // Wait for that import to finish so the tables are populated before
                // we try to resolve DXCC prefixes from logged QSOs.
                CallsignDatabase.awaitImport(30_000);

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

                // Worked US states: derive each logged QSO's state from its grid square,
                // using the same grid->state table the decode/display paths use. Keeps the
                // "new state" alert consistent with what the UI shows on each row.
                Cursor gridCursor = db.rawQuery(
                        "SELECT DISTINCT gridsquare FROM QSLTable "
                                + "WHERE gridsquare IS NOT NULL AND gridsquare <> ''",
                        null);
                int stateCount = 0;
                while (gridCursor.moveToNext()) {
                    String grid = gridCursor.getString(gridCursor.getColumnIndex("gridsquare"));
                    String state = GeneralVariables.stateForGrid(grid);
                    if (state != null) {
                        GeneralVariables.addState(state);
                        stateCount++;
                    }
                }
                gridCursor.close();

                Log.d(TAG, "run: zone import complete, resolved " + count + " logged callsigns, "
                        + stateCount + " gridded QSOs -> " + GeneralVariables.workedStates.size()
                        + " worked states");
                GeneralVariables.zoneMapReady = true;
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
        try {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                newRecord.isLotW_QSL = cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1
                        || record.isLotW_QSL;
                newRecord.id = cursor.getLong(cursor.getColumnIndex("ID"));
            }
        } finally {
            cursor.close();
        }
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
        try {
            if (cursor.getCount() > 0) {
                cursor.moveToFirst();
                newRecord.isLotW_QSL = cursor.getInt(cursor.getColumnIndex("isLotW_QSL")) == 1
                        || record.isLotW_QSL;
                newRecord.id = cursor.getLong(cursor.getColumnIndex("id"));
            }
        } finally {
            cursor.close();
        }
        return newRecord.id != -1;//
    }

    @SuppressLint("Range")
    public boolean doInsertQSLData(QSLRecord record,AfterInsertQSLData afterInsertQSLData) {
        // On-air and web-logged QSOs mirror to the running ft8af_log.adi; bulk imports use the
        // 3-arg overload with appendToAdifFile=false to avoid double-counting on re-export.
        return doInsertQSLData(record, afterInsertQSLData, true);
    }

    public boolean doInsertQSLData(QSLRecord record, AfterInsertQSLData afterInsertQSLData,
                                   boolean appendToAdifFile) {
        if (record.getToCallsign() == null) {
            if (afterInsertQSLData!=null){
                afterInsertQSLData.doAfterInsert(true,true);//Invalid QSL
            }
            return false;
        }
        // POTA: if an activation is running, stamp MY_SIG/MY_SIG_INFO; if the worked
        // station is currently spotted on pota.app, stamp SIG/SIG_INFO too (P2P case).
        // No-op when no activation/spot, so non-POTA contacts are unaffected.
        radio.ks3ckc.ft8af.pota.PotaSessionManager.stampQso(
                record,
                radio.ks3ckc.ft8af.pota.PotaSpotsRepository.parkRefFor(record.getToCallsign()));

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
                    "comment,my_sig,my_sig_info,sig,sig_info,my_lat,my_lon)VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

            // Object[] (not String[]) so a null myLat/myLon binds as SQL NULL rather than the
            // literal string "null" — the rest of the fields are never null here.
            db.execSQL(querySQL, new Object[]{record.getToCallsign()
                    , String.valueOf(record.isQSL ? 1 : 0)
                    , String.valueOf(record.isLotW_import ? 1 : 0)
                    , String.valueOf(record.isLotW_QSL ? 1 : 0)
                    , record.getToMaidenGrid()
                    , record.getMode()
                    , AdifFormat.formatReport(record.getSendReport())
                    , AdifFormat.formatReport(record.getReceivedReport())
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
                    , record.getSigInfo()
                    , record.getMyLat()
                    , record.getMyLon()});
            // If this QSO was logged during an active POTA activation, bump its qso_count.
            if (record.getMySigInfo() != null && !record.getMySigInfo().isEmpty()) {
                db.execSQL("UPDATE pota_activation SET qso_count = qso_count + 1 "
                        + "WHERE park_ref = ? AND ended_at IS NULL"
                        , new Object[]{record.getMySigInfo()});
            }
            // Mirror this genuinely-new QSO to the running ADIF file. Wrapped so a full disk
            // or missing SD can never break QSO logging (AdifLogFile.logQso itself never throws).
            if (appendToAdifFile) {
                com.k1af.ft8af.log.AdifLogFile.logQso(context, record);
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
                        , new Object[]{AdifFormat.formatReport(record.getSendReport()), record.getToCallsign()
                                , record.getQso_date()
                                , record.getTime_on()
                                , record.getMode()});
            }
            if (record.getReceivedReport() > -100) {
                db.execSQL("UPDATE  QSLTable  SET rst_rcvd=? " +
                                " WHERE (call=?) and (qso_date=?) and(time_on=?) and(mode=?)"
                        , new Object[]{AdifFormat.formatReport(record.getReceivedReport()), record.getToCallsign()
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
            try {
                if (cursor.moveToFirst()) {
                    if (afterQueryConfig != null) {
                        afterQueryConfig.doOnAfterQueryConfig(KeyName, cursor.getString(cursor.getColumnIndex("Value")));
                    }
                } else {
                    if (afterQueryConfig != null) {
                        afterQueryConfig.doOnAfterQueryConfig(KeyName, "");
                    }
                }
            } finally {
                cursor.close();
            }
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
            try {
                if (cursor.moveToFirst()) {
                    if (onGetCallsign != null) {
                        onGetCallsign.doOnAfterGetCallSign(cursor.getInt(cursor.getColumnIndex("a")) > 0);
                    }
                } else {
                    if (onGetCallsign != null) {
                        onGetCallsign.doOnAfterGetCallSign(false);
                    }
                }
            } finally {
                cursor.close();
            }
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
                db.execSQL(sql, new Object[]{message.i3, message.n3,
                        com.k1af.ft8af.ModeProfile.fromId(message.signalFormat).displayName
                        ,UtcTimer.getDatetimeYYYYMMDD_HHMMSS(message.utcTime)
                        , message.hasSnr() ? message.snr : 0, message.time_sec, Math.round(message.freq_hz)
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
                    , AdifFormat.formatReport(qslRecord.getSendReport())
                    , AdifFormat.formatReport(qslRecord.getReceivedReport())
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
            try {
                while (cursor.moveToNext()) {
                    long s = cursor.getLong(cursor.getColumnIndex("BAND")); //Get band
                    int total = cursor.getInt(cursor.getColumnIndex("c")); //Get count
                    callsigns.add(String.format("%.3fMHz \t %d", s / 1000000f, total));
                    sum = sum + total;
                }
            } finally {
                cursor.close();
            }
            callsigns.add(String.format("-----------Total %d -----------", sum));
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
            try {
                while (cursor.moveToNext()) {
                    String date = cursor.getString(cursor.getColumnIndex("t")); //Get date
                    int total = cursor.getInt(cursor.getColumnIndex("c")); //Get count
                    callsigns.add(String.format("%s \t %d ", date, total));
                    sum = sum + total;
                }
            } finally {
                cursor.close();
            }
            callsigns.add(String.format("-----------Total %d -----------", sum));
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
            try {
                while (cursor.moveToNext()) {
                    @SuppressLint("Range")
                    String s = cursor.getString(cursor.getColumnIndex("callsign")); //Get the first column value (index starts at 0)
                    if (s != null) {
                        callsigns.add(s);
                    }
                }
            } finally {
                cursor.close();
            }
            if (onAffterQueryFollowCallsigns != null) {
                onAffterQueryFollowCallsigns.doOnAfterQueryFollowCallsigns(callsigns);
            }
            return null;
        }
    }

    /**
     * Single-thread executor backing {@link #loadCallsignMapGridAsync(SQLiteDatabase)}.
     * Replaces the deprecated {@code GetCallsignMapGrid} AsyncTask (issue #455).
     */
    private static final Executor CALLSIGN_MAP_GRID_EXECUTOR =
            Executors.newSingleThreadExecutor();

    /**
     * Loads the callsign→grid map from the {@code QslCallsigns} table into
     * {@link GeneralVariables#addCallsignAndGrid} synchronously on the caller's thread.
     * Extracted from the old {@code GetCallsignMapGrid} AsyncTask so the query logic is
     * directly unit-testable; production callers should use
     * {@link #loadCallsignMapGridAsync(SQLiteDatabase)} to stay off the main thread.
     */
    @SuppressLint("Range")
    public static void loadCallsignMapGrid(SQLiteDatabase db) {
        String querySQL = "select DISTINCT callsign,grid from QslCallsigns qc \n" +
                "where LENGTH(grid)>3\n" +
                "order by ID ";
        Cursor cursor = db.rawQuery(querySQL, null);
        try {
            int callsignIdx = cursor.getColumnIndex("callsign");
            int gridIdx = cursor.getColumnIndex("grid");
            while (cursor.moveToNext()) {
                GeneralVariables.addCallsignAndGrid(cursor.getString(callsignIdx), cursor.getString(gridIdx));
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Runs {@link #loadCallsignMapGrid(SQLiteDatabase)} on a background executor. This
     * is the non-deprecated replacement for the old
     * {@code new GetCallsignMapGrid(db).execute()} AsyncTask call (issue #455).
     */
    public static void loadCallsignMapGridAsync(SQLiteDatabase db) {
        CALLSIGN_MAP_GRID_EXECUTOR.execute(() -> loadCallsignMapGrid(db));
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
            try {
                while (cursor.moveToNext()) {
                    grids.put(cursor.getString(cursor.getColumnIndex("gridsquare"))
                            , cursor.getInt(cursor.getColumnIndex("isQSL")) != 0);
                }
            } finally {
                cursor.close();
            }
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
            try {
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
            } finally {
                cursor.close();
            }
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
            // Normalize time_on to a fixed-width 6-digit HHMMSS before max()/ORDER BY.
            // time_on is stored as variable-width TEXT (ADIF may carry HHMM; the edit
            // dialog allows arbitrary input), so a value with a dropped leading zero like
            // "815" (08:15) would otherwise sort after "103000". Restore the hour's leading
            // zero on odd-length values, then right-pad seconds. Mirrors normalizeTimeOn()
            // in LogbookScreen.kt so the DB ordering and the Compose sort agree.
            String normTimeOn =
                    "CASE WHEN q.time_on IS NULL OR q.time_on='' THEN '000000'\n" +
                    " WHEN length(q.time_on)%2=1 THEN substr('0'||q.time_on||'000000',1,6)\n" +
                    " ELSE substr(q.time_on||'000000',1,6) END";
            String querySQL = "select max(q.id) as id, q.[call] as callsign ,q.gridsquare as grid" +
                    ",q.band||\"(\"||q.freq||\" MHz)\" as band \n" +
                    ",q.qso_date as last_time ,q.mode ,q.isQSL,q.isLotW_QSL\n" +
                    ",max(" + normTimeOn + ") as last_time_on\n" +
                    ",max(q.synced_cloudlog) as synced_cloudlog\n" +
                    ",max(q.synced_qrz) as synced_qrz\n" +
                    "from QSLTable q inner join QSLTable q2 ON q.id =q2.id \n" +
                    "where (q.[call] like ?)\n" +
                    filterStr +
                    "group by q.[call] ,q.gridsquare,q.freq ,q.qso_date,q.band\n" +
                    ",q.mode,q.isQSL,q.isLotW_QSL\n" +
                    "HAVING q.qso_date =MAX(q2.qso_date) \n" +
                    // newest first, by date then time-of-day so same-day QSOs order correctly
                    "order by q.qso_date desc, last_time_on desc\n"+
                    limitStr;


            Cursor cursor = db.rawQuery(querySQL, new String[]{"%" + callsign + "%"});
            ArrayList<QSLCallsignRecord> records = new ArrayList<>();
            try {
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
                    int idxTimeOn = cursor.getColumnIndex("last_time_on");
                    if (idxTimeOn >= 0) {
                        record.setTimeOn(cursor.getString(idxTimeOn));
                    }
                    record.setMode(cursor.getString(cursor.getColumnIndex("mode")));
                    record.setGrid(cursor.getString(cursor.getColumnIndex("grid")));
                    record.setBand(cursor.getString(cursor.getColumnIndex("band")));
                    records.add(record);
                }
            } finally {
                cursor.close();
            }
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

            // "Same mode only" refinement (Settings → Decode Highlights): when on,
            // restrict every worked list to QSOs made on the current operating mode,
            // so the "and mode" variants of the worked-station scopes work. FROM_LIST
            // is user-maintained and unaffected. See WorkedModeFilter.
            String meter = BaseRigOperation.getMeterFromFreq(GeneralVariables.band);
            String mode = com.k1af.ft8af.ModeProfile.fromId(GeneralVariables.operatingMode).displayName;
            WorkedModeFilter modeFilter = WorkedModeFilter.build(GeneralVariables.workedSameMode, mode);

            //String querySQL = "select distinct [call] from QSLTable where freq=?";
            //Changed to get contacted callsigns by band wavelength
            String querySQL = "select distinct [call] from QSLTable where band=?" + modeFilter.sqlSuffix;
            Cursor cursor = db.rawQuery(querySQL, modeFilter.withArgs(meter));
            ArrayList<String> callsigns = new ArrayList<>();
            try {
                while (cursor.moveToNext()) {
                    @SuppressLint("Range")
                    String s = cursor.getString(cursor.getColumnIndex("call"));
                    if (s != null) {
                        callsigns.add(s);
                    }
                }
            } finally {
                cursor.close();
            }
            // Publish as a CopyOnWriteArrayList so the wholesale swap is atomic for the
            // decode/UI/web-logbook readers racing this background reload (see the field's note).
            GeneralVariables.QSL_Callsign_list = new java.util.concurrent.CopyOnWriteArrayList<>(callsigns);

            querySQL = "select distinct [call] from QSLTable where band<>?" + modeFilter.sqlSuffix;
            cursor = db.rawQuery(querySQL, modeFilter.withArgs(meter));

            ArrayList<String> other_callsigns = new ArrayList<>();
            try {
                while (cursor.moveToNext()) {
                    @SuppressLint("Range")
                    String s = cursor.getString(cursor.getColumnIndex("call"));
                    if (s != null) {
                        other_callsigns.add(s);
                    }
                }
            } finally {
                cursor.close();
            }
            GeneralVariables.QSL_Callsign_list_other_band = other_callsigns;

            // Load distinct callsigns worked today or yesterday (any band) for the
            // TODAY worked-station scope. Dates are ADIF YYYYMMDD (UTC), so a plain
            // string >= yesterday comparison selects the last two UTC days.
            long nowUtc = com.k1af.ft8af.timer.UtcTimer.getSystemTime();
            String yesterday = com.k1af.ft8af.timer.UtcTimer.getYYYYMMDD(nowUtc - 86400000L);
            querySQL = "select distinct [call] from QSLTable where qso_date>=?" + modeFilter.sqlSuffix;
            cursor = db.rawQuery(querySQL, modeFilter.withArgs(yesterday));
            java.util.HashSet<String> today_callsigns = new java.util.HashSet<>();
            try {
                while (cursor.moveToNext()) {
                    @SuppressLint("Range")
                    String s = cursor.getString(cursor.getColumnIndex("call"));
                    if (s != null) {
                        today_callsigns.add(s);
                    }
                }
            } finally {
                cursor.close();
            }
            GeneralVariables.QSL_Callsign_list_today = today_callsigns;

            // Load distinct 4-char worked grids (any band) into in-memory set
            querySQL = "select distinct upper(substr(gridsquare,1,4)) as g from QSLTable" +
                    " where gridsquare is not null and length(gridsquare) >= 4";
            cursor = db.rawQuery(querySQL, null);
            java.util.HashSet<String> grids = new java.util.HashSet<>();
            try {
                while (cursor.moveToNext()) {
                    @SuppressLint("Range")
                    String g = cursor.getString(cursor.getColumnIndex("g"));
                    if (g != null && g.length() >= 4) {
                        grids.add(g);
                    }
                }
            } finally {
                cursor.close();
            }
            GeneralVariables.QSL_Grid_list = grids;

            // Load distinct hunted POTA park refs (any band) into in-memory set.
            // sig/sig_info may be absent on some upgraded installs (see note in
            // onUpgrade), so guard the query defensively.
            try {
                Cursor potaCursor = db.rawQuery("select distinct upper(sig_info) as p from QSLTable" +
                        " where sig='POTA' and sig_info is not null and sig_info<>''", null);
                java.util.HashSet<String> parks = new java.util.HashSet<>();
                while (potaCursor.moveToNext()) {
                    String p = potaCursor.getString(0);
                    if (p != null && !p.isEmpty()) {
                        parks.add(p);
                    }
                }
                potaCursor.close();
                GeneralVariables.QSL_Pota_list = parks;
            } catch (Exception ignored) {
                GeneralVariables.QSL_Pota_list = new java.util.HashSet<>();
            }
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
            try {
                String result = "";
                if (cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex("Value"));
                }
                return result;
            } finally {
                cursor.close();
            }
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {

            String querySQL = "select keyName,Value from config ";
            Cursor cursor = db.rawQuery(querySQL, null);
            try {
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
                if (name.equalsIgnoreCase("cqFreeText")) {
                    if (result != null) {
                        GeneralVariables.cqFreeText = result;
                    }
                }
                if (name.equalsIgnoreCase("fieldDayMode")) {
                    GeneralVariables.fieldDayMode = result.equals("1");
                }
                if (name.equalsIgnoreCase("fieldDayClass")) {
                    if (result != null && result.length() > 0) {
                        GeneralVariables.fieldDayClass = result;
                    }
                }
                if (name.equalsIgnoreCase("fieldDayNumTx")) {
                    try {
                        int v = result.equals("") ? 1 : Integer.parseInt(result);
                        GeneralVariables.fieldDayNumTx = Math.max(1, Math.min(16, v));
                    } catch (NumberFormatException e) {
                        GeneralVariables.fieldDayNumTx = 1;
                    }
                }
                if (name.equalsIgnoreCase("fieldDaySection")) {
                    if (result != null) {
                        GeneralVariables.fieldDaySection = result;
                    }
                }
                if (name.equalsIgnoreCase("antenna")) {
                    GeneralVariables.myAntenna = result;
                }
                if (name.equalsIgnoreCase("powerWatts")) {
                    try {
                        GeneralVariables.myPowerWatts = result.isEmpty() ? 0 : Integer.parseInt(result);
                    } catch (NumberFormatException e) {
                        GeneralVariables.myPowerWatts = 0;
                    }
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
                if (name.equalsIgnoreCase("holdTxFreq")) {
                    // Parse like synFreq above: any non-empty, non-"0" value is true,
                    // so the two boolean configs handle stored values consistently.
                    GeneralVariables.holdTxFreq = !(result.equals("") || result.equals("0"));
                }
                if (name.equalsIgnoreCase("transDelay")) {
                    if (result.matches("^\\d{1,4}$")) {//Regex: 1-4 digit number
                        GeneralVariables.transmitDelay = Integer.parseInt(result);
                    } else {
                        GeneralVariables.transmitDelay = FT8Common.FT8_TRANSMIT_DELAY;
                    }
                }
                //Manual time correction (ms). Re-applied to UtcTimer.delay at startup so a
                //field operator's offline clock nudge survives a relaunch. delay is read live
                //by the running timers, so this takes effect immediately.
                if (name.equalsIgnoreCase("timeCorrectionMs")) {
                    int ms;
                    try {
                        ms = Integer.parseInt(result.trim());
                    } catch (NumberFormatException e) {
                        ms = 0;
                    }
                    // Clamp with the SAME bounds the live settings UI uses when it
                    // persists this value (±5 s). The reload clamp used to be ±2 s,
                    // silently truncating any correction beyond ±2 s on every launch.
                    ms = GeneralVariables.clampManualTimeCorrectionMs(ms);
                    GeneralVariables.manualTimeCorrectionMs = ms;
                    UtcTimer.delay = ms;
                }

                if (name.equalsIgnoreCase("civ")) {
                    GeneralVariables.civAddress = parseConfigInt(result, 0xa4, 16);
                }
                if (name.equalsIgnoreCase("baudRate")) {
                    GeneralVariables.baudRate = parseConfigInt(result, 19200);
                }
                if (name.equalsIgnoreCase("bandFreq")) {
                    GeneralVariables.band = parseConfigLong(result, 14074000L);
                    GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(GeneralVariables.band);
                }

                if (name.equalsIgnoreCase("excludedBands")) {
                    java.util.HashSet<String> newSet = new java.util.HashSet<>();
                    if (!result.trim().isEmpty()) {
                        for (String w : result.split(",")) {
                            String t = w.trim();
                            if (!t.isEmpty()) newSet.add(t);
                        }
                    }
                    GeneralVariables.excludedBands = newSet;
                }

                if (name.equalsIgnoreCase("msgMode")) {
                    GeneralVariables.simpleCallItemMode = result.equals("1") ;
                }

                if (name.equalsIgnoreCase("clearDecodesEveryCycle")) {
                    GeneralVariables.clearDecodesEveryCycle = result.equals("1");
                }

                if (name.equalsIgnoreCase("pileupStrongestFirst")) {
                    GeneralVariables.pileupStrongestFirst = result.equals("1");
                }

                if (name.equalsIgnoreCase("decodeSortMode")) {
                    // parseConfigInt, not Integer.parseInt: a whitespace/non-numeric value in
                    // the config table would otherwise throw during startup hydration.
                    GeneralVariables.decodeSortMode = parseConfigInt(result, 0);
                }

                if (name.equalsIgnoreCase("clearOnBandModeChange")) {
                    GeneralVariables.clearOnBandModeChange = result.equals("1");
                }

                if (name.equalsIgnoreCase("ctrMode")) {
                    GeneralVariables.controlMode = parseConfigInt(result, ControlMode.VOX);
                }
                if (name.equalsIgnoreCase("connectMode")) {
                    GeneralVariables.connectMode = parseConfigInt(result, ConnectMode.USB_CABLE);
                }
                if (name.equalsIgnoreCase("bluetoothDeviceAddress")) {//last-selected BT (SPP/CAT) device, for auto-reconnect
                    GeneralVariables.bluetoothDeviceAddress = result;
                }
                if (name.equalsIgnoreCase("model")) {//Radio model
                    GeneralVariables.modelNo = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("instruction")) {//Instruction set
                    GeneralVariables.instructionSet = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("launchSupervision")) {//Transmit supervision
                    GeneralVariables.launchSupervision =
                            parseConfigInt(result, GeneralVariables.DEFAULT_LAUNCH_SUPERVISION);
                }
                if (name.equalsIgnoreCase("noReplyLimit")) {//
                    GeneralVariables.noReplyLimit = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("max73Sends")) {//Max RR73/73 sends per QSO; 0==Auto
                    GeneralVariables.max73Sends = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("autoFollowCQ")) {//Auto-follow CQ
                    GeneralVariables.autoFollowCQ = result.equals("1");
                }
                if (name.equalsIgnoreCase("huntCallsCQ")) {//Hunt+CQ hybrid
                    GeneralVariables.huntCallsCQ = result.equals("1");
                }
                if (name.equalsIgnoreCase("autoCallFollow")) {//Auto-call followed stations
                    GeneralVariables.autoCallFollow = (result.equals("") || result.equals("1"));
                }
                if (name.equalsIgnoreCase("autoGridFromGPS")) {//Auto-update grid from GPS
                    GeneralVariables.autoUpdateGridFromGPS = result.equals("1");
                }
                if (name.equalsIgnoreCase("disciplineClockFromGPS")) {//Discipline clock from GPS (issue #373)
                    GeneralVariables.disciplineClockFromGPS = result.equals("1");
                }
                if (name.equalsIgnoreCase("gpsClockIntervalMin")) {//GPS discipline update interval (minutes)
                    GeneralVariables.gpsClockIntervalMinutes =
                            com.k1af.ft8af.location.GpsClockUpdater.parseIntervalMinutes(result);
                }
                if (name.equalsIgnoreCase("pttDelay")) {//PTT delay setting
                    GeneralVariables.pttDelay = parseConfigInt(result, 100);
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
                if (name.equalsIgnoreCase("earlyDecode")) {//Fast turnaround: shorter RX window, defaults on
                    GeneralVariables.earlyDecode = (result.equals("") || result.equals("1"));
                }
                if (name.equalsIgnoreCase("operatingMode")) {//Operating mode (0=FT8,1=FT4), defaults FT8
                    try {
                        int parsed = result.equals("")
                                ? FT8Common.FT8_MODE : Integer.parseInt(result);
                        // Normalize through ModeProfile so an unknown id persisted by a
                        // future build (e.g. a mode this build doesn't know) degrades to
                        // FT8 everywhere, not just in descriptor lookups.
                        GeneralVariables.operatingMode = com.k1af.ft8af.ModeProfile.fromId(parsed).id;
                    } catch (NumberFormatException nfe) {
                        GeneralVariables.operatingMode = FT8Common.FT8_MODE;
                    }
                }
                if (name.equalsIgnoreCase("iaruRegion")) {//Operator's IARU region (1/2/3), defaults 2
                    // Load side only for now: the Settings row that writes this key lands
                    // with the Message-Creator UI. Wiring the read here means a value
                    // persisted by that follow-up survives relaunch without a second edit
                    // to this hydration block. regionFromNumber maps anything out of range
                    // back to region 2, so a junk config value degrades instead of throwing.
                    GeneralVariables.iaruRegion = com.k1af.ft8af.message.SpecialMessage
                            .regionFromNumber(parseConfigInt(result, 2)).number;
                }
                if (name.equalsIgnoreCase("autoCQAfterQSO")) {//Auto-CQ after each completed QSO, defaults off
                    GeneralVariables.autoCQAfterQSO = result.equals("1");
                }
                if (name.equalsIgnoreCase("icomIp")) {//ICOM IP address
                    GeneralVariables.icomIp = result.equals("") ? "255.255.255.255" : result;
                }
                if (name.equalsIgnoreCase("icomPort")) {//ICOM port
                    GeneralVariables.icomUdpPort = parseConfigInt(result, 50001);
                }
                if (name.equalsIgnoreCase("icomUserName")) {//ICOM username
                    GeneralVariables.icomUserName = result.equals("") ? "ic705" : result;
                }
                if (name.equalsIgnoreCase("icomPassword")) {//ICOM password
                    GeneralVariables.icomPassword = result;
                }
                if (name.equalsIgnoreCase("volumeValue")) {//Output volume level
                    // parseConfigFloat's fallback is pre-scaling: empty/garbage -> 100f -> 1.0f (unity).
                    GeneralVariables.volumePercent = parseConfigFloat(result, 100f) / 100f;
                    GeneralVariables.mutableVolumePercent.postValue(GeneralVariables.volumePercent);
                }
                if (name.equalsIgnoreCase("inputVolume")) {//RX input gain (percent, 100 = unity)
                    //Defensive parse + clamp: the config value is a free-form
                    //string and settings import (#382) can feed a corrupted or
                    //out-of-range value through here at startup. Non-numeric
                    //falls back to unity; numeric clamps to 0..200%.
                    GeneralVariables.inputGainPercent = InputAudioLevel.parseGainPercent(result);
                }
                if (name.equalsIgnoreCase("showTxVolumeSlider")) {//Inline TX volume slider visibility
                    GeneralVariables.showTxVolumeSlider = !result.equals("0");
                    GeneralVariables.mutableShowTxVolumeSlider.postValue(GeneralVariables.showTxVolumeSlider);
                }
                if (name.equalsIgnoreCase("perBandOutputLevel")) {//Save TX output level per band, defaults off
                    GeneralVariables.savePerBandOutputLevel = result.equals("1");
                }
                if (name.equalsIgnoreCase("perBandOutputLevels")) {//Per-band TX output levels ("20m=60,40m=85")
                    GeneralVariables.perBandOutputLevels = result == null ? "" : result;
                }
                if (name.equalsIgnoreCase("autoClearTxFreq")) {//Auto-select clear CQ offset (issue #418)
                    GeneralVariables.autoClearTxFreq = "1".equals(result);
                }
                if (name.equalsIgnoreCase("tuneMaxOnSeconds")) {//Tune carrier hard cap (issue #408)
                    //Defensive parse: settings import (#382) can feed anything here.
                    //Null/non-numeric keeps the default; TuneController clamps the range.
                    if (result != null) {
                        try {
                            GeneralVariables.tuneMaxOnSeconds =
                                    com.k1af.ft8af.ft8transmit.TuneController.clampMaxOnSeconds(
                                            Integer.parseInt(result.trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (name.equalsIgnoreCase("tuneLevelIndependent")) {//Tune level decoupled from TX drive
                    GeneralVariables.tuneLevelIndependent = "1".equals(result);
                }
                if (name.equalsIgnoreCase("tuneLevel")) {//Global independent tune level (0..100)
                    if (result != null) {
                        try {
                            GeneralVariables.tuneLevel =
                                    Math.max(0, Math.min(100, Integer.parseInt(result.trim())));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (name.equalsIgnoreCase("perBandTuneLevels")) {//Per-band independent tune levels
                    GeneralVariables.perBandTuneLevels = result == null ? "" : result;
                }
                if (name.equalsIgnoreCase("tuneMethod")) {//Tune method: rig ATU vs carrier (issue #425)
                    if (result != null) {
                        try {
                            GeneralVariables.tuneMethod =
                                    com.k1af.ft8af.ft8transmit.TuneMethod.clamp(
                                            Integer.parseInt(result.trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (name.equalsIgnoreCase("excludedCallsigns")) {//Blocklist: callsign prefixes
                    GeneralVariables.addExcludedCallsigns(result);
                }
                if (name.equalsIgnoreCase("blockedExactCallsigns")) {//Blocklist: whole-call exact
                    GeneralVariables.addBlockedExactCallsigns(result);
                }
                if (name.equalsIgnoreCase("blockedKeywords")) {//Blocklist: keyword substrings
                    GeneralVariables.addBlockedKeywords(result);
                }
                if (name.equalsIgnoreCase("watchCallsigns")) {//Watchlist: alert on these call(prefix)es
                    GeneralVariables.addWatchCallsigns(result);
                }
                if (name.equalsIgnoreCase("filterShowOnlyCQ")) {//Decode filter: CQ only
                    GeneralVariables.filterShowOnlyCQ = result.equals("1");
                }
                if (name.equalsIgnoreCase("filterDxOnly")) {//Decode filter: DX (other continents) only
                    GeneralVariables.filterDxOnly = result.equals("1");
                }
                if (name.equalsIgnoreCase("filterNeededOnly")) {//Decode filter: needed only
                    GeneralVariables.filterNeededOnly = result.equals("1");
                }
                if (name.equalsIgnoreCase("filterByContinent")) {//Decode filter: by continent
                    GeneralVariables.filterByContinent = result.equals("1");
                }
                if (name.equalsIgnoreCase("filterContinent")) {//Decode filter: target continent
                    if (result != null && result.length() > 0) {
                        GeneralVariables.filterContinent = result;
                    }
                }
                if (name.equalsIgnoreCase("respectDirectionalCQ")) {//Directional CQ: suppress auto-reply
                    GeneralVariables.respectDirectionalCQ = result.equals("1");
                }
                if (name.equalsIgnoreCase("filterDirectionalCQ")) {//Directional CQ: hide from decode list
                    GeneralVariables.filterDirectionalCQ = result.equals("1");
                }
                if (name.equalsIgnoreCase("alertNewDxcc")) {//Needed-DX alert: new DXCC entity
                    GeneralVariables.alertNewDxcc = result.equals("1");
                }
                if (name.equalsIgnoreCase("alertNewState")) {//Needed-DX alert: new US state
                    GeneralVariables.alertNewState = result.equals("1");
                }
                if (name.equalsIgnoreCase("alertOnCqReply")) {//Alert when someone replies to my CQ
                    GeneralVariables.alertOnCqReply = result.equals("1");
                }
                if (name.equalsIgnoreCase("alertOnQsoComplete")) {//Alert when a QSO completes
                    GeneralVariables.alertOnQsoComplete = result.equals("1");
                }
                if (name.equalsIgnoreCase("flexMaxRfPower")) {//Flex max RF power
                    GeneralVariables.flexMaxRfPower = parseConfigInt(result, 10);
                }
                if (name.equalsIgnoreCase("flexMaxTunePower")) {//Flex max tune power
                    GeneralVariables.flexMaxTunePower = parseConfigInt(result, 10);
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
                    // Defensive parse (like the FFT/serial keys): settings import (#382)
                    // can feed an empty or non-numeric value through here, and this key
                    // had no guard at all, so a hand-edited/corrupted backup crashed
                    // startup hydration (and every relaunch). Fall back to the default.
                    GeneralVariables.audioSampleRate = parseConfigInt(result, 12000);
                }
                if (name.equalsIgnoreCase("audioInputDevice")) {//Audio input device ID
                    GeneralVariables.audioInputDeviceId = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("audioOutputDevice")) {//Audio output device ID
                    GeneralVariables.audioOutputDeviceId = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("usbAudioInputVid")) {
                    GeneralVariables.usbAudioInputVendorId = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("usbAudioInputPid")) {
                    GeneralVariables.usbAudioInputProductId = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("usbAudioOutputVid")) {
                    GeneralVariables.usbAudioOutputVendorId = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("usbAudioOutputPid")) {
                    GeneralVariables.usbAudioOutputProductId = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("deepMode")) {//Deep decode mode
                    GeneralVariables.deepDecodeMode = "1".equals(result);
                }
                if (name.equalsIgnoreCase("keepScreenOn")) {//Hold the screen awake in foreground
                    // "1".equals(result), not result.equals("1"): a null config value
                    // (missing/blank column from an imported backup) must not NPE here.
                    GeneralVariables.keepScreenOn = "1".equals(result);
                }
                if (name.equalsIgnoreCase("debugModeEnabled")) {//Hidden debug screen unlock
                    GeneralVariables.debugModeEnabled = result.equals("1");
                }
                // Serial line params: same defensive parse as audioRate above. These
                // three keys were the only remaining hydration parses with no guard,
                // so an empty or non-numeric value from an imported backup (#382) threw
                // NumberFormatException and crashed hydration. Fall back to the defaults
                // (8-N-1) that GeneralVariables initializes to.
                if (name.equalsIgnoreCase("dataBits")) {//Serial data bits
                    GeneralVariables.serialDataBits = parseConfigInt(result, 8);
                }
                if (name.equalsIgnoreCase("stopBits")) {//Serial stop bits
                    GeneralVariables.serialStopBits = parseConfigInt(result, 1);
                }
                if (name.equalsIgnoreCase("parityBits")) {//Serial parity bits
                    GeneralVariables.serialParity = parseConfigInt(result, 0);
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
                if (name.equalsIgnoreCase("enableAdifExport")) {//Running ft8af_log.adi export
                    GeneralVariables.enableAdifExport = result.equals("1");
                }
                // WSJT-X UDP interface
                if (name.equalsIgnoreCase("udp_enabled")) {
                    GeneralVariables.udpEnabled = result.equals("1");
                }
                if (name.equalsIgnoreCase("udp_host")) {
                    if (result != null && !result.isEmpty()) GeneralVariables.udpHost = result;
                }
                if (name.equalsIgnoreCase("udp_port")) {
                    // result (cursor.getString) can be null; the other udp_* keys already
                    // null-check, and Integer.parseInt(null.trim()) would NPE config hydration.
                    if (result != null) {
                        try {
                            int p = Integer.parseInt(result.trim());
                            if (p > 0 && p <= 65535) GeneralVariables.udpPort = p;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (name.equalsIgnoreCase("udp_accept_requests")) {
                    GeneralVariables.udpAcceptRequests = result.equals("1");
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
                if (name.equalsIgnoreCase("grayLineEnabled")) {
                    GeneralVariables.grayLineEnabled = result.equals("1");
                }

                if (name.equalsIgnoreCase("swrSwitch")) {
                    GeneralVariables.swr_switch_on = result.equals("1");
                }
                if (name.equalsIgnoreCase("alcSwitch")) {
                    GeneralVariables.alc_switch_on = result.equals("1");
                }
                // TX Protection: ALC auto-volume + SWR halt
                if (name.equalsIgnoreCase("autoVolumeEnabled")) {
                    GeneralVariables.autoVolumeEnabled = result.equals("1");
                }
                if (name.equalsIgnoreCase("swrHaltEnabled")) {
                    GeneralVariables.swrHaltEnabled = result.equals("1");
                }
                if (name.equalsIgnoreCase("swrHaltThreshold")) {
                    GeneralVariables.swrHaltThreshold = parseConfigInt(result, 120);
                }
                if (name.equalsIgnoreCase("alcTargetLow")) {
                    GeneralVariables.alcTargetLow = parseConfigInt(result, 60);
                }
                if (name.equalsIgnoreCase("alcTargetHigh")) {
                    GeneralVariables.alcTargetHigh = parseConfigInt(result, 100);
                }
                if (name.equalsIgnoreCase("spectrumWidth")) {
                    GeneralVariables.setSpectrumWidth(parseConfigInt(result, 3500));
                }
                // FFT display developer knobs (issue #428). Parsed defensively:
                // these are expected to survive hand-edited/stale backups, so a
                // non-numeric value must fall back to the default instead of
                // crashing hydration; the setters then clamp the range.
                if (name.equalsIgnoreCase("fftWindowType")) {
                    GeneralVariables.setFftWindowType(parseConfigInt(result, 1));
                }
                if (name.equalsIgnoreCase("fftAveragingMode")) {
                    GeneralVariables.setFftAveragingMode(parseConfigInt(result, 0));
                }
                if (name.equalsIgnoreCase("spectrumBinAggregation")) {
                    GeneralVariables.setSpectrumBinAggregation(parseConfigInt(result, 0));
                }

                if (name.equalsIgnoreCase("highlightNewDxcc")) {
                    GeneralVariables.highlightNewDxcc = result.equals("1");
                }
                if (name.equalsIgnoreCase("highlightNewZone")) {
                    GeneralVariables.highlightNewZone = result.equals("1");
                }
                if (name.equalsIgnoreCase("highlightNewState")) {
                    GeneralVariables.highlightNewState = result.equals("1");
                }
                if (name.equalsIgnoreCase("highlightNewGrid")) {
                    GeneralVariables.highlightNewGrid = result.equals("1");
                }
                if (name.equalsIgnoreCase("highlightNewBand")) {
                    GeneralVariables.highlightNewBand = result.equals("1");
                }
                if (name.equalsIgnoreCase("highlightWorked")) {
                    GeneralVariables.highlightWorked = result.equals("1");
                }
                if (name.equalsIgnoreCase("highlightPota")) {
                    GeneralVariables.highlightPota = result.equals("1");
                }
                if (name.equalsIgnoreCase("workedStationMode")) {
                    GeneralVariables.workedStationMode = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("workedStationScope")) {
                    GeneralVariables.workedStationScope = parseConfigInt(result, 0);
                }
                if (name.equalsIgnoreCase("workedStationList")) {
                    GeneralVariables.addWorkedStationList(result);
                }
                if (name.equalsIgnoreCase("workedSameMode")) {//Restrict worked scopes to the current mode
                    GeneralVariables.workedSameMode = result.equals("1");
                }

                if (name.equalsIgnoreCase("distanceInMiles")) {
                    GeneralVariables.distanceInMiles = !result.equals("0");
                }
                if (name.equalsIgnoreCase("showBeamHeading")) {
                    GeneralVariables.showBeamHeading = result.equals("1");
                }

            }

            } finally {
                cursor.close();
            }

            GetAllQSLCallsign.get(db);//Get previously contacted callsigns

            if (onAfterQueryConfig != null) {
                onAfterQueryConfig.doOnAfterQueryConfig(null, null);
            }

            return null;
        }
    }


}
