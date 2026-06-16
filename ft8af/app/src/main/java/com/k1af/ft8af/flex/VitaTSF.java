package com.k1af.ft8af.flex;

//Fractional timestamp type
//There are three main types of fractional parts:
// Sample-count, with sampling period as minimum resolution;
// Real-time, with picoseconds as minimum unit;
// Free-running count, accumulated from an arbitrarily chosen time.
// The first two can be directly added to the integer part,
// while the third cannot guarantee a constant relationship with the integer part. The first two can cover a time range of years.
// The fractional timestamp is 64-bit and can be used without the integer part.
// All timestamps reference a single sample data point (reference-point).
public enum VitaTSF {
    TSF_NONE,//No Fractional-seconds Timestamp field included
    TSF_SAMPLE_COUNT,//Sample Count Timestamp
    TSF_REALTIME,//Real Time (Picoseconds) Timestamp
    TSF_FREERUN,//Free Running Count Timestamp
}
