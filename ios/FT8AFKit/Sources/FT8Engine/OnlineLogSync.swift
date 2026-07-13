import Foundation

/// Pure catch-up-sync planning over the QSO log — which records still need an
/// upload to at least one enabled service. Mirrors Android
/// `ThirdPartyService.unsyncedFilter` / `countUnsyncedQSOs` so the badge count
/// and the actual sync always agree.
public enum OnlineLogSync {

    /// Records still awaiting upload to an enabled service, in log order.
    /// Empty when neither service is enabled (nothing to do).
    public static func unsynced(
        _ records: [QsoRecord], cloudlogEnabled: Bool, qrzEnabled: Bool
    ) -> [QsoRecord] {
        guard cloudlogEnabled || qrzEnabled else { return [] }
        return records.filter { r in
            (cloudlogEnabled && !r.syncedCloudlog) || (qrzEnabled && !r.syncedQrz)
        }
    }

    /// Number of records `unsynced` would return.
    public static func unsyncedCount(
        _ records: [QsoRecord], cloudlogEnabled: Bool, qrzEnabled: Bool
    ) -> Int {
        unsynced(records, cloudlogEnabled: cloudlogEnabled, qrzEnabled: qrzEnabled).count
    }
}
