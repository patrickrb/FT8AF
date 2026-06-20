package com.k1af.ft8af.grid_tracker;
/**
 * Operations for drawing QSO lines and grids in OsmMapView. The map uses SQLite format
 * with an offline tile source (nightUSGS4Layer).
 * @author BGY70Z
 * @date 2023-03-20
 */

import static java.lang.Math.PI;
import static java.lang.Math.asin;
import static java.lang.Math.atan;
import static java.lang.Math.cos;
import static java.lang.Math.floor;
import static java.lang.Math.sin;
import static java.lang.Math.tan;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.util.Log;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.database.DatabaseOpr;
import com.k1af.ft8af.log.QSLRecordStr;
import com.k1af.ft8af.maidenhead.MaidenheadGrid;
import com.google.android.gms.maps.model.LatLng;

import org.osmdroid.tileprovider.IRegisterReceiver;
import org.osmdroid.tileprovider.modules.IArchiveFile;
import org.osmdroid.tileprovider.modules.OfflineTileProvider;
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsDisplay;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.milestones.MilestoneLineDisplayer;
import org.osmdroid.views.overlay.milestones.MilestoneLister;
import org.osmdroid.views.overlay.milestones.MilestoneManager;
import org.osmdroid.views.overlay.milestones.MilestoneMeterDistanceSliceLister;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class GridOsmMapView {
    private static final String TAG = "GridOsmMapView";

    public enum GridMode {//Grid display mode
        QSX, QSO, QSL
    }

    public enum ShowTipsMode {
        ALL, NEW, NONE
    }

    private final MainViewModel mainViewModel;
    //public static int COLOR_QSX = 0x7f0000ff;//Red 50%, not yet contacted
    //public static int COLOR_QSO = 0x7fffff00;//Yellow 50%, contacted
    //public static int COLOR_QSL = 0x7fff0000;//Red 50%, confirmed
    private boolean showCQ = true;
    private boolean showQSX = false;

    public final MapView gridMapView;
    private final Context context;
    // public ItemizedIconOverlay<OverlayItem> markerOverlay;
    // private final ArrayList<OverlayItem> markerItems = new ArrayList<>();
    private final ArrayList<GridPolyLine> gridLines = new ArrayList<>();
    private GridPolyLine selectedLine = null;
    private static final int TIME_OUT = 3;
    private int selectLineTimeOut = TIME_OUT;//Number of cycles the selected line remains highlighted
    private final ArrayList<GridPolygon> gridPolygons = new ArrayList<>();
    private final ArrayList<GridMarker> gridMarkers = new ArrayList<>();

    private ShowTipsMode showTipsMode = ShowTipsMode.NEW;

    public GridOsmMapView(Context context, MapView gridMapView, MainViewModel mainViewModel) {
        this.gridMapView = gridMapView;
        this.context = context;
        this.mainViewModel = mainViewModel;
    }


    public void initMap(String grid, boolean offset) {
        mapViewOtherData(gridMapView);//Set up the tile source
        gridMapView.setMultiTouchControls(true);
        gridMapView.setBuiltInZoomControls(true);//Show zoom buttons
        gridMapView.getZoomController().getDisplay().setPositions(true
                , CustomZoomButtonsDisplay.HorizontalPosition.RIGHT
                , CustomZoomButtonsDisplay.VerticalPosition.BOTTOM);
        gridMapView.setTilesScaledToDpi(true);

        gridMapView.setMaxZoomLevel(6.0);
        gridMapView.setMinZoomLevel(1.0);
        gridMapView.getController().setZoom(1.6);

        gridMapView.setUseDataConnection(true);
        gridMapView.setMultiTouchControls(true);
        gridMapView.getOverlayManager().getTilesOverlay().setEnabled(true);
        gridMapView.setSelected(true);
        setGrayLine();

        //addMarkerOverlay();//Add marker overlay


        //[A-Ra-r]{2}[0-9]{2}[A-Xa-x]{2}, regex for 6-character Maidenhead grid
        // [A-Ra-r]{2}[0-9]{2}, regex for 4-character Maidenhead grid
        LatLng latLng = MaidenheadGrid.gridToLatLng(grid);//Validate if it is a valid grid locator
        if (latLng != null) {
            if (offset) {
                gridMapView.getController().setCenter(new GeoPoint(latLng.latitude
                        , latLng.longitude - 90f));
            } else {
                gridMapView.getController().setCenter(new GeoPoint(latLng.latitude
                        , latLng.longitude));
            }
        }
    }

    /**
     * Zoom to fit the line within the visible bounds
     *
     * @param line the polyline
     */
    public void zoomToLineBound(GridPolyLine line) {
        BoundingBox boundingBox = new BoundingBox();
        selectedLine = line;
        selectLineTimeOut = TIME_OUT;
        line.getOutlinePaint().setColor(gridMapView.getResources().getColor(
                R.color.tracker_select_line_color));
        line.getOutlinePaint().setStrokeWidth(6);
        //mOutlinePaint = getStrokePaint(0xffFF1E27, 3);

        if (line.getActualPoints().size() < 2) return;
        GeoPoint eastNorthPoint = new GeoPoint(line.getActualPoints().get(0).getLatitude()
                , line.getActualPoints().get(0).getLongitude());
        GeoPoint westSouthPoint = new GeoPoint(line.getActualPoints().get(1).getLatitude()
                , line.getActualPoints().get(1).getLongitude());

        if (Math.abs(westSouthPoint.getLongitude() - eastNorthPoint.getLongitude()) > 180) {
            if (eastNorthPoint.getLongitude() > westSouthPoint.getLongitude()) {
                double temp = westSouthPoint.getLongitude();
                westSouthPoint.setLongitude(eastNorthPoint.getLongitude());
                eastNorthPoint.setLongitude(temp);

            }
        } else {
            if (eastNorthPoint.getLongitude() < westSouthPoint.getLongitude()) {
                double temp = westSouthPoint.getLongitude();
                westSouthPoint.setLongitude(eastNorthPoint.getLongitude());
                eastNorthPoint.setLongitude(temp);

            }
        }
        if (eastNorthPoint.getLatitude() < westSouthPoint.getLatitude()) {
            double temp = westSouthPoint.getLatitude();
            westSouthPoint.setLatitude(eastNorthPoint.getLatitude());
            eastNorthPoint.setLatitude(temp);
        }

        boundingBox.set(eastNorthPoint.getLatitude(), eastNorthPoint.getLongitude()
                , westSouthPoint.getLatitude(), westSouthPoint.getLongitude());

        gridMapView.zoomToBoundingBox(boundingBox, true, 100);
    }


    /**
     * Navigate to the CQ marker location
     *
     * @param marker the CQ marker
     * @param offset whether to apply a longitude offset
     */
    public void gotoCqGrid(GridMarker marker, boolean offset) {
        GeoPoint geoPoint = new GeoPoint(marker.getPosition());
        if (offset) {
            geoPoint.setLongitude(geoPoint.getLongitude() - 40f);
        }
        gridMapView.getController().animateTo(geoPoint, 2.5, 500L);
    }


    public synchronized GridMarker addGridMarker(String grid, Ft8Message msg) {
        //todo For type 4.0 CQ messages that lack grid info, the country's coordinates could be used instead
        if (LatLng2GeoPoint(MaidenheadGrid.gridToLatLng(grid)) == null) return null;
        GridMarker marker = new GridMarker(context, mainViewModel, gridMapView, grid, msg);
        gridMarkers.add(marker);
        return marker;
    }

    /**
     * Clear all markers
     */
    public synchronized void clearMarkers() {
        for (GridMarker marker : gridMarkers) {
            marker.closeInfoWindow();
            gridMapView.getOverlays().remove(marker);
        }
        gridMarkers.clear();
        gridMapView.invalidate();
    }

    public GridPolyLine getSelectedLine() {
        return selectedLine;
    }

    public void clearSelectedLines() {
        if (selectedLine != null) {
            selectedLine.closeInfoWindow();
            gridMapView.getOverlays().remove(selectedLine);
            selectedLine = null;

        }
    }

    /**
     * Clear all lines
     */
    public synchronized void clearLines() {

        boolean isOpening = false;

        if (selectedLine != null) {
            selectLineTimeOut--;
            isOpening = selectedLine.isInfoWindowOpen();
            selectedLine.closeInfoWindow();
            gridMapView.getOverlays().remove(selectedLine);
        }
        for (GridPolyLine line : gridLines) {
            line.closeInfoWindow();
            gridMapView.getOverlays().remove(line);
        }
        gridLines.clear();
        if (selectedLine != null && selectLineTimeOut > 0) {
            gridMapView.getOverlays().add(selectedLine);
            if (isOpening) selectedLine.showInfoWindow();
        }
        gridMapView.invalidate();
    }


    /**
     * Clear all grid polygons
     */
    public synchronized void clearGridPolygon() {
        for (GridPolygon polygon : gridPolygons) {
            gridMapView.getOverlays().remove(polygon);
        }
        gridPolygons.clear();
        gridMapView.invalidate();
    }

    /**
     * Clear all overlays
     */
    public void clearAll() {
        clearMarkers();
        clearLines();
        clearGridPolygon();
    }

    /**
     * Find a grid polygon by grid locator; returns null if not found
     *
     * @param grid the grid locator
     * @return the grid polygon, or null
     */
    public synchronized GridPolygon getGridPolygon(String grid) {
        synchronized (gridPolygons) {
            for (GridPolygon polygon : gridPolygons) {
                if (polygon.grid.equals(grid)) return polygon;
            }
        }
        return null;
    }

    /**
     * Mark or update the grid where a new message occurred
     *
     * @param grid      the grid locator
     * @param msg       the message content
     * @param subDetail additional detail text
     * @return the grid polygon object
     */
    public GridPolygon upgradeGridInfo(String grid, String msg, String subDetail) {
        GridPolygon gridPolygon = getGridPolygon(grid);
        if (gridPolygon == null) {
            gridPolygon = addGridPolygon(grid, GridMode.QSX);
        }
        gridPolygon.setSnippet(msg);
        gridPolygon.setSubDescription(subDetail);
        //gridPolygon.showInfoWindow();
        return gridPolygon;
    }

    /**
     * Mark or update the grid from a historical QSO record
     *
     * @param recordStr the QSO record
     * @return the grid polygon object
     */
    public GridPolygon upgradeGridInfo(QSLRecordStr recordStr) {
        GridPolygon gridPolygon = getGridPolygon(recordStr.getGridsquare());
        if (gridPolygon == null) {
            if (recordStr.isQSL) {
                gridPolygon = addGridPolygon(recordStr.getGridsquare(), GridMode.QSL);
            } else {
                gridPolygon = addGridPolygon(recordStr.getGridsquare(), GridMode.QSO);
            }
        }

        gridPolygon.setSnippet(String.format(String.format("%s %s",
                String.format(GeneralVariables.getStringFromResource(R.string.qsl_freq)
                        , recordStr.getFreq()),
                String.format(GeneralVariables.getStringFromResource(R.string.qsl_band)
                        , recordStr.getBand()))));

        gridPolygon.setSubDescription(String.format("%s\n%s\n%s  %s\n%s %s",
                String.format(GeneralVariables.getStringFromResource(R.string.qsl_start_time)
                        , recordStr.getTime_on()),
                String.format(GeneralVariables.getStringFromResource(R.string.qsl_end_time)
                        , recordStr.getTime_off()),
                String.format(GeneralVariables.getStringFromResource(R.string.qsl_rst_rcvd)
                        , recordStr.getRst_rcvd()),
                String.format(GeneralVariables.getStringFromResource(R.string.qsl_rst_sent)
                        , recordStr.getRst_sent()),

                String.format(GeneralVariables.getStringFromResource(R.string.qsl_mode)
                        , recordStr.getMode()),
                recordStr.getComment()
        ));
        gridPolygon.setTitle(String.format("%s--%s", recordStr.getCall(), recordStr.getStation_callsign()));//Display message content
        gridPolygon.setInfoWindow(new GridRecordInfoWindow(R.layout.tracker_record_info_win, gridMapView));
        return gridPolygon;
    }

    /**
     * Refresh the map view
     */
    public void mapUpdate(){
        gridMapView.invalidate();
    }

    /**
     * Upgrade grid status. If the grid does not exist, it is new and will be added (returns false).
     * If it already exists, returns true.
     *
     * @param grid     the grid locator
     * @param gridMode the mode
     * @return true if the grid already existed
     */
    public boolean upgradeGridMode(String grid, GridMode gridMode) {
        GridPolygon polygon = getGridPolygon(grid);
        if (polygon != null) {
            polygon.upgradeGridMode(gridMode);
            return true;
        } else {
            addGridPolygon(grid, gridMode);
            return false;
        }
    }

    /**
     * Add a grid polygon overlay
     *
     * @param grid     the grid locator
     * @param gridMode the grid type
     * @return the created grid polygon object
     */
    public synchronized GridPolygon addGridPolygon(String grid, GridMode gridMode) {
        if (gridMapView == null) return null;
        if (gridMapView.getRepository()==null) return null;
        try {//When log volume is too large, a crash can occur; catch the exception here to prevent it

            GridPolygon polygon = new GridPolygon(context, gridMapView, grid, gridMode);
            gridPolygons.add(polygon);
            gridMapView.getOverlays().add(polygon);
            return polygon;

        } catch (Exception e) {
            //throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * Find a matching CQ marker for the given message
     *
     * @param message the message
     * @return the matching marker, or null
     */
    public GridMarker getMarker(Ft8Message message) {
        for (GridMarker marker : gridMarkers) {
            if (marker.msg == message) {
                return marker;
            }
        }
        return null;
    }

    /**
     * Find a matching line for the given message
     *
     * @param message the message
     * @return the matching line, or null
     */
    public GridPolyLine getLine(Ft8Message message) {
        for (GridPolyLine line : gridLines) {
            if (line.msg == message) {
                return line;
            }
        }
        return null;
    }

    /**
     * Draw a line between two grid locations.
     *
     * @param message the message
     * @param db      the database
     */
    public synchronized GridPolyLine drawLine(Ft8Message message, DatabaseOpr db) {
        LatLng fromLatLng = MaidenheadGrid.gridToLatLng(message.getMaidenheadGrid(db));
        LatLng toLatLng = MaidenheadGrid.gridToLatLng(message.getToMaidenheadGrid(db));
        if (fromLatLng == null) {
            fromLatLng = message.fromLatLng;
        }

        if (toLatLng == null) {
            toLatLng = message.toLatLng;
        }
        if (fromLatLng == null || toLatLng == null) {
            return null;
        }
        final GridPolyLine line = new GridPolyLine(gridMapView, fromLatLng, toLatLng, message);
        gridLines.add(line);
        return line;
    }

    public synchronized GridPolyLine drawLine(QSLRecordStr recordStr) {
        LatLng fromLatLng = MaidenheadGrid.gridToLatLng(recordStr.getGridsquare());
        LatLng toLatLng = MaidenheadGrid.gridToLatLng(recordStr.getMy_gridsquare());
        if (fromLatLng == null) {
            //todo Convert callsign to country coordinates
            return null;
        }

        if (toLatLng == null) {
            //todo Convert callsign to country coordinates
            return null;
        }
        final GridPolyLine line = new GridPolyLine(gridMapView, fromLatLng, toLatLng, recordStr);
        return line;
    }

    /**
     * Set the offline tile source for the map
     *
     * @param mapView osmMap
     */
    public void mapViewOtherData(MapView mapView) {
        //Can display different maps depending on the time of day
        String strFilepath = getAssetsCacheFile(context, context.getString(R.string.map_name));
        File exitFile = new File(strFilepath);
        if (!exitFile.exists()) {
            mapView.setTileSource(TileSourceFactory.USGS_SAT);
        } else {
            OfflineTileProvider tileProvider = new OfflineTileProvider(
                    (IRegisterReceiver) new SimpleRegisterReceiver(context), new File[]{exitFile});
            mapView.setTileProvider(tileProvider);
            String source = "";
            IArchiveFile[] archives = tileProvider.getArchives();
            if (archives.length > 0) {
                Set<String> tileSources = archives[0].getTileSources();
                if (!tileSources.isEmpty()) {
                    source = tileSources.iterator().next();
                    mapView.setTileSource(FileBasedTileSource.getSource(source));
                } else {
                    mapView.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE);
                }
            } else
                mapView.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE);
            mapView.invalidate();

        }
    }

    /**
     * Get the map file from the assets directory
     *
     * @param context  context
     * @param fileName the map file name in SQLite format
     * @return the full path to the cached file
     */
    public String getAssetsCacheFile(Context context, String fileName) {
        File cacheFile = new File(context.getCacheDir(), fileName);
        try {
            InputStream inputStream = context.getAssets().open(fileName);
            try {
                FileOutputStream outputStream = new FileOutputStream(cacheFile);
                try {
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = inputStream.read(buf)) > 0) {
                        outputStream.write(buf, 0, len);
                    }
                } finally {
                    outputStream.close();
                }
            } finally {
                inputStream.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return cacheFile.getAbsolutePath();
    }

    public static GeoPoint LatLng2GeoPoint(LatLng latLng) {
        if (latLng == null) return null;
        return new GeoPoint(latLng.latitude, latLng.longitude);
    }

    public static ArrayList<GeoPoint> LatLngs2GeoPoints(LatLng[] latLngs) {
        ArrayList<GeoPoint> geoPoints = new ArrayList<>();
        if (latLngs != null) {
            for (int i = 0; i < latLngs.length; i++) {
                geoPoints.add(LatLng2GeoPoint(latLngs[i]));
            }
        }
        return geoPoints;
    }

    public static class GridPolyLine extends Polyline {
        //public String fromGrid;
        //public String toGrid;
        public Ft8Message msg;
        public QSLRecordStr recorder;
        //public boolean marked = false;

        @SuppressLint("DefaultLocale")
        public GridPolyLine(MapView mapView, LatLng fromLatLng, LatLng toLatLng, QSLRecordStr recordStr) {
            super(mapView);
            this.recorder = recordStr;
            setSnippet(String.format(String.format("%s %s",
                    String.format(GeneralVariables.getStringFromResource(R.string.qsl_freq)
                            , recordStr.getFreq()),
                    String.format(GeneralVariables.getStringFromResource(R.string.qsl_band)
                            , recordStr.getBand()))));

            setSubDescription(String.format("%s\n%s\n%s  %s\n%s %s",
                    String.format(GeneralVariables.getStringFromResource(R.string.qsl_start_time)
                            , recordStr.getTime_on()),
                    String.format(GeneralVariables.getStringFromResource(R.string.qsl_end_time)
                            , recordStr.getTime_off()),
                    String.format(GeneralVariables.getStringFromResource(R.string.qsl_rst_rcvd)
                            , recordStr.getRst_rcvd()),
                    String.format(GeneralVariables.getStringFromResource(R.string.qsl_rst_sent)
                            , recordStr.getRst_sent()),

                    String.format(GeneralVariables.getStringFromResource(R.string.qsl_mode)
                            , recordStr.getMode()),
                    recordStr.getComment()
            ));
            setTitle(String.format("%s--%s", recordStr.getCall(), recordStr.getStation_callsign()));//Display message content
            this.mOutlinePaint = getStrokePaint(
                    mapView.getResources().getColor(
                            R.color.tracker_history_line_color), 3);
            List<GeoPoint> pts = new ArrayList<>();
            pts.add(GridOsmMapView.LatLng2GeoPoint(fromLatLng));
            pts.add(GridOsmMapView.LatLng2GeoPoint(toLatLng));


            setPoints(pts);
            setGeodesic(true);
            setInfoWindow(new GridRecordInfoWindow(R.layout.tracker_record_info_win, mapView));
            mapView.getOverlayManager().add(this);
        }

        @SuppressLint("DefaultLocale")
        public GridPolyLine(MapView mapView, LatLng fromLatLng, LatLng toLatLng, Ft8Message msg) {
            super(mapView);
            this.msg = msg;

            setSnippet(String.format("%s<--%s", msg.toWhere, msg.fromWhere));//Indicates the path
            setSubDescription(String.format("%dBm , %.1f ms , %s"
                    , msg.snr, msg.time_sec
                    , MaidenheadGrid.getDistLatLngStr(fromLatLng, toLatLng)));
            setTitle(msg.getMessageText());//Display message content
            if (msg.inMyCall()) {
                this.mOutlinePaint = getStrokePaint(
                        mapView.getResources().getColor(
                                R.color.tracker_in_my_line_color), 3);
            } else {
                this.mOutlinePaint = getStrokePaint(mapView.getResources().getColor(
                        R.color.tracker_line_color), 3);
            }

            List<GeoPoint> pts = new ArrayList<>();
            pts.add(GridOsmMapView.LatLng2GeoPoint(fromLatLng));
            pts.add(GridOsmMapView.LatLng2GeoPoint(toLatLng));


            setPoints(pts);
            setGeodesic(true);
            setInfoWindow(new GridInfoWindow(R.layout.tracker_grid_info_win, mapView, msg));
            mapView.getOverlayManager().add(this);
            //showInfoWindow();

            final float lineLen = (float) getDistance();
            final float pointLen = lineLen / 10f > 200000 ? 200000f : lineLen / 10f;

            final List<MilestoneManager> managers = new ArrayList<>();

            final MilestoneMeterDistanceSliceLister slicerForPath = new MilestoneMeterDistanceSliceLister();
            managers.add(getAnimatedPathManager(slicerForPath));

            setMilestoneManagers(managers);


            //Set up directional animation
            final ValueAnimator percentageCompletion = ValueAnimator.ofFloat(0, 1); // 10 kilometers

            percentageCompletion.setRepeatCount(ValueAnimator.INFINITE);
            percentageCompletion.setDuration(1000); // 1 seconds
            percentageCompletion.setStartDelay(0); // 1 second

            percentageCompletion.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    double dist = ((float) animation.getAnimatedValue()) * lineLen;
                    double distStart = dist - pointLen;
                    if (distStart < 0) distStart = 0;
                    slicerForPath.setMeterDistanceSlice(distStart, dist);
                    mapView.invalidate();
                }
            });
            percentageCompletion.start();
        }

        /**
         * Animated path points on the line, set to green with 15f width
         */
        private MilestoneManager getAnimatedPathManager(final MilestoneLister pMilestoneLister) {
            final Paint slicePaint = getStrokePaint(Color.GREEN, 15f);
            return new MilestoneManager(pMilestoneLister, new MilestoneLineDisplayer(slicePaint));
        }

        private Paint getStrokePaint(final int pColor, final float pWidth) {
            Paint paint = new Paint();
            paint.setStrokeWidth(pWidth);
            paint.setStyle(Paint.Style.STROKE);
            paint.setAntiAlias(true);
            paint.setColor(pColor);
            //paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setPathEffect(new DashPathEffect(new float[]{20, 10}, 0));
            return paint;
        }

        public void showNewInfo() {
            if (msg != null) {
                if ((msg.fromDxcc || msg.fromItu || msg.fromCq)
                        && !GeneralVariables.checkQSLCallsign(msg.callsignFrom)) {
                    showInfoWindow();
                }
            }
            if (recorder != null) {
                showInfoWindow();
            }

        }
    }

    public static class GridPolygon extends Polygon {
        public String grid;
        public GridMode gridMode;
        private final Context context;
        //private BasicInfoWindow infoWindow;
        //public String details;

        public GridPolygon(Context context, MapView mapView, String grid, GridMode gridMode) {
            super(mapView);
            this.grid = grid;
            this.gridMode = gridMode;
            this.context = context;

            setTitle(grid);
            setStrokeWidth(3f);
            setStrokeColor(this.context.getColor(R.color.osm_grid_out_line_color));

            updateGridMode();

            ArrayList<GeoPoint> pts = LatLngs2GeoPoints(MaidenheadGrid.gridToPolygon(grid));
            setPoints(pts);

            setVisible(true);

        }

        public synchronized void updateGridMode() {
            synchronized (this) {//Prevent crash
                switch (gridMode) {
                    case QSL:
                        this.mFillPaint.setColor(this.context.getColor(R.color.tracker_sample_qsl_color));
                        //setFillColor(this.context.getColor(R.color.tracker_sample_qsl_color));
                        break;
                    case QSO:
                        this.mFillPaint.setColor(this.context.getColor(R.color.tracker_sample_qso_color));
                        //setFillColor(this.context.getColor(R.color.tracker_sample_qso_color));
                        break;
                    case QSX:
                        this.mFillPaint.setColor(this.context.getColor(R.color.tracker_sample_qsx_color));
                        //setFillColor(this.context.getColor(R.color.tracker_sample_qsx_color));
                        break;
                }
            }
        }

        public synchronized void upgradeGridMode(GridMode mode) {
            if (mode.ordinal() > gridMode.ordinal()) {
                gridMode = mode;
                updateGridMode();
            }
        }
    }

    public static class GridMarker extends Marker {
        public String grid;
        private final Context context;
        private final Ft8Message msg;

        @SuppressLint({"UseCompatLoadingForDrawables", "DefaultLocale"})
        public GridMarker(Context context, MainViewModel mainViewModel, MapView mapView
                , String grid, Ft8Message msg) {
            super(mapView);
            this.grid = grid;
            this.context = context;
            this.msg = msg;

            this.setPosition(LatLng2GeoPoint(MaidenheadGrid.gridToLatLng(grid)));
            this.setAnchor(ANCHOR_CENTER, ANCHOR_BOTTOM);
            this.setInfoWindow(new GridMarkerInfoWindow(mainViewModel
                    , R.layout.tracker_cq_marker_info_win, mapView, msg));
            setSnippet(String.format("%d dBm , %.1f ms", msg.snr, msg.time_sec));
            setSubDescription(String.format("%s , %s"
                    , MaidenheadGrid.getDistStr(grid, GeneralVariables.getMyMaidenheadGrid())
                    , msg.fromWhere));//Indicates distance
            setTitle(msg.getMessageText());//Display message content


            @SuppressLint("UseCompatLoadingForDrawables")
            Drawable d;
            if (GeneralVariables.checkQSLCallsign(msg.callsignFrom)) {
                d = context.getDrawable(R.drawable.ic_baseline_cq_qso_24).mutate();
                d.setColorFilter(context.getColor(R.color.tracker_cq_marker_is_qso_color)
                        , PorterDuff.Mode.SRC_ATOP);

            } else {
                d = context.getDrawable(R.drawable.ic_baseline_cq_24).mutate();
            }
            if (GeneralVariables.checkQSLCallsign_OtherBand(msg.callsignFrom)) {
                d.setColorFilter(context.getColor(R.color.tracker_cq_marker_other_is_qso_color)
                        , PorterDuff.Mode.SRC_ATOP);
            }
            setIcon(d);

            //this.showInfoWindow();
            mapView.getOverlays().add(this);
        }

        public void showNewInfo() {
            if ((msg.fromDxcc || msg.fromItu || msg.fromCq || (msg.checkIsCQ()))
                    && !GeneralVariables.checkQSLCallsign(msg.callsignFrom)) {
                showInfoWindow();
            }
        }
    }


    /**
     * Show info windows based on the current display mode.
     */
    public void showInfoWindows() {
        setShowTipsMode(showTipsMode);
    }

    /**
     * Show all info windows
     */
    public void showAllInfoWindows() {
        if (showQSX) {
            for (GridPolyLine line : gridLines) {
                line.showInfoWindow();
            }
        }
        if (showCQ) {
            for (GridMarker marker : gridMarkers) {
                marker.showInfoWindow();
            }
        }
    }

    /**
     * Show only new info windows
     */
    public void showNewInfoWindows() {
        if (showQSX) {
            for (GridPolyLine line : gridLines) {
                line.showNewInfo();
            }
        }
        if (showCQ) {
            for (GridMarker marker : gridMarkers) {
                marker.showNewInfo();
            }
        }
    }

    /**
     * Close all info windows
     */
    public synchronized void hideInfoWindows() {
        for (GridPolygon polygon : gridPolygons
        ) {
            polygon.closeInfoWindow();
        }
        for (GridPolyLine line : gridLines) {
            line.closeInfoWindow();
        }
        for (GridMarker marker : gridMarkers) {
            marker.closeInfoWindow();
        }
        gridMapView.invalidate();
    }


    public void setShowCQ(boolean showCQ) {
        this.showCQ = showCQ;
        showInfoWindows();
    }

    public void setShowQSX(boolean showQSX) {
        this.showQSX = showQSX;
        showInfoWindows();
    }

    public void setShowTipsMode(ShowTipsMode showTipsMode) {
        this.showTipsMode = showTipsMode;
        hideInfoWindows();
        switch (this.showTipsMode) {
            case ALL:
                showAllInfoWindows();
                break;
            case NEW:
                showNewInfoWindows();
                break;
            case NONE:
                break;
        }
    }


    private static double[] computeDayNightTerminator(long t) {
        // The nice thing about the java time standard is that converting it
        // to a julian date is trivial - unlike the gyrations the original
        // matlab code had to go through to convert the y/n/d/h/m/s parameters
        final double julianDate1970 = t / (double) (1000 * 60 * 60 * 24);
        // convert from the unix epoch to the astronomical epoch
        // (noon on January 1, 4713 BC, GMT/UT) (the .5 is noon versus midnight)
        final double juliandate = julianDate1970 + 2440587.500000;
        final double K = PI / 180;
        // here be dragons!
        final double T = (juliandate - 2451545.0) / 36525;
        double L = 280.46645 + 36000.76983 * T + 0.0003032 * T * T;
        L = L % 360;
        if (L < 0)
            L = L + 360;
        double M = 357.52910 + 35999.05030 * T - 0.0001559 * T * T -
                0.00000048 * T * T * T;
        M = M % 360;
        if (M < 0)
            M = M + 360;
        final double C = (1.914600 - 0.004817 * T - 0.000014 * T * T) * sin(K * M) +
                (0.019993 - 0.000101 * T) * sin(K * 2 * M) +
                0.000290 * sin(K * 3 * M);
        final double theta = L + C;
        final double LS = L;
        final double LM = 218.3165 + 481267.8813 * T;
        final double eps0 = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 -
                (46.8150 * T +
                        0.00059 * T * T - 0.001813 * T * T * T) / 3600;
        final double omega = 125.04452 - 1934.136261 * T + 0.0020708 * T * T +
                T * T *
                        T / 450000;
        final double deltaEps =
                (9.20 * cos(K * omega) + 0.57 * cos(K * 2 * LS) +
                        0.10 * cos(K * 2 * LM) - 0.09 * cos(K * 2 * omega)) / 3600;
        final double eps = eps0 + deltaEps + 0.00256 *
                cos(K * (125.04 - 1934.136 * T));
        final double lambda = theta - 0.00569 - 0.00478 * sin(K * (125.04 -
                1934.136 *
                        T));
        final double delta = asin(sin(K * eps) * sin(K * lambda));
        final double dec = delta / K;
        final double tau = (juliandate - floor(juliandate)) * 360;
        double[] coords = new double[361];
        for (int i = 0; i < 361; i++)
            coords[i] = atan(cos((i - 180 + tau) * K) / tan(dec * K)) / K + 90;
        return coords;
    }

    /**
     * Draw the gray line (day/night terminator) based on the current time
     */
    public void setGrayLine() {
        double[] lats = computeDayNightTerminator(System.currentTimeMillis());
        LatLng[] grayLine = new LatLng[lats.length * 3];
        for (int i = 0; i < lats.length; i++) {
            grayLine[i] = new LatLng((lats[i] - 90), i);
            grayLine[lats.length + i] = new LatLng((lats[i] - 90), i);
            grayLine[lats.length * 2 + i] = new LatLng((lats[i] - 90), i);
        }

        Polyline line = new Polyline(gridMapView);
        line.setWidth(15f);
        line.setColor(context.getColor(R.color.tracker_gray_line_color));

        List<GeoPoint> pts = new ArrayList<>();
        for (int i = 0; i < grayLine.length; i++) {
            pts.add(GridOsmMapView.LatLng2GeoPoint(grayLine[i]));
        }
        line.setInfoWindow(null);
        line.setPoints(pts);
        gridMapView.getOverlays().add(line);

    }

}
