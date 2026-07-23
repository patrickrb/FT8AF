package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exercise {@link MessageHashMap}'s callsign-hash bookkeeping. The class
 * extends HashMap and adds three guards on top of {@code put}: it filters the
 * reserved meta-callsigns CQ/QRZ/DE, it rejects entries whose callsign is a
 * hash placeholder like {@code <...>}, and it ignores hash code 0.
 */
public class MessageHashMapTest {

    @Test
    public void addHash_storesCallsignAndIsLookupable() {
        MessageHashMap map = new MessageHashMap();
        map.addHash(0xABCDL, "K1ABC");
        assertThat(map.checkHash(0xABCDL)).isTrue();
        assertThat(map.get(0xABCDL)).isEqualTo("K1ABC");
    }

    @Test
    public void addHash_skipsReservedCallsigns() {
        MessageHashMap map = new MessageHashMap();
        map.addHash(1L, "CQ");
        map.addHash(2L, "QRZ");
        map.addHash(3L, "DE");
        assertThat(map.checkHash(1L)).isFalse();
        assertThat(map.checkHash(2L)).isFalse();
        assertThat(map.checkHash(3L)).isFalse();
        assertThat(map).isEmpty();
    }

    @Test
    public void addHash_rejectsZeroHashCode() {
        // Hash 0 is the sentinel for "no hash known yet"; never store it.
        MessageHashMap map = new MessageHashMap();
        map.addHash(0L, "W1AW");
        assertThat(map).isEmpty();
    }

    @Test
    public void addHash_rejectsPlaceholderCallsign() {
        // Callsigns rendered as <...> are the unresolved-placeholder form that
        // getCallsign() emits; never let one round-trip back into the map.
        MessageHashMap map = new MessageHashMap();
        map.addHash(99L, "<...>");
        assertThat(map.checkHash(99L)).isFalse();
    }

    @Test
    public void addHash_isIdempotentForSameKey() {
        MessageHashMap map = new MessageHashMap();
        map.addHash(7L, "VE3XYZ");
        map.addHash(7L, "VE3XYZ");
        assertThat(map).hasSize(1);
    }

    @Test
    public void getCallsign_returnsAngleWrappedMatch() {
        MessageHashMap map = new MessageHashMap();
        map.addHash(0x10L, "K1ABC");
        // getCallsign walks the array in order, returning the first match.
        String result = map.getCallsign(new long[]{0x99L, 0x10L, 0x77L});
        assertThat(result).isEqualTo("<K1ABC>");
    }

    @Test
    public void getCallsign_returnsPlaceholderWhenNoMatch() {
        MessageHashMap map = new MessageHashMap();
        map.addHash(0x10L, "K1ABC");
        assertThat(map.getCallsign(new long[]{1L, 2L, 3L})).isEqualTo("<...>");
    }

    @Test
    public void addHash_emptyCallsignWithNonZeroHash_isIgnoredNotCrash() {
        // Regression: a DXpedition (Fox/Hound) decode leaves callsignFrom = ""
        // yet carries a non-zero call-from hash (derived from the invited call),
        // so the zero-hash short-circuit does NOT fire and addHash reached
        // "".charAt(0) -> StringIndexOutOfBoundsException. That threw inside the
        // decode loop's try/catch, silently dropping the whole Fox message.
        MessageHashMap map = new MessageHashMap();
        map.addHash(0xABCDL, "");
        // No crash, and an empty callsign is never stored.
        assertThat(map.checkHash(0xABCDL)).isFalse();
        assertThat(map).isEmpty();
    }

    @Test
    public void addHash_nullCallsign_isIgnoredNotCrash() {
        // Ft8Message.extraInfo/callsign defaults are null; a null callsign must
        // not NPE at the reserved-callsign equals() check either.
        MessageHashMap map = new MessageHashMap();
        map.addHash(0x1234L, null);
        assertThat(map.checkHash(0x1234L)).isFalse();
        assertThat(map).isEmpty();
    }

    @Test
    public void snapshotEntries_returnsAllCurrentEntries() {
        MessageHashMap map = new MessageHashMap();
        map.addHash(0x10L, "K1ABC");
        map.addHash(0x20L, "VE3XYZ");
        map.addHash(0x30L, "JA1AA");

        List<Map.Entry<Long, String>> snap = map.snapshotEntries();
        assertThat(snap).hasSize(3);
        // Every stored (hash, callsign) pair is present in the snapshot.
        for (Map.Entry<Long, String> e : snap) {
            assertThat(map.get(e.getKey())).isEqualTo(e.getValue());
        }
    }

    @Test
    public void snapshotEntries_isDecoupledFromLaterMutation() {
        // The snapshot is a point-in-time copy: mutating the live map afterwards
        // must neither change the snapshot's contents nor throw while it is
        // iterated. This is the property the web-logger's hash-table page relies
        // on to render safely off the decode thread.
        MessageHashMap map = new MessageHashMap();
        map.addHash(1L, "K1ABC");
        map.addHash(2L, "W1AW");

        List<Map.Entry<Long, String>> snap = map.snapshotEntries();
        assertThat(snap).hasSize(2);

        // Structurally modify the live map via the production writer path
        // (addHash with fresh keys) while iterating the snapshot; a live
        // entrySet() would throw ConcurrentModificationException here.
        long freshKey = 100L;
        for (Map.Entry<Long, String> ignored : snap) {
            map.addHash(freshKey, "N0CALL" + freshKey);
            freshKey++;
        }
        // The live map grew, but the point-in-time snapshot is unchanged.
        assertThat(snap).hasSize(2);
    }

    @Test
    public void liveEntrySetIteration_throwsOnConcurrentStructuralModification() {
        // Documents the hazard snapshotEntries() exists to remove: iterating the
        // live map while another writer put()s (as addHash does on the decode/DB/
        // TX threads) is a fail-fast ConcurrentModificationException. Reproduced
        // deterministically single-threaded via a structural modification during
        // iteration.
        MessageHashMap map = new MessageHashMap();
        for (long h = 1; h <= 50; h++) {
            map.addHash(h, "K" + h);
        }
        try {
            for (Map.Entry<Long, String> e : map.entrySet()) {
                map.put(1000L + e.getKey(), "X");
            }
            fail("expected ConcurrentModificationException from live entrySet iteration");
        } catch (ConcurrentModificationException expected) {
            // exactly the failure mode the web logger hit off-thread
        }
    }

    @Test
    public void snapshotEntries_survivesConcurrentWrites() throws Exception {
        // Stress the real cross-thread scenario: a writer thread hammers addHash
        // (the sole production writer path — decode/DB/TX side) while this thread
        // repeatedly snapshots and iterates (web-logger side). addHash and
        // snapshotEntries both hold the map's monitor, so the reader must never
        // observe a ConcurrentModificationException or a corrupted iteration.
        // Iterating the live entrySet() here instead would throw within a handful
        // of rounds as the writer's put()s resize the table.
        MessageHashMap map = new MessageHashMap();
        AtomicBoolean writerDone = new AtomicBoolean(false);
        AtomicReference<Throwable> writerError = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                // Fresh keys keep the table growing/resizing (the structural
                // change that trips a live iterator). Bounded so the map size and
                // runtime stay modest.
                for (long h = 1; h <= 20000; h++) {
                    map.addHash(h, "K" + h);
                }
            } catch (Throwable t) {
                writerError.set(t);
            } finally {
                writerDone.set(true);
            }
        });
        writer.start();

        try {
            while (!writerDone.get()) {
                for (Map.Entry<Long, String> entry : map.snapshotEntries()) {
                    // touch both accessors exactly like showCallsignHash() does
                    entry.getKey();
                    entry.getValue();
                }
            }
        } finally {
            writer.join();
        }
        assertThat(writerError.get()).isNull();
    }
}
