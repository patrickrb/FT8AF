import Foundation

/// Per-park POTA ADIF export — a port of Android's
/// `PotaAdifExporter.buildActivationAdif`. New functions only; the existing
/// `Adif.export`/`Adif.parse` used by the general logbook are untouched.
extension Adif {

    /// One park's ADIF document plus the filename it should be shared under.
    /// Mirrors Android's `PotaAdifExporter.NamedAdif`.
    public struct PotaDocument: Equatable, Sendable {
        public let parkRef: String
        public let filename: String
        public let content: String

        public init(parkRef: String, filename: String, content: String) {
            self.parkRef = parkRef
            self.filename = filename
            self.content = content
        }
    }

    /// Build one ADIF document per park reference for `activation`, from the
    /// QSOs logged inside its time window (same `PotaQsoWindow` scoping the
    /// live progress count uses, so the export always agrees with the UI).
    ///
    /// pota.app's uploader expects each QSO to carry MY_SIG=POTA /
    /// MY_SIG_INFO=<park ref>; for multi-park activations (two-fers) each
    /// document contains the same QSOs with MY_SIG_INFO pinned to that single
    /// park. Returns [] when no QSOs fall in the window — callers must treat
    /// that as "nothing to share" rather than emitting header-only files.
    public static func potaActivationExport(
        records: [QsoRecord], activation: PotaActivationRecord
    ) -> [PotaDocument] {
        let rows = PotaQsoWindow.qsos(
            in: records,
            startedAtMs: activation.startedAtMs,
            endedAtMs: activation.endedAtMs
        )
        if rows.isEmpty { return [] }

        let ts = potaFilenameStamp(ms: activation.startedAtMs)
        return activation.parkRefs.map { parkRef in
            var out = "FT8AF POTA Activation \(parkRef)\n"
            out += "<ADIF_VER:5>3.1.4 "
            out += "<PROGRAMID:5>FT8AF "
            out += "<EOH>\n"
            for r in rows {
                potaField(&out, "CALL", r.call)
                potaField(&out, "GRIDSQUARE", r.gridsquare)
                potaMode(&out, r.mode)
                potaField(&out, "BAND", r.band)
                potaField(&out, "FREQ", r.freq)
                potaField(&out, "RST_SENT", r.rstSent)
                potaField(&out, "RST_RCVD", r.rstRcvd)
                potaField(&out, "QSO_DATE", r.qsoDate)
                potaField(&out, "TIME_ON", r.timeOn)
                potaField(&out, "TIME_OFF", r.timeOff)
                potaField(&out, "STATION_CALLSIGN", r.stationCallsign)
                potaField(&out, "MY_GRIDSQUARE", r.myGridsquare)
                potaField(&out, "MY_SIG", "POTA")
                // Pinned to this single park (not the comma-separated activation ref).
                potaField(&out, "MY_SIG_INFO", parkRef)
                // Park-to-park: the worked station's park, stamped at log time when
                // they were a spotted activator. Empty (omitted) for a normal QSO.
                // Matches Android's buildActivationAdif SIG/SIG_INFO emission.
                potaField(&out, "SIG", r.sig)
                potaField(&out, "SIG_INFO", r.sigInfo)
                out += "<EOR>\n"
            }
            return PotaDocument(
                parkRef: parkRef,
                filename: "pota-\(parkRef)-\(ts).adi",
                content: out
            )
        }
    }

    /// FT4/FT2 are ADIF *submodes* of MFSK — a bare `<MODE:3>FT2` is what
    /// pota.app rejects as invalid. For those, emit MODE=MFSK + SUBMODE; every
    /// other mode (FT8 is a first-class ADIF mode) passes through unchanged.
    /// Port of Android's `AdifFormat.mfskSubmode` + `PotaAdifExporter.adifMode`.
    internal static func mfskSubmode(_ mode: String) -> String? {
        let normalized = mode.trimmingCharacters(in: .whitespaces).uppercased()
        return (normalized == "FT4" || normalized == "FT2") ? normalized : nil
    }

    private static func potaMode(_ out: inout String, _ mode: String) {
        if let submode = mfskSubmode(mode) {
            potaField(&out, "MODE", "MFSK")
            potaField(&out, "SUBMODE", submode)
        } else {
            potaField(&out, "MODE", mode)
        }
    }

    /// `<NAME:byteLen>value ` — length in UTF-8 bytes (not characters), skipping
    /// empty values, exactly like Android's `PotaAdifExporter.adifField`.
    private static func potaField(_ out: inout String, _ name: String, _ value: String) {
        if value.isEmpty { return }
        out += "<\(name):\(value.utf8.count)>\(value) "
    }

    /// "yyyyMMdd-HHmm" stamp for the export filename. Rendered in UTC so the
    /// same activation always produces the same filename regardless of the
    /// device timezone (Android uses local time here; the difference is
    /// cosmetic — the filename never reaches the POTA parser).
    internal static func potaFilenameStamp(ms: Int64) -> String {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.timeZone = TimeZone(identifier: "GMT")
        fmt.dateFormat = "yyyyMMdd-HHmm"
        return fmt.string(from: Date(timeIntervalSince1970: Double(ms) / 1000.0))
    }
}
