import Foundation

/// One row from the live POTA spots feed (`https://api.pota.app/spot/activator`).
/// Field set mirrors Android `radio.ks3ckc.ft8af.pota.model.PotaSpot`: the feed's
/// `name` key is the park name, `frequency` is a *string* in kHz.
public struct PotaSpot: Equatable, Identifiable, Sendable {
    public var activator: String
    public var frequencyKhz: Double
    public var mode: String
    public var reference: String
    public var parkName: String
    public var locationDesc: String
    public var spotter: String
    /// Raw feed timestamp, e.g. "2026-07-13T19:44:50" (UTC, optional fractional
    /// seconds). Parsed by `PotaSpots.ageSeconds`.
    public var spotTimeUtc: String
    public var comments: String

    /// Same identity Android's hunt list keys rows by.
    public var id: String { "\(activator)|\(reference)|\(frequencyKhz)" }

    public var frequencyHz: Int64 { Int64(frequencyKhz * 1000) }

    /// Display frequency, matching Android's `"%.1f kHz"`.
    public var frequencyText: String { String(format: "%.1f kHz", frequencyKhz) }

    public var isFT8: Bool { mode.uppercased() == "FT8" }

    public init(
        activator: String, frequencyKhz: Double, mode: String, reference: String,
        parkName: String, locationDesc: String, spotter: String,
        spotTimeUtc: String, comments: String
    ) {
        self.activator = activator
        self.frequencyKhz = frequencyKhz
        self.mode = mode
        self.reference = reference
        self.parkName = parkName
        self.locationDesc = locationDesc
        self.spotter = spotter
        self.spotTimeUtc = spotTimeUtc
        self.comments = comments
    }
}

/// Pure decode + display logic for the POTA activator-spot feed. Network-free;
/// the app-side `PotaService` owns the URLSession fetch.
public enum PotaSpots {

    /// The public activator-spot feed (same endpoint Android's `PotaClient` polls).
    public static let activatorSpotsURLString = "https://api.pota.app/spot/activator"

    /// Decode the feed's JSON array. Tolerant the way Android's `optString`
    /// parsing is: missing/null fields become "", `frequency` may be a string
    /// ("14074", "14061.0") or a bare number, unparseable frequencies become 0,
    /// and the activator callsign is uppercased. Returns nil only when the
    /// payload isn't a JSON array (mirroring Android returning null on a parse
    /// error); malformed *elements* are skipped rather than failing the batch.
    public static func decode(_ data: Data) -> [PotaSpot]? {
        guard let root = try? JSONSerialization.jsonObject(with: data),
              let arr = root as? [Any] else { return nil }
        var out: [PotaSpot] = []
        out.reserveCapacity(arr.count)
        for element in arr {
            guard let o = element as? [String: Any] else { continue }
            out.append(PotaSpot(
                activator: str(o["activator"]).uppercased(),
                frequencyKhz: khz(o["frequency"]),
                mode: str(o["mode"]),
                reference: str(o["reference"]),
                parkName: str(o["name"]),
                locationDesc: str(o["locationDesc"]),
                spotter: str(o["spotter"]),
                spotTimeUtc: str(o["spotTime"]),
                comments: str(o["comments"])
            ))
        }
        return out
    }

    /// Hunt-list ordering + mode constraint: Android fetches with
    /// `modeFilter = "FT8"` (case-insensitive) and sorts ascending by frequency.
    /// The iOS toggle applies the same filter client-side so flipping it doesn't
    /// need a refetch.
    public static func forDisplay(_ spots: [PotaSpot], ft8Only: Bool) -> [PotaSpot] {
        let filtered = ft8Only ? spots.filter(\.isFT8) : spots
        return filtered.sorted { $0.frequencyKhz < $1.frequencyKhz }
    }

    /// Seconds elapsed since the spot's UTC timestamp, or nil when the timestamp
    /// doesn't parse. Accepts "yyyy-MM-dd'T'HH:mm:ss" with optional fractional
    /// seconds / trailing zone designator (only the first 19 chars are read).
    /// Negative ages (clock skew) clamp to 0. Feed the result to `relativeAge`.
    public static func ageSeconds(spotTimeUtc: String, now: Date) -> Int? {
        let trimmed = String(spotTimeUtc.prefix(19))
        guard trimmed.count == 19 else { return nil }
        guard let date = spotTimeFormatter.date(from: trimmed) else { return nil }
        return max(0, Int(now.timeIntervalSince(date)))
    }

    /// Shared UTC/POSIX parser for spot timestamps. Building a `DateFormatter` is
    /// expensive, and `ageSeconds` runs once per rendered spot row, so configure it
    /// once and reuse it. A configured `DateFormatter` is safe to share for parsing
    /// (only `date(from:)` is called; it is never mutated after this).
    private static let spotTimeFormatter: DateFormatter = {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.timeZone = TimeZone(identifier: "UTC")
        fmt.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return fmt
    }()

    /// Amateur band (in the app's "20M" naming) containing a spot frequency, or
    /// nil when it falls outside the HF/6m allocations the band picker offers.
    /// Ranges are the full band allocations, not just the FT8 dial, so a spot
    /// anywhere in-band still maps (the app then tunes the band's FT8 dial).
    public static func band(forFrequencyKhz khz: Double) -> String? {
        switch khz {
        case 1_800..<2_000:   return "160M"
        case 3_500..<4_000:   return "80M"
        case 5_250..<5_450:   return "60M"
        case 7_000..<7_300:   return "40M"
        case 10_100..<10_150: return "30M"
        case 14_000..<14_350: return "20M"
        case 18_068..<18_168: return "17M"
        case 21_000..<21_450: return "15M"
        case 24_890..<24_990: return "12M"
        case 28_000..<29_700: return "10M"
        case 50_000..<54_000: return "6M"
        default:              return nil
        }
    }

    // MARK: - Park-to-park activator lookup

    /// Normalize a decoded callsign into the spot-cache match key. Port of
    /// Android's `spotLookupKey`: strip the angle brackets FT8 non-standard /
    /// hashed calls come wrapped in (`<K1ABC/P>`, or `<...>` when unresolved),
    /// trim, and uppercase. Returns nil for an empty/whitespace-only result so
    /// callers can bail early.
    public static func spotLookupKey(_ callsign: String?) -> String? {
        guard let raw = callsign else { return nil }
        let key = raw
            .replacingOccurrences(of: "<", with: "")
            .replacingOccurrences(of: ">", with: "")
            .trimmingCharacters(in: .whitespaces)
            .uppercased()
        return key.isEmpty ? nil : key
    }

    /// The park reference `callsign` is currently spotted activating, or nil when
    /// they aren't a current activator. Port of Android
    /// `PotaSpotsRepository.parkRefFor`: match the live spots by uppercased
    /// activator call (after `spotLookupKey` normalization) and return their
    /// non-empty reference. Used at QSO-log time to fill SIG/SIG_INFO for a
    /// park-to-park contact.
    public static func parkRef(forCallsign callsign: String?, in spots: [PotaSpot]) -> String? {
        guard let key = spotLookupKey(callsign) else { return nil }
        let ref = spots.first { $0.activator.uppercased() == key }?.reference
        guard let ref, !ref.isEmpty else { return nil }
        return ref
    }

    // MARK: - Tolerant JSON field extraction

    private static func str(_ any: Any?) -> String {
        if let s = any as? String { return s }
        if any == nil || any is NSNull { return "" }
        if let n = any as? NSNumber { return n.stringValue }
        return ""
    }

    private static func khz(_ any: Any?) -> Double {
        if let s = any as? String { return Double(s) ?? 0 }
        if let n = any as? NSNumber { return n.doubleValue }
        return 0
    }
}
