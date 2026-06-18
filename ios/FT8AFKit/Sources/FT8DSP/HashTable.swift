import CFT8
import Foundation

/// Per-decoder callsign hash table, ported from desktop dsp/hashtable.rs (itself
/// from ft8af_glue/ft8_decoder.cpp). FT8 messages can carry 10/12/22-bit *hashes*
/// of compound/nonstandard callsigns instead of the full call; ft8_lib resolves
/// them through the `ftx_callsign_hash_interface_t` save/lookup callbacks, which
/// need somewhere to stash calls seen earlier in the same slot.
final class HashTable {
    static let size = 256

    private struct Entry {
        var callsign = [UInt8](repeating: 0, count: 12)
        var hash: UInt32 = 0   // 22-bit
        var used = false
    }
    private var entries = [Entry](repeating: Entry(), count: HashTable.size)

    func clear() {
        entries = [Entry](repeating: Entry(), count: HashTable.size)
    }

    /// Mirror of `hash_save` in ft8_decoder.cpp.
    func save(_ callsign: String, _ n22: UInt32) {
        let bytes = Array(callsign.utf8)
        if bytes.isEmpty || bytes[0] == UInt8(ascii: "<") { return }
        let h10 = (n22 >> 12) & 0x3FF
        var idx = (Int(h10) * 23) % HashTable.size
        var probes = 0
        while entries[idx].used {
            if entries[idx].hash == n22 { return } // already stored
            idx = (idx + 1) % HashTable.size
            probes += 1
            if probes >= HashTable.size { return } // table full: drop, never spin
        }
        let n = min(bytes.count, 11)
        var cs = [UInt8](repeating: 0, count: 12)
        cs.replaceSubrange(0..<n, with: bytes[0..<n])
        entries[idx].callsign = cs
        entries[idx].hash = n22
        entries[idx].used = true
    }

    /// Mirror of `hash_lookup`. `shift` selects the hash width (0 / 10 / 12).
    func lookup(shift: UInt8, hash: UInt32) -> String? {
        for e in entries where e.used {
            if ((e.hash & 0x3F_FFFF) >> shift) == hash {
                let len = e.callsign.firstIndex(of: 0) ?? 11
                return String(decoding: e.callsign[0..<len], as: UTF8.self)
            }
        }
        return nil
    }
}

// The C hash interface is plain function pointers with no user-data arg, so the
// callbacks route through the table active for the current decode pass. That
// table is stored **thread-local** (matching desktop hashtable.rs's thread_local)
// so concurrent decoders on different threads — or a re-entrant decode — never
// share or clobber each other's active table. The @convention(c) callbacks can't
// capture context, so they read it back off the current thread.
// `installActiveHashTable` / `clearActiveHashTable` bracket a pass (RAII-style
// via `defer` in decodeAll).
private let activeHashTableThreadKey = "radio.ks3ckc.ft8af.activeHashTable"

func installActiveHashTable(_ table: HashTable) {
    Thread.current.threadDictionary[activeHashTableThreadKey] = table
}
func clearActiveHashTable() {
    Thread.current.threadDictionary.removeObject(forKey: activeHashTableThreadKey)
}
private func activeHashTable() -> HashTable? {
    Thread.current.threadDictionary[activeHashTableThreadKey] as? HashTable
}

/// C callback: store a callsign by its 22-bit hash.
let saveHashCallback: @convention(c) (UnsafePointer<CChar>?, UInt32) -> Void = { callsign, n22 in
    guard let table = activeHashTable(), let callsign = callsign else { return }
    table.save(String(cString: callsign), n22)
}

/// C callback: look up a callsign by its 10/12/22-bit hash, writing it
/// (NUL-terminated) into `out`. Returns true if found. Shift mapping matches
/// ft8_decoder.cpp / hashtable.rs.
let lookupHashCallback: @convention(c) (ftx_callsign_hash_type_e, UInt32, UnsafeMutablePointer<CChar>?) -> Bool = { hashType, hash, out in
    guard let out = out else { return false }
    let shift: UInt8
    switch hashType {
    case FTX_CALLSIGN_HASH_10_BITS: shift = 12
    case FTX_CALLSIGN_HASH_12_BITS: shift = 10
    default: shift = 0
    }
    if let table = activeHashTable(), let call = table.lookup(shift: shift, hash: hash) {
        let bytes = Array(call.utf8)
        let n = min(bytes.count, 11)
        for i in 0..<n { out[i] = CChar(bitPattern: bytes[i]) }
        out[n] = 0
        return true
    }
    out[0] = 0
    return false
}

/// Build the C hash interface struct wired to our callbacks.
func makeHashInterface() -> ftx_callsign_hash_interface_t {
    ftx_callsign_hash_interface_t(lookup_hash: lookupHashCallback, save_hash: saveHashCallback)
}
