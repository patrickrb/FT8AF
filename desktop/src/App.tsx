import { Fragment, useEffect, useRef, useState, type MouseEvent } from "react";
import {
  api,
  onEngineEvent,
  type AudioDevice,
  type BandInfo,
  type CycleTick,
  type EngineEvent,
  type HamlibRig,
  type QsoRecord,
  type RigConfig,
  type RigStatusEvent,
  type SerialPortInfo,
  type TxStateEvent,
  type UiMessage,
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
function drawWaterfallRow(bins: number[]) {
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
}

export default function App() {
  const [tab, setTab] = useState<Tab>("decode");
  const [messages, setMessages] = useState<UiMessage[]>([]);
  const [cycle, setCycle] = useState<CycleTick | null>(null);
  const [txState, setTxState] = useState<TxStateEvent | null>(null);
  const [rig, setRig] = useState<RigStatusEvent | null>(null);
  const [decoding, setDecoding] = useState(false);
  const [status, setStatus] = useState("");
  const [filter, setFilter] = useState<Filter>("all");
  const [txFreq, setTxFreq] = useState(1500);
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
    });
    api.listBands().then(setBands);
    api.getConfig("dial_hz").then((v) => {
      if (v) setDialHz(parseInt(v, 10));
    });
    api.getConfig("base_freq").then((v) => {
      if (v) setTxFreq(parseInt(v, 10));
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
      case "waterfall_row":
        drawWaterfallRow(e.data.bins);
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
      />
      <div className="tabs">
        {(["decode", "log", "settings"] as Tab[]).map((t) => (
          <button key={t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>
            {t === "decode" ? "Decode" : t === "log" ? "Logbook" : "Settings"}
          </button>
        ))}
      </div>
      <div className="content">
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
        {tab === "settings" && <SettingsScreen onStatus={setStatus} />}
      </div>
      <TxBar txState={txState} onCq={() => api.startCq()} onStop={() => api.stopTx()} />
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
}) {
  const { cycle, decoding, onToggleDecode, bands, dialHz, onBand, rig } = props;
  const utc = cycle ? new Date(cycle.utc_ms).toISOString().substring(11, 19) : "--:--:--";
  const pct = cycle ? (cycle.ms_into_cycle / 15000) * 100 : 0;
  return (
    <div className="topbar">
      <span className="brand">FT8AF</span>
      <span className="clock">{utc}</span>
      <div className="cycle-bar">
        <div style={{ width: `${pct}%` }} />
      </div>
      <span className="seq">seq {cycle?.sequential ?? "-"}</span>
      <div className="spacer" />
      <select value={dialHz} onChange={(e) => onBand(parseInt(e.target.value, 10))}>
        {bands.map((b) => (
          <option key={b.name} value={b.dial_hz}>
            {b.name} · {(b.dial_hz / 1e6).toFixed(3)}
          </option>
        ))}
      </select>
      <span className="muted">
        {rig?.connected ? `rig: ${rig.model}` : "no rig"}
        {rig?.ptt ? " · PTT" : ""}
      </span>
      <button className={decoding ? "danger" : "primary"} onClick={onToggleDecode}>
        {decoding ? "Stop" : "Start"} decode
      </button>
    </div>
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
                  className={(m.is_cq ? "cq " : "") + (m.to_me ? "tome " : "") + (m.is_cq ? "clickable" : "")}
                  onClick={() => m.is_cq && onAnswer(m)}
                  title={m.is_cq ? "Click to answer" : ""}
                >
                  <td>{new Date(m.utc_ms).toISOString().substring(11, 19)}</td>
                  <td>{m.snr}</td>
                  <td>{m.time_sec.toFixed(1)}</td>
                  <td>{Math.round(m.freq_hz)}</td>
                  <td>{m.text}</td>
                  <td>{m.is_cq ? "↩ answer" : ""}</td>
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

function SettingsScreen(props: { onStatus: (s: string) => void }) {
  const { onStatus } = props;
  const [call, setCall] = useState("");
  const [grid, setGrid] = useState("");
  const [inputs, setInputs] = useState<AudioDevice[]>([]);
  const [outputs, setOutputs] = useState<AudioDevice[]>([]);
  const [ports, setPorts] = useState<SerialPortInfo[]>([]);
  const [hamlibRigs, setHamlibRigs] = useState<HamlibRig[]>([]);
  const [input, setInput] = useState("");
  const [output, setOutput] = useState("");
  const [baseFreq, setBaseFreq] = useState(1500);
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
  }, []);

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
        <h3>Audio</h3>
        <div className="col">
          <div className="field">
            <label>Input device (RX)</label>
            <select
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
            <label>Backend</label>
            <select
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
                    <select value={rigCfg.port} onChange={(e) => setRigCfg({ ...rigCfg, port: e.target.value })}>
                      <option value="">(none — e.g. Dummy)</option>
                      {ports.map((p) => (
                        <option key={p.name} value={p.name}>
                          {p.name} ({p.kind})
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="field">
                    <label>Baud</label>
                    <select
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
                <select value={rigCfg.port} onChange={(e) => setRigCfg({ ...rigCfg, port: e.target.value })}>
                  <option value="">— select —</option>
                  {ports.map((p) => (
                    <option key={p.name} value={p.name}>
                      {p.name} ({p.kind})
                    </option>
                  ))}
                </select>
              </div>
              <div className="row">
                <div className="field">
                  <label>Baud</label>
                  <select
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
        </div>
      </div>
    </div>
  );
}

function TxBar(props: { txState: TxStateEvent | null; onCq: () => void; onStop: () => void }) {
  const { txState, onCq, onStop } = props;
  const tx = txState?.transmitting;
  const stage = txState?.status.stage ?? "idle";
  return (
    <div className="txbar">
      <span className={"badge " + (tx ? "tx" : "rx")}>{tx ? "TX" : "RX"}</span>
      <span className="muted">stage: {stage}</span>
      {txState?.status.target && <span className="muted">→ {txState.status.target}</span>}
      <span className="msg">{txState?.message ?? ""}</span>
      <div className="spacer" style={{ flex: 1 }} />
      <button className="primary" onClick={onCq}>Call CQ</button>
      <button className="danger" onClick={onStop}>Stop TX</button>
    </div>
  );
}
