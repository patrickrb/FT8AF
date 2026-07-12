import XCTest
@testable import FT8Engine

final class GridToLatLonTests: XCTestCase {

    private func assertClose(
        _ actual: (Double, Double)?,
        _ lat: Double,
        _ lon: Double,
        accuracy: Double = 1e-9,
        _ message: String = "",
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        guard let actual else {
            XCTFail("expected (\(lat), \(lon)) but got nil \(message)", file: file, line: line)
            return
        }
        XCTAssertEqual(actual.0, lat, accuracy: accuracy, "lat \(message)", file: file, line: line)
        XCTAssertEqual(actual.1, lon, accuracy: accuracy, "lon \(message)", file: file, line: line)
    }

    // MARK: 4-char (unchanged from the previous app-local implementation)

    func testFourCharResolvesToSquareCentre() {
        // FN42 (Boston area): field F/N, square 4/2 → centre of the 2°×1° square.
        // lon = (5*20) + (4*2) + 1 - 180 = -71 ; lat = (13*10) + 2 + 0.5 - 90 = 42.5
        assertClose(gridToLatLon("FN42"), 42.5, -71.0)
    }

    func testSixCharPrefixMatchesFourCharCentreForSubsquareAtOrigin() {
        // "aa" is the first subsquare; its centre sits half a cell in from the
        // square's SW corner, so a 6-char "..aa" is NOT the 4-char square centre.
        // Regression guard that the two code paths stay distinct.
        let four = gridToLatLon("FN42")!
        let six = gridToLatLon("FN42aa")!
        XCTAssertNotEqual(four.0, six.0, accuracy: 1e-12)
        XCTAssertNotEqual(four.1, six.1, accuracy: 1e-12)
    }

    // MARK: 6-char subsquare precision (the fix)

    func testSixCharHighSubsquareMatchesRealLocation() {
        // IO91wm = London (true ≈ 51.52°N, -0.125°E). Dropping the subsquare (the old
        // behaviour) resolved IO91's centre at (51.5, -1.0) — ~45 km west, wrong side
        // of the prime meridian. With SUBSQUARES = 24 it lands within the subsquare.
        // lon = (8*20)+(9*2)-180 + (22+0.5)*(2/24) = -2 + 1.875 = -0.125
        // lat = (14*10)+1-90     + (12+0.5)*(1/24) = 51 + 0.520833… = 51.520833…
        assertClose(gridToLatLon("IO91wm"), 51.0 + 12.5 / 24.0, -0.125)
    }

    func testSixCharSubsquareCentreOffsets() {
        // JN58td (Munich-ish): verify both axes independently.
        // lon field J=9,square 5 → (9*20)+(5*2)-180 = 10 ; subsquare t = 't'-'a' = 19
        //   → 10 + (19+0.5)*(2/24) = 10 + 1.625 = 11.625
        // lat field N=13,square 8 → (13*10)+8-90 = 48 ; subsquare d = 3
        //   → 48 + (3+0.5)*(1/24) = 48 + 0.145833… = 48.145833…
        assertClose(gridToLatLon("JN58td"), 48.0 + 3.5 / 24.0, 11.625)
    }

    func testSubsquareIsCaseInsensitive() {
        assertClose(gridToLatLon("io91WM"), gridToLatLon("IO91wm")!.0, gridToLatLon("IO91wm")!.1)
    }

    // MARK: robustness — malformed subsquare falls back to the 4-char centre, never mislocates

    func testMalformedSubsquareFallsBackToSquareCentre() {
        // 'z' (> 'x') is not a valid subsquare letter → keep the 4-char square centre
        // rather than indexing an out-of-range cell.
        assertClose(gridToLatLon("FN42zz"), gridToLatLon("FN42")!.0, gridToLatLon("FN42")!.1)
    }

    func testFiveCharGridUsesSquareCentre() {
        assertClose(gridToLatLon("FN42a"), gridToLatLon("FN42")!.0, gridToLatLon("FN42")!.1)
    }

    // MARK: invalid input → nil

    func testTooShortReturnsNil() {
        XCTAssertNil(gridToLatLon("FN"))
        XCTAssertNil(gridToLatLon(""))
    }

    func testNonGridCharsReturnNil() {
        XCTAssertNil(gridToLatLon("ZZ99"))   // field letters must be A–R
        XCTAssertNil(gridToLatLon("FNXY"))   // square must be digits
    }
}
