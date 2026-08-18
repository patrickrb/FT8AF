import FT8Engine
import Foundation
import Observation

// MARK: - Root state

@Observable @MainActor
final class AppState {
    let decode = DecodeState()
    let waterfall = WaterfallState()
    let logbook = LogbookState()
    let settings = SettingsState()
    let rig = RigState()
    let tx = TxState()
    let pota = PotaState()
    let toast = ToastState()

    /// The live engine is set once by `FT8AFApp` at startup. Views access it
    /// through `appState.engine` instead of a separate `@Environment` entry,
    /// which avoids fatal crashes when the Observable-environment lookup fails.
    var engine: LiveEngine?
}

// MARK: - Decode

enum DecodeFilter: String, CaseIterable {
    case all = "All"
    case cq = "CQ Calls"
    case cqPota = "CQ POTA"
    case newDxcc = "New DXCC"
    case newState = "New State"
    case needed = "Needed"
    case forMe = "For Me"
}

@Observable @MainActor
final class DecodeState {
    var messages: [DecodeMessage] = []
    var activeFilter: DecodeFilter = .all
    var selectedMessage: DecodeMessage?
    var compactMode: Bool = false
    var autoClear: Bool = false
}

/// UI-level decoded message with display-oriented fields. Phase 2+ will map
/// `FT8DSP.DecodedMessage` into this shape; for now it carries mock data only.
struct DecodeMessage: Identifiable, Equatable {
    let id = UUID()
    var utcTime: String
    var callFrom: String
    var callTo: String
    var snr: Int
    var freqHz: Float
    var grid: String
    var extra: String
    var slotIndex: Int
    /// Wall-clock arrival of the decode. Defaulted to construction time so
    /// every existing `DecodeMessage(...)` call site (LiveEngine creates
    /// messages the moment a slot decodes) gets the correct arrival without
    /// changes; the decode list derives the relative "now / 32s / 5m" age
    /// from it.
    var arrival: Date = Date()

    static let mock: [DecodeMessage] = [
        DecodeMessage(utcTime: "12:30:00", callFrom: "W1AW", callTo: "CQ", snr: -5, freqHz: 1120, grid: "FN31", extra: "FN31", slotIndex: 0),
        DecodeMessage(utcTime: "12:30:00", callFrom: "KD2OGR", callTo: "CQ POTA", snr: -12, freqHz: 890, grid: "FN20", extra: "K-1234", slotIndex: 0),
        DecodeMessage(utcTime: "12:30:00", callFrom: "JA1ABC", callTo: "CQ", snr: 3, freqHz: 1450, grid: "PM95", extra: "PM95", slotIndex: 0),
        DecodeMessage(utcTime: "12:29:45", callFrom: "DL4RCK", callTo: "W1AW", snr: -8, freqHz: 1120, grid: "JO31", extra: "+03", slotIndex: 1),
        DecodeMessage(utcTime: "12:29:45", callFrom: "VK3BDX", callTo: "CQ", snr: -18, freqHz: 2100, grid: "QF22", extra: "QF22", slotIndex: 1),
        DecodeMessage(utcTime: "12:29:45", callFrom: "EA4FKR", callTo: "CQ", snr: -2, freqHz: 530, grid: "IN80", extra: "IN80", slotIndex: 1),
        DecodeMessage(utcTime: "12:29:30", callFrom: "K5ABC", callTo: "JA1ABC", snr: 5, freqHz: 1450, grid: "EM12", extra: "R-08", slotIndex: 2),
        DecodeMessage(utcTime: "12:29:30", callFrom: "OH2BFO", callTo: "CQ", snr: -15, freqHz: 1780, grid: "KP20", extra: "KP20", slotIndex: 2),
        DecodeMessage(utcTime: "12:29:30", callFrom: "ZL1BYZ", callTo: "CQ", snr: -22, freqHz: 680, grid: "RF72", extra: "RF72", slotIndex: 2),
        DecodeMessage(utcTime: "12:29:15", callFrom: "PY2SEX", callTo: "W1AW", snr: -10, freqHz: 1120, grid: "GG87", extra: "RR73", slotIndex: 3),
        DecodeMessage(utcTime: "12:29:15", callFrom: "UA3ABC", callTo: "CQ", snr: 1, freqHz: 950, grid: "KO85", extra: "KO85", slotIndex: 3),
        DecodeMessage(utcTime: "12:29:15", callFrom: "VE3XYZ", callTo: "CQ", snr: -7, freqHz: 1300, grid: "FN03", extra: "FN03", slotIndex: 3),
    ]
}

// MARK: - Waterfall

@Observable @MainActor
final class WaterfallState {
    var rows: [[UInt8]] = []
    /// Optional UTC boundary label per row, kept in lockstep with `rows`:
    /// non-nil on the first row of each 15 s FT8 period ("HH:mm:ss" of the
    /// period start), nil elsewhere. `WaterfallCanvas` draws a divider + label
    /// at these rows.
    var rowTimestamps: [String?] = []
    var spectrum: [Float] = []
    /// Top of the audio band the current `rows`/`spectrum` were built to, in
    /// Hz. Maintained by the LiveEngine waterfall loop from the operator's
    /// spectrum-width setting; on a live width change the loop clears the
    /// old-width history and updates this.
    var displayMaxHz: Float = 3000
    /// RX input level of the most recent metering window (linear, 0..~1), for
    /// the level indicator in the waterfall info bar. Classified via
    /// `AudioInputLevel.fromPeakRms` in the view.
    var inputPeak: Float = 0
    var inputRms: Float = 0
    var txFreqHz: Float = 1500
    var isLive: Bool = false
    var noiseReduction: Bool = false
    var messageMarking: Bool = true
    var updateCount: Int = 0
}

// MARK: - QSO Conversation Log

enum QsoLogDirection: String {
    case tx = "TX"
    case rx = "RX"
    case busy = "BUSY"
}

struct QsoLogEntry: Identifiable {
    let id = UUID()
    let direction: QsoLogDirection
    let message: String
    let snr: Int?
    let utcTime: String
}

// MARK: - Logbook

@Observable @MainActor
final class LogbookState {
    var records: [QsoRecord] = []
    var totalCount: Int { records.count }
    var bandStats: [String: Int] {
        var stats: [String: Int] = [:]
        for r in records {
            stats[r.band, default: 0] += 1
        }
        return stats
    }
}

extension QsoRecord {
    static let mockRecords: [QsoRecord] = [
        QsoRecord(id: 1, call: "W1AW", gridsquare: "FN31", mode: "FT8", rstSent: "-05", rstRcvd: "-08",
                  qsoDate: "20260618", timeOn: "123000", qsoDateOff: "20260618", timeOff: "123115",
                  band: "20M", freq: "14.074", stationCallsign: "KD2OGR", myGridsquare: "FN20", comment: ""),
        QsoRecord(id: 2, call: "JA1ABC", gridsquare: "PM95", mode: "FT8", rstSent: "+03", rstRcvd: "-12",
                  qsoDate: "20260618", timeOn: "122800", qsoDateOff: "20260618", timeOff: "122930",
                  band: "15M", freq: "21.074", stationCallsign: "KD2OGR", myGridsquare: "FN20", comment: ""),
        QsoRecord(id: 3, call: "DL4RCK", gridsquare: "JO31", mode: "FT8", rstSent: "-10", rstRcvd: "-03",
                  qsoDate: "20260617", timeOn: "180000", qsoDateOff: "20260617", timeOff: "180130",
                  band: "40M", freq: "7.074", stationCallsign: "KD2OGR", myGridsquare: "FN20", comment: ""),
        QsoRecord(id: 4, call: "VK3BDX", gridsquare: "QF22", mode: "FT8", rstSent: "-18", rstRcvd: "-15",
                  qsoDate: "20260617", timeOn: "090000", qsoDateOff: "20260617", timeOff: "090130",
                  band: "20M", freq: "14.074", stationCallsign: "KD2OGR", myGridsquare: "FN20", comment: "Long path"),
        QsoRecord(id: 5, call: "PY2SEX", gridsquare: "GG87", mode: "FT8", rstSent: "-02", rstRcvd: "+05",
                  qsoDate: "20260616", timeOn: "220000", qsoDateOff: "20260616", timeOff: "220130",
                  band: "10M", freq: "28.074", stationCallsign: "KD2OGR", myGridsquare: "FN20", comment: ""),
        QsoRecord(id: 6, call: "OH2BFO", gridsquare: "KP20", mode: "FT8", rstSent: "-15", rstRcvd: "-10",
                  qsoDate: "20260615", timeOn: "140000", qsoDateOff: "20260615", timeOff: "140200",
                  band: "30M", freq: "10.136", stationCallsign: "KD2OGR", myGridsquare: "FN20", comment: ""),
        QsoRecord(id: 7, call: "EA4FKR", gridsquare: "IN80", mode: "FT8", rstSent: "-05", rstRcvd: "+01",
                  qsoDate: "20260614", timeOn: "160000", qsoDateOff: "20260614", timeOff: "160200",
                  band: "17M", freq: "18.100", stationCallsign: "KD2OGR", myGridsquare: "FN20", comment: ""),
    ]
}

// MARK: - Settings

enum RigModel: String, CaseIterable, Identifiable {
    case none = "None"
    case ic705 = "IC-705"
    case ic7300 = "IC-7300"
    case ft991a = "FT-991A"
    case ft710 = "FT-710"
    case ft450d = "FT-450D"
    case ts590s = "TS-590S"
    case k3 = "K3/K3S"
    case kx3 = "KX3"
    case flex6000 = "Flex 6000"
    case x6100 = "X6100"

    var id: String { rawValue }
}

enum PttMode: String, CaseIterable, Identifiable {
    case vox = "VOX"
    case cat = "CAT"
    case rts = "RTS"
    case dtr = "DTR"

    var id: String { rawValue }
}

@Observable @MainActor
final class SettingsState {
    var myCall: String = ""
    var myGrid: String = ""
    var band: String = "20M"
    var rigModel: RigModel = .none
    var pttMode: PttMode = .vox
    var txPowerWatts: Int = 5
    var txVolume: Int = 80
    var showOnlyCQ: Bool = false
    var dxOnly: Bool = false
    var autoLog: Bool = true
    var blockedCallsigns: [String] = []
    var pttDelayMs: Int = 0
    var txDelayMs: Int = 0
    var lateStartToleranceMs: Int = 2360
    // Auto-sequence
    var huntCallsCQ: Bool = false
    var autoCallFollow: Bool = false
    var earlyDecode: Bool = false
    var autoCQAfterQSO: Bool = false
    // TX safety
    var txWatchdogMin: Int = 0          // 0 = off
    var stopAfterAttempts: Int = 0      // 0 = off
    // Radio
    // Default 3500 matches the pre-configurable fixed span (desktop WF_MAX_HZ)
    // so updating doesn't silently narrow the displayed band.
    var spectrumWidthHz: Int = 3500
    var enabledBands: [String] = ["160M","80M","40M","30M","20M","17M","15M","12M","10M","6M"]
    // WSJT-X UDP interface (GridTracker / JTAlert / N1MM / Log4OM interop).
    var udpEnabled: Bool = false
    var udpHost: String = "127.0.0.1"
    var udpPort: Int = 2237
    var udpAcceptRequests: Bool = false
    // Online logging (Cloudlog/Wavelog family + QRZ.com logbook + PSK Reporter)
    var cloudlogEnabled: Bool = false
    var cloudlogUrl: String = ""
    var cloudlogApiKey: String = ""
    var cloudlogStationId: String = ""
    var qrzLogbookEnabled: Bool = false
    var qrzLogbookApiKey: String = ""
    var pskReporterEnabled: Bool = false
    // Decode highlights & filters
    var highlightNewDxcc: Bool = true
    var highlightNewState: Bool = true
    var highlightNewGrid: Bool = true
    var highlightNewBand: Bool = true
    var highlightWorked: Bool = true
    var continentFilter: String = "All"   // All / NA / SA / EU / AF / AS / OC / AN
    var distanceInMiles: Bool = false
    // Tune
    var tuneTimeoutSec: Int = 30
    // Preferred audio input port name ("" = system default); matched by name
    // so the choice survives replug/relaunch.
    var preferredInputPort: String = ""
}

// MARK: - Rig

enum ConnectionStatus: String {
    case disconnected = "Disconnected"
    case connecting = "Connecting"
    case connected = "Connected"
}

@Observable @MainActor
final class RigState {
    var connectionStatus: ConnectionStatus = .disconnected
    var currentFreqHz: UInt64 = 14_074_000
}

// MARK: - TX

/// App-local TX progress used by the Phase-1 UI. Named distinctly from
/// `FT8Engine.TxStage` (imported above) to avoid shadowing the engine type
/// when QSO wiring lands in a later phase.
enum TxUIStage: String {
    case idle
    case cqSent
    case reportSent
    case rrSent
    case complete
}

@Observable @MainActor
final class TxState {
    var stage: TxUIStage = .idle
    var isActivated: Bool = false
    var huntEnabled: Bool = false
    var slotParity: Int = 0
    var isTransmitting: Bool = false
    var expanded: Bool = false
    var targetCall: String = ""
    var txMessage: String = ""
    var qsoCompletedAt: Date?
    var conversationLog: [QsoLogEntry] = []
    /// Set by the engine when our CQ is answered, to auto-open QsoSheet.
    var autoOpenMessage: DecodeMessage?
    /// The QSO engine's raw sequencer stage, mirrored by LiveEngine for the
    /// TX message-selector chips (`TxStageSelector`).
    var qsoStage: TxStage = .idle
    /// Last SNR heard from the current target (for the Active QSO header).
    var targetSnr: Int?
    /// Callsigns waiting their turn (mirror of LiveEngine's `CallerQueue`).
    var queuedCallers: [String] = []
    /// TUNE carrier state: latched steady tone at the TX audio frequency.
    var isTuning: Bool = false
    var tuneRemainingSec: Int = 0
}

// MARK: - POTA

@Observable @MainActor
final class PotaState {
    /// Start-form inputs (park reference + optional notes).
    var parkInput: String = ""
    var notes: String = ""
    /// All activation sessions (past + at most one active). Loaded from
    /// `PotaActivationStore` the first time the POTA screen appears and
    /// persisted on every change; an unfinished activation therefore survives
    /// app restarts.
    var activations: [PotaActivationRecord] = []
    /// Whether `activations` has been loaded from disk yet (the POTA screen
    /// loads lazily on first appearance).
    var activationsLoaded: Bool = false

    /// The in-progress activation, if any (endedAtMs == nil).
    var current: PotaActivationRecord? { activations.first { $0.isActive } }
    var isActivating: Bool { current != nil }
}

// MARK: - Settings Persistence

enum SettingsPersistence {
    private static let prefix = "ft8af_"

    @MainActor static func save(_ s: SettingsState) {
        let d = UserDefaults.standard
        d.set(s.myCall, forKey: key("myCall"))
        d.set(s.myGrid, forKey: key("myGrid"))
        d.set(s.band, forKey: key("band"))
        d.set(s.rigModel.rawValue, forKey: key("rigModel"))
        d.set(s.pttMode.rawValue, forKey: key("pttMode"))
        d.set(s.txPowerWatts, forKey: key("txPowerWatts"))
        d.set(s.txVolume, forKey: key("txVolume"))
        d.set(s.showOnlyCQ, forKey: key("showOnlyCQ"))
        d.set(s.dxOnly, forKey: key("dxOnly"))
        d.set(s.autoLog, forKey: key("autoLog"))
        d.set(s.blockedCallsigns, forKey: key("blockedCallsigns"))
        d.set(s.pttDelayMs, forKey: key("pttDelayMs"))
        d.set(s.txDelayMs, forKey: key("txDelayMs"))
        d.set(s.lateStartToleranceMs, forKey: key("lateStartToleranceMs"))
        d.set(s.huntCallsCQ, forKey: key("huntCallsCQ"))
        d.set(s.autoCallFollow, forKey: key("autoCallFollow"))
        d.set(s.earlyDecode, forKey: key("earlyDecode"))
        d.set(s.autoCQAfterQSO, forKey: key("autoCQAfterQSO"))
        d.set(s.txWatchdogMin, forKey: key("txWatchdogMin"))
        d.set(s.stopAfterAttempts, forKey: key("stopAfterAttempts"))
        d.set(s.spectrumWidthHz, forKey: key("spectrumWidthHz"))
        d.set(s.enabledBands, forKey: key("enabledBands"))
        d.set(s.udpEnabled, forKey: key("udpEnabled"))
        d.set(s.udpHost, forKey: key("udpHost"))
        d.set(s.udpPort, forKey: key("udpPort"))
        d.set(s.udpAcceptRequests, forKey: key("udpAcceptRequests"))
        d.set(s.cloudlogEnabled, forKey: key("cloudlogEnabled"))
        d.set(s.cloudlogUrl, forKey: key("cloudlogUrl"))
        d.set(s.cloudlogApiKey, forKey: key("cloudlogApiKey"))
        d.set(s.cloudlogStationId, forKey: key("cloudlogStationId"))
        d.set(s.qrzLogbookEnabled, forKey: key("qrzLogbookEnabled"))
        d.set(s.qrzLogbookApiKey, forKey: key("qrzLogbookApiKey"))
        d.set(s.pskReporterEnabled, forKey: key("pskReporterEnabled"))
        d.set(s.highlightNewDxcc, forKey: key("highlightNewDxcc"))
        d.set(s.highlightNewState, forKey: key("highlightNewState"))
        d.set(s.highlightNewGrid, forKey: key("highlightNewGrid"))
        d.set(s.highlightNewBand, forKey: key("highlightNewBand"))
        d.set(s.highlightWorked, forKey: key("highlightWorked"))
        d.set(s.continentFilter, forKey: key("continentFilter"))
        d.set(s.distanceInMiles, forKey: key("distanceInMiles"))
        d.set(s.tuneTimeoutSec, forKey: key("tuneTimeoutSec"))
        d.set(s.preferredInputPort, forKey: key("preferredInputPort"))
    }

    @MainActor static func load(into s: SettingsState) {
        let d = UserDefaults.standard
        if let v = d.string(forKey: key("myCall")) { s.myCall = v }
        if let v = d.string(forKey: key("myGrid")) { s.myGrid = v }
        if let v = d.string(forKey: key("band")) { s.band = v }
        if let v = d.string(forKey: key("rigModel")),
           let m = RigModel(rawValue: v) { s.rigModel = m }
        if let v = d.string(forKey: key("pttMode")),
           let m = PttMode(rawValue: v) { s.pttMode = m }
        if d.object(forKey: key("txPowerWatts")) != nil {
            s.txPowerWatts = d.integer(forKey: key("txPowerWatts"))
        }
        if d.object(forKey: key("txVolume")) != nil {
            s.txVolume = d.integer(forKey: key("txVolume"))
        }
        if d.object(forKey: key("showOnlyCQ")) != nil {
            s.showOnlyCQ = d.bool(forKey: key("showOnlyCQ"))
        }
        if d.object(forKey: key("dxOnly")) != nil {
            s.dxOnly = d.bool(forKey: key("dxOnly"))
        }
        if d.object(forKey: key("autoLog")) != nil {
            s.autoLog = d.bool(forKey: key("autoLog"))
        }
        if let blocked = d.stringArray(forKey: key("blockedCallsigns")) {
            s.blockedCallsigns = blocked
        }
        if d.object(forKey: key("pttDelayMs")) != nil {
            s.pttDelayMs = d.integer(forKey: key("pttDelayMs"))
        }
        if d.object(forKey: key("txDelayMs")) != nil {
            s.txDelayMs = d.integer(forKey: key("txDelayMs"))
        }
        if d.object(forKey: key("lateStartToleranceMs")) != nil {
            s.lateStartToleranceMs = d.integer(forKey: key("lateStartToleranceMs"))
        }
        if d.object(forKey: key("huntCallsCQ")) != nil {
            s.huntCallsCQ = d.bool(forKey: key("huntCallsCQ"))
        }
        if d.object(forKey: key("autoCallFollow")) != nil {
            s.autoCallFollow = d.bool(forKey: key("autoCallFollow"))
        }
        if d.object(forKey: key("earlyDecode")) != nil {
            s.earlyDecode = d.bool(forKey: key("earlyDecode"))
        }
        if d.object(forKey: key("autoCQAfterQSO")) != nil {
            s.autoCQAfterQSO = d.bool(forKey: key("autoCQAfterQSO"))
        }
        if d.object(forKey: key("txWatchdogMin")) != nil {
            s.txWatchdogMin = d.integer(forKey: key("txWatchdogMin"))
        }
        if d.object(forKey: key("stopAfterAttempts")) != nil {
            s.stopAfterAttempts = d.integer(forKey: key("stopAfterAttempts"))
        }
        if d.object(forKey: key("spectrumWidthHz")) != nil {
            s.spectrumWidthHz = d.integer(forKey: key("spectrumWidthHz"))
        }
        if let bands = d.stringArray(forKey: key("enabledBands")) {
            s.enabledBands = bands
        }
        if d.object(forKey: key("udpEnabled")) != nil {
            s.udpEnabled = d.bool(forKey: key("udpEnabled"))
        }
        if let v = d.string(forKey: key("udpHost")), !v.isEmpty { s.udpHost = v }
        if d.object(forKey: key("udpPort")) != nil {
            let p = d.integer(forKey: key("udpPort"))
            if p > 0 && p <= 65535 { s.udpPort = p }
        }
        if d.object(forKey: key("udpAcceptRequests")) != nil {
            s.udpAcceptRequests = d.bool(forKey: key("udpAcceptRequests"))
        }
        if d.object(forKey: key("cloudlogEnabled")) != nil {
            s.cloudlogEnabled = d.bool(forKey: key("cloudlogEnabled"))
        }
        if let v = d.string(forKey: key("cloudlogUrl")) { s.cloudlogUrl = v }
        if let v = d.string(forKey: key("cloudlogApiKey")) { s.cloudlogApiKey = v }
        if let v = d.string(forKey: key("cloudlogStationId")) { s.cloudlogStationId = v }
        if d.object(forKey: key("qrzLogbookEnabled")) != nil {
            s.qrzLogbookEnabled = d.bool(forKey: key("qrzLogbookEnabled"))
        }
        if let v = d.string(forKey: key("qrzLogbookApiKey")) { s.qrzLogbookApiKey = v }
        if d.object(forKey: key("pskReporterEnabled")) != nil {
            s.pskReporterEnabled = d.bool(forKey: key("pskReporterEnabled"))
        }
        if d.object(forKey: key("highlightNewDxcc")) != nil {
            s.highlightNewDxcc = d.bool(forKey: key("highlightNewDxcc"))
        }
        if d.object(forKey: key("highlightNewState")) != nil {
            s.highlightNewState = d.bool(forKey: key("highlightNewState"))
        }
        if d.object(forKey: key("highlightNewGrid")) != nil {
            s.highlightNewGrid = d.bool(forKey: key("highlightNewGrid"))
        }
        if d.object(forKey: key("highlightNewBand")) != nil {
            s.highlightNewBand = d.bool(forKey: key("highlightNewBand"))
        }
        if d.object(forKey: key("highlightWorked")) != nil {
            s.highlightWorked = d.bool(forKey: key("highlightWorked"))
        }
        if let v = d.string(forKey: key("continentFilter")), !v.isEmpty {
            s.continentFilter = v
        }
        if d.object(forKey: key("distanceInMiles")) != nil {
            s.distanceInMiles = d.bool(forKey: key("distanceInMiles"))
        }
        if d.object(forKey: key("tuneTimeoutSec")) != nil {
            s.tuneTimeoutSec = d.integer(forKey: key("tuneTimeoutSec"))
        }
        if let v = d.string(forKey: key("preferredInputPort")) {
            s.preferredInputPort = v
        }
    }

    private static func key(_ name: String) -> String { prefix + name }
}
