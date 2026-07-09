package com.k1af.ft8af.flex;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.flex.FlexRadio.FlexResponse;

import org.junit.Test;

/**
 * Pure-JVM coverage for {@link FlexRadio.FlexResponse}'s {@code 'M'} (message)
 * branch — a crash fix for a truncated/garbled FlexRadio message frame.
 *
 * <p>{@code FlexResponse} is constructed on the FlexRadio TCP read thread
 * ({@link RadioTcpClient}) for every line the radio sends. That read loop
 * catches only {@code SocketException}/{@code IOException}, so any
 * {@link RuntimeException} thrown while parsing a line escapes the loop, kills
 * the read thread and crashes the whole app.
 *
 * <p>The {@code 'M'} branch did {@code head.substring(2)} inside a
 * {@code try/catch (NumberFormatException)}. The catch does not cover
 * {@link StringIndexOutOfBoundsException}, so a message frame whose header
 * token (the part before the first {@code '|'}) is shorter than two characters
 * — a bare {@code "M"} or {@code "M|text"} — threw and crashed. The sibling
 * {@code 'C'} branch already length-guards before indexing; this pins the same
 * guard on {@code 'M'} while keeping the parse of well-formed frames unchanged.
 *
 * <p>{@code android.util.Log} returns defaults under plain JUnit
 * ({@code returnDefaultValues = true}), so no Robolectric runner is needed.
 */
public class FlexResponseMessageTest {

    @Test
    public void bareMHeader_doesNotCrash() {
        // Regression: head == "M" -> substring(2) threw
        // StringIndexOutOfBoundsException on the read thread.
        FlexResponse r = new FlexResponse("M");
        assertThat(r.responseStyle).isEqualTo(FlexResponseStyle.MESSAGE);
        assertThat(r.message_num).isEqualTo(0);
    }

    @Test
    public void shortHeaderWithBody_doesNotCrash() {
        // head is the part before the first '|'; "M|text" -> head "M" -> the
        // old substring(2) threw before the '|' body was even considered.
        FlexResponse r = new FlexResponse("M|no message number here");
        assertThat(r.responseStyle).isEqualTo(FlexResponseStyle.MESSAGE);
        assertThat(r.message_num).isEqualTo(0);
    }

    @Test
    public void twoCharHeader_leavesDefaultWithoutParsing() {
        // head "M1" -> substring(2) == "" -> the guard skips the parse entirely
        // rather than driving control flow through NumberFormatException.
        FlexResponse r = new FlexResponse("M1|only one hex digit");
        assertThat(r.responseStyle).isEqualTo(FlexResponseStyle.MESSAGE);
        assertThat(r.message_num).isEqualTo(0);
    }

    @Test
    public void wellFormedMessage_stillParses() {
        // Real frame: 'M' + 32-bit hex message id + '|' + text. The guard must
        // not change parsing of valid frames.
        FlexResponse r = new FlexResponse("M10000001|client connected");
        assertThat(r.responseStyle).isEqualTo(FlexResponseStyle.MESSAGE);
        // Existing behaviour parses head.substring(2) as hex ("0000001" -> 1).
        assertThat(r.message_num).isEqualTo(0x0000001);
    }

    @Test
    public void nonNumericMessageNumber_isSwallowed() {
        // A long-enough header that is not valid hex must still be tolerated
        // (NumberFormatException path), leaving message_num at its default.
        FlexResponse r = new FlexResponse("MZZ|garbled");
        assertThat(r.responseStyle).isEqualTo(FlexResponseStyle.MESSAGE);
        assertThat(r.message_num).isEqualTo(0);
    }
}
