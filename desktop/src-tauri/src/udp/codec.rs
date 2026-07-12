//! Byte-exact (de)serialization of the WSJT-X UDP message protocol.
//!
//! WSJT-X broadcasts a documented binary protocol over UDP that the wider
//! ecosystem (GridTracker, JTAlert, N1MM+, Log4OM, ...) consumes to plot spots
//! and auto-log QSOs, and it *accepts* a few request messages so those apps can
//! drive it. This module speaks that exact wire format so FT8AF is a drop-in
//! source/sink for those tools.
//!
//! The framing is a bespoke big-endian Qt-`QDataStream` layout (NOT JSON), so
//! byte-for-byte correctness is the whole game — every encoder here is pinned by
//! golden-vector tests (see the module tests and `docs/wsjtx-udp.md`). No I/O
//! lives here; `super::UdpService` owns the socket. Keeping the codec pure is
//! what makes it unit-testable and lets the Android/iOS ports transcribe the
//! same vectors.
//!
//! Wire primitives (all big-endian):
//!   * u32 / u64 / i32 / i64 / f64 / bool(1 byte)
//!   * string: u32 byte-length (0xFFFFFFFF = null) then that many UTF-8 bytes;
//!     an empty string is length 0.
//!   * datetime (UTC form): i64 Julian day, u32 ms-since-midnight, u8=1 (UTC).
//!
//! Every datagram starts with: u32 magic 0xADBCCBDA, u32 schema, u32 type, then
//! a string `id` (our client id), then type-specific fields.

/// Magic number prefixing every WSJT-X datagram.
pub const MAGIC: u32 = 0xADBC_CBDA;
/// Schema version we advertise (current WSJT-X). Receivers we target parse it.
pub const SCHEMA: u32 = 3;
/// Client id we stamp on every outbound message. Distinguishes FT8AF from a real
/// WSJT-X instance in multi-source setups (GridTracker keys stations by id).
pub const CLIENT_ID: &str = "FT8AF";
/// Max schema we understand, reported in the Heartbeat.
pub const MAX_SCHEMA: u32 = SCHEMA;

// WSJT-X message type numbers.
pub const T_HEARTBEAT: u32 = 0;
pub const T_STATUS: u32 = 1;
pub const T_DECODE: u32 = 2;
pub const T_CLEAR: u32 = 3;
pub const T_REPLY: u32 = 4;
pub const T_QSO_LOGGED: u32 = 5;
pub const T_REPLAY: u32 = 7;
pub const T_HALT_TX: u32 = 8;
pub const T_FREE_TEXT: u32 = 9;
pub const T_LOGGED_ADIF: u32 = 12;

// --- writer -----------------------------------------------------------------

/// Append-only big-endian byte writer for the Qt-DataStream primitives.
struct Writer {
    buf: Vec<u8>,
}

impl Writer {
    fn new() -> Self {
        Writer { buf: Vec::with_capacity(64) }
    }
    fn u8(&mut self, v: u8) {
        self.buf.push(v);
    }
    fn bool(&mut self, v: bool) {
        self.buf.push(v as u8);
    }
    fn u32(&mut self, v: u32) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }
    fn i32(&mut self, v: i32) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }
    fn u64(&mut self, v: u64) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }
    fn i64(&mut self, v: i64) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }
    fn f64(&mut self, v: f64) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }
    /// UTF-8 string: u32 byte length then the bytes. Empty string => length 0.
    fn string(&mut self, s: &str) {
        self.u32(s.len() as u32);
        self.buf.extend_from_slice(s.as_bytes());
    }
    /// QDateTime in UTC form: i64 Julian day, u32 ms-since-midnight, u8 spec=1.
    fn datetime(&mut self, julian_day: i64, ms_of_day: u32) {
        self.i64(julian_day);
        self.u32(ms_of_day);
        self.u8(1); // Qt::UTC
    }
}

/// Start a datagram: magic + schema + type + client id.
fn begin(msg_type: u32) -> Writer {
    let mut w = Writer::new();
    w.u32(MAGIC);
    w.u32(SCHEMA);
    w.u32(msg_type);
    w.string(CLIENT_ID);
    w
}

// --- outbound: builders returning ready-to-send datagrams --------------------

/// Type 0. Periodic keep-alive; also how companion apps learn our address so
/// their reply/halt/free-text requests can be routed back to us.
pub fn heartbeat(version: &str, revision: &str) -> Vec<u8> {
    let mut w = begin(T_HEARTBEAT);
    w.u32(MAX_SCHEMA);
    w.string(version);
    w.string(revision);
    w.buf
}

/// All the fields a WSJT-X Status message carries, gathered from engine state.
#[derive(Debug, Clone)]
pub struct Status {
    pub dial_freq_hz: u64,
    pub mode: String,
    pub dx_call: String,
    pub report: String,
    pub tx_mode: String,
    pub tx_enabled: bool,
    pub transmitting: bool,
    pub decoding: bool,
    pub rx_df: u32,
    pub tx_df: u32,
    pub de_call: String,
    pub de_grid: String,
    pub dx_grid: String,
    pub tx_watchdog: bool,
    pub sub_mode: String,
    pub fast_mode: bool,
    pub special_op_mode: u8,
    pub freq_tolerance: u32,
    pub tr_period: u32,
    pub config_name: String,
    pub tx_message: String,
}

/// Type 1. The big state snapshot: dial freq, mode, who we're working, and the
/// TX/decode flags that drive companion-app UI.
pub fn status(s: &Status) -> Vec<u8> {
    let mut w = begin(T_STATUS);
    w.u64(s.dial_freq_hz);
    w.string(&s.mode);
    w.string(&s.dx_call);
    w.string(&s.report);
    w.string(&s.tx_mode);
    w.bool(s.tx_enabled);
    w.bool(s.transmitting);
    w.bool(s.decoding);
    w.u32(s.rx_df);
    w.u32(s.tx_df);
    w.string(&s.de_call);
    w.string(&s.de_grid);
    w.string(&s.dx_grid);
    w.bool(s.tx_watchdog);
    w.string(&s.sub_mode);
    w.bool(s.fast_mode);
    w.u8(s.special_op_mode);
    w.u32(s.freq_tolerance);
    w.u32(s.tr_period);
    w.string(&s.config_name);
    w.string(&s.tx_message);
    w.buf
}

/// One decoded FT8 message.
#[derive(Debug, Clone)]
pub struct Decode {
    /// `true` for a fresh decode (vs. a replay).
    pub is_new: bool,
    /// Milliseconds since 00:00:00 UTC of the decode.
    pub time_ms: u32,
    pub snr: i32,
    /// Time offset (DT) in seconds.
    pub delta_time: f64,
    /// Audio frequency offset in Hz.
    pub delta_freq: u32,
    pub mode: String,
    pub message: String,
    pub low_confidence: bool,
    pub off_air: bool,
}

/// Type 2. A single decode line — the bread and butter that lights up spot maps.
pub fn decode(d: &Decode) -> Vec<u8> {
    let mut w = begin(T_DECODE);
    w.bool(d.is_new);
    w.u32(d.time_ms);
    w.i32(d.snr);
    w.f64(d.delta_time);
    w.u32(d.delta_freq);
    w.string(&d.mode);
    w.string(&d.message);
    w.bool(d.low_confidence);
    w.bool(d.off_air);
    w.buf
}

/// Type 3 (outbound form). Tells companion apps to wipe their decode band. The
/// engine sends this on a genuine decode reset — stopping decode and changing
/// band/context — not once per cycle, to avoid churning companions' views.
pub fn clear() -> Vec<u8> {
    begin(T_CLEAR).buf
}

/// A completed QSO, in WSJT-X "QSO Logged" field order.
#[derive(Debug, Clone)]
pub struct QsoLogged {
    pub time_off: DateTime,
    pub dx_call: String,
    pub dx_grid: String,
    pub tx_freq_hz: u64,
    pub mode: String,
    pub report_sent: String,
    pub report_received: String,
    pub tx_power: String,
    pub comments: String,
    pub name: String,
    pub time_on: DateTime,
    pub operator_call: String,
    pub my_call: String,
    pub my_grid: String,
    pub exchange_sent: String,
    pub exchange_received: String,
    pub prop_mode: String,
}

/// A UTC instant as WSJT-X serializes it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DateTime {
    pub julian_day: i64,
    pub ms_of_day: u32,
}

impl DateTime {
    /// Build from ADIF-style `YYYYMMDD` + `HHMMSS` strings (both UTC). Returns a
    /// zeroed instant if either is malformed — a bad clock string must not abort
    /// the whole QSO-Logged datagram.
    pub fn from_adif(date_yyyymmdd: &str, time_hhmmss: &str) -> DateTime {
        let julian_day = julian_day_from_yyyymmdd(date_yyyymmdd).unwrap_or(0);
        let ms_of_day = ms_of_day_from_hhmmss(time_hhmmss).unwrap_or(0);
        DateTime { julian_day, ms_of_day }
    }
}

/// Type 5. Emitted when a QSO is logged so companion loggers capture it.
pub fn qso_logged(q: &QsoLogged) -> Vec<u8> {
    let mut w = begin(T_QSO_LOGGED);
    w.datetime(q.time_off.julian_day, q.time_off.ms_of_day);
    w.string(&q.dx_call);
    w.string(&q.dx_grid);
    w.u64(q.tx_freq_hz);
    w.string(&q.mode);
    w.string(&q.report_sent);
    w.string(&q.report_received);
    w.string(&q.tx_power);
    w.string(&q.comments);
    w.string(&q.name);
    w.datetime(q.time_on.julian_day, q.time_on.ms_of_day);
    w.string(&q.operator_call);
    w.string(&q.my_call);
    w.string(&q.my_grid);
    w.string(&q.exchange_sent);
    w.string(&q.exchange_received);
    w.string(&q.prop_mode);
    w.buf
}

/// Type 12. The just-logged QSO as an ADIF record string. Loggers that key off
/// ADIF (JTAlert, N1MM) prefer this to the field-by-field QSO Logged message.
pub fn logged_adif(adif: &str) -> Vec<u8> {
    let mut w = begin(T_LOGGED_ADIF);
    w.string(adif);
    w.buf
}

// --- inbound: parse companion-app requests -----------------------------------

/// A request a companion app sent us, already decoded to intent.
#[derive(Debug, Clone, PartialEq)]
pub enum Inbound {
    /// User double-clicked a decode elsewhere: call this station. `grid` is the
    /// station's grid if the clicked message carried one.
    Reply { call: String, grid: String, snr: i32 },
    /// Re-broadcast this session's decodes.
    Replay,
    /// Stop transmitting. `auto_only` = halt only automatic (leave a manual TX).
    HaltTx { auto_only: bool },
    /// Set/send an arbitrary free-text message. `send` = transmit immediately.
    FreeText { text: String, send: bool },
}

/// Big-endian reader mirroring [`Writer`]. Every read is bounds-checked and
/// returns `None` on a short/malformed buffer so a stray datagram can't panic
/// the listener.
struct Reader<'a> {
    b: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(b: &'a [u8]) -> Self {
        Reader { b, pos: 0 }
    }
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let end = self.pos.checked_add(n)?;
        let s = self.b.get(self.pos..end)?;
        self.pos = end;
        Some(s)
    }
    fn u8(&mut self) -> Option<u8> {
        self.take(1).map(|s| s[0])
    }
    fn bool(&mut self) -> Option<bool> {
        self.u8().map(|v| v != 0)
    }
    fn u32(&mut self) -> Option<u32> {
        self.take(4).map(|s| u32::from_be_bytes(s.try_into().unwrap()))
    }
    fn i32(&mut self) -> Option<i32> {
        self.take(4).map(|s| i32::from_be_bytes(s.try_into().unwrap()))
    }
    fn f64(&mut self) -> Option<f64> {
        self.take(8).map(|s| f64::from_be_bytes(s.try_into().unwrap()))
    }
    /// String: u32 length then bytes. A null string (0xFFFFFFFF) reads as empty.
    fn string(&mut self) -> Option<String> {
        let len = self.u32()?;
        if len == 0xFFFF_FFFF {
            return Some(String::new());
        }
        let bytes = self.take(len as usize)?;
        Some(String::from_utf8_lossy(bytes).into_owned())
    }
    fn skip_string(&mut self) -> Option<()> {
        self.string().map(|_| ())
    }
}

/// Parse an inbound datagram into intent. Returns `None` for anything we don't
/// act on (bad magic, unknown/outbound-only type, truncated buffer).
pub fn parse_inbound(buf: &[u8]) -> Option<Inbound> {
    let mut r = Reader::new(buf);
    if r.u32()? != MAGIC {
        return None;
    }
    let _schema = r.u32()?;
    let msg_type = r.u32()?;
    r.skip_string()?; // id — we don't filter on it

    match msg_type {
        T_REPLY => {
            let _time = r.u32()?;
            let snr = r.i32()?;
            let _dt = r.f64()?;
            let _df = r.u32()?;
            let _mode = r.string()?;
            let message = r.string()?;
            // trailing low_confidence / modifiers are optional across versions.
            let (call, grid) = parse_reply_target(&message)?;
            Some(Inbound::Reply { call, grid, snr })
        }
        T_REPLAY => Some(Inbound::Replay),
        T_HALT_TX => {
            let auto_only = r.bool().unwrap_or(false);
            Some(Inbound::HaltTx { auto_only })
        }
        T_FREE_TEXT => {
            let text = r.string()?;
            // Reject a truncated packet rather than defaulting the send flag to true: a
            // malformed datagram must never trigger an unintended transmit when "Accept
            // UDP requests" is enabled.
            let send = r.bool()?;
            Some(Inbound::FreeText { text, send })
        }
        _ => None,
    }
}

/// From a decoded message line, work out which station a Reply should call and
/// their grid (if present). Handles plain `TO FROM ...` and CQ lines with an
/// optional directive: `CQ FROM GRID`, `CQ DX FROM GRID`, `CQ POTA FROM GRID`.
///
/// The caller is the first callsign-shaped token after any `CQ`+directive; the
/// grid is the first following 4-char Maidenhead square.
fn parse_reply_target(message: &str) -> Option<(String, String)> {
    let tokens: Vec<&str> = message.split_whitespace().collect();
    if tokens.is_empty() {
        return None;
    }
    // Index of the caller's callsign.
    let call_idx = if tokens[0].eq_ignore_ascii_case("CQ") {
        // Skip a directive token that isn't itself a callsign (e.g. DX, POTA, a
        // numeric frequency directive) — the caller is the next real callsign.
        match tokens.get(1) {
            Some(t) if is_callsign(t) => 1,
            Some(_) => 2,
            None => return None,
        }
    } else {
        // "TO FROM ..." — the sender we'd answer is the second token.
        1
    };
    let call = tokens.get(call_idx)?;
    if !is_callsign(call) {
        return None;
    }
    let grid = tokens
        .get(call_idx + 1..)
        .unwrap_or(&[])
        .iter()
        .find(|t| is_grid4(t))
        .map(|t| t.to_uppercase())
        .unwrap_or_default();
    Some((call.to_uppercase(), grid))
}

/// A token that looks like a callsign: has at least one letter and one digit and
/// is otherwise alphanumeric or `/`. Deliberately loose (covers portable `/P`,
/// compound prefixes) while still rejecting directives like `DX`/`POTA` and bare
/// grids/reports.
fn is_callsign(t: &str) -> bool {
    let core = t.split('/').next().unwrap_or(t);
    let has_alpha = core.chars().any(|c| c.is_ascii_alphabetic());
    let has_digit = core.chars().any(|c| c.is_ascii_digit());
    let ok_chars = t.chars().all(|c| c.is_ascii_alphanumeric() || c == '/');
    has_alpha && has_digit && ok_chars && !is_grid4(t)
}

/// A 4-character Maidenhead square: two letters A–R then two digits.
fn is_grid4(t: &str) -> bool {
    let b = t.as_bytes();
    b.len() == 4
        && b[0].to_ascii_uppercase().is_ascii_uppercase()
        && (b'A'..=b'R').contains(&b[0].to_ascii_uppercase())
        && (b'A'..=b'R').contains(&b[1].to_ascii_uppercase())
        && b[2].is_ascii_digit()
        && b[3].is_ascii_digit()
}

// --- date/time helpers -------------------------------------------------------

/// Julian Day Number for a UTC `YYYYMMDD` date (proleptic Gregorian), matching
/// what Qt's `QDate::toJulianDay` produces. `None` if the string is malformed.
fn julian_day_from_yyyymmdd(s: &str) -> Option<i64> {
    if s.len() != 8 || !s.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let year: i32 = s[0..4].parse().ok()?;
    let month: u32 = s[4..6].parse().ok()?;
    let day: u32 = s[6..8].parse().ok()?;
    let date = chrono::NaiveDate::from_ymd_opt(year, month, day)?;
    // chrono day 1 = 0001-01-01 (proleptic Gregorian); JDN of that day = 1721426.
    use chrono::Datelike;
    Some(date.num_days_from_ce() as i64 + 1_721_425)
}

/// Milliseconds since midnight for a UTC `HHMMSS` string. `None` if malformed.
fn ms_of_day_from_hhmmss(s: &str) -> Option<u32> {
    if s.len() != 6 || !s.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let h: u32 = s[0..2].parse().ok()?;
    let m: u32 = s[2..4].parse().ok()?;
    let sec: u32 = s[4..6].parse().ok()?;
    if h > 23 || m > 59 || sec > 60 {
        return None;
    }
    Some(((h * 3600) + (m * 60) + sec) * 1000)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Re-read a datagram's header, returning the type and leaving the reader
    // positioned at the first payload field (after the id string).
    fn read_header(buf: &[u8]) -> (u32, Reader<'_>) {
        let mut r = Reader::new(buf);
        assert_eq!(r.u32().unwrap(), MAGIC, "magic");
        assert_eq!(r.u32().unwrap(), SCHEMA, "schema");
        let t = r.u32().unwrap();
        assert_eq!(r.string().unwrap(), CLIENT_ID, "id");
        (t, r)
    }

    #[test]
    fn header_bytes_are_exact() {
        let d = clear();
        // magic AD BC CB DA, schema 00 00 00 03, type 00 00 00 03,
        // id length 00 00 00 05, "FT8AF".
        assert_eq!(
            d,
            vec![
                0xAD, 0xBC, 0xCB, 0xDA, // magic
                0x00, 0x00, 0x00, 0x03, // schema 3
                0x00, 0x00, 0x00, 0x03, // type 3 (clear)
                0x00, 0x00, 0x00, 0x05, // id len 5
                b'F', b'T', b'8', b'A', b'F',
            ]
        );
    }

    #[test]
    fn heartbeat_roundtrips() {
        let d = heartbeat("1.2.3", "");
        let (t, mut r) = read_header(&d);
        assert_eq!(t, T_HEARTBEAT);
        assert_eq!(r.u32().unwrap(), MAX_SCHEMA);
        assert_eq!(r.string().unwrap(), "1.2.3");
        assert_eq!(r.string().unwrap(), "");
        assert_eq!(r.pos, d.len(), "no trailing bytes");
    }

    #[test]
    fn decode_roundtrips_and_string_framing() {
        let d = decode(&Decode {
            is_new: true,
            time_ms: 47_493_000,
            snr: -7,
            delta_time: 0.2,
            delta_freq: 1500,
            mode: "FT8".into(),
            message: "CQ K1ABC FN42".into(),
            low_confidence: false,
            off_air: false,
        });
        let (t, mut r) = read_header(&d);
        assert_eq!(t, T_DECODE);
        assert!(r.bool().unwrap());
        assert_eq!(r.u32().unwrap(), 47_493_000);
        assert_eq!(r.i32().unwrap(), -7);
        assert_eq!(r.f64().unwrap(), 0.2);
        assert_eq!(r.u32().unwrap(), 1500);
        assert_eq!(r.string().unwrap(), "FT8");
        assert_eq!(r.string().unwrap(), "CQ K1ABC FN42");
        assert!(!r.bool().unwrap());
        assert!(!r.bool().unwrap());
        assert_eq!(r.pos, d.len());
    }

    #[test]
    fn status_roundtrips_all_fields() {
        let s = Status {
            dial_freq_hz: 14_074_000,
            mode: "FT8".into(),
            dx_call: "K1ABC".into(),
            report: "-07".into(),
            tx_mode: "FT8".into(),
            tx_enabled: true,
            transmitting: false,
            decoding: true,
            rx_df: 1500,
            tx_df: 1500,
            de_call: "K0XYZ".into(),
            de_grid: "EN37".into(),
            dx_grid: "FN42".into(),
            tx_watchdog: false,
            sub_mode: "".into(),
            fast_mode: false,
            special_op_mode: 0,
            freq_tolerance: 0xFFFF_FFFF,
            tr_period: 15,
            config_name: "Default".into(),
            tx_message: "K1ABC K0XYZ EN37".into(),
        };
        let d = status(&s);
        let (t, mut r) = read_header(&d);
        assert_eq!(t, T_STATUS);
        assert_eq!(r.take(8).unwrap(), &14_074_000u64.to_be_bytes());
        assert_eq!(r.string().unwrap(), "FT8"); // mode
        assert_eq!(r.string().unwrap(), "K1ABC"); // dx_call
        assert_eq!(r.string().unwrap(), "-07"); // report
        assert_eq!(r.string().unwrap(), "FT8"); // tx_mode
        assert!(r.bool().unwrap()); // tx_enabled
        assert!(!r.bool().unwrap()); // transmitting
        assert!(r.bool().unwrap()); // decoding
        assert_eq!(r.u32().unwrap(), 1500); // rx_df
        assert_eq!(r.u32().unwrap(), 1500); // tx_df
        assert_eq!(r.string().unwrap(), "K0XYZ"); // de_call
        assert_eq!(r.string().unwrap(), "EN37"); // de_grid
        assert_eq!(r.string().unwrap(), "FN42"); // dx_grid
        assert!(!r.bool().unwrap()); // tx_watchdog
        assert_eq!(r.string().unwrap(), ""); // sub_mode
        assert!(!r.bool().unwrap()); // fast_mode
        assert_eq!(r.u8().unwrap(), 0); // special_op_mode
        assert_eq!(r.u32().unwrap(), 0xFFFF_FFFF); // freq_tolerance
        assert_eq!(r.u32().unwrap(), 15); // tr_period
        assert_eq!(r.string().unwrap(), "Default"); // config_name
        assert_eq!(r.string().unwrap(), "K1ABC K0XYZ EN37"); // tx_message
        assert_eq!(r.pos, d.len());
    }

    #[test]
    fn qso_logged_roundtrips_with_datetime() {
        let on = DateTime::from_adif("20260712", "134500");
        let off = DateTime::from_adif("20260712", "134615");
        let q = QsoLogged {
            time_off: off,
            dx_call: "K1ABC".into(),
            dx_grid: "FN42".into(),
            tx_freq_hz: 14_075_500,
            mode: "FT8".into(),
            report_sent: "-07".into(),
            report_received: "-12".into(),
            tx_power: "".into(),
            comments: "".into(),
            name: "".into(),
            time_on: on,
            operator_call: "K0XYZ".into(),
            my_call: "K0XYZ".into(),
            my_grid: "EN37".into(),
            exchange_sent: "".into(),
            exchange_received: "".into(),
            prop_mode: "".into(),
        };
        let d = qso_logged(&q);
        let (t, mut r) = read_header(&d);
        assert_eq!(t, T_QSO_LOGGED);
        // time_off datetime
        assert_eq!(r.take(8).unwrap(), &off.julian_day.to_be_bytes());
        assert_eq!(r.u32().unwrap(), off.ms_of_day);
        assert_eq!(r.u8().unwrap(), 1); // UTC spec
        assert_eq!(r.string().unwrap(), "K1ABC");
        assert_eq!(r.string().unwrap(), "FN42");
        assert_eq!(r.take(8).unwrap(), &14_075_500u64.to_be_bytes());
        assert_eq!(r.string().unwrap(), "FT8");
        assert_eq!(r.string().unwrap(), "-07");
        assert_eq!(r.string().unwrap(), "-12");
        // tx_power, comments, name
        assert_eq!(r.string().unwrap(), "");
        assert_eq!(r.string().unwrap(), "");
        assert_eq!(r.string().unwrap(), "");
        // time_on datetime
        assert_eq!(r.take(8).unwrap(), &on.julian_day.to_be_bytes());
        assert_eq!(r.u32().unwrap(), on.ms_of_day);
        assert_eq!(r.u8().unwrap(), 1);
        assert_eq!(r.string().unwrap(), "K0XYZ"); // operator
        assert_eq!(r.string().unwrap(), "K0XYZ"); // my_call
        assert_eq!(r.string().unwrap(), "EN37"); // my_grid
        assert_eq!(r.string().unwrap(), ""); // exch sent
        assert_eq!(r.string().unwrap(), ""); // exch recv
        assert_eq!(r.string().unwrap(), ""); // prop mode
        assert_eq!(r.pos, d.len());
    }

    #[test]
    fn logged_adif_wraps_string() {
        let d = logged_adif("<call:5>K1ABC <eor>");
        let (t, mut r) = read_header(&d);
        assert_eq!(t, T_LOGGED_ADIF);
        assert_eq!(r.string().unwrap(), "<call:5>K1ABC <eor>");
        assert_eq!(r.pos, d.len());
    }

    // --- julian day / time-of-day golden values ------------------------------

    #[test]
    fn julian_day_matches_known_values() {
        // 2000-01-01 has Julian Day Number 2451545 (the J2000 epoch date).
        assert_eq!(julian_day_from_yyyymmdd("20000101"), Some(2_451_545));
        // 1970-01-01 (Unix epoch) = JDN 2440588.
        assert_eq!(julian_day_from_yyyymmdd("19700101"), Some(2_440_588));
        assert_eq!(julian_day_from_yyyymmdd("bad"), None);
        assert_eq!(julian_day_from_yyyymmdd("20261301"), None); // month 13
    }

    #[test]
    fn ms_of_day_from_time() {
        assert_eq!(ms_of_day_from_hhmmss("000000"), Some(0));
        assert_eq!(ms_of_day_from_hhmmss("134615"), Some(49_575_000));
        assert_eq!(ms_of_day_from_hhmmss("235959"), Some(86_399_000));
        assert_eq!(ms_of_day_from_hhmmss("240000"), None);
        assert_eq!(ms_of_day_from_hhmmss("12345"), None);
    }

    // --- inbound parsing -----------------------------------------------------

    // Build an inbound Reply datagram for a given message line.
    fn make_reply(message: &str, snr: i32) -> Vec<u8> {
        let mut w = begin(T_REPLY);
        w.u32(47_000_000); // time
        w.i32(snr);
        w.f64(0.1); // dt
        w.u32(1500); // df
        w.string("FT8"); // mode
        w.string(message);
        w.bool(false); // low confidence
        w.u8(0); // modifiers
        w.buf
    }

    #[test]
    fn parse_reply_plain_cq() {
        let d = make_reply("CQ K1ABC FN42", -3);
        assert_eq!(
            parse_inbound(&d),
            Some(Inbound::Reply { call: "K1ABC".into(), grid: "FN42".into(), snr: -3 })
        );
    }

    #[test]
    fn parse_reply_cq_with_directive() {
        assert_eq!(
            parse_inbound(&make_reply("CQ DX K1ABC FN42", -3)),
            Some(Inbound::Reply { call: "K1ABC".into(), grid: "FN42".into(), snr: -3 })
        );
        assert_eq!(
            parse_inbound(&make_reply("CQ POTA W1AW/4 EM70", 5)),
            Some(Inbound::Reply { call: "W1AW/4".into(), grid: "EM70".into(), snr: 5 })
        );
    }

    #[test]
    fn parse_reply_non_cq_calls_sender() {
        // Double-click a station mid-QSO: we call the FROM (sender) station.
        assert_eq!(
            parse_inbound(&make_reply("K0XYZ K1ABC -12", -12)),
            Some(Inbound::Reply { call: "K1ABC".into(), grid: "".into(), snr: -12 })
        );
    }

    #[test]
    fn parse_halt_and_freetext_and_replay() {
        let mut w = begin(T_HALT_TX);
        w.bool(true);
        assert_eq!(parse_inbound(&w.buf), Some(Inbound::HaltTx { auto_only: true }));

        let mut w = begin(T_FREE_TEXT);
        w.string("TEST DE FT8AF");
        w.bool(true);
        assert_eq!(
            parse_inbound(&w.buf),
            Some(Inbound::FreeText { text: "TEST DE FT8AF".into(), send: true })
        );

        assert_eq!(parse_inbound(&begin(T_REPLAY).buf), Some(Inbound::Replay));
    }

    #[test]
    fn parse_freetext_missing_send_flag_is_rejected() {
        // A truncated Free-Text datagram (text present, send bool missing) must be
        // rejected rather than defaulting send=true — otherwise a malformed packet could
        // trigger an unintended transmit when "Accept UDP requests" is enabled.
        let mut w = begin(T_FREE_TEXT);
        w.string("HELLO");
        // no w.bool(...) — the trailing send flag is missing
        assert_eq!(parse_inbound(&w.buf), None);
    }

    #[test]
    fn parse_rejects_bad_and_outbound_only() {
        // Wrong magic.
        assert_eq!(parse_inbound(&[0, 1, 2, 3, 4, 5, 6, 7]), None);
        // Truncated (magic only).
        assert_eq!(parse_inbound(&MAGIC.to_be_bytes()), None);
        // A Status message is outbound-only; we don't act on it.
        assert_eq!(parse_inbound(&begin(T_STATUS).buf), None);
    }

    #[test]
    fn parse_reply_short_buffer_is_none() {
        // Reply header but truncated before the message field.
        let mut w = begin(T_REPLY);
        w.u32(1); // time only
        assert_eq!(parse_inbound(&w.buf), None);
    }
}
