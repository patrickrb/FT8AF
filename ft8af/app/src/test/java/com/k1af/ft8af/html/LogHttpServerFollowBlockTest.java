package com.k1af.ft8af.html;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Pure-logic coverage for {@link LogHttpServer#followCallsignBlock(List)}, the
 * followed-callsign cell renderer on the web-logbook config page.
 *
 * <p>The block used to be an inline {@code for (i < followCallsign.size())
 * followCallsign.get(i)} index scan run on a NanoHTTPD worker thread, while the
 * decode/DB threads add to and the UI thread clears the shared
 * {@code GeneralVariables.followCallsign} list. That {@code size()}-then-{@code
 * get(i)} pattern races a concurrent clear straight into an
 * {@link IndexOutOfBoundsException}, and a plain {@code ArrayList} has no
 * happens-before against the writers at all. Extracting the loop and iterating
 * with a for-each lets a {@link CopyOnWriteArrayList} hand back a stable
 * snapshot. These tests pin the exact HTML (including the ten-per-row break) and
 * lock in that a concurrent add/clear during rendering never throws. No Android
 * types are touched, so no Robolectric runner is needed.
 */
public class LogHttpServerFollowBlockTest {

    @Test
    public void emptyList_rendersNothing() {
        assertThat(LogHttpServer.followCallsignBlock(new ArrayList<>())).isEmpty();
    }

    @Test
    public void singleCallsign_rendersOneCell() {
        List<String> calls = new ArrayList<>();
        calls.add("K1ABC");
        assertThat(LogHttpServer.followCallsignBlock(calls)).isEqualTo("K1ABC,&nbsp;");
    }

    @Test
    public void tenCallsigns_breakRowAfterTheTenth() {
        List<String> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add("C" + i);
        }
        String html = LogHttpServer.followCallsignBlock(calls);
        // The row break fires once, immediately after the tenth cell.
        assertThat(html).endsWith(",&nbsp;</td></tr><tr><td class=\"default\" >\n");
        assertThat(countOccurrences(html, "</td></tr><tr><td class=\"default\" >\n")).isEqualTo(1);
    }

    @Test
    public void elevenCallsigns_startNewRowForTheEleventh() {
        List<String> calls = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            calls.add("C" + i);
        }
        String html = LogHttpServer.followCallsignBlock(calls);
        // Exactly one break (after ten), and the eleventh trails it.
        assertThat(countOccurrences(html, "</td></tr><tr><td class=\"default\" >\n")).isEqualTo(1);
        assertThat(html).endsWith("C10,&nbsp;");
    }

    @Test
    public void twentyCallsigns_breakTwice() {
        List<String> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add("C" + i);
        }
        String html = LogHttpServer.followCallsignBlock(calls);
        assertThat(countOccurrences(html, "</td></tr><tr><td class=\"default\" >\n")).isEqualTo(2);
    }

    @Test
    public void preservesInsertionOrder() {
        List<String> calls = new ArrayList<>();
        calls.add("W1AW");
        calls.add("VE3XYZ");
        calls.add("G0ABC");
        assertThat(LogHttpServer.followCallsignBlock(calls))
                .isEqualTo("W1AW,&nbsp;VE3XYZ,&nbsp;G0ABC,&nbsp;");
    }

    /**
     * The reason the field is a CopyOnWriteArrayList: a writer thread adding and
     * clearing it while the renderer iterates must never throw. A plain
     * ArrayList would surface a ConcurrentModificationException / a torn read;
     * the COW snapshot iterator is immune. Run enough rounds that a plain-list
     * implementation would reliably trip.
     */
    @Test
    public void concurrentAddClear_duringRender_neverThrows() throws Exception {
        final List<String> shared = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 50; i++) {
            shared.add("SEED" + i);
        }
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final int rounds = 5000;

        Thread writer = new Thread(() -> {
            try {
                for (int r = 0; r < rounds; r++) {
                    shared.add("X" + (r % 64));
                    if ((r % 32) == 0) {
                        shared.clear();
                    }
                }
            } catch (Throwable t) {
                // Record writer-side failures too; a bare thread throw would be
                // swallowed by the JVM and let this test pass despite a crash.
                failure.compareAndSet(null, t);
            }
        });
        writer.start();

        try {
            for (int r = 0; r < rounds; r++) {
                // Must not throw even as the list is mutated underneath us.
                LogHttpServer.followCallsignBlock(shared);
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
        writer.join();

        assertThat(failure.get()).isNull();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                break;
            }
            count++;
            from = idx + needle.length();
        }
        return count;
    }
}
