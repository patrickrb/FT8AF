package com.k1af.ft8af.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Submits work to an {@link ExecutorService} without letting a submission that
 * loses the shutdown race crash the app.
 *
 * <p>The FT8 decode listener threads keep delivering passes for a short while
 * after {@code MainViewModel.onCleared()} shuts its pools down — in particular a
 * late deep-decode pass fires {@code afterDecode(...)} on a background decode
 * thread, which submits a QTH-lookup task to {@code getQTHThreadPool}. A raw
 * {@code execute()} on an already-terminated pool throws
 * {@link RejectedExecutionException} on that decode thread (the default
 * {@code AbortPolicy}), which is uncaught and takes the whole process down
 * (observed crash: {@code MainViewModel.afterDecode} on ViewModel teardown).
 *
 * <p>{@link #tryExecute} swallows exactly that race: if the pool is shut down or
 * otherwise rejects the task, the task is dropped and the caller is told via a
 * {@code false} return instead of an exception.
 */
public final class SafeExecutor {

    private SafeExecutor() {}

    /**
     * Execute {@code task} on {@code pool}, returning whether it was accepted.
     *
     * @return {@code true} if the pool accepted the task; {@code false} if
     *     {@code pool}/{@code task} was null, the pool was already shut down, or
     *     the pool rejected the submission (shutdown race / saturation). Never
     *     throws {@link RejectedExecutionException}.
     */
    public static boolean tryExecute(ExecutorService pool, Runnable task) {
        if (pool == null || task == null) {
            return false;
        }
        // Fast path: skip the throw entirely once the pool is known-down.
        if (pool.isShutdown()) {
            return false;
        }
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            // The pool was shut down (or hit a bound) between the isShutdown()
            // check above and execute() — the race with onCleared(). Drop the
            // task rather than crash the decode thread.
            return false;
        }
    }
}
