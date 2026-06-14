package com.bg7yoz.ft8cn.callsign;
/**
 * Database operations for callsign location lookup; uses an in-memory database. Data source is CTY.DAT.
 * @author BG7YOZ
 * 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.util.Log;

import androidx.annotation.Nullable;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.Set;

public class CallsignDatabase extends SQLiteOpenHelper {
    private static final String TAG = "CallsignDatabase";
    @SuppressLint("StaticFieldLeak")
    private static CallsignDatabase instance;
    private final Context context;
    private SQLiteDatabase db;

    public static CallsignDatabase getInstance(@Nullable Context context, @Nullable String databaseName, int version) {
        if (instance == null) {
            instance = new CallsignDatabase(context, databaseName, null, version);
        }
        return instance;
    }


    public CallsignDatabase(@Nullable Context context, @Nullable String name
            , @Nullable SQLiteDatabase.CursorFactory factory, int version) {
        super(context, name, factory, version);
        this.context = context;

        // Connect to the database; if the physical database does not exist, onCreate will be called to initialize it
        db = this.getWritableDatabase();
    }

    public SQLiteDatabase getDb() {
        return db;
    }

    /**
     * Called when the physical database does not exist. Creates tables and imports data here.
     *
     * @param sqLiteDatabase the database to connect to
     */
    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        Log.d(TAG, "Create database.");
        db = sqLiteDatabase; // Save the database connection
        createTables(); // Create database tables
        new InitDatabase(context, db).execute(); // Import data
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int i, int i1) {

    }


    private void createTables() {
        try {
            db.execSQL("CREATE TABLE countries (\n" +
                    "id INTEGER NOT NULL PRIMARY KEY,\n" +
                    "CountryNameEn TEXT,\n" +
                    "CountryNameCN TEXT,\n" +
                    "CQZone INTEGER,\n" +
                    "ITUZone INTEGER,\n" +
                    "Continent TEXT,\n" +
                    "Latitude REAL,\n" +
                    "Longitude REAL,\n" +
                    "GMT_offset REAL,\n" +
                    "DXCC TEXT)");
            db.execSQL("CREATE INDEX countries_id_IDX ON countries (id)");

            db.execSQL("CREATE TABLE callsigns (countryId INTEGER NOT NULL,callsign TEXT)");
            db.execSQL("CREATE INDEX callsigns_callsign_IDX ON callsigns (callsign)");

            db.execSQL("CREATE INDEX countries_id_IDX_MORE ON countries (id,CountryNameEn,CountryNameCN,CQZone,ITUZone,DXCC)");
            db.execSQL("CREATE INDEX callsigns_countryId_IDX ON callsigns (countryId)");
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());

        }
    }

    // Look up the callsign's location
    public void getCallsignInformation(String callsign, OnAfterQueryCallsignLocation afterQueryCallsignLocation) {
        new QueryCallsignInformation(db, callsign, afterQueryCallsignLocation).execute();
    }

    public CallsignInfo getCallInfo(String callsign) {
        return getCallsignInfo(db, callsign);
    }

    /**
     * Update location and latitude/longitude information in messages.
     *
     * @param ft8Messages the message list
     */
    public static synchronized void getMessagesLocation(SQLiteDatabase db, ArrayList<Ft8Message> ft8Messages ) {
        if (ft8Messages==null) return;
        ArrayList<Ft8Message> messages = new ArrayList<>(ft8Messages); // Prevent thread access conflicts

        for (Ft8Message msg : messages) {
            if (msg.i3==0&&msg.n3==0) continue; // Skip free-text messages
            CallsignInfo fromCallsignInfo = getCallsignInfo(db,
                    msg.callsignFrom.replace("<","").replace(">",""));
            if (fromCallsignInfo != null) {
                    // Suppress "new" flags until the zone maps are populated; otherwise
                    // the async load race makes everything appear new. (#247)
                    if (GeneralVariables.zoneMapReady) {
                        msg.fromDxcc = !GeneralVariables.getDxccByPrefix(fromCallsignInfo.DXCC);
                        msg.fromItu = !GeneralVariables.getItuZoneById(fromCallsignInfo.ITUZone);
                        msg.fromCq = !GeneralVariables.getCqZoneById(fromCallsignInfo.CQZone);
                    }
                    msg.fromWhere = fromCallsignInfo.CountryNameEn;
                    msg.continent = fromCallsignInfo.Continent;
                    msg.fromLatLng = new LatLng(fromCallsignInfo.Latitude, fromCallsignInfo.Longitude * -1);
            }

            // US state from the sender's grid (US-only table → null for non-US grids).
            // fromNewState mirrors fromDxcc: true when the state is not yet worked.
            msg.fromState = GeneralVariables.stateForGrid(msg.maidenGrid);
            msg.fromNewState = msg.fromState != null
                    && !GeneralVariables.getStateWorked(msg.fromState);

            // Resolve the operator's own continent + DXCC once (continent for the DX
            // decode filter, DXCC for the directional-CQ matcher).
            if ((GeneralVariables.myContinent == null || GeneralVariables.myDxcc == null)
                    && GeneralVariables.myCallsign != null
                    && GeneralVariables.myCallsign.length() > 0) {
                CallsignInfo myInfo = getCallsignInfo(db,
                        GeneralVariables.myCallsign.replace("<", "").replace(">", ""));
                if (myInfo != null) {
                    GeneralVariables.myContinent = myInfo.Continent;
                    GeneralVariables.myDxcc = myInfo.DXCC;
                }
            }

            if (msg.checkIsCQ() || msg.getCallsignTo().contains("...")) { // Skip CQ messages
                continue;
            }

            CallsignInfo toCallsignInfo = getCallsignInfo(db,
                    msg.callsignTo.replace("<","").replace(">",""));
            if (toCallsignInfo != null) {
                if (GeneralVariables.zoneMapReady) {
                    msg.toDxcc = !GeneralVariables.getDxccByPrefix(toCallsignInfo.DXCC);
                    msg.toItu = !GeneralVariables.getItuZoneById(toCallsignInfo.ITUZone);
                    msg.toCq = !GeneralVariables.getCqZoneById(toCallsignInfo.CQZone);
                }

                msg.toWhere = toCallsignInfo.CountryNameEn;
                msg.toLatLng = new LatLng(toCallsignInfo.Latitude, toCallsignInfo.Longitude*-1);
            }
        }
    }

    @SuppressLint("Range")
    private static CallsignInfo getCallsignInfo(SQLiteDatabase db, String callsign) {
        CallsignInfo callsignInfo = null;

        String querySQL = "select a.*,b.* from callsigns as a left join countries as b on a.countryId =b.id \n" +
                "WHERE (SUBSTR(?,1,LENGTH(callsign))=callsign) OR (callsign=\"=\"||?)\n" +
                "order by LENGTH(callsign) desc\n" +
                "LIMIT 1";

        Cursor cursor = db.rawQuery(querySQL, new String[]{callsign.toUpperCase(), callsign.toUpperCase()});
        try {
            if (cursor.moveToFirst()) {
                callsignInfo = new CallsignInfo(callsign.toUpperCase()
                        , cursor.getString(cursor.getColumnIndex("CountryNameEn"))
                        , cursor.getString(cursor.getColumnIndex("CountryNameCN"))
                        , cursor.getInt(cursor.getColumnIndex("CQZone"))
                        , cursor.getInt(cursor.getColumnIndex("ITUZone"))
                        , cursor.getString(cursor.getColumnIndex("Continent"))
                        , cursor.getFloat(cursor.getColumnIndex("Latitude"))
                        , cursor.getFloat(cursor.getColumnIndex("Longitude"))
                        , cursor.getFloat(cursor.getColumnIndex("GMT_offset"))
                        , cursor.getString(cursor.getColumnIndex("DXCC")));
            }
        } finally {
            cursor.close();
        }
        return callsignInfo;
    }


    static class QueryCallsignInformation extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final String sqlParameter;
        private final OnAfterQueryCallsignLocation afterQueryCallsignLocation;

        public QueryCallsignInformation(SQLiteDatabase db, String sqlParameter, OnAfterQueryCallsignLocation afterQueryCallsignLocation) {
            this.db = db;
            this.sqlParameter = sqlParameter;
            this.afterQueryCallsignLocation = afterQueryCallsignLocation;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
//            String querySQL = "select a.*,b.* from callsigns as a left join countries as b on a.countryId =b.id \n" +
//                    "WHERE (SUBSTR(?,1,LENGTH(callsign))=callsign) OR (callsign=\"=\"||?)\n" +
//                    "order by LENGTH(callsign) desc\n" +
//                    "LIMIT 1";
//
//            Cursor cursor = db.rawQuery(querySQL, new String[]{sqlParameter.toUpperCase(), sqlParameter.toUpperCase()});
//            if (cursor.moveToFirst()) {
//                CallsignInfo callsignInfo = new CallsignInfo();
//                callsignInfo.CallSign = sqlParameter.toUpperCase();
//                callsignInfo.CountryNameEn = cursor.getString(cursor.getColumnIndex("CountryNameEn"));
//                callsignInfo.CountryNameCN = cursor.getString(cursor.getColumnIndex("CountryNameCN"));
//                callsignInfo.CQZone = cursor.getInt(cursor.getColumnIndex("CQZone"));
//                callsignInfo.ITUZone = cursor.getInt(cursor.getColumnIndex("ITUZone"));
//                callsignInfo.Continent = cursor.getString(cursor.getColumnIndex("Continent"));
//                callsignInfo.Latitude = cursor.getFloat(cursor.getColumnIndex("Latitude"));
//                callsignInfo.Longitude = cursor.getFloat(cursor.getColumnIndex("Longitude"));
//                callsignInfo.GMT_offset = cursor.getFloat(cursor.getColumnIndex("GMT_offset"));
//                callsignInfo.DXCC = cursor.getString(cursor.getColumnIndex("DXCC"));
//                if (afterQueryCallsignLocation!=null){
//                    afterQueryCallsignLocation.doOnAfterQueryCallsignLocation(callsignInfo);
//                }
//            }
//            cursor.close();
            CallsignInfo callsignInfo = getCallsignInfo(db, sqlParameter);
            if (callsignInfo != null && afterQueryCallsignLocation != null) {
                afterQueryCallsignLocation.doOnAfterQueryCallsignLocation(callsignInfo);
            }
            return null;
        }
    }


    static class InitDatabase extends AsyncTask<Void, Void, Void> {
        @SuppressLint("StaticFieldLeak")
        private final Context context;
        private final SQLiteDatabase db;

        public InitDatabase(Context context, SQLiteDatabase db) {
            this.context = context;
            this.db = db;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            Log.d(TAG, "Starting callsign location data import...");
            String insertCountriesSQL = "INSERT INTO countries (id,CountryNameEn,CountryNameCN,CQZone" +
                    ",ITUZone,Continent,Latitude,Longitude,GMT_offset,DXCC)\n" +
                    "VALUES(?,?,?,?,?,?,?,?,?,?)";

            ArrayList<CallsignInfo> callsignInfos =
                    CallsignFileOperation.getCallSingInfoFromFile(context);
            ContentValues values = new ContentValues();
            for (int i = 0; i < callsignInfos.size(); i++) {
                try {
                    // Write country and region data into the table; id is used to associate with callsigns
                    db.execSQL(insertCountriesSQL, new Object[]{
                            i, // ID number
                            callsignInfos.get(i).CountryNameEn,
                            callsignInfos.get(i).CountryNameCN,
                            callsignInfos.get(i).CQZone,
                            callsignInfos.get(i).ITUZone,
                            callsignInfos.get(i).Continent,
                            callsignInfos.get(i).Latitude,
                            callsignInfos.get(i).Longitude,
                            callsignInfos.get(i).GMT_offset,
                            callsignInfos.get(i).DXCC});
                    Set<String> calls = CallsignFileOperation.getCallsigns(callsignInfos.get(i).CallSign);

                    for (String s : calls
                    ) {
                        values.put("countryId", i);
                        values.put("callsign", s);
                        db.insert("callsigns", null, values);
                        values.clear();
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error: " + e.getMessage());
                }
            }
            Log.d(TAG, "Callsign location data import complete!");
            return null;
        }
    }

}
