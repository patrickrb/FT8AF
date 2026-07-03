package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tests for the synchronous config helpers backing settings backup/restore
 * (issue #357): writeConfigSync's transactional upsert and getAllConfigSync's
 * deterministic (KeyName-ordered) read-back. Uses a Robolectric in-memory
 * database (name=null) so no on-disk state is touched.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprConfigSyncTest {

    private DatabaseOpr opr;

    @Before
    public void setUp() {
        opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null, 18);
    }

    @After
    public void tearDown() {
        opr.close();
    }

    @Test
    public void writeThenRead_roundTripsAllEntries() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("callsign", "K1AF");
        config.put("grid", "FN42");
        opr.writeConfigSync(config);

        Map<String, String> read = opr.getAllConfigSync();
        assertThat(read).containsAtLeastEntriesIn(config);
    }

    @Test
    public void read_returnsKeysInKeyNameOrder() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("zulu", "1");
        config.put("alpha", "2");
        config.put("mike", "3");
        opr.writeConfigSync(config);

        Map<String, String> read = opr.getAllConfigSync();
        // The map promises insertion order == query order; the query orders by KeyName.
        java.util.List<String> keys = new java.util.ArrayList<>(read.keySet());
        java.util.List<String> sorted = new java.util.ArrayList<>(keys);
        java.util.Collections.sort(sorted);
        assertThat(keys).isEqualTo(sorted);
    }

    @Test
    public void write_upsertsExistingKeysInsteadOfDuplicating() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("callsign", "K1AF");
        opr.writeConfigSync(first);

        Map<String, String> second = new LinkedHashMap<>();
        second.put("callsign", "KS3CKC");
        opr.writeConfigSync(second);

        Map<String, String> read = opr.getAllConfigSync();
        assertThat(read).containsEntry("callsign", "KS3CKC");
        assertThat(java.util.Collections.frequency(
                new java.util.ArrayList<>(read.keySet()), "callsign")).isEqualTo(1);
    }

    @Test
    public void write_treatsNullValueAsEmptyString() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("cloudlogApiKey", null);
        opr.writeConfigSync(config);

        assertThat(opr.getAllConfigSync()).containsEntry("cloudlogApiKey", "");
    }
}
