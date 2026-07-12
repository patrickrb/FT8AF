import Foundation

/// Byte-exact (de)serialization of the WSJT-X UDP message protocol.
///
/// WSJT-X broadcasts a documented binary protocol over UDP that GridTracker,
/// JTAlert, N1MM+, Log4OM etc. consume, and accepts a few request messages so
/// those apps can drive it. This speaks that exact wire format so FT8AF is a
/// drop-in source/sink.
///
/// The framing is a bespoke big-endian Qt-`QDataStream` layout (NOT JSON), so
/// byte-for-byte correctness is the whole game — every encoder here is pinned by
/// the same golden vectors as the desktop (Rust) and Android (Kotlin) codecs
/// (see `docs/wsjtx-udp.md` and `WsjtxCodecTests`). Pure, no I/O —
/// `WsjtxUdpService` owns the socket.
///
/// Wire primitives (all big-endian): u32/u64/i32/i64/f64, bool(1 byte); string =
/// u32 byte-length (0xFFFFFFFF = null) then UTF-8 bytes (empty = length 0);
/// datetime (UTC) = i64 Julian day, u32 ms-since-midnight, u8=1. Every datagram:
/// u32 magic 0xADBCCBDA, u32 schema, u32 type, string id, then type fields.
public enum WsjtxCodec {
    public static let magic: UInt32 = 0xADBC_CBDA
    public static let schema: UInt32 = 3
    public static let clientId = "FT8AF"

    // Message type numbers.
    public static let tHeartbeat: UInt32 = 0
    public static let tStatus: UInt32 = 1
    public static let tDecode: UInt32 = 2
    public static let tClear: UInt32 = 3
    public static let tReply: UInt32 = 4
    public static let tQsoLogged: UInt32 = 5
    public static let tReplay: UInt32 = 7
    public static let tHaltTx: UInt32 = 8
    public static let tFreeText: UInt32 = 9
    public static let tLoggedAdif: UInt32 = 12

    // MARK: - outbound field carriers

    public struct Status {
        public var dialFreqHz: UInt64
        public var mode: String
        public var dxCall: String
        public var report: String
        public var txMode: String
        public var txEnabled: Bool
        public var transmitting: Bool
        public var decoding: Bool
        public var rxDf: UInt32
        public var txDf: UInt32
        public var deCall: String
        public var deGrid: String
        public var dxGrid: String
        public var txWatchdog: Bool
        public var subMode: String
        public var fastMode: Bool
        public var specialOpMode: UInt8
        public var freqTolerance: UInt32
        public var trPeriod: UInt32
        public var configName: String
        public var txMessage: String

        public init(
            dialFreqHz: UInt64, mode: String, dxCall: String, report: String,
            txMode: String, txEnabled: Bool, transmitting: Bool, decoding: Bool,
            rxDf: UInt32, txDf: UInt32, deCall: String, deGrid: String, dxGrid: String,
            txWatchdog: Bool, subMode: String, fastMode: Bool, specialOpMode: UInt8,
            freqTolerance: UInt32, trPeriod: UInt32, configName: String, txMessage: String
        ) {
            self.dialFreqHz = dialFreqHz; self.mode = mode; self.dxCall = dxCall
            self.report = report; self.txMode = txMode; self.txEnabled = txEnabled
            self.transmitting = transmitting; self.decoding = decoding; self.rxDf = rxDf
            self.txDf = txDf; self.deCall = deCall; self.deGrid = deGrid; self.dxGrid = dxGrid
            self.txWatchdog = txWatchdog; self.subMode = subMode; self.fastMode = fastMode
            self.specialOpMode = specialOpMode; self.freqTolerance = freqTolerance
            self.trPeriod = trPeriod; self.configName = configName; self.txMessage = txMessage
        }
    }

    public struct Decode {
        public var isNew: Bool
        public var timeMs: UInt32
        public var snr: Int32
        public var deltaTime: Double
        public var deltaFreq: UInt32
        public var mode: String
        public var message: String
        public var lowConfidence: Bool
        public var offAir: Bool

        public init(
            isNew: Bool, timeMs: UInt32, snr: Int32, deltaTime: Double, deltaFreq: UInt32,
            mode: String, message: String, lowConfidence: Bool, offAir: Bool
        ) {
            self.isNew = isNew; self.timeMs = timeMs; self.snr = snr
            self.deltaTime = deltaTime; self.deltaFreq = deltaFreq; self.mode = mode
            self.message = message; self.lowConfidence = lowConfidence; self.offAir = offAir
        }
    }

    /// A UTC instant as WSJT-X serializes it.
    public struct DateTime: Equatable {
        public var julianDay: Int64
        public var msOfDay: UInt32
        public init(julianDay: Int64, msOfDay: UInt32) {
            self.julianDay = julianDay; self.msOfDay = msOfDay
        }
        /// Build from ADIF `YYYYMMDD` + `HHMMSS` (UTC); zeroed on bad input.
        public static func fromAdif(_ dateYyyymmdd: String, _ timeHhmmss: String) -> DateTime {
            DateTime(
                julianDay: julianDayFromYyyymmdd(dateYyyymmdd) ?? 0,
                msOfDay: msOfDayFromHhmmss(timeHhmmss) ?? 0
            )
        }
    }

    public struct QsoLogged {
        public var timeOff: DateTime
        public var dxCall: String
        public var dxGrid: String
        public var txFreqHz: UInt64
        public var mode: String
        public var reportSent: String
        public var reportReceived: String
        public var txPower: String
        public var comments: String
        public var name: String
        public var timeOn: DateTime
        public var operatorCall: String
        public var myCall: String
        public var myGrid: String
        public var exchangeSent: String
        public var exchangeReceived: String
        public var propMode: String

        public init(
            timeOff: DateTime, dxCall: String, dxGrid: String, txFreqHz: UInt64, mode: String,
            reportSent: String, reportReceived: String, txPower: String, comments: String,
            name: String, timeOn: DateTime, operatorCall: String, myCall: String, myGrid: String,
            exchangeSent: String, exchangeReceived: String, propMode: String
        ) {
            self.timeOff = timeOff; self.dxCall = dxCall; self.dxGrid = dxGrid
            self.txFreqHz = txFreqHz; self.mode = mode; self.reportSent = reportSent
            self.reportReceived = reportReceived; self.txPower = txPower; self.comments = comments
            self.name = name; self.timeOn = timeOn; self.operatorCall = operatorCall
            self.myCall = myCall; self.myGrid = myGrid; self.exchangeSent = exchangeSent
            self.exchangeReceived = exchangeReceived; self.propMode = propMode
        }
    }

    // MARK: - writer

    private struct Writer {
        var data = Data()
        mutating func u8(_ v: UInt8) { data.append(v) }
        mutating func bool(_ v: Bool) { data.append(v ? 1 : 0) }
        mutating func u32(_ v: UInt32) { var b = v.bigEndian; withUnsafeBytes(of: &b) { data.append(contentsOf: $0) } }
        mutating func i32(_ v: Int32) { u32(UInt32(bitPattern: v)) }
        mutating func u64(_ v: UInt64) { var b = v.bigEndian; withUnsafeBytes(of: &b) { data.append(contentsOf: $0) } }
        mutating func i64(_ v: Int64) { u64(UInt64(bitPattern: v)) }
        mutating func f64(_ v: Double) { u64(v.bitPattern) }
        mutating func string(_ s: String) {
            let b = Array(s.utf8)
            u32(UInt32(b.count))
            data.append(contentsOf: b)
        }
        mutating func datetime(_ dt: DateTime) {
            i64(dt.julianDay)
            u32(dt.msOfDay)
            u8(1) // Qt::UTC
        }
    }

    private static func begin(_ type: UInt32) -> Writer {
        var w = Writer()
        w.u32(magic)
        w.u32(schema)
        w.u32(type)
        w.string(clientId)
        return w
    }

    // MARK: - outbound builders

    public static func heartbeat(version: String, revision: String) -> Data {
        var w = begin(tHeartbeat)
        w.u32(schema) // max schema
        w.string(version)
        w.string(revision)
        return w.data
    }

    public static func status(_ s: Status) -> Data {
        var w = begin(tStatus)
        w.u64(s.dialFreqHz)
        w.string(s.mode)
        w.string(s.dxCall)
        w.string(s.report)
        w.string(s.txMode)
        w.bool(s.txEnabled)
        w.bool(s.transmitting)
        w.bool(s.decoding)
        w.u32(s.rxDf)
        w.u32(s.txDf)
        w.string(s.deCall)
        w.string(s.deGrid)
        w.string(s.dxGrid)
        w.bool(s.txWatchdog)
        w.string(s.subMode)
        w.bool(s.fastMode)
        w.u8(s.specialOpMode)
        w.u32(s.freqTolerance)
        w.u32(s.trPeriod)
        w.string(s.configName)
        w.string(s.txMessage)
        return w.data
    }

    public static func decode(_ d: Decode) -> Data {
        var w = begin(tDecode)
        w.bool(d.isNew)
        w.u32(d.timeMs)
        w.i32(d.snr)
        w.f64(d.deltaTime)
        w.u32(d.deltaFreq)
        w.string(d.mode)
        w.string(d.message)
        w.bool(d.lowConfidence)
        w.bool(d.offAir)
        return w.data
    }

    public static func clear() -> Data { begin(tClear).data }

    public static func qsoLogged(_ q: QsoLogged) -> Data {
        var w = begin(tQsoLogged)
        w.datetime(q.timeOff)
        w.string(q.dxCall)
        w.string(q.dxGrid)
        w.u64(q.txFreqHz)
        w.string(q.mode)
        w.string(q.reportSent)
        w.string(q.reportReceived)
        w.string(q.txPower)
        w.string(q.comments)
        w.string(q.name)
        w.datetime(q.timeOn)
        w.string(q.operatorCall)
        w.string(q.myCall)
        w.string(q.myGrid)
        w.string(q.exchangeSent)
        w.string(q.exchangeReceived)
        w.string(q.propMode)
        return w.data
    }

    public static func loggedAdif(_ adif: String) -> Data {
        var w = begin(tLoggedAdif)
        w.string(adif)
        return w.data
    }

    // MARK: - inbound

    public enum Inbound: Equatable {
        case reply(call: String, grid: String, snr: Int32)
        case replay
        case haltTx(autoOnly: Bool)
        case freeText(text: String, send: Bool)
    }

    /// Bounds-checked big-endian reader; every read returns nil past the end.
    private struct Reader {
        let b: [UInt8]
        var pos = 0
        init(_ data: Data) { b = [UInt8](data) }
        mutating func take(_ n: Int) -> ArraySlice<UInt8>? {
            guard pos + n <= b.count else { return nil }
            let s = b[pos ..< pos + n]; pos += n; return s
        }
        mutating func u8() -> UInt8? { take(1)?.first }
        mutating func bool() -> Bool? { u8().map { $0 != 0 } }
        mutating func u32() -> UInt32? {
            guard let s = take(4) else { return nil }
            return s.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        }
        mutating func i32() -> Int32? { u32().map { Int32(bitPattern: $0) } }
        mutating func f64() -> Double? {
            guard let s = take(8) else { return nil }
            return Double(bitPattern: s.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) })
        }
        mutating func string() -> String? {
            guard let len = u32() else { return nil }
            if len == 0xFFFF_FFFF { return "" }
            guard let s = take(Int(len)) else { return nil }
            return String(decoding: s, as: UTF8.self)
        }
    }

    /// Parse an inbound datagram into intent. Returns nil for anything we don't
    /// act on (bad magic, unknown/outbound-only type, truncated buffer).
    public static func parseInbound(_ data: Data) -> Inbound? {
        var r = Reader(data)
        guard r.u32() == magic else { return nil }
        guard r.u32() != nil else { return nil } // schema
        guard let type = r.u32() else { return nil }
        guard r.string() != nil else { return nil } // id
        switch type {
        case tReply:
            guard r.u32() != nil else { return nil }       // time
            guard let snr = r.i32() else { return nil }
            guard r.f64() != nil else { return nil }        // dt
            guard r.u32() != nil else { return nil }        // df
            guard r.string() != nil else { return nil }     // mode
            guard let message = r.string() else { return nil }
            guard let target = parseReplyTarget(message) else { return nil }
            return .reply(call: target.0, grid: target.1, snr: snr)
        case tReplay:
            return .replay
        case tHaltTx:
            return .haltTx(autoOnly: r.bool() ?? false)
        case tFreeText:
            guard let text = r.string() else { return nil }
            return .freeText(text: text, send: r.bool() ?? true)
        default:
            return nil
        }
    }

    /// From a decoded message line, work out which station a Reply should call and
    /// their grid. Handles `TO FROM ...` and CQ lines with an optional directive
    /// (`CQ FROM GRID`, `CQ DX FROM GRID`, `CQ POTA FROM GRID`).
    static func parseReplyTarget(_ message: String) -> (String, String)? {
        let tokens = message.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
        guard !tokens.isEmpty else { return nil }
        let callIdx: Int
        if tokens[0].uppercased() == "CQ" {
            guard tokens.count >= 2 else { return nil }
            callIdx = isCallsign(tokens[1]) ? 1 : 2
        } else {
            callIdx = 1
        }
        guard callIdx < tokens.count else { return nil }
        let call = tokens[callIdx]
        guard isCallsign(call) else { return nil }
        let grid = tokens[(callIdx + 1)...].first(where: isGrid4)?.uppercased() ?? ""
        return (call.uppercased(), grid)
    }

    private static func isCallsign(_ t: String) -> Bool {
        let core = t.split(separator: "/").first.map(String.init) ?? t
        let hasAlpha = core.contains { $0.isLetter }
        let hasDigit = core.contains { $0.isNumber }
        let okChars = t.allSatisfy { $0.isLetter || $0.isNumber || $0 == "/" }
        return hasAlpha && hasDigit && okChars && !isGrid4(t)
    }

    private static func isGrid4(_ t: String) -> Bool {
        let c = Array(t)
        guard c.count == 4 else { return false }
        func isAtoR(_ ch: Character) -> Bool {
            let u = Character(ch.uppercased()); return u >= "A" && u <= "R"
        }
        return isAtoR(c[0]) && isAtoR(c[1]) && c[2].isNumber && c[3].isNumber
    }

    // MARK: - date/time helpers

    /// Julian Day Number for a UTC `YYYYMMDD` date (matches Qt's toJulianDay).
    static func julianDayFromYyyymmdd(_ s: String) -> Int64? {
        guard s.count == 8, s.allSatisfy({ $0.isNumber }) else { return nil }
        let a = Array(s)
        guard let year = Int(String(a[0 ..< 4])),
              let month = Int(String(a[4 ..< 6])),
              let day = Int(String(a[6 ..< 8])),
              (1 ... 12).contains(month), (1 ... 31).contains(day) else { return nil }
        // Fliegel–Van Flandern JDN for a proleptic Gregorian date.
        let aa = (14 - month) / 12
        let y = year + 4800 - aa
        let m = month + 12 * aa - 3
        let jdn = day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
        return Int64(jdn)
    }

    /// Milliseconds since midnight for a UTC `HHMMSS` string.
    static func msOfDayFromHhmmss(_ s: String) -> UInt32? {
        guard s.count == 6, s.allSatisfy({ $0.isNumber }) else { return nil }
        let a = Array(s)
        guard let h = Int(String(a[0 ..< 2])),
              let m = Int(String(a[2 ..< 4])),
              let sec = Int(String(a[4 ..< 6])),
              h <= 23, m <= 59, sec <= 60 else { return nil }
        return UInt32(((h * 3600) + (m * 60) + sec) * 1000)
    }
}
