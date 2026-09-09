import Foundation

/// Maps a Maidenhead grid to a US state (USPS) code for Worked-All-States (WAS)
/// chasing, a faithful port of the Android `UsStateLookup` object and
/// `GeneralVariables.stateForGrid`.
///
/// Backed by the SAME `us_grid_states.json` the Android app ships in
/// `assets/` — a US-only table keyed by the uppercased first four characters of
/// the grid (field + square). Non-US grids are absent, so they resolve to nil
/// naturally. The table is bundled as a package resource and loaded once via
/// `Bundle.module`.
public enum UsStateLookup {
    /// Grid square (4-char, uppercased) -> USPS state code. Loaded lazily on
    /// first use and cached for the process lifetime (the table is immutable).
    private static let map: [String: String] = loadMap()

    /// Resolve a Maidenhead grid to a US state code, or nil when it is not a US
    /// grid. Mirrors Android: grids shorter than four characters return nil, and
    /// the lookup keys on the first four characters uppercased.
    ///
    /// - Parameter grid: the station's Maidenhead locator (4+ chars). Shorter,
    ///   empty, or nil grids return nil.
    /// - Returns: the two-letter USPS state code, or nil.
    public static func state(forGrid grid: String?) -> String? {
        guard let grid, grid.count >= 4 else { return nil }
        // Swift's `uppercased()` is locale-independent (no Turkish-i hazard, the
        // reason the Kotlin side pins Locale.ROOT), so it matches the ASCII keys.
        let key = String(grid.prefix(4)).uppercased()
        return map[key]
    }

    private static func loadMap() -> [String: String] {
        guard let url = Bundle.module.url(forResource: "us_grid_states", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: String]
        else { return [:] }
        return obj
    }
}

/// Resolve the human-readable location line shown under a decode row, a faithful
/// port of Android `DecodeRow.resolveLocationText`. Prefers a resolved US state
/// ("CT, USA") over the bare DXCC entity name, and abbreviates the two most
/// common entities the way Android does.
///
/// Kept as a pure top-level function (no SwiftUI) so it is unit-testable; the
/// row view is a thin caller.
///
/// - Parameters:
///   - callFrom: the sender's callsign (drives the DXCC-entity fallback).
///   - grid: the sender's Maidenhead grid (drives the US-state lookup).
/// - Returns: the location string, or nil when nothing is known.
public func decodeLocationText(callFrom: String, grid: String) -> String? {
    // Normalize the way classifyDecode does before resolving, so a decode with
    // stray whitespace around the call/grid still yields the right entity and
    // state (an untrimmed call would miss the DXCC table and blank the line).
    let call = callFrom.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    let normGrid = grid.trimmingCharacters(in: .whitespacesAndNewlines)
    let country = DxccPrefix.entity(for: call)?.name
    let isUs = country == "United States"
    if let state = UsStateLookup.state(forGrid: normGrid) {
        if isUs || country == nil { return "\(state), USA" }
        return "\(state), \(country!)"
    }
    switch country {
    case .none: return nil
    case "United States": return "USA"
    case "United Kingdom": return "UK"
    case let other: return other
    }
}
