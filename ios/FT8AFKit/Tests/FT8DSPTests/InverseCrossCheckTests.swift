import XCTest
import CFT8
@testable import FT8DSP

/// Layer (B): prove each golden tone set is a GENUINE FT8 frame for its payload,
/// using the decoder's own LDPC parity + CRC validation — no encoder involved.
/// Invert the Gray map, drop the Costas symbols, rebuild the 174-bit codeword,
/// confirm every LDPC parity check is satisfied and the CRC validates, and that
/// the recovered 77-bit payload equals the golden payload. Verbatim port of
/// test_golden_encode.c's test_inverse_crosscheck, using the same C tables/CRC
/// (kFT8_Gray_map, kFTX_LDPC_Nm, kFTX_LDPC_Num_rows, ftx_extract_crc,
/// ftx_compute_crc) via CFT8 so the check stays independent of FT8DSP's encoder.
final class InverseCrossCheckTests: XCTestCase {

    private let ldpcN = Int(FTX_LDPC_N) // 174
    private let ldpcM = Int(FTX_LDPC_M) // 83
    private let ldpcK = Int(FTX_LDPC_K) // 91

    /// 79 tones -> 174-bit hard codeword via inverse Gray map. nil on a bad tone
    /// or wrong bit count.
    private func tonesToCodeword(_ tones: [UInt8]) -> [UInt8]? {
        var invGray = [UInt8](repeating: 0, count: 8)
        withUnsafeBytes(of: kFT8_Gray_map) { g in
            for b in 0..<8 { invGray[Int(g[b])] = UInt8(b) }
        }
        var cw = [UInt8]()
        cw.reserveCapacity(ldpcN)
        for t in 0..<79 {
            if Golden.isCostasPos(t) { continue }
            if tones[t] > 7 { return nil }
            let b3 = invGray[Int(tones[t])]
            cw.append((b3 >> 2) & 1)
            cw.append((b3 >> 1) & 1)
            cw.append((b3 >> 0) & 1)
        }
        return cw.count == ldpcN ? cw : nil
    }

    /// Count unsatisfied LDPC parity checks; 0 == valid codeword. Port of the
    /// static ldpc_check() in ft8_lib/ft8/ldpc.c.
    private func ldpcCheckHard(_ cw: [UInt8]) -> Int {
        var errors = 0
        withUnsafeBytes(of: kFTX_LDPC_Num_rows) { numRows in
            withUnsafeBytes(of: kFTX_LDPC_Nm) { nm in
                for m in 0..<ldpcM {
                    var x: UInt8 = 0
                    let rows = Int(numRows[m])
                    for i in 0..<rows {
                        x ^= cw[Int(nm[m * 7 + i]) - 1]
                    }
                    if x != 0 { errors += 1 }
                }
            }
        }
        return errors
    }

    /// Pack the first FTX_LDPC_K (91) hard bits, MSB-first, into a 12-byte a91.
    private func packA91(_ cw: [UInt8]) -> [UInt8] {
        var a91 = [UInt8](repeating: 0, count: 12)
        for i in 0..<ldpcK where cw[i] != 0 {
            a91[i / 8] |= UInt8(0x80 >> (i % 8))
        }
        return a91
    }

    func testGoldenTonesAreGenuineFT8Frames() {
        for (k, msg) in Golden.msgs.enumerated() {
            guard let cw = tonesToCodeword(Golden.tones[k]) else {
                XCTFail("[\(msg)] tones->codeword failed (out-of-range tone)")
                continue
            }
            XCTAssertEqual(ldpcCheckHard(cw), 0, "[\(msg)] LDPC parity not satisfied")

            var a91 = packA91(cw)
            let crcExtracted = a91.withUnsafeBufferPointer { ftx_extract_crc($0.baseAddress) }
            a91[9] &= 0xF8 // zero the CRC region exactly as decode.c does
            a91[10] = 0
            let crcCalculated = a91.withUnsafeBufferPointer { ftx_compute_crc($0.baseAddress, 96 - 14) }
            XCTAssertEqual(crcExtracted, crcCalculated, "[\(msg)] CRC mismatch")

            XCTAssertTrue(Golden.payload77Eq(a91, Golden.payload[k]),
                          "[\(msg)] recovered payload != golden payload")
        }
    }
}
