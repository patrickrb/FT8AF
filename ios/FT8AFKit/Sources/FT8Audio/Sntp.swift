import Foundation

/// Pure SNTP (RFC 4330) packet build/parse, kept off the networking layer so
/// the byte math is unit-testable. The UDP transport that sends the request and
/// awaits the reply lives in the app (`SntpSyncService`), the same split as the
/// WSJT-X `WsjtxCodec` (pure) vs `WsjtxUdpService` (transport) pair.
///
/// Mirrors the offset math in Android's `UtcTimer.syncTime` /
/// `ntpClockOffsetMs`: query a time server, take its transmit timestamp, and
/// compute `reference - deviceNow` as the whole-clock correction.
public enum Sntp {
    /// A client-mode SNTP request is exactly 48 bytes.
    public static let packetSize = 48

    /// Seconds between the NTP epoch (1900-01-01) and the Unix epoch
    /// (1970-01-01). NTP timestamps count seconds since 1900.
    public static let ntpUnixEpochDeltaSec: Int64 = 2_208_988_800

    /// Build a minimal client request packet: LI = 0 (no warning), VN = 4,
    /// Mode = 3 (client) in the first byte, the rest zero.
    public static func makeRequest() -> [UInt8] {
        var packet = [UInt8](repeating: 0, count: packetSize)
        packet[0] = 0x23 // 00_100_011 : LI=0, VN=4, Mode=3 (client)
        return packet
    }

    /// Extract the server's Transmit Timestamp (bytes 40..47) from a response
    /// and convert it to Unix epoch milliseconds. Returns `nil` when the packet
    /// is too short, fails the header sanity checks, or carries a zero (unset /
    /// Kiss-o'-Death) timestamp.
    ///
    /// The header checks reject anything we can't trust as an authoritative
    /// server time — otherwise any 48-byte UDP payload with a nonzero seconds
    /// field would be accepted and could shift the RX/TX/QSO clock by years:
    ///  - LI (leap indicator) == 3 → the server is unsynchronized ("alarm").
    ///  - Mode != 4 → not a server-mode reply to our Mode-3 client request.
    ///  - Stratum 0 → a Kiss-o'-Death packet (no time); 16..255 are reserved.
    public static func transmitTimeUnixMs(fromResponse bytes: [UInt8]) -> Int64? {
        guard bytes.count >= packetSize else { return nil }
        let leapIndicator = (bytes[0] >> 6) & 0x3
        let mode = bytes[0] & 0x7
        let stratum = bytes[1]
        guard leapIndicator != 3, mode == 4, stratum >= 1, stratum <= 15 else { return nil }

        let seconds = beUInt32(bytes, at: 40)
        let fraction = beUInt32(bytes, at: 44)
        if seconds == 0 { return nil } // no valid timestamp in the reply

        let unixSec = Int64(seconds) - ntpUnixEpochDeltaSec
        // The 32-bit fraction is a binary fraction of one second.
        let fractionMs = Int64((Double(fraction) / 4_294_967_296.0) * 1_000.0)
        return unixSec * 1_000 + fractionMs
    }

    /// The whole-clock offset (ms) to add to the device clock so it matches the
    /// reference: `reference - deviceNow`. Positive means the device clock is
    /// behind the reference and must be shifted forward. The full offset is
    /// returned (not just its remainder within one FT8 cycle) so a device clock
    /// more than a cycle out is fully corrected — matching Android's
    /// `ntpClockOffsetMs`.
    public static func offsetMs(referenceUnixMs: Int64, deviceNowMs: Int64) -> Int64 {
        referenceUnixMs - deviceNowMs
    }

    /// The largest clock correction (ms) an SNTP reply may apply — one hour,
    /// mirroring Android's `NtpClockUpdater.MAX_SANE_OFFSET_MS`. A larger jump is
    /// treated as a bad/rogue response and dropped so a corrupt timestamp can't
    /// shove the RX/TX/QSO clock by an implausible amount.
    public static let maxSaneOffsetMs: Int64 = 60 * 60 * 1000

    /// Whether an offset is within the sane bound (`abs(offset) <= maxSaneOffsetMs`).
    /// Callers reject an out-of-bound offset instead of applying it.
    public static func isOffsetSane(_ offsetMs: Int64) -> Bool {
        abs(offsetMs) <= maxSaneOffsetMs
    }

    /// Read a big-endian (network byte order) 32-bit unsigned int at `offset`.
    static func beUInt32(_ bytes: [UInt8], at offset: Int) -> UInt32 {
        (UInt32(bytes[offset]) << 24)
            | (UInt32(bytes[offset + 1]) << 16)
            | (UInt32(bytes[offset + 2]) << 8)
            | UInt32(bytes[offset + 3])
    }
}
