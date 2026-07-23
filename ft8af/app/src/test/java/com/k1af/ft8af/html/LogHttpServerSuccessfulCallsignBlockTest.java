package com.k1af.ft8af.html;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure-logic coverage for {@link LogHttpServer#successfulCallsignBlock(List)}, the
 * "successfully contacted callsigns" cell block on the web-logbook debug page.
 *
 * <p>The block used to iterate the shared {@link com.k1af.ft8af.GeneralVariables#QSL_Callsign_list}
 * inline with {@code for (i < list.size()) list.get(i)}, re-reading the static field on every
 * {@code size()}/{@code get(i)}. A background DB reload swaps that list wholesale, so a swap to a
 * shorter list between the two reads threw {@link IndexOutOfBoundsException} mid-render. The block
 * now takes a single snapshot reference, and the field is a CopyOnWriteArrayList, so neither a
 * ref-swap nor a concurrent in-place add() can tear the iteration. These tests pin the exact HTML
 * shape and prove the render survives a concurrent writer. No Android types are touched, so no
 * Robolectric runner is needed.
 */
public class LogHttpServerSuccessfulCallsignBlockTest {

    @Test
    public void emptyList_rendersJustTheWrappingCell() {
        assertThat(LogHttpServer.successfulCallsignBlock(new ArrayList<>()))
                .isEqualTo("<tr><td class=\"default\" ></td></tr>\n");
    }

    @Test
    public void singleCallsign_appendsSeparator() {
        assertThat(LogHttpServer.successfulCallsignBlock(List.of("K1ABC")))
                .isEqualTo("<tr><td class=\"default\" >K1ABC,&nbsp;</td></tr>\n");
    }

    @Test
    public void tenthCallsign_startsANewRow() {
        List<String> calls = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            calls.add("C" + i);
        }
        String html = LogHttpServer.successfulCallsignBlock(calls);

        // The row break fires after the 10th entry (i + 1 == 10); since that's the last entry the
        // block closes with the (pre-existing) trailing empty row. Ten separators, one per call.
        assertThat(countOccurrences(html, "</td></tr><tr><td class=\"default\" >\n")).isEqualTo(1);
        assertThat(html).contains("C10,&nbsp;");
        assertThat(html).endsWith("</td></tr><tr><td class=\"default\" >\n</td></tr>\n");
        assertThat(countOccurrences(html, "&nbsp;")).isEqualTo(10);
    }

    @Test
    public void wrapsEveryTenCallsigns() {
        List<String> calls = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            calls.add("C" + i);
        }
        // Row breaks after the 10th and 20th entries only (not the 25th, mid-row).
        assertThat(countOccurrences(
                LogHttpServer.successfulCallsignBlock(calls),
                "</td></tr><tr><td class=\"default\" >\n")).isEqualTo(2);
    }

    /**
     * The regression: a writer mutating the same CopyOnWriteArrayList while the block renders it
     * must never throw and must always produce well-formed HTML. Run against a plain ArrayList
     * this loop reliably throws; against the copy-on-write list production now uses, it can't.
     */
    @Test
    public void concurrentInPlaceAdd_neverThrows() throws InterruptedException {
        List<String> shared = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 500; i++) {
            shared.add("SEED" + i);
        }

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            // Bound the writes and yield between them: CopyOnWriteArrayList copies
            // its entire backing array on every add(), so an unbounded tight add
            // loop balloons memory (and render time, since each render walks the
            // whole list) and makes the test slow/flaky. A few thousand bounded
            // adds still overlap the reader's 2000 renders and exercise concurrent
            // mutation.
            int n = 0;
            while (!stop.get() && n < 5000) {
                shared.add("NEW" + (n++));
                Thread.yield();
            }
        });
        writer.start();

        try {
            for (int r = 0; r < 2000; r++) {
                String html = LogHttpServer.successfulCallsignBlock(shared);
                // Well-formed: opens and closes the wrapping cell every time.
                assertThat(html).startsWith("<tr><td class=\"default\" >");
                assertThat(html).endsWith("</td></tr>\n");
            }
        } catch (Throwable t) {
            failure.set(t);
        } finally {
            stop.set(true);
            writer.join();
        }

        assertThat(failure.get()).isNull();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
