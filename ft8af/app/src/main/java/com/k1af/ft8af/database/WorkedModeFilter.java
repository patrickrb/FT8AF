package com.k1af.ft8af.database;

import java.util.Locale;

/**
 * "Same mode only" refinement for the worked-station scopes.
 *
 * <p>The worked-before handling (Settings → Decode Highlights) lets the operator
 * pick which stations count as worked — on this band, worked before anywhere,
 * worked today, or from a list. This adds the orthogonal "and mode" axis the
 * feature request asked for ("worked before/today <b>on this band and mode</b>"):
 * when {@link com.k1af.ft8af.GeneralVariables#workedSameMode} is on, the worked
 * lists loaded by {@link DatabaseOpr.GetAllQSLCallsign} are additionally
 * restricted to QSOs made on the current operating mode, so a station worked
 * only on a different mode (e.g. FT4 while you're on FT8) still counts as new.
 *
 * <p>Kept as a plain, side-effect-free helper (no DB/Android types) so the
 * predicate is unit-testable without Robolectric.
 */
public final class WorkedModeFilter {

    /** SQL fragment appended after an existing {@code WHERE} clause (empty when disabled). */
    public final String sqlSuffix;
    /** Positional bind arguments for {@link #sqlSuffix}, in order (never null). */
    public final String[] args;

    private WorkedModeFilter(String sqlSuffix, String[] args) {
        this.sqlSuffix = sqlSuffix;
        this.args = args;
    }

    /**
     * Build the mode predicate.
     *
     * @param sameMode whether the "same mode only" refinement is enabled
     * @param mode     current operating-mode name ("FT8"/"FT4"…); a null/blank
     *                 mode disables the predicate even when {@code sameMode} is on
     */
    public static WorkedModeFilter build(boolean sameMode, String mode) {
        if (!sameMode || mode == null || mode.trim().isEmpty()) {
            return new WorkedModeFilter("", new String[0]);
        }
        // Case-insensitive compare so a hand-imported "ft8" still matches "FT8".
        return new WorkedModeFilter(
                " and upper(mode) = ?",
                new String[]{mode.trim().toUpperCase(Locale.US)});
    }

    /**
     * Whether an operating-mode switch has invalidated the cached worked lists.
     *
     * <p>{@link DatabaseOpr.GetAllQSLCallsign} builds those lists per band and — when the
     * "same mode only" refinement is on — per operating mode. {@code MainViewModel.setOperatingMode}
     * historically reloaded them only when the switch also retuned the dial, which is not
     * guaranteed: the new mode may have no band entry, or the dial may already sit on the target
     * frequency. In that case the lists kept the previous mode's stations and the highlighting
     * (or hiding) stayed wrong until an unrelated reload ran.
     *
     * @param retuned  whether the mode switch also moved the dial (band-keyed lists are stale)
     * @param sameMode whether the "same mode only" refinement is on (mode-keyed lists are stale)
     */
    public static boolean reloadNeededOnModeChange(boolean retuned, boolean sameMode) {
        return retuned || sameMode;
    }

    /**
     * Combine a base set of query arguments with this filter's extra args.
     *
     * @param baseArgs the arguments already required by the query (e.g. band)
     * @return {@code baseArgs} followed by {@link #args} (the input array when no extras)
     */
    public String[] withArgs(String... baseArgs) {
        if (args.length == 0) {
            return baseArgs;
        }
        String[] combined = new String[baseArgs.length + args.length];
        System.arraycopy(baseArgs, 0, combined, 0, baseArgs.length);
        System.arraycopy(args, 0, combined, baseArgs.length, args.length);
        return combined;
    }
}
