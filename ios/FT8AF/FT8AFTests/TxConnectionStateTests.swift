import XCTest
@testable import FT8AF

/// Covers the connection-lifecycle decisions that guard `TxPlayerService.play`.
/// The regression these lock down: after an `AVAudioEngineConfigurationChange`
/// (e.g. a DigiRig USB interface attaching) the engine tears down the player
/// node's connection, and playing on a disconnected node aborts the app. The
/// state must report `needsConnect` again after such a change while keeping the
/// node attached, so `play()` reconnects instead of keying a dead node.
///
/// Assertions use `needsAttach`/`needsConnect` — the exact flags
/// `ensureConnected()` branches on — so the tests exercise the production paths.
final class TxConnectionStateTests: XCTestCase {
    func testFreshStateNeedsAttachAndConnect() {
        let s = TxConnectionState()
        XCTAssertTrue(s.needsAttach)
        XCTAssertTrue(s.needsConnect)
    }

    func testAfterAttachAndConnectNeedsNothing() {
        var s = TxConnectionState()
        s.markAttached()
        s.markConnected()
        XCTAssertFalse(s.needsAttach)
        XCTAssertFalse(s.needsConnect)
    }

    func testAttachedButNotConnectedStillNeedsConnect() {
        var s = TxConnectionState()
        s.markAttached()
        XCTAssertFalse(s.needsAttach)
        XCTAssertTrue(s.needsConnect)
    }

    /// The core regression: a config change must invalidate the connection but
    /// leave the node attached, so the next play reconnects without re-attaching.
    func testConfigChangeInvalidatesConnectionButKeepsAttachment() {
        var s = TxConnectionState()
        s.markAttached()
        s.markConnected()

        s.invalidateConnection()

        XCTAssertFalse(s.needsAttach, "node stays attached across a config change")
        XCTAssertTrue(s.needsConnect, "connection must be rebuilt after a config change")
    }

    func testReconnectAfterConfigChangeClearsNeedsConnect() {
        var s = TxConnectionState()
        s.markAttached()
        s.markConnected()
        s.invalidateConnection()

        // Simulate ensureConnected()'s reconnect path: no re-attach needed,
        // just a fresh connection.
        XCTAssertFalse(s.needsAttach)
        s.markConnected()

        XCTAssertFalse(s.needsConnect)
    }

    /// Repeated config changes (unplug/replug) must keep forcing a reconnect,
    /// never latching into a stale connected state.
    func testRepeatedConfigChangesKeepRequiringReconnect() {
        var s = TxConnectionState()
        s.markAttached()
        s.markConnected()

        for _ in 0..<5 {
            s.invalidateConnection()
            XCTAssertTrue(s.needsConnect)
            XCTAssertFalse(s.needsAttach)
            s.markConnected()
            XCTAssertFalse(s.needsConnect)
        }
    }

    // MARK: - Engine-cycle-after-connect decision

    /// The silent-TX regression: a (re)connect forms the player → mixer →
    /// output chain on the already-running capture engine, which the render
    /// thread won't pull, so `play()` must cycle the engine to make it render.
    func testMustCycleEngineAfterAFreshConnect() {
        XCTAssertTrue(TxConnectionState.mustCycleEngineAfterConnect(didConnect: true))
    }

    /// Steady-state TX (already connected, nothing reconnected) must NOT cycle
    /// the engine — otherwise every transmit slot would stutter the RX capture
    /// tap for no reason.
    func testDoesNotCycleEngineWhenAlreadyConnected() {
        XCTAssertFalse(TxConnectionState.mustCycleEngineAfterConnect(didConnect: false))
    }
}
