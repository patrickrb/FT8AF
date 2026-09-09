import Foundation

/// POTA ADIF fields stamped onto a logged QSO. Port of the four columns Android's
/// `PotaSessionManager.stampQso` writes onto a `QSLRecord`: MY_SIG/MY_SIG_INFO
/// identify the contact as part of *our* activation; SIG/SIG_INFO record the
/// worked station's park for a park-to-park (P2P) contact. Empty strings mean
/// "don't emit" (the ADIF writer skips empty fields).
public struct PotaStampFields: Equatable, Sendable {
    public var mySig: String
    public var mySigInfo: String
    public var sig: String
    public var sigInfo: String

    public init(
        mySig: String = "", mySigInfo: String = "",
        sig: String = "", sigInfo: String = ""
    ) {
        self.mySig = mySig
        self.mySigInfo = mySigInfo
        self.sig = sig
        self.sigInfo = sigInfo
    }
}

/// The `MY_SIG`/`SIG` program value POTA expects.
public let potaSigProgram = "POTA"

/// Pure POTA stamping decision — port of Android `PotaSessionManager.stampQso`.
///
/// - `activationParkRef`: the active activation's (possibly comma-separated
///   two-fer) park ref, or nil when no activation is running. When present it
///   stamps MY_SIG=POTA / MY_SIG_INFO=<ref> so the export identifies the QSO as
///   part of our activation.
/// - `workedParkRef`: the worked station's park reference when they are a
///   currently-spotted POTA activator (resolve via `PotaSpots.parkRef`), or nil
///   otherwise. When present it stamps SIG=POTA / SIG_INFO=<ref> for P2P.
///
/// The two are independent: a QSO can be neither, either, or both (a P2P logged
/// during our own activation carries all four fields).
public func potaQsoStamp(
    activationParkRef: String?, workedParkRef: String?
) -> PotaStampFields {
    var fields = PotaStampFields()
    if let mine = activationParkRef?.trimmingCharacters(in: .whitespaces),
       !mine.isEmpty {
        fields.mySig = potaSigProgram
        fields.mySigInfo = mine
    }
    if let theirs = workedParkRef?.trimmingCharacters(in: .whitespaces),
       !theirs.isEmpty {
        fields.sig = potaSigProgram
        fields.sigInfo = theirs
    }
    return fields
}

/// Apply POTA stamp fields onto a QSO record in place — mirrors the mutating
/// half of Android's `stampQso(record, spottedParkRef)`. Keeps the caller
/// (LiveEngine's log path) a thin wire between the pure decision and the record.
public func applyPotaStamp(_ fields: PotaStampFields, to record: inout QsoRecord) {
    record.mySig = fields.mySig
    record.mySigInfo = fields.mySigInfo
    record.sig = fields.sig
    record.sigInfo = fields.sigInfo
}
