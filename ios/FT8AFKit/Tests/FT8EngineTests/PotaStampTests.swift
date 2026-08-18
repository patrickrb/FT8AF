import XCTest
@testable import FT8Engine

/// POTA QSO stamping + park-to-park activator resolution — the iOS port of
/// Android `PotaSessionManager.stampQso` and `PotaSpotsRepository.parkRefFor`.
final class PotaStampTests: XCTestCase {

    private func spot(_ activator: String, _ reference: String) -> PotaSpot {
        PotaSpot(
            activator: activator, frequencyKhz: 14_074, mode: "FT8",
            reference: reference, parkName: "Test Park", locationDesc: "US-XX",
            spotter: "N0CALL", spotTimeUtc: "2026-07-13T19:44:50", comments: "")
    }

    // MARK: - Stamping decision

    func testMyParkAlwaysStampedWhenActivating() {
        let f = potaQsoStamp(activationParkRef: "US-7443", workedParkRef: nil)
        XCTAssertEqual(f.mySig, "POTA")
        XCTAssertEqual(f.mySigInfo, "US-7443")
        // Not park-to-park → no SIG.
        XCTAssertEqual(f.sig, "")
        XCTAssertEqual(f.sigInfo, "")
    }

    func testNoStampWhenNotActivatingAndNotP2P() {
        let f = potaQsoStamp(activationParkRef: nil, workedParkRef: nil)
        XCTAssertEqual(f, PotaStampFields())
    }

    func testTheirParkStampedForParkToPark() {
        let f = potaQsoStamp(activationParkRef: "US-7443", workedParkRef: "K-1234")
        XCTAssertEqual(f.mySig, "POTA")
        XCTAssertEqual(f.mySigInfo, "US-7443")
        XCTAssertEqual(f.sig, "POTA")
        XCTAssertEqual(f.sigInfo, "K-1234")
    }

    func testP2POutsideOwnActivationStampsSigOnly() {
        // Hunting a P2P station while not activating ourselves: SIG only.
        let f = potaQsoStamp(activationParkRef: nil, workedParkRef: "K-1234")
        XCTAssertEqual(f.mySig, "")
        XCTAssertEqual(f.mySigInfo, "")
        XCTAssertEqual(f.sig, "POTA")
        XCTAssertEqual(f.sigInfo, "K-1234")
    }

    func testEmptyRefsTreatedAsAbsent() {
        let f = potaQsoStamp(activationParkRef: "", workedParkRef: "   ")
        XCTAssertEqual(f, PotaStampFields())
    }

    func testApplyStampMutatesRecord() {
        var r = QsoRecord(
            call: "W1AW", gridsquare: "FN31", mode: "FT8", rstSent: "-05",
            rstRcvd: "-08", qsoDate: "20260713", timeOn: "123000",
            qsoDateOff: "20260713", timeOff: "123115", band: "20M",
            freq: "14.074", stationCallsign: "KD2OGR", myGridsquare: "FN20",
            comment: "")
        applyPotaStamp(
            potaQsoStamp(activationParkRef: "US-7443", workedParkRef: "K-1234"),
            to: &r)
        XCTAssertEqual(r.mySig, "POTA")
        XCTAssertEqual(r.mySigInfo, "US-7443")
        XCTAssertEqual(r.sig, "POTA")
        XCTAssertEqual(r.sigInfo, "K-1234")
    }

    // MARK: - Park-to-park resolution against the live spots

    func testParkRefResolvesSpottedActivator() {
        let spots = [spot("K1ABC", "K-1234"), spot("JA1XYZ", "JA-0001")]
        XCTAssertEqual(PotaSpots.parkRef(forCallsign: "K1ABC", in: spots), "K-1234")
    }

    func testParkRefNilForUnspottedCallsign() {
        let spots = [spot("K1ABC", "K-1234")]
        XCTAssertNil(PotaSpots.parkRef(forCallsign: "W1AW", in: spots))
    }

    func testParkRefStripsAngleBracketsOnNonStandardCall() {
        // FT8 non-standard/hashed calls come wrapped as "<K1ABC/P>".
        let spots = [spot("K1ABC/P", "K-1234")]
        XCTAssertEqual(PotaSpots.parkRef(forCallsign: "<K1ABC/P>", in: spots), "K-1234")
    }

    func testParkRefCaseInsensitive() {
        let spots = [spot("K1ABC", "K-1234")]
        XCTAssertEqual(PotaSpots.parkRef(forCallsign: "k1abc", in: spots), "K-1234")
    }

    func testParkRefNilForEmptyReference() {
        let spots = [spot("K1ABC", "")]
        XCTAssertNil(PotaSpots.parkRef(forCallsign: "K1ABC", in: spots))
    }

    func testParkRefNilForNilOrEmptyCallsign() {
        let spots = [spot("K1ABC", "K-1234")]
        XCTAssertNil(PotaSpots.parkRef(forCallsign: nil, in: spots))
        XCTAssertNil(PotaSpots.parkRef(forCallsign: "  ", in: spots))
    }
}
