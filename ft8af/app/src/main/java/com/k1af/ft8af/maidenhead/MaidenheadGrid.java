package com.k1af.ft8af.maidenhead;
/**
 * Maidenhead grid processing. Includes latitude/longitude conversion and distance calculation.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;
import com.google.android.gms.maps.model.LatLng;

import java.util.List;

public class MaidenheadGrid {
    private static final String TAG = "MaidenheadGrid";
    private static final double EARTH_RADIUS = 6371393; // Mean radius in meters; not the equatorial radius, which is about 6378 km
    /**
     * Maidenhead subsquares (the third pair, letters a–x) divide each square into
     * 24 parts along each axis — not 18. The 6-character decode below must agree
     * with the {@link #getGridSquare} encoder, which steps by 2/24° of longitude
     * and 1/24° of latitude per subsquare. Dividing by 18 stretched every
     * subsquare offset by 24/18 = 1.33×, misplacing 6-character grids by up to
     * ~0.6° (~45 km) of longitude for the high subsquare letters (e.g. IO91wm
     * landed at +0.5° instead of the true −0.125°).
     */
    private static final float SUBSQUARES = 24f;

    /**
     * Calculate latitude/longitude from a 4-character or 6-character Maidenhead grid. Returns null if grid data is invalid. For 4-character grids, 'll' is appended to use the center position.
     *
     * @param grid Maidenhead grid data
     * @return LatLng latitude/longitude, or null if data is invalid
     */
    public static LatLng gridToLatLng(String grid) {
        if (grid==null) return null;
        if (grid.length()==0) return null;
        //Check if it conforms to Maidenhead grid rules
        if (grid.length() != 2&&grid.length() != 4 && grid.length() != 6) {
            return null;
        }
        if (grid.equalsIgnoreCase("RR73")) return null;
        if (grid.equalsIgnoreCase("RR")) return null;
        double x=0;
        double y=0;
        double z=0;
        //Latitude
        double lat=0;
        if (grid.length()==2){
            x=grid.toUpperCase().getBytes()[1]-'A'+0.5f;
        }else {
            x=grid.toUpperCase().getBytes()[1]-'A';
        }
        x*=10;

        if (grid.length()==4){
            y=grid.getBytes()[3]-'0'+0.5f;
        }else if (grid.length()==6){
            y=grid.getBytes()[3]-'0';
        }

        if (grid.length()==6){
            z=grid.toUpperCase().getBytes()[5]-'A'+0.5f;
            z=z*(1/SUBSQUARES);
        }
        lat=x+y+z-90;

        //Longitude
        x=0;
        y=0;
        z=0;
        double lng=0;
        if (grid.length()==2){
            x=grid.toUpperCase().getBytes()[0]-'A'+0.5;
        }else {
            x=grid.toUpperCase().getBytes()[0]-'A';
        }
        x*=20;
        if (grid.length()==4){
            y=grid.getBytes()[2]-'0'+0.5;
        }else if (grid.length()==6){
            y=grid.getBytes()[2]-'0';
        }
        y*=2;
        if (grid.length()==6){
            z=grid.toUpperCase().getBytes()[4]-'A'+0.5;
            z=z*(2/SUBSQUARES);
        }
        lng=x+y+z-180;
        if (lat>85) lat=85;//Prevent going out of bounds on the map
        if (lat<-85) lat=-85;//Prevent going out of bounds on the map


        return new LatLng(lat,lng);


    }






    public static LatLng[] gridToPolygon(String grid) {
        if (grid.length() != 2 && grid.length() != 4 && grid.length() != 6) {
            return null;
        }
        LatLng[] latLngs = new LatLng[4];

        //Latitude 1
        double x;
        double y = 0;
        double z = 0;
        double lat1;
        x = grid.toUpperCase().getBytes()[1] - 'A';
        x *= 10;
        if (grid.length() > 2) {
            y = grid.getBytes()[3] - '0';
        }
        if (grid.length() > 4) {
            z = grid.toUpperCase().getBytes()[5] - 'A';
            z = z * (1f / SUBSQUARES);
        }
        lat1 = x + y + z - 90;
        if (lat1<-85.0){
            lat1=-85.0;
        }
        if (lat1>85.0){
            lat1=85.0;
        }

        //Latitude 2
        x = 0;
        y = 0;
        z = 0;
        double lat2;
        if (grid.length() == 2) {
            x = grid.toUpperCase().getBytes()[1] - 'A' + 1;
        } else {
            x = grid.toUpperCase().getBytes()[1] - 'A';
        }
        x *= 10;
        if (grid.length() == 4) {
            y = grid.getBytes()[3] - '0' + 1;
        } else if (grid.length() == 6) {
            y = grid.getBytes()[3] - '0';
        }
        if (grid.length() == 6) {
            z = grid.toUpperCase().getBytes()[5] - 'A' + 1;
            z = z * (1f / SUBSQUARES);
        }
        lat2 = x + y + z - 90;
        if (lat2<-85.0){
            lat2=-85.0;
        }
        if (lat2>85.0){
            lat2=85.0;
        }


        //Longitude 1
        x=0;y=0;z=0;
        double lng1;
        x=grid.toUpperCase().getBytes()[0]-'A';
        x*=20;

        if (grid.length()>2){
            y=grid.getBytes()[2]-'0';
            y*=2;
        }
        if (grid.length()>4){
            z=grid.toUpperCase().getBytes()[4]-'A';
            z=z*2/SUBSQUARES;
        }
        lng1=x+y+z-180;

        //Longitude 2
        x=0;y=0;z=0;
        double lng2;
        if (grid.length()==2){
            x=grid.toUpperCase().getBytes()[0]-'A'+1;
        }else {
            x=grid.toUpperCase().getBytes()[0]-'A';
        }
        x*=20;
        if (grid.length()==4){
            y=grid.getBytes()[2]-'0'+1;
        }else if (grid.length()==6){
            y=grid.getBytes()[2]-'0';
        }
        y*=2;
        if (grid.length()==6){
            z=grid.toUpperCase().getBytes()[4]-'A'+1;
            z=z*2/SUBSQUARES;
        }
        lng2=x+y+z-180;

        latLngs[0] = new LatLng(lat1,lng1);
        latLngs[1] = new LatLng(lat1,lng2);
        latLngs[2] = new LatLng(lat2,lng2);
        latLngs[3] = new LatLng(lat2,lng1);

        return latLngs;


    }

    /**
     * This function calculates a 6-character Maidenhead grid from latitude/longitude.
     * Latitude/longitude use NMEA format. In other words, west longitude and south latitude are negative. They are specified as double type.
     *
     * @param location latitude/longitude
     * @return String Maidenhead grid string
     */
    public static String getGridSquare(LatLng location) {
        double tempNumber;//For intermediate calculation
        int index;//Determines the character to display
        double _long = location.longitude;
        double _lat = location.latitude;
        StringBuilder buff = new StringBuilder();

        /*
         *	Calculate the first pair of two characters
         */
        _long += 180;                    // Start from the middle of the Pacific
        tempNumber = _long / 20;            // Each major square is 20 degrees wide
        index = (int) tempNumber;            // Index for uppercase letter
        buff.append(String.valueOf((char) (index + 'A')));  // Set the first character
        _long = _long - (index * 20);            // Remainder for step 2

        _lat += 90;                    // Start from the South Pole, 180 degrees
        tempNumber = _lat / 10;                // Each major square is 10 degrees tall
        index = (int) tempNumber;            // Index for uppercase letter
        buff.append(String.valueOf((char) (index + 'A')));//Set the second character
        _lat = _lat - (index * 10);            // Remainder for step 2

        /*
         *	Now the second pair of two digits:
         */
        tempNumber = _long / 2;                // Remainder from step 1 divided by 2
        index = (int) tempNumber;            // Digit index
        buff.append(String.valueOf((char) (index + '0')));//Set the third character
        _long = _long - (index * 2);            // Remainder for step 3

        tempNumber = _lat;                // Remainder from step 1 divided by 1
        index = (int) tempNumber;            // Digit index
        buff.append(String.valueOf((char) (index + '0')));//Set the fourth character
        _lat = _lat - index;                // Remainder for step 3

        /*
         * Now the third pair of two lowercase characters:
         */
        tempNumber = _long / 0.083333;            // Remainder from step 2 divided by 0.083333
        index = (int) tempNumber;            // Index for lowercase letter
        buff.append(String.valueOf((char) (index + 'a')));//Set the fifth character

        tempNumber = _lat / 0.0416665;            // Remainder from step 2 divided by 0.0416665
        index = (int) tempNumber;            // Index for lowercase letter
        buff.append(String.valueOf((char) (index + 'a')));//Set the sixth character

        return buff.toString().substring(0, 4);
    }

    /**
     * Calculate distance between two latitude/longitude points
     *
     * @param latLng1 latitude/longitude
     * @param latLng2 latitude/longitude
     * @return distance in kilometers
     */
    public static double getDist(LatLng latLng1, LatLng latLng2) {
        return greatCircleDistanceKm(latLng1.latitude, latLng1.longitude,
                latLng2.latitude, latLng2.longitude);
    }

    /**
     * Great-circle distance in kilometres between two points given in decimal
     * degrees. Extracted as a plain static method (no Play-Services {@code LatLng})
     * so the {@code acos} domain clamp below is unit-testable without Robolectric.
     *
     * <p>The dot product {@code cos} of the angle AOB is mathematically in
     * {@code [-1, 1]}, but for two identical or sub-metre-apart points IEEE-754
     * rounding of {@code cos(b1)cos(b2)cos(a1-a2) + sin(b1)sin(b2)} can land one
     * ULP above {@code 1.0}. {@link Math#acos} of anything {@code > 1.0} returns
     * {@link Double#NaN}, which propagated as the literal "NaN km" / "NaN mi" in
     * the log and calling-list distance columns for two stations in the same
     * Maidenhead grid (their grid centres coincide), and silently dropped those
     * contacts from the min/max distance statistics (every {@code NaN} comparison
     * is false). Clamping {@code cos} to {@code [-1, 1]} yields the correct 0 km
     * for coincident points and leaves every valid distance unchanged.
     *
     * @param lat1 latitude of point A in degrees
     * @param lng1 longitude of point A in degrees
     * @param lat2 latitude of point B in degrees
     * @param lng2 longitude of point B in degrees
     * @return great-circle distance in kilometres
     */
    static double greatCircleDistanceKm(double lat1, double lng1, double lat2, double lng2) {
        double radiansAX = Math.toRadians(lng1); // A longitude in radians
        double radiansAY = Math.toRadians(lat1); // A latitude in radians
        double radiansBX = Math.toRadians(lng2); // B longitude in radians
        double radiansBY = Math.toRadians(lat2); // B latitude in radians

        // The formula part "cos(b1)*cos(b2)*cos(a1-a2)+sin(b1)*sin(b2)" gives the cos value of angle AOB
        double cos = Math.cos(radiansAY) * Math.cos(radiansBY) * Math.cos(radiansAX - radiansBX)
                + Math.sin(radiansAY) * Math.sin(radiansBY);
        // Clamp to acos's valid domain — rounding can push cos just past ±1.
        if (cos > 1.0) {
            cos = 1.0;
        } else if (cos < -1.0) {
            cos = -1.0;
        }
        double acos = Math.acos(cos); // Arccosine value
        return EARTH_RADIUS * acos / 1000; // Final result in km
    }

    /**
     * Calculate distance between two Maidenhead grids
     *
     * @param mGrid1 Maidenhead grid 1
     * @param mGrid2 Maidenhead grid 2
     * @return double distance between the two grids
     */
    public static double getDist(String mGrid1, String mGrid2) {
        LatLng latLng1 = gridToLatLng(mGrid1);
        LatLng latLng2 = gridToLatLng(mGrid2);
        if (latLng1 != null && latLng2 != null) {
            return getDist(latLng1, latLng2);
        } else {
            return 0;
        }
    }

    private static final double KM_TO_MILES = 0.621371;

    /**
     * Convert a distance in km to the user's preferred unit (miles or km).
     */
    public static double convertDist(double distKm) {
        return GeneralVariables.distanceInMiles ? distKm * KM_TO_MILES : distKm;
    }

    /**
     * Return the abbreviated unit label for the current distance setting.
     */
    public static String getDistUnitLabel() {
        return GeneralVariables.distanceInMiles ? "mi" : "km";
    }

    /**
     * Format a distance (given in km) as a rounded string with the user's preferred unit.
     */
    @SuppressLint("DefaultLocale")
    public static String formatDist(double distKm) {
        return String.format("%.0f %s", convertDist(distKm), getDistUnitLabel());
    }

    @SuppressLint("DefaultLocale")
    public static String getDistStr(String mGrid1, String mGrid2) {
        double dist = getDist(mGrid1, mGrid2);
        if (dist == 0) {
            return "";
        } else {
            return formatDist(dist);
        }
    }
    public static String getDistLatLngStr(LatLng latLng1,LatLng latLng2){
        return formatDist(getDist(latLng1,latLng2));
    }

    /**
     * Calculate distance between two grids, displaying in user's preferred unit
     *
     * @param mGrid1 grid
     * @param mGrid2 grid
     * @return distance
     */
    @SuppressLint("DefaultLocale")
    public static String getDistStrEN(String mGrid1, String mGrid2) {
        double dist = getDist(mGrid1, mGrid2);
        if (dist == 0) {
            return "";
        } else {
            return formatDist(dist);
        }
    }

    /**
     * Get the latitude/longitude of this device
     *
     * @param context context
     * @return latitude/longitude
     */
    public static LatLng getLocalLocation(Context context) {
        // Get location service
        String serviceName = Context.LOCATION_SERVICE;
        // Call getSystemService() to obtain the LocationManager object
        LocationManager locationManager = (LocationManager) context.getSystemService(serviceName);
        // Specify LocationManager's positioning method
        //String provider = LocationManager.GPS_PROVIDER;
        // Call getLastKnownLocation() to get the current location information

        List<String> providers = locationManager.getProviders(true);
        Location location = null;
        for (String s : providers) {
            @SuppressLint("MissingPermission") Location l = locationManager.getLastKnownLocation(s);
            if (l == null) {
                continue;
            }
            if (location == null || l.getAccuracy() < location.getAccuracy()) {
                // Found best last known location: %s", l);
                location = l;
            }
        }

        if (location != null) {
            return new LatLng(location.getLatitude(), location.getLongitude());
        } else {
            return null;
        }
    }


    /**
     * Get the Maidenhead grid data for this device. Requires location permission.
     *
     * @param context context
     * @return String 6-character Maidenhead grid.
     */
    public static String getMyMaidenheadGrid(Context context) {
        LatLng latLng = getLocalLocation(context);

        if (latLng != null) {
            return getGridSquare(latLng);
        } else {
            //ToastMessage.show("Unable to locate. Please confirm you have location permissions.");
            return "";
        }
    }

    /**
     * Check if the string is a valid Maidenhead grid. Returns false if not.
     *
     * @param s Maidenhead grid string
     * @return boolean whether it is a valid Maidenhead grid.
     */
    public static boolean checkMaidenhead(String s) {
        if (s.length() != 4 && s.length() != 6) {
            return false;
        } else {
            if (s.equals("RR73")) {
                return false;
            }
            return Character.isAlphabetic(s.charAt(0))
                    && Character.isAlphabetic(s.charAt(1))
                    && Character.isDigit(s.charAt(2))
                    && Character.isDigit(s.charAt(3));
        }
    }

}