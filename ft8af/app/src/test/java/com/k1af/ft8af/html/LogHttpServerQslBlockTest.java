package com.k1af.ft8af.html;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure-logic coverage for {@link LogHttpServer#qslCallsignBlock(List)}, the
 * successful-callsign table body rendered on the web-logbook status page.
 *
 * <p>The helper was extracted from an inline loop that re-dereferenced the shared
 * {@link com.k1af.ft8af.GeneralVariables#QSL_Callsign_list} field on every
 * {@code size()}/{@code get(i)} step. Taking the list as a parameter lets a caller
 * read one stable snapshot (so a mid-render reassignment on the DB thread can no
 * longer make {@code get(i)} run off the end of a shorter list), and makes the exact
 * HTML layout unit-testable. No Android types are touched, so no Robolectric runner is
 * needed.
 */
public class LogHttpServerQslBlockTest {

    private static final String ROW_OPEN = "<tr><td class=\"default\" >";
    private static final String ROW_BREAK = "</td></tr><tr><td class=\"default\" >\n";
    private static final String ROW_CLOSE = "</td></tr>\n";

    @Test
    public void emptyList_rendersEmptyCell() {
        // Still opens and closes the row so the surrounding table stays well-formed.
        assertThat(LogHttpServer.qslCallsignBlock(new ArrayList<>()))
                .isEqualTo(ROW_OPEN + ROW_CLOSE);
    }

    @Test
    public void singleCallsign_isCommaSpaced() {
        assertThat(LogHttpServer.qslCallsignBlock(List.of("K1AF")))
                .isEqualTo(ROW_OPEN + "K1AF,&nbsp;" + ROW_CLOSE);
    }

    @Test
    public void tenthEntry_startsANewRow() {
        // A row break is emitted after every tenth entry (matching the original layout).
        List<String> calls = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            calls.add("C" + i);
        }
        StringBuilder expected = new StringBuilder(ROW_OPEN);
        for (int i = 1; i <= 10; i++) {
            expected.append("C").append(i).append(",&nbsp;");
        }
        // The tenth entry triggers exactly one break; nothing follows it before the close.
        expected.append(ROW_BREAK);
        expected.append(ROW_CLOSE);
        assertThat(LogHttpServer.qslCallsignBlock(calls)).isEqualTo(expected.toString());
    }

    @Test
    public void elevenEntries_haveOneBreakThenTheEleventh() {
        List<String> calls = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            calls.add("C" + i);
        }
        String html = LogHttpServer.qslCallsignBlock(calls);
        // Exactly one row break (after the tenth), and the eleventh entry appears after it.
        int breaks = html.split(java.util.regex.Pattern.quote(ROW_BREAK), -1).length - 1;
        assertThat(breaks).isEqualTo(1);
        assertThat(html).endsWith("C11,&nbsp;" + ROW_CLOSE);
    }

    @Test
    public void renderSurvivesConcurrentAppend() throws InterruptedException {
        // Production scenario: the NanoHTTPD worker renders the block from the shared
        // CopyOnWriteArrayList while the decode thread appends to it. The render must never
        // throw and must always see at least the seeded entries. (A plain ArrayList here
        // would race the writer inside the helper's for-each.)
        final CopyOnWriteArrayList<String> shared = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 100; i++) {
            shared.add("SEED" + i);
        }

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch writerDone = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 5000; i++) {
                shared.add("ADD" + i);
            }
            writerDone.countDown();
        });
        writer.start();

        try {
            while (writerDone.getCount() > 0) {
                String html = LogHttpServer.qslCallsignBlock(shared);
                assertThat(html).contains("SEED99,&nbsp;");
                assertThat(html).startsWith(ROW_OPEN);
                assertThat(html).endsWith(ROW_CLOSE);
            }
        } catch (Throwable t) {
            failure.set(t);
        }

        writer.join();
        assertThat(failure.get()).isNull();
    }
}
