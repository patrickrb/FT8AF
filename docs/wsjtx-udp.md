# WSJT-X UDP interface

FT8AF speaks the [WSJT-X UDP message
protocol](https://sourceforge.net/p/wsjt/wsjtx/ci/master/tree/Network/NetworkMessage.hpp)
so it interoperates with the wider desktop ecosystem — GridTracker, JTAlert,
N1MM+, Log4OM, PSKReporter proxies, etc. It **broadcasts** decodes, status and
logged QSOs, and (when enabled) **accepts** a few request messages so those apps
can drive it.

This document is the shared, language-agnostic spec: the desktop (Rust), Android
(Kotlin) and iOS (Swift) builds each implement the same wire format and are
pinned to the same golden vectors below, so all three encoders are byte-for-byte
identical.

## Settings

Off by default. Configure under **Settings → WSJT-X UDP**:

| Setting | Default | Meaning |
|---|---|---|
| Enable UDP broadcast | off | Master on/off. |
| Server address | `127.0.0.1` | Destination host — unicast, broadcast (`x.x.x.255`), or an IPv4 multicast group. |
| Server port | `2237` | Destination port (WSJT-X default). |
| Accept UDP requests | off | Bind a listener and act on inbound Reply/Halt/Free-Text/Replay. |

The app sends from an ephemeral local port; companion apps learn our address
from the periodic **Heartbeat** and reply to that source, exactly as they do for
a real WSJT-X instance.

## Wire format

Bespoke big-endian Qt-`QDataStream` framing (not JSON). Primitives, all
big-endian:

- `u32` / `u64` / `i32` / `i64` / `f64`
- `bool` — 1 byte (`00`/`01`)
- **string** — `u32` byte length (`0xFFFFFFFF` = null), then that many UTF-8
  bytes. Empty string = length `0`.
- **datetime** (UTC form) — `i64` Julian Day Number, `u32` ms-since-midnight,
  `u8` = `1` (Qt UTC timespec).

Every datagram starts with a header:

```
u32  magic   = 0xADBCCBDA
u32  schema  = 3
u32  type
str  id      = "FT8AF"        (client id; distinguishes us from real WSJT-X)
```

…followed by type-specific fields.

### Outbound (FT8AF → companion apps)

| # | Name | Fields after header |
|---|------|---------------------|
| 0 | Heartbeat | `u32` max-schema=3, `str` version, `str` revision |
| 1 | Status | `u64` dial-Hz, `str` mode, `str` dx-call, `str` report, `str` tx-mode, `bool` tx-enabled, `bool` transmitting, `bool` decoding, `u32` rx-df, `u32` tx-df, `str` de-call, `str` de-grid, `str` dx-grid, `bool` tx-watchdog, `str` sub-mode, `bool` fast-mode, `u8` special-op, `u32` freq-tol, `u32` tr-period, `str` config-name, `str` tx-message |
| 2 | Decode | `bool` new, `u32` time-ms-of-day, `i32` snr, `f64` dt-sec, `u32` df-Hz (audio offset), `str` mode, `str` message, `bool` low-conf, `bool` off-air |
| 3 | Clear | (header only) |
| 5 | QSO Logged | `datetime` time-off, `str` dx-call, `str` dx-grid, `u64` tx-Hz, `str` mode, `str` rpt-sent, `str` rpt-rcvd, `str` tx-power, `str` comments, `str` name, `datetime` time-on, `str` op-call, `str` my-call, `str` my-grid, `str` exch-sent, `str` exch-rcvd, `str` prop-mode |
| 12 | Logged ADIF | `str` ADIF record |

Cadence: **Status** every ~1 s and on every TX/QSO state change; **Heartbeat**
every ~15 s; **Decode** per decoded message; **Clear** on decode-stop / band
change (a genuine reset, *not* every cycle); **QSO Logged** + **Logged ADIF** on
each logged QSO.

### Inbound (companion apps → FT8AF, when "Accept UDP requests" is on)

| # | Name | Fields after header | Action |
|---|------|---------------------|--------|
| 4 | Reply | `u32` time, `i32` snr, `f64` dt, `u32` df, `str` mode, `str` message, `bool` low-conf, `u8` modifiers | Call the station named in `message` (auto-sequence). |
| 7 | Replay | — | Re-broadcast this session's decodes (marked not-new). |
| 8 | Halt Tx | `bool` auto-only | Stop transmitting. |
| 9 | Free Text | `str` text, `bool` send | Send `text` (only acted on when `send` = true). |

The station to call from a **Reply** is parsed from the message line: `TO FROM
…`, or a CQ line with an optional directive (`CQ FROM GRID`, `CQ DX FROM GRID`,
`CQ POTA FROM GRID`) → the first callsign-shaped token, with the grid taken from
the first following 4-char Maidenhead square. Any other/unknown message type is
ignored; malformed or truncated datagrams are dropped without effect.

## Golden vectors

Byte-exact reference datagrams (hex). The Rust codec's unit tests in
`desktop/src-tauri/src/udp/codec.rs` are the authoritative fixtures; the Android
and iOS ports transcribe the same field values and assert the same bytes.

**Header only — Clear (type 3):**

```
ad bc cb da  00 00 00 03  00 00 00 03  00 00 00 05  46 54 38 41 46
└─ magic ──┘  └ schema 3┘  └─ type 3 ┘  └ id len 5┘  └── "FT8AF" ──┘
```

**Heartbeat (type 0)** — version `"0.1.0"`, revision `""`:

```
ad bc cb da 00 00 00 03 00 00 00 00 00 00 00 05 46 54 38 41 46
00 00 00 03                      max-schema = 3
00 00 00 05 30 2e 31 2e 30       version "0.1.0"
00 00 00 00                      revision ""
```

**Decode (type 2)** — new=true, time=47493000 ms, snr=-7, dt=0.2, df=1500,
mode="FT8", message="CQ K1ABC FN42", low-conf=false, off-air=false:

```
ad bc cb da 00 00 00 03 00 00 00 02 00 00 00 05 46 54 38 41 46
01                               new = true
02 d4 af 88                      time = 47493000
ff ff ff f9                      snr = -7
3f c9 99 99 99 99 99 9a          dt = 0.2 (f64)
00 00 05 dc                      df = 1500
00 00 00 03 46 54 38             mode "FT8"
00 00 00 0d 43 51 20 4b 31 41 42 43 20 46 4e 34 32   message "CQ K1ABC FN42"
00 00                            low-conf=false, off-air=false
```

**datetime** example — `2000-01-01` has Julian Day Number `2451545`; midnight is
ms-of-day `0`, so a `time_on` of that instant serializes as
`00 00 00 00 00 25 68 59` (`i64` 2451545) `00 00 00 00` (`u32` 0) `01` (UTC).
