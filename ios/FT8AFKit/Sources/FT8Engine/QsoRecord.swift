import Foundation

/// One logged QSO. Field names follow ADIF (port of desktop db.rs QsoRecord); a
/// plain value type so both the QSO state machine (which emits it) and the future
/// FT8Data persistence layer can share it. GRDB conformance, when FT8Data lands,
/// is added in an extension there.
public struct QsoRecord: Equatable, Codable {
    public var id: Int64?
    public var call: String
    public var gridsquare: String
    public var mode: String
    public var rstSent: String
    public var rstRcvd: String
    public var qsoDate: String      // YYYYMMDD (UTC)
    public var timeOn: String       // HHMMSS (UTC)
    public var qsoDateOff: String
    public var timeOff: String
    public var band: String
    public var freq: String
    public var stationCallsign: String
    public var myGridsquare: String
    public var comment: String
    /// Whether this QSO has been accepted by the Cloudlog/Wavelog server
    /// (Android: QSLTable.synced_cloudlog). Defaults false; absent in logs
    /// stored before online logging existed.
    public var syncedCloudlog: Bool
    /// Whether this QSO has been accepted by the QRZ.com logbook
    /// (Android: QSLTable.synced_qrz).
    public var syncedQrz: Bool

    public init(
        id: Int64? = nil,
        call: String,
        gridsquare: String,
        mode: String,
        rstSent: String,
        rstRcvd: String,
        qsoDate: String,
        timeOn: String,
        qsoDateOff: String,
        timeOff: String,
        band: String,
        freq: String,
        stationCallsign: String,
        myGridsquare: String,
        comment: String,
        syncedCloudlog: Bool = false,
        syncedQrz: Bool = false
    ) {
        self.id = id
        self.call = call
        self.gridsquare = gridsquare
        self.mode = mode
        self.rstSent = rstSent
        self.rstRcvd = rstRcvd
        self.qsoDate = qsoDate
        self.timeOn = timeOn
        self.qsoDateOff = qsoDateOff
        self.timeOff = timeOff
        self.band = band
        self.freq = freq
        self.stationCallsign = stationCallsign
        self.myGridsquare = myGridsquare
        self.comment = comment
        self.syncedCloudlog = syncedCloudlog
        self.syncedQrz = syncedQrz
    }

    /// Custom decode so JSON logs written before the sync flags existed still
    /// load (the flags default to false when the keys are absent). Encoding
    /// stays synthesized and always writes both flags.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(Int64.self, forKey: .id)
        call = try c.decode(String.self, forKey: .call)
        gridsquare = try c.decode(String.self, forKey: .gridsquare)
        mode = try c.decode(String.self, forKey: .mode)
        rstSent = try c.decode(String.self, forKey: .rstSent)
        rstRcvd = try c.decode(String.self, forKey: .rstRcvd)
        qsoDate = try c.decode(String.self, forKey: .qsoDate)
        timeOn = try c.decode(String.self, forKey: .timeOn)
        qsoDateOff = try c.decode(String.self, forKey: .qsoDateOff)
        timeOff = try c.decode(String.self, forKey: .timeOff)
        band = try c.decode(String.self, forKey: .band)
        freq = try c.decode(String.self, forKey: .freq)
        stationCallsign = try c.decode(String.self, forKey: .stationCallsign)
        myGridsquare = try c.decode(String.self, forKey: .myGridsquare)
        comment = try c.decode(String.self, forKey: .comment)
        syncedCloudlog = try c.decodeIfPresent(Bool.self, forKey: .syncedCloudlog) ?? false
        syncedQrz = try c.decodeIfPresent(Bool.self, forKey: .syncedQrz) ?? false
    }
}
