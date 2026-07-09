package com.k1af.ft8af.log;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit coverage for {@link HashTable}, the two-key table that backs SWL QSO
 * deduplication. The class was previously a stub (contains() == false, put() ==
 * no-op) which silently disabled dedup; these tests lock in that it now stores
 * and reports entries while still tolerating null keys handed in on the decode
 * thread. Pure JVM — no Android types involved.
 */
public class HashTableTest {

    @Test
    public void putThenContains_isTrueForSameKeyPair() {
        HashTable table = new HashTable();
        assertThat(table.contains("W1AW", "K1ABC")).isFalse();
        table.put("W1AW", "K1ABC", true);
        assertThat(table.contains("W1AW", "K1ABC")).isTrue();
        assertThat(table.get("W1AW", "K1ABC")).isEqualTo(true);
        assertThat(table.size()).isEqualTo(1);
    }

    @Test
    public void keysAreDirectional() {
        HashTable table = new HashTable();
        table.put("W1AW", "K1ABC", true);
        // Reversed row/column is a different contact and must not read as present.
        assertThat(table.contains("K1ABC", "W1AW")).isFalse();
    }

    @Test
    public void nullKeys_areToleratedNotThrown() {
        HashTable table = new HashTable();
        // guava's backing table rejects null keys; SWL callsigns can be absent and
        // the detector runs on the decode thread, so null lookups/puts are treated
        // as "not present"/no-op instead of throwing.
        assertThat(table.contains(null, "K1ABC")).isFalse();
        assertThat(table.contains("W1AW", null)).isFalse();
        table.put(null, "K1ABC", true);   // must not throw
        table.put("W1AW", null, true);     // must not throw
        table.put("W1AW", "K1ABC", null);  // null value: no-op, must not throw
        assertThat(table.size()).isEqualTo(0);
        assertThat(table.get(null, null)).isNull();
        assertThat(table.remove(null, "K1ABC")).isNull();
    }

    @Test
    public void nullKeyRowAndColumn_returnEmptyMapNotThrow() {
        HashTable table = new HashTable();
        table.put("W1AW", "K1ABC", true);
        // row(null)/column(null) would throw NPE if forwarded straight to guava;
        // to match the documented null-tolerance they report "nothing present".
        assertThat(table.row(null)).isEmpty();
        assertThat(table.column(null)).isEmpty();
        // Non-null keys still resolve normally.
        assertThat(table.row("W1AW")).containsKey("K1ABC");
        assertThat(table.column("K1ABC")).containsKey("W1AW");
    }
}
