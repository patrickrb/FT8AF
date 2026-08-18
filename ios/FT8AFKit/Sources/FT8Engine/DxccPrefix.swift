import Foundation

/// A DXCC entity resolved from a callsign, with its CQ/ITU zone and location.
///
/// Populated from cty.dat (see ``DxccResolver``). `cqZone` and, where the
/// matched prefix carries an override, `continent`/`ituZone`/lat-lon are the
/// per-prefix values, so split-zone entities report the correct WAZ.
public struct DxccEntity: Equatable, Sendable {
    /// Entity display name (e.g. "United States", "Japan", "England").
    public let name: String
    /// Continent code: NA / SA / EU / AF / AS / OC / AN.
    public let continent: String
    /// CQ zone (WAZ), 1–40.
    public let cqZone: Int
    /// ITU zone, when known.
    public let ituZone: Int?
    /// Reference latitude in degrees (north positive), when known.
    public let latitude: Double?
    /// Reference longitude in degrees (east positive), when known.
    public let longitude: Double?
    /// The entity's primary DXCC prefix (the header prefix in cty.dat, e.g.
    /// "K", "DL"). A stable per-entity key for new-DXCC dedup.
    public let dxccPrefix: String

    public init(
        name: String,
        continent: String,
        cqZone: Int = 0,
        ituZone: Int? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil,
        dxccPrefix: String = ""
    ) {
        self.name = name
        self.continent = continent
        self.cqZone = cqZone
        self.ituZone = ituZone
        self.latitude = latitude
        self.longitude = longitude
        self.dxccPrefix = dxccPrefix
    }
}

/// Callsign → DXCC-entity resolution, backed by the bundled cty.dat dataset.
///
/// This is the stable public surface used across the app (NEW DXCC
/// highlighting, the decode location line, the DX-only / continent filters).
/// The lookup itself lives in ``DxccResolver``; this enum is a thin,
/// source-compatible facade over `DxccResolver.shared` so existing call sites
/// (`DxccPrefix.entity(for:)?.name`, `DxccPrefix.continent(for:)`) keep working.
public enum DxccPrefix {
    /// Resolve the DXCC entity for a callsign, or nil when nothing matches.
    ///
    /// Exact `=CALL` entries win over prefix matches; among prefixes the longest
    /// wins. Portable designators are handled by matching every `/`-separated
    /// segment and keeping the one with the longest matched prefix — e.g.
    /// "EA8/W1AW" → Canary Islands, "K1ABC/VE3" → Canada — while a bare suffix
    /// ("W1AW/P", "DL4RCK/QRP") matches nothing and is ignored.
    public static func entity(for call: String) -> DxccEntity? {
        DxccResolver.shared.entity(for: call)
    }

    /// Continent code (NA/SA/EU/AF/AS/OC/AN) for a callsign, or nil when unknown.
    public static func continent(for call: String) -> String? {
        entity(for: call)?.continent
    }

    /// CQ zone (WAZ) for a callsign, or nil when unknown.
    public static func cqZone(for call: String) -> Int? {
        entity(for: call)?.cqZone
    }
}
