import CFT8
import Foundation

/// Callsign hash table for resolving hashed compound/nonstandard calls (the
/// `<...>` placeholders FT8 carries as 10/12/22-bit *hashes* instead of the full
/// call). ft8_lib resolves them through the `ftx_callsign_hash_interface_t`
/// save/lookup callbacks, which need a store of calls seen earlier.
///
/// Unlike the original per-slot table (ported from desktop dsp/hashtable.rs /
/// ft8af_glue/ft8_decoder.cpp, which cleared every slot), this table is meant to
/// **persist across slots** so a `<...>` in one slot resolves against a full call
/// decoded in an *earlier* slot — matching Android, which keeps the store in a
/// process-wide `static MessageHashMap hashList` (Ft8Message.java) that is never
/// cleared. LiveEngine owns one instance and injects it into every per-slot
/// `FT8Decoder` (a fresh decoder is built each slot), so the store outlives the
/// decoder.
///
/// Android's map is unbounded; on a phone we cap the store at `capacity` entries
/// and evict oldest-first (insertion-order FIFO) on overflow. A session sees far
/// fewer than `capacity` distinct compound calls inside the window where a
/// `<...>` back-reference is still useful, and the oldest calls are the least
/// likely to be referenced again — so the cap bounds memory without losing
/// resolvable hashes in practice.
///
/// Every method takes an internal lock, so one table can be shared safely across
/// concurrent or re-entrant decode passes: the thread-local active-table plumbing
/// below may point several threads at the same instance at once.
public final class HashTable: @unchecked Sendable {
    // @unchecked Sendable: all mutable state is guarded by `lock`, so instances
    // are safe to share across actors/threads (e.g. the engine passing one into
    // each nonisolated per-slot decode).

    /// Max stored callsigns before oldest-first eviction bounds a long-lived
    /// (process-lifetime) table. See the type doc for the rationale.
    static let capacity = 512

    private struct Entry {
        let callsign: String
        let hash22: UInt32   // 22-bit hash, masked to 0x3FFFFF
    }
    // Insertion-ordered: newest appended at the end, oldest (index 0) evicted
    // first once `capacity` is exceeded.
    private var entries: [Entry] = []
    // 22-bit hashes currently stored, for O(1) duplicate rejection.
    private var stored = Set<UInt32>()
    private let lock = NSLock()

    public init() {}

    /// Drop every stored callsign. No longer called per slot (the table now
    /// persists), but kept for tests and explicit resets.
    func clear() {
        lock.lock(); defer { lock.unlock() }
        entries.removeAll(keepingCapacity: true)
        stored.removeAll(keepingCapacity: true)
    }

    /// Mirror of `hash_save` / `MessageHashMap.addHash`: store `callsign` under
    /// its 22-bit hash. Skips empties and the `<...>`-wrapped placeholder form,
    /// dedupes on the 22-bit hash, and evicts the oldest entry once full.
    func save(_ callsign: String, _ n22: UInt32) {
        if callsign.isEmpty || callsign.hasPrefix("<") { return }
        let hash22 = n22 & 0x3F_FFFF
        lock.lock(); defer { lock.unlock() }
        if stored.contains(hash22) { return } // already stored
        entries.append(Entry(callsign: callsign, hash22: hash22))
        stored.insert(hash22)
        if entries.count > HashTable.capacity {
            let evicted = entries.removeFirst()
            stored.remove(evicted.hash22)
        }
    }

    /// Mirror of `hash_lookup`: find a stored call whose 22-bit hash, shifted
    /// right by `shift` (0 / 10 / 12 for the 22/12/10-bit lookups), equals
    /// `hash`. Newest match wins, so a recently re-heard call supersedes a stale
    /// collision on the narrower widths.
    func lookup(shift: UInt8, hash: UInt32) -> String? {
        lock.lock(); defer { lock.unlock() }
        for e in entries.reversed() {
            if (e.hash22 >> shift) == hash {
                return e.callsign
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
