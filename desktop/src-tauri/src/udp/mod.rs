//! WSJT-X UDP transport: owns the socket, sends outbound datagrams built by
//! [`codec`], and (when "accept requests" is on) runs a background listener that
//! turns inbound requests into [`EngineCommand`]s.
//!
//! All state changes triggered by inbound datagrams are funnelled back through
//! the engine's command channel and handled on the engine thread, so the QSO
//! state machine is never touched from the listener thread — no locking, same
//! ordering guarantees as an operator clicking in the UI.

pub mod codec;

use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, ToSocketAddrs, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::engine::{AnswerArgs, EngineCommand};

/// How many recent decodes to retain for an inbound Replay request. Roughly a
/// few band-sessions of traffic; bounded so a long run can't grow unboundedly.
const REPLAY_CACHE_CAP: usize = 1000;

/// User-facing UDP settings (persisted as `udp_*` config keys, edited in the
/// desktop Settings screen). Mirrors WSJT-X's Reporting → UDP options.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UdpConfig {
    /// Master on/off for the whole feature.
    pub enabled: bool,
    /// Destination address for outbound datagrams (unicast, broadcast, or an
    /// IPv4 multicast group). WSJT-X default is loopback.
    pub host: String,
    /// Destination port. WSJT-X default is 2237.
    pub port: u16,
    /// Bind a listener and act on inbound Reply/Halt/Free-Text/Replay requests.
    pub accept_requests: bool,
}

impl Default for UdpConfig {
    fn default() -> Self {
        UdpConfig {
            enabled: false,
            host: "127.0.0.1".to_string(),
            port: 2237,
            accept_requests: false,
        }
    }
}

/// Owns the send socket + listener lifecycle. Held by the engine on its thread.
pub struct UdpService {
    cfg: UdpConfig,
    /// Sender for outbound datagrams. `None` when disabled or a bind failed.
    sock: Option<UdpSocket>,
    target: Option<SocketAddr>,
    listener: Option<Listener>,
    /// Cloned to hand each spawned listener so inbound requests reach the engine.
    cmd_tx: Sender<EngineCommand>,
    /// This session's decodes, for the Replay request.
    replay_cache: Vec<codec::Decode>,
}

struct Listener {
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl Drop for Listener {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        // The recv loop uses a read timeout, so it observes the flag promptly.
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

impl UdpService {
    /// Create in the disabled state. Call [`apply`](Self::apply) with the
    /// persisted config to actually bind.
    pub fn new(cmd_tx: Sender<EngineCommand>) -> Self {
        UdpService {
            cfg: UdpConfig { enabled: false, ..UdpConfig::default() },
            sock: None,
            target: None,
            listener: None,
            cmd_tx,
            replay_cache: Vec::new(),
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.cfg.enabled && self.sock.is_some()
    }

    pub fn config(&self) -> &UdpConfig {
        &self.cfg
    }

    /// The local address the send/receive socket is bound to, if enabled. Lets
    /// callers (and tests) learn the ephemeral port companion apps reply to.
    pub fn local_addr(&self) -> Option<SocketAddr> {
        self.sock.as_ref().and_then(|s| s.local_addr().ok())
    }

    /// Reconfigure to `cfg`: tears down any existing socket/listener and rebinds
    /// as needed. Returns `Ok(human summary)` for the status line, or `Err` if
    /// binding failed (the service ends up disabled).
    pub fn apply(&mut self, cfg: UdpConfig) -> Result<String, String> {
        // Always drop the old listener + socket first (Listener::drop joins).
        self.listener = None;
        self.sock = None;
        self.target = None;
        self.cfg = cfg.clone();

        if !cfg.enabled {
            return Ok("WSJT-X UDP off".into());
        }

        // Reject a zero port: it can never deliver, but would otherwise leave the service
        // "enabled" (sock bound to an ephemeral port) yet silently dropping every send.
        if cfg.port == 0 {
            return Err("WSJT-X UDP port must be non-zero".into());
        }

        let target = (cfg.host.as_str(), cfg.port)
            .to_socket_addrs()
            .map_err(|e| format!("bad UDP host '{}': {e}", cfg.host))?
            .next()
            .ok_or_else(|| format!("could not resolve UDP host '{}'", cfg.host))?;

        // Bind the same address family as the resolved target. A dual-stack name like
        // "localhost" can resolve to IPv6 (::1) first, and an IPv4-only 0.0.0.0 socket
        // can't send to an IPv6 destination — send_to would fail at runtime.
        let bind_addr: SocketAddr = match target {
            SocketAddr::V4(_) => (Ipv4Addr::UNSPECIFIED, 0).into(),
            SocketAddr::V6(_) => (Ipv6Addr::UNSPECIFIED, 0).into(),
        };
        let sock = UdpSocket::bind(bind_addr).map_err(|e| format!("UDP bind failed: {e}"))?;
        // Allow a broadcast destination (e.g. x.x.x.255); harmless otherwise.
        let _ = sock.set_broadcast(true);
        // For a multicast target, join the group so replies on it reach us.
        if let SocketAddr::V4(v4) = target {
            if v4.ip().is_multicast() {
                let _ = sock.join_multicast_v4(v4.ip(), &Ipv4Addr::UNSPECIFIED);
            }
        }

        if cfg.accept_requests {
            self.listener = Some(spawn_listener(&sock, self.cmd_tx.clone())?);
        }

        self.sock = Some(sock);
        self.target = Some(target);
        let listening = if cfg.accept_requests { " (accepting requests)" } else { "" };
        Ok(format!("WSJT-X UDP → {}:{}{listening}", cfg.host, cfg.port))
    }

    /// Send a pre-built datagram to the configured target. No-op when disabled;
    /// send errors are swallowed (UDP is best-effort and a dropped spot must not
    /// disturb the engine loop).
    pub fn send(&self, datagram: &[u8]) {
        if let (Some(sock), Some(target)) = (&self.sock, &self.target) {
            let _ = sock.send_to(datagram, target);
        }
    }

    /// Send a decode (caching it for Replay). Cheap no-op when disabled.
    pub fn send_decode(&mut self, d: codec::Decode) {
        if !self.is_enabled() {
            return;
        }
        self.send(&codec::decode(&d));
        if self.replay_cache.len() >= REPLAY_CACHE_CAP {
            self.replay_cache.remove(0);
        }
        self.replay_cache.push(d);
    }

    /// Re-broadcast this session's cached decodes (marked not-new), answering an
    /// inbound Replay request.
    pub fn replay(&self) {
        if !self.is_enabled() {
            return;
        }
        for d in &self.replay_cache {
            let mut d = d.clone();
            d.is_new = false;
            self.send(&codec::decode(&d));
        }
    }

    /// Clear the replay cache (call at band change / decode clear).
    pub fn clear_replay_cache(&mut self) {
        self.replay_cache.clear();
    }
}

/// Spawn the inbound listener: a blocking `recv_from` loop (short read timeout so
/// it notices the stop flag) that parses requests and forwards them as commands.
fn spawn_listener(sock: &UdpSocket, cmd_tx: Sender<EngineCommand>) -> Result<Listener, String> {
    let rx = sock
        .try_clone()
        .map_err(|e| format!("UDP listener clone failed: {e}"))?;
    rx.set_read_timeout(Some(Duration::from_millis(500)))
        .map_err(|e| format!("UDP listener timeout failed: {e}"))?;
    let stop = Arc::new(AtomicBool::new(false));
    let stop_thread = stop.clone();
    let handle = std::thread::Builder::new()
        .name("ft8af-udp-rx".into())
        .spawn(move || {
            let mut buf = [0u8; 2048];
            while !stop_thread.load(Ordering::Relaxed) {
                match rx.recv_from(&mut buf) {
                    Ok((n, _src)) => {
                        if let Some(cmd) = to_command(&buf[..n]) {
                            if cmd_tx.send(cmd).is_err() {
                                return; // engine gone
                            }
                        }
                    }
                    // Timeout (WouldBlock/TimedOut) — loop to re-check the flag.
                    Err(ref e)
                        if e.kind() == std::io::ErrorKind::WouldBlock
                            || e.kind() == std::io::ErrorKind::TimedOut => {}
                    Err(_) => return, // socket closed / fatal
                }
            }
        })
        .map_err(|e| format!("spawn UDP listener failed: {e}"))?;
    Ok(Listener { stop, handle: Some(handle) })
}

/// Translate a parsed inbound request into an engine command, reusing the
/// existing operator-facing commands. `None` for requests we ignore.
fn to_command(buf: &[u8]) -> Option<EngineCommand> {
    match codec::parse_inbound(buf)? {
        codec::Inbound::Reply { call, grid, snr, delta_freq } => Some(EngineCommand::Answer(
            AnswerArgs { call_from: call, grid, snr, delta_freq },
        )),
        // We only support a global halt; `auto_only` doesn't map to a separate
        // manual-TX path here, so any halt request stops TX.
        codec::Inbound::HaltTx { .. } => Some(EngineCommand::StopTx),
        // Load-without-send has no engine path (FreeText transmits), so only act
        // when the request asks to send.
        codec::Inbound::FreeText { text, send } if send => Some(EngineCommand::FreeText(text)),
        codec::Inbound::FreeText { .. } => None,
        codec::Inbound::Replay => Some(EngineCommand::UdpReplay),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config_matches_wsjtx() {
        let c = UdpConfig::default();
        assert_eq!(c.host, "127.0.0.1");
        assert_eq!(c.port, 2237);
        assert!(!c.enabled);
        assert!(!c.accept_requests);
    }

    fn reply_datagram(msg: &str) -> Vec<u8> {
        // Mirror codec's inbound Reply layout via a tiny hand-built buffer.
        let mut b = Vec::new();
        b.extend_from_slice(&codec::MAGIC.to_be_bytes());
        b.extend_from_slice(&codec::SCHEMA.to_be_bytes());
        b.extend_from_slice(&codec::T_REPLY.to_be_bytes());
        b.extend_from_slice(&(codec::CLIENT_ID.len() as u32).to_be_bytes());
        b.extend_from_slice(codec::CLIENT_ID.as_bytes());
        b.extend_from_slice(&0u32.to_be_bytes()); // time
        b.extend_from_slice(&(-3i32).to_be_bytes()); // snr
        b.extend_from_slice(&0.1f64.to_be_bytes()); // dt
        b.extend_from_slice(&1500u32.to_be_bytes()); // df
        b.extend_from_slice(&3u32.to_be_bytes()); // mode len
        b.extend_from_slice(b"FT8");
        b.extend_from_slice(&(msg.len() as u32).to_be_bytes());
        b.extend_from_slice(msg.as_bytes());
        b.push(0); // low confidence
        b
    }

    #[test]
    fn reply_maps_to_answer_command() {
        let cmd = to_command(&reply_datagram("CQ K1ABC FN42")).unwrap();
        match cmd {
            EngineCommand::Answer(a) => {
                assert_eq!(a.call_from, "K1ABC");
                assert_eq!(a.grid, "FN42");
                assert_eq!(a.snr, -3);
                // The requested audio tone (df) rides through to the engine so it
                // can key up on it rather than the current TX offset.
                assert_eq!(a.delta_freq, 1500);
            }
            other => panic!("expected Answer, got {other:?}"),
        }
    }

    #[test]
    fn unparseable_datagram_yields_no_command() {
        assert!(to_command(&[0xDE, 0xAD, 0xBE, 0xEF]).is_none());
    }

    #[test]
    fn disabled_service_send_is_noop() {
        let (tx, _rx) = std::sync::mpsc::channel();
        let svc = UdpService::new(tx);
        // No socket bound: send must not panic.
        svc.send(b"anything");
        assert!(!svc.is_enabled());
    }

    #[test]
    fn apply_disabled_config_ok() {
        let (tx, _rx) = std::sync::mpsc::channel();
        let mut svc = UdpService::new(tx);
        let msg = svc.apply(UdpConfig::default()).unwrap();
        assert!(msg.contains("off"));
        assert!(!svc.is_enabled());
    }

    #[test]
    fn apply_enabled_zero_port_is_rejected_and_disabled() {
        // Port 0 can never deliver; apply() must error and leave the service disabled
        // rather than binding an ephemeral port that silently drops every send.
        let (tx, _rx) = std::sync::mpsc::channel();
        let mut svc = UdpService::new(tx);
        let err = svc
            .apply(UdpConfig {
                enabled: true,
                host: "127.0.0.1".into(),
                port: 0,
                accept_requests: false,
            })
            .unwrap_err();
        assert!(err.contains("non-zero"));
        assert!(!svc.is_enabled());
    }

    #[test]
    fn apply_enabled_binds_and_sends_to_loopback() {
        // Bind a real receiver on an OS-assigned port, point the service at it,
        // and confirm a datagram arrives — exercises the full bind+send path.
        let receiver = UdpSocket::bind("127.0.0.1:0").unwrap();
        receiver.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let port = receiver.local_addr().unwrap().port();

        let (tx, _rx) = std::sync::mpsc::channel();
        let mut svc = UdpService::new(tx);
        svc.apply(UdpConfig {
            enabled: true,
            host: "127.0.0.1".into(),
            port,
            accept_requests: false,
        })
        .unwrap();
        assert!(svc.is_enabled());

        svc.send(&codec::clear());
        let mut buf = [0u8; 64];
        let (n, _src) = receiver.recv_from(&mut buf).expect("datagram should arrive");
        assert_eq!(&buf[..4], &codec::MAGIC.to_be_bytes());
        assert!(n >= 16);
    }

    #[test]
    fn listener_delivers_inbound_reply_as_command() {
        // Full inbound path: bind with accept_requests, fire a Reply datagram at
        // the bound port from a throwaway socket, and confirm the listener thread
        // turns it into an Answer command on the engine channel.
        let (tx, rx) = std::sync::mpsc::channel();
        let mut svc = UdpService::new(tx);
        svc.apply(UdpConfig {
            enabled: true,
            host: "127.0.0.1".into(),
            // Any valid non-zero target; the socket still binds an ephemeral local port and
            // we drive the listener directly via local_addr() below, so the target is unused.
            port: 2237,
            accept_requests: true,
        })
        .unwrap();
        // The socket binds 0.0.0.0:<port>; target loopback with that port.
        let port = svc.local_addr().expect("bound").port();
        let dest = SocketAddr::from(([127, 0, 0, 1], port));

        let sender = UdpSocket::bind("127.0.0.1:0").unwrap();
        sender.send_to(&reply_datagram("CQ K1ABC FN42"), dest).unwrap();

        let cmd = rx.recv_timeout(Duration::from_secs(2)).expect("command should arrive");
        match cmd {
            EngineCommand::Answer(a) => {
                assert_eq!(a.call_from, "K1ABC");
                assert_eq!(a.grid, "FN42");
            }
            other => panic!("expected Answer, got {other:?}"),
        }
        // Dropping the service joins the listener thread cleanly.
        drop(svc);
    }

    #[test]
    fn replay_resends_cached_decodes_as_not_new() {
        let receiver = UdpSocket::bind("127.0.0.1:0").unwrap();
        receiver.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let port = receiver.local_addr().unwrap().port();

        let (tx, _rx) = std::sync::mpsc::channel();
        let mut svc = UdpService::new(tx);
        svc.apply(UdpConfig { enabled: true, host: "127.0.0.1".into(), port, accept_requests: false })
            .unwrap();

        let d = codec::Decode {
            is_new: true,
            time_ms: 0,
            snr: -5,
            delta_time: 0.0,
            delta_freq: 1500,
            mode: "FT8".into(),
            message: "CQ K1ABC FN42".into(),
            low_confidence: false,
            off_air: false,
        };
        svc.send_decode(d);
        let mut buf = [0u8; 256];
        let (n1, _) = receiver.recv_from(&mut buf).unwrap(); // the live decode
        let live = buf[..n1].to_vec();

        svc.replay();
        let (n2, _) = receiver.recv_from(&mut buf).unwrap(); // the replayed copy
        let replayed = &buf[..n2];

        // Same length; only the is_new flag (first payload byte after the id)
        // differs — true in the live copy, false in the replay.
        assert_eq!(live.len(), replayed.len());
        // is_new sits at offset 12 (magic4+schema4+type4) + 4 (id len) + 5 ("FT8AF").
        let flag_off = 12 + 4 + codec::CLIENT_ID.len();
        assert_eq!(live[flag_off], 1);
        assert_eq!(replayed[flag_off], 0);
    }
}
