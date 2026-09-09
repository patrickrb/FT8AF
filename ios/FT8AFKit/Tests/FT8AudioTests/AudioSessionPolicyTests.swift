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

    // MARK: - Bootstrap options

    func testBootstrapOptionsNeverPinTheSpeaker() {
        // The bootstrap category is applied before USB presence is knowable;
        // pinning the speaker there would steal TX from an attached DigiRig
        // until the policy is re-applied.
        XCTAssertFalse(AudioSessionPolicy.bootstrapOptions.contains(.defaultToSpeaker))
        XCTAssertTrue(AudioSessionPolicy.bootstrapOptions.contains(.allowBluetooth))
    }

    func testBootstrapOptionsMatchTheUsbPresentPolicy() {
        // So the USB-present case needs no second category change after
        // bootstrap (each setCategory emits a route-change notification).
        XCTAssertEqual(AudioSessionPolicy.bootstrapOptions,
                       AudioSessionPolicy.playAndRecordOptions(usbAudioConnected: true))
    }

    // MARK: - USB presence predicate (one test per detection path)

    func testUsbInAvailableInputsCounts() {
        XCTAssertTrue(AudioSessionPolicy.usbAudioPresent(
            availableInputs: [.builtInMic, .usb], routeInputs: [.builtInMic], routeOutputs: [.speaker]))
    }

    func testUsbAsCurrentInputCounts() {
        XCTAssertTrue(AudioSessionPolicy.usbAudioPresent(
            availableInputs: [], routeInputs: [.usb], routeOutputs: [.speaker]))
    }

    func testUsbAsCurrentOutputCounts() {
        XCTAssertTrue(AudioSessionPolicy.usbAudioPresent(
            availableInputs: [], routeInputs: [.builtInMic], routeOutputs: [.usb]))
    }

    func testNoUsbAnywhereIsAbsent() {
        XCTAssertFalse(AudioSessionPolicy.usbAudioPresent(
            availableInputs: [.builtInMic, .wired, .bluetooth],
            routeInputs: [.builtInMic],
            routeOutputs: [.speaker, .bluetooth]))
    }

    func testEmptyPortListsAreAbsent() {
        // The pre-bootstrap state: nothing selectable, nothing routed.
        XCTAssertFalse(AudioSessionPolicy.usbAudioPresent(
            availableInputs: [], routeInputs: [], routeOutputs: []))
    }
}
