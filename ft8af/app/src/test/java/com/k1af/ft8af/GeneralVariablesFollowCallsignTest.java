package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the thread-safety of {@link GeneralVariables#followCallsign}.
 *
 * <p>The list is mutated with no external lock from several threads: the
 * decode/DB threads add to it ({@code MainViewModel.addFollowCallsign},
 * {@code getFollowCallsignsFromDataBase}) and the UI thread clears it
 * ({@code ClearCacheDataDialog}), while the web logbook renders it on a
 * NanoHTTPD worker thread ({@code LogHttpServer}). As a plain {@code ArrayList}
 * that contention corrupts the backing array and a {@code size()}+{@code get(i)}
 * scan on the HTTP thread races a concurrent clear into an
 * {@link IndexOutOfBoundsException}. It must be a {@link CopyOnWriteArrayList}
 * (mirroring its sibling {@code transmitMessages}) so every add/clear is atomic
 * and for-each readers get a stable snapshot.
 */
@RunWith(RobolectricTestRunner.class)
public class GeneralVariablesFollowCallsignTest {

    @Before
    public void setUp() {
        GeneralVariables.followCallsign.clear();
    }

    @After
    public void tearDown() {
        GeneralVariables.followCallsign.clear();
    }

    @Test
    public void followCallsign_isCopyOnWriteArrayList() {
        // Regression lock: a plain ArrayList reintroduces the cross-thread
        // web-logbook crash this guards against.
        assertThat(GeneralVariables.followCallsign).isInstanceOf(CopyOnWriteArrayList.class);
    }

    @Test
    public void concurrentAddClear_duringForEach_neverThrows() throws Exception {
        for (int i = 0; i < 32; i++) {
            GeneralVariables.followCallsign.add("SEED" + i);
        }
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final int rounds = 5000;

        Thread writer = new Thread(() -> {
            for (int r = 0; r < rounds; r++) {
                GeneralVariables.followCallsign.add("W" + (r % 64));
                if ((r % 25) == 0) {
                    GeneralVariables.followCallsign.clear();
                }
            }
        });
        writer.start();

        try {
            for (int r = 0; r < rounds; r++) {
                // The read pattern LogHttpServer/ClearCacheDataDialog now use:
                // a for-each over the shared list must never throw while the
                // writer thread adds/clears it.
                StringBuilder sink = new StringBuilder();
                for (String callsign : GeneralVariables.followCallsign) {
                    sink.append(callsign);
                }
            }
        } catch (Throwable t) {
            failure.set(t);
        }
        writer.join();

        assertThat(failure.get()).isNull();
    }
}
