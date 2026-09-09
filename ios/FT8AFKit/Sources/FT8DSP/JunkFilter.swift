import Foundation

/// CRC-collision / false-decode rejection, ported from Android
/// `Ft8Message.isPlausibleCallsign` / `isJunkDecode`.
///
/// FT8 guards each 77-bit frame with only a 14-bit CRC, so roughly one noise
/// event in 16k slips through as a "valid" decode. When that garbage lands in a
/// callsign-bearing frame, the corrupt sender field renders as junk like
/// `.<. >`. These pure predicates let the decode path drop such frames before
/// they reach the UI/log.

/// Whether a callsign field could be a real call.
///
/// A real field uses only the `[A-Z0-9/]` alphabet, optionally wrapped in a
/// single `<...>` (a hashed / nonstandard call), or is the bare `<...>`
/// placeholder shown before a hash resolves. Anything else — stray angle
/// brackets, embedded spaces, dots — is the junk a CRC-collision false decode
/// renders into the sender field.
func isPlausibleCallsign(_ callsign: String) -> Bool {
    var s = callsign.trimmingCharacters(in: .whitespacesAndNewlines)
    if s.isEmpty { return false }
    if s == "<...>" { return true } // unresolved hashed-call placeholder
    // Strip a single matching <...> wrapper around a hashed / nonstandard call.
    if s.count > 2, s.hasPrefix("<"), s.hasSuffix(">") {
        s = String(s.dropFirst().dropLast())
    }
    // Only the call alphabet may remain — no leftover brackets, spaces, dots.
    // Manual scan keeps this off the regex compiler on the decode hot-path.
    for c in s.utf8 {
        let inAlphabet = (c >= UInt8(ascii: "A") && c <= UInt8(ascii: "Z"))
            || (c >= UInt8(ascii: "0") && c <= UInt8(ascii: "9"))
            || c == UInt8(ascii: "/")
        if !inAlphabet { return false }
    }
    return true
}

/// Whether a decode is CRC-collision garbage that should be dropped.
///
/// A structured decode is junk when its sender isn't a plausible callsign. Free
/// text (`i3=0, n3=0`) and telemetry (`i3=0, n3=5`) legitimately carry
/// non-callsign text in that field, so they are never treated as junk.
func isJunkDecode(i3: UInt8, n3: UInt8, callFrom: String) -> Bool {
    let freeText = (i3 == 0 && n3 == 0)
    let telemetry = (i3 == 0 && n3 == 5)
    if freeText || telemetry { return false }
    return !isPlausibleCallsign(callFrom)
}
