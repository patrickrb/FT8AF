package com.k1af.ft8af.flex;

//Timestamp type
//Timestamp has two parts: fractional and integer. Integer part has second resolution, 32-bit, mainly for UTC or GPS time.
//Fractional part has three types: sample-count (minimum resolution is sampling period), real-time (minimum unit is ps), and free-running count. The first two can be directly added to the integer part; the third cannot guarantee a constant relationship. The first two can cover a time range of years.
//Fractional timestamp is 64-bit and can be used without the integer part.
//All timestamps reference a single sample data point.
public enum VitaTSI {
    TSI_NONE,//No Integer-seconds Timestamp field included
    TSI_UTC,//Coordinated Universal Time(UTC)
    TSI_GPS,//GPS time
    TSI_OTHER//Other
}
