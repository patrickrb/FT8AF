import Foundation

/// cty.dat-backed callsign → DXCC/CQ-zone resolver.
///
/// Parses the AD1C country file (`cty.dat`, the same asset the Android app
/// bundles) into an in-memory lookup and resolves any callsign to its DXCC
/// entity, CQ/ITU zone, continent and reference lat/lon. This replaces the old
/// hand-rolled ~130-entry prefix table with the full ~350-entity dataset.
///
/// ## Parsing
/// A cty.dat record is a header line:
///
/// ```
/// Country Name:  CQ:  ITU:  Cont:  Lat:  Lon:  GMT:  PrimaryPrefix:
/// ```
///
/// followed by one or more indented lines of comma-separated prefix tokens,
/// the last ending in `;`. Mirroring Android's `CallsignFileOperation`, a line
/// is treated as a header iff it contains a `:` (prefix lines never do); every
/// other non-empty line contributes prefix tokens to the current record.
///
/// A token is a prefix by default, or an exact full-callsign match when it
/// starts with `=`. Any token may carry per-token overrides that refine the
/// country defaults for just that prefix/call:
/// - `(n)` — CQ-zone override
/// - `[n]` — ITU-zone override
/// - `<lat/lon>` — coordinate override
/// - `{CC}` — continent override
/// - `~…~` — GMT/time override (ignored)
///
/// Unlike Android's importer (which strips everything from the first `(`/`[`
/// and therefore always uses the country-level zone), this resolver *applies*
/// the per-token overrides. That yields the correct WAZ for split-zone entities
/// — e.g. a US call area 0 station is CQ zone 4, and Asiatic-Russia `R0…` is
/// zone 19 — which the Android importer collapses to the country default.
/// New-DXCC dedup keys on the entity *name*, which is unaffected, so highlight
/// behaviour is unchanged; only the reported zone is more precise.
///
/// ## Lookup
/// Exact `=CALL` entries win over prefix matches. Among prefixes the longest
/// match wins. Lookup is O(prefix length): the prefix map is consulted for
/// progressively shorter leading substrings, capped at the longest prefix seen.
final class DxccResolver {

    /// Shared instance, parsed once from the bundled `cty.dat`. Failing to load
    /// the resource yields an empty resolver (every lookup returns nil) rather
    /// than crashing — DXCC info is cosmetic, never used for logging.
    static let shared: DxccResolver = {
        guard let url = Bundle.module.url(forResource: "cty", withExtension: "dat"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return DxccResolver(catText: "")
        }
        return DxccResolver(catText: text)
    }()

    /// Exact full-callsign matches (`=CALL` tokens), keyed by upper-cased call.
    private var exact: [String: DxccEntity] = [:]
    /// Prefix → entity. Keyed by upper-cased prefix; longest match wins.
    private var prefixes: [String: DxccEntity] = [:]
    /// Length of the longest registered prefix, to bound lookup iterations.
    private var maxPrefixLength = 0

    /// Parse `catText` (the contents of a cty.dat file) into the lookup tables.
    init(catText: String) {
        parse(catText)
    }

    // MARK: - Public lookup

    /// Resolve the DXCC entity for a callsign, or nil when nothing matches.
    ///
    /// Exact `=CALL` overrides are tried against the whole callsign first (they
    /// include any `/suffix`, e.g. `AL7QQ/P`). Otherwise the call is split on
    /// `/` and every segment is prefix-matched; the segment with the longest
    /// matched prefix wins, ties going to the earliest segment. That resolves
    /// portables to the DX operating prefix — `EA8/W1AW` → Canary Islands,
    /// `K1ABC/VE3` → Canada — while a bare suffix like `/P` (matches nothing)
    /// or `/M` (ties England at length 1, loses to the home call) is ignored.
    func entity(for call: String) -> DxccEntity? {
        let upper = call.uppercased()
        guard !upper.isEmpty else { return nil }

        if let hit = exact[upper] { return hit }

        var best: (length: Int, entity: DxccEntity)?
        for segment in upper.split(separator: "/") {
            guard let (length, entity) = longestPrefixMatch(String(segment)) else { continue }
            if best == nil || length > best!.length {
                best = (length, entity)
            }
        }
        return best?.entity
    }

    /// Longest registered prefix that is a leading substring of `segment`.
    func longestPrefixMatch(_ segment: String) -> (Int, DxccEntity)? {
        guard !segment.isEmpty else { return nil }
        let chars = Array(segment)
        let maxLen = min(maxPrefixLength, chars.count)
        guard maxLen > 0 else { return nil }
        for len in stride(from: maxLen, through: 1, by: -1) {
            let candidate = String(chars[0..<len])
            if let entity = prefixes[candidate] {
                return (len, entity)
            }
        }
        return nil
    }

    // MARK: - Parsing

    /// Some cty.dat entity names are the formal AD1C spelling; the app (and its
    /// tests / UI) expect the common short name. Reconcile the handful that
    /// differ so `entity(for:)?.name` matches what callers key on.
    private static let nameOverrides: [String: String] = [
        "Fed. Rep. of Germany": "Germany",
    ]

    private func parse(_ text: String) {
        var current: DxccEntity?
        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(rawLine)
            if line.contains(":") {
                current = parseHeader(line)
            } else if let country = current {
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                guard !trimmed.isEmpty else { continue }
                addTokens(trimmed, country: country)
            }
        }
    }

    /// Build the country-default entity from a `Name: CQ: ITU: Cont: Lat: Lon:
    /// GMT: Prefix:` header line, or nil if the fields don't parse.
    private func parseHeader(_ line: String) -> DxccEntity? {
        let fields = line.components(separatedBy: ":")
        guard fields.count >= 8 else { return nil }
        let rawName = fields[0].trimmingCharacters(in: .whitespaces)
        guard !rawName.isEmpty,
              let cq = Int(fields[1].trimmingCharacters(in: .whitespaces)) else {
            return nil
        }
        let itu = Int(fields[2].trimmingCharacters(in: .whitespaces))
        let continent = fields[3].trimmingCharacters(in: .whitespaces)
        let lat = Double(fields[4].trimmingCharacters(in: .whitespaces))
        // cty.dat longitude is positive-west; expose the standard east-positive
        // convention (matching Android's `Longitude * -1` when it builds LatLng).
        let lon = Double(fields[5].trimmingCharacters(in: .whitespaces)).map { -$0 }
        let primary = fields[7].trimmingCharacters(in: .whitespaces)
        let name = Self.nameOverrides[rawName] ?? rawName
        return DxccEntity(
            name: name,
            continent: continent,
            cqZone: cq,
            ituZone: itu,
            latitude: lat,
            longitude: lon,
            dxccPrefix: primary
        )
    }

    /// Parse a comma-separated prefix line and register each token against the
    /// current country entity, applying any per-token overrides.
    private func addTokens(_ line: String, country: DxccEntity) {
        let body = line.hasSuffix(";") ? String(line.dropLast()) : line
        for rawToken in body.split(separator: ",") {
            let token = rawToken.trimmingCharacters(in: .whitespaces)
            guard !token.isEmpty else { continue }
            let (base, isExact, entity) = parseToken(token, country: country)
            guard !base.isEmpty else { continue }
            if isExact {
                if exact[base] == nil { exact[base] = entity }
            } else if prefixes[base] == nil {
                prefixes[base] = entity
                maxPrefixLength = max(maxPrefixLength, base.count)
            }
        }
    }

    /// Split a token into its base prefix/call and an entity with any `(cq)`,
    /// `[itu]`, `<lat/lon>`, `{continent}` overrides applied to the country
    /// defaults. Returns `isExact == true` for a leading `=` (full-call match).
    private func parseToken(_ token: String, country: DxccEntity) -> (base: String, isExact: Bool, entity: DxccEntity) {
        var chars = Array(token)
        var index = 0
        var isExact = false
        if chars.first == "=" {
            isExact = true
            index = 1
        }

        var base = ""
        while index < chars.count {
            let c = chars[index]
            if c == "(" || c == "[" || c == "<" || c == "{" || c == "~" {
                break
            }
            base.append(c)
            index += 1
        }

        var cq = country.cqZone
        var itu = country.ituZone
        var continent = country.continent
        var lat = country.latitude
        var lon = country.longitude

        while index < chars.count {
            let open = chars[index]
            let close: Character
            switch open {
            case "(": close = ")"
            case "[": close = "]"
            case "<": close = ">"
            case "{": close = "}"
            case "~": close = "~"
            default:
                index += 1
                continue
            }
            index += 1
            var inner = ""
            while index < chars.count, chars[index] != close {
                inner.append(chars[index])
                index += 1
            }
            if index < chars.count { index += 1 } // consume the closing bracket
            applyOverride(open: open, inner: inner,
                          cq: &cq, itu: &itu, continent: &continent, lat: &lat, lon: &lon)
        }

        let entity = DxccEntity(
            name: country.name,
            continent: continent,
            cqZone: cq,
            ituZone: itu,
            latitude: lat,
            longitude: lon,
            dxccPrefix: country.dxccPrefix
        )
        return (base, isExact, entity)
    }

    private func applyOverride(
        open: Character,
        inner: String,
        cq: inout Int,
        itu: inout Int?,
        continent: inout String,
        lat: inout Double?,
        lon: inout Double?
    ) {
        let value = inner.trimmingCharacters(in: .whitespaces)
        switch open {
        case "(":
            if let z = Int(value) { cq = z }
        case "[":
            if let z = Int(value) { itu = z }
        case "{":
            if !value.isEmpty { continent = value }
        case "<":
            let parts = value.split(separator: "/")
            if parts.count == 2,
               let la = Double(parts[0].trimmingCharacters(in: .whitespaces)),
               let lo = Double(parts[1].trimmingCharacters(in: .whitespaces)) {
                lat = la
                lon = -lo // positive-west → east-positive, as in the header
            }
        default:
            break // ~time~ override ignored
        }
    }
}
