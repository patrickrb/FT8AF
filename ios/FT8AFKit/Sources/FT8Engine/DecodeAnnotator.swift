import Foundation

/// Highlight category for a decode-list row, mirroring the Android
/// `resolveQsoStatus` priority order (highest first):
/// PENDING (to me) > NEW POTA > POTA > NEW DXCC > NEW ZONE > NEW STATE >
/// NEW GRID > NEW PREFIX > NEW BAND > WORKED > CQ.
///
/// SOTA is intentionally omitted: iOS has no SOTA data source, so there is
/// nothing to classify against (see the module notes / task deferral).
public enum DecodeHighlight: String, CaseIterable, Sendable {
    case pending
    case newPota
    case pota
    case newDxcc
    case newZone
    case newState
    case newGrid
    case newPrefix
    case newBand
    case worked
    case cq
}

/// User toggles gating the logbook-derived highlight categories
/// (Settings → Decode Filters → Highlights). PENDING, POTA and CQ are
/// always-on because they reflect live on-air state, not logbook history;
/// `newPota` only refines an already-shown POTA row into the rarer NEW POTA.
public struct HighlightToggles: Equatable, Sendable {
    public var newPota: Bool
    public var newDxcc: Bool
    public var newZone: Bool
    public var newState: Bool
    public var newGrid: Bool
    public var newPrefix: Bool
    public var newBand: Bool
    public var worked: Bool

    public init(
        newPota: Bool = true,
        newDxcc: Bool = true,
        newZone: Bool = true,
        newState: Bool = true,
        newGrid: Bool = true,
        newPrefix: Bool = true,
        newBand: Bool = true,
        worked: Bool = true
    ) {
        self.newPota = newPota
        self.newDxcc = newDxcc
        self.newZone = newZone
        self.newState = newState
        self.newGrid = newGrid
        self.newPrefix = newPrefix
        self.newBand = newBand
        self.worked = worked
    }
}

/// One logged QSO, reduced to the fields the annotator needs. `park` is the
/// worked station's POTA park reference (ADIF `SIG_INFO`, from a park-to-park
/// hunt), used to tell an already-hunted park from a NEW POTA one; empty for a
/// non-POTA QSO.
public struct LoggedQso: Equatable, Sendable {
    public let call: String
    public let grid: String
    public let band: String
    public let park: String

    public init(call: String, grid: String, band: String, park: String = "") {
        self.call = call
        self.grid = grid
        self.band = band
        self.park = park
    }
}

/// Pre-computed lookup index over the logbook, built once per render pass so
/// per-row classification is O(1). All keys are uppercased; grids are
/// truncated to their 4-char square.
public struct LogbookIndex: Sendable {
    public let workedCalls: Set<String>
    public let workedGrids: Set<String>
    public let bandsByCall: [String: Set<String>]
    public let workedEntities: Set<String>
    /// USPS state codes resolved from each logged QSO's grid (US-only, via
    /// `UsStateLookup`). Mirrors Android's `GeneralVariables.workedStates`: a
    /// decode from a state absent here is a "new state" for WAS chasing.
    public let workedStates: Set<String>
    /// CQ zones (WAZ, 1–40) resolved from each logged QSO's callsign via
    /// `DxccPrefix.cqZone`. A decode from a zone absent here is a "new zone"
    /// for Worked-All-Zones chasing. Mirrors Android's decode-time `fromCq`.
    public let workedZones: Set<Int>
    /// CQ WPX prefixes (e.g. "W1", "DL4") derived from each logged QSO's
    /// callsign via `wpxPrefix`. A decode whose prefix is absent here is a
    /// "new prefix" for Worked-All-Prefixes chasing. Mirrors Android's
    /// worked-prefix set (`checkQSLPrefix`).
    public let workedPrefixes: Set<String>
    /// POTA park references already hunted (uppercased `SIG_INFO`). A spotted
    /// activator whose park is absent here is a NEW POTA. Mirrors Android's
    /// `checkQSLPark`.
    public let workedParks: Set<String>

    public init(qsos: [LoggedQso]) {
        var calls = Set<String>()
        var grids = Set<String>()
        var bands = [String: Set<String>]()
        var entities = Set<String>()
        var states = Set<String>()
        var zones = Set<Int>()
        var prefixes = Set<String>()
        var parks = Set<String>()
        for qso in qsos {
            let call = qso.call.uppercased()
            guard !call.isEmpty else { continue }
            calls.insert(call)
            let grid = grid4(qso.grid.uppercased())
            if grid.count >= 4 { grids.insert(grid) }
            let band = qso.band.uppercased()
            if !band.isEmpty { bands[call, default: []].insert(band) }
            if let entity = DxccPrefix.entity(for: call) {
                entities.insert(entity.name)
                if entity.cqZone > 0 { zones.insert(entity.cqZone) }
            }
            if let prefix = wpxPrefix(call) { prefixes.insert(prefix) }
            if let state = UsStateLookup.state(forGrid: qso.grid) {
                states.insert(state.uppercased())
            }
            let park = qso.park.trimmingCharacters(in: .whitespaces).uppercased()
            if !park.isEmpty { parks.insert(park) }
        }
        workedCalls = calls
        workedGrids = grids
        bandsByCall = bands
        workedEntities = entities
        workedStates = states
        workedZones = zones
        workedPrefixes = prefixes
        workedParks = parks
    }

    public static let empty = LogbookIndex(qsos: [])
}

/// True when a decode's destination field is a CQ (plain or with modifier,
/// e.g. "CQ", "CQ POTA", "CQ DX").
public func isCQMessage(callTo: String) -> Bool {
    let to = callTo.trimmingCharacters(in: .whitespaces).uppercased()
    return to == "CQ" || to.hasPrefix("CQ ")
}

/// True when a decode is directed at the operator's own callsign.
public func isDirectedToMe(callTo: String, myCall: String) -> Bool {
    let my = myCall.trimmingCharacters(in: .whitespaces).uppercased()
    guard !my.isEmpty else { return false }
    return callTo.trimmingCharacters(in: .whitespaces).uppercased() == my
}

/// True when a CQ looks like a POTA activation: explicit "CQ POTA" modifier,
/// or a park reference (e.g. "K-1234", "VE-5082") in the free-text/extra field.
public func isPotaCq(callTo: String, extra: String) -> Bool {
    guard isCQMessage(callTo: callTo) else { return false }
    let to = callTo.trimmingCharacters(in: .whitespaces).uppercased()
    if to == "CQ POTA" || to.hasPrefix("CQ POTA ") { return true }
    return looksLikeParkRef(extra)
}

/// True when a token matches the POTA park-reference shape: 1–4 alphanumeric
/// program characters, a dash, then 4–5 digits (K-1234, VE-5082, DL-03000).
public func looksLikeParkRef(_ token: String) -> Bool {
    let t = token.trimmingCharacters(in: .whitespaces).uppercased()
    guard let dash = t.firstIndex(of: "-") else { return false }
    let program = t[t.startIndex..<dash]
    let number = t[t.index(after: dash)...]
    guard (1...4).contains(program.count), (4...5).contains(number.count) else { return false }
    return program.allSatisfy { $0.isLetter || $0.isNumber } && number.allSatisfy(\.isNumber)
}

/// Extract the CQ WPX prefix from a callsign (e.g. "W1AW" → "W1",
/// "VE3XYZ" → "VE3", "9A1AA" → "9A1", "DL/W1AW" → "DL0"), or nil when no
/// plausible prefix can be determined (grids, signal reports, bare partial
/// tokens, hashed calls). Pure port of Android `com.k1af.ft8af.callsign.WpxPrefix`
/// so the NEW PREFIX pill and the "New Prefix" filter agree with the log
/// loader on what counts as a prefix.
public func wpxPrefix(_ raw: String) -> String? {
    let call = raw.trimmingCharacters(in: .whitespaces).uppercased()
    guard !call.isEmpty else { return nil }
    if !call.contains("/") { return wpxSimple(call) }
    return wpxCompound(call)
}

private func wpxIsAlnum(_ s: String) -> Bool {
    s.allSatisfy { ($0 >= "A" && $0 <= "Z") || ($0 >= "0" && $0 <= "9") }
}

private func wpxIsAllDigits(_ s: String) -> Bool {
    !s.isEmpty && s.allSatisfy { $0 >= "0" && $0 <= "9" }
}

private func wpxContainsLetter(_ s: String) -> Bool {
    s.contains { $0 >= "A" && $0 <= "Z" }
}

private func wpxIsIgnoredSuffix(_ s: String) -> Bool {
    switch s {
    case "P", "M", "MM", "AM", "QRP", "QRPP": return true
    default: return false
    }
}

/// Prefix of a plain (non-slashed) callsign, or nil if implausible.
private func wpxSimple(_ call: String) -> String? {
    guard wpxIsAlnum(call) else { return nil }
    let chars = Array(call)
    var lastDigit = -1
    var hasLetter = false
    for (i, c) in chars.enumerated() {
        if c >= "0" && c <= "9" { lastDigit = i } else { hasLetter = true }
    }
    guard hasLetter else { return nil }  // all-digit token — not a call
    if lastDigit < 0 {
        // No numeral at all (historic call like RAEM): first two letters + 0.
        return chars.count >= 2 ? String(chars[0...1]) + "0" : nil
    }
    // A full callsign carries a letter suffix after its prefix numeral;
    // requiring one keeps bare prefixes ("W1") and grids ("FN42") out.
    if lastDigit == chars.count - 1 { return nil }
    let prefix = String(chars[0...lastDigit])
    return wpxContainsLetter(prefix) ? prefix : nil
}

/// Prefix of a compound (slashed) callsign, or nil if indeterminate.
private func wpxCompound(_ call: String) -> String? {
    let parts = call.split(separator: "/").map(String.init)
        .filter { !$0.isEmpty && !wpxIsIgnoredSuffix($0) }
    guard !parts.isEmpty else { return nil }
    if parts.count == 1 { return wpxSimple(parts[0]) }

    // Portable number CALL/n → the base call's own prefix with its numeral
    // swapped for the new one.
    if let numeric = parts.last(where: { wpxIsAllDigits($0) && $0.count <= 2 }) {
        for p in parts where !wpxIsAllDigits(p) {
            guard let base = wpxSimple(p) else { return nil }
            return wpxWithPortableNumber(base, numeric)
        }
        return nil
    }

    // Portable prefix pfx/CALL. The designator is conventionally the shorter
    // token (e.g. "DL" in DL/W1AW, "KH6" in W1AW/KH6).
    let a = parts[0], b = parts[1]
    let designator = a.count <= b.count ? a : b
    guard wpxIsAlnum(designator), wpxContainsLetter(designator) else { return nil }
    let dchars = Array(designator)
    var lastDigit = -1
    for (i, c) in dchars.enumerated() where c >= "0" && c <= "9" { lastDigit = i }
    if lastDigit >= 0 { return String(dchars[0...lastDigit]) }
    // Designator without a numeral (bare prefix like "DL"): first two letters + 0.
    return dchars.count >= 2 ? String(dchars[0...1]) + "0" : designator + "0"
}

/// Swap the trailing numerals of an already-derived prefix for the portable
/// number, e.g. ("9A1","7") → "9A7", ("W1","4") → "W4".
private func wpxWithPortableNumber(_ base: String, _ number: String) -> String? {
    var end = base.endIndex
    while end > base.startIndex {
        let prev = base.index(before: end)
        if base[prev] >= "0" && base[prev] <= "9" { end = prev } else { break }
    }
    let letters = String(base[base.startIndex..<end])
    return letters.isEmpty ? nil : letters + number
}

/// The POTA park reference carried by a POTA CQ, if one is present and
/// well-formed — either the free-text/extra token ("K-1234") or a park ref
/// spelled out in the destination ("CQ POTA K-1234"). Returns nil for a POTA
/// CQ with no explicit reference (a bare "CQ POTA"), so those stay plain POTA.
public func potaParkRef(callTo: String, extra: String) -> String? {
    guard isPotaCq(callTo: callTo, extra: extra) else { return nil }
    let e = extra.trimmingCharacters(in: .whitespaces).uppercased()
    if looksLikeParkRef(e) { return e }
    for token in callTo.uppercased().split(separator: " ").map(String.init)
    where looksLikeParkRef(token) {
        return token
    }
    return nil
}

/// Classify a decode against the logbook, returning the highest-priority
/// highlight or nil when there is nothing to surface (a station mid-QSO with
/// someone else that is not new in any dimension).
///
/// Mirrors Android `resolveQsoStatus`:
/// - `newGrid` requires a valid ≥4-char locator not yet in the worked-grid set.
/// - `newBand` means worked before, but never on the current band.
/// - `worked` (any band) is checked after the "new" categories so a worked
///   station that is still a new grid shows the more actionable pill.
public func classifyDecode(
    callFrom: String,
    callTo: String,
    grid: String,
    extra: String,
    myCall: String,
    band: String,
    index: LogbookIndex,
    toggles: HighlightToggles
) -> DecodeHighlight? {
    let call = callFrom.trimmingCharacters(in: .whitespaces).uppercased()
    let cq = isCQMessage(callTo: callTo)

    if isDirectedToMe(callTo: callTo, myCall: myCall) { return .pending }

    // POTA activator. When we can pin the park ref and it isn't already hunted,
    // surface the rarer NEW POTA; otherwise a plain POTA. (Android order: the
    // POTA block sits just under PENDING, above every logbook "new" category.)
    if isPotaCq(callTo: callTo, extra: extra) {
        if toggles.newPota,
           let park = potaParkRef(callTo: callTo, extra: extra),
           !index.workedParks.contains(park) {
            return .newPota
        }
        return .pota
    }

    if toggles.newDxcc,
       let entity = DxccPrefix.entity(for: call),
       !index.workedEntities.contains(entity.name) {
        return .newDxcc
    }

    // A new CQ zone (Worked All Zones) outranks a new state/grid: only 40 zones
    // exist, so an unworked one is a rarer, more prized catch. Ranks just below
    // NEW DXCC, matching Android's `fromCq` slot.
    if toggles.newZone,
       let zone = DxccPrefix.cqZone(for: call), zone > 0,
       !index.workedZones.contains(zone) {
        return .newZone
    }

    // A new US state (Worked All States) outranks a new grid: WAS is one of the
    // most-chased US awards, so an unworked state is more prized than a bare new
    // grid field. Only US grids resolve (the table is US-only).
    if toggles.newState,
       let state = UsStateLookup.state(forGrid: grid),
       !index.workedStates.contains(state.uppercased()) {
        return .newState
    }

    let square = grid4(grid.uppercased())
    if toggles.newGrid,
       square.count >= 4,
       gridToLatLon(square) != nil,
       !index.workedGrids.contains(square) {
        return .newGrid
    }

    // A new WPX prefix (Worked All Prefixes) ranks just below a new grid: both
    // are common early on, so they sit under the rarer DXCC/zone/state catches.
    if toggles.newPrefix,
       let prefix = wpxPrefix(call),
       !index.workedPrefixes.contains(prefix) {
        return .newPrefix
    }

    let workedBands = index.bandsByCall[call] ?? []
    let workedThisBand = workedBands.contains(band.uppercased())
    if toggles.newBand, !workedBands.isEmpty, !workedThisBand {
        return .newBand
    }

    if toggles.worked, index.workedCalls.contains(call) {
        return .worked
    }

    if cq { return .cq }
    return nil
}
