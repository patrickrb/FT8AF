# Auto-selecting a clear FT8 TX offset for CQ (issue #418)

## Problem

When calling CQ, transmitting on an offset that is already occupied (or hemmed
in by strong neighbours) raises the collision rate at every receiver on the
band: two overlapping FT8 signals within ~50 Hz usually cost both decodes.
Manual slot selection from the waterfall is error-prone — the operator sees one
cycle of one parity and has to eyeball guard spacing.

## Background: what "occupied" means on FT8

- An FT8 signal is 8-GFSK with 6.25 Hz tone spacing: it occupies **50 Hz**
  starting at its base offset. Two signals collide when those 50 Hz intervals
  overlap at a receiver; decode probability also degrades with a strong signal
  immediately adjacent (AGC/splatter), which is why operators keep extra
  clearance beyond the nominal bandwidth.
- Activity is **slot-parity split** (even/odd 15 s slots). A station colliding
  with us is one transmitting in *our* slot — exactly the stations we cannot
  hear *while* we transmit. But before we start CQing we are receiving in both
  parities, and while CQing we still see the opposite parity every cycle. This
  is the core reason to score occupancy over a **multi-cycle history** rather
  than the last decode pass alone: a short window still captures both parities
  from the pre-TX observation period, and same-parity stations reappear in the
  history whenever we skip a transmission or they shift timing.
- Activity moves. A slot that was clear four cycles ago may be a pile-up now,
  and vice versa; conversely a single stale hit should not condemn a slot
  forever. A window of ~4 slots (1 minute of FT8) balances both.

## Algorithm

All logic lives in `ClearFrequencyFinder` (pure Java, unit-tested); the
tunables below are constructor parameters with the defaults shown.

1. **Occupancy history.** Every kept decode (own-echo-filtered) is recorded as
   `(freqHz, utcMs)`. Entries older than `historyCycles` × slot length
   (default **4 cycles**) are evicted against the newest UTC seen, so the
   window is mode-aware (60 s on FT8, 30 s on FT4).
2. **Occupied intervals.** Each recorded signal blocks
   `[f − guardHz, f + 50 + guardHz]` (default guard **10 Hz** per side —
   nominal bandwidth plus a margin for drift and splatter).
3. **Candidates.** A grid from `minOffset` to `maxOffset` (default
   **200–2800 Hz**, inside every rig's SSB passband and the decoder range) in
   `stepHz` (default **10 Hz**) increments. A candidate's own 50 Hz interval
   must fit inside the range.
4. **Scoring.** For each candidate: `clearance` = distance from the candidate's
   50 Hz interval to the nearest occupied interval in the window (0 when they
   intersect = occupied). Rank by:
   - larger clearance first, capped at `clearanceCap` (default **200 Hz** —
     beyond that more empty space buys nothing, and the cap stops the picker
     from always racing to the extreme band edges);
   - tie-break toward the band centre (**1500 Hz**), where every rig's passband
     is flat and most receivers listen;
   - final tie-break: lower offset (determinism).
5. **Keep-current bias.** If the operator's current offset is already clear, no
   move is suggested — a gratuitous QSY costs callers that had already spotted
   us. Selection also returns "no move" when there is no history at all (no
   information ≠ occupied).
6. **Retry / backoff.** While CQing (order 6, no station locked, not
   transmitting), each decode delivery re-checks the current offset. If it has
   become occupied, the finder relocates — but at most once per
   `moveHolddownMs` (default **45 s** ≈ 3 FT8 cycles), so a marginal decode
   can't make the CQ hop every cycle (each hop orphans answers already in
   flight). Fallback when the whole band is busy: the least-bad candidate
   (max clearance) is used only if it strictly beats the current offset's
   clearance; otherwise stay put.

## Integration

- `MainViewModel.afterDecode` feeds every kept decode (all passes — deep passes
  see signals the fast pass missed) to the finder via
  `FT8TransmitSignal.recordBandActivity`.
- Pressing **CQ** (`userResetToCQ`) applies a selection when the "Auto clear
  TX offset" setting is on: the chosen offset goes through the existing
  `setBaseFrequency` path (same as a waterfall tap), with a toast and a
  `debug.log` line (`CLEARFREQ: ...`) recording the decision inputs.
- The re-check hook runs from the same decode delivery, guarded to the
  CQ-idle state so a live QSO never QSYs.
- Off by default (config key `autoClearTxFreq`); the manual waterfall tap
  always wins — it just becomes the new "current" offset that the keep-current
  bias protects.

## Validation

- Unit tests (`ClearFrequencyFinderTest`) cover: interval overlap/guard math,
  window eviction by mode slot length, clearance ranking + centre tie-break,
  keep-current and no-history behaviour, full-band fallback, hold-down pacing,
  and the CQ-idle gate predicate.
- On-air validation: start CQ on a busy band, confirm the chosen offset lands
  in a gap on the waterfall and `debug.log` shows the candidate scoring; park a
  strong signal on the CQ offset and confirm exactly one relocation per
  hold-down window.
