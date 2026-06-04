//! The runtime engine: a dedicated thread that owns audio + decoder + QSO state
//! machine + rig, runs the 15 s UTC cycle (ports `UtcTimer`), and turns QSO
//! decisions into encode + PTT + playback + DB writes. Communicates with the
//! Tauri layer over command/event channels so the cpal `Stream`s (which are
//! `!Send` on Windows) never leave this thread.

use std::sync::mpsc::{Receiver, Sender, TryRecvError};
use std::sync::Arc;
use std::time::Duration;

use rustfft::num_complex::Complex;
use rustfft::{Fft, FftPlanner};
use serde::{Deserialize, Serialize};

use crate::audio::{output, AudioInput, SlotAccumulator};
use crate::bands;
use crate::db::{Db, QsoRecord};
use crate::dsp::{DecodedMessage, Decoder, SAMPLE_RATE};
use crate::qso::{QsoEngine, QsoOutcome, QsoStatus};
use crate::rig::{RigConfig, RigConnection};
use crate::util;

const CYCLE_MS: i64 = 15_000;
const TX_SLACK_MS: i64 = 2_360; // 12.64 s audio in a 15 s slot
const PTT_DELAY_MS: u64 = 100;
const DEFAULT_TX_AUDIO_HZ: i32 = 1_500;
const WF_FFT_SIZE: usize = 2048; // ~5.86 Hz/bin at 12 kHz
const WF_AVG: usize = 6; // averaged FFT segments per row (Welch) to smooth the noise floor
const WF_STEP: usize = WF_FFT_SIZE / 2; // 50% overlap between averaged segments
const WF_MAX_HZ: f32 = 3_500.0; // displayed top of the audio band (matches decoder f_max)
const WF_SPAN_DB: f32 = 26.0; // dB above the black point that maps to full brightness
const WF_BLACK_OFFSET_DB: f32 = 4.0; // push the black point above the noise floor so noise stays dark

// --- messages crossing the channel boundary --------------------------------

#[derive(Debug, Clone, Deserialize)]
pub struct AnswerArgs {
    pub call_from: String,
    #[serde(default)]
    pub grid: String,
    #[serde(default)]
    pub snr: i32,
}

#[derive(Debug, Clone)]
pub enum EngineCommand {
    StartDecode,
    StopDecode,
    SetStation { call: String, grid: String },
    SetBand(u64),
    SetBaseFreq(i32),
    SetInputDevice(Option<String>),
    SetOutputDevice(Option<String>),
    SelectRig(RigConfig),
    StartCq,
    Answer(AnswerArgs),
    StopTx,
    FreeText(String),
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
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", content = "data", rename_all = "snake_case")]
pub enum EngineEvent {
    Decoded(Vec<UiMessage>),
    Cycle(CycleTick),
    TxState(TxStateEvent),
    RigStatus(RigStatusEvent),
    QsoCompleted(QsoRecord),
    Waterfall(WaterfallFrame),
    WaterfallRow(WaterfallRow),
    Info(String),
    Error(String),
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
    std::thread::Builder::new()
        .name("ft8af-engine".into())
        .spawn(move || {
            let mut engine = Engine::new(db, evt_tx);
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
    last_slot_id: i64,
    last_tick_ms: i64,
    tx_parity: Option<i64>,
    clock_offset_ms: i64,
    ptt: bool,
    // live waterfall FFT
    fft: Arc<dyn Fft<f32>>,
    hann: Vec<f32>,
    wf_floor_db: f32, // smoothed noise-floor estimate for auto color scaling
}

impl Engine {
    fn new(db: Arc<Db>, evt: Sender<EngineEvent>) -> Self {
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
        let mut qso = QsoEngine::new(my_call, my_grid);
        qso.band = bands::band_for_freq_hz(dial_hz).to_string();
        qso.freq_mhz = bands::freq_mhz_string(dial_hz);

        let fft = FftPlanner::<f32>::new().plan_fft_forward(WF_FFT_SIZE);
        let hann: Vec<f32> = (0..WF_FFT_SIZE)
            .map(|i| {
                0.5 - 0.5 * (2.0 * std::f32::consts::PI * i as f32 / WF_FFT_SIZE as f32).cos()
            })
            .collect();

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
            last_slot_id: -1,
            last_tick_ms: 0,
            tx_parity: None,
            clock_offset_ms: 0,
            ptt: false,
            fft,
            hann,
            wf_floor_db: -60.0,
        }
    }

    fn now(&self) -> i64 {
        util::now_unix_ms() + self.clock_offset_ms
    }

    fn run(&mut self, cmd_rx: Receiver<EngineCommand>) {
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
            }

            if slot_id != self.last_slot_id {
                self.last_slot_id = slot_id;
                self.on_slot_boundary(slot_id);
            }

            // Act on any decodes the worker has finished (off-loop DSP).
            while let Ok(msgs) = self.dec_rx.try_recv() {
                self.handle_decoded(msgs, slot_id);
            }

            // Live scrolling waterfall: one spectrum row per ~100 ms tick.
            if self.decoding {
                self.emit_waterfall_row();
            }

            std::thread::sleep(Duration::from_millis(100));
        }
    }

    fn on_slot_boundary(&mut self, _entering_slot: i64) {
        // Hand the slot that just ended to the decode worker; decoding happens
        // off this loop, so the waterfall + clock keep updating. The worker's
        // results are consumed in the run loop (see handle_decoded), which then
        // drives QSO sequencing + TX for the slot we're now in.
        if self.decoding {
            let slot = self.accum.take_slot();
            let _ = self.slot_tx.send(slot);
        }
    }

    /// Handle one slot's decodes (from the worker): publish them, advance the QSO
    /// state machine, and transmit if it's our turn this slot.
    fn handle_decoded(&mut self, decoded: Vec<DecodedMessage>, slot_id: i64) {
        self.publish_decodes(&decoded);

        if let Some(QsoOutcome::Completed(record)) = self.qso.process_rx(&decoded) {
            match self.db.insert_qso(&record) {
                Ok(_) => self.emit(EngineEvent::QsoCompleted(record)),
                Err(e) => self.emit(EngineEvent::Error(format!("log QSO failed: {e}"))),
            }
        }

        if self.qso.active {
            if self.tx_parity.is_none() {
                self.tx_parity = Some(slot_id.rem_euclid(2));
            }
            let my_turn = Some(slot_id.rem_euclid(2)) == self.tx_parity;
            if my_turn && self.txed_slot != slot_id {
                self.txed_slot = slot_id;
                if let Some(msg) = self.qso.tx_message().map(|s| s.to_string()) {
                    self.transmit(&msg);
                }
            }
        }
        self.publish_tx_state(false);
    }

    fn transmit(&mut self, message: &str) {
        let signal = match crate::dsp::encode::generate_ft8(message, self.tx_audio_hz as f32, SAMPLE_RATE) {
            Some(s) => s,
            None => {
                self.emit(EngineEvent::Error(format!("cannot encode '{message}'")));
                return;
            }
        };

        self.set_ptt(true);
        self.publish_tx_state(true);
        std::thread::sleep(Duration::from_millis(PTT_DELAY_MS));

        // Clip leading audio only if we'd overrun the cycle (CLAUDE.md gotcha:
        // ms_late = max(0, into_cycle - 2360), NOT into_cycle % 15000).
        let into_cycle = self.now().rem_euclid(CYCLE_MS);
        let ms_late = (into_cycle - TX_SLACK_MS).max(0);
        let skip = (ms_late * SAMPLE_RATE as i64 / 1000) as usize;
        let clipped = if skip < signal.len() { &signal[skip..] } else { &[] };

        if let Err(e) = output::play_blocking(self.output_device.as_deref(), clipped, 0.9) {
            self.emit(EngineEvent::Error(format!("playback failed: {e}")));
        }

        self.set_ptt(false);
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
        // Average WF_AVG overlapping FFTs (Welch) to cut the per-bin variance of
        // a single FFT, so the noise floor reads as a smooth blue instead of
        // speckled yellow.
        let need = WF_FFT_SIZE + (WF_AVG - 1) * WF_STEP;
        let samples = self.accum.peek_recent(need);
        if samples.len() < need {
            return; // still warming up
        }

        let bin_hz = SAMPLE_RATE as f32 / WF_FFT_SIZE as f32;
        let cols = ((WF_MAX_HZ / bin_hz) as usize).min(WF_FFT_SIZE / 2).max(1);

        let mut power = vec![0f32; cols];
        let mut buf: Vec<Complex<f32>> = vec![Complex { re: 0.0, im: 0.0 }; WF_FFT_SIZE];
        for seg in 0..WF_AVG {
            let off = seg * WF_STEP;
            for i in 0..WF_FFT_SIZE {
                buf[i] = Complex { re: samples[off + i] * self.hann[i], im: 0.0 };
            }
            self.fft.process(&mut buf);
            for (i, p) in power.iter_mut().enumerate() {
                *p += buf[i].re * buf[i].re + buf[i].im * buf[i].im;
            }
        }

        // Averaged power -> dB magnitude per bin.
        let mut dbs = vec![0f32; cols];
        for (i, &p) in power.iter().enumerate() {
            let avg = p / WF_AVG as f32;
            let mag = (avg.sqrt() * 2.0) / WF_FFT_SIZE as f32;
            dbs[i] = 20.0 * (mag + 1e-12).log10();
        }

        // Estimate the noise floor from the 30th-percentile dB (robust even on a
        // busy band where signals fill much of the spectrum), smoothed over time.
        let mut sorted = dbs.clone();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        let pct = sorted[((cols as f32 * 0.30) as usize).min(cols - 1)];
        self.wf_floor_db = self.wf_floor_db * 0.9 + pct * 0.1;

        // Black point sits a few dB above the floor so the noise renders dark and
        // only signals above it take on color.
        let black_point = self.wf_floor_db + WF_BLACK_OFFSET_DB;
        let mut bins = vec![0u8; cols];
        for (i, &db) in dbs.iter().enumerate() {
            let t = ((db - black_point) / WF_SPAN_DB).clamp(0.0, 1.0);
            bins[i] = (t * 255.0) as u8;
        }
        self.emit(EngineEvent::WaterfallRow(WaterfallRow { bins, hz_per_col: bin_hz }));
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
                text: if m.raw_text.is_empty() {
                    format!("{} {} {}", m.call_to, m.call_from, m.extra)
                } else {
                    m.raw_text.clone()
                },
                is_cq: m.call_to.eq_ignore_ascii_case("CQ"),
                to_me: !self.qso.my_call.is_empty()
                    && m.call_to.eq_ignore_ascii_case(&self.qso.my_call),
            })
            .collect();
        self.emit(EngineEvent::Decoded(ui));
    }

    fn publish_tx_state(&self, transmitting: bool) {
        self.emit(EngineEvent::TxState(TxStateEvent {
            transmitting,
            message: self.qso.tx_message().map(|s| s.to_string()),
            status: self.qso.status(),
        }));
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

    fn emit(&self, e: EngineEvent) {
        let _ = self.evt.send(e);
    }

    fn handle(&mut self, cmd: EngineCommand) {
        match cmd {
            EngineCommand::StartDecode => self.start_decode(),
            EngineCommand::StopDecode => {
                self.decoding = false;
                self.input = None;
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
                self.publish_rig_status();
            }
            EngineCommand::SetBaseFreq(hz) => {
                self.tx_audio_hz = hz.clamp(200, 3000);
                let _ = self.db.set_config("base_freq", &self.tx_audio_hz.to_string());
            }
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
            EngineCommand::StartCq => {
                self.tx_parity = None;
                self.qso.start_cq();
                self.publish_tx_state(false);
            }
            EngineCommand::Answer(args) => {
                let msg = DecodedMessage {
                    call_from: args.call_from,
                    grid: args.grid,
                    snr: args.snr,
                    ..Default::default()
                };
                self.tx_parity = None;
                self.qso.answer(&msg);
                self.publish_tx_state(false);
            }
            EngineCommand::StopTx => {
                self.qso.stop();
                self.tx_parity = None;
                self.publish_tx_state(false);
            }
            EngineCommand::FreeText(text) => {
                self.tx_parity = None;
                self.qso.set_free_text(&text);
                self.publish_tx_state(false);
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
                self.rig = Some(rig);
                self.emit(EngineEvent::Info(format!("rig connected: {:?}", cfg.model)));
            }
            Err(e) => {
                self.rig = None;
                self.emit(EngineEvent::Error(format!("rig connect failed: {e}")));
            }
        }
        self.publish_rig_status();
    }
}
