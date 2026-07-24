//! The runtime engine: a dedicated thread that owns audio + decoder + QSO state
//! machine + rig, runs the 15 s UTC cycle (ports `UtcTimer`), and turns QSO
//! decisions into encode + PTT + playback + DB writes. Communicates with the
//! Tauri layer over command/event channels so the cpal `Stream`s (which are
//! `!Send` on Windows) never leave this thread.

use std::sync::mpsc::{Receiver, Sender, TryRecvError};
use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::audio::{output, AudioInput, SlotAccumulator};
use crate::bands;
use crate::db::{Db, QsoRecord};
use crate::dsp::{DecodedMessage, Decoder, SAMPLE_RATE};
use crate::qso::{QsoEngine, QsoOutcome, QsoStatus};
use crate::rig::{RigConfig, RigConnection};
use crate::util;
use crate::wf::{WfConfig, WfProcessor, WfWindow};

const CYCLE_MS: i64 = 15_000;
const TX_SLACK_MS: i64 = 2_360; // 12.64 s audio in a 15 s slot
const PTT_DELAY_MS: u64 = 100;
// Latest point into the cycle we can still key up and have the full 12.64 s
// waveform (plus the PTT lead) land before the next slot boundary. Start later
// than this and the leading Costas array gets clipped — audible but undecodable.
const TX_LATEST_MS: i64 = TX_SLACK_MS - PTT_DELAY_MS as i64;
// Point in the (rx-corrected) cycle at which we hand the slot to the decoder.
// The FT8 waveform is complete at 12.64 s, so decoding ~0.6 s later returns
// results around the 15 s boundary even on a slow CPU — early enough that the
// operator's reply lands in the very next slot instead of skipping a cycle.
// The rx-offset calibration aligns this clock to the real signal timing, and
// any positive capture latency only pushes the real trigger later (safer), so
// 13.2 s keeps a comfortable margin above the 12.64 s waveform end.
const DECODE_AT_MS: i64 = 13_200;
const DEFAULT_TX_AUDIO_HZ: i32 = 1_500;
// Usable TX audio-offset passband (Hz). Real FT8 audio tones live well inside
// this; both the base-freq setter and an inbound WSJT-X Reply's requested `df`
// validate against it.
const MIN_TX_AUDIO_HZ: i32 = 200;
const MAX_TX_AUDIO_HZ: i32 = 3_000;
// Default TX output level (0.0–1.0). Slightly below full scale so a fresh
// install doesn't overdrive the soundcard/ALC before the operator sets it.
const DEFAULT_TX_GAIN: f32 = 0.9;

/// Clamp a requested TX gain into the valid 0.0–1.0 range (full scale).
fn clamp_tx_gain(g: f32) -> f32 {
    g.clamp(0.0, 1.0)
}

/// The TX audio offset (Hz) to adopt for an inbound WSJT-X Reply's requested
/// `df`, or `None` to keep the current offset. Bounds the untrusted UDP value to
/// the usable audio passband; a `0` (unspecified) or out-of-band `df` is ignored
/// so a malformed/garbled datagram can't push the TX tone off the band. Mirrors
/// the Android/iOS ports, which likewise honor a plausible `df` and drop the rest.
fn reply_tx_audio_hz(delta_freq: u32) -> Option<i32> {
    let df = i32::try_from(delta_freq).ok()?;
    (MIN_TX_AUDIO_HZ..=MAX_TX_AUDIO_HZ).contains(&df).then_some(df)
}

/// The core keying gate `maybe_transmit` enforces, factored out so it is pure and
/// unit-testable and shared with the run loop's boundary trigger. We may key iff:
///   * a QSO is active,
///   * a locked TX parity (set when answering a CQ) matches this slot's parity —
///     `None` leaves us eligible in any slot and locks on the first transmission,
///   * we have not already transmitted in this slot, and
///   * we are early enough in the cycle to fit the full 12.64 s waveform before the
///     next boundary (`into_cycle_ms <= TX_LATEST_MS`); starting later clips the
///     leading Costas array — audible but undecodable.
fn tx_slot_eligible(
    active: bool,
    tx_parity: Option<i64>,
    slot_id: i64,
    txed_slot: i64,
    into_cycle_ms: i64,
) -> bool {
    if !active {
        return false;
    }
    if let Some(p) = tx_parity {
        if p != slot_id.rem_euclid(2) {
            return false;
        }
    }
    if txed_slot == slot_id {
        return false;
    }
    into_cycle_ms <= TX_LATEST_MS
}

/// Whether any decode batch is still outstanding, gating the boundary TX trigger.
/// Derived from the pending-decode *count* (not a single in-flight bool) so the
/// gate stays closed until every enqueued slot has returned — see
/// [`Engine::pending_decodes`].
fn awaiting_any_decode(pending_decodes: usize) -> bool {
    pending_decodes > 0
}

/// Whether the run loop should key a queued transmission at this tick. Unlike an
/// operator command (which keys immediately), the per-tick boundary trigger waits
/// until the current slot's decodes have been processed (`!awaiting_decode`) so it
/// never keys a stale message ahead of a slow decode; once they are in,
/// `handle_decoded` has set the fresh `tx_message` this fires on. This is what lets
/// a reply computed early in the previous cycle (fast CPU) still go out early in
/// its slot — `maybe_transmit` is otherwise only reached on decode arrival, which
/// on a quick decode lands mid-slot, past the TX window, dropping the reply.
///
/// "Early in its slot" is the whole TX window, not a hard boundary: the gate only
/// requires `into_cycle_ms <= TX_LATEST_MS` (via [`tx_slot_eligible`]). In practice
/// the run loop ticks fast, so the first eligible tick after the decodes settle is
/// near the top of the slot — but a reply that becomes eligible a little later in
/// the window is still keyed rather than dropped.
///
/// The trigger is part of the decode-driven RX/TX cycle, so it is dormant while the
/// decoder is stopped (`decoding == false`) — matching the sibling per-tick actions
/// (the decode trigger and the waterfall row, both already gated on `self.decoding`).
/// Without this a QSO/CQ left `active` when the operator stops decoding would keep
/// auto-keying the rig every eligible slot with the receiver off — the radio would
/// transmit unattended, since `pending_decodes` drops to 0 on `StopDecode` so the
/// awaiting gate no longer holds it back.
fn boundary_tx_ready(
    decoding: bool,
    active: bool,
    awaiting_decode: bool,
    tx_parity: Option<i64>,
    slot_id: i64,
    txed_slot: i64,
    into_cycle_ms: i64,
) -> bool {
    decoding
        && !awaiting_decode
        && tx_slot_eligible(active, tx_parity, slot_id, txed_slot, into_cycle_ms)
}
// Live-waterfall FFT parameters (window/size/averaging + display constants)
// live in `crate::wf` and are runtime-configurable via SetWaterfallConfig.
// Input RMS at/below this (dBFS) counts as silence — no audio reaching the app.
// Real RX audio through a soundcard/DAX sits well above this even on a quiet band;
// a fully-routed-but-silent virtual device floors near -100 dBFS.
const SILENCE_DBFS: f32 = -75.0;

/// RMS level of a mono buffer in dBFS (0 dB = full scale). Empty or all-zero
/// input returns a large negative number rather than -inf, so the meter and the
/// silence test stay finite.
fn rms_dbfs(samples: &[f32]) -> f32 {
    if samples.is_empty() {
        return -120.0;
    }
    let sum_sq: f64 = samples.iter().map(|&s| (s as f64) * (s as f64)).sum();
    let rms = (sum_sq / samples.len() as f64).sqrt();
    (20.0 * (rms.max(1e-12)).log10()).max(-120.0) as f32
}

/// True once per cycle, on the first waterfall row at/after a 15 s boundary on
/// the rx-corrected clock, advancing `last_slot`. The live waterfall draws audio
/// on real time while the decoder measures DT on the rx-corrected clock (shifted
/// by capture latency), so the spectrum reads ahead of the DT; marking the cycle
/// grid on the corrected clock gives both the same time reference.
fn wf_boundary_row(corrected_now_ms: i64, last_slot: &mut i64) -> bool {
    let slot = corrected_now_ms.div_euclid(CYCLE_MS);
    if slot != *last_slot {
        *last_slot = slot;
        true
    } else {
        false
    }
}

/// The on-air text of a decode: the raw decoded line if we have it, else a
/// reconstruction from the parsed fields. Shared by the UI publish and the
/// WSJT-X UDP Decode broadcast so both show identical message text.
fn decode_text(m: &DecodedMessage) -> String {
    if m.raw_text.is_empty() {
        format!("{} {} {}", m.call_to, m.call_from, m.extra)
    } else {
        m.raw_text.clone()
    }
}

// --- messages crossing the channel boundary --------------------------------

#[derive(Debug, Clone, Deserialize)]
pub struct AnswerArgs {
    pub call_from: String,
    #[serde(default)]
    pub grid: String,
    #[serde(default)]
    pub snr: i32,
    /// Audio offset (Hz) to answer on — WSJT-X's `df`, set by an inbound UDP
    /// Reply request. `0` (the default, and what the desktop UI's own "click to
    /// answer" sends) means "keep the current TX offset".
    #[serde(default)]
    pub delta_freq: u32,
}

#[derive(Debug, Clone)]
pub enum EngineCommand {
    StartDecode,
    StopDecode,
    SetStation { call: String, grid: String },
    SetBand(u64),
    SetBaseFreq(i32),
    /// TX output level, 0.0–1.0 (drive into the soundcard/USB audio path).
    SetTxGain(f32),
    SetInputDevice(Option<String>),
    SetOutputDevice(Option<String>),
    SelectRig(RigConfig),
    /// Drop the live rig connection for this session (CAT/PTT released). Leaves
    /// the saved rig config intact so Connect — or the next launch — reconnects.
    DisconnectRig,
    StartCq,
    Answer(AnswerArgs),
    /// Operator manually selects which QSO message to transmit next.
    SetStage(crate::qso::TxStage),
    StopTx,
    FreeText(String),
    /// Re-emit the current rig + TX status. Lets a freshly-loaded UI sync up with
    /// state set before its event listener existed (e.g. the startup auto-reconnect).
    RefreshStatus,
    /// Apply a fresh NTP clock offset (ms to add to local time to get true UTC).
    SetClockOffset(i64),
    /// Kick off an immediate one-shot NTP re-sync (manual "Resync" button).
    ResyncTime,
    /// Apply new live-waterfall FFT parameters (developer knobs, issue #428).
    SetWaterfallConfig(WfConfig),
    /// Apply new WSJT-X UDP settings (enable/host/port/accept-requests). Rebinds
    /// the socket + listener live so the Settings screen takes effect at once.
    SetUdpConfig(crate::udp::UdpConfig),
    /// Re-broadcast this session's decodes over UDP (inbound WSJT-X Replay).
    UdpReplay,
    Shutdown,
}

#[derive(Debug, Clone, Serialize)]
pub struct UiMessage {
    pub utc_ms: i64,
    pub call_to: String,
    pub call_from: String,
    pub extra: String,
    pub grid: String,
    pub snr: i32,
    pub freq_hz: f32,
    pub time_sec: f32,
    pub text: String,
    pub is_cq: bool,
    pub to_me: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct CycleTick {
    pub utc_ms: i64,
    pub sequential: i64,
    pub ms_into_cycle: i64,
}

#[derive(Debug, Clone, Serialize)]
pub struct TxStateEvent {
    pub transmitting: bool,
    pub message: Option<String>,
    pub status: QsoStatus,
}

#[derive(Debug, Clone, Serialize)]
pub struct RigStatusEvent {
    pub connected: bool,
    pub model: String,
    pub frequency_hz: u64,
    pub ptt: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct ClockSyncEvent {
    /// ms added to the local clock to get true UTC (negative = local is fast).
    pub offset_ms: i64,
    /// true once an NTP sync has succeeded this session (or a saved offset was restored).
    pub synced: bool,
    /// Auto-calibrated audio-capture latency compensation (ms) applied to the RX window.
    pub rx_offset_ms: i64,
}

#[derive(Debug, Clone, Serialize)]
pub struct WaterfallFrame {
    /// Magnitude bins (0..=255) for the displayed audio band of one slot,
    /// downsampled server-side. `cols` bins per row, `rows` time rows.
    pub bins: Vec<u8>,
    pub rows: usize,
    pub cols: usize,
    pub hz_per_col: f32,
}

/// A single live spectrum row (emitted ~10×/s) for the scrolling waterfall.
#[derive(Debug, Clone, Serialize)]
pub struct WaterfallRow {
    pub bins: Vec<u8>,
    pub hz_per_col: f32,
    /// True on the first row at/after a 15 s cycle boundary (on the rx-corrected
    /// clock the decoder measures DT against). The UI draws a grid line there so
    /// the spectrum lines up with decode DT instead of reading ahead of it.
    pub boundary: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", content = "data", rename_all = "snake_case")]
pub enum EngineEvent {
    Decoded(Vec<UiMessage>),
    Cycle(CycleTick),
    TxState(TxStateEvent),
    RigStatus(RigStatusEvent),
    ClockSync(ClockSyncEvent),
    QsoCompleted(QsoRecord),
    Waterfall(WaterfallFrame),
    WaterfallRow(WaterfallRow),
    /// Periodic RX input level (~1/s while decoding) so the UI can show a meter
    /// and warn when the captured audio is silent (e.g. the source/DAX channel
    /// isn't routed) instead of leaving a mysteriously blank waterfall.
    InputLevel(InputLevelEvent),
    Info(String),
    Error(String),
}

#[derive(Debug, Clone, Serialize)]
pub struct InputLevelEvent {
    /// RMS level of the recent capture in dBFS (≈ -100 for digital silence).
    pub db: f32,
    /// True when the level is at/below the silence floor while decoding — the
    /// "no audio reaching the app" condition.
    pub silent: bool,
}

/// Thread-safe handle for sending commands to the engine. Wraps the `Sender` in
/// a mutex so it satisfies Tauri managed-state's `Sync` bound regardless of the
/// `mpsc::Sender` auto-trait situation.
pub struct EngineHandle {
    tx: parking_lot::Mutex<Sender<EngineCommand>>,
}

impl EngineHandle {
    pub fn send(&self, cmd: EngineCommand) {
        let _ = self.tx.lock().send(cmd);
    }
}

/// Spawn the engine thread. Returns a command handle and the event receiver.
pub fn spawn(db: Arc<Db>) -> (EngineHandle, Receiver<EngineEvent>) {
    let (cmd_tx, cmd_rx) = std::sync::mpsc::channel();
    let (evt_tx, evt_rx) = std::sync::mpsc::channel();
    // Background NTP time sync: corrects the app's clock without admin rights or
    // external software. Feeds offsets back through the command channel.
    crate::timesync::spawn_loop(cmd_tx.clone());
    let engine_cmd_tx = cmd_tx.clone();
    std::thread::Builder::new()
        .name("ft8af-engine".into())
        .spawn(move || {
            let mut engine = Engine::new(db, evt_tx, engine_cmd_tx);
            engine.run(cmd_rx);
        })
        .expect("spawn engine thread");
    (EngineHandle { tx: parking_lot::Mutex::new(cmd_tx) }, evt_rx)
}

struct Engine {
    db: Arc<Db>,
    evt: Sender<EngineEvent>,
    accum: Arc<SlotAccumulator>,
    slot_tx: Sender<Vec<f32>>,
    dec_rx: Receiver<Vec<DecodedMessage>>,
    txed_slot: i64,
    qso: QsoEngine,
    rig: Option<RigConnection>,
    input: Option<AudioInput>,
    input_device: Option<String>,
    output_device: Option<String>,
    decoding: bool,
    dial_hz: u64,
    tx_audio_hz: i32,
    /// TX output level (0.0–1.0) applied to the waveform before playback.
    tx_gain: f32,
    /// Slot id (rx-corrected clock) most recently handed to the decode worker.
    /// Guards the once-per-slot early decode trigger in the run loop.
    last_decoded_slot: i64,
    /// Number of decode batches handed to the worker but not yet returned. A
    /// *count* rather than a single in-flight bool: the decoder returns exactly one
    /// batch per enqueued slot, so if it ever falls behind by more than one slot,
    /// the first returned batch must not clear the gate while later slots are still
    /// pending. Gates the run loop's boundary TX trigger (via [`awaiting_any_decode`])
    /// so it never keys a stale message ahead of a slow decode (the fresh one is
    /// keyed by `handle_decoded` when it arrives). See [`boundary_tx_ready`].
    pending_decodes: usize,
    /// Audio-capture latency compensation (ms). The RX decode window is sliced
    /// this much later than the UTC cycle boundary so that buffered/late-arriving
    /// input audio lands aligned — drives decoded DT toward 0. Auto-calibrated
    /// from the median DT of each slot's decodes; persisted across sessions.
    rx_offset_ms: i64,
    last_tick_ms: i64,
    tx_parity: Option<i64>,
    clock_offset_ms: i64,
    time_synced: bool,
    /// Clone of the command sender so background helpers (NTP sync) can feed
    /// results back into this loop.
    cmd_tx: Sender<EngineCommand>,
    ptt: bool,
    /// In-flight TX playback, if any. Held on this thread (the cpal stream is
    /// `!Send`); polled each loop tick so completion drops PTT, and dropped to
    /// stop audio immediately on Stop TX.
    tx_playback: Option<output::PreparedTx>,
    // live waterfall FFT (plan + window table + floor state; rebuilt on config change)
    wf: WfProcessor,
    last_wf_slot: i64, // last cycle slot marked on the live waterfall (rx-corrected)
    /// WSJT-X UDP interface (outbound broadcast + inbound request listener).
    udp: crate::udp::UdpService,
    /// UTC ms of the last UDP Heartbeat sent (rate-limits it to ~15 s).
    last_udp_heartbeat_ms: i64,
}

impl Engine {
    fn new(db: Arc<Db>, evt: Sender<EngineEvent>, cmd_tx: Sender<EngineCommand>) -> Self {
        // Restore persisted station + band where present.
        let my_call = db.get_config("my_call").unwrap_or_default();
        let my_grid = db.get_config("my_grid").unwrap_or_default();
        let dial_hz = db
            .get_config("dial_hz")
            .and_then(|s| s.parse().ok())
            .unwrap_or(bands::default_band().dial_hz);
        let input_device = db.get_config("input_device").filter(|s| !s.is_empty());
        let output_device = db.get_config("output_device").filter(|s| !s.is_empty());
        let tx_audio_hz = db
            .get_config("base_freq")
            .and_then(|s| s.parse().ok())
            .unwrap_or(DEFAULT_TX_AUDIO_HZ);
        let tx_gain = db
            .get_config("tx_gain")
            .and_then(|s| s.parse::<f32>().ok())
            .map(clamp_tx_gain)
            .unwrap_or(DEFAULT_TX_GAIN);
        // Restore the last NTP offset so DT is roughly right immediately, before
        // the first fresh sync of this session lands. Treated as already-synced.
        let saved_offset: Option<i64> = db.get_config("clock_offset_ms").and_then(|s| s.parse().ok());
        let rx_offset_ms: i64 = db
            .get_config("rx_offset_ms")
            .and_then(|s| s.parse().ok())
            .unwrap_or(0);
        let mut qso = QsoEngine::new(my_call, my_grid);
        qso.band = bands::band_for_freq_hz(dial_hz).to_string();
        qso.freq_mhz = bands::freq_mhz_string(dial_hz);

        // Restore persisted waterfall FFT knobs (issue #428); defaults match
        // the historical hard-coded pipeline (Hann, 2048, 6-segment Welch).
        let wf_defaults = WfConfig::default();
        let wf_cfg = WfConfig {
            window: db
                .get_config("wf_window")
                .map(|s| WfWindow::parse(&s))
                .unwrap_or(wf_defaults.window),
            fft_size: db
                .get_config("wf_fft_size")
                .and_then(|s| s.parse().ok())
                .unwrap_or(wf_defaults.fft_size),
            avg: db
                .get_config("wf_avg")
                .and_then(|s| s.parse().ok())
                .unwrap_or(wf_defaults.avg),
        }
        .sanitize();
        let wf = WfProcessor::new(wf_cfg);

        // Decode worker: owns the (!Send) Decoder on its own thread so the heavy
        // per-slot DSP never blocks the engine loop (which drives the live
        // waterfall + UTC clock). Fed slot audio over a channel; returns decodes.
        let (slot_tx, slot_rx) = std::sync::mpsc::channel::<Vec<f32>>();
        let (dec_tx, dec_rx) = std::sync::mpsc::channel::<Vec<DecodedMessage>>();
        std::thread::Builder::new()
            .name("ft8af-decoder".into())
            .spawn(move || {
                let mut decoder = Decoder::new(SAMPLE_RATE, true);
                while let Ok(slot) = slot_rx.recv() {
                    decoder.feed_slot(&slot);
                    let n = decoder.find_sync();
                    let msgs = if n > 0 { decoder.decode_all() } else { Vec::new() };
                    let _ = dec_tx.send(msgs);
                }
            })
            .expect("spawn decoder thread");

        Engine {
            db,
            evt,
            accum: Arc::new(SlotAccumulator::new()),
            slot_tx,
            dec_rx,
            txed_slot: -1,
            qso,
            rig: None,
            input: None,
            input_device,
            output_device,
            decoding: false,
            dial_hz,
            tx_audio_hz,
            tx_gain,
            last_decoded_slot: -1,
            pending_decodes: 0,
            rx_offset_ms,
            last_tick_ms: 0,
            tx_parity: None,
            clock_offset_ms: saved_offset.unwrap_or(0),
            time_synced: saved_offset.is_some(),
            udp: crate::udp::UdpService::new(cmd_tx.clone()),
            cmd_tx,
            ptt: false,
            tx_playback: None,
            wf,
            last_wf_slot: -1,
            last_udp_heartbeat_ms: 0,
        }
    }

    /// Load the persisted WSJT-X UDP settings into a config struct. Falls back to
    /// the WSJT-X-compatible defaults (disabled, 127.0.0.1:2237) for missing keys.
    fn read_udp_config(&self) -> crate::udp::UdpConfig {
        let d = crate::udp::UdpConfig::default();
        crate::udp::UdpConfig {
            enabled: self.db.get_config("udp_enabled").as_deref() == Some("true"),
            host: self.db.get_config("udp_host").filter(|s| !s.is_empty()).unwrap_or(d.host),
            port: self
                .db
                .get_config("udp_port")
                .and_then(|s| s.parse().ok())
                .unwrap_or(d.port),
            accept_requests: self.db.get_config("udp_accept_requests").as_deref() == Some("true"),
        }
    }

    fn now(&self) -> i64 {
        util::now_unix_ms() + self.clock_offset_ms
    }

    fn run(&mut self, cmd_rx: Receiver<EngineCommand>) {
        // Bring up the WSJT-X UDP interface from persisted settings (binds the
        // socket + inbound listener if enabled). Non-fatal — a bad host/port just
        // surfaces an error and leaves the feature off.
        let udp_cfg = self.read_udp_config();
        match self.udp.apply(udp_cfg) {
            Ok(msg) => self.emit(EngineEvent::Info(msg)),
            Err(e) => self.emit(EngineEvent::Error(e)),
        }

        // Reconnect the last-used rig on startup (non-fatal if it can't).
        if let Some(json) = self.db.get_config("rig_config") {
            if let Ok(cfg) = serde_json::from_str::<RigConfig>(&json) {
                if cfg.backend != crate::rig::RigBackend::None {
                    self.select_rig(cfg);
                }
            }
        }

        loop {
            // Drain pending commands.
            loop {
                match cmd_rx.try_recv() {
                    Ok(EngineCommand::Shutdown) | Err(TryRecvError::Disconnected) => return,
                    Ok(cmd) => self.handle(cmd),
                    Err(TryRecvError::Empty) => break,
                }
            }

            let now = self.now();
            let ms_into = now.rem_euclid(CYCLE_MS);
            let slot_id = now.div_euclid(CYCLE_MS);

            // 1 Hz UI clock.
            if now - self.last_tick_ms >= 1000 {
                self.last_tick_ms = now;
                self.emit(EngineEvent::Cycle(CycleTick {
                    utc_ms: now,
                    sequential: slot_id.rem_euclid(2),
                    ms_into_cycle: ms_into,
                }));
                // WSJT-X UDP Status every tick (so companions track dial/TX/decode
                // state), Heartbeat every ~15 s (how they discover our address).
                self.broadcast_status();
                if now - self.last_udp_heartbeat_ms >= 15_000 {
                    self.last_udp_heartbeat_ms = now;
                    self.broadcast_heartbeat();
                }
                // RX input level + silence warning, once we're capturing. Surfaces
                // a dead source (e.g. DAX channel not routed) that otherwise looks
                // like a broken waterfall.
                if self.decoding {
                    let recent = self.accum.peek_recent(SAMPLE_RATE as usize / 2); // ~0.5 s
                    let db = rms_dbfs(&recent);
                    self.emit(EngineEvent::InputLevel(InputLevelEvent {
                        db,
                        silent: db <= SILENCE_DBFS,
                    }));
                }
            }

            // Decode runs on a clock shifted later by the capture-latency
            // compensation, so the sliced slot aligns with the audio that has
            // actually arrived in the buffer (UTC display + TX still use real `now`).
            // We fire once per slot as soon as the 12.64 s waveform is captured
            // (~13.2 s in) rather than at the 15 s boundary, so decodes land a slot
            // earlier and the operator can reply on the next cycle (not skip one).
            let corrected = now - self.rx_offset_ms;
            let rx_slot_id = corrected.div_euclid(CYCLE_MS);
            let into_rx_cycle = corrected.rem_euclid(CYCLE_MS);
            if self.decoding && rx_slot_id != self.last_decoded_slot && into_rx_cycle >= DECODE_AT_MS
            {
                self.last_decoded_slot = rx_slot_id;
                // The slot's audio so far = the most recent `into_rx_cycle` of it,
                // front-aligned (signal at sample 0) with the tail zero-padded.
                let elapsed = (into_rx_cycle * SAMPLE_RATE as i64 / 1000) as usize;
                let slot = self.accum.take_slot_from_start(elapsed);
                if self.slot_tx.send(slot).is_ok() {
                    // Hold off the boundary TX trigger until this slot's decodes
                    // come back, so it can't key a stale message ahead of a slow
                    // decode (the fresh one is keyed by handle_decoded on arrival).
                    // Count it: if the decoder is already behind, the gate must
                    // stay closed until every outstanding slot has returned.
                    self.pending_decodes += 1;
                }
            }

            // Act on any decodes the worker has finished (off-loop DSP). This is
            // where steady-state TX is driven: each slot's decodes advance the QSO
            // and then `maybe_transmit` keys up if it's our turn. Immediate keying
            // on an operator tap is handled separately, in the command handlers.
            while let Ok(msgs) = self.dec_rx.try_recv() {
                // One batch settled — decrement, not reset, so a still-pending
                // later slot keeps the boundary gate closed. saturating_sub guards
                // against a stray batch arriving after a StopDecode reset.
                self.pending_decodes = self.pending_decodes.saturating_sub(1);
                self.handle_decoded(msgs, slot_id);
            }

            // Boundary TX trigger: key a queued reply/CQ early in its slot (anywhere
            // within the TX window), even when this slot's decodes were processed
            // earlier in the previous cycle. `maybe_transmit` is otherwise only
            // reached on decode arrival, which on a fast decode lands mid-slot — past
            // the TX window — so the queued message would never go out (auto-sequence
            // stalls; CQ never retransmits). Gated on `!awaiting_any_decode` so a
            // stale message is never keyed before every outstanding slot's decodes
            // are in; `maybe_transmit` re-checks eligibility and is idempotent per
            // slot (the `txed_slot` guard).
            if boundary_tx_ready(
                self.decoding,
                self.qso.active,
                awaiting_any_decode(self.pending_decodes),
                self.tx_parity,
                slot_id,
                self.txed_slot,
                now.rem_euclid(CYCLE_MS),
            ) {
                self.maybe_transmit(slot_id);
            }

            // Drop PTT as soon as the TX waveform finishes clocking out (or a
            // stalled device trips the deadline). Polling here — rather than
            // blocking inside `transmit` — is what lets Stop TX take effect
            // mid-transmission instead of at the end of the cycle.
            if let Some(pb) = self.tx_playback.as_ref() {
                if pb.is_done() || pb.timed_out() {
                    self.finalize_tx();
                }
            }

            // Live scrolling waterfall: one spectrum row per ~100 ms tick.
            if self.decoding {
                self.emit_waterfall_row();
            }

            std::thread::sleep(Duration::from_millis(100));
        }
    }

    /// Nudge the capture-latency compensation toward zeroing the decoded DT. The
    /// FT8 network is NTP-synced, so the *median* DT of a slot's decodes is a robust
    /// estimate of our own systematic timing error (clock residual + audio latency).
    /// A slow EMA keeps it from chasing noise; persisted so DT is right at next launch.
    fn calibrate_dt(&mut self, decoded: &[DecodedMessage]) {
        // Need a few independent decodes for the median to be meaningful.
        const MIN_DECODES: usize = 4;
        const ALPHA: f64 = 0.6; // correction fraction applied per slot
        const DEADBAND_MS: i64 = 60; // ignore tiny residuals to avoid jitter
        const MAX_OFFSET_MS: i64 = 4_000;
        if decoded.len() < MIN_DECODES {
            return;
        }

        let mut dts: Vec<f32> = decoded.iter().map(|m| m.time_sec).collect();
        dts.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        let median_dt_ms = (dts[dts.len() / 2] * 1000.0) as i64;
        if median_dt_ms.abs() <= DEADBAND_MS {
            return;
        }

        let adjusted = self.rx_offset_ms + (median_dt_ms as f64 * ALPHA).round() as i64;
        let new_offset = adjusted.clamp(-MAX_OFFSET_MS, MAX_OFFSET_MS);
        if new_offset == self.rx_offset_ms {
            return;
        }
        self.rx_offset_ms = new_offset;
        // No re-anchor needed: the decode trigger requires `into_rx_cycle >=
        // DECODE_AT_MS`, and offset changes land early in the cycle (right after a
        // decode is delivered), far below that threshold — so a shifted slot id
        // can't spuriously re-fire the decode this cycle.
        let _ = self.db.set_config("rx_offset_ms", &new_offset.to_string());
        self.publish_clock_sync();
    }

    /// Handle one slot's decodes (from the worker): publish them, advance the QSO
    /// state machine, then key up if it's our turn this slot. Runs once per slot
    /// (the decoder returns a batch — possibly empty — every cycle), so this is
    /// what drives CQ/auto-sequence retransmits.
    fn handle_decoded(&mut self, decoded: Vec<DecodedMessage>, slot_id: i64) {
        self.publish_decodes(&decoded);
        self.broadcast_decodes(&decoded);
        self.calibrate_dt(&decoded);

        if let Some(QsoOutcome::Completed(record)) = self.qso.process_rx(&decoded) {
            match self.db.insert_qso(&record) {
                Ok(_) => {
                    self.broadcast_qso(&record);
                    self.emit(EngineEvent::QsoCompleted(record));
                }
                Err(e) => self.emit(EngineEvent::Error(format!("log QSO failed: {e}"))),
            }
        }
        self.maybe_transmit(slot_id);
        self.publish_tx_state();
    }

    /// Key up this slot's QSO message if we're armed, it's our turn, and the
    /// 12.64 s waveform still fits before the next boundary. Idempotent per slot
    /// (`txed_slot` guard), so calling it from both `handle_decoded` and a command
    /// handler in the same slot transmits at most once.
    fn maybe_transmit(&mut self, slot_id: i64) {
        // All the keying rules — active QSO, locked-parity alternation, once per
        // slot, and early enough in the cycle to fit the waveform — live in the pure
        // `tx_slot_eligible` so the run loop's boundary trigger shares them exactly.
        if !tx_slot_eligible(
            self.qso.active,
            self.tx_parity,
            slot_id,
            self.txed_slot,
            self.now().rem_euclid(CYCLE_MS),
        ) {
            return;
        }
        // Answering a CQ pins the parity to the operator's click slot so we always
        // reply opposite the DX; a CQ / free-text start locks it on this first TX.
        self.tx_parity = Some(slot_id.rem_euclid(2));
        self.txed_slot = slot_id;
        if let Some(msg) = self.qso.tx_message().map(|s| s.to_string()) {
            if self.start_transmit(&msg) {
                // Tell the sequencer the message actually went out. This is what
                // lets a courtesy "73" wrap the QSO up only after it's on the air;
                // if start_transmit bailed (busy/encode error) the QSO stays armed
                // and retransmits next slot instead of clobbering the 73.
                self.qso.notify_transmitted();
            }
        }
    }

    /// The TX slot index we're currently in, by real UTC.
    fn cur_slot(&self) -> i64 {
        self.now().div_euclid(CYCLE_MS)
    }

    /// Parity (0/1) of the slot we're currently in.
    fn cur_parity(&self) -> i64 {
        self.cur_slot().rem_euclid(2)
    }

    /// Begin transmitting `message`, returning immediately. Playback runs off the
    /// loop (in `tx_playback`) so the engine keeps draining commands — that's what
    /// makes Stop TX able to interrupt mid-transmission. PTT is dropped later, when
    /// the run loop sees the playback finish (or on Stop TX).
    ///
    /// Returns `true` if a transmission was actually started (PTT keyed, playback
    /// armed), `false` if it bailed out (already transmitting, or an encode /
    /// device / playback error). The caller uses this to tell the sequencer the
    /// message really went out.
    fn start_transmit(&mut self, message: &str) -> bool {
        // Never key a second transmission over a live one.
        if self.tx_playback.is_some() {
            return false;
        }
        let signal = match crate::dsp::encode::generate_ft8(message, self.tx_audio_hz as f32, SAMPLE_RATE) {
            Some(s) => s,
            None => {
                self.emit(EngineEvent::Error(format!("cannot encode '{message}'")));
                return false;
            }
        };

        // Do the slow work — device open + full-buffer resample — BEFORE keying
        // the rig, so PTT isn't held through it. On a weak CPU this resample is
        // hundreds of ms; running it after PTT is what produced the long
        // keyed-but-silent gap reported on the Inovato Quadra.
        let mut prepared = match output::prepare(self.output_device.as_deref(), &signal, self.tx_gain) {
            Ok(p) => p,
            Err(e) => {
                self.emit(EngineEvent::Error(format!("playback failed: {e}")));
                return false;
            }
        };

        // Key up, let the rig's T/R relay settle, then start clocking samples.
        self.set_ptt(true);
        std::thread::sleep(Duration::from_millis(PTT_DELAY_MS));

        // Clip leading audio only if we'd overrun the cycle (CLAUDE.md gotcha:
        // ms_late = max(0, into_cycle - 2360), NOT into_cycle % 15000). Computed
        // here, right before audio starts, so it reflects the real lateness after
        // the PTT settle — `maybe_transmit`'s window check keeps this at 0 in
        // normal operation.
        let into_cycle = self.now().rem_euclid(CYCLE_MS);
        let ms_late = (into_cycle - TX_SLACK_MS).max(0);
        if let Err(e) = prepared.start(ms_late) {
            self.emit(EngineEvent::Error(format!("playback failed: {e}")));
            self.set_ptt(false);
            return false;
        }
        self.tx_playback = Some(prepared);
        self.publish_tx_state();
        true
    }

    /// Stop the in-flight transmission (if any): dropping the handle halts the
    /// cpal stream immediately, then PTT goes down. Safe to call when not TXing.
    fn finalize_tx(&mut self) {
        if self.tx_playback.take().is_some() {
            self.set_ptt(false);
            self.publish_tx_state();
        }
    }

    fn set_ptt(&mut self, on: bool) {
        if let Some(rig) = self.rig.as_mut() {
            if let Err(e) = rig.set_ptt(on) {
                self.emit(EngineEvent::Error(format!("PTT failed: {e}")));
            }
        }
        self.ptt = on;
        self.publish_rig_status();
    }

    /// Compute one windowed-FFT spectrum row from the most recent audio and emit
    /// it for the scrolling waterfall. Color is auto-scaled relative to a smoothed
    /// noise-floor estimate so the floor stays blue regardless of input gain.
    fn emit_waterfall_row(&mut self) {
        let samples = self.accum.peek_recent(self.wf.samples_needed());
        let Some((bins, hz_per_col)) = self.wf.process_row(&samples, SAMPLE_RATE as f32) else {
            return; // still warming up
        };
        // Mark this row if it's the first at/after a cycle boundary on the same
        // rx-corrected clock the decoder uses, so the grid line aligns with DT.
        let boundary = wf_boundary_row(self.now() - self.rx_offset_ms, &mut self.last_wf_slot);
        self.emit(EngineEvent::WaterfallRow(WaterfallRow { bins, hz_per_col, boundary }));
    }

    fn publish_decodes(&self, decoded: &[DecodedMessage]) {
        let now = self.now();
        let ui: Vec<UiMessage> = decoded
            .iter()
            .map(|m| UiMessage {
                utc_ms: now,
                call_to: m.call_to.clone(),
                call_from: m.call_from.clone(),
                extra: m.extra.clone(),
                grid: m.grid.clone(),
                snr: m.snr,
                freq_hz: m.freq_hz,
                time_sec: m.time_sec,
                text: decode_text(m),
                is_cq: m.call_to.eq_ignore_ascii_case("CQ"),
                to_me: !self.qso.my_call.is_empty()
                    && m.call_to.eq_ignore_ascii_case(&self.qso.my_call),
            })
            .collect();
        self.emit(EngineEvent::Decoded(ui));
    }

    // --- WSJT-X UDP broadcast (outbound) ------------------------------------

    /// Assemble a WSJT-X Status snapshot from current engine state.
    fn udp_status(&self) -> crate::udp::codec::Status {
        let st = self.qso.status();
        crate::udp::codec::Status {
            dial_freq_hz: self.dial_hz,
            mode: "FT8".to_string(),
            dx_call: st.target.clone().unwrap_or_default(),
            report: st.report_sent.map(|n| format!("{n:+03}")).unwrap_or_default(),
            tx_mode: "FT8".to_string(),
            tx_enabled: st.active,
            transmitting: self.tx_playback.is_some(),
            decoding: self.decoding,
            rx_df: self.tx_audio_hz.max(0) as u32,
            tx_df: self.tx_audio_hz.max(0) as u32,
            de_call: self.qso.my_call.clone(),
            de_grid: self.qso.my_grid.clone(),
            dx_grid: String::new(),
            tx_watchdog: false,
            sub_mode: String::new(),
            fast_mode: false,
            special_op_mode: 0,
            freq_tolerance: 0xFFFF_FFFF,
            tr_period: 15,
            config_name: "Default".to_string(),
            tx_message: st.tx_message.clone().unwrap_or_default(),
        }
    }

    fn broadcast_status(&self) {
        if self.udp.is_enabled() {
            self.udp.send(&crate::udp::codec::status(&self.udp_status()));
        }
    }

    fn broadcast_heartbeat(&self) {
        if self.udp.is_enabled() {
            self.udp
                .send(&crate::udp::codec::heartbeat(env!("CARGO_PKG_VERSION"), ""));
        }
    }

    /// Broadcast each of a slot's decodes as a WSJT-X Decode message. The `Time`
    /// field is ms-since-UTC-midnight; `Frequency` is the audio offset in Hz.
    fn broadcast_decodes(&mut self, decoded: &[DecodedMessage]) {
        if !self.udp.is_enabled() {
            return;
        }
        let time_ms = self.now().rem_euclid(86_400_000) as u32;
        for m in decoded {
            self.udp.send_decode(crate::udp::codec::Decode {
                is_new: true,
                time_ms,
                snr: m.snr,
                delta_time: m.time_sec as f64,
                delta_freq: m.freq_hz.max(0.0) as u32,
                mode: "FT8".to_string(),
                message: decode_text(m),
                low_confidence: false,
                off_air: false,
            });
        }
    }

    /// Broadcast a logged QSO as both the structured QSO-Logged message and the
    /// Logged-ADIF message (loggers consume one or the other).
    fn broadcast_qso(&mut self, record: &QsoRecord) {
        if !self.udp.is_enabled() {
            return;
        }
        use crate::udp::codec::{qso_logged, DateTime, QsoLogged};
        // Actual TX RF frequency = dial + audio offset (what WSJT-X reports).
        let tx_freq_hz = self.dial_hz + self.tx_audio_hz.max(0) as u64;
        let q = QsoLogged {
            time_off: DateTime::from_adif(&record.qso_date_off, &record.time_off),
            dx_call: record.call.clone(),
            dx_grid: record.gridsquare.clone(),
            tx_freq_hz,
            mode: record.mode.clone(),
            report_sent: record.rst_sent.clone(),
            report_received: record.rst_rcvd.clone(),
            tx_power: String::new(),
            comments: record.comment.clone(),
            name: String::new(),
            time_on: DateTime::from_adif(&record.qso_date, &record.time_on),
            operator_call: record.station_callsign.clone(),
            my_call: record.station_callsign.clone(),
            my_grid: record.my_gridsquare.clone(),
            exchange_sent: String::new(),
            exchange_received: String::new(),
            prop_mode: String::new(),
        };
        self.udp.send(&qso_logged(&q));
        self.udp
            .send(&crate::udp::codec::logged_adif(&crate::db::adif_record(record)));
    }

    fn publish_tx_state(&self) {
        self.emit(EngineEvent::TxState(TxStateEvent {
            // Derived from the live playback handle so the badge can't get stuck:
            // it's TX exactly while a transmission is clocking out.
            transmitting: self.tx_playback.is_some(),
            message: self.qso.tx_message().map(|s| s.to_string()),
            status: self.qso.status(),
        }));
        // Mirror the state change out over WSJT-X UDP so companion apps see TX
        // start/stop and QSO progress without waiting for the next 1 Hz tick.
        self.broadcast_status();
    }

    fn publish_rig_status(&self) {
        let (connected, model) = match &self.rig {
            Some(r) => (r.connected(), r.name()),
            None => (false, "None".to_string()),
        };
        self.emit(EngineEvent::RigStatus(RigStatusEvent {
            connected,
            model,
            frequency_hz: self.dial_hz,
            ptt: self.ptt,
        }));
    }

    fn publish_clock_sync(&self) {
        self.emit(EngineEvent::ClockSync(ClockSyncEvent {
            offset_ms: self.clock_offset_ms,
            synced: self.time_synced,
            rx_offset_ms: self.rx_offset_ms,
        }));
    }

    fn emit(&self, e: EngineEvent) {
        let _ = self.evt.send(e);
    }

    /// Adopt an inbound WSJT-X Reply's requested `df` as the **session** TX audio
    /// offset — in-memory only. The value arrives on an untrusted UDP socket, so
    /// this deliberately does *not* write through to the persisted `base_freq`
    /// config: only explicit operator actions (SetBaseFreq / the UI) change the
    /// saved offset, and a datagram can't survive a restart. A `0`/unspecified or
    /// out-of-band `df` is ignored (current offset kept). Returns whether the
    /// session offset changed.
    fn apply_reply_df(&mut self, delta_freq: u32) -> bool {
        match reply_tx_audio_hz(delta_freq) {
            Some(hz) => {
                self.tx_audio_hz = hz;
                true
            }
            None => false,
        }
    }

    fn handle(&mut self, cmd: EngineCommand) {
        match cmd {
            EngineCommand::StartDecode => self.start_decode(),
            EngineCommand::StopDecode => {
                self.decoding = false;
                self.input = None;
                // No slot is in flight once capture stops; clear the count so a
                // later CQ can key at its slot boundary without being blocked. Any
                // batch still in the channel decrements from 0 via saturating_sub.
                self.pending_decodes = 0;
                // Tell companion apps to wipe their decode band and forget the
                // replay history — this is a genuine reset, not a per-cycle churn.
                if self.udp.is_enabled() {
                    self.udp.send(&crate::udp::codec::clear());
                }
                self.udp.clear_replay_cache();
                self.broadcast_status();
                self.emit(EngineEvent::Info("decode stopped".into()));
            }
            EngineCommand::SetStation { call, grid } => {
                self.qso.my_call = call.to_uppercase();
                self.qso.my_grid = util::grid4(&grid).to_uppercase();
                let _ = self.db.set_config("my_call", &self.qso.my_call);
                let _ = self.db.set_config("my_grid", &self.qso.my_grid);
            }
            EngineCommand::SetBand(hz) => {
                self.dial_hz = hz;
                self.qso.band = bands::band_for_freq_hz(hz).to_string();
                self.qso.freq_mhz = bands::freq_mhz_string(hz);
                let _ = self.db.set_config("dial_hz", &hz.to_string());
                if let Some(rig) = self.rig.as_mut() {
                    let _ = rig.set_frequency(hz);
                }
                // New band = new decode context: clear companions' band + replay
                // history and push the fresh dial frequency in a Status.
                if self.udp.is_enabled() {
                    self.udp.send(&crate::udp::codec::clear());
                }
                self.udp.clear_replay_cache();
                self.broadcast_status();
                self.publish_rig_status();
            }
            EngineCommand::SetBaseFreq(hz) => {
                self.tx_audio_hz = hz.clamp(MIN_TX_AUDIO_HZ, MAX_TX_AUDIO_HZ);
                let _ = self.db.set_config("base_freq", &self.tx_audio_hz.to_string());
            }
            EngineCommand::SetTxGain(g) => {
                self.tx_gain = clamp_tx_gain(g);
                let _ = self.db.set_config("tx_gain", &self.tx_gain.to_string());
            }
            EngineCommand::SetWaterfallConfig(cfg) => {
                let cfg = cfg.sanitize();
                let _ = self.db.set_config("wf_window", cfg.window.as_str());
                let _ = self.db.set_config("wf_fft_size", &cfg.fft_size.to_string());
                let _ = self.db.set_config("wf_avg", &cfg.avg.to_string());
                // Rebuild plan + window table; the noise-floor EMA restarts and
                // reconverges within a couple of seconds.
                self.wf = WfProcessor::new(cfg);
                self.emit(EngineEvent::Info(format!(
                    "waterfall: {} {} avg {}",
                    cfg.window.as_str(),
                    cfg.fft_size,
                    cfg.avg
                )));
            }
            EngineCommand::SetUdpConfig(cfg) => {
                // Persist the four settings, then rebind live.
                let _ = self.db.set_config("udp_enabled", if cfg.enabled { "true" } else { "false" });
                let _ = self.db.set_config("udp_host", &cfg.host);
                let _ = self.db.set_config("udp_port", &cfg.port.to_string());
                let _ = self.db.set_config(
                    "udp_accept_requests",
                    if cfg.accept_requests { "true" } else { "false" },
                );
                match self.udp.apply(cfg) {
                    Ok(msg) => {
                        // Prime companions immediately on enable.
                        self.broadcast_heartbeat();
                        self.broadcast_status();
                        self.emit(EngineEvent::Info(msg));
                    }
                    Err(e) => self.emit(EngineEvent::Error(e)),
                }
            }
            EngineCommand::UdpReplay => self.udp.replay(),
            EngineCommand::SetInputDevice(name) => {
                self.input_device = name.clone();
                let _ = self.db.set_config("input_device", name.as_deref().unwrap_or(""));
                if self.decoding {
                    self.start_decode(); // restart capture on the new device
                }
            }
            EngineCommand::SetOutputDevice(name) => {
                self.output_device = name.clone();
                let _ = self.db.set_config("output_device", name.as_deref().unwrap_or(""));
            }
            EngineCommand::SelectRig(cfg) => {
                if let Ok(json) = serde_json::to_string(&cfg) {
                    let _ = self.db.set_config("rig_config", &json);
                }
                self.select_rig(cfg);
            }
            EngineCommand::DisconnectRig => {
                // Stop any in-flight TX and drop PTT *before* dropping the rig:
                // set_ptt() only sends the CAT un-key when the rig is still
                // connected, so tearing the transport down first could leave the
                // radio keyed. Also stop CQ/QSO so we don't try to re-key a
                // disconnected rig on the next slot.
                self.qso.stop();
                self.tx_parity = None;
                self.finalize_tx();
                self.publish_tx_state();
                // Now drop the connection (runs the rig's close/cleanup); keep the
                // saved config so reconnecting is one click away.
                self.rig = None;
                self.ptt = false;
                self.emit(EngineEvent::Info("rig disconnected".into()));
                self.publish_rig_status();
            }
            EngineCommand::StartCq => {
                self.tx_parity = None;
                self.qso.start_cq();
                // Start CQ this very slot if we're still inside the window;
                // otherwise the first transmission lands at the next free slot.
                self.maybe_transmit(self.cur_slot());
                self.publish_tx_state();
            }
            EngineCommand::Answer(args) => {
                // Honor the audio tone the companion asked us to answer on (WSJT-X's
                // `df`) for *this session* when it carries a plausible one, so we key
                // up on the requested offset rather than the current TX tone —
                // matching the Android/iOS ports. This is an in-memory-only change:
                // the `df` arrives on an untrusted UDP socket, so it must never
                // rewrite the operator's persisted `base_freq` (only SetBaseFreq / the
                // UI do that, and the saved offset survives a restart). The desktop
                // UI's own "click to answer" sends df=0 (offset unchanged).
                self.apply_reply_df(args.delta_freq);
                let msg = DecodedMessage {
                    call_from: args.call_from,
                    grid: args.grid,
                    snr: args.snr,
                    ..Default::default()
                };
                // Reply opposite the DX: the operator is clicking during the slot
                // after the CQ, so pin TX to this slot's parity and key up right
                // away if we're still inside the window — matching WSJT-X.
                self.tx_parity = Some(self.cur_parity());
                self.qso.answer(&msg);
                self.maybe_transmit(self.cur_slot());
                self.publish_tx_state();
            }
            EngineCommand::SetStage(stage) => {
                self.tx_parity = None;
                self.qso.set_stage(stage);
                self.maybe_transmit(self.cur_slot());
                self.publish_tx_state();
            }
            EngineCommand::StopTx => {
                self.qso.stop();
                self.tx_parity = None;
                // Halt audio + drop PTT now, not at the end of the cycle.
                self.finalize_tx();
                self.publish_tx_state();
            }
            EngineCommand::FreeText(text) => {
                self.tx_parity = None;
                self.qso.set_free_text(&text);
                self.maybe_transmit(self.cur_slot());
                self.publish_tx_state();
            }
            EngineCommand::RefreshStatus => {
                self.publish_rig_status();
                self.publish_tx_state();
                self.publish_clock_sync();
            }
            EngineCommand::SetClockOffset(offset_ms) => {
                self.clock_offset_ms = offset_ms;
                self.time_synced = true;
                let _ = self.db.set_config("clock_offset_ms", &offset_ms.to_string());
                self.emit(EngineEvent::Info(format!(
                    "time synced: clock offset {offset_ms:+} ms"
                )));
                self.publish_clock_sync();
            }
            EngineCommand::ResyncTime => {
                crate::timesync::spawn_once(self.cmd_tx.clone());
            }
            EngineCommand::Shutdown => {}
        }
    }

    fn start_decode(&mut self) {
        match AudioInput::start(self.input_device.as_deref(), self.accum.clone()) {
            Ok(input) => {
                self.emit(EngineEvent::Info(format!(
                    "capturing from '{}' @ {} Hz",
                    input.device_name, input.device_rate
                )));
                self.input = Some(input);
                self.decoding = true;
            }
            Err(e) => self.emit(EngineEvent::Error(format!("audio start failed: {e}"))),
        }
    }

    fn select_rig(&mut self, cfg: RigConfig) {
        // Close any existing connection FIRST. Some CAT servers (notably
        // SmartSDR CAT) accept only one client, so opening the new session while
        // the old one is still open makes the new handshake time out. Dropping
        // here runs the old rig's close/cleanup before we reconnect.
        self.rig = None;
        match RigConnection::connect(&cfg) {
            Ok(mut rig) => {
                let _ = rig.set_frequency(self.dial_hz);
                let name = rig.name();
                self.rig = Some(rig);
                self.emit(EngineEvent::Info(format!("rig connected: {name}")));
            }
            Err(e) => {
                self.rig = None;
                self.emit(EngineEvent::Error(format!("rig connect failed: {e}")));
            }
        }
        self.publish_rig_status();
    }
}

#[cfg(test)]
mod tests {
    use super::{
        clamp_tx_gain, reply_tx_audio_hz, rms_dbfs, wf_boundary_row, Engine, CYCLE_MS,
        MAX_TX_AUDIO_HZ, MIN_TX_AUDIO_HZ, SILENCE_DBFS,
    };
    use crate::db::Db;
    use std::sync::Arc;

    #[test]
    fn reply_df_updates_session_offset_without_persisting() {
        // The operator's saved TX offset (base_freq) is what survives a restart.
        let db = Arc::new(Db::open_in_memory().expect("in-memory db"));
        db.set_config("base_freq", "1500").expect("seed base_freq");

        let (evt_tx, _evt_rx) = std::sync::mpsc::channel();
        let (cmd_tx, _cmd_rx) = std::sync::mpsc::channel();
        let mut engine = Engine::new(Arc::clone(&db), evt_tx, cmd_tx);
        assert_eq!(engine.tx_audio_hz, 1500, "restored from persisted base_freq");

        // An inbound (untrusted) UDP Reply asks us to answer on 1800 Hz: the
        // in-memory session offset moves so we key up on the requested tone...
        assert!(engine.apply_reply_df(1800));
        assert_eq!(engine.tx_audio_hz, 1800);
        // ...but the operator's *persisted* offset is left exactly as they saved
        // it — a network datagram must not change what comes back after a restart.
        assert_eq!(db.get_config("base_freq").as_deref(), Some("1500"));

        // A 0/unspecified or out-of-band df changes neither the session offset nor
        // the persisted config.
        assert!(!engine.apply_reply_df(0));
        assert!(!engine.apply_reply_df(50_000));
        assert_eq!(engine.tx_audio_hz, 1800);
        assert_eq!(db.get_config("base_freq").as_deref(), Some("1500"));
    }

    #[test]
    fn reply_df_honors_in_band_and_ignores_the_rest() {
        // A plausible tone in the passband is adopted verbatim so we answer on it.
        assert_eq!(reply_tx_audio_hz(1500), Some(1500));
        assert_eq!(reply_tx_audio_hz(MIN_TX_AUDIO_HZ as u32), Some(MIN_TX_AUDIO_HZ));
        assert_eq!(reply_tx_audio_hz(MAX_TX_AUDIO_HZ as u32), Some(MAX_TX_AUDIO_HZ));

        // df == 0 is WSJT-X's "unspecified" — keep the current offset.
        assert_eq!(reply_tx_audio_hz(0), None);
        // Below/above the usable audio passband: a garbled datagram must not push
        // the TX tone off the band, so ignore it and keep the current offset.
        assert_eq!(reply_tx_audio_hz(MIN_TX_AUDIO_HZ as u32 - 1), None);
        assert_eq!(reply_tx_audio_hz(MAX_TX_AUDIO_HZ as u32 + 1), None);
        assert_eq!(reply_tx_audio_hz(50_000), None);
        // A value past i32::MAX can't be a real audio offset either.
        assert_eq!(reply_tx_audio_hz(u32::MAX), None);
    }

    #[test]
    fn rms_dbfs_flags_silence_and_signal() {
        // Digital silence floors well below the silence threshold (not -inf).
        let silence = vec![0.0f32; 6000];
        let db_silent = rms_dbfs(&silence);
        assert!(db_silent.is_finite());
        assert!(db_silent <= SILENCE_DBFS, "silence {db_silent} should be ≤ {SILENCE_DBFS}");

        // Empty buffer is treated as silence, never a panic or -inf.
        assert!(rms_dbfs(&[]) <= SILENCE_DBFS);

        // A half-scale full-amplitude square wave is ~ -6 dBFS — clearly "audio".
        let signal = vec![0.5f32; 6000];
        let db_signal = rms_dbfs(&signal);
        assert!((db_signal - -6.02).abs() < 0.1, "0.5 RMS should be ~-6 dBFS, got {db_signal}");
        assert!(db_signal > SILENCE_DBFS);
    }

    #[test]
    fn tx_gain_clamps_to_unit_range() {
        assert_eq!(clamp_tx_gain(0.5), 0.5); // in range, untouched
        assert_eq!(clamp_tx_gain(1.4), 1.0); // never overdrives past full scale
        assert_eq!(clamp_tx_gain(-0.2), 0.0); // never negative (phase flip / garbage)
    }

    #[test]
    fn wf_boundary_fires_once_per_cycle() {
        let mut last = -1;
        // First call always marks (initial slot differs from the -1 sentinel).
        assert!(wf_boundary_row(0, &mut last));
        // Subsequent rows within the same 15 s slot do not re-mark.
        assert!(!wf_boundary_row(100, &mut last));
        assert!(!wf_boundary_row(CYCLE_MS - 1, &mut last));
        // Crossing into the next slot marks exactly once.
        assert!(wf_boundary_row(CYCLE_MS, &mut last));
        assert!(!wf_boundary_row(CYCLE_MS + 500, &mut last));
        // And again at the following boundary.
        assert!(wf_boundary_row(2 * CYCLE_MS + 10, &mut last));
    }

    #[test]
    fn tx_slot_eligible_enforces_active_parity_once_and_window() {
        use super::{tx_slot_eligible, TX_LATEST_MS};
        // Inactive QSO never keys.
        assert!(!tx_slot_eligible(false, None, 10, -1, 0));
        // Active, no parity lock, fresh slot, at the very top of the cycle → eligible.
        assert!(tx_slot_eligible(true, None, 10, -1, 0));
        // The last instant that still fits the waveform is eligible; one ms later is not.
        assert!(tx_slot_eligible(true, None, 10, -1, TX_LATEST_MS));
        assert!(!tx_slot_eligible(true, None, 10, -1, TX_LATEST_MS + 1));
        // Already transmitted in this slot → no second keying.
        assert!(!tx_slot_eligible(true, None, 10, 10, 0));
        // A locked parity must match the slot's parity (slot 10 is even, 11 is odd).
        assert!(tx_slot_eligible(true, Some(0), 10, -1, 0));
        assert!(!tx_slot_eligible(true, Some(1), 10, -1, 0));
        assert!(tx_slot_eligible(true, Some(1), 11, -1, 0));
    }

    #[test]
    fn boundary_tx_keys_at_slot_top_only_after_this_slots_decode() {
        use super::{boundary_tx_ready, TX_LATEST_MS};
        // Fast decode: this slot's decodes are already processed (awaiting=false), so a
        // reply computed in the previous cycle keys at the top of its slot. This is the
        // case the decode-arrival trigger used to miss — it fires mid-slot, past the
        // window, so the queued reply/CQ was silently dropped. (First arg is `decoding`,
        // true throughout here — the normal case.)
        assert!(boundary_tx_ready(true, true, false, None, 4, -1, 0));
        // Slow decode still pending: must NOT key a (stale) message; handle_decoded
        // keys the fresh one when the decode lands.
        assert!(!boundary_tx_ready(true, true, true, None, 4, -1, 0));
        // Past the window, already transmitted this slot, wrong parity, or inactive all
        // block it too (it delegates to tx_slot_eligible).
        assert!(!boundary_tx_ready(true, true, false, None, 4, -1, TX_LATEST_MS + 1));
        assert!(!boundary_tx_ready(true, true, false, None, 4, 4, 0));
        assert!(!boundary_tx_ready(true, true, false, Some(1), 4, -1, 0)); // wants odd, slot 4 even
        assert!(!boundary_tx_ready(true, false, false, None, 4, -1, 0));
    }

    #[test]
    fn boundary_tx_is_dormant_while_decoding_is_stopped() {
        use super::boundary_tx_ready;
        // Regression for the StopDecode runaway-TX bug: after the operator stops
        // decoding, `pending_decodes` is reset to 0 (so the awaiting gate opens) but a
        // CQ/QSO left `active` keeps `qso.active` and `tx_parity` set. The per-tick
        // boundary trigger must stay dormant with the decoder off — otherwise the rig
        // keys every eligible slot with the receiver muted (transmitting unattended).
        // Every other input here is exactly the "would key" case from the test above;
        // only `decoding == false` must hold it back.
        assert!(!boundary_tx_ready(false, true, false, None, 4, -1, 0));
        assert!(!boundary_tx_ready(false, true, false, Some(0), 4, -1, 0));
        // Turning decoding back on restores keying (the fix changes nothing else).
        assert!(boundary_tx_ready(true, true, false, None, 4, -1, 0));
    }

    #[test]
    fn awaiting_gate_stays_closed_until_every_pending_slot_returns() {
        use super::awaiting_any_decode;
        // The gate is driven by the pending-decode count. Walk the "decoder falls
        // behind by a slot" sequence a single in-flight bool would get wrong: it
        // would clear on the first returned batch while a later slot is still out.
        let mut pending = 0usize; // idle: nothing outstanding, gate open.
        assert!(!awaiting_any_decode(pending));

        pending += 1; // slot A enqueued
        assert!(awaiting_any_decode(pending));
        pending += 1; // slot B enqueued before A came back (decoder behind)
        assert!(awaiting_any_decode(pending));

        pending = pending.saturating_sub(1); // A's batch returns...
        assert!(
            awaiting_any_decode(pending),
            "gate must stay closed while slot B is still decoding",
        );
        pending = pending.saturating_sub(1); // B's batch returns
        assert!(!awaiting_any_decode(pending));

        // A stray settle (e.g. a batch arriving after a StopDecode reset to 0)
        // can't underflow the count and reopen/close spuriously.
        pending = pending.saturating_sub(1);
        assert_eq!(pending, 0);
        assert!(!awaiting_any_decode(pending));
    }
}
