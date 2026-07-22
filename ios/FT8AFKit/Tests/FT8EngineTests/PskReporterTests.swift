import XCTest
@testable import FT8Engine

final class PskReporterTests: XCTestCase {

    private func spot(
        call: String = "K1ABC", freq: Int64 = 14_074_123, snr: Int = -12,
        mode: String = "FT8", locator: String? = "FN42", flowStart: Int64 = 1_700_000_000
    ) -> PskSpot {
        PskSpot(senderCallsign: call, frequencyHz: freq, snr: snr, mode: mode,
                senderLocator: locator, flowStartSeconds: flowStart)
    }

    private func u32be(_ v: UInt32) -> [UInt8] {
        [UInt8((v >> 24) & 0xFF), UInt8((v >> 16) & 0xFF),
         UInt8((v >> 8) & 0xFF), UInt8(v & 0xFF)]
    }

    // MARK: encodeVarString

    func testEncodeVarStringShort() {
        XCTAssertEqual(PskReporterEncoder.encodeVarString(""), [0])
        XCTAssertEqual(PskReporterEncoder.encodeVarString("AB"), [2, 0x41, 0x42])
    }

    func testEncodeVarStringLongUsesThreeBytePrefix() {
        let s = String(repeating: "x", count: 300)
        let out = PskReporterEncoder.encodeVarString(s)
        XCTAssertEqual(Array(out.prefix(3)), [0xFF, 0x01, 0x2C]) // 300 = 0x012C
        XCTAssertEqual(out.count, 303)
    }

    // MARK: templates (golden bytes vs the Android layout)

    func testReceiverTemplateGoldenBytes() {
        let ent: [UInt8] = [0x00, 0x00, 0x76, 0x8F] // enterprise 30351
        var expected: [UInt8] = [
            0x00, 0x03,             // options template set
            0x00, 0x34,             // total length 52 (50 + 2 padding)
            0x50, 0xE2,             // template id 20706
            0x00, 0x05,             // field count 5
            0x00, 0x00,             // scope field count 0
        ]
        for field: UInt8 in [0x02, 0x04, 0x08, 0x09, 0x0D] {
            expected += [0x80, field, 0xFF, 0xFF] + ent
        }
        expected += [0x00, 0x00]    // padding to 4-byte boundary
        XCTAssertEqual(PskReporterEncoder.encodeReceiverTemplate(), expected)
    }

    func testSenderTemplateGoldenBytes() {
        let ent: [UInt8] = [0x00, 0x00, 0x76, 0x8F]
        var expected: [UInt8] = [
            0x00, 0x02,             // data template set
            0x00, 0x3C,             // total length 60 (no padding needed)
            0x50, 0xE3,             // template id 20707
            0x00, 0x07,             // field count 7
        ]
        expected += [0x80, 0x01, 0xFF, 0xFF] + ent // senderCallsign, var
        expected += [0x80, 0x05, 0x00, 0x04] + ent // frequency, uint32
        expected += [0x80, 0x06, 0x00, 0x01] + ent // sNR, int8
        expected += [0x80, 0x0A, 0xFF, 0xFF] + ent // mode, var
        expected += [0x80, 0x03, 0xFF, 0xFF] + ent // senderLocator, var
        expected += [0x80, 0x0B, 0x00, 0x01] + ent // informationSource, uint8
        expected += [0x00, 0x96, 0x00, 0x04]       // flowStartSeconds: STANDARD element, no enterprise
        XCTAssertEqual(PskReporterEncoder.encodeSenderTemplate(), expected)
    }

    // MARK: receiver data set

    func testReceiverDataSetGoldenBytes() {
        let out = PskReporterEncoder.encodeReceiverDataSet(
            call: "K1ABC", grid: "FN20", software: "FT8AF 1.0", antenna: "", rig: "")
        // body = 6 + 5 + 10 + 1 + 1 = 23; set = 27; padded to 28
        var expected: [UInt8] = [0x50, 0xE2, 0x00, 0x1C]
        expected += [5] + Array("K1ABC".utf8)
        expected += [4] + Array("FN20".utf8)
        expected += [9] + Array("FT8AF 1.0".utf8)
        expected += [0, 0]      // empty antenna + rig
        expected += [0]         // padding
        XCTAssertEqual(out, expected)
    }

    // MARK: sender record

    func testSenderRecordGoldenBytes() {
        let out = PskReporterEncoder.encodeSenderRecord(spot())
        var expected: [UInt8] = [5] + Array("K1ABC".utf8)
        expected += u32be(14_074_123)
        expected += [0xF4]      // snr -12 as int8
        expected += [3] + Array("FT8".utf8)
        expected += [4] + Array("FN42".utf8)
        expected += [1]         // informationSource = automatic
        expected += u32be(1_700_000_000)
        XCTAssertEqual(out, expected)
    }

    func testSenderRecordNilLocatorEncodesEmptyString() {
        let out = PskReporterEncoder.encodeSenderRecord(spot(locator: nil))
        // call(6) + freq(4) + snr(1) + mode(4) + locator(1: just the 0 length) + info(1) + flow(4)
        XCTAssertEqual(out.count, 21)
        XCTAssertEqual(out[15], 0) // empty-locator length prefix
    }

    // MARK: buildPackets

    func testBuildPacketsHeaderAndTemplateInclusion() {
        let enc = PskReporterEncoder(observationDomainId: 0x1234_5678, sequenceNumber: 0)
        let nowMs: Int64 = 1_700_000_000_000
        let packets = enc.buildPackets(
            rxCall: "KD2OGR", rxGrid: "FN20", software: "FT8AF 1.0",
            spots: [spot()], nowMs: nowMs)
        XCTAssertEqual(packets.count, 1)
        let p = packets[0]
        XCTAssertEqual(Array(p[0..<2]), [0x00, 0x0A])                    // IPFIX version
        XCTAssertEqual(Int(p[2]) << 8 | Int(p[3]), p.count)              // declared length
        XCTAssertEqual(Array(p[4..<8]), u32be(1_700_000_000))            // export time (s)
        XCTAssertEqual(Array(p[8..<12]), u32be(0))                       // sequence number
        XCTAssertEqual(Array(p[12..<16]), u32be(0x1234_5678))            // observation domain
        XCTAssertEqual(Array(p[16..<18]), [0x00, 0x03])                  // templates included
        XCTAssertEqual(p.count % 4, 0)                                   // 4-byte aligned
        XCTAssertEqual(enc.sequenceNumber, 1)
    }

    func testTemplatesSentOnFirstThreePacketsThenOmittedThenHourlyRefresh() {
        let enc = PskReporterEncoder(observationDomainId: 1, sequenceNumber: 0)
        let nowMs: Int64 = 1_700_000_000_000
        func firstSetId(_ atMs: Int64) -> [UInt8] {
            let p = enc.buildPackets(rxCall: "K", rxGrid: "AA00", software: "s",
                                     spots: [spot()], nowMs: atMs)[0]
            return Array(p[16..<18])
        }
        XCTAssertEqual(firstSetId(nowMs), [0x00, 0x03])          // packet 1: templates
        XCTAssertEqual(firstSetId(nowMs), [0x00, 0x03])          // packet 2: templates
        XCTAssertEqual(firstSetId(nowMs), [0x00, 0x03])          // packet 3: templates
        XCTAssertEqual(firstSetId(nowMs), [0x50, 0xE2])          // packet 4: receiver set directly
        XCTAssertEqual(firstSetId(nowMs + 3_600_001), [0x00, 0x03]) // hourly refresh
        XCTAssertEqual(firstSetId(nowMs + 3_600_002), [0x50, 0xE2]) // fresh again
    }

    func testBuildPacketsSplitsAtMtu() {
        let enc = PskReporterEncoder(observationDomainId: 1, sequenceNumber: 0)
        let many = (0..<200).map { i in
            spot(call: String(format: "K%03dABC", i), freq: 14_074_000 + Int64(i))
        }
        let packets = enc.buildPackets(rxCall: "KD2OGR", rxGrid: "FN20",
                                       software: "FT8AF 1.0", spots: many,
                                       nowMs: 1_700_000_000_000)
        XCTAssertGreaterThan(packets.count, 1)
        for p in packets {
            XCTAssertLessThanOrEqual(p.count, 1400)
            XCTAssertEqual(p.count % 4, 0)
        }
        // Sequence advanced once per packet.
        XCTAssertEqual(enc.sequenceNumber, UInt32(packets.count))
    }

    /// True when `needle` appears as a contiguous byte run inside `haystack`.
    private func containsBytes(_ haystack: [UInt8], _ needle: [UInt8]) -> Bool {
        guard !needle.isEmpty, haystack.count >= needle.count else { return false }
        for start in 0...(haystack.count - needle.count)
        where Array(haystack[start..<(start + needle.count)]) == needle {
            return true
        }
        return false
    }

    func testExactBoundaryBatchNeverExceedsMaxDatagramIncludingPadding() {
        // Sweep locator lengths 0/2/4/6 chars to shift the record size (and
        // therefore the fit boundary) across every 4-byte padding alignment:
        // whatever lands exactly at the budget, the padded datagram must stay
        // <= 1400 bytes.
        for locLen in [0, 2, 4, 6] {
            let enc = PskReporterEncoder(observationDomainId: 1, sequenceNumber: 0)
            let loc = locLen > 0 ? String(repeating: "A", count: locLen) : nil
            let many = (0..<300).map { i in
                spot(call: String(format: "K%03dABC", i), locator: loc)
            }
            let packets = enc.buildPackets(rxCall: "KD2OGR", rxGrid: "FN20",
                                           software: "FT8AF 1.0", spots: many,
                                           nowMs: 1_700_000_000_000)
            XCTAssertGreaterThan(packets.count, 1)
            for p in packets {
                XCTAssertLessThanOrEqual(p.count, 1400,
                                         "locator length \(locLen) busted the datagram cap")
                XCTAssertEqual(p.count % 4, 0)
                XCTAssertEqual(Int(p[2]) << 8 | Int(p[3]), p.count) // declared == actual
            }
        }
    }

    func testOversizedSingleRecordDroppedSubsequentRecordsStillSent() {
        // A record that cannot fit even alone in an empty datagram is
        // pathological: drop it, keep sending the rest, never emit > 1400.
        let enc = PskReporterEncoder(observationDomainId: 1, sequenceNumber: 0)
        let huge = spot(call: String(repeating: "X", count: 1500))
        let packets = enc.buildPackets(
            rxCall: "KD2OGR", rxGrid: "FN20", software: "FT8AF 1.0",
            spots: [huge, spot(call: "K1ABC"), spot(call: "W9DEF")],
            nowMs: 1_700_000_000_000)
        XCTAssertEqual(packets.count, 1)
        let p = packets[0]
        XCTAssertLessThanOrEqual(p.count, 1400)
        XCTAssertTrue(containsBytes(p, Array("K1ABC".utf8)))
        XCTAssertTrue(containsBytes(p, Array("W9DEF".utf8)))
        XCTAssertFalse(containsBytes(p, Array(String(repeating: "X", count: 20).utf8)))
        // Sequence advanced once per emitted packet — dropped records don't
        // consume sequence numbers.
        XCTAssertEqual(enc.sequenceNumber, 1)
    }

    func testOversizedRecordInMiddleOfBatchIsSkipped() {
        let enc = PskReporterEncoder(observationDomainId: 1, sequenceNumber: 0)
        let huge = spot(call: String(repeating: "X", count: 1500))
        let packets = enc.buildPackets(
            rxCall: "KD2OGR", rxGrid: "FN20", software: "FT8AF 1.0",
            spots: [spot(call: "K1ABC"), huge, spot(call: "W9DEF")],
            nowMs: 1_700_000_000_000)
        for p in packets { XCTAssertLessThanOrEqual(p.count, 1400) }
        let all = packets.flatMap { $0 }
        XCTAssertTrue(containsBytes(all, Array("K1ABC".utf8)))
        XCTAssertTrue(containsBytes(all, Array("W9DEF".utf8)))
        XCTAssertFalse(containsBytes(all, Array(String(repeating: "X", count: 20).utf8)))
        XCTAssertEqual(enc.sequenceNumber, UInt32(packets.count))
    }

    func testAllRecordsOversizedProducesNoPackets() {
        let enc = PskReporterEncoder(observationDomainId: 1, sequenceNumber: 0)
        let packets = enc.buildPackets(
            rxCall: "KD2OGR", rxGrid: "FN20", software: "FT8AF 1.0",
            spots: [spot(call: String(repeating: "X", count: 1500)),
                    spot(call: String(repeating: "Y", count: 2000))],
            nowMs: 1_700_000_000_000)
        XCTAssertTrue(packets.isEmpty)
        XCTAssertEqual(enc.sequenceNumber, 0)
    }

    func testBuildPacketsEmptySpots() {
        let enc = PskReporterEncoder(observationDomainId: 1, sequenceNumber: 0)
        XCTAssertTrue(enc.buildPackets(rxCall: "K", rxGrid: "AA00", software: "s",
                                       spots: [], nowMs: 0).isEmpty)
        XCTAssertEqual(enc.sequenceNumber, 0)
    }

    // MARK: dedup

    func testDedupWindowPerCallPerBand() {
        let dedup = PskDedup()
        let t0: Int64 = 1_000_000
        XCTAssertTrue(dedup.markIfFresh(call: "K1ABC", dialFreqHz: 14_074_000, nowMs: t0))
        // Same call+band inside the window: suppressed.
        XCTAssertFalse(dedup.markIfFresh(call: "K1ABC", dialFreqHz: 14_074_000, nowMs: t0 + 1000))
        // Different band (dial MHz differs): fresh.
        XCTAssertTrue(dedup.markIfFresh(call: "K1ABC", dialFreqHz: 7_074_000, nowMs: t0 + 1000))
        // Window expired: fresh again.
        XCTAssertTrue(dedup.markIfFresh(
            call: "K1ABC", dialFreqHz: 14_074_000,
            nowMs: t0 + PskReporter.dedupWindowMs))
    }

    // MARK: makeSpot policy

    func testMakeSpotBuildsRfFrequencyFromDialPlusAudioOffset() {
        let s = PskReporter.makeSpot(
            callFrom: "K1ABC", i3: 1, n3: 0, grid: "FN42", snr: -7,
            audioFreqHz: 1512, dialFreqHz: 14_074_000, myCall: "KD2OGR",
            utcSeconds: 1_700_000_000)
        XCTAssertEqual(s?.senderCallsign, "K1ABC")
        XCTAssertEqual(s?.frequencyHz, 14_075_512)
        XCTAssertEqual(s?.snr, -7)
        XCTAssertEqual(s?.mode, "FT8")
        XCTAssertEqual(s?.senderLocator, "FN42")
        XCTAssertEqual(s?.flowStartSeconds, 1_700_000_000)
    }

    func testMakeSpotStripsHashedCallsignBrackets() {
        let s = PskReporter.makeSpot(
            callFrom: "<K1ABC>", i3: 1, n3: 0, grid: "", snr: 0,
            audioFreqHz: 0, dialFreqHz: 14_074_000, myCall: "KD2OGR", utcSeconds: 0)
        XCTAssertEqual(s?.senderCallsign, "K1ABC")
        XCTAssertNil(s?.senderLocator) // short grid omitted
    }

    func testMakeSpotDropsInvalidSelfAndFreeText() {
        // Unresolved hash
        XCTAssertNil(PskReporter.makeSpot(
            callFrom: "<...>", i3: 1, n3: 0, grid: "", snr: 0,
            audioFreqHz: 0, dialFreqHz: 0, myCall: "K", utcSeconds: 0))
        // Empty
        XCTAssertNil(PskReporter.makeSpot(
            callFrom: "", i3: 1, n3: 0, grid: "", snr: 0,
            audioFreqHz: 0, dialFreqHz: 0, myCall: "K", utcSeconds: 0))
        // Our own callsign (case-insensitive)
        XCTAssertNil(PskReporter.makeSpot(
            callFrom: "kd2ogr", i3: 1, n3: 0, grid: "FN20", snr: 0,
            audioFreqHz: 0, dialFreqHz: 0, myCall: "KD2OGR", utcSeconds: 0))
        // Free text (i3 = 0, n3 = 0)
        XCTAssertNil(PskReporter.makeSpot(
            callFrom: "K1ABC", i3: 0, n3: 0, grid: "FN42", snr: 0,
            audioFreqHz: 0, dialFreqHz: 0, myCall: "KD2OGR", utcSeconds: 0))
    }

    // MARK: reportableLocator / RR73 policy

    func testReportableLocatorAcceptsRealGrids() {
        XCTAssertEqual(PskReporter.reportableLocator("FN42"), "FN42")
        XCTAssertEqual(PskReporter.reportableLocator("IO91wm"), "IO91wm")
    }

    func testReportableLocatorRejectsShortTokens() {
        XCTAssertNil(PskReporter.reportableLocator(""))
        XCTAssertNil(PskReporter.reportableLocator("FN"))
        XCTAssertNil(PskReporter.reportableLocator("RR")) // bare sign-off, < 4 chars
    }

    func testReportableLocatorRejectsRR73SignOff() {
        // "RR73" is the FT8 roger-73 report, never a grid — even though it is a
        // 4-char Maidenhead look-alike. Case-insensitive.
        XCTAssertNil(PskReporter.reportableLocator("RR73"))
        XCTAssertNil(PskReporter.reportableLocator("rr73"))
        XCTAssertNil(PskReporter.reportableLocator("Rr73"))
    }

    func testMakeSpotDropsRR73Locator() {
        // A decode whose grid field carries the RR73 sign-off must still be
        // spotted (the call is real), but never with RR73 as the locator.
        let s = PskReporter.makeSpot(
            callFrom: "K1ABC", i3: 1, n3: 0, grid: "RR73", snr: -3,
            audioFreqHz: 1200, dialFreqHz: 14_074_000, myCall: "KD2OGR",
            utcSeconds: 1_700_000_000)
        XCTAssertEqual(s?.senderCallsign, "K1ABC")
        XCTAssertEqual(s?.frequencyHz, 14_075_200)
        XCTAssertNil(s?.senderLocator)
    }

    // MARK: software string

    func testBuildSoftwareString() {
        XCTAssertEqual(PskReporter.buildSoftwareString(version: "1.2", rigName: nil),
                       "FT8AF 1.2")
        XCTAssertEqual(PskReporter.buildSoftwareString(version: "1.2", rigName: "  "),
                       "FT8AF 1.2")
        XCTAssertEqual(PskReporter.buildSoftwareString(version: "1.2", rigName: "IC-705"),
                       "FT8AF 1.2 (IC-705)")
    }
}
