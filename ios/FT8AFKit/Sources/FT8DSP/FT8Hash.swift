import Foundation

/// WSJT-X 22-bit callsign hash (the value behind FT8Package.getHash10/12/22).
///
/// Faithful Swift port of `compute_n22` / `save_callsign` (ft8_lib message.c):
/// base-38 over an 11-char field via " 0123456789A..Z/", times the
/// 47055833459 magic constant, top 22 bits. h12 = n22>>10, h10 = n22>>12.
/// The multiply intentionally wraps mod 2^64 (`&*`) — it is a multiplicative hash.
public enum FT8Hash {
    private static let table = Array(" 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/".utf8)

    /// Full 22-bit hash.
    public static func n22(_ callsign: String) -> UInt32 {
        var n58: UInt64 = 0
        let chars = Array(callsign.uppercased().utf8)
        var i = 0
        while i < chars.count && i < 11 {
            let j = table.firstIndex(of: chars[i]) ?? 0
            n58 = 38 &* n58 &+ UInt64(j)
            i += 1
        }
        while i < 11 {           // pad to 11 chars with the space symbol (index 0)
            n58 = 38 &* n58
            i += 1
        }
        return UInt32((47055833459 &* n58) >> (64 - 22) & 0x3FFFFF)
    }

    /// 12-bit hash (n22 >> 10).
    public static func h12(_ callsign: String) -> UInt32 { n22(callsign) >> 10 }
    /// 10-bit hash (n22 >> 12).
    public static func h10(_ callsign: String) -> UInt32 { n22(callsign) >> 12 }
}
