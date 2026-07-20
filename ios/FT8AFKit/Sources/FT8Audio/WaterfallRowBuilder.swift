import Foundation

/// One row of the scrolling waterfall: per-column brightness (0...255) plus the
/// Hz width each column spans.
public struct WaterfallRow: Equatable {
    public let bins: [UInt8]
    public let hzPerCol: Float
    public init(bins: [UInt8], hzPerCol: Float) {
        self.bins = bins
        self.hzPerCol = hzPerCol
    }
}

/// Turns an averaged power spectrum into a colorized waterfall row — the
/// platform-neutral, testable core of `WaterfallView` (per the project rule of
/// extracting `DrawScope`/decision logic into a plain type). Port of the
/// dB-magnitude → percentile-noise-floor → black-point mapping in desktop
/// engine.rs `emit_waterfall_row`.
///
/// The FFT itself (Welch-averaging `segments` overlapping Hann-windowed FFTs)
/// stays in the engine / vDSP and is **not** part of this type: callers hand in
/// `summedPower` (the sum over `segments` of |FFT bin|² for each displayed bin).
/// This keeps the brightness/geometry logic host-testable with synthetic spectra.
public struct WaterfallRowBuilder {
    // Tunables — match the WF_* constants in desktop engine.rs.
    public static let fftSize = 2048              // ~5.86 Hz/bin at 12 kHz
    public static let segments = 6                // WF_AVG (Welch segments)
    public static let step = fftSize / 2          // WF_STEP, 50% overlap
    /// Default displayed top of the audio band (desktop `WF_MAX_HZ`). The
    /// operator's spectrum-width setting overrides it per call via the `maxHz`
    /// parameter of `columns(sampleRate:maxHz:)` — display-only; the FT8
    /// decoder's range is unaffected.
    public static let defaultMaxHz: Float = 3500
    public static let spanDb: Float = 26          // dB above black that maps to full brightness
    public static let blackOffsetDb: Float = 4    // push black above the noise floor
    public static let noiseFloorPercentile: Float = 0.30
    public static let floorSeedDb: Float = -60    // WfProcessor::new seeds the floor here
    private static let floorEmaUp: Float = 0.9    // previous-floor weight
    private static let floorEmaNew: Float = 0.1   // new-sample weight

    /// Samples the engine must `peekRecent` to build one row.
    public static func samplesNeeded() -> Int { fftSize + (segments - 1) * step }

    /// Hz per FFT bin at a given sample rate.
    public static func binHz(sampleRate: Int) -> Float { Float(sampleRate) / Float(fftSize) }

    /// Number of displayed columns: bins up to `maxHz` (the operator's spectrum
    /// width; defaults to `defaultMaxHz`), capped at the Nyquist half.
    public static func columns(sampleRate: Int, maxHz: Float = WaterfallRowBuilder.defaultMaxHz) -> Int {
        let bh = binHz(sampleRate: sampleRate)
        guard bh > 0 else { return 1 }
        return min(fftSize / 2, max(1, Int(maxHz / bh)))
    }

    /// EMA state of the noise floor (dB). Seeded at -60 dB like the engine
    /// (desktop `wf.rs` `WfProcessor::new`), then reconverges onto the real floor
    /// within a couple of seconds. Seeding at 0 dB instead pins the black point ~4
    /// dB — far above a real -40…-80 dB floor — so every signal renders black until
    /// the EMA crawls down, painting the first seconds of each waterfall dark.
    public private(set) var floorDb: Float

    public init(floorDb: Float = WaterfallRowBuilder.floorSeedDb) { self.floorDb = floorDb }

    /// Build one brightness row from the summed power spectrum. `summedPower[i]`
    /// is Σ over `segments` of |FFT bin i|² (length == displayed columns). Updates
    /// the noise-floor EMA as a side effect.
    public mutating func buildRow(summedPower: [Float], sampleRate: Int) -> WaterfallRow {
        let cols = summedPower.count
        let hzPerCol = Self.binHz(sampleRate: sampleRate)
        guard cols > 0 else { return WaterfallRow(bins: [], hzPerCol: hzPerCol) }

        // Averaged power -> dB magnitude per bin.
        var dbs = [Float](repeating: 0, count: cols)
        for i in 0..<cols {
            let avg = summedPower[i] / Float(Self.segments)
            let mag = (avg.squareRoot() * 2.0) / Float(Self.fftSize)
            dbs[i] = 20.0 * log10(mag + 1e-12)
        }

        // Noise floor from the 30th-percentile dB (robust on a busy band),
        // smoothed over time.
        let sorted = dbs.sorted()
        let idx = min(Int(Float(cols) * Self.noiseFloorPercentile), cols - 1)
        floorDb = floorDb * Self.floorEmaUp + sorted[idx] * Self.floorEmaNew

        // Black point a few dB above the floor so noise renders dark; only signals
        // above it take on color.
        let blackPoint = floorDb + Self.blackOffsetDb
        var bins = [UInt8](repeating: 0, count: cols)
        for i in 0..<cols {
            let t = min(max((dbs[i] - blackPoint) / Self.spanDb, 0), 1)
            bins[i] = UInt8(t * 255.0)
        }
        return WaterfallRow(bins: bins, hzPerCol: hzPerCol)
    }
}
