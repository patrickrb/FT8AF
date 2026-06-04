//! Rig control with pluggable backends:
//!   * **Serial CAT** — direct serial drivers ported from the Android `rigs/`
//!     code (Yaesu CAT, Kenwood ASCII, Icom CI-V). Write-only (no reader loop),
//!     which is also the safe FT-710 behavior.
//!   * **FLrig** — XML-RPC to a running FLrig instance (default 127.0.0.1:12345).
//!     FLrig owns the radio's CAT link and handles the rig-specific quirks +
//!     PTT, so other apps (loggers, FLrig itself) can share the radio. CAT only;
//!     audio still flows through cpal.
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
    /// Direct serial CAT (built-in drivers).
    Serial,
    /// FLrig XML-RPC.
    Flrig,
    /// Hamlib, embedded via the dynamically-loaded shared library.
    Hamlib,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RigModel {
    Yaesu,
    Kenwood,
    Icom,
    None,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PttMethod {
    Cat,
    Rts,
    Dtr,
    None,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RigConfig {
    #[serde(default = "default_backend")]
    pub backend: RigBackend,

    // --- serial backend ---
    #[serde(default = "default_model")]
    pub model: RigModel,
    #[serde(default)]
    pub port: String,
    #[serde(default = "default_baud")]
    pub baud: u32,
    #[serde(default = "default_ptt")]
    pub ptt: PttMethod,
    /// Icom CI-V radio address (default 0x94 = IC-7300).
    #[serde(default = "default_civ_addr")]
    pub civ_address: u8,

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
    RigBackend::Hamlib
}
fn default_model() -> RigModel {
    RigModel::None
}
fn default_baud() -> u32 {
    38400
}
fn default_ptt() -> PttMethod {
    PttMethod::Cat
}
fn default_civ_addr() -> u8 {
    0x94
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
            RigBackend::Serial => Box::new(SerialCat::connect(cfg)?),
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
// Serial CAT backend
// ===========================================================================

trait Driver: Send {
    fn freq_cmd(&self, hz: u64) -> Vec<u8>;
    fn data_mode_cmd(&self) -> Vec<u8>;
    fn ptt_cmd(&self, on: bool) -> Vec<u8>;
}

struct Yaesu;
impl Driver for Yaesu {
    fn freq_cmd(&self, hz: u64) -> Vec<u8> {
        format!("FA{:09};", hz).into_bytes()
    }
    fn data_mode_cmd(&self) -> Vec<u8> {
        b"MD0C;NA00;SH0117;".to_vec()
    }
    fn ptt_cmd(&self, on: bool) -> Vec<u8> {
        if on { b"TX1;".to_vec() } else { b"TX0;".to_vec() }
    }
}

struct Kenwood;
impl Driver for Kenwood {
    fn freq_cmd(&self, hz: u64) -> Vec<u8> {
        format!("FA{:011};", hz).into_bytes()
    }
    fn data_mode_cmd(&self) -> Vec<u8> {
        b"MD2;".to_vec()
    }
    fn ptt_cmd(&self, on: bool) -> Vec<u8> {
        if on { b"TX;".to_vec() } else { b"RX;".to_vec() }
    }
}

struct Icom {
    addr: u8,
}
impl Icom {
    fn frame(&self, body: &[u8]) -> Vec<u8> {
        let mut v = vec![0xFE, 0xFE, self.addr, 0xE0];
        v.extend_from_slice(body);
        v.push(0xFD);
        v
    }
    fn freq_bcd(hz: u64) -> [u8; 5] {
        let mut digits = [0u8; 10];
        let mut f = hz;
        for d in digits.iter_mut() {
            *d = (f % 10) as u8;
            f /= 10;
        }
        let mut out = [0u8; 5];
        for i in 0..5 {
            out[i] = digits[2 * i] | (digits[2 * i + 1] << 4);
        }
        out
    }
}
impl Driver for Icom {
    fn freq_cmd(&self, hz: u64) -> Vec<u8> {
        let mut body = vec![0x05];
        body.extend_from_slice(&Self::freq_bcd(hz));
        self.frame(&body)
    }
    fn data_mode_cmd(&self) -> Vec<u8> {
        self.frame(&[0x26, 0x00, 0x01, 0x01, 0x01])
    }
    fn ptt_cmd(&self, on: bool) -> Vec<u8> {
        self.frame(&[0x1C, 0x00, if on { 0x01 } else { 0x00 }])
    }
}

fn driver_for(model: RigModel, civ_address: u8) -> Option<Box<dyn Driver>> {
    match model {
        RigModel::Yaesu => Some(Box::new(Yaesu)),
        RigModel::Kenwood => Some(Box::new(Kenwood)),
        RigModel::Icom => Some(Box::new(Icom { addr: civ_address })),
        RigModel::None => None,
    }
}

struct SerialCat {
    port: Box<dyn serialport::SerialPort>,
    driver: Box<dyn Driver>,
    ptt: PttMethod,
    model: RigModel,
}

impl SerialCat {
    fn connect(cfg: &RigConfig) -> anyhow::Result<SerialCat> {
        let driver = driver_for(cfg.model, cfg.civ_address)
            .ok_or_else(|| anyhow::anyhow!("select a rig model for serial CAT"))?;
        let port = serialport::new(&cfg.port, cfg.baud)
            .timeout(Duration::from_millis(200))
            .open()?;
        Ok(SerialCat { port, driver, ptt: cfg.ptt, model: cfg.model })
    }

    fn send(&mut self, bytes: &[u8]) -> anyhow::Result<()> {
        self.port.write_all(bytes)?;
        self.port.flush()?;
        Ok(())
    }
}

impl RigTransport for SerialCat {
    fn set_frequency(&mut self, hz: u64) -> anyhow::Result<()> {
        let cmd = self.driver.freq_cmd(hz);
        self.send(&cmd)
    }
    fn set_data_mode(&mut self) -> anyhow::Result<()> {
        let cmd = self.driver.data_mode_cmd();
        self.send(&cmd)
    }
    fn set_ptt(&mut self, on: bool) -> anyhow::Result<()> {
        match self.ptt {
            PttMethod::Cat => {
                let cmd = self.driver.ptt_cmd(on);
                self.send(&cmd)
            }
            PttMethod::Rts => Ok(self.port.write_request_to_send(on)?),
            PttMethod::Dtr => Ok(self.port.write_data_terminal_ready(on)?),
            PttMethod::None => Ok(()),
        }
    }
    fn name(&self) -> String {
        format!("Serial:{:?}", self.model)
    }
    fn connected(&self) -> bool {
        true
    }
}

// ===========================================================================
// FLrig XML-RPC backend
// ===========================================================================

struct Flrig {
    url: String,
    connected: bool,
}

impl Flrig {
    fn connect(cfg: &RigConfig) -> Flrig {
        let url = format!("http://{}:{}/", cfg.flrig_host, cfg.flrig_port);
        let mut f = Flrig { url, connected: false };
        // Probe FLrig by reading the current VFO.
        f.connected = f.call("rig.get_vfo", "").is_ok();
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
        "FLrig".into()
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

        Ok(Hamlib { _lib: lib, fns, rig, connected: true })
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
        "Hamlib".into()
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

    #[test]
    fn yaesu_freq_command() {
        assert_eq!(Yaesu.freq_cmd(14_074_000), b"FA014074000;".to_vec());
    }

    #[test]
    fn icom_freq_bcd_little_endian() {
        assert_eq!(Icom::freq_bcd(14_074_000), [0x00, 0x40, 0x07, 0x14, 0x00]);
    }

    #[test]
    fn icom_freq_frame() {
        let icom = Icom { addr: 0x94 };
        assert_eq!(
            icom.freq_cmd(14_074_000),
            vec![0xFE, 0xFE, 0x94, 0xE0, 0x05, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD]
        );
    }

    #[test]
    fn icom_ptt_frame() {
        let icom = Icom { addr: 0x94 };
        assert_eq!(
            icom.ptt_cmd(true),
            vec![0xFE, 0xFE, 0x94, 0xE0, 0x1C, 0x00, 0x01, 0xFD]
        );
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
