import Foundation

/// Pure timing math for TX playback scheduling.
///
/// The FT8 waveform is 12.64 s in a 15 s cycle, leaving 2.36 s of slack. When
/// playback starts late into the cycle we clip the *leading* audio so the
/// transmission still ends on the cycle boundary — but ONLY the portion past
/// the slack. Clipping anything on an on-time start (e.g. a naive
/// `msIntoCycle % 15000`) chops the leading Costas sync array and produces an
/// audible-but-undecodable signal (see project CLAUDE.md, "FT8 TX audio
/// pipeline" gotcha #2, fixed on Android in PR #93).
public enum TxTiming {
    /// Slack between the 12.64 s FT8 waveform and the 15 s cycle.
    public static let defaultSlackMs: Int64 = 15_000 - 12_640 // 2360

    /// Leading milliseconds to clip: `max(0, msIntoCycle - slack)`.
    /// An on-time start (anything within the slack) clips nothing.
    public static func lateStartClipMs(
        msIntoCycle: Int64,
        slackMs: Int64 = defaultSlackMs
    ) -> Int64 {
        max(0, msIntoCycle - max(0, slackMs))
    }

    /// Convert a clip duration to a leading sample count at `sampleRate`.
    public static func clipSampleCount(clipMs: Int64, sampleRate: Int) -> Int {
        max(0, Int(clipMs) * sampleRate / 1000)
    }
}
