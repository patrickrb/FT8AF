import XCTest
@testable import FT8Audio

final class SntpTests: XCTestCase {

    /// Write a big-endian UInt32 into `bytes` at `offset`.
    private func putBE(_ value: UInt32, into bytes: inout [UInt8], at offset: Int) {
        bytes[offset] = UInt8((value >> 24) & 0xFF)
        bytes[offset + 1] = UInt8((value >> 16) & 0xFF)
        bytes[offset + 2] = UInt8((value >> 8) & 0xFF)
        bytes[offset + 3] = UInt8(value & 0xFF)
    }

    // MARK: - Request

    func testRequestIs48BytesClientMode() {
        let req = Sntp.makeRequest()
        XCTAssertEqual(req.count, 48)
        XCTAssertEqual(req[0], 0x23) // LI=0, VN=4, Mode=3 (client)
        XCTAssertTrue(req[1...].allSatisfy { $0 == 0 })
    }

    // MARK: - Parse (programmatic)

    func testParseTransmitTimestampToUnixMs() {
        // Reference: 2023-11-14 22:13:20 UTC = 1_700_000_000 s, plus 0.5 s.
        let unixSec: Int64 = 1_700_000_000
        let ntpSec = UInt32(unixSec + Sntp.ntpUnixEpochDeltaSec)
        let halfSecondFraction: UInt32 = 0x8000_0000 // 0.5 s

        var packet = [UInt8](repeating: 0, count: 48)
        packet[0] = 0x24 // LI=0, VN=4, Mode=4 (server)
        packet[1] = 1    // stratum 1 (primary)
        putBE(ntpSec, into: &packet, at: 40)
        putBE(halfSecondFraction, into: &packet, at: 44)

        let ms = Sntp.transmitTimeUnixMs(fromResponse: packet)
        XCTAssertEqual(ms, unixSec * 1_000 + 500)
    }

    // MARK: - Header validation

    /// Build an otherwise-valid server response carrying a fixed timestamp, so a
    /// test can flip one header byte and assert it is rejected.
    private func validResponse(li: UInt8 = 0, mode: UInt8 = 4, stratum: UInt8 = 2) -> [UInt8] {
        var packet = [UInt8](repeating: 0, count: 48)
        packet[0] = (li << 6) | (4 << 3) | mode // VN=4
        packet[1] = stratum
        // NTP seconds 0xE8FE6F80 = 1_700_000_000 s Unix, fraction 0.5 s.
        packet[40] = 0xE8; packet[41] = 0xFE; packet[42] = 0x6F; packet[43] = 0x80
        packet[44] = 0x80
        return packet
    }

    func testValidServerResponseAccepted() {
        XCTAssertEqual(Sntp.transmitTimeUnixMs(fromResponse: validResponse()), 1_700_000_000_500)
    }

    func testRejectsUnsynchronizedLeapIndicator() {
        // LI = 3 ("alarm", clock not synchronized) — even with a plausible timestamp.
        XCTAssertNil(Sntp.transmitTimeUnixMs(fromResponse: validResponse(li: 3)))
    }

    func testRejectsNonServerMode() {
        // Mode 3 (client) or 6 (control) is not a server-mode reply to our query.
        XCTAssertNil(Sntp.transmitTimeUnixMs(fromResponse: validResponse(mode: 3)))
        XCTAssertNil(Sntp.transmitTimeUnixMs(fromResponse: validResponse(mode: 6)))
    }

    func testRejectsKissOfDeathAndReservedStratum() {
        // Stratum 0 = Kiss-o'-Death (no usable time); 16..255 are reserved.
        XCTAssertNil(Sntp.transmitTimeUnixMs(fromResponse: validResponse(stratum: 0)))
        XCTAssertNil(Sntp.transmitTimeUnixMs(fromResponse: validResponse(stratum: 16)))
    }

    // MARK: - Sane-offset bound

    func testOffsetSaneBound() {
        XCTAssertTrue(Sntp.isOffsetSane(0))
        XCTAssertTrue(Sntp.isOffsetSane(Sntp.maxSaneOffsetMs))
        XCTAssertTrue(Sntp.isOffsetSane(-Sntp.maxSaneOffsetMs))
        XCTAssertFalse(Sntp.isOffsetSane(Sntp.maxSaneOffsetMs + 1))
        // A year-scale jump (the failure the header/bound guards prevent) is rejected.
        XCTAssertFalse(Sntp.isOffsetSane(365 * 24 * 60 * 60 * 1000))
    }

    // MARK: - Parse (literal captured bytes)

    func testParseLiteralResponseAndOffset() {
        // A full 48-byte response whose transmit timestamp encodes
        // 1_700_000_000.500 s Unix (NTP seconds 0xE8FE6F80, fraction 0x80000000).
        var packet = [UInt8](repeating: 0, count: 48)
        packet[0] = 0x24 // LI=0, VN=4, Mode=4 (server)
        packet[1] = 2    // stratum
        // Transmit Timestamp @ bytes 40..47
        packet[40] = 0xE8; packet[41] = 0xFE; packet[42] = 0x6F; packet[43] = 0x80
        packet[44] = 0x80; packet[45] = 0x00; packet[46] = 0x00; packet[47] = 0x00

        let ref = Sntp.transmitTimeUnixMs(fromResponse: packet)
        XCTAssertEqual(ref, 1_700_000_000_500)

        // Device clock 0.5 s behind the reference -> +500 ms correction.
        let offset = Sntp.offsetMs(referenceUnixMs: ref!, deviceNowMs: 1_700_000_000_000)
        XCTAssertEqual(offset, 500)
    }

    // MARK: - Offset sign

    func testOffsetSignForFastAndSlowClocks() {
        // Device ahead of reference -> negative correction (pull it back).
        XCTAssertEqual(Sntp.offsetMs(referenceUnixMs: 1_000, deviceNowMs: 1_800), -800)
        // Device behind reference -> positive correction (push it forward).
        XCTAssertEqual(Sntp.offsetMs(referenceUnixMs: 2_000, deviceNowMs: 1_200), 800)
    }

    // MARK: - Malformed / degenerate responses

    func testTooShortResponseReturnsNil() {
        XCTAssertNil(Sntp.transmitTimeUnixMs(fromResponse: [UInt8](repeating: 0, count: 47)))
    }

    func testZeroTimestampReturnsNil() {
        // All-zero transmit timestamp (unset / Kiss-o'-Death) is rejected.
        XCTAssertNil(Sntp.transmitTimeUnixMs(fromResponse: [UInt8](repeating: 0, count: 48)))
    }
}
