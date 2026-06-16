//! Built-in SNTP time sync. FT8 lives or dies by sub-second clock accuracy: if
//! the PC clock is off, every decode shows a large DT and your own transmissions
//! land outside the window. Rather than make the user install Meinberg / Dimension 4
//! / "set time automatically", we query an NTP server ourselves and feed the
//! offset into the engine's `clock_offset_ms` — correcting the app's notion of UTC
//! WITHOUT touching the system clock (so no admin rights, no background service).
//!
//! This is SNTP (RFC 4330): one request/response, no filtering or dispersion
//! tracking — plenty for keeping DT inside ±0.1 s.

use std::net::{ToSocketAddrs, UdpSocket};
use std::sync::mpsc::Sender;
use std::time::Duration;

use crate::engine::EngineCommand;
use crate::util;

/// Seconds between the NTP epoch (1900-01-01) and the Unix epoch (1970-01-01).
const NTP_UNIX_DELTA: i64 = 2_208_988_800;

/// Public NTP pools, tried in order until one answers. Anycast services first
/// (single low-latency hop, no pool DNS spread) with the pool as a fallback.
const SERVERS: &[&str] = &["time.cloudflare.com", "time.google.com", "pool.ntp.org"];

/// Re-sync cadence once we've succeeded. Hourly is far tighter than crystal drift
/// needs, but cheap insurance against a clock that wandered while suspended.
const RESYNC_INTERVAL: Duration = Duration::from_secs(3600);
/// Back-off when every server fails (offline / firewalled UDP 123).
const RETRY_INTERVAL: Duration = Duration::from_secs(60);

/// Query one NTP server and return the local clock's offset from true UTC in
/// milliseconds. Positive means the local clock is *behind* real time, so
/// `true_utc = local + offset` — exactly what the engine adds in `now()`.
pub fn query_offset_ms(server: &str) -> anyhow::Result<i64> {
    let addr = (server, 123u16)
        .to_socket_addrs()?
        .next()
        .ok_or_else(|| anyhow::anyhow!("could not resolve NTP server {server}"))?;

    let sock = UdpSocket::bind("0.0.0.0:0")?;
    sock.set_read_timeout(Some(Duration::from_secs(3)))?;
    sock.set_write_timeout(Some(Duration::from_secs(3)))?;

    // 48-byte client request: LI=0, VN=3, Mode=3 (client); rest zeroed.
    let mut pkt = [0u8; 48];
    pkt[0] = 0x1B;

    let t1 = util::now_unix_ms();
    sock.send_to(&pkt, addr)?;
    let mut buf = [0u8; 48];
    let (n, src) = sock.recv_from(&mut buf)?;
    let t4 = util::now_unix_ms();
    if src.ip() != addr.ip() {
        anyhow::bail!("NTP reply from unexpected source {src}, expected {addr}");
    }
    if n < 48 {
        anyhow::bail!("short NTP reply ({n} bytes)");
    }

    // Server receive timestamp = bytes 32..40, transmit timestamp = 40..48.
    let t2 = ntp_ts_to_unix_ms(&buf[32..40]);
    let t3 = ntp_ts_to_unix_ms(&buf[40..48]);
    if t2 == 0 || t3 == 0 {
        anyhow::bail!("NTP server returned a null timestamp (kiss-of-death?)");
    }

    // RFC 4330 offset: ((t2 - t1) + (t3 - t4)) / 2.
    Ok(((t2 - t1) + (t3 - t4)) / 2)
}

/// Try each server in turn; return the first successful offset.
pub fn query_offset_ms_multi() -> anyhow::Result<i64> {
    let mut last_err = None;
    for srv in SERVERS {
        match query_offset_ms(srv) {
            Ok(off) => return Ok(off),
            Err(e) => last_err = Some(e),
        }
    }
    Err(last_err.unwrap_or_else(|| anyhow::anyhow!("no NTP servers configured")))
}

/// Convert a 64-bit NTP timestamp (32.32 fixed point, big-endian) to Unix millis.
fn ntp_ts_to_unix_ms(b: &[u8]) -> i64 {
    let secs = u32::from_be_bytes([b[0], b[1], b[2], b[3]]) as i64;
    let frac = u32::from_be_bytes([b[4], b[5], b[6], b[7]]) as i64;
    let ms_frac = (frac * 1000) >> 32;
    (secs - NTP_UNIX_DELTA) * 1000 + ms_frac
}

/// Spawn the background sync loop: sync on startup, then every hour (with a short
/// retry back-off while offline). Pushes results into the engine via `SetClockOffset`.
pub fn spawn_loop(tx: Sender<EngineCommand>) {
    std::thread::Builder::new()
        .name("ft8af-timesync".into())
        .spawn(move || loop {
            match query_offset_ms_multi() {
                Ok(off) => {
                    if tx.send(EngineCommand::SetClockOffset(off)).is_err() {
                        return; // engine gone
                    }
                    std::thread::sleep(RESYNC_INTERVAL);
                }
                Err(_) => std::thread::sleep(RETRY_INTERVAL),
            }
        })
        .expect("spawn timesync thread");
}

/// Fire a one-shot sync (for a manual "Resync" button) without blocking the caller.
pub fn spawn_once(tx: Sender<EngineCommand>) {
    std::thread::Builder::new()
        .name("ft8af-timesync-once".into())
        .spawn(move || {
            if let Ok(off) = query_offset_ms_multi() {
                let _ = tx.send(EngineCommand::SetClockOffset(off));
            }
        })
        .expect("spawn one-shot timesync thread");
}
