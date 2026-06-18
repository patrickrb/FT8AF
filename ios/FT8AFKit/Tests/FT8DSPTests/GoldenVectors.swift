import Foundation

// Golden vectors copied VERBATIM from
//   ft8af/app/src/main/cpp/ft8af_glue/test_golden_encode.c
// so iOS encode is pinned bit-for-bit to the Android app and desktop port.
// Never hand-edit a byte: regenerate via that C test's `--emit` mode if the
// FT8 spec/vectors ever intentionally change.
enum Golden {

    /// Reference messages spanning the standard FT8 message-type space.
    static let msgs: [String] = [
        "CQ K1ABC FN42",    // CQ + grid           (Type 1, standard call)
        "CQ DX K1ABC FN42", // CQ DX directed
        "W9XYZ K1ABC FN42", // call-to-call + grid
        "W9XYZ K1ABC -15",  // signal report
        "W9XYZ K1ABC R-08", // R + report
        "W9XYZ K1ABC RRR",  // RRR
        "W9XYZ K1ABC RR73", // RR73
        "W9XYZ K1ABC 73",   // 73
        "CQ G4ABC/P IO91",  // /P suffix           (Type 2)
        "PJ4/K1ABC RR73",   // nonstandard compound (Type 4)
    ]

    /// pack77(message) golden: 77-bit payload, 10 bytes MSB-first (low 3 bits of
    /// byte 9 are unused and masked out of comparisons).
    static let payload: [[UInt8]] = [
        [0x00,0x00,0x00,0x20,0x4D,0xEF,0x1A,0x8A,0x19,0x88], // CQ K1ABC FN42
        [0x2C,0x91,0x27,0x42,0xE2,0x5D,0xA9,0x25,0x68,0x00], // CQ DX K1ABC FN42
        [0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0x8A,0x19,0x88], // W9XYZ K1ABC FN42
        [0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0x9F,0xA9,0x08], // W9XYZ K1ABC -15
        [0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0xBF,0xAA,0xC8], // W9XYZ K1ABC R-08
        [0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0x9F,0xA4,0x88], // W9XYZ K1ABC RRR
        [0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0x9F,0xA4,0xC8], // W9XYZ K1ABC RR73
        [0x0C,0x29,0x3B,0x80,0x4D,0xEF,0x1A,0x9F,0xA5,0x08], // W9XYZ K1ABC 73
        [0x2C,0x91,0x2D,0xF3,0xD6,0x11,0xE7,0x57,0xCE,0x00], // CQ G4ABC/P IO91
        [0x56,0x7F,0xD3,0xB0,0x0A,0x0C,0x28,0xC7,0x40,0x00], // PJ4/K1ABC RR73
    ]

    /// ft8_encode(payload) golden: 79 tones (0..7).
    static let tones: [[UInt8]] = [
        [3,1,4,0,6,5,2,0,0,0,0,0,0,0,0,1,0,0,5,4,7,6,7,0,4,6,0,6,0,2,1,5,3,3,4,3,3,1,4,0,6,5,2,7,3,6,0,1,1,0,4,7,5,1,7,0,0,7,3,3,4,7,4,5,4,5,5,1,3,3,5,4,3,1,4,0,6,5,2],
        [3,1,4,0,6,5,2,1,2,1,1,0,5,5,7,3,0,6,4,1,1,2,6,6,3,3,3,3,6,6,0,0,1,1,3,6,3,1,4,0,6,5,2,7,4,3,6,1,1,3,7,2,7,5,4,3,1,6,3,6,1,4,5,7,4,3,0,2,0,6,3,4,3,1,4,0,6,5,2],
        [3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,0,6,0,2,1,5,3,5,7,2,3,1,4,0,6,5,2,0,5,3,1,6,5,5,7,4,0,6,1,7,4,0,3,0,0,4,3,4,7,2,2,5,4,1,2,2,3,1,4,0,6,5,2],
        [3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,1,7,4,6,1,0,2,1,4,0,3,1,4,0,6,5,2,5,4,5,0,4,2,0,1,1,5,0,1,3,6,5,2,4,7,2,2,5,3,1,4,5,1,5,5,2,3,1,4,0,6,5,2],
        [3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,2,7,4,6,3,4,3,5,4,6,3,1,4,0,6,5,2,2,3,1,5,7,4,3,0,3,2,5,5,7,1,1,7,0,0,5,6,2,6,1,4,5,6,0,7,4,3,1,4,0,6,5,2],
        [3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,1,7,4,5,5,5,3,0,3,1,3,1,4,0,6,5,2,5,6,4,3,0,5,5,3,5,1,6,1,1,1,7,5,2,4,5,2,3,1,2,7,7,5,3,2,7,3,1,4,0,6,5,2],
        [3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,1,7,4,5,5,4,2,4,1,2,3,1,4,0,6,5,2,1,3,4,5,0,4,3,1,0,0,7,5,3,3,2,6,2,0,6,6,1,2,7,6,4,1,2,4,3,3,1,4,0,6,5,2],
        [3,1,4,0,6,5,2,0,2,0,3,5,5,7,2,5,0,0,5,4,7,6,7,0,4,6,1,7,4,5,6,0,2,7,3,1,3,1,4,0,6,5,2,6,1,4,5,0,7,5,0,5,2,3,3,7,4,6,5,4,5,0,7,0,4,0,3,0,6,5,5,6,3,1,4,0,6,5,2],
        [3,1,4,0,6,5,2,1,2,1,1,0,5,6,6,7,5,7,6,2,0,3,1,7,1,4,6,2,7,1,4,0,1,3,4,6,3,1,4,0,6,5,2,6,6,1,3,4,1,4,7,5,0,6,2,2,7,4,0,5,2,7,1,7,7,2,5,2,1,2,0,4,3,1,4,0,6,5,2],
        [3,1,4,0,6,5,2,3,6,5,7,7,7,3,2,6,5,0,0,6,0,1,5,1,3,1,5,2,6,0,0,0,1,5,1,5,3,1,4,0,6,5,2,2,7,3,7,1,7,4,3,4,7,4,6,5,1,2,1,6,1,2,6,4,2,7,1,3,1,4,5,3,3,1,4,0,6,5,2],
    ]

    /// Callsign-hash golden: { h22, h12, h10 }.
    static let calls: [String] = ["K1ABC", "W9XYZ", "G4ABC/P", "PJ4/K1ABC", "VK7BO", "EA1ABC"]
    static let hashes: [(h22: UInt32, h12: UInt32, h10: UInt32)] = [
        (2920267, 2851, 712), // K1ABC
        (3982604, 3889, 972), // W9XYZ
        (3288979, 3211, 802), // G4ABC/P
        (1420834, 1387, 346), // PJ4/K1ABC
        (2117032, 2067, 516), // VK7BO
        ( 527469,  515, 128), // EA1ABC
    ]

    /// Compare two 77-bit payloads: bytes 0..8 full, byte 9 top 5 bits
    /// (the low 3 bits are unused payload, masked out — see payload77_eq in C).
    static func payload77Eq(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        for i in 0..<9 where a[i] != b[i] { return false }
        return (a[9] & 0xF8) == (b[9] & 0xF8)
    }

    /// Costas sync symbols sit at indices 0-6, 36-42, 72-78.
    static func isCostasPos(_ t: Int) -> Bool {
        (t >= 0 && t < 7) || (t >= 36 && t < 43) || (t >= 72 && t < 79)
    }
}
