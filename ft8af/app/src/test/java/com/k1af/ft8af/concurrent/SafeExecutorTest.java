package com.k1af.ft8af.concurrent;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure-JVM tests for {@link SafeExecutor} — no Android types, so no Robolectric
 * runner is needed. Covers the shutdown race that took the app down at
 * MainViewModel.afterDecode.
 */
public class SafeExecutorTest {

    @Test
    public void tryExecute_runsTaskOnLivePool() throws InterruptedException {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch ran = new CountDownLatch(1);
            boolean accepted = SafeExecutor.tryExecute(pool, ran::countDown);

            assertThat(accepted).isTrue();
            assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void tryExecute_onShutdownPool_returnsFalseAndDoesNotThrow() {
        ExecutorService pool = Executors.newCachedThreadPool();
        pool.shutdown();

        AtomicBoolean ran = new AtomicBoolean(false);
        // A raw pool.execute() here would throw RejectedExecutionException and,
        // on the decode thread, crash the process. tryExecute must not.
        boolean accepted = SafeExecutor.tryExecute(pool, () -> ran.set(true));

        assertThat(accepted).isFalse();
        assertThat(ran.get()).isFalse();
    }

    @Test
    public void tryExecute_afterShutdownNow_returnsFalse() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.shutdownNow();

        boolean accepted = SafeExecutor.tryExecute(pool, () -> { });

        assertThat(accepted).isFalse();
    }

    @Test
    public void tryExecute_nullPool_returnsFalse() {
        assertThat(SafeExecutor.tryExecute(null, () -> { })).isFalse();
    }

    @Test
    public void tryExecute_nullTask_returnsFalse() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertThat(SafeExecutor.tryExecute(pool, null)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }
}
