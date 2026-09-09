// Trailing-edge debounce, extracted from App so it can be unit-tested (the
// timer bookkeeping inside a component body is not reachable from vitest).

/** A debounced callback, plus the `cancel` a React cleanup needs. */
export type Debounced<T> = {
  (value: T): void;
  /** Drop any pending call. Idempotent; safe to call when nothing is pending. */
  cancel: () => void;
};

/**
 * Call `fn` with the most recent argument once `ms` have passed with no
 * further calls. Trailing edge only: rapid calls collapse into one, which is
 * the point here — a gain-slider drag fires dozens of onChange events and each
 * one would otherwise be a full IPC round-trip to the Rust backend.
 *
 * `cancel` exists because a pending timer outlives unmount: without it the
 * callback still fires after teardown, invoking IPC against a component that
 * no longer exists.
 */
export function debounce<T>(fn: (value: T) => void, ms: number): Debounced<T> {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const debounced = (value: T) => {
    if (timer !== null) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      fn(value);
    }, ms);
  };
  debounced.cancel = () => {
    if (timer !== null) clearTimeout(timer);
    timer = null;
  };
  return debounced;
}
