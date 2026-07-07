package com.k1af.ft8af.log;

import java.util.Objects;

/**
 * One persisted {@code signature -> station_profile_id} mapping row for the
 * per-location Wavelog station cache (issue #437). Plain immutable value type; the
 * SQLite-backed DAO lives in {@code DatabaseOpr}.
 */
public final class LocationStationCacheEntry {
    /** The canonical {@link LocationSignature#signature()} string (the dedup key). */
    public final String signature;
    /** The Wavelog {@code station_profile_id} that covers this location. */
    public final String stationProfileId;

    public LocationStationCacheEntry(String signature, String stationProfileId) {
        this.signature = signature;
        this.stationProfileId = stationProfileId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocationStationCacheEntry)) return false;
        LocationStationCacheEntry that = (LocationStationCacheEntry) o;
        return Objects.equals(signature, that.signature)
                && Objects.equals(stationProfileId, that.stationProfileId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(signature, stationProfileId);
    }

    @Override
    public String toString() {
        return "LocationStationCacheEntry{signature=" + signature
                + ", stationProfileId=" + stationProfileId + "}";
    }
}
