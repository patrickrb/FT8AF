import XCTest
@testable import FT8Audio

final class AudioPortLabelTests: XCTestCase {
    func testBuiltInMicIgnoresPortName() {
        XCTAssertEqual(AudioPortLabel.label(kind: .builtInMic, portName: "iPhone Microphone"),
                       "Built-in Microphone")
    }

    func testSpeakerAndReceiverAreFixed() {
        XCTAssertEqual(AudioPortLabel.label(kind: .speaker, portName: "Speaker"), "Speaker")
        XCTAssertEqual(AudioPortLabel.label(kind: .receiver, portName: "Receiver"), "Receiver")
    }

    func testUsbAppendsTransportSuffix() {
        XCTAssertEqual(AudioPortLabel.label(kind: .usb, portName: "CM108 Audio"),
                       "CM108 Audio (USB)")
    }

    func testUsbSkipsSuffixWhenNameMentionsUsb() {
        XCTAssertEqual(AudioPortLabel.label(kind: .usb, portName: "USB Audio CODEC"),
                       "USB Audio CODEC")
        XCTAssertEqual(AudioPortLabel.label(kind: .usb, portName: "Digirig usb"),
                       "Digirig usb")
    }

    func testUsbEmptyNameFallsBack() {
        XCTAssertEqual(AudioPortLabel.label(kind: .usb, portName: ""), "USB Audio")
    }

    func testBluetoothSuffix() {
        XCTAssertEqual(AudioPortLabel.label(kind: .bluetooth, portName: "AirPods Pro"),
                       "AirPods Pro (Bluetooth)")
        XCTAssertEqual(AudioPortLabel.label(kind: .bluetooth, portName: ""), "Bluetooth Audio")
    }

    func testWiredUsesNameOrFallback() {
        XCTAssertEqual(AudioPortLabel.label(kind: .wired, portName: "Headset Microphone"),
                       "Headset Microphone")
        XCTAssertEqual(AudioPortLabel.label(kind: .wired, portName: "  "), "Wired")
    }

    func testOtherFallsBackToUnknown() {
        XCTAssertEqual(AudioPortLabel.label(kind: .other, portName: ""), "Unknown Device")
        XCTAssertEqual(AudioPortLabel.label(kind: .other, portName: "HDMI"), "HDMI")
    }

    func testWhitespaceTrimmed() {
        XCTAssertEqual(AudioPortLabel.label(kind: .usb, portName: " Radio Cable "),
                       "Radio Cable (USB)")
    }
}
