import Foundation

/// One decoded spot destined for PSK Reporter.
public struct PskSpot: Equatable, Sendable {
    public let senderCallsign: String
    public let frequencyHz: Int64
    public let snr: Int
    public let mode: String
    public let senderLocator: String?
    public let flowStartSeconds: Int64

    public init(senderCallsign: String, frequencyHz: Int64, snr: Int, mode: String,
                senderLocator: String?, flowStartSeconds: Int64) {
        self.senderCallsign = senderCallsign
        self.frequencyHz = frequencyHz
        self.snr = snr
        self.mode = mode
        self.senderLocator = senderLocator
        self.flowStartSeconds = flowStartSeconds
    }
}

/// Spot-policy helpers shared by the encoder and the app-side sender. Pure
/// functions — ports of the filter logic in Android `PskReporterSender`.
public enum PskReporter {
    /// Where the datagrams go.
    public static let host = "report.pskreporter.info"
    public static let port: UInt16 = 4739
    /// Flush cadence and per-callsign/band dedup window (5 minutes, like
    /// Android and the WSJT-X convention).
    public static let sendIntervalMs: Int64 = 5 * 60 * 1000
    public static let dedupWindowMs: Int64 = 5 * 60 * 1000

    /// Build the PSKReporter "decodingSoftware" string, optionally including
    /// the connected rig name.
    public static func buildSoftwareString(version: String, rigName: String?) -> String {
        let base = "FT8AF \(version)"
        if let rigName, !rigName.trimmingCharacters(in: .whitespaces).isEmpty {
            return "\(base) (\(rigName))"
        }
        return base
    }

    /// Convert one decode into a spot, applying the Android sender's policy:
    /// drop empty/unresolved-hash calls and free text (i3=0, n3=0), strip
    /// angle brackets from hashed callsigns, never report our own callsign,
    /// require a >= 4-char locator (else omit it), and report the RF frequency
    /// as dial + audio offset. Returns nil when the decode must not be spotted.
    public static func makeSpot(
        callFrom: String, i3: UInt8, n3: UInt8, grid: String, snr: Int,
        audioFreqHz: Float, dialFreqHz: Int64, myCall: String, utcSeconds: Int64
    ) -> PskSpot? {
        if callFrom.isEmpty || callFrom == "<...>" { return nil }
        let clean = callFrom
            .replacingOccurrences(of: "<", with: "")
            .replacingOccurrences(of: ">", with: "")
        if clean.isEmpty { return nil }
        if !myCall.isEmpty, clean.uppercased() == myCall.uppercased() { return nil }
        if i3 == 0 && n3 == 0 { return nil }
        return PskSpot(
            senderCallsign: clean,
            frequencyHz: dialFreqHz + Int64(audioFreqHz),
            snr: snr,
            mode: "FT8",
            senderLocator: grid.count >= 4 ? grid : nil,
            flowStartSeconds: utcSeconds
        )
    }
}

/// Per-callsign-per-band dedup: a station is reported at most once per
/// `PskReporter.dedupWindowMs` on a given band (keyed by dial-frequency MHz,
/// like Android's "CALL|BAND" key).
public final class PskDedup {
    private var lastSeen: [String: Int64] = [:]

    public init() {}

    /// Returns true if the spot should be reported (and marks it seen now);
    /// false when it is a duplicate inside the window.
    public func markIfFresh(call: String, dialFreqHz: Int64, nowMs: Int64) -> Bool {
        let key = "\(call)|\(dialFreqHz / 1_000_000)"
        if let t = lastSeen[key], nowMs - t < PskReporter.dedupWindowMs { return false }
        lastSeen[key] = nowMs
        return true
    }
}

/// Builds the IPFIX-style binary UDP datagrams PSK Reporter's collector
/// expects (report.pskreporter.info:4739). Byte-layout port of Android
/// `PskReporterSender`: 16-byte IPFIX header, options/data template sets on
/// the first sends (and hourly refresh), a receiver record (our station) and
/// N sender records (the spots), each datagram kept under the MTU budget.
///
/// Pure byte-building — no sockets. Time is injected (`nowMs`) so packet
/// construction is fully deterministic and testable.
public final class PskReporterEncoder {
    static let maxDatagram = 1400
    static let enterpriseNumber: UInt32 = 30351          // 0x0000768F
    static let ipfixVersion: UInt16 = 0x000A

    // Template set IDs
    static let templateSetIdOptions: UInt16 = 0x0003     // options template
    static let templateSetIdData: UInt16 = 0x0002        // data template

    // Template IDs (== the data set IDs that reference them)
    static let receiverTemplateId: UInt16 = 0x50E2       // 20706
    static let senderTemplateId: UInt16 = 0x50E3         // 20707

    // PSKReporter's enterprise-specific element IDs under 30351. The collector
    // maps element-ID -> column, so these must match exactly.
    static let ftRxCallsign: UInt16 = 2     // receiverCallsign   (0x8002)
    static let ftRxLocator: UInt16 = 4      // receiverLocator    (0x8004)
    static let ftDecodingSw: UInt16 = 8     // decodingSoftware   (0x8008)
    static let ftRxAntenna: UInt16 = 9      // antennaInformation (0x8009)
    static let ftRxRig: UInt16 = 13         // rigInformation     (0x800D)
    static let ftTxCallsign: UInt16 = 1     // senderCallsign     (0x8001)
    static let ftFrequency: UInt16 = 5      // frequency          (0x8005)
    static let ftSnr: UInt16 = 6            // sNR                (0x8006)
    static let ftMode: UInt16 = 10          // mode               (0x800A)
    static let ftTxLocator: UInt16 = 3      // senderLocator      (0x8003)
    static let ftInfoSource: UInt16 = 11    // informationSource  (0x800B)
    // flowStartSeconds is the STANDARD IANA IPFIX element 150 (0x0096): no
    // enterprise bit, no enterprise number in its template descriptor.
    static let ftFlowStart: UInt16 = 150

    public let observationDomainId: UInt32
    public private(set) var sequenceNumber: UInt32
    private var packetsSent = 0
    private var lastTemplateSentMs: Int64 = 0

    public init(observationDomainId: UInt32 = UInt32.random(in: 0...UInt32(Int32.max)),
                sequenceNumber: UInt32 = 0) {
        self.observationDomainId = observationDomainId
        self.sequenceNumber = sequenceNumber
    }

    /// Build one or more datagrams containing the given spots; each stays
    /// under `maxDatagram` bytes. Advances the sequence number per packet and
    /// tracks template cadence (first 3 packets and then hourly).
    public func buildPackets(
        rxCall: String, rxGrid: String, software: String,
        antenna: String = "", rig: String = "",
        spots: [PskSpot], nowMs: Int64
    ) -> [[UInt8]] {
        guard !spots.isEmpty else { return [] }
        var packets: [[UInt8]] = []
        let includeTemplates = needTemplates(nowMs: nowMs)

        var remaining = spots[...]
        while !remaining.isEmpty {
            let templateBytes: [UInt8] = includeTemplates ? Self.encodeTemplates() : []
            let receiverBytes = Self.encodeReceiverDataSet(
                call: rxCall, grid: rxGrid, software: software, antenna: antenna, rig: rig)

            // Header is 16 bytes. Reserve the worst-case sender-set padding
            // (3 bytes) inside the budget so the padded datagram can never
            // exceed `maxDatagram`.
            let overhead = 16 + templateBytes.count + receiverBytes.count
            let budget = Self.maxDatagram - overhead - 3

            // Encode as many sender records as fit. A record that cannot fit
            // even alone in an otherwise-empty datagram is pathological —
            // drop it (never emit an oversized datagram) and keep going.
            var senderRecords: [[UInt8]] = []
            var senderSetSize = 4 // set header (setId + length)
            var used = 0
            for spot in remaining {
                let rec = Self.encodeSenderRecord(spot)
                if senderSetSize + rec.count > budget {
                    if senderRecords.isEmpty {
                        used += 1 // oversized single record: drop it
                        continue
                    }
                    break
                }
                senderRecords.append(rec)
                senderSetSize += rec.count
                used += 1
            }
            remaining = remaining.dropFirst(used)
            // Everything consumed this pass was dropped as oversized —
            // nothing to send in this datagram.
            if senderRecords.isEmpty { continue }

            // Pad sender set to a 4-byte boundary.
            let senderPadding = (4 - (senderSetSize % 4)) % 4
            senderSetSize += senderPadding

            let totalLen = 16 + templateBytes.count + receiverBytes.count + senderSetSize
            var w = ByteWriter()
            // IPFIX header
            w.u16(Self.ipfixVersion)
            w.u16(UInt16(totalLen))
            w.u32(UInt32(truncatingIfNeeded: nowMs / 1000))
            w.u32(sequenceNumber)
            w.u32(observationDomainId)
            sequenceNumber &+= 1
            packetsSent += 1

            w.bytes.append(contentsOf: templateBytes)
            w.bytes.append(contentsOf: receiverBytes)

            // Sender data set
            w.u16(Self.senderTemplateId)
            w.u16(UInt16(senderSetSize))
            for rec in senderRecords { w.bytes.append(contentsOf: rec) }
            w.pad(senderPadding)

            packets.append(w.bytes)
            if includeTemplates { lastTemplateSentMs = nowMs }
        }
        return packets
    }

    func needTemplates(nowMs: Int64) -> Bool {
        if packetsSent < 3 { return true }
        if nowMs - lastTemplateSentMs > 3_600_000 { return true }
        return false
    }

    /// Receiver options template + sender data template, concatenated.
    static func encodeTemplates() -> [UInt8] {
        encodeReceiverTemplate() + encodeSenderTemplate()
    }

    static func encodeReceiverTemplate() -> [UInt8] {
        // Options template set (0x0003): template 0x50E2, 5 variable-length
        // enterprise fields, scope field count 0.
        let fieldCount = 5
        let templateRecordLen = 2 + 2 + 2 + fieldCount * 8
        let setLen = 4 + templateRecordLen
        let padding = (4 - (setLen % 4)) % 4

        var w = ByteWriter()
        w.u16(templateSetIdOptions)
        w.u16(UInt16(setLen + padding))
        w.u16(receiverTemplateId)
        w.u16(UInt16(fieldCount))
        w.u16(0) // scope field count
        for field in [ftRxCallsign, ftRxLocator, ftDecodingSw, ftRxAntenna, ftRxRig] {
            w.u16(field | 0x8000)   // enterprise bit
            w.u16(0xFFFF)           // variable length
            w.u32(enterpriseNumber)
        }
        w.pad(padding)
        return w.bytes
    }

    static func encodeSenderTemplate() -> [UInt8] {
        // Data template set (0x0002): template 0x50E3, 7 fields. Six are
        // enterprise-specific (8-byte descriptor); flowStartSeconds is the
        // standard element 0x0096 (4-byte descriptor, no enterprise number).
        let templateRecordLen = 2 + 2 + 6 * 8 + 4
        let setLen = 4 + templateRecordLen
        let padding = (4 - (setLen % 4)) % 4

        var w = ByteWriter()
        w.u16(templateSetIdData)
        w.u16(UInt16(setLen + padding))
        w.u16(senderTemplateId)
        w.u16(7)
        // senderCallsign — variable length
        w.u16(ftTxCallsign | 0x8000); w.u16(0xFFFF); w.u32(enterpriseNumber)
        // frequency — uint32
        w.u16(ftFrequency | 0x8000); w.u16(4); w.u32(enterpriseNumber)
        // sNR — int8
        w.u16(ftSnr | 0x8000); w.u16(1); w.u32(enterpriseNumber)
        // mode — variable length
        w.u16(ftMode | 0x8000); w.u16(0xFFFF); w.u32(enterpriseNumber)
        // senderLocator — variable length
        w.u16(ftTxLocator | 0x8000); w.u16(0xFFFF); w.u32(enterpriseNumber)
        // informationSource — uint8
        w.u16(ftInfoSource | 0x8000); w.u16(1); w.u32(enterpriseNumber)
        // flowStartSeconds — standard element, uint32, NO enterprise number
        w.u16(ftFlowStart); w.u16(4)
        w.pad(padding)
        return w.bytes
    }

    /// Receiver data set (our station info). Set ID = receiver template ID.
    static func encodeReceiverDataSet(
        call: String, grid: String, software: String, antenna: String, rig: String
    ) -> [UInt8] {
        let body = encodeVarString(call) + encodeVarString(grid)
            + encodeVarString(software) + encodeVarString(antenna) + encodeVarString(rig)
        let setLen = 4 + body.count
        let padding = (4 - (setLen % 4)) % 4

        var w = ByteWriter()
        w.u16(receiverTemplateId)
        w.u16(UInt16(setLen + padding))
        w.bytes.append(contentsOf: body)
        w.pad(padding)
        return w.bytes
    }

    /// One sender record (one spot). No set header — these are concatenated
    /// inside the sender data set.
    static func encodeSenderRecord(_ spot: PskSpot) -> [UInt8] {
        var w = ByteWriter()
        w.bytes.append(contentsOf: encodeVarString(spot.senderCallsign))
        w.u32(UInt32(truncatingIfNeeded: spot.frequencyHz))
        w.u8(UInt8(bitPattern: Int8(truncatingIfNeeded: spot.snr)))
        w.bytes.append(contentsOf: encodeVarString(spot.mode))
        w.bytes.append(contentsOf: encodeVarString(spot.senderLocator ?? ""))
        w.u8(1) // informationSource = 1 (automatic)
        w.u32(UInt32(truncatingIfNeeded: spot.flowStartSeconds))
        return w.bytes
    }

    /// IPFIX variable-length string: 1-byte length prefix + UTF-8 bytes; a
    /// length >= 255 uses a 3-byte prefix (0xFF + 2-byte big-endian length).
    static func encodeVarString(_ s: String) -> [UInt8] {
        let utf8 = Array(s.utf8)
        if utf8.count < 255 {
            return [UInt8(utf8.count)] + utf8
        }
        return [0xFF, UInt8(utf8.count >> 8), UInt8(utf8.count & 0xFF)] + utf8
    }
}

/// Tiny big-endian byte assembler (ByteBuffer.BIG_ENDIAN equivalent).
private struct ByteWriter {
    var bytes: [UInt8] = []
    mutating func u8(_ v: UInt8) { bytes.append(v) }
    mutating func u16(_ v: UInt16) {
        bytes.append(UInt8(v >> 8))
        bytes.append(UInt8(v & 0xFF))
    }
    mutating func u32(_ v: UInt32) {
        bytes.append(UInt8((v >> 24) & 0xFF))
        bytes.append(UInt8((v >> 16) & 0xFF))
        bytes.append(UInt8((v >> 8) & 0xFF))
        bytes.append(UInt8(v & 0xFF))
    }
    mutating func pad(_ n: Int) {
        bytes.append(contentsOf: [UInt8](repeating: 0, count: n))
    }
}
