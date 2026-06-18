import Foundation

/// Fixed FT8 codec parameters (mirror desktop dsp/mod.rs + dsp/encode.rs).
public enum FT8 {
    /// Sample rate the FT8 codec runs at internally (fixed).
    public static let sampleRate: Int32 = 12_000
    /// Total channel symbols in an FT8 frame (FT8_NN).
    public static let nn: Int = 79
    /// FT8 symbol duration in seconds.
    public static let symbolPeriod: Float = 0.160
    /// Gaussian BT product for FT8 GFSK.
    public static let symbolBT: Float = 2.0
    /// Samples in one 15 s FT8 slot at `sampleRate`.
    public static let slotSamples: Int = Int(sampleRate) * 15
}
