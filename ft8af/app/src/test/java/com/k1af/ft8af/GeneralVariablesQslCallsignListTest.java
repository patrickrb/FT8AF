package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guards the shared worked-callsign list {@link GeneralVariables#QSL_Callsign_list}
 * against the web-logbook / decode / DB-reload data race.
 *
 * <p>The list is reassigned wholesale on the database thread
 * ({@code DatabaseOpr.GetAllQSLCallsign}) and appended to on the decode thread
 * ({@link GeneralVariables#addQSLCallsign(String)}), while the NanoHTTPD worker
 * index-scans it to render the web status page. As a plain {@code ArrayList} that is a
 * lock-free data race: a for-each on the HTTP thread throws
 * {@link java.util.ConcurrentModificationException} when a decode adds mid-render.
 * Backing it with a {@link CopyOnWriteArrayList} makes every reader see a stable snapshot.
 *
 * <p>The field is reset to a {@code CopyOnWriteArrayList} in {@code setUp} because
 * {@code GeneralVariables} statics are process-global and a neighbouring test may leave a
 * plain list behind; the "production always uses a COW" invariant is proven separately by
 * {@code GetAllQSLCallsignModeTest} (reload) plus {@code addQSLCallsign} here.
 *
 * <p>Robolectric because {@link GeneralVariables} carries Android LiveData statics that
 * must initialize before the field can be touched.
 */
@RunWith(RobolectricTestRunner.class)
public class GeneralVariablesQslCallsignListTest {

    @Before
    public void setUp() {
        GeneralVariables.QSL_Callsign_list = new CopyOnWriteArrayList<>();
    }

    @Test
    public void addQSLCallsignAddsEachCallsignOnce() {
        GeneralVariables.addQSLCallsign("K1AF");
        GeneralVariables.addQSLCallsign("K1AF");
        GeneralVariables.addQSLCallsign("W1AW");
        assertThat(GeneralVariables.QSL_Callsign_list).containsExactly("K1AF", "W1AW");
        assertThat(GeneralVariables.checkQSLCallsign("K1AF")).isTrue();
        assertThat(GeneralVariables.checkQSLCallsign("N0CALL")).isFalse();
    }

    @Test
    public void forEachSurvivesConcurrentAppend() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            GeneralVariables.addQSLCallsign("SEED" + i);
        }

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch writerDone = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 5000; i++) {
                GeneralVariables.addQSLCallsign("ADD" + i);
            }
            writerDone.countDown();
        });
        writer.start();

        // Iterate exactly the way LogHttpServer renders the page: a for-each over the
        // shared field. On a plain ArrayList this races the writer and throws; on the
        // CopyOnWriteArrayList each iterator walks a stable snapshot.
        try {
            while (writerDone.getCount() > 0) {
                int count = 0;
                for (String call : GeneralVariables.QSL_Callsign_list) {
                    if (call != null) {
                        count++;
                    }
                }
                assertThat(count).isAtLeast(50);
            }
        } catch (Throwable t) {
            failure.set(t);
        }

        writer.join();
        assertThat(failure.get()).isNull();
    }
}
