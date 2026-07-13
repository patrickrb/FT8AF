import Foundation

/// One POTA activation session, persisted as JSON by the app's activation store.
/// Mirrors Android's `pota_activation` row (`PotaActivation` model): a park ref
/// (comma-separated for two-fers), a start/end window, and the QSO count frozen
/// at end time. An open activation has `endedAtMs == nil`.
public struct PotaActivationRecord: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var parkRef: String
    public var notes: String
    public var startedAtMs: Int64
    public var endedAtMs: Int64?
    /// QSO count frozen when the activation ends. While active, derive the live
    /// count from the logbook via `PotaQsoWindow.qsoCount` instead.
    public var qsoCount: Int

    public init(
        id: String = UUID().uuidString,
        parkRef: String,
        notes: String = "",
        startedAtMs: Int64,
        endedAtMs: Int64? = nil,
        qsoCount: Int = 0
    ) {
        self.id = id
        self.parkRef = parkRef
        self.notes = notes
        self.startedAtMs = startedAtMs
        self.endedAtMs = endedAtMs
        self.qsoCount = qsoCount
    }

    public var isActive: Bool { endedAtMs == nil }

    /// Individual park references (splits comma-separated `parkRef`), matching
    /// Android's multi-park ("two-fer") handling.
    public var parkRefs: [String] {
        parkRef.split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    /// Human-readable display: "K-1234 + K-5678".
    public var parkRefsDisplay: String { parkRefs.joined(separator: " + ") }
}

/// Past activations to list (newest first). Drops any still-active row so the
/// in-progress activation is never shown as "past" — a direct port of Android's
/// `historyForDisplay`.
public func potaHistoryForDisplay(_ all: [PotaActivationRecord]) -> [PotaActivationRecord] {
    all.filter { !$0.isActive }.sorted { $0.startedAtMs > $1.startedAtMs }
}

/// Scopes logged QSOs to a POTA activation's time window. Port of Android's
/// `PotaQsoWindow` (shared by its DAO and ADIF exporter): QSO rows are compared
/// as fixed-width `yyyyMMddHHmmss` UTC stamps built from `qso_date` + a
/// normalized `time_on`, against stamps of the activation's start/end instants.
public enum PotaQsoWindow {

    /// Upper bound for an open (still-active) activation that has no end time.
    public static let openEnd = "99991231235959"

    /// Format an epoch-millis instant as the matching `yyyyMMddHHmmss` UTC stamp.
    public static func stamp(ms: Int64) -> String {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.timeZone = TimeZone(identifier: "GMT")
        fmt.dateFormat = "yyyyMMddHHmmss"
        return fmt.string(from: Date(timeIntervalSince1970: Double(ms) / 1000.0))
    }

    /// Fixed-width stamp for one QSO row. `time_on` is stored variable-width:
    /// app-logged rows are HHMMSS, but imported ADIF rows may carry HHMM or drop
    /// a leading zero ("815" for 08:15). Same normalization as Android's
    /// `ROW_STAMP` SQL: pad even-width times to HHMMSS with trailing zeros;
    /// prepend a zero to odd-width times (a dropped leading zero) first.
    public static func rowStamp(qsoDate: String, timeOn: String) -> String {
        let time: String
        if timeOn.isEmpty {
            time = "000000"
        } else if timeOn.count % 2 == 1 {
            time = String(("0" + timeOn + "000000").prefix(6))
        } else {
            time = String((timeOn + "000000").prefix(6))
        }
        return qsoDate + time
    }

    /// The records logged inside the activation window, in QSO order.
    /// `endedAtMs == nil` (still active) uses the far-future `openEnd` bound.
    public static func qsos(
        in records: [QsoRecord], startedAtMs: Int64, endedAtMs: Int64?
    ) -> [QsoRecord] {
        let start = stamp(ms: startedAtMs)
        let end = endedAtMs.map { stamp(ms: $0) } ?? openEnd
        return records
            .filter { r in
                let s = rowStamp(qsoDate: r.qsoDate, timeOn: r.timeOn)
                return s >= start && s <= end
            }
            .sorted {
                rowStamp(qsoDate: $0.qsoDate, timeOn: $0.timeOn)
                    < rowStamp(qsoDate: $1.qsoDate, timeOn: $1.timeOn)
            }
    }

    /// Live QSO count for an activation (drives the 10-QSO progress bar).
    public static func qsoCount(
        records: [QsoRecord], startedAtMs: Int64, endedAtMs: Int64?
    ) -> Int {
        qsos(in: records, startedAtMs: startedAtMs, endedAtMs: endedAtMs).count
    }
}
