import Foundation

/// Rig families whose CAT command bytes we know how to build.
public enum RigModel: String, Codable, Equatable {
    case none
    case yaesu
    case kenwood
    case icom
}

/// Builds the raw CAT command bytes for a rig model. The transport that writes
/// them (BLE CAT adapter, or serial-over-network) is device code; this framing is
/// pure and host-testable. Port of the per-model `Driver` impls in desktop rig.rs.
public protocol RigDriver {
    /// Set the VFO-A dial frequency (Hz).
    func frequencyCommand(_ hz: UInt64) -> [UInt8]
    /// Switch to the FT8 data/DIG mode (+ filter where applicable).
    func dataModeCommand() -> [UInt8]
    /// Key (`true`) or unkey (`false`) PTT.
    func pttCommand(_ on: Bool) -> [UInt8]
}

/// Default CI-V radio address (0x94 = IC-7300).
public let defaultCivAddress: UInt8 = 0x94

/// The driver for a model, or nil for `.none`. `civAddress` only applies to Icom.
public func rigDriver(for model: RigModel, civAddress: UInt8 = defaultCivAddress) -> RigDriver? {
    switch model {
    case .yaesu: return YaesuDriver()
    case .kenwood: return KenwoodDriver()
    case .icom: return IcomDriver(address: civAddress)
    case .none: return nil
    }
}

/// Decimal `hz` as a zero-padded, fixed-width ASCII string (Yaesu/Kenwood CAT).
/// Avoids printf width/`%llu` pitfalls with UInt64.
func zeroPadded(_ hz: UInt64, width: Int) -> String {
    let s = String(hz)
    return s.count >= width ? s : String(repeating: "0", count: width - s.count) + s
}

// MARK: Yaesu (ASCII CAT, e.g. FT-991A/FTDX)

public struct YaesuDriver: RigDriver {
    public init() {}
    public func frequencyCommand(_ hz: UInt64) -> [UInt8] {
        Array("FA\(zeroPadded(hz, width: 9));".utf8)
    }
    public func dataModeCommand() -> [UInt8] { Array("MD0C;NA00;SH0117;".utf8) }
    public func pttCommand(_ on: Bool) -> [UInt8] { Array((on ? "TX1;" : "TX0;").utf8) }
}

// MARK: Kenwood (ASCII CAT, e.g. TS-590/TS-890)

public struct KenwoodDriver: RigDriver {
    public init() {}
    public func frequencyCommand(_ hz: UInt64) -> [UInt8] {
        Array("FA\(zeroPadded(hz, width: 11));".utf8)
    }
    public func dataModeCommand() -> [UInt8] { Array("MD2;".utf8) }
    public func pttCommand(_ on: Bool) -> [UInt8] { Array((on ? "TX;" : "RX;").utf8) }
}

// MARK: Icom (CI-V)

public struct IcomDriver: RigDriver {
    public let address: UInt8
    public init(address: UInt8 = defaultCivAddress) { self.address = address }

    /// Wrap a CI-V body: FE FE <radio> E0 <body...> FD.
    func frame(_ body: [UInt8]) -> [UInt8] {
        [0xFE, 0xFE, address, 0xE0] + body + [0xFD]
    }

    /// 10-digit frequency as 5 little-endian BCD bytes (two digits per byte,
    /// low nibble first). Port of Icom::freq_bcd.
    static func frequencyBCD(_ hz: UInt64) -> [UInt8] {
        var digits = [UInt8](repeating: 0, count: 10)
        var f = hz
        for i in 0..<10 {
            digits[i] = UInt8(f % 10)
            f /= 10
        }
        var out = [UInt8](repeating: 0, count: 5)
        for i in 0..<5 {
            out[i] = digits[2 * i] | (digits[2 * i + 1] << 4)
        }
        return out
    }

    public func frequencyCommand(_ hz: UInt64) -> [UInt8] {
        frame([0x05] + IcomDriver.frequencyBCD(hz))
    }
    public func dataModeCommand() -> [UInt8] {
        // 0x26 set-mode: DATA mode on, FIL1 (USB-D).
        frame([0x26, 0x00, 0x01, 0x01, 0x01])
    }
    public func pttCommand(_ on: Bool) -> [UInt8] {
        frame([0x1C, 0x00, on ? 0x01 : 0x00])
    }
}
