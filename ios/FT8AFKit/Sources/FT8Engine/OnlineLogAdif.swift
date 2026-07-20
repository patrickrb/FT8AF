import Foundation

public extension Adif {
    /// Single-record ADIF body for online logbook uploads (Cloudlog/Wavelog
    /// "api/qso" and the QRZ logbook INSERT). Ports Android
    /// `ThirdPartyService.QSLRecordToADIF`: no `<eoh>` header, no QSL flags,
    /// same field order, freq passed through as an MHz string, and the comment
    /// always emitted (even when empty). Lengths are UTF-8 byte counts.
    static func singleRecord(_ r: QsoRecord) -> String {
        var out = ""
        append(&out, "call", r.call)
        append(&out, "gridsquare", r.gridsquare)
        append(&out, "mode", r.mode)
        append(&out, "rst_sent", r.rstSent)
        append(&out, "rst_rcvd", r.rstRcvd)
        append(&out, "qso_date", r.qsoDate)
        append(&out, "time_on", r.timeOn)
        append(&out, "band", r.band)
        append(&out, "qso_date_off", r.qsoDateOff)
        append(&out, "time_off", r.timeOff)
        append(&out, "freq", r.freq)
        append(&out, "station_callsign", r.stationCallsign)
        append(&out, "my_gridsquare", r.myGridsquare)
        out += "<comment:\(r.comment.utf8.count)>\(r.comment) <eor>\n"
        return out
    }

    /// `<name:byteLen>value ` — emitted only when the value is non-empty
    /// (matches Android's appendAdif / the null-checks in QSLRecordToADIF).
    private static func append(_ out: inout String, _ name: String, _ value: String) {
        guard !value.isEmpty else { return }
        out += "<\(name):\(value.utf8.count)>\(value) "
    }
}
