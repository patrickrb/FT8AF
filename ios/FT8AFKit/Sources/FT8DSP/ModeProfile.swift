import Foundation

/// The operating modes the iOS app can select at runtime. The iOS analog of
/// Android's `FT8Common.*_MODE` ids + `ModeProfile` enum.
///
/// FT2 is intentionally absent: its `*Ft2` native decode entry points are not
/// exposed in the iOS `CFT8` shim (only `FTX_PROTOCOL_FT8` / `FTX_PROTOCOL_FT4`
/// reach Swift), so a faithful FT2 profile can't be decoded here. It is deferred
/// rather than faked — see the port notes on the C surface.
public enum Mode: String, CaseIterable, Identifiable, Sendable {
    case ft8 = "FT8"
    case ft4 = "FT4"

    public var id: String { rawValue }

    /// The timing/codec descriptor for this mode.
    public var profile: ModeProfile {
        switch self {
        case .ft8: return .ft8
        case .ft4: return .ft4
        }
    }
}

/// Per-mode timing, symbol, and synthesis parameters — the iOS port of Android
/// `com.k1af.ft8af.ModeProfile`. The rest of the app stays mode-agnostic by
/// reading the active descriptor (from `SettingsState.mode`) instead of
/// hard-coding the 15 s / 12.64 s / 79-tone FT8 assumptions.
///
/// Constants match Android's `ModeProfile` exactly (FT8 15 s / FT4 7.5 s). The
/// derived `waveformMs`, `slackMs`, and `slotSamples` use the same formulas as
/// Android's `audioMillis` / `audioSlackMillis` so the two frontends agree
/// byte-for-byte on slot geometry.
public struct ModeProfile: Equatable, Sendable {
    /// Which mode this describes.
    public let mode: Mode
    /// Human/protocol-facing name ("FT8"/"FT4") — logs, PSKReporter, ADIF, the
    /// WSJT-X UDP `mode`/`txMode` fields.
    public let displayName: String
    /// TX/RX cycle length in ms (15000 FT8, 7500 FT4); the slot grid the
    /// `SlotClock` fires boundaries on.
    public let cycleMs: Int64
    /// Shorter RX capture window used when early/fast decode is on (13500 FT8,
    /// 6500 FT4). Always exceeds `waveformMs`, so an on-time signal is never
    /// clipped by the early window.
    public let earlyDecodeMillis: Int64
    /// GFSK symbol period in seconds (0.160 FT8, 0.048 FT4).
    public let symbolPeriod: Float
    /// Gaussian BT product for GFSK (2.0 FT8, 1.0 FT4).
    public let symbolBT: Float
    /// Number of channel symbols/tones (79 FT8, 105 FT4).
    public let numTones: Int
    /// Whether the native decoder/encoder should use the FT8 protocol
    /// (`FTX_PROTOCOL_FT8`); false selects FT4.
    public let isFT8: Bool
    /// Codec sample rate (12 kHz for both modes; the native lib is fixed here).
    public let sampleRate: Int32

    /// Audio waveform length in ms: `round(numTones * symbolPeriod * 1000)`
    /// (12640 FT8, 5040 FT4). Mirror of Android `audioMillis`.
    public let waveformMs: Int64
    /// Slack between the waveform end and the slot boundary
    /// (`cycleMs - waveformMs`): 2360 FT8, 2460 FT4. Drives the late-start clip
    /// math in transmit so a normal on-time TX is never clipped. Mirror of
    /// Android `audioSlackMillis`.
    public let slackMs: Int64
    /// Samples in one full slot at `sampleRate` (`sampleRate * cycleMs / 1000`):
    /// 180000 FT8, 90000 FT4.
    public let slotSamples: Int

    private init(
        mode: Mode, cycleMs: Int64, earlyDecodeMillis: Int64,
        symbolPeriod: Float, symbolBT: Float, numTones: Int, isFT8: Bool,
        sampleRate: Int32 = FT8.sampleRate
    ) {
        self.mode = mode
        self.displayName = mode.rawValue
        self.cycleMs = cycleMs
        self.earlyDecodeMillis = earlyDecodeMillis
        self.symbolPeriod = symbolPeriod
        self.symbolBT = symbolBT
        self.numTones = numTones
        self.isFT8 = isFT8
        self.sampleRate = sampleRate
        // Compute in Double then round, matching Android `Math.round(...)`.
        self.waveformMs = Int64((Double(numTones) * Double(symbolPeriod) * 1000.0).rounded())
        self.slackMs = cycleMs - Int64((Double(numTones) * Double(symbolPeriod) * 1000.0).rounded())
        self.slotSamples = Int(sampleRate) * Int(cycleMs) / 1000
    }

    /// Wall-clock budget for the deep-decode subtract-and-redecode loop in ms.
    /// Scales with the slot (0.75x) with a 2.5 s floor — the iOS twin of
    /// Android `ModeProfile.deepDecodeBudgetMillis()` (11250 FT8, 5625 FT4).
    public var deepDecodeBudgetMillis: Int64 {
        max(2500, Int64((Double(cycleMs) * 0.75).rounded()))
    }

    /// The WSJT-X UDP `TR Period` in whole seconds (`cycleMs / 1000`): 15 FT8,
    /// 7 FT4. GridTracker/JTAlert key their period grid off this + the mode name.
    public var trPeriodSeconds: UInt32 {
        UInt32(cycleMs / 1000)
    }

    // MARK: - Profiles (exact Android constants)

    /// FT8: 15 s slot, 12.64 s / 79-tone waveform, 0.160 s symbols.
    public static let ft8 = ModeProfile(
        mode: .ft8, cycleMs: 15_000, earlyDecodeMillis: 13_500,
        symbolPeriod: 0.160, symbolBT: 2.0, numTones: 79, isFT8: true
    )

    /// FT4: 7.5 s slot, 5.04 s / 105-tone waveform, 0.048 s symbols.
    public static let ft4 = ModeProfile(
        mode: .ft4, cycleMs: 7_500, earlyDecodeMillis: 6_500,
        symbolPeriod: 0.048, symbolBT: 1.0, numTones: 105, isFT8: false
    )
}
