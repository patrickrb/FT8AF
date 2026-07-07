package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.log.ThirdPartyService.StationProfile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure unit tests for {@link LocationStationResolver#resolve}: the find-or-create
 * decision for the per-location Wavelog station feature (issue #437). No Android
 * types, so a bare JUnit runner suffices.
 */
public class LocationStationResolverTest {

    private static StationProfile profile(String id) {
        return new StationProfile(id, "Home", "K1AF", "FN42");
    }

    private static List<StationProfile> profiles(String... ids) {
        List<StationProfile> list = new ArrayList<>();
        for (String id : ids) {
            list.add(profile(id));
        }
        return list;
    }

    @Test
    public void matchFound_whenCacheHitAndProfileExists() {
        Map<String, String> cache = new HashMap<>();
        cache.put("GRID=FN42", "7");
        LocationStationResolver.Resolution r =
                LocationStationResolver.resolve("GRID=FN42", cache, profiles("7", "8"));
        assertThat(r.needsCreate).isFalse();
        assertThat(r.stationId).isEqualTo("7");
    }

    @Test
    public void needsCreate_whenCacheMiss() {
        Map<String, String> cache = new HashMap<>();
        cache.put("GRID=EN61", "3");
        LocationStationResolver.Resolution r =
                LocationStationResolver.resolve("GRID=FN42", cache, profiles("3"));
        assertThat(r.needsCreate).isTrue();
        assertThat(r.stationId).isNull();
    }

    @Test
    public void needsCreate_whenCachedProfileNoLongerExistsOnServer() {
        Map<String, String> cache = new HashMap<>();
        cache.put("GRID=FN42", "99");
        // Server only has 1 and 2 — cached id 99 is stale.
        LocationStationResolver.Resolution r =
                LocationStationResolver.resolve("GRID=FN42", cache, profiles("1", "2"));
        assertThat(r.needsCreate).isTrue();
    }

    @Test
    public void needsCreate_whenCacheNull() {
        LocationStationResolver.Resolution r =
                LocationStationResolver.resolve("GRID=FN42", null, profiles("1"));
        assertThat(r.needsCreate).isTrue();
    }

    @Test
    public void needsCreate_whenExistingProfilesNull() {
        Map<String, String> cache = new HashMap<>();
        cache.put("GRID=FN42", "1");
        LocationStationResolver.Resolution r =
                LocationStationResolver.resolve("GRID=FN42", cache, null);
        assertThat(r.needsCreate).isTrue();
    }

    @Test
    public void nullSignature_treatedAsEmptyKey() {
        Map<String, String> cache = new HashMap<>();
        cache.put("", "5");
        LocationStationResolver.Resolution r =
                LocationStationResolver.resolve(null, cache, profiles("5"));
        assertThat(r.needsCreate).isFalse();
        assertThat(r.stationId).isEqualTo("5");
    }

    @Test
    public void potaRefDifference_resolvesIndependently() {
        // Two POTA-bearing signatures for the same grid: only one is cached.
        String sigA = LocationSignature.builder()
                .gridsquare("FN42")
                .enabledFields(EnumSet.of(LocationSignature.Field.GRIDSQUARE))
                .potaRef("K-1234").build().signature();
        String sigB = LocationSignature.builder()
                .gridsquare("FN42")
                .enabledFields(EnumSet.of(LocationSignature.Field.GRIDSQUARE))
                .potaRef("K-5678").build().signature();

        Map<String, String> cache = new HashMap<>();
        cache.put(sigA, "10");
        List<StationProfile> existing = profiles("10");

        assertThat(LocationStationResolver.resolve(sigA, cache, existing).stationId)
                .isEqualTo("10");
        // Same grid, different park -> not the cached one -> needs create.
        assertThat(LocationStationResolver.resolve(sigB, cache, existing).needsCreate)
                .isTrue();
    }

    @Test
    public void fieldSubset_collapsesTwoLocationsToSameCacheHit() {
        // Grid-only granularity: two cities in the same grid share a signature and
        // therefore the same cached station profile.
        String sigCityA = LocationSignature.builder()
                .gridsquare("FN42").city("Albany")
                .enabledFields(EnumSet.of(LocationSignature.Field.GRIDSQUARE))
                .build().signature();
        String sigCityB = LocationSignature.builder()
                .gridsquare("FN42").city("Troy")
                .enabledFields(EnumSet.of(LocationSignature.Field.GRIDSQUARE))
                .build().signature();
        assertThat(sigCityA).isEqualTo(sigCityB);

        Map<String, String> cache = new HashMap<>();
        cache.put(sigCityA, "42");
        assertThat(LocationStationResolver.resolve(sigCityB, cache, profiles("42")).stationId)
                .isEqualTo("42");
    }

    @Test
    public void emptyCachedId_treatedAsMiss() {
        Map<String, String> cache = new HashMap<>();
        cache.put("GRID=FN42", "");
        LocationStationResolver.Resolution r =
                LocationStationResolver.resolve("GRID=FN42", cache, profiles("1"));
        assertThat(r.needsCreate).isTrue();
    }
}
