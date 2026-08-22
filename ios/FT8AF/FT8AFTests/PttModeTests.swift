import XCTest
@testable import FT8AF

final class PttModeTests: XCTestCase {
    func testOnlyVoxIsSelectableOnIOS() {
        XCTAssertEqual(PttMode.selectableOnIOS, [.vox])
    }

    func testNonVoxCasesAreRetained() {
        // Kept for a future Wi-Fi/rigctld bridge even though they're not offered.
        XCTAssertTrue(PttMode.allCases.contains(.cat))
        XCTAssertTrue(PttMode.allCases.contains(.rts))
        XCTAssertTrue(PttMode.allCases.contains(.dtr))
    }

    // MARK: - Legacy value migration

    func testVoxIsLeftAlone() {
        XCTAssertEqual(PttMode.vox.coercedForIOS, .vox)
    }

    func testEveryUnsupportedModeCoercesToVox() {
        for mode in PttMode.allCases where !PttMode.selectableOnIOS.contains(mode) {
            XCTAssertEqual(mode.coercedForIOS, .vox, "\(mode.rawValue) must migrate to VOX on iOS")
        }
    }

    /// A CAT/RTS/DTR value persisted by an older build must come back as VOX
    /// from `SettingsPersistence.load` — the model itself, not just the
    /// picker's display — so the settings summary and the next save agree.
    @MainActor
    func testPersistedLegacyModeLoadsAsVox() {
        let key = "ft8af_pttMode"
        let defaults = UserDefaults.standard
        let previous = defaults.string(forKey: key)
        defer {
            if let previous { defaults.set(previous, forKey: key) } else { defaults.removeObject(forKey: key) }
        }

        for legacy in [PttMode.cat, .rts, .dtr] {
            defaults.set(legacy.rawValue, forKey: key)
            let state = SettingsState()
            state.pttMode = .cat  // anything non-VOX, to prove load() overwrote it
            SettingsPersistence.load(into: state)
            XCTAssertEqual(state.pttMode, .vox, "persisted \(legacy.rawValue) must load as VOX")
        }

        // And a persisted VOX still loads as VOX (no over-coercion).
        defaults.set(PttMode.vox.rawValue, forKey: key)
        let state = SettingsState()
        SettingsPersistence.load(into: state)
        XCTAssertEqual(state.pttMode, .vox)
    }
}
