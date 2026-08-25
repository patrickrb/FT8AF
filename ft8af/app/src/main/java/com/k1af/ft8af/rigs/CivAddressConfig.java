package com.k1af.ft8af.rigs;

import java.util.Locale;

/**
 * Encodes / decodes the ICOM CI-V address for the {@code civ} config key (issue #753).
 *
 * <p>The key has always been stored as <b>hex</b> — the legacy settings screen wrote the
 * text of its hex field, {@code rigaddress.txt} lists addresses in hex, and
 * {@code DatabaseOpr} parses it with radix 16. The Compose rig picker (2026-05) wrote
 * {@code Integer.toString(address)} instead, i.e. <b>decimal</b>: an IC-705's {@code 0xA4}
 * went to disk as {@code "164"}, came back as {@code 0x164}, was truncated to a byte and
 * became {@code 0x64}. From the next launch on, every frequency command went to a rig that
 * doesn't exist and every CI-V reply was filtered out — while PTT (which uses the address
 * the rig announces over WLAN) and audio kept working, which is exactly what #753 looks
 * like.
 *
 * <p>This class writes hex, reads hex, and repairs values a buggy build already wrote:
 * <ul>
 *   <li>{@link #decode}: a hex parse that lands outside {@code 0x00..0xFF} can only be a
 *       decimal string of a real address (all three-digit decimals are ≥ {@code 0x100} as
 *       hex), so it is re-read as decimal.</li>
 *   <li>{@link #reconcileWithModel}: two-digit decimal strings ({@code "88"} for an IC-706's
 *       {@code 0x58}) are valid hex too, so they are resolved against the selected rig
 *       model's address instead.</li>
 * </ul>
 * Pure — no Android imports — so it is unit-testable.
 */
public final class CivAddressConfig {

    /** Default when nothing usable is stored: IC-705 / X6100 style {@code 0xA4}. */
    public static final int DEFAULT_ADDRESS = 0xA4;

    /**
     * Provenance marker config key. Every writer that stores {@code civ} in hex also writes
     * {@code civFormat=hex}; the buggy decimal-writing picker never did. Its presence means
     * the stored value is trusted verbatim — in particular a deliberate two-digit override
     * that happens to be a decimal twin of the model address ({@code 0x88} on an IC-706)
     * is never "repaired" away. Its absence marks a value of unknown provenance that gets
     * the one-time model reconciliation and is then re-written canonically with the marker.
     */
    public static final String FORMAT_KEY = "civFormat";
    /** The only value {@link #FORMAT_KEY} ever carries. */
    public static final String FORMAT_HEX = "hex";

    private CivAddressConfig() {
    }

    /** Outcome of {@link #planRepair}: the address to use and whether to persist it. */
    public static final class Repair {
        /** The address the app should run with. */
        public final int address;
        /** True when {@code civ} (canonical hex) and {@link #FORMAT_KEY} must be written. */
        public final boolean writeBack;

        Repair(int address, boolean writeBack) {
            this.address = address;
            this.writeBack = writeBack;
        }
    }

    /**
     * Decides the one-time repair for the loaded CI-V address (#753).
     *
     * @param storedRaw    the {@code civ} string as found in the database, or null if absent
     * @param formatKnown  whether {@link #FORMAT_KEY} was present with {@link #FORMAT_HEX}
     * @param loaded       the address {@link #decode} produced from {@code storedRaw}
     * @param modelAddress the selected rig model's default address
     * @return the address to use, and whether to write it (and the marker) back
     *
     * <p>Rules: a marked value is trusted as-is — no model reconciliation, so user overrides
     * survive. An unmarked value gets {@link #reconcileWithModel}. A write-back is due when
     * the marker is missing (settles provenance once and for all, and fixes the three-digit
     * decimal case whose decoded address already equals the model's) or when the stored
     * text isn't the canonical {@link #encode} form.
     */
    public static Repair planRepair(String storedRaw, boolean formatKnown, int loaded,
                                    int modelAddress) {
        int resolved = formatKnown ? loaded : reconcileWithModel(loaded, modelAddress);
        boolean canonical = storedRaw != null
                && encode(resolved).equals(storedRaw.trim().toLowerCase(Locale.ROOT));
        return new Repair(resolved, !formatKnown || !canonical);
    }

    /** True when a stored {@link #FORMAT_KEY} value says the {@code civ} key is hex. */
    public static boolean isHexFormatMarker(String stored) {
        return stored != null && FORMAT_HEX.equalsIgnoreCase(stored.trim());
    }

    /** True for a value that fits a CI-V address byte. */
    public static boolean isValid(int address) {
        return address >= 0 && address <= 0xFF;
    }

    /** The on-disk form: lowercase hex without a prefix, e.g. {@code "a4"}. */
    public static String encode(int address) {
        return Integer.toHexString(address & 0xFF);
    }

    /**
     * Parses the stored {@code civ} value. Hex first; if that overflows a byte the string was
     * written in decimal by the old Compose picker and is re-read as such. Anything else
     * (empty, non-numeric, out of range either way) yields {@code fallback}.
     */
    public static int decode(String stored, int fallback) {
        if (stored == null) return fallback;
        String s = stored.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("0x")) s = s.substring(2);
        if (s.isEmpty()) return fallback;
        int hex;
        try {
            hex = Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
        if (isValid(hex)) return hex;
        int dec = decimalTwin(s);
        return isValid(dec) ? dec : fallback;
    }

    /**
     * Resolves the ambiguity {@link #decode} can't: a loaded address that doesn't match the
     * selected rig model, but whose digits read in decimal do. Example: an IC-706MKIIG
     * ({@code 0x58} = 88) saved as {@code "88"}, loaded as {@code 0x88}. Returns
     * {@code modelAddress} in that case, otherwise {@code loaded} unchanged — a deliberate
     * user override (rig configured to a non-default address) is left alone.
     */
    public static int reconcileWithModel(int loaded, int modelAddress) {
        if (!isValid(modelAddress) || loaded == modelAddress) return loaded;
        int dec = decimalTwin(Integer.toHexString(loaded));
        return dec == modelAddress ? modelAddress : loaded;
    }

    /** The digits of {@code s} read in base 10, or -1 if they aren't all decimal digits. */
    static int decimalTwin(String s) {
        if (s == null || s.isEmpty()) return -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        try {
            return Integer.parseInt(s, 10);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
