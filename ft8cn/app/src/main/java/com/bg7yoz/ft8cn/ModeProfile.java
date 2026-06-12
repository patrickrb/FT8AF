package com.bg7yoz.ft8cn;

import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;

/**
 * Describes one operating mode (FT8, FT4, ...). All the per-mode timing, symbol, and
 * synthesis parameters live here so the rest of the app can stay mode-agnostic: read the
 * descriptor via {@link GeneralVariables#currentMode()} instead of hard-coding 15s/79-tone
 * FT8 assumptions.
 *
 * <p>Adding a future mode (e.g. "FT2") is a one-entry change here plus a matching native
 * encode binding wired into {@link #encode}.
 *
 * @see FT8Common#FT8_MODE
 * @see FT8Common#FT4_MODE
 */
public enum ModeProfile {
    // id, name, slotMs, earlyDecodeMs, symPeriod, symBt, numTones, isFt8
    FT8(FT8Common.FT8_MODE, "FT8", 15000, 13500, 0.160f, 2.0f, 79, true),
    FT4(FT8Common.FT4_MODE, "FT4", 7500, 6500, 0.048f, 1.0f, 105, false),
    // FT2 reuses FT4's tone layout (4-GFSK, 4 Costas, 105 symbols, same LDPC/encoder) at
    // double the baud: 0.024s symbol period -> ~2.52s audio, 3.75s slot (exactly half FT4's
    // 7.5s). isFt8=false so encode() dispatches to the shared ft4Encode; decode routes to the
    // from-source decoder (see usesFromSourceDecoder) since the prebuilt has no FT2 protocol.
    FT2(FT8Common.FT2_MODE, "FT2", 3750, 3000, 0.024f, 1.0f, 105, false);

    /** Mode id, matching the {@code FT8Common.*_MODE} ints (also stored in config + Ft8Message). */
    public final int id;
    /** Human/protocol-facing name ("FT8"/"FT4"), used for logs, PSKReporter, POTA, ADIF. */
    public final String displayName;
    /** TX/RX cycle length in milliseconds (15000 FT8, 7500 FT4, 3750 FT2); the
     * {@link com.bg7yoz.ft8cn.timer.UtcTimer} fires a slot boundary on this grid. */
    public final int slotMillis;
    /** Shorter RX capture window used when fast/early decode is on. */
    public final int earlyDecodeMillis;
    /** GFSK symbol period in seconds (0.160 FT8, 0.048 FT4). */
    public final float symbolPeriod;
    /** GFSK Gaussian BT product (2.0 FT8, 1.0 FT4). */
    public final float symbolBt;
    /** Number of channel symbols/tones (79 FT8, 105 FT4). */
    public final int numTones;
    /** Whether the native decoder/encoder should use the FT8 protocol (false == FT4). */
    public final boolean isFt8;

    /** Audio waveform length in milliseconds, rounded: numTones * symbolPeriod * 1000. */
    public final int audioMillis;
    /**
     * Slack between the end of the audio and the slot boundary (slotMillis - audioMillis).
     * Drives the late-start clip math in transmit so a normal on-time TX is never clipped.
     */
    public final int audioSlackMillis;

    ModeProfile(int id, String displayName, int slotMillis,
                int earlyDecodeMillis, float symbolPeriod, float symbolBt,
                int numTones, boolean isFt8) {
        this.id = id;
        this.displayName = displayName;
        this.slotMillis = slotMillis;
        this.earlyDecodeMillis = earlyDecodeMillis;
        this.symbolPeriod = symbolPeriod;
        this.symbolBt = symbolBt;
        this.numTones = numTones;
        this.isFt8 = isFt8;
        this.audioMillis = Math.round(numTones * symbolPeriod * 1000f);
        this.audioSlackMillis = slotMillis - this.audioMillis;
    }

    /**
     * Resolve a descriptor from a mode id. Unknown ids (e.g. a config value persisted by a
     * future build before this one knows the mode) fall back to FT8 for forward-compat.
     */
    public static ModeProfile fromId(int id) {
        for (ModeProfile m : values()) {
            if (m.id == id) {
                return m;
            }
        }
        return FT8;
    }

    /**
     * Whether receive uses the from-source ft8_lib decoder (compiled into libft8af_usb.so)
     * rather than the prebuilt libft8cn.so.
     *
     * <p>FT2 must, because the closed prebuilt's protocol enum has no FT2. FT4 also does now:
     * the prebuilt decodes FT4 but reports its SNR ~15 dB low (its FT4 SNR path was never
     * calibrated), whereas the from-source build has a corrected protocol-aware {@code
     * ft8_snr}. The from-source decoder is pinned at the same kgoba commit the prebuilt was
     * built from, so FT4 decoding matches; only the SNR differs. FT8 stays on the prebuilt.
     */
    public boolean usesFromSourceDecoder() {
        return id == FT8Common.FT2_MODE || id == FT8Common.FT4_MODE;
    }

    /**
     * The native {@code ftx_protocol_t} value for this mode (constants.h enum order:
     * FT4 = 0, FT8 = 1, FT2 = 2). Passed to the from-source decoder so it configures the
     * monitor for the right symbol period / sync layout.
     */
    public int ftxProtocol() {
        if (id == FT8Common.FT2_MODE) return 2; // FTX_PROTOCOL_FT2
        if (isFt8) return 1;                     // FTX_PROTOCOL_FT8
        return 0;                                // FTX_PROTOCOL_FT4
    }

    /**
     * Encode an a91 payload (77-bit message + CRC, shared across modes) into this mode's
     * tone array. Dispatches to the matching native encoder.
     *
     * @param a91   the packed payload (>=10 bytes)
     * @param tones output buffer, must be at least {@link #numTones} long
     */
    public void encode(byte[] a91, byte[] tones) {
        if (isFt8) {
            GenerateFT8.ft8Encode(a91, tones);
        } else {
            GenerateFT8.ft4Encode(a91, tones);
        }
    }
}
