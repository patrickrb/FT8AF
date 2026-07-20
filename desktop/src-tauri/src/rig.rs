//! Rig control with pluggable backends:
//!   * **FLrig** — XML-RPC to a running FLrig instance (default 127.0.0.1:12345).
//!     FLrig owns the radio's CAT link and handles the rig-specific quirks +
//!     PTT, so other apps (loggers, FLrig itself) can share the radio. CAT only;
//!     audio still flows through cpal.
//!   * **Hamlib** — embedded via the dynamically-loaded shared library, with a
//!     per-model backend for every supported radio. This is the superset that
//!     replaced the old built-in serial CAT drivers.
//!
//! Both expose the same `RigTransport` interface; the engine doesn't care which.
//! Everything here is desktop-only and never touches the Android tree.

use serde::{Deserialize, Serialize};
use std::ffi::CString;
use std::os::raw::{c_char, c_int, c_long, c_void};
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RigBackend {
    /// No CAT — operator tunes manually; app only tracks the frequency.
    None,
    /// FLrig XML-RPC.
    Flrig,
    /// Hamlib, embedded via the dynamically-loaded shared library.
    Hamlib,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RigConfig {
    #[serde(default = "default_backend")]
    pub backend: RigBackend,

    // --- shared serial connection (Hamlib serial-type backends) ---
    #[serde(default)]
    pub port: String,
    #[serde(default = "default_baud")]
    pub baud: u32,

    // --- flrig backend ---
    #[serde(default = "default_flrig_host")]
    pub flrig_host: String,
    #[serde(default = "default_flrig_port")]
    pub flrig_port: u16,

    // --- hamlib backend ---
    /// Hamlib numeric rig model id (e.g. 1 = Dummy, run `rigctl -l` to find yours).
    #[serde(default = "default_hamlib_model")]
    pub hamlib_model: i32,
    /// true → connect over the network (host:port); false → serial (`port`+`baud`).
    /// Needed for network-type backends like the native FlexRadio 6xxx.
    #[serde(default)]
    pub hamlib_network: bool,
    /// Network address (host:port) when `hamlib_network` is set, e.g. localhost:4992.
    #[serde(default = "default_hamlib_address")]
    pub hamlib_address: String,
}

fn default_backend() -> RigBackend {
    RigBackend::None
}
fn default_baud() -> u32 {
    38400
}
fn default_flrig_host() -> String {
    "127.0.0.1".to_string()
}
fn default_flrig_port() -> u16 {
    12345
}
fn default_hamlib_model() -> i32 {
    1 // RIG_MODEL_DUMMY — works without hardware for testing
}
fn default_hamlib_address() -> String {
    "127.0.0.1:4992".to_string() // native Flex API; SmartSDR CAT TCP is often :5002
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SerialPortInfo {
    pub name: String,
    pub kind: String,
}

/// Enumerate available serial ports.
pub fn list_serial_ports() -> Vec<SerialPortInfo> {
    serialport::available_ports()
        .unwrap_or_default()
        .into_iter()
        .map(|p| {
            let kind = match p.port_type {
                serialport::SerialPortType::UsbPort(_) => "usb",
                serialport::SerialPortType::BluetoothPort => "bluetooth",
                serialport::SerialPortType::PciPort => "pci",
                serialport::SerialPortType::Unknown => "unknown",
            }
            .to_string();
            SerialPortInfo { name: p.port_name, kind }
        })
        .collect()
}

// ===========================================================================
// Transport abstraction
// ===========================================================================

trait RigTransport: Send {
    fn set_frequency(&mut self, hz: u64) -> anyhow::Result<()>;
    fn set_data_mode(&mut self) -> anyhow::Result<()>;
    fn set_ptt(&mut self, on: bool) -> anyhow::Result<()>;
    /// Human-readable radio model for the UI (e.g. "FlexRadio 6400"), not the
    /// backend name — resolved per-backend at connect time.
    fn name(&self) -> String;
    fn connected(&self) -> bool;
}

/// The engine's handle to whatever backend is configured.
pub struct RigConnection {
    inner: Box<dyn RigTransport>,
}

impl RigConnection {
    pub fn connect(cfg: &RigConfig) -> anyhow::Result<RigConnection> {
        let inner: Box<dyn RigTransport> = match cfg.backend {
            RigBackend::None => Box::new(NullTransport),
            RigBackend::Flrig => Box::new(Flrig::connect(cfg)),
            RigBackend::Hamlib => Box::new(Hamlib::connect(cfg)?),
        };
        let mut conn = RigConnection { inner };
        let _ = conn.set_ptt(false);
        let _ = conn.set_data_mode();
        Ok(conn)
    }

    pub fn set_frequency(&mut self, hz: u64) -> anyhow::Result<()> {
        self.inner.set_frequency(hz)
    }
    pub fn set_data_mode(&mut self) -> anyhow::Result<()> {
        self.inner.set_data_mode()
    }
    pub fn set_ptt(&mut self, on: bool) -> anyhow::Result<()> {
        self.inner.set_ptt(on)
    }
    pub fn name(&self) -> String {
        self.inner.name()
    }
    pub fn connected(&self) -> bool {
        self.inner.connected()
    }
}

// --- null backend -----------------------------------------------------------

struct NullTransport;
impl RigTransport for NullTransport {
    fn set_frequency(&mut self, _hz: u64) -> anyhow::Result<()> {
        Ok(())
    }
    fn set_data_mode(&mut self) -> anyhow::Result<()> {
        Ok(())
    }
    fn set_ptt(&mut self, _on: bool) -> anyhow::Result<()> {
        Ok(())
    }
    fn name(&self) -> String {
        "None".into()
    }
    fn connected(&self) -> bool {
        false
    }
}

// ===========================================================================
// FLrig XML-RPC backend
// ===========================================================================

struct Flrig {
    url: String,
    connected: bool,
    radio: String,
}

impl Flrig {
    fn connect(cfg: &RigConfig) -> Flrig {
        let url = format!("http://{}:{}/", cfg.flrig_host, cfg.flrig_port);
        let mut f = Flrig { url, connected: false, radio: "FLrig".to_string() };
        // Probe FLrig by reading the current VFO.
        f.connected = f.call("rig.get_vfo", "").is_ok();
        // Ask FLrig which transceiver it's driving so the UI can show the radio.
        if let Some(name) = f
            .call("rig.get_xcvr", "")
            .ok()
            .and_then(|r| parse_xmlrpc_string(&r))
        {
            f.radio = name;
        }
        f
    }

    /// Issue an XML-RPC call. `params_xml` is the inner `<param>…</param>` block
    /// (empty for no params). Returns the raw response body.
    fn call(&mut self, method: &str, params_xml: &str) -> anyhow::Result<String> {
        let body = format!(
            "<?xml version=\"1.0\"?><methodCall><methodName>{method}</methodName><params>{params_xml}</params></methodCall>"
        );
        let resp = ureq::post(&self.url)
            .set("Content-Type", "text/xml")
            .timeout(Duration::from_millis(800))
            .send_string(&body);
        match resp {
            Ok(r) => {
                self.connected = true;
                Ok(r.into_string()?)
            }
            Err(e) => {
                self.connected = false;
                Err(anyhow::anyhow!("FLrig XML-RPC: {e}"))
            }
        }
    }
}

/// Pull the text out of the first `<value>…</value>` of an XML-RPC response,
/// unwrapping an optional `<string>` element. Crude on purpose — FLrig's replies
/// are tiny and well-formed, and we only need scalar strings.
fn parse_xmlrpc_string(xml: &str) -> Option<String> {
    let start = xml.find("<value>")? + "<value>".len();
    let end = xml[start..].find("</value>")? + start;
    let mut s = xml[start..end].trim();
    if let Some(rest) = s.strip_prefix("<string>") {
        s = rest.strip_suffix("</string>").unwrap_or(rest).trim();
    }
    if s.is_empty() {
        None
    } else {
        Some(s.to_string())
    }
}

fn double_param(v: f64) -> String {
    format!("<param><value><double>{v}</double></value></param>")
}
fn int_param(v: i32) -> String {
    format!("<param><value><i4>{v}</i4></value></param>")
}

impl RigTransport for Flrig {
    fn set_frequency(&mut self, hz: u64) -> anyhow::Result<()> {
        self.call("rig.set_frequency", &double_param(hz as f64))?;
        Ok(())
    }
    fn set_data_mode(&mut self) -> anyhow::Result<()> {
        // Leave the mode to FLrig/the operator — forcing a mode string here would
        // fight per-rig naming (USB vs DATA-U vs PKT-U). FT8 only needs freq + PTT.
        Ok(())
    }
    fn set_ptt(&mut self, on: bool) -> anyhow::Result<()> {
        self.call("rig.set_ptt", &int_param(if on { 1 } else { 0 }))?;
        Ok(())
    }
    fn name(&self) -> String {
        self.radio.clone()
    }
    fn connected(&self) -> bool {
        self.connected
    }
}

// ===========================================================================
// Hamlib backend (embedded via dynamically-loaded shared library)
// ===========================================================================

// vfo_t value for "current VFO" (RIG_VFO_CURR = 1<<29 in hamlib/rig.h).
const RIG_VFO_CURR: c_int = 1 << 29;

// Subset of the Hamlib C API we use. freq_t is a C double; token_t/pbwidth_t are
// C long; vfo_t/ptt_t/model are int. RIG* is opaque.
#[allow(non_snake_case)]
struct HamlibFns {
    rig_init: unsafe extern "C" fn(c_int) -> *mut c_void,
    rig_open: unsafe extern "C" fn(*mut c_void) -> c_int,
    rig_close: unsafe extern "C" fn(*mut c_void) -> c_int,
    rig_cleanup: unsafe extern "C" fn(*mut c_void) -> c_int,
    rig_set_freq: unsafe extern "C" fn(*mut c_void, c_int, f64) -> c_int,
    rig_set_ptt: unsafe extern "C" fn(*mut c_void, c_int, c_int) -> c_int,
    rig_token_lookup: unsafe extern "C" fn(*mut c_void, *const c_char) -> c_long,
    rig_set_conf: unsafe extern "C" fn(*mut c_void, c_long, *const c_char) -> c_int,
}

struct Hamlib {
    _lib: libloading::Library, // kept alive for the life of the connection
    fns: HamlibFns,
    rig: *mut c_void,
    connected: bool,
    radio: String,
}

// The RIG* and loaded symbols are only ever touched from the engine thread.
unsafe impl Send for Hamlib {}

/// A Hamlib-supported rig for the UI picker.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HamlibRig {
    pub model: i32,
    pub name: String,
}

// Leading fields of `struct rig_caps` (stable across Hamlib 4.x): we only read
// these three, so the rest of the (large) struct layout is irrelevant.
#[repr(C)]
struct RigCapsHead {
    rig_model: c_int,
    model_name: *const c_char,
    mfg_name: *const c_char,
}

fn cstr(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { std::ffi::CStr::from_ptr(p) }
        .to_string_lossy()
        .into_owned()
}

extern "C" fn collect_rig(caps: *const RigCapsHead, data: *mut c_void) -> c_int {
    if caps.is_null() || data.is_null() {
        return 1; // keep iterating
    }
    let list = unsafe { &mut *(data as *mut Vec<HamlibRig>) };
    let c = unsafe { &*caps };
    let mfg = cstr(c.mfg_name);
    let model_name = cstr(c.model_name);
    let name = format!("{mfg} {model_name}").trim().to_string();
    list.push(HamlibRig { model: c.rig_model, name });
    1
}

/// Human name for a Hamlib model id (e.g. "FlexRadio 6400"), via the enumerated
/// rig list. Falls back to a generic label if it can't be resolved.
fn hamlib_model_name(model: i32) -> String {
    list_hamlib_rigs()
        .into_iter()
        .find(|r| r.model == model)
        .map(|r| r.name)
        .unwrap_or_else(|| format!("Hamlib #{model}"))
}

/// Enumerate every rig the installed Hamlib supports (for the Settings picker).
/// Returns empty if the Hamlib library can't be loaded.
pub fn list_hamlib_rigs() -> Vec<HamlibRig> {
    let lib = match load_hamlib() {
        Ok(l) => l,
        Err(_) => return Vec::new(),
    };
    let mut rigs: Vec<HamlibRig> = Vec::new();
    unsafe {
        // Ensure all backend modules are loaded before enumerating.
        if let Ok(load_all) = lib.get::<unsafe extern "C" fn() -> c_int>(b"rig_load_all_backends\0") {
            (load_all)();
        }
        let foreach: libloading::Symbol<
            unsafe extern "C" fn(
                extern "C" fn(*const RigCapsHead, *mut c_void) -> c_int,
                *mut c_void,
            ) -> c_int,
        > = match lib.get(b"rig_list_foreach\0") {
            Ok(f) => f,
            Err(_) => return Vec::new(),
        };
        (foreach)(collect_rig, &mut rigs as *mut _ as *mut c_void);
    }
    rigs.sort_by_key(|r| r.model);
    rigs.dedup_by_key(|r| r.model);
    rigs.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    rigs
}

fn load_hamlib() -> anyhow::Result<libloading::Library> {
    // Try the common shared-library names across platforms; the OS loader
    // resolves them against PATH / the app directory / system paths.
    let candidates = [
        "hamlib-4.dll",
        "libhamlib-4.dll",
        "libhamlib.dll",
        "libhamlib.so.4",
        "libhamlib.so",
        "libhamlib.4.dylib",
        "libhamlib.dylib",
    ];
    for name in candidates {
        if let Ok(lib) = unsafe { libloading::Library::new(name) } {
            return Ok(lib);
        }
    }
    anyhow::bail!(
        "could not load the Hamlib library (hamlib-4.dll / libhamlib). \
         Install Hamlib and place its DLLs next to the app or on PATH."
    )
}

impl Hamlib {
    fn connect(cfg: &RigConfig) -> anyhow::Result<Hamlib> {
        let lib = load_hamlib()?;
        // Resolve the symbols we need.
        let fns = unsafe {
            HamlibFns {
                rig_init: *lib.get(b"rig_init\0")?,
                rig_open: *lib.get(b"rig_open\0")?,
                rig_close: *lib.get(b"rig_close\0")?,
                rig_cleanup: *lib.get(b"rig_cleanup\0")?,
                rig_set_freq: *lib.get(b"rig_set_freq\0")?,
                rig_set_ptt: *lib.get(b"rig_set_ptt\0")?,
                rig_token_lookup: *lib.get(b"rig_token_lookup\0")?,
                rig_set_conf: *lib.get(b"rig_set_conf\0")?,
            }
        };

        let rig = unsafe { (fns.rig_init)(cfg.hamlib_model) };
        if rig.is_null() {
            anyhow::bail!("rig_init failed for Hamlib model {}", cfg.hamlib_model);
        }

        // Configure the connection via the conf interface (avoids depending on
        // the RIG struct layout). Network-type backends (e.g. native Flex 6xxx)
        // take host:port in rig_pathname; serial backends take the COM port +
        // speed. Skipped entirely for e.g. model 1 (Dummy).
        if cfg.hamlib_network {
            if !cfg.hamlib_address.is_empty() {
                // Force IPv4: Hamlib resolves "localhost" to ::1 (IPv6) first, but
                // many CAT servers (e.g. SmartSDR CAT) listen only on 0.0.0.0,
                // so the IPv6 attempt is refused. Rewriting to 127.0.0.1 avoids it.
                let addr = cfg.hamlib_address.replacen("localhost", "127.0.0.1", 1);
                set_conf(&fns, rig, "rig_pathname", &addr);
            }
        } else if !cfg.port.is_empty() {
            set_conf(&fns, rig, "rig_pathname", &cfg.port);
            set_conf(&fns, rig, "serial_speed", &cfg.baud.to_string());
        }

        let rc = unsafe { (fns.rig_open)(rig) };
        if rc != 0 {
            unsafe { (fns.rig_cleanup)(rig) };
            anyhow::bail!("rig_open failed (Hamlib error {rc})");
        }

        let radio = hamlib_model_name(cfg.hamlib_model);
        Ok(Hamlib { _lib: lib, fns, rig, connected: true, radio })
    }
}

fn set_conf(fns: &HamlibFns, rig: *mut c_void, name: &str, value: &str) {
    let cname = match CString::new(name) {
        Ok(c) => c,
        Err(_) => return,
    };
    let cval = match CString::new(value) {
        Ok(c) => c,
        Err(_) => return,
    };
    unsafe {
        let token = (fns.rig_token_lookup)(rig, cname.as_ptr());
        (fns.rig_set_conf)(rig, token, cval.as_ptr());
    }
}

impl RigTransport for Hamlib {
    fn set_frequency(&mut self, hz: u64) -> anyhow::Result<()> {
        let rc = unsafe { (self.fns.rig_set_freq)(self.rig, RIG_VFO_CURR, hz as f64) };
        if rc != 0 {
            self.connected = false;
            anyhow::bail!("rig_set_freq failed (Hamlib error {rc})");
        }
        Ok(())
    }
    fn set_data_mode(&mut self) -> anyhow::Result<()> {
        // Leave the mode to the operator/Hamlib config; FT8 needs only freq + PTT.
        Ok(())
    }
    fn set_ptt(&mut self, on: bool) -> anyhow::Result<()> {
        let rc = unsafe { (self.fns.rig_set_ptt)(self.rig, RIG_VFO_CURR, if on { 1 } else { 0 }) };
        if rc != 0 {
            self.connected = false;
            anyhow::bail!("rig_set_ptt failed (Hamlib error {rc})");
        }
        Ok(())
    }
    fn name(&self) -> String {
        self.radio.clone()
    }
    fn connected(&self) -> bool {
        self.connected
    }
}

impl Drop for Hamlib {
    fn drop(&mut self) {
        if !self.rig.is_null() {
            unsafe {
                (self.fns.rig_close)(self.rig);
                (self.fns.rig_cleanup)(self.rig);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A fresh install must default to manual tuning ("None"), not a backend
    /// that immediately tries to load a shared library that may be absent.
    #[test]
    fn default_backend_is_none() {
        assert_eq!(default_backend(), RigBackend::None);
        let cfg: RigConfig = serde_json::from_str("{}").unwrap();
        assert_eq!(cfg.backend, RigBackend::None);
    }

    /// The removed serial backend is no longer a valid selector value; only
    /// none / flrig / hamlib deserialize.
    #[test]
    fn backend_serde_roundtrip() {
        for (json, backend) in [
            ("\"none\"", RigBackend::None),
            ("\"flrig\"", RigBackend::Flrig),
            ("\"hamlib\"", RigBackend::Hamlib),
        ] {
            let b: RigBackend = serde_json::from_str(json).unwrap();
            assert_eq!(b, backend);
            assert_eq!(serde_json::to_string(&b).unwrap(), json);
        }
        assert!(serde_json::from_str::<RigBackend>("\"serial\"").is_err());
    }

    /// A config persisted by an older build (with the now-removed serial fields
    /// `model`/`ptt`/`civ_address`) must still deserialize — serde ignores the
    /// extra keys and applies defaults for anything missing.
    #[test]
    fn legacy_serial_config_still_deserializes() {
        let legacy = r#"{
            "backend": "hamlib",
            "model": "icom",
            "port": "/dev/ttyUSB0",
            "baud": 9600,
            "ptt": "rts",
            "civ_address": 148,
            "hamlib_model": 3073
        }"#;
        let cfg: RigConfig = serde_json::from_str(legacy).unwrap();
        assert_eq!(cfg.backend, RigBackend::Hamlib);
        assert_eq!(cfg.port, "/dev/ttyUSB0");
        assert_eq!(cfg.baud, 9600);
        assert_eq!(cfg.hamlib_model, 3073);
    }

    #[test]
    fn flrig_xmlrpc_params() {
        assert_eq!(
            double_param(14_074_000.0),
            "<param><value><double>14074000</double></value></param>"
        );
        assert_eq!(int_param(1), "<param><value><i4>1</i4></value></param>");
    }
}
