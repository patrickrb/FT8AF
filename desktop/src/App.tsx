import { Fragment, useEffect, useRef, useState, type MouseEvent } from "react";
import {
  api,
  onEngineEvent,
  type AudioDevice,
  type BandInfo,
  type ClockSyncEvent,
  type CycleTick,
  type EngineEvent,
  type HamlibRig,
  type QsoRecord,
  type RigConfig,
  type RigStatusEvent,
  type SerialPortInfo,
  type TxStage,
  type TxStateEvent,
  type UiMessage,
  type WaterfallConfig,
  type WfWindow,
} from "./ipc";

type Tab = "decode" | "log" | "settings";
type Filter = "all" | "cq" | "tome";
const MAX_MESSAGES = 800;

// The live waterfall draws imperatively to a canvas (registered by WaterfallView)
// so per-row spectrum events never go through React state.
const WF_HEIGHT = 160;
// Top of the displayed audio band; matches the backend's WF_MAX_HZ.
const WF_TOP_HZ = 3500;
const TX_MIN_HZ = 200;
const TX_MAX_HZ = 2900;
let wfCanvas: HTMLCanvasElement | null = null;
function registerWfCanvas(c: HTMLCanvasElement | null) {
  wfCanvas = c;
  if (c) c.height = WF_HEIGHT;
}
function drawWaterfallRow(bins: number[], boundary = false) {
  const cv = wfCanvas;
  if (!cv || bins.length === 0) return;
  const cols = bins.length;
  if (cv.width !== cols) cv.width = cols;
  const ctx = cv.getContext("2d");
  if (!ctx) return;
  const h = cv.height;
  // Scroll the existing image down by one row, then paint the new row on top.
  ctx.drawImage(cv, 0, 0, cols, h - 1, 0, 1, cols, h - 1);
  const img = ctx.createImageData(cols, 1);
  for (let i = 0; i < cols; i++) {
    const [r, g, b] = colormap(bins[i]);
    img.data[i * 4] = r;
    img.data[i * 4 + 1] = g;
    img.data[i * 4 + 2] = b;
    img.data[i * 4 + 3] = 255;
  }
  ctx.putImageData(img, 0, 0);
  // 15 s cycle grid line, on the same rx-corrected clock as decode DT: a faint
  // overlay so the waterfall's timing reads against the cycle (and the DT)
  // rather than appearing to run ahead of it.
  if (boundary) {
    ctx.fillStyle = "rgba(255,255,255,0.35)";
    ctx.fillRect(0, 0, cols, 1);
  }
}

export default function App() {
  const [tab, setTab] = useState<Tab>("decode");
  const [messages, setMessages] = useState<UiMessage[]>([]);
  const [cycle, setCycle] = useState<CycleTick | null>(null);
  const [txState, setTxState] = useState<TxStateEvent | null>(null);
  const [rig, setRig] = useState<RigStatusEvent | null>(null);
  const [clock, setClock] = useState<ClockSyncEvent | null>(null);
  const [decoding, setDecoding] = useState(false);
  const [audio, setAudio] = useState<{ db: number; silent: boolean } | null>(null);
  const [status, setStatus] = useState("");
  const [filter, setFilter] = useState<Filter>("all");
  const [txFreq, setTxFreq] = useState(1500);
  const [txGain, setTxGain] = useState(90); // TX level as a percentage (0–100)
  const [rigLabel, setRigLabel] = useState(""); // optional display-name override
  const [bands, setBands] = useState<BandInfo[]>([]);
  const [dialHz, setDialHz] = useState<number>(14074000);

  // keep latest decoding flag for the toggle without stale closure
  const decodingRef = useRef(decoding);
  decodingRef.current = decoding;

  useEffect(() => {
    // Register the event listener with cancellation-safe cleanup. Without this,
    // React StrictMode's mount/unmount/mount in dev leaks the first listener
    // (its async `listen()` resolves after the first cleanup ran), so every
    // event would be handled twice — duplicating each decode batch.
    let unlisten: (() => void) | undefined;
    let cancelled = false;
    onEngineEvent(handleEvent).then((u) => {
      if (cancelled) u();
      else unlisten = u;
      // Pull current rig/TX status now that we're listening. The engine
      // auto-reconnects the saved rig at startup and emits its status before this
      // listener exists, so without this the corner would show "no rig" until the
      // next status change.
      api.refreshStatus();
    });
    api.listBands().then(setBands);
    api.getConfig("dial_hz").then((v) => {
      if (v) setDialHz(parseInt(v, 10));
    });
    api.getConfig("base_freq").then((v) => {
      if (v) setTxFreq(parseInt(v, 10));
    });
    api.getConfig("tx_gain").then((v) => {
      if (v == null || v === "") return;
      const pct = Math.round(parseFloat(v) * 100);
      if (!Number.isNaN(pct)) setTxGain(pct); // keep a persisted 0% (not just falsy-skip)
    });
    api.getConfig("rig_label").then((v) => {
      if (v) setRigLabel(v);
    });
    return () => {
      cancelled = true;
      unlisten?.();
    };
  }, []);

  function handleEvent(e: EngineEvent) {
    switch (e.type) {
      case "decoded":
        if (e.data.length) {
          setMessages((prev) => {
            // Safety net: drop any incoming message already present for the same
            // slot (same utc_ms + text), so a stray duplicate event can't double rows.
            const seen = new Set(prev.map((m) => `${m.utc_ms}|${m.text}`));
            const fresh = e.data.filter((m) => !seen.has(`${m.utc_ms}|${m.text}`));
            if (fresh.length === 0) return prev;
            return [...fresh, ...prev].slice(0, MAX_MESSAGES);
          });
        }
        break;
      case "cycle":
        setCycle(e.data);
        break;
      case "tx_state":
        setTxState(e.data);
        break;
      case "rig_status":
        setRig(e.data);
        break;
      case "clock_sync":
        setClock(e.data);
        break;
      case "waterfall_row":
        drawWaterfallRow(e.data.bins, e.data.boundary);
        break;
      case "input_level":
        setAudio(e.data);
        break;
      case "qso_completed":
        setStatus(`QSO logged: ${e.data.call} ${e.data.rst_sent}/${e.data.rst_rcvd}`);
        break;
      case "info":
        setStatus(e.data);
        break;
      case "error":
        setStatus("⚠ " + e.data);
        break;
    }
  }

  function toggleDecode() {
    if (decodingRef.current) {
      api.stopDecode();
      setDecoding(false);
      setAudio(null); // no input meter while stopped
    } else {
      api.startDecode();
      setDecoding(true);
    }
  }

  function onBand(hz: number) {
    setDialHz(hz);
    api.setBand(hz);
  }

  const visible = messages.filter((m) =>
    filter === "all" ? true : filter === "cq" ? m.is_cq : m.to_me
  );

  return (
    <>
      <TopBar
        cycle={cycle}
        decoding={decoding}
        onToggleDecode={toggleDecode}
        bands={bands}
        dialHz={dialHz}
        onBand={onBand}
        rig={rig}
        clock={clock}
        audio={audio}
        rigLabel={rigLabel}
        txGain={txGain}
        onTxGain={(v) => {
          setTxGain(v);
          api.setTxGain(v / 100);
        }}
      />
      <div className="tabs">
        {(["decode", "log", "settings"] as Tab[]).map((t) => (
          <button key={t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>
            {t === "decode" ? "Decode" : t === "log" ? "Logbook" : "Settings"}
          </button>
        ))}
      </div>
      <div className={"content" + (tab === "decode" ? " decode" : "")}>
        {tab === "decode" && (
          <DecodeScreen
            messages={visible}
            filter={filter}
            setFilter={setFilter}
            txFreq={txFreq}
            onSelectFreq={(hz) => {
              setTxFreq(hz);
              api.setBaseFreq(hz);
            }}
            onAnswer={(m) => api.answer({ call_from: m.call_from, grid: m.grid, snr: m.snr })}
          />
        )}
        {tab === "log" && <LogScreen />}
        {tab === "settings" && (
          <SettingsScreen
            onStatus={setStatus}
            clock={clock}
            onRigLabel={(v) => {
              setRigLabel(v);
              api.setConfig("rig_label", v);
            }}
          />
        )}
      </div>
      <TxBar
        txState={txState}
        decoding={decoding}
        onCq={() => api.startCq()}
        onStage={(s) => api.setStage(s)}
        onStop={() => api.stopTx()}
      />
      <div className="status-line">{status}</div>
    </>
  );
}

function TopBar(props: {
  cycle: CycleTick | null;
  decoding: boolean;
  onToggleDecode: () => void;
  bands: BandInfo[];
  dialHz: number;
  onBand: (hz: number) => void;
  rig: RigStatusEvent | null;
  clock: ClockSyncEvent | null;
  audio: { db: number; silent: boolean } | null;
  rigLabel: string;
  txGain: number;
  onTxGain: (v: number) => void;
}) {
  const { cycle, decoding, onToggleDecode, bands, dialHz, onBand, rig, clock, audio, rigLabel, txGain, onTxGain } = props;
  const utc = cycle ? new Date(cycle.utc_ms).toISOString().substring(11, 19) : "--:--:--";
  const pct = cycle ? (cycle.ms_into_cycle / 15000) * 100 : 0;
  return (
    <div className="topbar">
      <span className="brand">FT8AF</span>
      <span className="clock">{utc}</span>
      <ClockSyncBadge clock={clock} />
      <div className="cycle-bar">
        <div style={{ width: `${pct}%` }} />
      </div>
      <span className="seq">seq {cycle?.sequential ?? "-"}</span>
      <div className="spacer" />
      <span className="tx-level" title="TX output level (drive) — lower until ALC stops pinning">
        <span className="muted">TX</span>
        <input
          type="range"
          aria-label="TX output level (drive), percent"
          min={0}
          max={100}
          step={1}
          value={txGain}
          onChange={(e) => onTxGain(parseInt(e.target.value, 10))}
          style={{ width: 90 }}
        />
        <span className="muted" style={{ fontVariantNumeric: "tabular-nums", minWidth: 34, textAlign: "right" }}>
          {txGain}%
        </span>
      </span>
      <select id="band-select" value={dialHz} onChange={(e) => onBand(parseInt(e.target.value, 10))}>
        {bands.map((b) => (
          <option key={b.name} value={b.dial_hz}>
            {b.name} · {(b.dial_hz / 1e6).toFixed(3)}
          </option>
        ))}
      </select>
      <span className="muted">
        {rig?.connected ? `rig: ${rigLabel || rig.model}` : "no rig"}
        {rig?.ptt ? " · PTT" : ""}
      </span>
      <AudioBadge decoding={decoding} audio={audio} />
      <button className={decoding ? "danger" : "primary"} onClick={onToggleDecode}>
        {decoding ? "Stop" : "Start"} decode
      </button>
    </div>
  );
}

// RX input-level indicator. Shows the captured level in dBFS while decoding, and
// a clear "no audio" warning when the input is silent — the case where the source
// (e.g. a DAX/soundcard channel) isn't actually routed and the waterfall is blank.
function AudioBadge({
  decoding,
  audio,
}: {
  decoding: boolean;
  audio: { db: number; silent: boolean } | null;
}) {
  if (!decoding) return null;
  if (!audio) {
    return <span className="muted" title="Waiting for the first input-level reading">audio …</span>;
  }
  if (audio.silent) {
    return (
      <span
        style={{ color: "var(--tx)", fontVariantNumeric: "tabular-nums" }}
        title="No audio is reaching the app. Check that the selected input device is the right one and that its source is routed (e.g. SmartSDR slice → DAX RX channel, soundcard input not muted)."
      >
        ⚠ no audio
      </span>
    );
  }
  return (
    <span
      className="muted"
      style={{ fontVariantNumeric: "tabular-nums" }}
      title="RX input level (dBFS) of the captured audio"
    >
      audio {audio.db.toFixed(0)} dB
    </span>
  );
}

// Format an NTP offset (ms) as a signed seconds string, e.g. "+0.12s".
function fmtOffset(ms: number): string {
  const s = ms / 1000;
  return `${s >= 0 ? "+" : ""}${s.toFixed(2)}s`;
}

// Small clock-health indicator next to the UTC clock. Green when the offset is
// tight enough for clean FT8 (<0.5 s), amber when it's getting risky, grey until
// the first sync lands.
function ClockSyncBadge({ clock }: { clock: ClockSyncEvent | null }) {
  if (!clock || !clock.synced) {
    return <span className="muted" title="Waiting for NTP time sync">sync …</span>;
  }
  const mag = Math.abs(clock.offset_ms);
  const color = mag < 500 ? "var(--ok)" : mag < 1500 ? "var(--tome)" : "var(--tx)";
  return (
    <span
      style={{ color, fontVariantNumeric: "tabular-nums" }}
      title={`Clock offset from NTP: ${clock.offset_ms} ms (applied to decode timing, system clock untouched)`}
    >
      sync {fmtOffset(clock.offset_ms)}
    </span>
  );
}

function WaterfallView({
  txFreq,
  onSelectFreq,
}: {
  txFreq: number;
  onSelectFreq: (hz: number) => void;
}) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    registerWfCanvas(ref.current);
    return () => registerWfCanvas(null);
  }, []);

  function handleClick(e: MouseEvent<HTMLDivElement>) {
    const rect = e.currentTarget.getBoundingClientRect();
    const frac = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
    const hz = Math.round(frac * WF_TOP_HZ);
    onSelectFreq(Math.min(TX_MAX_HZ, Math.max(TX_MIN_HZ, hz)));
  }

  const leftPct = (txFreq / WF_TOP_HZ) * 100;

  return (
    <div
      style={{ position: "relative", marginBottom: 8, cursor: "crosshair" }}
      onClick={handleClick}
      title="Click to set TX frequency"
    >
      <canvas
        ref={ref}
        style={{
          width: "100%",
          height: WF_HEIGHT,
          imageRendering: "pixelated",
          background: "#000",
          borderRadius: 6,
          display: "block",
        }}
      />
      {/* TX frequency marker */}
      <div
        style={{
          position: "absolute",
          top: 0,
          bottom: 0,
          left: `${leftPct}%`,
          width: 2,
          background: "#ff3b30",
          pointerEvents: "none",
        }}
      />
      <div
        style={{
          position: "absolute",
          top: 2,
          left: `calc(${leftPct}% + 4px)`,
          color: "#ff8f87",
          fontSize: 11,
          fontVariantNumeric: "tabular-nums",
          pointerEvents: "none",
          textShadow: "0 0 3px #000",
        }}
      >
        TX {txFreq} Hz
      </div>
    </div>
  );
}

// Map a 0..255 magnitude to a dark-blue -> cyan -> yellow -> white ramp.
function colormap(v: number): [number, number, number] {
  const t = v / 255;
  if (t < 0.25) return [0, 0, Math.round(60 + t * 4 * 140)];
  if (t < 0.5) return [0, Math.round((t - 0.25) * 4 * 200), 200];
  if (t < 0.75) return [Math.round((t - 0.5) * 4 * 230), 230, Math.round(200 - (t - 0.5) * 4 * 200)];
  return [255, Math.round(230 + (t - 0.75) * 4 * 25), Math.round((t - 0.75) * 4 * 200)];
}

function DecodeScreen(props: {
  messages: UiMessage[];
  filter: Filter;
  setFilter: (f: Filter) => void;
  txFreq: number;
  onSelectFreq: (hz: number) => void;
  onAnswer: (m: UiMessage) => void;
}) {
  const { messages, filter, setFilter, txFreq, onSelectFreq, onAnswer } = props;
  return (
    <>
      <WaterfallView txFreq={txFreq} onSelectFreq={onSelectFreq} />
      <div className="chips">
        {(["all", "cq", "tome"] as Filter[]).map((f) => (
          <div key={f} className={"chip" + (filter === f ? " active" : "")} onClick={() => setFilter(f)}>
            {f === "all" ? "All" : f === "cq" ? "CQ" : "To me"}
          </div>
        ))}
      </div>
      <div className="decode-list">
      <table>
        <thead>
          <tr>
            <th>UTC</th>
            <th>dB</th>
            <th>DT</th>
            <th>Hz</th>
            <th>Message</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {messages.map((m, i) => {
            const newBlock = i > 0 && messages[i - 1].utc_ms !== m.utc_ms;
            return (
              <Fragment key={i}>
                {newBlock && (
                  <tr className="time-sep">
                    <td colSpan={6}>{new Date(m.utc_ms).toISOString().substring(11, 19)} UTC</td>
                  </tr>
                )}
                <tr
                  className={
                    (m.is_cq ? "cq " : "") +
                    (m.to_me ? "tome " : "") +
                    (m.is_cq || m.to_me ? "clickable" : "")
                  }
                  onClick={() => (m.is_cq || m.to_me) && onAnswer(m)}
                  title={m.is_cq || m.to_me ? "Click to answer" : ""}
                >
                  <td>{new Date(m.utc_ms).toISOString().substring(11, 19)}</td>
                  <td>{m.snr}</td>
                  <td>{m.time_sec.toFixed(1)}</td>
                  <td>{Math.round(m.freq_hz)}</td>
                  <td>{m.text}</td>
                  <td>{m.is_cq || m.to_me ? "↩ answer" : ""}</td>
                </tr>
              </Fragment>
            );
          })}
          {messages.length === 0 && (
            <tr>
              <td colSpan={6} className="muted">
                No decodes yet — set your callsign in Settings, pick an audio input, and Start decode.
              </td>
            </tr>
          )}
        </tbody>
      </table>
      </div>
    </>
  );
}

function LogScreen() {
  const [rows, setRows] = useState<QsoRecord[]>([]);
  const [count, setCount] = useState(0);

  function refresh() {
    api.listLog(500, 0).then(setRows);
    api.logCount().then(setCount);
  }
  useEffect(refresh, []);

  async function exportAdif() {
    const adif = await api.exportAdif();
    const blob = new Blob([adif], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "ft8af_log.adi";
    a.click();
    URL.revokeObjectURL(url);
  }

  async function remove(id?: number | null) {
    if (id == null) return;
    await api.deleteQso(id);
    refresh();
  }

  return (
    <>
      <div className="row" style={{ marginBottom: 10 }}>
        <strong>{count}</strong> <span className="muted">QSOs</span>
        <div className="spacer" style={{ flex: 1 }} />
        <button onClick={refresh}>Refresh</button>
        <button className="primary" onClick={exportAdif}>Export ADIF</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>Date</th>
            <th>Time</th>
            <th>Call</th>
            <th>Grid</th>
            <th>Band</th>
            <th>Sent</th>
            <th>Rcvd</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td>{r.qso_date}</td>
              <td>{r.time_on}</td>
              <td>{r.call}</td>
              <td>{r.gridsquare}</td>
              <td>{r.band}</td>
              <td>{r.rst_sent}</td>
              <td>{r.rst_rcvd}</td>
              <td>
                <button onClick={() => remove(r.id)}>✕</button>
              </td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={8} className="muted">No QSOs logged yet.</td>
            </tr>
          )}
        </tbody>
      </table>
    </>
  );
}

// Waterfall FFT developer-knob option lists (issue #428). Single source of
// truth for both the <select> options and validation of persisted config —
// raw config strings are untrusted (hand-edited DB, older builds), so
// anything outside these lists falls back to the defaults.
const WF_WINDOWS: WfWindow[] = ["rect", "hann", "hamming", "blackman", "blackman_harris"];
const WF_WINDOW_LABELS: Record<WfWindow, string> = {
  rect: "Rectangular (none)",
  hann: "Hann (default)",
  hamming: "Hamming",
  blackman: "Blackman",
  blackman_harris: "Blackman-Harris",
};
const WF_FFT_SIZES = [512, 1024, 2048, 4096, 8192];
const WF_AVG_OPTIONS = [1, 2, 3, 4, 6, 8, 12, 16];

function SettingsScreen(props: {
  onStatus: (s: string) => void;
  clock: ClockSyncEvent | null;
  onRigLabel: (v: string) => void;
}) {
  const { onStatus, clock, onRigLabel } = props;
  const [call, setCall] = useState("");
  const [rigLabel, setRigLabel] = useState("");
  const [grid, setGrid] = useState("");
  const [inputs, setInputs] = useState<AudioDevice[]>([]);
  const [outputs, setOutputs] = useState<AudioDevice[]>([]);
  const [ports, setPorts] = useState<SerialPortInfo[]>([]);
  const [hamlibRigs, setHamlibRigs] = useState<HamlibRig[]>([]);
  const [input, setInput] = useState("");
  const [output, setOutput] = useState("");
  const [baseFreq, setBaseFreq] = useState(1500);
  // Live-waterfall FFT developer knobs (issue #428). Defaults mirror
  // WfConfig::default() on the Rust side: hann / 2048 / 6.
  const [wfCfg, setWfCfg] = useState<WaterfallConfig>({
    window: "hann",
    fft_size: 2048,
    avg: 6,
  });
  const [rigCfg, setRigCfg] = useState<RigConfig>({
    backend: "hamlib",
    model: "none",
    port: "",
    baud: 38400,
    ptt: "cat",
    civ_address: 0x94,
    flrig_host: "127.0.0.1",
    flrig_port: 12345,
    hamlib_model: 1,
    hamlib_network: false,
    hamlib_address: "127.0.0.1:4992",
  });

  useEffect(() => {
    api.listAudioInputs().then(setInputs);
    api.listAudioOutputs().then(setOutputs);
    api.listSerialPorts().then(setPorts);
    api.listHamlibRigs().then(setHamlibRigs);
    api.getConfig("my_call").then((v) => v && setCall(v));
    api.getConfig("my_grid").then((v) => v && setGrid(v));
    api.getConfig("input_device").then((v) => v && setInput(v));
    api.getConfig("output_device").then((v) => v && setOutput(v));
    api.getConfig("base_freq").then((v) => v && setBaseFreq(parseInt(v, 10)));
    api.getConfig("rig_config").then((v) => {
      if (v) {
        try {
          setRigCfg(JSON.parse(v));
        } catch {
          /* ignore malformed saved config */
        }
      }
    });
    api.getConfig("rig_label").then((v) => v && setRigLabel(v));
    // Restore persisted waterfall knobs (written by the engine on change),
    // keeping only values from the option lists — an unknown window string or
    // NaN size would leave the <select>s blank and push an invalid config
    // back through applyWfCfg.
    Promise.all([
      api.getConfig("wf_window"),
      api.getConfig("wf_fft_size"),
      api.getConfig("wf_avg"),
    ]).then(([w, size, avg]) => {
      const fftSize = parseInt(size ?? "", 10);
      const avgN = parseInt(avg ?? "", 10);
      setWfCfg((prev) => ({
        window: WF_WINDOWS.includes(w as WfWindow) ? (w as WfWindow) : prev.window,
        fft_size: WF_FFT_SIZES.includes(fftSize) ? fftSize : prev.fft_size,
        avg: WF_AVG_OPTIONS.includes(avgN) ? avgN : prev.avg,
      }));
    });
  }, []);

  function applyWfCfg(next: WaterfallConfig) {
    setWfCfg(next);
    api.setWaterfallConfig(next);
  }

  const refreshPorts = () => api.listSerialPorts().then(setPorts);

  // Re-enumerate serial ports whenever a port-list backend becomes visible.
  // Ports were previously read only once at mount, so a rig attached after
  // launch — or Hamlib selected first, before its serial port was chosen —
  // showed an empty/stale list. Switching to Hamlib (serial) or Direct serial
  // now re-scans, so /dev/ttyACM0 (a QMX/QDX) appears without the
  // select-Direct-then-Hamlib workaround.
  const showsPortList =
    rigCfg.backend === "serial" ||
    (rigCfg.backend === "hamlib" && !rigCfg.hamlib_network);
  useEffect(() => {
    if (showsPortList) refreshPorts();
  }, [showsPortList]);

  function saveStation() {
    api.setStation(call, grid);
    onStatus(`Station set: ${call} ${grid}`);
  }

  return (
    <div className="grid2">
      <div className="panel">
        <h3>Station</h3>
        <div className="col">
          <div className="field">
            <label>Callsign</label>
            <input value={call} onChange={(e) => setCall(e.target.value.toUpperCase())} placeholder="K0XYZ" />
          </div>
          <div className="field">
            <label>Grid (Maidenhead)</label>
            <input value={grid} onChange={(e) => setGrid(e.target.value.toUpperCase())} placeholder="EN37" />
          </div>
          <button className="primary" onClick={saveStation}>Save station</button>
        </div>
      </div>

      <div className="panel">
        <h3>Time sync</h3>
        <div className="col">
          <div className="muted">
            FT8 needs the clock within ~1 s of UTC. The app syncs to NTP on its own —
            no external time software, and your system clock is left untouched.
          </div>
          <div className="field">
            <label>Clock offset from UTC</label>
            <div>
              {clock?.synced
                ? `${fmtOffset(clock.offset_ms)} (${clock.offset_ms} ms)`
                : "waiting for first sync…"}
            </div>
          </div>
          <div className="field">
            <label>DT auto-calibration (audio latency)</label>
            <div>
              {clock ? `${fmtOffset(clock.rx_offset_ms)} (${clock.rx_offset_ms} ms)` : "—"}
              <span className="muted" style={{ display: "block", marginTop: 4 }}>
                Auto-tuned from decoded DT to keep the RX window aligned with your
                soundcard/SDR latency. Self-corrects over a few cycles.
              </span>
            </div>
          </div>
          <button
            onClick={() => {
              api.resyncTime();
              onStatus("Re-syncing clock to NTP…");
            }}
          >
            Sync now
          </button>
        </div>
      </div>

      <div className="panel">
        <h3>Audio</h3>
        <div className="col">
          <div className="field">
            <label>Input device (RX)</label>
            <select
              id="audio-input-select"
              value={input}
              onChange={(e) => {
                setInput(e.target.value);
                api.setInputDevice(e.target.value || null);
              }}
            >
              <option value="">System default</option>
              {inputs.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name} {d.is_default ? "(default)" : ""}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Output device (TX)</label>
            <select
              id="audio-output-select"
              value={output}
              onChange={(e) => {
                setOutput(e.target.value);
                api.setOutputDevice(e.target.value || null);
              }}
            >
              <option value="">System default</option>
              {outputs.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name} {d.is_default ? "(default)" : ""}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>TX audio offset: {baseFreq} Hz</label>
            <input
              type="range"
              min={200}
              max={2800}
              step={1}
              value={baseFreq}
              onChange={(e) => {
                const v = parseInt(e.target.value, 10);
                setBaseFreq(v);
                api.setBaseFreq(v);
              }}
            />
          </div>
        </div>
      </div>

      <div className="panel">
        <h3>Rig control</h3>
        <div className="col">
          <div className="field">
            <label>Display name (optional)</label>
            <input
              value={rigLabel}
              onChange={(e) => {
                setRigLabel(e.target.value);
                onRigLabel(e.target.value);
              }}
              placeholder="e.g. Flex 6400"
            />
            <span className="muted" style={{ display: "block", marginTop: 4 }}>
              Shown in the top bar instead of the CAT model. Useful when connecting
              a radio through an emulator (e.g. a Flex via SmartSDR CAT reports as
              Kenwood TS-2000). Leave blank to show the detected model.
            </span>
          </div>
          <div className="field">
            <label>Backend</label>
            <select
              id="rig-backend-select"
              value={rigCfg.backend}
              onChange={(e) => setRigCfg({ ...rigCfg, backend: e.target.value as RigConfig["backend"] })}
            >
              <option value="hamlib">Hamlib (embedded library)</option>
              <option value="flrig">FLrig (XML-RPC)</option>
              <option value="serial">Direct serial CAT</option>
              <option value="none">None (manual tuning)</option>
            </select>
          </div>

          {rigCfg.backend === "hamlib" && (
            <>
              <div className="field">
                <label>Radio {hamlibRigs.length > 0 ? `(${hamlibRigs.length} supported)` : ""}</label>
                {hamlibRigs.length > 0 ? (
                  <select
                    id="rig-radio-select"
                    value={rigCfg.hamlib_model}
                    onChange={(e) =>
                      setRigCfg({ ...rigCfg, hamlib_model: parseInt(e.target.value, 10) })
                    }
                  >
                    {hamlibRigs.map((r) => (
                      <option key={r.model} value={r.model}>
                        {r.name} — #{r.model}
                      </option>
                    ))}
                  </select>
                ) : (
                  <input
                    value={rigCfg.hamlib_model}
                    onChange={(e) =>
                      setRigCfg({ ...rigCfg, hamlib_model: parseInt(e.target.value, 10) || 1 })
                    }
                    placeholder="model # (rigctl -l)"
                    style={{ width: 120 }}
                  />
                )}
              </div>
              <div className="field">
                <label>Connection</label>
                <select
                  id="rig-connection-select"
                  value={rigCfg.hamlib_network ? "network" : "serial"}
                  onChange={(e) => setRigCfg({ ...rigCfg, hamlib_network: e.target.value === "network" })}
                >
                  <option value="serial">Serial port (COM)</option>
                  <option value="network">Network (TCP host:port)</option>
                </select>
              </div>

              {rigCfg.hamlib_network ? (
                <div className="field">
                  <label>Network address (host:port)</label>
                  <input
                    value={rigCfg.hamlib_address}
                    onChange={(e) => setRigCfg({ ...rigCfg, hamlib_address: e.target.value })}
                    placeholder="127.0.0.1:4992"
                    style={{ width: 180 }}
                  />
                  <span className="muted" style={{ display: "block", marginTop: 4 }}>
                    Native FlexRadio 6xxx model: <code>&lt;radio-IP&gt;:4992</code>. For
                    SmartSDR CAT's TCP port, use model <b>Kenwood TS-2000</b> +{" "}
                    <code>127.0.0.1:&lt;cat-port&gt;</code> (e.g. 5002). Use 127.0.0.1, not
                    localhost.
                  </span>
                </div>
              ) : (
                <div className="row">
                  <div className="field">
                    <label>Serial port</label>
                    <div className="row">
                      <select id="rig-serial-port-select" value={rigCfg.port} onChange={(e) => setRigCfg({ ...rigCfg, port: e.target.value })}>
                        <option value="">(none — e.g. Dummy)</option>
                        {ports.map((p) => (
                          <option key={p.name} value={p.name}>
                            {p.name} ({p.kind})
                          </option>
                        ))}
                      </select>
                      <button type="button" title="Re-scan serial ports" onClick={refreshPorts}>
                        ↻
                      </button>
                    </div>
                  </div>
                  <div className="field">
                    <label>Baud</label>
                    <select
                      id="rig-baud-select"
                      value={rigCfg.baud}
                      onChange={(e) => setRigCfg({ ...rigCfg, baud: parseInt(e.target.value, 10) })}
                    >
                      {[4800, 9600, 19200, 38400, 57600, 115200].map((b) => (
                        <option key={b} value={b}>{b}</option>
                      ))}
                    </select>
                  </div>
                </div>
              )}
              {hamlibRigs.length === 0 && (
                <span className="muted">
                  Hamlib library not found — install Hamlib and put <code>hamlib-4.dll</code> next
                  to the app or on PATH, then reopen. (1 = Dummy, no hardware.)
                </span>
              )}
            </>
          )}

          {rigCfg.backend === "flrig" && (
            <div className="row">
              <div className="field">
                <label>FLrig host</label>
                <input
                  value={rigCfg.flrig_host}
                  onChange={(e) => setRigCfg({ ...rigCfg, flrig_host: e.target.value })}
                  style={{ width: 130 }}
                />
              </div>
              <div className="field">
                <label>Port</label>
                <input
                  value={rigCfg.flrig_port}
                  onChange={(e) =>
                    setRigCfg({ ...rigCfg, flrig_port: parseInt(e.target.value, 10) || 12345 })
                  }
                  style={{ width: 80 }}
                />
              </div>
            </div>
          )}

          {rigCfg.backend === "serial" && (
            <>
              <div className="field">
                <label>Model</label>
                <select
                  value={rigCfg.model}
                  onChange={(e) => setRigCfg({ ...rigCfg, model: e.target.value as RigConfig["model"] })}
                >
                  <option value="none">— select —</option>
                  <option value="yaesu">Yaesu (CAT)</option>
                  <option value="kenwood">Kenwood</option>
                  <option value="icom">Icom (CI-V)</option>
                </select>
              </div>
              <div className="field">
                <label>Serial port</label>
                <div className="row">
                  <select id="rig-serial-port-select" value={rigCfg.port} onChange={(e) => setRigCfg({ ...rigCfg, port: e.target.value })}>
                    <option value="">— select —</option>
                    {ports.map((p) => (
                      <option key={p.name} value={p.name}>
                        {p.name} ({p.kind})
                      </option>
                    ))}
                  </select>
                  <button type="button" title="Re-scan serial ports" onClick={refreshPorts}>
                    ↻
                  </button>
                </div>
              </div>
              <div className="row">
                <div className="field">
                  <label>Baud</label>
                  <select
                    id="rig-baud-select"
                    value={rigCfg.baud}
                    onChange={(e) => setRigCfg({ ...rigCfg, baud: parseInt(e.target.value, 10) })}
                  >
                    {[4800, 9600, 19200, 38400, 57600, 115200].map((b) => (
                      <option key={b} value={b}>{b}</option>
                    ))}
                  </select>
                </div>
                <div className="field">
                  <label>PTT</label>
                  <select
                    value={rigCfg.ptt}
                    onChange={(e) => setRigCfg({ ...rigCfg, ptt: e.target.value as RigConfig["ptt"] })}
                  >
                    <option value="cat">CAT</option>
                    <option value="rts">RTS</option>
                    <option value="dtr">DTR</option>
                    <option value="none">None / VOX</option>
                  </select>
                </div>
                {rigCfg.model === "icom" && (
                  <div className="field">
                    <label>CI-V addr (hex)</label>
                    <input
                      value={rigCfg.civ_address.toString(16)}
                      onChange={(e) =>
                        setRigCfg({ ...rigCfg, civ_address: parseInt(e.target.value, 16) || 0x94 })
                      }
                      style={{ width: 60 }}
                    />
                  </div>
                )}
              </div>
            </>
          )}

          <button
            className="primary"
            onClick={() => {
              api.selectRig(rigCfg);
              const where =
                rigCfg.backend === "flrig"
                  ? `${rigCfg.flrig_host}:${rigCfg.flrig_port}`
                  : rigCfg.backend === "serial"
                  ? `${rigCfg.model} on ${rigCfg.port || "(no port)"}`
                  : rigCfg.backend === "hamlib"
                  ? `model ${rigCfg.hamlib_model} on ${
                      rigCfg.hamlib_network ? rigCfg.hamlib_address : rigCfg.port || "(no port)"
                    }`
                  : "manual";
              onStatus(`Connecting rig (${rigCfg.backend}): ${where}`);
            }}
          >
            Connect rig
          </button>
          <button
            onClick={() => {
              api.disconnectRig();
              onStatus("Rig disconnected");
            }}
          >
            Disconnect
          </button>
        </div>
      </div>

      <div className="panel">
        <h3>Developer — waterfall FFT</h3>
        <div className="col">
          <div className="muted">
            Spectrum/waterfall rendering experiments (issue #428) — affects the
            display only, never decoding. Defaults: Hann, 2048, 6 averages.
          </div>
          <div className="field">
            <label>Window function</label>
            <select
              id="wf-window-select"
              value={wfCfg.window}
              onChange={(e) => applyWfCfg({ ...wfCfg, window: e.target.value as WfWindow })}
            >
              {WF_WINDOWS.map((w) => (
                <option key={w} value={w}>
                  {WF_WINDOW_LABELS[w]}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>FFT size</label>
            <select
              id="wf-fft-size-select"
              value={wfCfg.fft_size}
              onChange={(e) => applyWfCfg({ ...wfCfg, fft_size: parseInt(e.target.value, 10) })}
            >
              {WF_FFT_SIZES.map((n) => (
                <option key={n} value={n}>
                  {n} — {(12000 / n).toFixed(1)} Hz/bin{n === 2048 ? " (default)" : ""}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Averaging (Welch segments per row)</label>
            <select
              id="wf-avg-select"
              value={wfCfg.avg}
              onChange={(e) => applyWfCfg({ ...wfCfg, avg: parseInt(e.target.value, 10) })}
            >
              {WF_AVG_OPTIONS.map((n) => (
                <option key={n} value={n}>
                  {n}{n === 6 ? " (default)" : n === 1 ? " (off)" : ""}
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>
    </div>
  );
}

// The operator-selectable QSO messages, in WSJT-X Tx1–Tx5 order. Each rebuilds
// the outgoing message from the current target/report on the backend.
const TX_STAGES: { stage: TxStage; label: string; title: string }[] = [
  { stage: "grid", label: "Grid", title: "Tx1 — send my grid" },
  { stage: "report", label: "Rpt", title: "Tx2 — send signal report" },
  { stage: "r_report", label: "R+Rpt", title: "Tx3 — send R + report" },
  { stage: "rr73", label: "RR73", title: "Tx4 — send RR73" },
  { stage: "bye73", label: "73", title: "Tx5 — send 73" },
];

function TxBar(props: {
  txState: TxStateEvent | null;
  decoding: boolean;
  onCq: () => void;
  onStage: (s: TxStage) => void;
  onStop: () => void;
}) {
  const { txState, decoding, onCq, onStage, onStop } = props;
  const tx = txState?.transmitting;
  const stage = txState?.status.stage ?? "idle";
  const target = txState?.status.target ?? null;
  // Outside a QSO the auto-sequencer stage is "idle", which reads as "nothing
  // happening" even while we're decoding — show the receiver's real activity then.
  const stageLabel = stage === "idle" ? (decoding ? "listening" : "stopped") : stage;
  return (
    <div className="txbar">
      <span className={"badge " + (tx ? "tx" : "rx")}>{tx ? "TX" : "RX"}</span>
      <span className="muted">stage: {stageLabel}</span>
      {target && <span className="muted">→ {target}</span>}
      <span className="msg">{txState?.message ?? ""}</span>
      <div className="spacer" style={{ flex: 1 }} />
      <div className="stage-picker" title={target ? "" : "Pick a station first"}>
        {TX_STAGES.map((s) => (
          <button
            key={s.stage}
            className={stage === s.stage ? "active" : ""}
            disabled={!target}
            title={s.title}
            onClick={() => onStage(s.stage)}
          >
            {s.label}
          </button>
        ))}
      </div>
      <button className="primary" onClick={onCq}>Call CQ</button>
      <button className="danger" onClick={onStop}>Stop TX</button>
    </div>
  );
}
