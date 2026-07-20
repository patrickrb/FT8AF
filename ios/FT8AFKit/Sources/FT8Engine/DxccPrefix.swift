import Foundation

/// A DXCC entity resolved from a callsign prefix.
public struct DxccEntity: Equatable, Sendable {
    /// Entity display name (e.g. "United States", "Japan").
    public let name: String
    /// Continent code: NA / SA / EU / AF / AS / OC / AN.
    public let continent: String

    public init(name: String, continent: String) {
        self.name = name
        self.continent = continent
    }
}

/// Compact callsign-prefix → DXCC-entity heuristic (top ~130 entities).
///
/// Mirrors what the Android app derives from its bundled callsign database,
/// scaled down to a longest-prefix-match table that covers the entities an
/// operator actually sees on FT8. Used for NEW DXCC highlighting, the decode
/// location line, and the DX-only / continent filters — never for logging, so
/// a rare-prefix miss is cosmetic only.
public enum DxccPrefix {
    /// Resolve the DXCC entity for a callsign, or nil when no prefix matches.
    ///
    /// Portable designators are handled by matching every `/`-separated
    /// segment and keeping the segment whose matched prefix is longest —
    /// "EA8/W1AW" → Canary Islands (EA8, 3 chars beats W, 1 char);
    /// "W1AW/P" → United States (bare "P" matches nothing).
    /// Ties go to the earlier segment, so "W1AW/M" stays United States.
    public static func entity(for call: String) -> DxccEntity? {
        let segments = call.uppercased().split(separator: "/").map(String.init)
        var best: (length: Int, entity: DxccEntity)? = nil
        for segment in segments {
            guard let (length, entity) = longestPrefixMatch(segment) else { continue }
            if best == nil || length > best!.length {
                best = (length, entity)
            }
        }
        return best?.entity
    }

    /// Continent code (NA/SA/EU/AF/AS/OC/AN) for a callsign, or nil when unknown.
    public static func continent(for call: String) -> String? {
        entity(for: call)?.continent
    }

    /// Longest table prefix matching the start of `segment`, with its length.
    static func longestPrefixMatch(_ segment: String) -> (Int, DxccEntity)? {
        guard !segment.isEmpty else { return nil }
        let maxLen = min(4, segment.count)
        for len in stride(from: maxLen, through: 1, by: -1) {
            let prefix = String(segment.prefix(len))
            if let entity = table[prefix] {
                return (len, entity)
            }
        }
        return nil
    }

    // MARK: - Prefix table

    private static let usa = DxccEntity(name: "United States", continent: "NA")
    private static let canada = DxccEntity(name: "Canada", continent: "NA")
    private static let japan = DxccEntity(name: "Japan", continent: "AS")
    private static let germany = DxccEntity(name: "Germany", continent: "EU")
    private static let england = DxccEntity(name: "England", continent: "EU")
    private static let russiaEu = DxccEntity(name: "European Russia", continent: "EU")
    private static let russiaAs = DxccEntity(name: "Asiatic Russia", continent: "AS")
    private static let ukraine = DxccEntity(name: "Ukraine", continent: "EU")
    private static let spain = DxccEntity(name: "Spain", continent: "EU")
    private static let brazil = DxccEntity(name: "Brazil", continent: "SA")
    private static let cuba = DxccEntity(name: "Cuba", continent: "NA")
    private static let netherlands = DxccEntity(name: "Netherlands", continent: "EU")
    private static let sweden = DxccEntity(name: "Sweden", continent: "EU")
    private static let poland = DxccEntity(name: "Poland", continent: "EU")
    private static let southKorea = DxccEntity(name: "South Korea", continent: "AS")
    private static let argentina = DxccEntity(name: "Argentina", continent: "SA")
    private static let chile = DxccEntity(name: "Chile", continent: "SA")
    private static let southAfrica = DxccEntity(name: "South Africa", continent: "AF")

    static let table: [String: DxccEntity] = [
        // ---- North America ----
        "K": usa, "W": usa, "N": usa, "A": usa,
        "KL": DxccEntity(name: "Alaska", continent: "NA"),
        "KH6": DxccEntity(name: "Hawaii", continent: "OC"),
        "KH2": DxccEntity(name: "Guam", continent: "OC"),
        "KP4": DxccEntity(name: "Puerto Rico", continent: "NA"),
        "KP2": DxccEntity(name: "US Virgin Islands", continent: "NA"),
        "VE": canada, "VA": canada, "VO": canada, "VY": canada,
        "XE": DxccEntity(name: "Mexico", continent: "NA"),
        "XF": DxccEntity(name: "Mexico", continent: "NA"),
        "TI": DxccEntity(name: "Costa Rica", continent: "NA"),
        "TG": DxccEntity(name: "Guatemala", continent: "NA"),
        "YS": DxccEntity(name: "El Salvador", continent: "NA"),
        "HR": DxccEntity(name: "Honduras", continent: "NA"),
        "YN": DxccEntity(name: "Nicaragua", continent: "NA"),
        "HP": DxccEntity(name: "Panama", continent: "NA"),
        "HI": DxccEntity(name: "Dominican Republic", continent: "NA"),
        "CO": cuba, "CM": cuba,
        "6Y": DxccEntity(name: "Jamaica", continent: "NA"),
        "ZF": DxccEntity(name: "Cayman Islands", continent: "NA"),
        "C6": DxccEntity(name: "Bahamas", continent: "NA"),
        "V3": DxccEntity(name: "Belize", continent: "NA"),
        "VP9": DxccEntity(name: "Bermuda", continent: "NA"),
        "8P": DxccEntity(name: "Barbados", continent: "NA"),
        "FM": DxccEntity(name: "Martinique", continent: "NA"),
        "FG": DxccEntity(name: "Guadeloupe", continent: "NA"),
        "J3": DxccEntity(name: "Grenada", continent: "NA"),
        "OX": DxccEntity(name: "Greenland", continent: "NA"),

        // ---- South America ----
        "PY": brazil, "PP": brazil, "PR": brazil, "PS": brazil, "PT": brazil, "PU": brazil,
        "LU": argentina, "LW": argentina,
        "CE": chile, "CA": chile, "XQ": chile,
        "HK": DxccEntity(name: "Colombia", continent: "SA"),
        "HJ": DxccEntity(name: "Colombia", continent: "SA"),
        "YV": DxccEntity(name: "Venezuela", continent: "SA"),
        "OA": DxccEntity(name: "Peru", continent: "SA"),
        "HC": DxccEntity(name: "Ecuador", continent: "SA"),
        "CX": DxccEntity(name: "Uruguay", continent: "SA"),
        "ZP": DxccEntity(name: "Paraguay", continent: "SA"),
        "CP": DxccEntity(name: "Bolivia", continent: "SA"),
        "PZ": DxccEntity(name: "Suriname", continent: "SA"),
        "9Y": DxccEntity(name: "Trinidad & Tobago", continent: "SA"),

        // ---- Europe ----
        "G": england, "M": england, "2E": england,
        "GM": DxccEntity(name: "Scotland", continent: "EU"),
        "MM": DxccEntity(name: "Scotland", continent: "EU"),
        "2M": DxccEntity(name: "Scotland", continent: "EU"),
        "GW": DxccEntity(name: "Wales", continent: "EU"),
        "MW": DxccEntity(name: "Wales", continent: "EU"),
        "2W": DxccEntity(name: "Wales", continent: "EU"),
        "GI": DxccEntity(name: "Northern Ireland", continent: "EU"),
        "MI": DxccEntity(name: "Northern Ireland", continent: "EU"),
        "GD": DxccEntity(name: "Isle of Man", continent: "EU"),
        "GJ": DxccEntity(name: "Jersey", continent: "EU"),
        "GU": DxccEntity(name: "Guernsey", continent: "EU"),
        "EI": DxccEntity(name: "Ireland", continent: "EU"),
        "EJ": DxccEntity(name: "Ireland", continent: "EU"),
        "F": DxccEntity(name: "France", continent: "EU"),
        "DA": germany, "DB": germany, "DC": germany, "DD": germany, "DF": germany,
        "DG": germany, "DH": germany, "DJ": germany, "DK": germany, "DL": germany,
        "DM": germany, "DN": germany, "DO": germany, "DP": germany, "DR": germany,
        "I": DxccEntity(name: "Italy", continent: "EU"),
        "EA": spain, "EB": spain, "EC": spain, "ED": spain, "EE": spain,
        "EF": spain, "EG": spain, "EH": spain,
        "EA6": DxccEntity(name: "Balearic Islands", continent: "EU"),
        "EA8": DxccEntity(name: "Canary Islands", continent: "AF"),
        "EA9": DxccEntity(name: "Ceuta & Melilla", continent: "AF"),
        "CT": DxccEntity(name: "Portugal", continent: "EU"),
        "CT3": DxccEntity(name: "Madeira Islands", continent: "AF"),
        "CU": DxccEntity(name: "Azores", continent: "EU"),
        "ON": DxccEntity(name: "Belgium", continent: "EU"),
        "OO": DxccEntity(name: "Belgium", continent: "EU"),
        "OT": DxccEntity(name: "Belgium", continent: "EU"),
        "PA": netherlands, "PB": netherlands, "PC": netherlands, "PD": netherlands,
        "PE": netherlands, "PF": netherlands, "PG": netherlands, "PH": netherlands,
        "PI": netherlands,
        "HB": DxccEntity(name: "Switzerland", continent: "EU"),
        "HB0": DxccEntity(name: "Liechtenstein", continent: "EU"),
        "OE": DxccEntity(name: "Austria", continent: "EU"),
        "OZ": DxccEntity(name: "Denmark", continent: "EU"),
        "LA": DxccEntity(name: "Norway", continent: "EU"),
        "LB": DxccEntity(name: "Norway", continent: "EU"),
        "SM": sweden, "SA": sweden, "SK": sweden, "SL": sweden,
        "OH": DxccEntity(name: "Finland", continent: "EU"),
        "OH0": DxccEntity(name: "Aland Islands", continent: "EU"),
        "OY": DxccEntity(name: "Faroe Islands", continent: "EU"),
        "TF": DxccEntity(name: "Iceland", continent: "EU"),
        "SP": poland, "SN": poland, "SO": poland, "SQ": poland, "3Z": poland,
        "OK": DxccEntity(name: "Czech Republic", continent: "EU"),
        "OL": DxccEntity(name: "Czech Republic", continent: "EU"),
        "OM": DxccEntity(name: "Slovakia", continent: "EU"),
        "HA": DxccEntity(name: "Hungary", continent: "EU"),
        "HG": DxccEntity(name: "Hungary", continent: "EU"),
        "YO": DxccEntity(name: "Romania", continent: "EU"),
        "YP": DxccEntity(name: "Romania", continent: "EU"),
        "YQ": DxccEntity(name: "Romania", continent: "EU"),
        "YR": DxccEntity(name: "Romania", continent: "EU"),
        "LZ": DxccEntity(name: "Bulgaria", continent: "EU"),
        "SV": DxccEntity(name: "Greece", continent: "EU"),
        "SW": DxccEntity(name: "Greece", continent: "EU"),
        "SX": DxccEntity(name: "Greece", continent: "EU"),
        "SY": DxccEntity(name: "Greece", continent: "EU"),
        "SZ": DxccEntity(name: "Greece", continent: "EU"),
        "9A": DxccEntity(name: "Croatia", continent: "EU"),
        "S5": DxccEntity(name: "Slovenia", continent: "EU"),
        "E7": DxccEntity(name: "Bosnia-Herzegovina", continent: "EU"),
        "YU": DxccEntity(name: "Serbia", continent: "EU"),
        "YT": DxccEntity(name: "Serbia", continent: "EU"),
        "Z3": DxccEntity(name: "North Macedonia", continent: "EU"),
        "ZA": DxccEntity(name: "Albania", continent: "EU"),
        "4O": DxccEntity(name: "Montenegro", continent: "EU"),
        "Z6": DxccEntity(name: "Kosovo", continent: "EU"),
        // Russia: R/UA–UI default to European; the 8/9/0 call areas are Asiatic.
        "R": russiaEu,
        "UA": russiaEu, "UB": russiaEu, "UC": russiaEu, "UD": russiaEu,
        "UE": russiaEu, "UF": russiaEu, "UG": russiaEu, "UH": russiaEu, "UI": russiaEu,
        "R8": russiaAs, "R9": russiaAs, "R0": russiaAs,
        "RA8": russiaAs, "RA9": russiaAs, "RA0": russiaAs,
        "UA8": russiaAs, "UA9": russiaAs, "UA0": russiaAs,
        "UR": ukraine, "US": ukraine, "UT": ukraine, "UU": ukraine, "UV": ukraine,
        "UW": ukraine, "UX": ukraine, "UY": ukraine, "UZ": ukraine,
        "EM": ukraine, "EN": ukraine, "EO": ukraine,
        "EU": DxccEntity(name: "Belarus", continent: "EU"),
        "EV": DxccEntity(name: "Belarus", continent: "EU"),
        "EW": DxccEntity(name: "Belarus", continent: "EU"),
        "YL": DxccEntity(name: "Latvia", continent: "EU"),
        "LY": DxccEntity(name: "Lithuania", continent: "EU"),
        "ES": DxccEntity(name: "Estonia", continent: "EU"),
        "ER": DxccEntity(name: "Moldova", continent: "EU"),
        "LX": DxccEntity(name: "Luxembourg", continent: "EU"),
        "9H": DxccEntity(name: "Malta", continent: "EU"),
        "ZB": DxccEntity(name: "Gibraltar", continent: "EU"),
        "T7": DxccEntity(name: "San Marino", continent: "EU"),
        "HV": DxccEntity(name: "Vatican City", continent: "EU"),
        "C3": DxccEntity(name: "Andorra", continent: "EU"),
        "3A": DxccEntity(name: "Monaco", continent: "EU"),

        // ---- Asia ----
        "JA": japan, "JE": japan, "JF": japan, "JG": japan, "JH": japan,
        "JI": japan, "JJ": japan, "JK": japan, "JL": japan, "JM": japan,
        "JN": japan, "JO": japan, "JP": japan, "JQ": japan, "JR": japan,
        "JS": japan, "7J": japan, "7K": japan, "7L": japan, "7M": japan, "7N": japan,
        "HL": southKorea, "DS": southKorea, "6K": southKorea,
        "P5": DxccEntity(name: "North Korea", continent: "AS"),
        "B": DxccEntity(name: "China", continent: "AS"),
        "BV": DxccEntity(name: "Taiwan", continent: "AS"),
        "VR2": DxccEntity(name: "Hong Kong", continent: "AS"),
        "XX9": DxccEntity(name: "Macao", continent: "AS"),
        "VU": DxccEntity(name: "India", continent: "AS"),
        "AP": DxccEntity(name: "Pakistan", continent: "AS"),
        "4S": DxccEntity(name: "Sri Lanka", continent: "AS"),
        "S2": DxccEntity(name: "Bangladesh", continent: "AS"),
        "9N": DxccEntity(name: "Nepal", continent: "AS"),
        "A5": DxccEntity(name: "Bhutan", continent: "AS"),
        "HS": DxccEntity(name: "Thailand", continent: "AS"),
        "E2": DxccEntity(name: "Thailand", continent: "AS"),
        "XV": DxccEntity(name: "Vietnam", continent: "AS"),
        "3W": DxccEntity(name: "Vietnam", continent: "AS"),
        "XU": DxccEntity(name: "Cambodia", continent: "AS"),
        "XW": DxccEntity(name: "Laos", continent: "AS"),
        "XZ": DxccEntity(name: "Myanmar", continent: "AS"),
        "9V": DxccEntity(name: "Singapore", continent: "AS"),
        "9M": DxccEntity(name: "Malaysia", continent: "AS"),
        "HZ": DxccEntity(name: "Saudi Arabia", continent: "AS"),
        "7Z": DxccEntity(name: "Saudi Arabia", continent: "AS"),
        "A4": DxccEntity(name: "Oman", continent: "AS"),
        "A6": DxccEntity(name: "United Arab Emirates", continent: "AS"),
        "A7": DxccEntity(name: "Qatar", continent: "AS"),
        "A9": DxccEntity(name: "Bahrain", continent: "AS"),
        "9K": DxccEntity(name: "Kuwait", continent: "AS"),
        "OD": DxccEntity(name: "Lebanon", continent: "AS"),
        "YK": DxccEntity(name: "Syria", continent: "AS"),
        "JY": DxccEntity(name: "Jordan", continent: "AS"),
        "YI": DxccEntity(name: "Iraq", continent: "AS"),
        "EP": DxccEntity(name: "Iran", continent: "AS"),
        "4X": DxccEntity(name: "Israel", continent: "AS"),
        "4Z": DxccEntity(name: "Israel", continent: "AS"),
        "TA": DxccEntity(name: "Turkey", continent: "AS"),
        "TB": DxccEntity(name: "Turkey", continent: "AS"),
        "TC": DxccEntity(name: "Turkey", continent: "AS"),
        "EK": DxccEntity(name: "Armenia", continent: "AS"),
        "4J": DxccEntity(name: "Azerbaijan", continent: "AS"),
        "4K": DxccEntity(name: "Azerbaijan", continent: "AS"),
        "4L": DxccEntity(name: "Georgia", continent: "AS"),
        "UN": DxccEntity(name: "Kazakhstan", continent: "AS"),
        "UP": DxccEntity(name: "Kazakhstan", continent: "AS"),
        "UK": DxccEntity(name: "Uzbekistan", continent: "AS"),
        "UJ": DxccEntity(name: "Uzbekistan", continent: "AS"),
        "EX": DxccEntity(name: "Kyrgyzstan", continent: "AS"),
        "EY": DxccEntity(name: "Tajikistan", continent: "AS"),
        "EZ": DxccEntity(name: "Turkmenistan", continent: "AS"),
        "T6": DxccEntity(name: "Afghanistan", continent: "AS"),
        "JT": DxccEntity(name: "Mongolia", continent: "AS"),
        "8Q": DxccEntity(name: "Maldives", continent: "AS"),

        // ---- Oceania ----
        "VK": DxccEntity(name: "Australia", continent: "OC"),
        "ZL": DxccEntity(name: "New Zealand", continent: "OC"),
        "YB": DxccEntity(name: "Indonesia", continent: "OC"),
        "YC": DxccEntity(name: "Indonesia", continent: "OC"),
        "YD": DxccEntity(name: "Indonesia", continent: "OC"),
        "YE": DxccEntity(name: "Indonesia", continent: "OC"),
        "DU": DxccEntity(name: "Philippines", continent: "OC"),
        "DV": DxccEntity(name: "Philippines", continent: "OC"),
        "DW": DxccEntity(name: "Philippines", continent: "OC"),
        "DX": DxccEntity(name: "Philippines", continent: "OC"),
        "V8": DxccEntity(name: "Brunei", continent: "OC"),
        "P2": DxccEntity(name: "Papua New Guinea", continent: "OC"),
        "FK": DxccEntity(name: "New Caledonia", continent: "OC"),
        "3D2": DxccEntity(name: "Fiji", continent: "OC"),
        "5W": DxccEntity(name: "Samoa", continent: "OC"),
        "A3": DxccEntity(name: "Tonga", continent: "OC"),
        "YJ": DxccEntity(name: "Vanuatu", continent: "OC"),

        // ---- Africa ----
        "ZS": southAfrica, "ZR": southAfrica, "ZT": southAfrica, "ZU": southAfrica,
        "SU": DxccEntity(name: "Egypt", continent: "AF"),
        "CN": DxccEntity(name: "Morocco", continent: "AF"),
        "7X": DxccEntity(name: "Algeria", continent: "AF"),
        "3V": DxccEntity(name: "Tunisia", continent: "AF"),
        "5A": DxccEntity(name: "Libya", continent: "AF"),
        "ST": DxccEntity(name: "Sudan", continent: "AF"),
        "ET": DxccEntity(name: "Ethiopia", continent: "AF"),
        "5Z": DxccEntity(name: "Kenya", continent: "AF"),
        "5H": DxccEntity(name: "Tanzania", continent: "AF"),
        "9J": DxccEntity(name: "Zambia", continent: "AF"),
        "Z2": DxccEntity(name: "Zimbabwe", continent: "AF"),
        "7Q": DxccEntity(name: "Malawi", continent: "AF"),
        "C9": DxccEntity(name: "Mozambique", continent: "AF"),
        "A2": DxccEntity(name: "Botswana", continent: "AF"),
        "V5": DxccEntity(name: "Namibia", continent: "AF"),
        "5N": DxccEntity(name: "Nigeria", continent: "AF"),
        "9G": DxccEntity(name: "Ghana", continent: "AF"),
        "TU": DxccEntity(name: "Cote d'Ivoire", continent: "AF"),
        "6W": DxccEntity(name: "Senegal", continent: "AF"),
        "EL": DxccEntity(name: "Liberia", continent: "AF"),
        "TZ": DxccEntity(name: "Mali", continent: "AF"),
        "5R": DxccEntity(name: "Madagascar", continent: "AF"),
        "3B8": DxccEntity(name: "Mauritius", continent: "AF"),
        "FR": DxccEntity(name: "Reunion Island", continent: "AF"),
        "D2": DxccEntity(name: "Angola", continent: "AF"),
        "D4": DxccEntity(name: "Cape Verde", continent: "AF"),
        "9Q": DxccEntity(name: "DR Congo", continent: "AF"),
        "TR": DxccEntity(name: "Gabon", continent: "AF"),
        "TJ": DxccEntity(name: "Cameroon", continent: "AF"),
        "9X": DxccEntity(name: "Rwanda", continent: "AF"),
        "5X": DxccEntity(name: "Uganda", continent: "AF"),
    ]
}
