package com.k1af.ft8af.ft8listener;

/**
 * The three decode-depth levels, replacing the old fast/deep boolean toggle
 * ({@code deepMode}). Pulled out of {@link FT8SignalListener} so the
 * pass-selection decisions are unit-testable without loading the native
 * decoder.
 *
 * <p>Per buffer (the early window and the late full-slot pass both apply
 * this independently -- see {@link FT8SignalListener}):
 * <ul>
 *   <li>{@link #FAST}: one decode pass. The historical deep-mode-off
 *       behavior.
 *   <li>{@link #BALANCED}: adds the deep LDPC+OSD pass (more LDPC
 *       iterations, the ordered-statistics backstop -- see
 *       {@code setDecodeMode}'s native side) on top of the fast pass, but
 *       stops there.
 *   <li>{@link #DEEP}: {@link #BALANCED} plus the subtract-and-redecode
 *       loop (successive interference cancellation: strip each decoded
 *       signal and redecode the remainder to find weaker ones underneath),
 *       budgeted by {@link DeepDecodeBudget}. This is the expensive,
 *       open-ended part -- the loop keeps going until a pass finds nothing
 *       new or its budget runs out -- and the historical deep-mode-on
 *       behavior.
 * </ul>
 */
public final class DecodeDepth {

    private DecodeDepth() {}

    public static final int FAST = 0;
    public static final int BALANCED = 1;
    public static final int DEEP = 2;

    /** Whether this depth runs the deep LDPC+OSD pass at all (BALANCED or DEEP). */
    public static boolean runsDeepPass(int decodeDepth) {
        return decodeDepth >= BALANCED;
    }

    /** Whether this depth runs the subtract-and-redecode (SIC) loop (DEEP only). */
    public static boolean runsSubtractRedecodeLoop(int decodeDepth) {
        return decodeDepth >= DEEP;
    }

    /**
     * Clamp an arbitrary persisted/parsed value to a valid depth. An
     * out-of-range value (a downgrade reading a future build's config, or a
     * parse fallback) becomes {@link #DEEP} -- the pre-existing default
     * behavior this field replaces ({@code deepDecodeMode = true}), so a
     * garbled value degrades to "most thorough," never silently to "least."
     */
    public static int clamp(int value) {
        if (value == FAST || value == BALANCED) {
            return value;
        }
        return DEEP;
    }
}
