import Foundation
import Security

/// Tiny wrapper over the iOS Keychain for the app's few long-lived secrets —
/// currently just the POTA refresh token (standing account access) and the
/// display email that goes with it. Items are `kSecClassGenericPassword` scoped
/// by `service` + `account`, accessible only after first unlock and never synced
/// off the device (`AfterFirstUnlockThisDeviceOnly`), which keeps the refresh
/// token out of iCloud Keychain and encrypted device backups.
///
/// This is the app's first Keychain use; kept deliberately minimal — three
/// operations, no generics, no caching. Mirrors the *intent* of Android's
/// EncryptedSharedPreferences (Keystore-backed at-rest encryption for the
/// refresh token) without the SharedPreferences surface.
enum KeychainStore {
    /// Namespacing service string. Ties items to this app. Note: unlike
    /// Android's EncryptedSharedPreferences (wiped on uninstall), iOS Keychain
    /// items can survive an app reinstall, so a signed-in session may persist
    /// across reinstalls; `signOut()` is the reliable way to clear it.
    private static let service = "radio.ks3ckc.ft8af.pota"

    /// Store (or replace) a string for `account`. Returns false on any OSStatus
    /// error so callers can decide whether a failed persist is fatal (for the
    /// refresh token it isn't — the user just re-authenticates next time).
    @discardableResult
    static func set(_ value: String, account: String) -> Bool {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        // Delete any existing item first so this is an upsert (SecItemUpdate
        // can't add, and add fails with errSecDuplicateItem if one exists).
        SecItemDelete(query as CFDictionary)

        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }

    /// Read the string for `account`, or nil if absent / unreadable.
    static func get(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Remove the item for `account` (no-op if absent).
    static func remove(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
