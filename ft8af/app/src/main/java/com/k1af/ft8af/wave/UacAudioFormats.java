package com.k1af.ft8af.wave;

import java.util.ArrayList;
import java.util.List;

/**
 * USB Audio Class 1.0 format-descriptor parsing and alt-setting selection (issue #364).
 *
 * <p>{@link UsbAudioDevice} used to assume every capture device streams 48 kHz/16-bit. Most
 * CM108-class codecs do, but a device that only offers 44.1 kHz got captured at the wrong rate
 * and nothing decoded. The fix has two halves; this class is the first: parse the raw
 * configuration descriptors ({@code UsbDeviceConnection.getRawDescriptors()}) for the
 * class-specific AS FORMAT_TYPE descriptors (bDescriptorType 0x24, subtype 0x02) that list
 * each alt setting's supported sample rates, then {@link #choose} an alt setting that really
 * supports 48 kHz — or, when none does, the best rate the device offers so the capture path
 * can resample rationally.
 *
 * <p>Deliberately plain Java operating on {@code byte[]} (no {@code android.hardware.usb}
 * types) so both the parsing and the selection decision are unit-testable on the host.
 */
public final class UacAudioFormats {

    private UacAudioFormats() {}

    // USB / UAC1 descriptor constants.
    private static final int DT_INTERFACE = 0x04;          // standard interface descriptor
    private static final int DT_CS_INTERFACE = 0x24;       // class-specific interface descriptor
    private static final int SUBTYPE_FORMAT_TYPE = 0x02;   // AS FORMAT_TYPE
    private static final int FORMAT_TYPE_I = 0x01;         // PCM
    private static final int CLASS_AUDIO = 0x01;
    private static final int SUBCLASS_AUDIOSTREAMING = 0x02;

    /** One FORMAT_TYPE_I descriptor: what a given (interface, alt setting) can stream. */
    public static final class StreamFormat {
        public final int interfaceId;
        public final int altSetting;
        public final int channels;
        public final int subframeSize;   // bytes per sample per channel (2 == 16-bit)
        public final int bitResolution;
        /** Discrete supported rates (empty when {@link #continuous}). */
        public final int[] discreteRates;
        /** True when the device reports a continuous min..max range instead of a table. */
        public final boolean continuous;
        public final int minRate;
        public final int maxRate;

        StreamFormat(int interfaceId, int altSetting, int channels, int subframeSize,
                     int bitResolution, int[] discreteRates, boolean continuous,
                     int minRate, int maxRate) {
            this.interfaceId = interfaceId;
            this.altSetting = altSetting;
            this.channels = channels;
            this.subframeSize = subframeSize;
            this.bitResolution = bitResolution;
            this.discreteRates = discreteRates;
            this.continuous = continuous;
            this.minRate = minRate;
            this.maxRate = maxRate;
        }

        public boolean supportsRate(int rate) {
            if (rate <= 0) return false;
            if (continuous) {
                return rate >= minRate && rate <= maxRate;
            }
            for (int r : discreteRates) {
                if (r == rate) return true;
            }
            return false;
        }

        boolean hasAnyRate() {
            if (continuous) return minRate > 0 && maxRate >= minRate;
            for (int r : discreteRates) {
                if (r > 0) return true;
            }
            return false;
        }

        /**
         * Best rate this format offers when {@code preferredRate} isn't supported: prefers
         * rates that can feed the decoder ({@code >= decoderRate}), then integer multiples of
         * {@code decoderRate} (FirDecimator fast path), then proximity to the preferred rate.
         */
        int bestRateFor(int preferredRate, int decoderRate) {
            if (supportsRate(preferredRate)) return preferredRate;
            if (continuous) {
                if (!hasAnyRate()) return -1;
                // Largest integer multiple of the decoder rate that fits both the range and
                // the preferred ceiling: exact integer decimation beats a rational ratio.
                if (decoderRate > 0) {
                    long hi = Math.min((long) maxRate, (long) preferredRate);
                    long multiple = (hi / decoderRate) * decoderRate;
                    if (multiple >= minRate && multiple >= decoderRate) {
                        return (int) multiple;
                    }
                }
                // Otherwise clamp the preferred rate into the range.
                return preferredRate < minRate ? minRate : maxRate;
            }
            int best = -1;
            long bestKey = Long.MIN_VALUE;
            for (int r : discreteRates) {
                if (r <= 0) continue;
                long key = rateKey(r, preferredRate, decoderRate, false);
                if (key > bestKey) {
                    bestKey = key;
                    best = r;
                }
            }
            return best;
        }
    }

    /** The selection result: which alt setting to activate and at what rate. */
    public static final class Choice {
        public final int altSetting;
        public final int sampleRate;
        public final int channels;

        Choice(int altSetting, int sampleRate, int channels) {
            this.altSetting = altSetting;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    /**
     * Walk a raw descriptor blob (device + configuration descriptors, as returned by
     * {@code UsbDeviceConnection.getRawDescriptors()}) and collect every FORMAT_TYPE_I
     * descriptor found inside an AudioStreaming interface, attributed to that interface's
     * (id, alt setting). Malformed input (zero {@code bLength}, truncated descriptor) stops
     * the walk and returns whatever parsed cleanly — never throws.
     */
    public static List<StreamFormat> parse(byte[] raw) {
        List<StreamFormat> out = new ArrayList<>();
        if (raw == null) return out;

        int i = 0;
        int ifaceId = -1;
        int altSetting = -1;
        boolean inAudioStreaming = false;

        while (i + 2 <= raw.length) {
            int len = raw[i] & 0xFF;
            int type = raw[i + 1] & 0xFF;
            if (len < 2 || i + len > raw.length) break;  // malformed; keep what we have

            if (type == DT_INTERFACE && len >= 9) {
                ifaceId = raw[i + 2] & 0xFF;
                altSetting = raw[i + 3] & 0xFF;
                int cls = raw[i + 5] & 0xFF;
                int sub = raw[i + 6] & 0xFF;
                inAudioStreaming = (cls == CLASS_AUDIO && sub == SUBCLASS_AUDIOSTREAMING);
            } else if (type == DT_CS_INTERFACE && inAudioStreaming && len >= 8
                    && (raw[i + 2] & 0xFF) == SUBTYPE_FORMAT_TYPE
                    && (raw[i + 3] & 0xFF) == FORMAT_TYPE_I) {
                int channels = raw[i + 4] & 0xFF;
                int subframe = raw[i + 5] & 0xFF;
                int bits = raw[i + 6] & 0xFF;
                int nFreq = raw[i + 7] & 0xFF;
                if (nFreq == 0) {
                    // Continuous range: 3-byte little-endian lower + upper bounds.
                    if (len >= 8 + 6) {
                        int lo = u24(raw, i + 8);
                        int hi = u24(raw, i + 11);
                        out.add(new StreamFormat(ifaceId, altSetting, channels, subframe,
                                bits, new int[0], true, lo, hi));
                    }
                } else if (len >= 8 + 3 * nFreq) {
                    int[] rates = new int[nFreq];
                    for (int k = 0; k < nFreq; k++) {
                        rates[k] = u24(raw, i + 8 + 3 * k);
                    }
                    out.add(new StreamFormat(ifaceId, altSetting, channels, subframe,
                            bits, rates, false, 0, 0));
                }
            }
            i += len;
        }
        return out;
    }

    /**
     * Pick the alt setting (and rate) to activate for a streaming interface.
     *
     * <ol>
     *   <li>Only 16-bit PCM formats are considered — the capture path is hardwired to
     *       2-byte samples.</li>
     *   <li>An alt setting supporting {@code preferredRate} (48 kHz) wins; the currently
     *       selected alt is kept when it qualifies, otherwise the lowest qualifying alt
     *       number is used.</li>
     *   <li>Otherwise the best-supported rate wins, ranked: can feed the decoder
     *       ({@code >= decoderRate}) &gt; integer multiple of {@code decoderRate} (integer
     *       FIR fast path) &gt; closest to {@code preferredRate} &gt; current alt setting.
     *       A non-multiple winner (e.g. 44.1 kHz) is handled downstream by
     *       {@link RationalResampler}.</li>
     * </ol>
     *
     * @return the choice, or {@code null} when nothing usable was parsed — callers must then
     *     keep the legacy assume-48-kHz behavior.
     */
    public static Choice choose(List<StreamFormat> formats, int interfaceId,
                                int currentAltSetting, int preferredRate, int decoderRate) {
        if (formats == null || formats.isEmpty()) return null;

        List<StreamFormat> candidates = new ArrayList<>();
        for (StreamFormat f : formats) {
            if (f.interfaceId != interfaceId) continue;
            if (f.subframeSize != 2) continue;  // capture path is 16-bit PCM only
            if (!f.hasAnyRate()) continue;
            candidates.add(f);
        }
        if (candidates.isEmpty()) return null;

        // 1) Preferred rate available: keep the current alt when it qualifies, else the
        //    lowest qualifying alt setting.
        StreamFormat atPreferred = null;
        for (StreamFormat f : candidates) {
            if (!f.supportsRate(preferredRate)) continue;
            if (f.altSetting == currentAltSetting) {
                atPreferred = f;
                break;
            }
            if (atPreferred == null || f.altSetting < atPreferred.altSetting) {
                atPreferred = f;
            }
        }
        if (atPreferred != null) {
            return new Choice(atPreferred.altSetting, preferredRate, atPreferred.channels);
        }

        // 2) Fallback: best rate the device really supports.
        StreamFormat bestFormat = null;
        int bestRate = -1;
        long bestKey = Long.MIN_VALUE;
        for (StreamFormat f : candidates) {
            int r = f.bestRateFor(preferredRate, decoderRate);
            if (r <= 0) continue;
            long key = rateKey(r, preferredRate, decoderRate, f.altSetting == currentAltSetting);
            if (key > bestKey) {
                bestKey = key;
                bestFormat = f;
                bestRate = r;
            }
        }
        if (bestFormat == null) return null;
        return new Choice(bestFormat.altSetting, bestRate, bestFormat.channels);
    }

    // Ranking for fallback rates; larger is better. Bit 62: rate can feed the decoder at all.
    // Bit 61: integer multiple of the decoder rate (FirDecimator fast path, no rational
    // stage). Then proximity to the preferred rate, then a current-alt tiebreak (avoid an
    // alt-setting switch when it buys nothing).
    private static long rateKey(int rate, int preferredRate, int decoderRate,
                                boolean isCurrentAlt) {
        long key = 0;
        if (rate >= decoderRate) key |= 1L << 62;
        if (decoderRate > 0 && rate % decoderRate == 0) key |= 1L << 61;
        key += ((long) Integer.MAX_VALUE - Math.abs((long) rate - (long) preferredRate)) << 1;
        if (isCurrentAlt) key += 1;
        return key;
    }

    private static int u24(byte[] raw, int off) {
        return (raw[off] & 0xFF) | ((raw[off + 1] & 0xFF) << 8) | ((raw[off + 2] & 0xFF) << 16);
    }
}
