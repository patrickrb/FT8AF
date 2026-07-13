//! Per-decoder callsign hash table, ported from the `decoder_state` hashtable in
//! `ft8af_glue/ft8_decoder.cpp`. FT8 messages can carry 10/12/22-bit *hashes* of
//! compound/nonstandard callsigns instead of the full call; ft8_lib resolves them
//! through the `ftx_callsign_hash_interface_t` save/lookup callbacks, which need
//! somewhere to stash calls seen earlier in the same slot.
//!
//! The C interface is plain function pointers with no user-data argument, so —
//! exactly like the C++ original's `g_active` thread-local — we point a
//! thread-local at the table that's active for the current `decode_all` call.
//! A `HashGuard` sets and clears it (RAII), and both callbacks `catch_unwind` so
//! a Rust panic can never unwind across the C frame.

use std::cell::Cell;
use std::os::raw::{c_char, c_int};

pub const HASHTABLE_SIZE: usize = 256;

#[derive(Clone, Copy)]
struct Entry {
    callsign: [u8; 12],
    hash: u32, // 22-bit
    used: bool,
}

impl Default for Entry {
    fn default() -> Self {
        Entry { callsign: [0; 12], hash: 0, used: false }
    }
}

pub struct HashTable {
    entries: [Entry; HASHTABLE_SIZE],
}

impl HashTable {
    pub fn new() -> Self {
        HashTable { entries: [Entry::default(); HASHTABLE_SIZE] }
    }

    pub fn clear(&mut self) {
        self.entries = [Entry::default(); HASHTABLE_SIZE];
    }

    /// Mirror of `hash_save` in ft8_decoder.cpp.
    fn save(&mut self, callsign: &str, n22: u32) {
        let bytes = callsign.as_bytes();
        if bytes.is_empty() || bytes[0] == b'<' {
            return;
        }
        let h10 = (n22 >> 12) & 0x3FF;
        let mut idx = (h10 as usize * 23) % HASHTABLE_SIZE;
        // Bound the linear probe to at most one full pass: on a saturated table
        // with no matching entry, drop the insert rather than probing forever —
        // an unbounded loop permanently wedges the decoder thread. Mirrors the
        // C++ `ftx_hash_store_save` and iOS `HashTable.save`; `lookup` already
        // degrades to a graceful miss the same way.
        let mut probes = 0;
        while self.entries[idx].used {
            if self.entries[idx].hash == n22 {
                return; // already stored
            }
            idx = (idx + 1) % HASHTABLE_SIZE;
            probes += 1;
            if probes >= HASHTABLE_SIZE {
                return; // table full: drop, never spin
            }
        }
        let n = bytes.len().min(11);
        let e = &mut self.entries[idx];
        e.callsign = [0; 12];
        e.callsign[..n].copy_from_slice(&bytes[..n]);
        e.hash = n22;
        e.used = true;
    }

    /// Mirror of `hash_lookup` in ft8_decoder.cpp. `shift` selects the hash width.
    fn lookup(&self, shift: u8, hash: u32) -> Option<String> {
        for e in self.entries.iter() {
            if !e.used {
                continue;
            }
            if ((e.hash & 0x3F_FFFF) >> shift) == hash {
                let len = e.callsign.iter().position(|&b| b == 0).unwrap_or(11);
                return Some(String::from_utf8_lossy(&e.callsign[..len]).into_owned());
            }
        }
        None
    }
}

thread_local! {
    static ACTIVE: Cell<*mut HashTable> = const { Cell::new(std::ptr::null_mut()) };
}

/// Sets the thread-local active table for the lifetime of a decode pass.
pub struct HashGuard;

impl HashGuard {
    /// # Safety
    /// `table` must remain valid (not moved/dropped) for the lifetime of the guard.
    pub unsafe fn new(table: *mut HashTable) -> Self {
        ACTIVE.with(|a| a.set(table));
        HashGuard
    }
}

impl Drop for HashGuard {
    fn drop(&mut self) {
        ACTIVE.with(|a| a.set(std::ptr::null_mut()));
    }
}

/// C callback: store a callsign by its 22-bit hash.
pub extern "C" fn save_hash_cb(callsign: *const c_char, n22: u32) {
    let _ = std::panic::catch_unwind(|| {
        let t = ACTIVE.with(|a| a.get());
        if t.is_null() || callsign.is_null() {
            return;
        }
        let cs = unsafe { std::ffi::CStr::from_ptr(callsign) }
            .to_string_lossy()
            .into_owned();
        unsafe { (*t).save(&cs, n22) };
    });
}

/// C callback: look up a callsign by its 10/12/22-bit hash, writing it (NUL-terminated)
/// into `out`. Returns true if found. Matches ft8_decoder.cpp's shift mapping.
pub extern "C" fn lookup_hash_cb(hash_type: c_int, hash: u32, out: *mut c_char) -> bool {
    let found = std::panic::catch_unwind(|| {
        let t = ACTIVE.with(|a| a.get());
        if t.is_null() {
            return None;
        }
        let shift: u8 = match hash_type {
            x if x == super::ffi::FTX_CALLSIGN_HASH_10_BITS => 12,
            x if x == super::ffi::FTX_CALLSIGN_HASH_12_BITS => 10,
            _ => 0,
        };
        unsafe { (*t).lookup(shift, hash) }
    })
    .unwrap_or(None);

    if out.is_null() {
        return false;
    }
    match found {
        Some(call) => {
            let bytes = call.as_bytes();
            let n = bytes.len().min(11);
            unsafe {
                for i in 0..n {
                    *out.add(i) = bytes[i] as c_char;
                }
                *out.add(n) = 0;
            }
            true
        }
        None => {
            unsafe { *out = 0 };
            false
        }
    }
}

/// Build the C hash interface struct wired to our callbacks.
pub fn interface() -> super::ffi::ftx_callsign_hash_interface_t {
    super::ffi::ftx_callsign_hash_interface_t {
        lookup_hash: Some(lookup_hash_cb),
        save_hash: Some(save_hash_cb),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;
    use std::thread;
    use std::time::Duration;

    #[test]
    fn save_and_lookup_round_trip() {
        let mut t = HashTable::new();
        let h = 0x0A_BCDE & 0x3F_FFFF;
        t.save("K1ABC", h);
        assert_eq!(t.lookup(0, h).as_deref(), Some("K1ABC"));
        // A different hash misses.
        assert_eq!(t.lookup(0, (h ^ 1) & 0x3F_FFFF), None);
    }

    #[test]
    fn save_skips_empty_and_placeholder_callsigns() {
        let mut t = HashTable::new();
        t.save("", 1);
        t.save("<...>", 2);
        assert_eq!(t.lookup(0, 1), None);
        assert_eq!(t.lookup(0, 2), None);
    }

    #[test]
    fn save_is_idempotent_on_repeated_hash() {
        // Re-saving the same 22-bit hash must not consume a second slot, so a
        // table that is exactly full still resolves the first insert.
        let mut t = HashTable::new();
        let h = 0x1234;
        t.save("K1ABC", h);
        t.save("K1ABC", h);
        // Fill the remaining 255 slots with distinct hashes → exactly full.
        for i in 0..(HASHTABLE_SIZE as u32 - 1) {
            t.save("FILL", 0x2000 + i);
        }
        assert_eq!(t.lookup(0, h).as_deref(), Some("K1ABC"));
    }

    #[test]
    fn save_on_full_table_drops_without_hanging() {
        // Regression: `save`'s linear probe MUST be bounded. Once all 256 slots
        // are used, a save whose hash matches no entry must DROP the insert, not
        // probe forever — an unbounded loop permanently wedges the decoder thread
        // (RX freezes for the rest of the session). Mirrors the C++
        // ftx_hash_store_save (PR #504) and iOS HashTable.save bounds. Runs on a
        // worker thread guarded by a timeout so the pre-fix infinite loop surfaces
        // as a clean failure instead of hanging the whole test binary.
        let (tx, rx) = mpsc::channel();
        let worker = thread::spawn(move || {
            let mut t = HashTable::new();
            // Fill every slot with a distinct 22-bit hash.
            for i in 0..HASHTABLE_SIZE as u32 {
                t.save("FILL", i);
            }
            // One more distinct hash into the now-full table.
            let extra = HASHTABLE_SIZE as u32 + 12_345;
            t.save("K1ABC", extra);
            let extra_stored = t.lookup(0, extra & 0x3F_FFFF).is_some();
            let existing_survived = t.lookup(0, 0).as_deref() == Some("FILL");
            tx.send((extra_stored, existing_survived)).unwrap();
        });

        // Pre-fix, the worker never returns and this times out (test fails here).
        let (extra_stored, existing_survived) = rx
            .recv_timeout(Duration::from_secs(5))
            .expect("save() on a full table hung — the linear probe is unbounded");
        worker.join().unwrap();

        // A saturated table drops the new insert (like lookup's graceful miss)…
        assert!(!extra_stored, "insert into a full table must be dropped");
        // …without corrupting the entries already stored.
        assert!(existing_survived, "existing entries must survive a full-table save");
    }

    #[test]
    fn clear_resets_a_full_table() {
        // The decoder clears the table each slot; a cleared table must forget
        // every entry and accept fresh inserts again (so it never stays
        // saturated across slots on a long-lived decoder).
        let mut t = HashTable::new();
        for i in 0..HASHTABLE_SIZE as u32 {
            t.save("FILL", i);
        }
        assert!(t.lookup(0, 5).is_some());

        t.clear();

        assert!(t.lookup(0, 5).is_none());
        t.save("K1ABC", 42);
        assert_eq!(t.lookup(0, 42).as_deref(), Some("K1ABC"));
    }
}
