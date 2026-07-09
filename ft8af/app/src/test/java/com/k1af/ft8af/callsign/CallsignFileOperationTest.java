package com.k1af.ft8af.callsign;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Pure-logic coverage for {@link CallsignFileOperation}'s static parsers:
 *   - getCallsigns: splits a comma-separated cty.dat prefix list, stripping the
 *     "(cqzone)" and "[ituzone]" annotations cty.dat attaches to individual
 *     prefixes, and trims whitespace.
 *   - getLinesFromInputStream: byte-reads a stream and splits on a delimiter.
 *
 * Both touch no Android types, so plain JUnit is sufficient.
 */
public class CallsignFileOperationTest {

    @Test
    public void getCallsigns_splitsCommaSeparatedList() {
        Set<String> calls = CallsignFileOperation.getCallsigns("K,W,N,AA");
        assertThat(calls).containsExactly("K", "W", "N", "AA");
    }

    @Test
    public void getCallsigns_stripsParenCqZoneAnnotation() {
        // cty.dat uses "(n)" to override a prefix's CQ zone; the prefix keeps only
        // the text before '('.
        Set<String> calls = CallsignFileOperation.getCallsigns("VE(5),VE7");
        assertThat(calls).contains("VE");
        assertThat(calls).contains("VE7");
    }

    @Test
    public void getCallsigns_stripsBracketItuZoneAnnotation() {
        // "[n]" overrides ITU zone; everything from '[' on is dropped.
        Set<String> calls = CallsignFileOperation.getCallsigns("KH6[61],KL7");
        assertThat(calls).contains("KH6");
        assertThat(calls).contains("KL7");
    }

    @Test
    public void getCallsigns_stripsParenWhenBeforeBracket() {
        // A prefix can carry both annotations: "(cq)" is removed first (substring
        // up to '('), so the result is just the bare prefix.
        Set<String> calls = CallsignFileOperation.getCallsigns("VP8(13)[73]");
        assertThat(calls).contains("VP8");
    }

    @Test
    public void getCallsigns_closingParenWithoutOpening_doesNotThrowAndKeepsSiblings() {
        // A malformed cty.dat token that carries a ')' but no '(' used to crash:
        // the guard tested for ')' yet the cut indexed '(', so indexOf("(") == -1
        // and substring(0, -1) threw StringIndexOutOfBoundsException. Because
        // getCallsigns runs inside CallsignDatabase's per-country try/catch, that
        // exception silently dropped *every* callsign for the affected country.
        // A stray ')' must not take the rest of the list down with it.
        Set<String> calls = CallsignFileOperation.getCallsigns("BAD),VE7");
        assertThat(calls).contains("VE7");
    }

    @Test
    public void getCallsigns_removesNewlinesAndTrims() {
        // Leading newline is stripped globally; per-token whitespace is trimmed.
        Set<String> calls = CallsignFileOperation.getCallsigns("\n K , W ");
        assertThat(calls).containsExactly("K", "W");
    }

    @Test
    public void getCallsigns_deduplicatesRepeatedPrefixes() {
        // Result is a Set, so duplicates collapse.
        Set<String> calls = CallsignFileOperation.getCallsigns("K,K,W");
        assertThat(calls).containsExactly("K", "W");
    }

    @Test
    public void getLinesFromInputStream_splitsOnSemicolon() {
        InputStream in = new ByteArrayInputStream(
                "rec1;rec2;rec3".getBytes(StandardCharsets.UTF_8));
        String[] lines = CallsignFileOperation.getLinesFromInputStream(in, ";");
        assertThat(lines).asList().containsExactly("rec1", "rec2", "rec3").inOrder();
    }

    @Test
    public void getLinesFromInputStream_singleRecordNoDelimiter() {
        InputStream in = new ByteArrayInputStream("only".getBytes(StandardCharsets.UTF_8));
        String[] lines = CallsignFileOperation.getLinesFromInputStream(in, ";");
        assertThat(lines).asList().containsExactly("only");
    }
}
