package com.k1af.ft8af.log;

import com.k1af.ft8af.log.ThirdPartyService.StationProfile;

import java.util.List;
import java.util.Map;

/**
 * Pure find-or-create decision logic for the per-location Wavelog station feature
 * (issue #437). Given a target {@link LocationSignature} (as its canonical
 * string), a persisted {@code signature -> station_profile_id} cache, and the
 * list of station profiles that currently exist on the server, it decides whether
 * an existing profile already covers this location (return its id) or a new one
 * must be created.
 *
 * <p>Matching is by the exact canonical signature string, which is the dedup
 * contract: how coarse or fine "the same location" is (grid-only vs.
 * grid+county+city+…) is governed entirely by which {@link LocationSignature.Field}s
 * the signature was built with, not by this resolver. That keeps this class a
 * trivially testable pure function.
 *
 * <p>The cached id is validated against {@code existingProfiles}: a cache entry
 * whose profile no longer exists on the server (deleted station, restored from a
 * stale backup, wrong server) is treated as a miss so the caller recreates it
 * rather than logging against a dangling id.
 *
 * <p><b>Dark feature.</b> No live code calls this yet.
 */
public final class LocationStationResolver {

    private LocationStationResolver() {
    }

    /** Outcome of {@link #resolve}. Exactly one of the two states holds. */
    public static final class Resolution {
        /** The existing station_profile_id to log against, or null when a create is needed. */
        public final String stationId;
        /** True when no existing profile matched and a new station must be created. */
        public final boolean needsCreate;

        private Resolution(String stationId, boolean needsCreate) {
            this.stationId = stationId;
            this.needsCreate = needsCreate;
        }

        static Resolution matched(String stationId) {
            return new Resolution(stationId, false);
        }

        static Resolution create() {
            return new Resolution(null, true);
        }

        @Override
        public String toString() {
            return needsCreate ? "Resolution{needsCreate}" : "Resolution{stationId=" + stationId + "}";
        }
    }

    /**
     * Decide whether {@code signature} maps to an existing station profile.
     *
     * @param signature       canonical signature string (from {@link LocationSignature#signature()});
     *                        null is treated as the empty signature.
     * @param signatureCache  persisted {@code signature -> station_profile_id} map; null == empty.
     * @param existingProfiles station profiles currently on the server (from
     *                        {@link ThirdPartyService#FetchCloudlogStations}); null == empty.
     * @return a {@link Resolution}: an existing id when the cache maps this signature to a
     *         profile that still exists on the server, otherwise a needs-create result.
     */
    public static Resolution resolve(String signature,
                                     Map<String, String> signatureCache,
                                     List<StationProfile> existingProfiles) {
        String key = signature == null ? "" : signature;
        if (signatureCache == null) {
            return Resolution.create();
        }
        String cachedId = signatureCache.get(key);
        if (cachedId == null || cachedId.isEmpty()) {
            return Resolution.create();
        }
        if (profileExists(cachedId, existingProfiles)) {
            return Resolution.matched(cachedId);
        }
        // Cache points at a profile the server no longer has — recreate.
        return Resolution.create();
    }

    private static boolean profileExists(String stationId, List<StationProfile> profiles) {
        if (profiles == null) {
            return false;
        }
        for (StationProfile p : profiles) {
            if (p != null && stationId.equals(p.stationId)) {
                return true;
            }
        }
        return false;
    }
}
