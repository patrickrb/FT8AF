// FT8AF desktop — Tauri entry point. Owns the SQLite DB and the runtime engine,
// exposes commands to the web UI, and forwards engine events to the webview.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::sync::Arc;

use serde::Serialize;
use tauri::{Emitter, State};

use ft8af::audio::{self, AudioDevice};
use ft8af::bands;
use ft8af::db::{Db, QsoRecord};
use ft8af::engine::{self, AnswerArgs, EngineCommand, EngineHandle};
use ft8af::os_location::{self, OsLocation};
use ft8af::rig::{self, HamlibRig, RigConfig, SerialPortInfo};
use ft8af::wf::WfConfig;

struct AppState {
    engine: EngineHandle,
    db: Arc<Db>,
}

#[derive(Serialize)]
struct BandInfo {
    name: String,
    dial_hz: u64,
}

// --- device / port / band enumeration --------------------------------------

#[tauri::command]
fn list_audio_inputs() -> Vec<AudioDevice> {
    audio::list_input_devices()
}

#[tauri::command]
fn list_audio_outputs() -> Vec<AudioDevice> {
    audio::list_output_devices()
}

#[tauri::command]
fn list_serial_ports() -> Vec<SerialPortInfo> {
    rig::list_serial_ports()
}

#[tauri::command]
fn list_hamlib_rigs() -> Vec<HamlibRig> {
    rig::list_hamlib_rigs()
}

#[tauri::command]
fn list_bands() -> Vec<BandInfo> {
    bands::FT8_BANDS
        .iter()
        .map(|b| BandInfo { name: b.name.to_string(), dial_hz: b.dial_hz })
        .collect()
}

// --- engine control ---------------------------------------------------------

#[tauri::command]
fn start_decode(state: State<AppState>) {
    state.engine.send(EngineCommand::StartDecode);
}

#[tauri::command]
fn stop_decode(state: State<AppState>) {
    state.engine.send(EngineCommand::StopDecode);
}

#[tauri::command]
fn set_station(state: State<AppState>, call: String, grid: String) {
    state.engine.send(EngineCommand::SetStation { call, grid });
}

#[tauri::command]
fn set_band(state: State<AppState>, dial_hz: u64) {
    state.engine.send(EngineCommand::SetBand(dial_hz));
}

#[tauri::command]
fn set_base_freq(state: State<AppState>, hz: i32) {
    state.engine.send(EngineCommand::SetBaseFreq(hz));
}

#[tauri::command]
fn set_tx_gain(state: State<AppState>, gain: f32) {
    state.engine.send(EngineCommand::SetTxGain(gain));
}

#[tauri::command]
fn set_input_device(state: State<AppState>, name: Option<String>) {
    state.engine.send(EngineCommand::SetInputDevice(name));
}

#[tauri::command]
fn set_output_device(state: State<AppState>, name: Option<String>) {
    state.engine.send(EngineCommand::SetOutputDevice(name));
}

#[tauri::command]
fn select_rig(state: State<AppState>, config: RigConfig) {
    state.engine.send(EngineCommand::SelectRig(config));
}

#[tauri::command]
fn disconnect_rig(state: State<AppState>) {
    state.engine.send(EngineCommand::DisconnectRig);
}

#[tauri::command]
fn refresh_status(state: State<AppState>) {
    state.engine.send(EngineCommand::RefreshStatus);
}

#[tauri::command]
fn resync_time(state: State<AppState>) {
    state.engine.send(EngineCommand::ResyncTime);
}

#[tauri::command]
fn start_cq(state: State<AppState>) {
    state.engine.send(EngineCommand::StartCq);
}

#[tauri::command]
fn answer(state: State<AppState>, args: AnswerArgs) {
    state.engine.send(EngineCommand::Answer(args));
}

#[tauri::command]
fn set_stage(state: State<AppState>, stage: ft8af::qso::TxStage) {
    state.engine.send(EngineCommand::SetStage(stage));
}

#[tauri::command]
fn stop_tx(state: State<AppState>) {
    state.engine.send(EngineCommand::StopTx);
}

#[tauri::command]
fn free_text(state: State<AppState>, text: String) {
    state.engine.send(EngineCommand::FreeText(text));
}

// --- logbook / config -------------------------------------------------------

#[tauri::command]
fn list_log(state: State<AppState>, limit: i64, offset: i64) -> Vec<QsoRecord> {
    state.db.list_qsos(limit, offset)
}

#[tauri::command]
fn log_count(state: State<AppState>) -> i64 {
    state.db.count()
}

#[tauri::command]
fn delete_qso(state: State<AppState>, id: i64) -> Result<(), String> {
    state.db.delete_qso(id).map_err(|e| e.to_string())
}

#[tauri::command]
fn save_qso(state: State<AppState>, record: QsoRecord) -> Result<i64, String> {
    state.db.insert_qso(&record).map_err(|e| e.to_string())
}

#[tauri::command]
fn export_adif(state: State<AppState>) -> String {
    state.db.export_adif()
}

#[tauri::command]
fn get_config(state: State<AppState>, key: String) -> Option<String> {
    state.db.get_config(&key)
}

#[tauri::command]
fn set_config(state: State<AppState>, key: String, value: String) -> Result<(), String> {
    state.db.set_config(&key, &value).map_err(|e| e.to_string())
}

#[tauri::command]
fn all_config(state: State<AppState>) -> Vec<(String, String)> {
    state.db.all_config()
}

// --- optional OS location -> grid (issue #471) ------------------------------

/// Resolve the operator grid from the OS location service. Opt-in only: refuses
/// unless the user has turned on the `location_service_enabled` flag, so nothing
/// requests location (and no permission prompt appears) until they explicitly
/// opt in. Returns lat/lon + the derived Maidenhead grid, or a human-readable
/// error the UI shows without touching the manual field.
#[tauri::command]
fn get_os_location(state: State<AppState>) -> Result<OsLocation, String> {
    let enabled = os_location::location_enabled(
        state
            .db
            .get_config(os_location::LOCATION_ENABLED_KEY)
            .as_deref(),
    );
    if !enabled {
        return Err(
            "Location service is off. Turn on \"Use OS location service\" in \
             Settings → Station first."
                .into(),
        );
    }
    os_location::resolve()
}

#[tauri::command]
fn set_waterfall_config(state: State<AppState>, config: WfConfig) {
    // The engine sanitizes, persists (wf_window/wf_fft_size/wf_avg), and
    // rebuilds its FFT plan; see EngineCommand::SetWaterfallConfig.
    state.engine.send(EngineCommand::SetWaterfallConfig(config));
}

fn main() {
    // Debug helper: `ft8af --list-rigs` prints the Hamlib-enumerated rig count
    // and exits — verifies the bundled Hamlib library loads without the GUI.
    if std::env::args().any(|a| a == "--list-rigs") {
        let rigs = rig::list_hamlib_rigs();
        println!("Hamlib enumerated {} rigs", rigs.len());
        for r in rigs.iter() {
            println!("  #{:<6} {}", r.model, r.name);
        }
        return;
    }

    let data_dir = dirs::data_dir()
        .unwrap_or_else(std::env::temp_dir)
        .join("FT8AF");
    let _ = std::fs::create_dir_all(&data_dir);
    let db = Arc::new(
        Db::open(data_dir.join("ft8af.sqlite")).expect("failed to open database"),
    );

    let (engine, evt_rx) = engine::spawn(db.clone());

    tauri::Builder::default()
        .manage(AppState { engine, db })
        .setup(move |app| {
            // Forward engine events to the webview on the "engine-event" channel.
            let handle = app.handle().clone();
            std::thread::Builder::new()
                .name("ft8af-event-forwarder".into())
                .spawn(move || {
                    while let Ok(ev) = evt_rx.recv() {
                        // Mirror status messages to the terminal. The app has no
                        // logger, so audio/rig/decode problems (e.g. "audio start
                        // failed") were previously invisible outside the in-app
                        // status line — making remote diagnosis impossible.
                        match &ev {
                            engine::EngineEvent::Error(m) => eprintln!("[ft8af] ERROR: {m}"),
                            engine::EngineEvent::Info(m) => eprintln!("[ft8af] {m}"),
                            _ => {}
                        }
                        let _ = handle.emit("engine-event", ev);
                    }
                })?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            list_audio_inputs,
            list_audio_outputs,
            list_serial_ports,
            list_hamlib_rigs,
            list_bands,
            start_decode,
            stop_decode,
            set_station,
            set_band,
            set_base_freq,
            set_tx_gain,
            set_input_device,
            set_output_device,
            select_rig,
            disconnect_rig,
            refresh_status,
            resync_time,
            start_cq,
            answer,
            set_stage,
            stop_tx,
            free_text,
            list_log,
            log_count,
            delete_qso,
            save_qso,
            export_adif,
            get_config,
            set_config,
            all_config,
            set_waterfall_config,
            get_os_location,
        ])
        .run(tauri::generate_context!())
        .expect("error running FT8AF");
}
