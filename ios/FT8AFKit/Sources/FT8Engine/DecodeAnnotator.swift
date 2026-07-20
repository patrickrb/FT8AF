import Foundation

/// Highlight category for a decode-list row, mirroring the Android
/// `resolveQsoStatus` priority order (highest first):
/// PENDING (to me) > POTA > NEW DXCC > NEW GRID > NEW BAND > WORKED > CQ.
public enum DecodeHighlight: String, CaseIterable, Sendable {
    case pending
    case pota
    case newDxcc
    case newGrid
    case newBand
    case worked
    case cq
}

/// User toggles gating the logbook-derived highlight categories
/// (Settings → Decode Filters → Highlights). PENDING, POTA and CQ are
/// always-on because they reflect live on-air state, not logbook history.
public struct HighlightToggles: Equatable, Sendable {
    public var newDxcc: Bool
    public var newGrid: Bool
    public var newBand: Bool
    public var worked: Bool

    public init(newDxcc: Bool = true, newGrid: Bool = true, newBand: Bool = true, worked: Bool = true) {
        self.newDxcc = newDxcc
        self.newGrid = newGrid
        self.newBand = newBand
        self.worked = worked
    }
}

/// One logged QSO, reduced to the fields the annotator needs.
public struct LoggedQso: Equatable, Sendable {
    public let call: String
    public let grid: String
    public let band: String

    public init(call: String, grid: String, band: String) {
        self.call = call
        self.grid = grid
        self.band = band
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

    public init(qsos: [LoggedQso]) {
        var calls = Set<String>()
        var grids = Set<String>()
        var bands = [String: Set<String>]()
        var entities = Set<String>()
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
            }
        }
        workedCalls = calls
        workedGrids = grids
        bandsByCall = bands
        workedEntities = entities
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
    if isPotaCq(callTo: callTo, extra: extra) { return .pota }

    if toggles.newDxcc,
       let entity = DxccPrefix.entity(for: call),
       !index.workedEntities.contains(entity.name) {
        return .newDxcc
    }

    let square = grid4(grid.uppercased())
    if toggles.newGrid,
       square.count >= 4,
       gridToLatLon(square) != nil,
       !index.workedGrids.contains(square) {
        return .newGrid
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
