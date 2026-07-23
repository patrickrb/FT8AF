package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

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
}
