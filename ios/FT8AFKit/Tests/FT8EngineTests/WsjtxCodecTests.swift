import XCTest
@testable import FT8Engine

/// Byte-exact tests for the WSJT-X UDP codec. The golden hex here matches the
/// desktop (Rust) and Android (Kotlin) codecs, so all three encoders are
/// provably identical (see `docs/wsjtx-udp.md`).
final class WsjtxCodecTests: XCTestCase {

    private func hex(_ d: Data) -> String {
        d.map { String(format: "%02x", $0) }.joined(separator: " ")
    }

    /// Minimal big-endian reader mirroring the (private) codec reader.
    private struct R {
        let b: [UInt8]
        var pos = 0
        init(_ d: Data) { b = [UInt8](d) }
        mutating func take(_ n: Int) -> ArraySlice<UInt8> { let s = b[pos ..< pos + n]; pos += n; return s }
        mutating func u8() -> UInt8 { take(1).first! }
        mutating func bool() -> Bool { u8() != 0 }
        mutating func u32() -> UInt32 { take(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } }
        mutating func i32() -> Int32 { Int32(bitPattern: u32()) }
        mutating func u64() -> UInt64 { take(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) } }
        mutating func str() -> String {
            let len = Int(u32()); let s = take(len); return String(decoding: s, as: UTF8.self)
        }
        var remaining: Int { b.count - pos }
    }

    private func readHeader(_ r: inout R) -> UInt32 {
        XCTAssertEqual(r.u32(), WsjtxCodec.magic)
        XCTAssertEqual(r.u32(), WsjtxCodec.schema)
        let type = r.u32()
        XCTAssertEqual(r.str(), WsjtxCodec.clientId)
        return type
    }

    func testClearHeaderBytesAreExact() {
        XCTAssertEqual(
            hex(WsjtxCodec.clear()),
            "ad bc cb da 00 00 00 03 00 00 00 03 00 00 00 05 46 54 38 41 46"
        )
    }

    func testHeartbeatBytesMatchGolden() {
        XCTAssertEqual(
            hex(WsjtxCodec.heartbeat(version: "0.1.0", revision: "")),
            "ad bc cb da 00 00 00 03 00 00 00 00 00 00 00 05 46 54 38 41 46 "
                + "00 00 00 03 00 00 00 05 30 2e 31 2e 30 00 00 00 00"
        )
    }

    func testDecodeBytesMatchGolden() {
        let d = WsjtxCodec.Decode(
            isNew: true, timeMs: 47_493_000, snr: -7, deltaTime: 0.2, deltaFreq: 1500,
            mode: "FT8", message: "CQ K1ABC FN42", lowConfidence: false, offAir: false
        )
        XCTAssertEqual(
            hex(WsjtxCodec.decode(d)),
            "ad bc cb da 00 00 00 03 00 00 00 02 00 00 00 05 46 54 38 41 46 "
                + "01 02 d4 af 88 ff ff ff f9 3f c9 99 99 99 99 99 9a 00 00 05 dc "
                + "00 00 00 03 46 54 38 00 00 00 0d 43 51 20 4b 31 41 42 43 20 46 4e 34 32 00 00"
        )
    }

    func testStatusRoundtripsAllFields() {
        let s = WsjtxCodec.Status(
            dialFreqHz: 14_074_000, mode: "FT8", dxCall: "K1ABC", report: "-07",
            txMode: "FT8", txEnabled: true, transmitting: false, decoding: true,
            rxDf: 1500, txDf: 1500, deCall: "K0XYZ", deGrid: "EN37", dxGrid: "FN42",
            txWatchdog: false, subMode: "", fastMode: false, specialOpMode: 0,
            freqTolerance: 0xFFFF_FFFF, trPeriod: 15, configName: "Default",
            txMessage: "K1ABC K0XYZ EN37"
        )
        var r = R(WsjtxCodec.status(s))
        XCTAssertEqual(readHeader(&r), WsjtxCodec.tStatus)
        XCTAssertEqual(r.u64(), 14_074_000)
        XCTAssertEqual(r.str(), "FT8")
        XCTAssertEqual(r.str(), "K1ABC")
        XCTAssertEqual(r.str(), "-07")
        XCTAssertEqual(r.str(), "FT8")
        XCTAssertTrue(r.bool())
        XCTAssertFalse(r.bool())
        XCTAssertTrue(r.bool())
        XCTAssertEqual(r.u32(), 1500)
        XCTAssertEqual(r.u32(), 1500)
        XCTAssertEqual(r.str(), "K0XYZ")
        XCTAssertEqual(r.str(), "EN37")
        XCTAssertEqual(r.str(), "FN42")
        XCTAssertFalse(r.bool())
        XCTAssertEqual(r.str(), "")
        XCTAssertFalse(r.bool())
        XCTAssertEqual(r.u8(), 0)
        XCTAssertEqual(r.u32(), 0xFFFF_FFFF)
        XCTAssertEqual(r.u32(), 15)
        XCTAssertEqual(r.str(), "Default")
        XCTAssertEqual(r.str(), "K1ABC K0XYZ EN37")
        XCTAssertEqual(r.remaining, 0)
    }

    func testQsoLoggedRoundtripsWithDatetime() {
        let on = WsjtxCodec.DateTime.fromAdif("20260712", "134500")
        let off = WsjtxCodec.DateTime.fromAdif("20260712", "134615")
        let q = WsjtxCodec.QsoLogged(
            timeOff: off, dxCall: "K1ABC", dxGrid: "FN42", txFreqHz: 14_075_500,
            mode: "FT8", reportSent: "-07", reportReceived: "-12", txPower: "",
            comments: "", name: "", timeOn: on, operatorCall: "K0XYZ", myCall: "K0XYZ",
            myGrid: "EN37", exchangeSent: "", exchangeReceived: "", propMode: ""
        )
        var r = R(WsjtxCodec.qsoLogged(q))
        XCTAssertEqual(readHeader(&r), WsjtxCodec.tQsoLogged)
        XCTAssertEqual(r.u64(), UInt64(off.julianDay))
        XCTAssertEqual(r.u32(), off.msOfDay)
        XCTAssertEqual(r.u8(), 1)
        XCTAssertEqual(r.str(), "K1ABC")
        XCTAssertEqual(r.str(), "FN42")
        XCTAssertEqual(r.u64(), 14_075_500)
        XCTAssertEqual(r.str(), "FT8")
        XCTAssertEqual(r.str(), "-07")
        XCTAssertEqual(r.str(), "-12")
        XCTAssertEqual(r.str(), "")
        XCTAssertEqual(r.str(), "")
        XCTAssertEqual(r.str(), "")
        XCTAssertEqual(r.u64(), UInt64(on.julianDay))
        XCTAssertEqual(r.u32(), on.msOfDay)
        XCTAssertEqual(r.u8(), 1)
        XCTAssertEqual(r.str(), "K0XYZ")
        XCTAssertEqual(r.str(), "K0XYZ")
        XCTAssertEqual(r.str(), "EN37")
        XCTAssertEqual(r.str(), "")
        XCTAssertEqual(r.str(), "")
        XCTAssertEqual(r.str(), "")
        XCTAssertEqual(r.remaining, 0)
    }

    func testLoggedAdifWrapsString() {
        var r = R(WsjtxCodec.loggedAdif("<call:5>K1ABC <eor>"))
        XCTAssertEqual(readHeader(&r), WsjtxCodec.tLoggedAdif)
        XCTAssertEqual(r.str(), "<call:5>K1ABC <eor>")
        XCTAssertEqual(r.remaining, 0)
    }

    // MARK: - date/time goldens

    func testJulianDayMatchesKnownValues() {
        XCTAssertEqual(WsjtxCodec.julianDayFromYyyymmdd("20000101"), 2_451_545)
        XCTAssertEqual(WsjtxCodec.julianDayFromYyyymmdd("19700101"), 2_440_588)
        XCTAssertNil(WsjtxCodec.julianDayFromYyyymmdd("bad"))
        XCTAssertNil(WsjtxCodec.julianDayFromYyyymmdd("20261301"))
    }

    func testMsOfDayFromTime() {
        XCTAssertEqual(WsjtxCodec.msOfDayFromHhmmss("000000"), 0)
        XCTAssertEqual(WsjtxCodec.msOfDayFromHhmmss("134615"), 49_575_000)
        XCTAssertEqual(WsjtxCodec.msOfDayFromHhmmss("235959"), 86_399_000)
        XCTAssertNil(WsjtxCodec.msOfDayFromHhmmss("240000"))
        XCTAssertNil(WsjtxCodec.msOfDayFromHhmmss("12345"))
    }

    // MARK: - inbound parsing

    private func beginInbound(_ type: UInt32) -> Data {
        var d = Data()
        func u32(_ v: UInt32) { var b = v.bigEndian; withUnsafeBytes(of: &b) { d.append(contentsOf: $0) } }
        u32(WsjtxCodec.magic); u32(WsjtxCodec.schema); u32(type)
        let id = Array(WsjtxCodec.clientId.utf8); u32(UInt32(id.count)); d.append(contentsOf: id)
        return d
    }
    private func putStr(_ d: inout Data, _ s: String) {
        let b = Array(s.utf8)
        var len = UInt32(b.count).bigEndian; withUnsafeBytes(of: &len) { d.append(contentsOf: $0) }
        d.append(contentsOf: b)
    }
    private func makeReply(_ message: String, _ snr: Int32, deltaFreq: UInt32 = 1500) -> Data {
        var d = beginInbound(WsjtxCodec.tReply)
        var time = UInt32(47_000_000).bigEndian; withUnsafeBytes(of: &time) { d.append(contentsOf: $0) }
        var sn = UInt32(bitPattern: snr).bigEndian; withUnsafeBytes(of: &sn) { d.append(contentsOf: $0) }
        var dt = Double(0.1).bitPattern.bigEndian; withUnsafeBytes(of: &dt) { d.append(contentsOf: $0) }
        var df = deltaFreq.bigEndian; withUnsafeBytes(of: &df) { d.append(contentsOf: $0) }
        putStr(&d, "FT8"); putStr(&d, message)
        d.append(0); d.append(0) // low conf + modifiers
        return d
    }

    func testParseReplyPlainCq() {
        XCTAssertEqual(
            WsjtxCodec.parseInbound(makeReply("CQ K1ABC FN42", -3)),
            .reply(call: "K1ABC", grid: "FN42", snr: -3, deltaFreq: 1500)
        )
    }

    func testParseReplyCqWithDirective() {
        XCTAssertEqual(
            WsjtxCodec.parseInbound(makeReply("CQ DX K1ABC FN42", -3)),
            .reply(call: "K1ABC", grid: "FN42", snr: -3, deltaFreq: 1500)
        )
        XCTAssertEqual(
            WsjtxCodec.parseInbound(makeReply("CQ POTA W1AW/4 EM70", 5)),
            .reply(call: "W1AW/4", grid: "EM70", snr: 5, deltaFreq: 1500)
        )
    }

    func testParseReplyNonCqCallsSender() {
        XCTAssertEqual(
            WsjtxCodec.parseInbound(makeReply("K0XYZ K1ABC -12", -12)),
            .reply(call: "K1ABC", grid: "", snr: -12, deltaFreq: 1500)
        )
    }

    func testParseReplyCapturesRequestedDeltaFreq() {
        // The df field must survive to the caller so it can answer on the requested tone.
        XCTAssertEqual(
            WsjtxCodec.parseInbound(makeReply("CQ K1ABC FN42", -3, deltaFreq: 2100)),
            .reply(call: "K1ABC", grid: "FN42", snr: -3, deltaFreq: 2100)
        )
    }

    func testParseHaltFreeTextReplay() {
        var halt = beginInbound(WsjtxCodec.tHaltTx); halt.append(1)
        XCTAssertEqual(WsjtxCodec.parseInbound(halt), .haltTx(autoOnly: true))

        var free = beginInbound(WsjtxCodec.tFreeText); putStr(&free, "TEST DE FT8AF"); free.append(1)
        XCTAssertEqual(WsjtxCodec.parseInbound(free), .freeText(text: "TEST DE FT8AF", send: true))

        XCTAssertEqual(WsjtxCodec.parseInbound(beginInbound(WsjtxCodec.tReplay)), .replay)
    }

    func testParseHaltTxMissingBoolIsRejected() {
        // Truncated HaltTx (no autoOnly byte) must be rejected, not defaulted to false.
        XCTAssertNil(WsjtxCodec.parseInbound(beginInbound(WsjtxCodec.tHaltTx)))
    }

    func testParseFreeTextMissingSendFlagIsRejected() {
        // Truncated Free-Text (text present, send byte missing) must be rejected, not
        // defaulted to send=true — a malformed packet must never trigger a transmit.
        var free = beginInbound(WsjtxCodec.tFreeText); putStr(&free, "HELLO")
        XCTAssertNil(WsjtxCodec.parseInbound(free))
    }

    func testParseRejectsBadAndOutboundOnly() {
        XCTAssertNil(WsjtxCodec.parseInbound(Data([0, 1, 2, 3, 4, 5, 6, 7])))
        XCTAssertNil(WsjtxCodec.parseInbound(beginInbound(WsjtxCodec.tStatus)))
    }

    func testParseReplyShortBufferIsNil() {
        var d = beginInbound(WsjtxCodec.tReply)
        var time = UInt32(1).bigEndian; withUnsafeBytes(of: &time) { d.append(contentsOf: $0) }
        XCTAssertNil(WsjtxCodec.parseInbound(d))
    }
}
