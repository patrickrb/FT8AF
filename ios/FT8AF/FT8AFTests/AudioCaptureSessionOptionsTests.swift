import AVFoundation
import FT8Audio
import XCTest
@testable import FT8AF

/// Verifies the app-side mapping from the pure `AudioSessionPolicy` decision
/// onto AVFoundation's `AVAudioSession.CategoryOptions`. (The decision itself
/// is host-tested in FT8AudioTests/AudioSessionPolicyTests.)
final class AudioCaptureSessionOptionsTests: XCTestCase {
    func testUsbPresentDropsDefaultToSpeaker() {
        let opts = AudioCaptureService.categoryOptions(
            from: AudioSessionPolicy.playAndRecordOptions(usbAudioConnected: true))
        XCTAssertFalse(opts.contains(.defaultToSpeaker))
        XCTAssertTrue(opts.contains(.allowBluetooth))
    }

    func testNoUsbKeepsDefaultToSpeaker() {
        let opts = AudioCaptureService.categoryOptions(
            from: AudioSessionPolicy.playAndRecordOptions(usbAudioConnected: false))
        XCTAssertTrue(opts.contains(.defaultToSpeaker))
        XCTAssertTrue(opts.contains(.allowBluetooth))
    }
}
