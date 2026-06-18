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
        comment: String
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
    }
}
