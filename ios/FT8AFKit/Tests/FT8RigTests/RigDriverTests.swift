import XCTest
@testable import FT8Rig

final class RigDriverTests: XCTestCase {

    // MARK: Yaesu

    func testYaesuFrequencyCommand() { // port of rig.rs yaesu_freq_command
        XCTAssertEqual(YaesuDriver().frequencyCommand(14_074_000), Array("FA014074000;".utf8))
    }

    func testYaesuDataModeAndPtt() {
        XCTAssertEqual(YaesuDriver().dataModeCommand(), Array("MD0C;NA00;SH0117;".utf8))
        XCTAssertEqual(YaesuDriver().pttCommand(true), Array("TX1;".utf8))
        XCTAssertEqual(YaesuDriver().pttCommand(false), Array("TX0;".utf8))
    }

    // MARK: Kenwood

    func testKenwoodFrequencyCommand() {
        XCTAssertEqual(KenwoodDriver().frequencyCommand(14_074_000), Array("FA00014074000;".utf8))
    }

    func testKenwoodDataModeAndPtt() {
        XCTAssertEqual(KenwoodDriver().dataModeCommand(), Array("MD2;".utf8))
        XCTAssertEqual(KenwoodDriver().pttCommand(true), Array("TX;".utf8))
        XCTAssertEqual(KenwoodDriver().pttCommand(false), Array("RX;".utf8))
    }

    // MARK: Icom CI-V

    func testIcomFrequencyBcdLittleEndian() { // port of rig.rs icom_freq_bcd_little_endian
        XCTAssertEqual(IcomDriver.frequencyBCD(14_074_000), [0x00, 0x40, 0x07, 0x14, 0x00])
    }

    func testIcomFrequencyBcdAnotherFreq() {
        XCTAssertEqual(IcomDriver.frequencyBCD(7_074_000), [0x00, 0x40, 0x07, 0x07, 0x00])
    }

    func testIcomFrequencyFrame() { // port of rig.rs icom_freq_frame
        XCTAssertEqual(IcomDriver(address: 0x94).frequencyCommand(14_074_000),
                       [0xFE, 0xFE, 0x94, 0xE0, 0x05, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD])
    }

    func testIcomPttFrame() { // port of rig.rs icom_ptt_frame (+ unkey)
        XCTAssertEqual(IcomDriver(address: 0x94).pttCommand(true),
                       [0xFE, 0xFE, 0x94, 0xE0, 0x1C, 0x00, 0x01, 0xFD])
        XCTAssertEqual(IcomDriver(address: 0x94).pttCommand(false),
                       [0xFE, 0xFE, 0x94, 0xE0, 0x1C, 0x00, 0x00, 0xFD])
    }

    func testIcomDataModeFrame() {
        XCTAssertEqual(IcomDriver(address: 0x94).dataModeCommand(),
                       [0xFE, 0xFE, 0x94, 0xE0, 0x26, 0x00, 0x01, 0x01, 0x01, 0xFD])
    }

    func testIcomEmbedsCivAddress() {
        // A non-default rig address (e.g. IC-705 = 0xA4) is placed in the frame.
        XCTAssertEqual(IcomDriver(address: 0xA4).pttCommand(true),
                       [0xFE, 0xFE, 0xA4, 0xE0, 0x1C, 0x00, 0x01, 0xFD])
    }

    // MARK: factory + model

    func testDriverFactory() {
        XCTAssertNil(rigDriver(for: .none))
        XCTAssertTrue(rigDriver(for: .yaesu) is YaesuDriver)
        XCTAssertTrue(rigDriver(for: .kenwood) is KenwoodDriver)
        XCTAssertEqual((rigDriver(for: .icom, civAddress: 0xA2) as? IcomDriver)?.address, 0xA2)
        XCTAssertEqual((rigDriver(for: .icom) as? IcomDriver)?.address, defaultCivAddress)
        XCTAssertEqual(defaultCivAddress, 0x94)
    }

    func testRigModelRawValues() {
        XCTAssertEqual(RigModel.icom.rawValue, "icom")
        XCTAssertEqual(RigModel(rawValue: "kenwood"), .kenwood)
    }
}
