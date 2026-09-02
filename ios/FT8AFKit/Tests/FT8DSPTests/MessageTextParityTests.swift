import XCTest
import CFT8
@testable import FT8DSP

/// Parity coverage for the message TEXT that `FT8Decoder.buildMessage` puts in
/// `DecodedMessage.rawText` (the string the QSO log/decode list shows), checked
/// against Android `Ft8Message.getMessageText()`. Covers the standard + CQ
/// common path plus the special contest/utility types this file builds payloads
/// for: Field Day, RTTY Roundup, DXpedition, WWROF, and telemetry. (Free-text /
/// EU-VHF / Contesting are discussed in the report but not asserted here — iOS
/// already emits the fuller ft8_lib text for them.)
///
/// KEY FINDING (see the FT8AF report): the shared `ft8_lib`
/// `ftx_message_decode` — the exact call `buildMessage` uses to fill
/// `rawText` — already formats every one of these types with the same labels
/// Android's Java re-formatter emits (`RR73;`, `TU;`, the FD class/section, the
/// R flag, …). So iOS is already at parity-or-better. The only residual
/// differences are:
///   * DXpedition / WWROF report: ft8_lib (and iOS + desktop) zero-pad to two
///     digits per WSJT-X (`-08`, `+00`, `+05`); Android drops the leading zero
///     (`-8`, `+0`, `+5`). iOS matches the C canonical + desktop; Android is the
///     outlier, so iOS is intentionally left as-is.
///   * Telemetry / EU-VHF / Contesting: iOS shows the full decoded string;
///     Android routes them through its free-text branch and truncates to 13
///     chars. iOS is strictly more complete.
///   * Free text: Android right-pads to 13 chars; iOS/ft8_lib emit the trimmed
///     text. Cosmetic (display width) only.
///
/// These tests therefore lock the CURRENT (correct) iOS output and document the
/// Android relationship inline, rather than regressing iOS to an Android quirk.
///
/// Because the ft8_lib encoder only packs standard/non-standard frames, the
/// contest-type payloads here are assembled bit-for-bit from the documented
/// 77-bit layouts (mirrored from `ftx_message_decode_*` in ft8_lib/ft8/message.c),
/// borrowing real `c28` callsign codes from a standard `pack77` frame so the
/// callsigns round-trip through `unpack28`.
final class MessageTextParityTests: XCTestCase {

    // MARK: - Bit helpers (MSB-first over a 10-byte FT8 payload; bit 0 = payload[0] & 0x80)

    private func bitsGet(_ p: [UInt8], _ start: Int, _ len: Int) -> UInt32 {
        var v: UInt32 = 0
        for k in 0..<len {
            let idx = start + k
            let bit = (p[idx >> 3] >> (7 - (idx & 7))) & 1
            v = (v << 1) | UInt32(bit)
        }
        return v
    }

    private func bitsSet(_ p: inout [UInt8], _ start: Int, _ len: Int, _ value: UInt32) {
        for k in 0..<len {
            let idx = start + k
            let bit = (value >> (len - 1 - k)) & 1
            let mask = UInt8(1) << (7 - (idx & 7))
            if bit == 1 { p[idx >> 3] |= mask } else { p[idx >> 3] &= ~mask }
        }
    }

    /// The 28-bit standard-callsign code for `call`, lifted from the first
    /// callsign slot of a `pack77` standard frame (ip=0 for a plain base call).
    private func c28(_ call: String) -> UInt32 {
        guard let p = FT8Encoder.pack77("\(call) W1AW FN42") else {
            XCTFail("pack77 failed for \(call)"); return 0
        }
        return bitsGet(p, 0, 28)
    }

    /// Finalize `rawText` exactly as `FT8Decoder.buildMessage` does: run the
    /// shared top-level `ftx_message_decode` with a hash table installed for the
    /// pass (verbatim mirror of the two lines in buildMessage).
    private func rawText(_ payload: [UInt8], hashTable: HashTable = HashTable()) -> String {
        var msg = ftx_message_t()
        withUnsafeMutableBytes(of: &msg.payload) { dst in
            payload.withUnsafeBytes { src in dst.copyMemory(from: src) }
        }
        installActiveHashTable(hashTable)
        defer { clearActiveHashTable() }
        var iface = makeHashInterface()
        var buf = [CChar](repeating: 0, count: 64)
        let rc = buf.withUnsafeMutableBufferPointer { ftx_message_decode(&msg, &iface, $0.baseAddress) }
        guard rc == FTX_MESSAGE_RC_OK else {
            XCTFail("ftx_message_decode failed rc=\(rc)")
            return ""
        }
        return buf.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }
    }

    private func typeI3N3(_ payload: [UInt8]) -> (UInt8, UInt8) {
        var msg = ftx_message_t()
        withUnsafeMutableBytes(of: &msg.payload) { dst in
            payload.withUnsafeBytes { src in dst.copyMemory(from: src) }
        }
        return (ftx_message_get_i3(&msg), ftx_message_get_n3(&msg))
    }

    // MARK: - Standard / CQ regression (the common path must stay byte-identical)

    func testStandardAndCqUnchanged() {
        // Standard directed message.
        guard let std = FT8Encoder.pack77("W9XYZ K1ABC FN42") else { return XCTFail("pack std") }
        XCTAssertEqual(rawText(std), "W9XYZ K1ABC FN42")

        // CQ message.
        guard let cq = FT8Encoder.pack77("CQ K1ABC FN42") else { return XCTFail("pack cq") }
        XCTAssertEqual(rawText(cq), "CQ K1ABC FN42")

        // Standard with a signed report + R (report formatting comes straight
        // from the C decode_std path on BOTH platforms — no divergence).
        guard let rpt = FT8Encoder.pack77("W9XYZ K1ABC R-08") else { return XCTFail("pack rpt") }
        XCTAssertEqual(rawText(rpt), "W9XYZ K1ABC R-08")

        // Standard acknowledgements.
        guard let rr73 = FT8Encoder.pack77("W9XYZ K1ABC RR73") else { return XCTFail("pack rr73") }
        XCTAssertEqual(rawText(rr73), "W9XYZ K1ABC RR73")
    }

    // MARK: - Field Day (i3=0, n3=3/4) — already identical to Android

    func testFieldDay_n3_3() {
        var p = [UInt8](repeating: 0, count: 10)
        bitsSet(&p, 0, 28, c28("K1ABC"))   // call_to
        bitsSet(&p, 28, 28, c28("W9XYZ"))  // call_de
        bitsSet(&p, 56, 1, 0)              // R1
        bitsSet(&p, 57, 4, 5)              // n4 = num_tx-1 (=6)
        bitsSet(&p, 61, 3, 0)              // k3 = class A
        bitsSet(&p, 64, 7, 75)             // S7 = WI
        bitsSet(&p, 71, 3, 3)              // n3
        bitsSet(&p, 74, 3, 0)              // i3
        // Android getMessageText: "%s %s %s%d%s %s" -> "K1ABC W9XYZ 6A WI"
        XCTAssertEqual(rawText(p), "K1ABC W9XYZ 6A WI")
    }

    func testFieldDay_n3_4_withR() {
        var p = [UInt8](repeating: 0, count: 10)
        bitsSet(&p, 0, 28, c28("W9XYZ"))
        bitsSet(&p, 28, 28, c28("K1ABC"))
        bitsSet(&p, 56, 1, 1)              // R1 set
        bitsSet(&p, 57, 4, 2)              // n4 -> num_tx 3
        bitsSet(&p, 61, 3, 1)              // k3 = class B
        bitsSet(&p, 64, 7, 10)             // S7 = EMA
        bitsSet(&p, 71, 3, 4)              // n3
        bitsSet(&p, 74, 3, 0)
        XCTAssertEqual(rawText(p), "W9XYZ K1ABC R 3B EMA")
    }

    // MARK: - RTTY Roundup (i3=3) — already identical to Android

    func testRtty_state() {
        var p = [UInt8](repeating: 0, count: 10)
        bitsSet(&p, 0, 1, 0)               // t1
        bitsSet(&p, 1, 28, c28("K1ABC"))
        bitsSet(&p, 29, 28, c28("W9XYZ"))
        bitsSet(&p, 57, 1, 0)              // R1
        bitsSet(&p, 58, 3, 5)              // r3 -> report 579
        bitsSet(&p, 61, 13, 8049)          // s13 -> state WI (8001 + index 48)
        bitsSet(&p, 74, 3, 3)              // i3
        XCTAssertEqual(rawText(p), "K1ABC W9XYZ 579 WI")
    }

    func testRtty_tuAndSerial() {
        var p = [UInt8](repeating: 0, count: 10)
        bitsSet(&p, 0, 1, 1)               // t1 -> "TU; "
        bitsSet(&p, 1, 28, c28("K1ABC"))
        bitsSet(&p, 29, 28, c28("W9XYZ"))
        bitsSet(&p, 57, 1, 1)              // R1 -> "R "
        bitsSet(&p, 58, 3, 7)              // r3 -> report 599
        bitsSet(&p, 61, 13, 123)           // s13 -> serial 123 (4-digit)
        bitsSet(&p, 74, 3, 3)
        XCTAssertEqual(rawText(p), "TU; K1ABC W9XYZ R 599 0123")
    }

    // MARK: - DXpedition (i3=0, n3=1) — report zero-pad diverges from Android

    func testDxpedition_negativeReport() {
        var p = [UInt8](repeating: 0, count: 10)
        bitsSet(&p, 0, 28, c28("K1ABC"))   // acked
        bitsSet(&p, 28, 28, c28("W9XYZ"))  // invited
        bitsSet(&p, 56, 10, 42)            // h10 (unresolved -> "<...>")
        bitsSet(&p, 66, 5, 11)             // r5=11 -> report -8
        bitsSet(&p, 71, 3, 1)              // n3
        bitsSet(&p, 74, 3, 0)
        // iOS / ft8_lib / desktop: "-08" (WSJT-X 2-digit). Android drops the
        // leading zero -> "K1ABC RR73; W9XYZ <...> -8".
        XCTAssertEqual(rawText(p), "K1ABC RR73; W9XYZ <...> -08")
    }

    func testDxpedition_zeroReport() {
        var p = [UInt8](repeating: 0, count: 10)
        bitsSet(&p, 0, 28, c28("K1ABC"))
        bitsSet(&p, 28, 28, c28("W9XYZ"))
        bitsSet(&p, 56, 10, 42)
        bitsSet(&p, 66, 5, 15)             // r5=15 -> report 0
        bitsSet(&p, 71, 3, 1)
        bitsSet(&p, 74, 3, 0)
        // iOS "+00" (Android "+0").
        XCTAssertEqual(rawText(p), "K1ABC RR73; W9XYZ <...> +00")
    }

    // MARK: - WWROF (i3=5) — report zero-pad diverges from Android

    func testWwrof_withR() {
        var p = [UInt8](repeating: 0, count: 10)
        bitsSet(&p, 0, 1, 0)               // t1
        bitsSet(&p, 1, 28, c28("K1ABC"))
        bitsSet(&p, 29, 28, c28("W9XYZ"))
        bitsSet(&p, 57, 1, 1)              // R1 -> "R"
        bitsSet(&p, 58, 7, 40)             // r7=40 -> report +5
        bitsSet(&p, 65, 9, 176)            // s9 -> grid "JO"
        bitsSet(&p, 74, 3, 5)              // i3
        // iOS / ft8_lib: "R+05". Android: "K1ABC W9XYZ R+5 JO".
        XCTAssertEqual(rawText(p), "K1ABC W9XYZ R+05 JO")
    }

    func testWwrof_negativeNoR() {
        var p = [UInt8](repeating: 0, count: 10)
        bitsSet(&p, 0, 1, 0)
        bitsSet(&p, 1, 28, c28("K1ABC"))
        bitsSet(&p, 29, 28, c28("W9XYZ"))
        bitsSet(&p, 57, 1, 0)              // R1 clear
        bitsSet(&p, 58, 7, 27)             // r7=27 -> report -8
        bitsSet(&p, 65, 9, 176)            // grid "JO"
        bitsSet(&p, 74, 3, 5)
        XCTAssertEqual(rawText(p), "K1ABC W9XYZ -08 JO")
    }

    // MARK: - Telemetry (i3=0, n3=5) — iOS shows the full 18 hex; Android truncates to 13

    func testTelemetry_fullHex() {
        var p: [UInt8] = [0x24, 0x68, 0xAC, 0xE0, 0x13, 0x57, 0x9B, 0xDE, 0x00, 0x00]
        // n3=5 (0b101): bit2 = payload[8] bit0, bits1-0 = payload[9] bits7-6.
        bitsSet(&p, 71, 3, 5)              // n3
        bitsSet(&p, 74, 3, 0)              // i3

        let (i3, n3) = typeI3N3(p)
        XCTAssertEqual(i3, 0)
        XCTAssertEqual(n3, 5)

        let text = rawText(p)
        // iOS keeps the full 18-nibble hex string; Android's free-text branch
        // would render only the first 13 characters.
        XCTAssertEqual(text.count, 18)
        XCTAssertTrue(text.allSatisfy { $0.isHexDigit && ($0.isNumber || $0.isUppercase) },
                      "telemetry should be uppercase hex, got \(text)")
    }
}
