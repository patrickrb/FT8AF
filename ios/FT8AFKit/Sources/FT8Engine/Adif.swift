import Foundation

/// ADIF import/export for the QSO log. `export` is a byte-for-byte port of desktop
/// db.rs `export_adif` (same header, field set, order, and the fixed QSL flag),
/// so logs round-trip identically across the iOS app, the desktop port, and
/// ShareLogs. `parse` is the inverse (new on iOS — users import existing logs),
/// a lenient length-prefixed `<field:len>value` tokenizer.
public enum Adif {

    // MARK: Export

    /// Render `records` as an ADIF string in the given order. Matches desktop
    /// db.rs export_adif exactly: a fixed header, then per record the CALL field,
    /// `QSL_RCVD` (iOS has no confirmation state yet, so always `N`), every
    /// non-empty optional field, and comment + <eor>.
    public static func export(_ records: [QsoRecord]) -> String {
        var out = "FT8AF ADIF Export<eoh>\n"
        for r in records {
            out += field("call", r.call)
            out += "<QSL_RCVD:1>N "
            opt(&out, "gridsquare", r.gridsquare)
            opt(&out, "mode", r.mode)
            opt(&out, "rst_sent", r.rstSent)
            opt(&out, "rst_rcvd", r.rstRcvd)
            opt(&out, "qso_date", r.qsoDate)
            opt(&out, "time_on", r.timeOn)
            opt(&out, "qso_date_off", r.qsoDateOff)
            opt(&out, "time_off", r.timeOff)
            opt(&out, "band", r.band)
            opt(&out, "freq", r.freq)
            opt(&out, "station_callsign", r.stationCallsign)
            opt(&out, "my_gridsquare", r.myGridsquare)
            // comment is always emitted (even when empty), as in the desktop.
            out += "<comment:\(r.comment.utf8.count)>\(r.comment) <eor>\n"
        }
        return out
    }

    /// `<name:byteLen>value ` (length is UTF-8 bytes, matching Rust `str::len`).
    private static func field(_ name: String, _ value: String) -> String {
        "<\(name):\(value.utf8.count)>\(value) "
    }

    private static func opt(_ out: inout String, _ name: String, _ value: String) {
        if !value.isEmpty { out += field(name, value) }
    }

    // MARK: Import

    /// Parse an ADIF document into records. Length-prefixed parsing on the UTF-8
    /// bytes (so a value may itself contain `<`/`>`); field names are matched
    /// case-insensitively; header fields (before `<eoh>`) and unknown fields are
    /// ignored. A record ends at `<eor>`; a trailing record without one is still
    /// accepted (lenient import).
    public static func parse(_ adif: String) -> [QsoRecord] {
        let bytes = Array(adif.utf8)
        let n = bytes.count
        let lt = UInt8(ascii: "<")
        let gtByte = UInt8(ascii: ">")

        var i = 0
        var records: [QsoRecord] = []
        var current: [String: String] = [:]

        while i < n {
            // Advance to the next tag.
            guard let open = nextIndex(of: lt, in: bytes, from: i) else { break }
            // Find the closing '>'.
            guard let close = nextIndex(of: gtByte, in: bytes, from: open + 1) else { break }
            let tag = String(decoding: bytes[(open + 1)..<close], as: UTF8.self)
            i = close + 1

            let lower = tag.lowercased()
            if lower == "eoh" {
                current = [:] // discard any header fields seen so far
                continue
            }
            if lower == "eor" {
                appendIfValid(current, to: &records)
                current = [:]
                continue
            }

            // name:len[:type]
            let parts = tag.split(separator: ":", omittingEmptySubsequences: false)
            // `len` comes from an untrusted length prefix, so compare against the
            // remaining byte count as `len <= n - i` rather than `i + len <= n`:
            // the latter traps on 64-bit signed overflow for a length near Int.max
            // (crashing the whole app on import of a corrupt/hostile .adi) before
            // the bound is even checked. `i <= n` always holds here (i = close + 1,
            // close < n), so `n - i` never underflows.
            guard parts.count >= 2, let len = Int(parts[1]), len >= 0, len <= n - i else {
                continue // skip a malformed/over-long field rather than mis-read
            }
            let name = parts[0].lowercased()
            current[name] = String(decoding: bytes[i..<(i + len)], as: UTF8.self)
            i += len
        }
        appendIfValid(current, to: &records) // trailing record w/o <eor>
        return records
    }

    /// Append a parsed record only if it has a non-empty CALL. A record with just
    /// ignored fields (QSL flags, unknown tags) is not a valid QSO and is dropped
    /// so downstream persistence/UI never sees an empty-call log entry.
    private static func appendIfValid(_ fields: [String: String], to records: inout [QsoRecord]) {
        guard let call = fields["call"], !call.isEmpty else { return }
        records.append(build(fields))
    }

    private static func nextIndex(of byte: UInt8, in bytes: [UInt8], from: Int) -> Int? {
        var j = from
        while j < bytes.count {
            if bytes[j] == byte { return j }
            j += 1
        }
        return nil
    }

    private static func build(_ d: [String: String]) -> QsoRecord {
        func g(_ k: String) -> String { d[k] ?? "" }
        return QsoRecord(
            id: nil,
            call: g("call"),
            gridsquare: g("gridsquare"),
            mode: g("mode"),
            rstSent: g("rst_sent"),
            rstRcvd: g("rst_rcvd"),
            qsoDate: g("qso_date"),
            timeOn: g("time_on"),
            qsoDateOff: g("qso_date_off"),
            timeOff: g("time_off"),
            band: g("band"),
            freq: g("freq"),
            stationCallsign: g("station_callsign"),
            myGridsquare: g("my_gridsquare"),
            comment: g("comment")
        )
    }
}
