package com.k1af.ft8af.count;
/**
 * Database operations for QSO log statistics.
 * Note: Currently location statistics are grid-based. Callsign prefix-based statistics are more accurate but too slow, resulting in poor user experience.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;

import com.google.android.gms.maps.model.LatLng;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.k1af.ft8af.callsign.CallsignInfo;
import com.k1af.ft8af.maidenhead.MaidenheadGrid;

import java.util.ArrayList;

public class CountDbOpr {
    private static final String TAG="CountDbOpr";

    public enum ChartType {Bar, Pie, Line, None}
    public static final int ChartBar=0;
    public static final int ChartPie=1;
    public static final int ChartLine=2;
    public static final int ChartNone=3;
    public static void getQSLTotal(SQLiteDatabase db,AfterCount afterCount){
        //number of contacted callsigns
        //SELECT count(DISTINCT callsign)  FROM QslCallsigns qc
        new GetTotal(db,afterCount).execute();
    }

    public static void getDxcc(SQLiteDatabase db,AfterCount afterCount){
        new GetDxccCount(db,afterCount).execute();
    }

    public static void getCQZoneCount(SQLiteDatabase db,AfterCount afterCount){
        new GetCqZoneCount(db,afterCount).execute();
    }
    public static void getItuCount(SQLiteDatabase db,AfterCount afterCount){
        new GetItuZoneCount(db,afterCount).execute();
    }
    public static void getBandCount(SQLiteDatabase db,AfterCount afterCount){
        new GetBandCount(db,afterCount).execute();
    }

    public static void getDistanceCount(SQLiteDatabase db,AfterCount afterCount){
        new DistanceCount(db,afterCount).execute();
    }

    /**
     * Count the continents worked, for the "Worked All Continents" (WAC) award.
     * Fires the callback once with one {@link CountValue} per worked continent
     * (its {@code name} is the continent code, e.g. "EU"); the presentation logic
     * lives in the pure Kotlin {@code workedAllContinents} reducer.
     */
    public static void getContinentCount(SQLiteDatabase db,AfterCount afterCount){
        new GetContinentCount(db,afterCount).execute();
    }

    /**
     * The distinct continent codes worked, derived from each log gridsquare via
     * the DXCC lookup tables ({@code dxcc_grid} grid -> DXCC entity, then
     * {@code dxccList} DXCC -> continent) exactly like the DXCC / CQ-zone stats.
     *
     * <p>Extracted as a synchronous static method (separate from the AsyncTask)
     * so the grid -> continent join is unit-testable against an in-memory
     * database without touching the UI thread. Blank / null continents are
     * filtered in SQL, so the returned list holds only usable codes (which may
     * still include the non-WAC "AN"/"-" — the Kotlin reducer drops those).
     *
     * @return one continent code per group; empty when no logged grid resolves.
     */
    @SuppressLint("Range")
    public static java.util.List<String> queryWorkedContinents(SQLiteDatabase db){
        java.util.ArrayList<String> continents=new java.util.ArrayList<>();
        String querySQL="SELECT dl.continent AS cont FROM dxcc_grid dg\n" +
                "inner join QSLTable q on dg.grid = UPPER(SUBSTR(q.gridsquare,1,4))\n" +
                "left join dxccList dl on dg.dxcc = dl.dxcc\n" +
                "WHERE dl.continent IS NOT NULL AND TRIM(dl.continent) <> ''\n" +
                "GROUP BY dl.continent";
        Cursor cursor=db.rawQuery(querySQL,null);
        while (cursor.moveToNext()){
            continents.add(cursor.getString(cursor.getColumnIndex("cont")));
        }
        cursor.close();
        return continents;
    }

    /**
     * Accumulated maximum/minimum great-circle distance (in km) from the
     * operator's own grid to a set of log gridsquares. {@link #hasData()}
     * distinguishes "no valid contacts were found" from a genuine 0 km
     * (same-grid) contact.
     */
    static class DistanceStats {
        /** Sentinel matching the legacy "unset minimum" value. */
        static final double NO_MIN = 6553500d;

        double maxKm = -1;
        double minKm = NO_MIN;
        String maxGrid = "";
        String minGrid = "";

        boolean hasData() {
            return maxKm > -1 && minKm < NO_MIN;
        }

        /** Fold another band's extremes into this (global) accumulator. */
        void merge(DistanceStats other) {
            if (other.maxKm > maxKm) {
                maxKm = other.maxKm;
                maxGrid = other.maxGrid;
            }
            if (other.minKm < minKm) {
                minKm = other.minKm;
                minGrid = other.minGrid;
            }
        }
    }

    /**
     * Compute the maximum and minimum great-circle distance from {@code myGrid}
     * to each gridsquare in {@code grids}.
     *
     * <p>Gridsquares that do not parse as a Maidenhead locator are SKIPPED
     * rather than being treated as 0 km. {@link MaidenheadGrid#getDist(String, String)}
     * returns 0 when either grid fails to parse (wrong length, or an end-of-QSO
     * token such as {@code "RR73"} that operators commonly leak into the
     * GRIDSQUARE field), so counting those as 0 km would collapse the "Nearest
     * distance" statistic to 0 with a junk grid whenever the log holds even one
     * contaminated row. A genuine same-grid (0 km) contact still parses and is
     * retained. If {@code myGrid} itself is not a valid locator no distance can
     * be computed and empty stats are returned.
     */
    static DistanceStats computeDistanceStats(java.util.List<String> grids, String myGrid) {
        DistanceStats stats = new DistanceStats();
        if (grids == null) {
            return stats;
        }
        LatLng myLatLng = MaidenheadGrid.gridToLatLng(myGrid);
        if (myLatLng == null) {
            return stats;
        }
        for (String grid : grids) {
            LatLng gridLatLng = MaidenheadGrid.gridToLatLng(grid);
            if (gridLatLng == null) {
                continue;
            }
            double distance = MaidenheadGrid.getDist(gridLatLng, myLatLng);
            if (distance > stats.maxKm) {
                stats.maxKm = distance;
                stats.maxGrid = grid;
            }
            if (distance < stats.minKm) {
                stats.minKm = distance;
                stats.minGrid = grid;
            }
        }
        return stats;
    }


    static class DistanceCount extends AsyncTask<Void,Void,Void>{
        private final SQLiteDatabase db;
        private final AfterCount afterCount;

        public DistanceCount(SQLiteDatabase db, AfterCount afterCount) {
            this.db = db;
            this.afterCount = afterCount;
        }

        @SuppressLint("Range")
        @Override
        protected Void doInBackground(Void... voids) {
            String querySQL;
            Cursor cursor;
            String myGrid = GeneralVariables.getMyMaidenheadGrid();

            DistanceStats global = new DistanceStats();

            ArrayList<CountValue> values=new ArrayList<>();

            ArrayList<String> bands=new ArrayList<>();
            querySQL="select DISTINCT band from QSLTable q WHERE gridsquare<>\"\"";
            cursor = db.rawQuery(querySQL,null);

            while (cursor.moveToNext()){
                bands.add(cursor.getString(cursor.getColumnIndex("band")));
            }
            cursor.close();

            for (String band:bands) {

                querySQL = "SELECT DISTINCT SUBSTR(gridsquare,1,4) as g FROM QSLTable q where (gridsquare <>\"\")and(band=?)";
                cursor = db.rawQuery(querySQL, new String[]{band});
                ArrayList<String> grids = new ArrayList<>();
                while (cursor.moveToNext()) {
                    grids.add(cursor.getString(cursor.getColumnIndex("g")));
                }
                cursor.close();

                DistanceStats bandStats = computeDistanceStats(grids, myGrid);
                global.merge(bandStats);

                if (bandStats.hasData()){
                    values.add(new CountValue((int) Math.round(MaidenheadGrid.convertDist(bandStats.maxKm)),String.format(
                            GeneralVariables.getStringFromResource(R.string.maximum_distance)
                            ,getQSLInfo(bandStats.maxGrid))));
                    values.add(new CountValue((int) Math.round(MaidenheadGrid.convertDist(bandStats.minKm)),String.format(
                            GeneralVariables.getStringFromResource(R.string.minimum_distance)
                            ,getQSLInfo(bandStats.minGrid))));
                }
            }

           String info=String.format(GeneralVariables.getStringFromResource(R.string.count_distance_info)
                   ,MaidenheadGrid.convertDist(global.maxKm),MaidenheadGrid.getDistUnitLabel(),global.maxGrid
                   ,MaidenheadGrid.convertDist(global.minKm),MaidenheadGrid.getDistUnitLabel(),global.minGrid);

            if (afterCount!=null &&(global.maxKm>0)&&global.hasData()){
                afterCount.countInformation(new CountInfo(info
                        ,ChartType.None
                        ,GeneralVariables.getStringFromResource(R.string.distance_statistics)
                        ,values));
            }
            return null;
        }

        @SuppressLint("Range")
        private String getQSLInfo(String grid){
            String querySQL="SELECT call,band,freq,qso_date,time_on,gridsquare  FROM QSLTable q " +
                    "where SUBSTR(gridsquare,1,4) =? LIMIT 1";
            Cursor cursor = db.rawQuery(querySQL,new String[]{grid});
            StringBuilder result=new StringBuilder();
            int breakLine=0;
            try {
                while (cursor.moveToNext()){
                     String call=cursor.getString(cursor.getColumnIndex("call"));
                     String band=cursor.getString(cursor.getColumnIndex("band"));
                     String freq=cursor.getString(cursor.getColumnIndex("freq"));
                     //String qso_date=cursor.getString(cursor.getColumnIndex("qso_date"));
                     //String time_on=cursor.getString(cursor.getColumnIndex("time_on"));
                     String gridsquare=cursor.getString(cursor.getColumnIndex("gridsquare"));

                     //get the callsign location
                    CallsignInfo callsignInfo= GeneralVariables.callsignDatabase.getCallInfo(call);

                     if (breakLine>0) result.append("\n");
                     result.append(String.format("%s %s(%s) %s\n %s", call, freq, band
                             ,gridsquare,callsignInfo != null ? callsignInfo.toString() : ""));
                     breakLine++;
                }
            } finally {
                cursor.close();
            }
            return  result.toString();
        }


    }


    /**
     * Count band distribution.
     */
    static class GetBandCount extends AsyncTask<Void, Void, Void> {
        private final SQLiteDatabase db;
        private final AfterCount afterCount;

        public GetBandCount(SQLiteDatabase db, AfterCount afterCount) {
            this.db = db;
            this.afterCount = afterCount;
        }

        @SuppressLint({"Range", "DefaultLocale"})
        @Override
        protected Void doInBackground(Void... voids) {
            int successCount=0;
            ArrayList<CountValue> values=new ArrayList<>();
            String querySQL;
            Cursor cursor;
            querySQL="SELECT UPPER( band) as band ,count(*) as c FROM QSLTable q \n" +
                    "GROUP BY UPPER( band) ORDER BY COUNT(*) desc ";
            cursor = db.rawQuery(querySQL,null);
            while (cursor.moveToNext()){
                int count=cursor.getInt(cursor.getColumnIndex("c"));
                successCount=successCount+count;
                values.add(new CountValue(count
                        ,String.format("%s",cursor.getString(cursor.getColumnIndex("band")))));
            }
            StringBuilder stringBuilder=new StringBuilder();
            stringBuilder.append(String.format(GeneralVariables.getStringFromResource(R.string.count_total),successCount));
            for (int i = 0; i < values.size(); i++) {
                stringBuilder.append(String.format("\n%s:\t%d",values.get(i).name,values.get(i).value));
            }
            cursor.close();
            if (afterCount!=null){
                afterCount.countInformation(new CountInfo(
                        stringBuilder.toString()
                        ,ChartType.Pie,GeneralVariables.getStringFromResource(R.string.band_statistics)
                        ,values));
            }

            return null;
        }
    }
    /**
     * Count QSO totals.
     */
    static class GetTotal extends AsyncTask<Void, Void, Void>{
        private final SQLiteDatabase db;
        private final AfterCount afterCount;

        public GetTotal(SQLiteDatabase db, AfterCount afterCount) {
            this.db = db;
            this.afterCount = afterCount;
        }

        @SuppressLint({"DefaultLocale", "Range"})
        @Override
        protected Void doInBackground(Void... voids) {

            int logCount=0;//log count
            int callsignCount=0;//callsign count
            int isQslCount=0;//confirmed count
            int isLotwQslCount=0;//third-party platform confirmed count

            //number of contacted callsigns
            String querySQL;
            Cursor cursor;
            querySQL="SELECT count(*) AS C FROM QSLTable q";
            cursor = db.rawQuery(querySQL,null);
            cursor.moveToFirst();
            logCount=cursor.getInt(cursor.getColumnIndex("C"));
            cursor.close();

            querySQL="SELECT count(DISTINCT \"call\") AS C FROM QSLTable q ";
            cursor = db.rawQuery(querySQL,null);
            cursor.moveToFirst();
            callsignCount=cursor.getInt(cursor.getColumnIndex("C"));
            cursor.close();

            querySQL="SELECT count(*) AS C FROM QSLTable q   WHERE isQSL =1 or isLotW_QSL =1";
            cursor = db.rawQuery(querySQL,null);
            cursor.moveToFirst();
            isQslCount=cursor.getInt(cursor.getColumnIndex("C"));
            cursor.close();

            querySQL="SELECT count(*) AS C FROM QSLTable q   WHERE isLotW_QSL =1";
            cursor = db.rawQuery(querySQL,null);
            cursor.moveToFirst();
            isLotwQslCount=cursor.getInt(cursor.getColumnIndex("C"));
            cursor.close();

            float qslPercent=0;
            if (logCount>0){
                qslPercent=100f*(float) isQslCount/(float) logCount;
            }
            //result.append(String.format("Contacted callsign count:%d",cursor.getInt(cursor.getColumnIndex("C"))));

            if (afterCount!=null){
                ArrayList<CountValue> values=new ArrayList<>();
                values.add(new CountValue(isQslCount,GeneralVariables.getStringFromResource(R.string.count_confirmed)));
                values.add(new CountValue(logCount-isQslCount,GeneralVariables.getStringFromResource(R.string.count_unconfirmed)));
                StringBuilder stringBuilder=new StringBuilder();
                stringBuilder.append(GeneralVariables.getStringFromResource(R.string.count_total_logs));
                stringBuilder.append("\n"+GeneralVariables.getStringFromResource(R.string.count_confirmed_log));
                stringBuilder.append("\n"+GeneralVariables.getStringFromResource(R.string.count_lotw_confirmed));
                stringBuilder.append("\n"+GeneralVariables.getStringFromResource(R.string.count_manually_confirmed));
                stringBuilder.append("\n"+GeneralVariables.getStringFromResource(R.string.count_confirmed_proportion));
                stringBuilder.append("\n"+GeneralVariables.getStringFromResource(R.string.count_tota_callsigns));
                afterCount.countInformation(new CountInfo(
                        //String.format("Log count:%d\nConfirmed logs:%d\nPlatform confirmed logs:%d" +
                        //        "\nManually confirmed logs:%d\nConfirmation ratio:%.1f%%\nCallsign count:%d"
                        String.format(stringBuilder.toString()
                        ,logCount,isQslCount,isLotwQslCount,isQslCount-isLotwQslCount,qslPercent,callsignCount)
                        ,ChartType.Pie,GeneralVariables.getStringFromResource(R.string.confirmation_statistics)
                        ,values));
            }

            return null;
        }
    }

    /**
     * Count ITU zone totals.
     */
    static class GetItuZoneCount extends AsyncTask<Void, Void, Void> {
        private static final String TAG = "GetItuZoneCount";
        private final SQLiteDatabase db;
        private final AfterCount afterCount;

        public GetItuZoneCount(SQLiteDatabase db, AfterCount afterCount) {
            this.db = db;
            this.afterCount = afterCount;
        }

        @SuppressLint({"Range", "DefaultLocale"})
        @Override
        protected Void doInBackground(Void... voids) {
            ArrayList<CountValue> countValues=new ArrayList<>();
            int ituZoneCount=0;
            int successCount=0;
            String querySQL;
            Cursor cursor;


            querySQL="SELECT count(DISTINCT itu) as c From ituList il ";

            cursor = db.rawQuery(querySQL,null);
            cursor.moveToFirst();
            ituZoneCount=cursor.getInt(cursor.getColumnIndex("c"));
            cursor.close();

            querySQL="SELECT il.itu  ,count(*) as c  FROM   ituList il \n" +
                    "inner join  QSLTable q\n" +
                    "on  il.grid =UPPER(SUBSTR(q.gridsquare,1,4))\n" +
                    "GROUP BY il.itu ORDER BY COUNT(*) DESC";

            cursor = db.rawQuery(querySQL,null);
            while (cursor.moveToNext()){
                successCount++;
                countValues.add(new CountValue(cursor.getInt(cursor.getColumnIndex("c"))
                        ,String.format(GeneralVariables.getStringFromResource(R.string.count_zone)
                                ,cursor.getString(cursor.getColumnIndex("itu")))));
            }
            cursor.close();

            if (afterCount!=null){
                afterCount.countInformation(new CountInfo(""
                        ,ChartType.Bar
                        ,GeneralVariables.getStringFromResource(R.string.itu_completion_statistics)
                        ,countValues));
            }
            if (afterCount!=null&&ituZoneCount!=0){
                ArrayList<CountValue> values=new ArrayList<>();
                values.add(new CountValue(successCount
                        ,GeneralVariables.getStringFromResource(R.string.count_completed)));
                values.add(new CountValue(ituZoneCount-successCount
                        ,GeneralVariables.getStringFromResource(R.string.count_incomplete)));
                afterCount.countInformation(new CountInfo(
                        String.format(GeneralVariables.getStringFromResource(R.string.count_total_itu)
                                ,ituZoneCount,successCount,100f*(float)successCount/(float) ituZoneCount)
                        ,ChartType.Pie
                        ,GeneralVariables.getStringFromResource(R.string.count_itu_completed_scale)
                        ,values));
            }
            return null;
        }
    }



    /**
     * Count CQ zones.
     */
    static class GetCqZoneCount extends AsyncTask<Void, Void, Void> {
        private static final String TAG = "GetCqZoneCount";
        private final SQLiteDatabase db;
        private final AfterCount afterCount;

        public GetCqZoneCount(SQLiteDatabase db, AfterCount afterCount) {
            this.db = db;
            this.afterCount = afterCount;
        }

        @SuppressLint({"Range", "DefaultLocale"})
        @Override
        protected Void doInBackground(Void... voids) {
            ArrayList<CountValue> countValues=new ArrayList<>();
            int cqZoneCount=0;
            int successCount=0;
            String querySQL;
            Cursor cursor;


            querySQL="SELECT count(DISTINCT cqzone) as c From cqzoneList cl ";

            cursor = db.rawQuery(querySQL,null);
            cursor.moveToFirst();
            cqZoneCount=cursor.getInt(cursor.getColumnIndex("c"));
            cursor.close();

            querySQL="SELECT cl.cqzone ,count(*) as c  FROM   cqzoneList cl \n" +
                    "inner join  QSLTable q \n" +
                    "on  cl.grid =UPPER(SUBSTR(q.gridsquare,1,4))   \n" +
                    "GROUP BY cl.cqzone ORDER BY COUNT(*) DESC";

            cursor = db.rawQuery(querySQL,null);
            while (cursor.moveToNext()){
                successCount++;
                countValues.add(new CountValue(cursor.getInt(cursor.getColumnIndex("c"))
                        ,String.format(GeneralVariables.getStringFromResource(R.string.count_zone)
                                ,cursor.getString(cursor.getColumnIndex("cqzone")))));
            }
            cursor.close();

            if (afterCount!=null){
                afterCount.countInformation(new CountInfo(""
                        ,ChartType.Bar
                        ,GeneralVariables.getStringFromResource(R.string.count_cqzone_completed)
                        ,countValues));
            }
            if (afterCount!=null&&cqZoneCount!=0){
                ArrayList<CountValue> values=new ArrayList<>();
                values.add(new CountValue(successCount
                        ,GeneralVariables.getStringFromResource(R.string.count_completed)));
                values.add(new CountValue(cqZoneCount-successCount
                        ,GeneralVariables.getStringFromResource(R.string.count_incomplete)));
                afterCount.countInformation(new CountInfo(
                        String.format(GeneralVariables.getStringFromResource(R.string.count_total_cqzone)
                                ,cqZoneCount,successCount,100f*(float)successCount/(float) cqZoneCount)
                        ,ChartType.Pie
                        ,GeneralVariables.getStringFromResource(R.string.count_cqzone_proportion)
                        ,values));
            }


            return null;
        }
    }
    /**
     * Count DXCC entity data.
     */
    static class GetDxccCount extends AsyncTask<Void, Void, Void>{
        private static final String TAG="GetDxccCount";
        private final SQLiteDatabase db;
        private final AfterCount afterCount;

        class DxccInfo{
            String name;
            int dxcc;
            int count=0;

            public DxccInfo(String name, int dxcc, int count) {
                this.name = name;
                this.dxcc = dxcc;
                this.count = count;
            }
        }

        public GetDxccCount(SQLiteDatabase db, AfterCount afterCount) {
            this.db = db;
            this.afterCount = afterCount;
        }

        @SuppressLint({"Range", "DefaultLocale"})
        @Override
        protected Void doInBackground(Void... voids) {
            //DXCC count
            ArrayList<CountValue> dxccValues=new ArrayList<>();
            int dxccCount=0;
            Cursor cursor;
            String querySQL;
            querySQL="SELECT count(*) as c FROM dxcclist";
            cursor = db.rawQuery(querySQL,null);
            cursor.moveToFirst();
            dxccCount=cursor.getInt(cursor.getColumnIndex("c"));
            cursor.close();

            querySQL = "SELECT dg.dxcc,count(*) as c ,dl.aname as dxccName FROM   dxcc_grid dg\n" +
                    "inner join  QSLTable q \n" +
                    "on  dg.grid =UPPER(SUBSTR(q.gridsquare,1,4))  LEFT JOIN dxccList dl on dg.dxcc =dl.dxcc \n" +
                    "GROUP BY dg.dxcc ,dl.aname ORDER BY count(*) DESC";
            cursor = db.rawQuery(querySQL,null);
            int successCount=0;
            while (cursor.moveToNext()){
                dxccValues.add(new CountValue(cursor.getInt(cursor.getColumnIndex("c"))
                ,cursor.getString(cursor.getColumnIndex("dxccName"))));
                successCount++;
            }
            cursor.close();

            if (afterCount!=null){
                afterCount.countInformation(new CountInfo(""
                        ,ChartType.Bar
                        ,GeneralVariables.getStringFromResource(R.string.dxcc_completion_statistics)
                        ,dxccValues));
            }


            if (afterCount!=null&&dxccCount!=0){
                ArrayList<CountValue> values=new ArrayList<>();
                values.add(new CountValue(successCount
                        ,GeneralVariables.getStringFromResource(R.string.count_completed)));
                values.add(new CountValue(dxccCount-successCount
                        ,GeneralVariables.getStringFromResource(R.string.count_incomplete)));
                afterCount.countInformation(new CountInfo(
                        String.format(GeneralVariables.getStringFromResource(R.string.count_total_dxcc)
                                ,dxccCount,successCount,100f*(float)successCount/(float) dxccCount)
                        ,ChartType.Pie
                        ,GeneralVariables.getStringFromResource(R.string.count_dxcc_proportion)
                        ,values));
            }

            return null;
        }
    }




    /**
     * Gather the worked continents for the WAC award off the UI thread. The
     * heavy lifting (the grid -> continent join) is in {@link #queryWorkedContinents}
     * so this task is just the async wrapper + callback marshalling.
     */
    static class GetContinentCount extends AsyncTask<Void, Void, Void>{
        private final SQLiteDatabase db;
        private final AfterCount afterCount;

        public GetContinentCount(SQLiteDatabase db, AfterCount afterCount) {
            this.db = db;
            this.afterCount = afterCount;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            java.util.List<String> continents = queryWorkedContinents(db);
            ArrayList<CountValue> values = new ArrayList<>();
            for (String c : continents) {
                values.add(new CountValue(1, c));
            }
            if (afterCount != null) {
                afterCount.countInformation(new CountInfo(""
                        , ChartType.Bar
                        , GeneralVariables.getStringFromResource(R.string.continent_completion_statistics)
                        , values));
            }
            return null;
        }
    }

    public interface  AfterCount{
        void countInformation(CountInfo countInfo);
    }

    static public class CountInfo{
        public String title;
        public String info;
        public ArrayList<CountValue> values=null;
        public ChartType chartType;

        public CountInfo(String info,ChartType chartType,String title
                , ArrayList<CountValue> values) {
            this.info = info;
            this.values = values;
            this.title=title;
            this.chartType=chartType;
        }
    }

    static public class CountValue{
        public int value;
        public String name;

        public CountValue(int value, String name) {
            this.value = value;
            this.name = name;
        }
    }


}
