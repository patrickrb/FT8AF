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
        loop {
            if !self.entries[idx].used {
                break;
            }
            if self.entries[idx].hash == n22 {
                return; // already stored
            }
            idx = (idx + 1) % HASHTABLE_SIZE;
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
