import XCTest
@testable import FT8Audio

final class AudioSessionPolicyTests: XCTestCase {
    func testUsbPresentDropsDefaultToSpeaker() {
        let opts = AudioSessionPolicy.playAndRecordOptions(usbAudioConnected: true)
        XCTAssertFalse(opts.contains(.defaultToSpeaker),
                       "With USB audio attached, output must follow to the interface, not the speaker")
        XCTAssertTrue(opts.contains(.allowBluetooth))
    }

    func testNoUsbKeepsDefaultToSpeaker() {
        let opts = AudioSessionPolicy.playAndRecordOptions(usbAudioConnected: false)
        XCTAssertTrue(opts.contains(.defaultToSpeaker),
                      "On a bare device, RX must stay audible on the speaker")
        XCTAssertTrue(opts.contains(.allowBluetooth))
    }
}
