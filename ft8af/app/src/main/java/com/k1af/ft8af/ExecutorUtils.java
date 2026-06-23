package com.k1af.ft8af;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Helpers for submitting work to executors that may be torn down concurrently.
 *
 * <p>The decode pipeline runs on its own thread and calls back into
 * {@code MainViewModel} after each pass to kick off background work (QTH/grid
 * lookups, network TX audio). Those pools are shut down in
 * {@code MainViewModel.onCleared()} when the ViewModel is destroyed (app close,
 * connection reset/reconnect). A decode result that lands in the same instant
 * the pool is shut down would otherwise reach {@link ExecutorService#execute}
 * on a terminated pool and throw {@link RejectedExecutionException} on the
 * decode thread, crashing the app. See the {@code afterDecode} race fixed
 * alongside this helper.
 */
public final class ExecutorUtils {

    private ExecutorUtils() {
    }

    /**
     * Submit {@code task} to {@code pool}, tolerating a pool that is shutting
     * down. The {@link ExecutorService#isShutdown()} check avoids the common
     * case; the catch covers the narrow race where the pool is shut down
     * between the check and {@link ExecutorService#execute}.
     *
     * @return {@code true} if the task was accepted, {@code false} if it was
     *     dropped because the pool (or task) was unavailable.
     */
    public static boolean safeExecute(ExecutorService pool, Runnable task) {
        if (pool == null || task == null || pool.isShutdown()) {
            return false;
        }
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            // Pool was shut down concurrently (ViewModel teardown). Drop the task.
            return false;
        }
    }
}
