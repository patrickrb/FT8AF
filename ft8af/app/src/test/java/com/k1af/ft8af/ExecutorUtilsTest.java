package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link ExecutorUtils#safeExecute(ExecutorService, Runnable)}.
 *
 * <p>Locks the invariant behind the {@code afterDecode} crash fix: submitting a
 * decode-followup task to a pool that has been shut down (ViewModel teardown)
 * must drop the task quietly instead of throwing
 * {@link java.util.concurrent.RejectedExecutionException}.
 */
public class ExecutorUtilsTest {

    @Test
    public void activePool_runsTaskAndReturnsTrue() throws InterruptedException {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch ran = new CountDownLatch(1);
            boolean accepted = ExecutorUtils.safeExecute(pool, ran::countDown);
            assertThat(accepted).isTrue();
            assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void shutdownPool_dropsTaskAndReturnsFalse() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.shutdown();
        // The pre-fix code path: execute() on a terminated pool threw and crashed
        // the decode thread. safeExecute must swallow it and report the drop.
        boolean accepted = ExecutorUtils.safeExecute(pool, () -> {
            throw new AssertionError("task must not run on a shut-down pool");
        });
        assertThat(accepted).isFalse();
    }

    @Test
    public void nullPool_returnsFalse() {
        assertThat(ExecutorUtils.safeExecute(null, () -> { })).isFalse();
    }

    @Test
    public void nullTask_returnsFalse() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertThat(ExecutorUtils.safeExecute(pool, null)).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }
}
