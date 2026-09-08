import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { debounce } from "./debounce";

describe("debounce", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("defers the call until the delay elapses", () => {
    const fn = vi.fn();
    const d = debounce(fn, 120);
    d(50);
    expect(fn).not.toHaveBeenCalled();
    vi.advanceTimersByTime(119);
    expect(fn).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(fn).toHaveBeenCalledExactlyOnceWith(50);
  });

  it("collapses a burst into one call with the last value", () => {
    // The slider-drag case: dozens of onChange events, one IPC round-trip.
    const fn = vi.fn();
    const d = debounce(fn, 120);
    for (const v of [10, 20, 30, 40]) {
      d(v);
      vi.advanceTimersByTime(30); // each below the threshold
    }
    expect(fn).not.toHaveBeenCalled();
    vi.advanceTimersByTime(120);
    expect(fn).toHaveBeenCalledExactlyOnceWith(40);
  });

  it("fires again once the burst has settled", () => {
    const fn = vi.fn();
    const d = debounce(fn, 120);
    d(1);
    vi.advanceTimersByTime(120);
    d(2);
    vi.advanceTimersByTime(120);
    expect(fn.mock.calls).toEqual([[1], [2]]);
  });

  it("cancel drops a pending call", () => {
    // Unmount with a drag still in flight: the IPC must not fire afterwards.
    const fn = vi.fn();
    const d = debounce(fn, 120);
    d(7);
    d.cancel();
    vi.advanceTimersByTime(1000);
    expect(fn).not.toHaveBeenCalled();
  });

  it("cancel is safe with nothing pending, and does not disable the debouncer", () => {
    const fn = vi.fn();
    const d = debounce(fn, 120);
    d.cancel();
    d.cancel();
    d(3);
    vi.advanceTimersByTime(120);
    expect(fn).toHaveBeenCalledExactlyOnceWith(3);
  });

  it("passes a falsy value through rather than skipping it", () => {
    // 0% is a deliberate setting (mute), not an absent one.
    const fn = vi.fn();
    const d = debounce(fn, 120);
    d(0);
    vi.advanceTimersByTime(120);
    expect(fn).toHaveBeenCalledExactlyOnceWith(0);
  });
});
