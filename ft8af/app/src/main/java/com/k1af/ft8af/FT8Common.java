package com.k1af.ft8af;

/**
 * FT8-related constants.
 * @author BGY70Z
 * @date 2023-03-20
 */
public final class FT8Common {
    public static final int FT8_MODE=0;
    public static final int FT4_MODE=1;
    //FT2: same 4-GFSK/Costas/LDPC structure as FT4 but double the baud (0.024s symbol
    //period, 167Hz BW, 3.75s T/R cycle). Receive uses the from-source FT2 decoder; the
    //closed prebuilt libft8cn.so only knows FT8/FT4.
    public static final int FT2_MODE=2;
    public static final int SAMPLE_RATE=12000;
    public static final int FT8_SLOT_TIME=15;
    public static final int FT8_SLOT_TIME_MILLISECOND=15000;//milliseconds per cycle
    //Shorter RX window used when GeneralVariables.earlyDecode is on. The FT8 message is
    //12.64s; 13500ms captures it plus ~0.86s of positive-DT margin and lets the decode
    //finish ~1s before the cycle boundary, so a CQ can be answered on the next slot.
    public static final int FT8_EARLY_DECODE_MILLISECOND=13500;
    public static final int FT4_SLOT_TIME_MILLISECOND=7500;
    public static final int FT2_SLOT_TIME_MILLISECOND=3750;
    public static final int FT8_5_SYMBOLS_MILLISECOND=800;//time needed for 5 symbols


    public static final float FT4_SLOT_TIME=7.5f;
    public static final float FT2_SLOT_TIME=3.75f;
    public static final int FT8_SLOT_TIME_M=150;//15 seconds
    public static final int FT8_5_SYMBOLS_TIME_M =8;//duration of 5 symbols: 0.8 seconds
    public static final int FT4_SLOT_TIME_M=75;//7.5 seconds
    // FT2's 3.75s slot is 37.5 tenths — not a whole number, so it has no tenths-of-a-second
    // form. Timing uses FT2_SLOT_TIME_MILLISECOND (3750) directly; see ModeProfile.slotMillis.
    public static final int FT8_TRANSMIT_DELAY=500;//default transmit delay duration in milliseconds
    // Deep-decode subtraction loop time bound now scales with the mode's slot length; see
    // ModeProfile#deepDecodeBudgetMillis. The old flat 7s constant was ~2x FT2's entire 3.75s
    // slot (so it never bounded fast modes) while leaving FT8 far short of its 15s cycle.
    public static final int DECODE_MAX_ITERATIONS=1;//number of iterations
}
