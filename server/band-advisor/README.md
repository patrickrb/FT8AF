# ft8af-band-advisor

Server-side scaffold for the FT8AF **Band Advisor** feature: a Python 3.11+
FastAPI service that fuses **VOACAP propagation predictions** with
**centralized PSK Reporter observations** into a deterministic per-band
recommendation for the mobile apps. Runs in a Linux container behind the
ft8af.app API.

**License note:** predictions come from `voacapl`
(<https://github.com/jawatson/voacapl>, James Watson's GPL Linux port of
NTIA/ITS VOACAP), invoked strictly as an **external tool** via subprocess.
No VOACAP/voacapl code is copied, vendored, or linked into this service; the
Dockerfile builds it from source in a separate stage.

## Architecture

```
app/
  api.py               FastAPI endpoints, validation, error contract, JSON logs
  recommendation.py    orchestration + the client-facing response contract
  scoring.py           pure weighted scoring + separate confidence function
  config.py            env-var configuration
  geo.py               Maidenhead grids, haversine, azimuths (pure)
  bands.py             the 10 FT8 dial frequencies + band mapping
  ttl_cache.py         small in-memory TTL cache (optional JSON persistence)
  voacap/
    input_builder.py   voacapx.dat card decks (power/antenna/noise classes)
    ft8_profile.py     FT8 REQ.SNR profile — the dB vs dB-Hz conversion
    runner.py          subprocess wrapper (short Fortran-safe run names)
    parser.py          Method-30 output parser (defensive)
    cache.py           TTL cache keyed by the full request tuple
    regions.py         target region centroids + REGIONAL / LONG_DX
  pskreporter/
    client.py          rate-limited HTTP client (gzip, defusedxml, backoff)
    aggregator.py      per-band stats (distance, octants, nearby-TX, quality)
    baseline.py        EWMA "typical activity" per (band, hour) + trend
    cache.py           field-granularity 5-min cache, 10-min personal cache
    service.py         facade used by the recommendation layer
```

Both upstream integrations degrade independently: if VOACAP or PSK Reporter
is down, the API still answers 200 with `sources.*Available: false`, the
missing score components are dropped (weights renormalized), and confidence
drops. An upstream outage is never a 500.

## Endpoints

### `GET /healthz`

```
curl http://localhost:8000/healthz
{"status":"ok","service":"ft8af-band-advisor","version":"0.1.0",
 "voacaplPresent":true,"voacaplPath":"/usr/local/bin/voacapl"}
```

### `GET /v1/feature-config`

Serves the mounted feature-flag document (`FEATURE_CONFIG_PATH`), stamped
with `generatedAt` and `expiresAt = now + FEATURE_CONFIG_TTL_S` (default 6 h):

```
curl http://localhost:8000/v1/feature-config
{"version":1,"generatedAt":"2026-09-27T12:00:00Z","expiresAt":"2026-09-27T18:00:00Z",
 "flags":{"bandAdvisor":true,"personalPskAnalytics":true,"propagationAlerts":false}}
```

### `GET /v1/recommendation`

```
curl "http://localhost:8000/v1/recommendation?grid=EM28&goal=MAKE_CONTACT&mode=FT8&power=STANDARD&antenna=WIRE&noise=RESIDENTIAL&callsign=K1AF"
```

Parameters:

| param | values | default |
|---|---|---|
| `grid` | Maidenhead; truncated to 4 chars, must match `[A-R]{2}[0-9]{2}` | required |
| `goal` | `MAKE_CONTACT`, `DX`, `TARGET`, `POTA` | `MAKE_CONTACT` |
| `mode` | `FT8` | `FT8` |
| `power` | `QRP` (5 W), `LOW` (25 W), `STANDARD` (100 W), `HIGH` (1000 W) | `STANDARD` |
| `antenna` | `ISOTROPE`, `WIRE`, `VERTICAL`, `BEAM` | `WIRE` |
| `noise` | `QUIET` (-164), `RURAL` (-154), `RESIDENTIAL` (-145), `URBAN` (-136 dBW/Hz) | `RESIDENTIAL` |
| `callsign` | valid amateur callsign; enables personal analytics | omitted |
| `targetRegion` | `EUROPE`, `NORTH_AMERICA_EAST`, `NORTH_AMERICA_WEST`, `SOUTH_AMERICA`, `AFRICA`, `ASIA`, `OCEANIA`, `REGIONAL`, `LONG_DX`; required when `goal=TARGET` | goal-dependent |

Response: the exact contract the clients parse (see
`app/recommendation.py`; example in the repo brief). Notes:

* `callsign` and `personalAnalytics` are **omitted** when no callsign param
  was sent; `personalAnalytics` is `null` when requested but unavailable.
* `scoreComponents[].weight` are the renormalized weights (sum to 1).
* Validation failures return HTTP 400 with
  `{"error":{"code":"INVALID_GRID","message":"..."}}` — deterministic codes:
  `MISSING_PARAMETER`, `INVALID_GRID`, `INVALID_PARAMETER`,
  `INVALID_CALLSIGN`.

### `GET /v1/conditions?grid=EM28`

Cheap pre-aggregated per-band summary for the mobile alert checker:
`bands[] = {band, score, confidence, activeRegions, openingEvidence}` sorted
best-first, plus `sources`.

## The FT8 SNR conversion (why REQ.SNR = 16, not -21)

VOACAP's REQ.SNR is in **dB-Hz** (1 Hz reference bandwidth). The famous FT8
threshold of **-21 dB** is referenced to a **2500 Hz** SSB bandwidth
(WSJT-X reporting convention). Conversion:

```
-21 dB + 10*log10(2500) = -21 + 34.0 ≈ 13 dB-Hz
```

Defaults (configurable in `app/voacap/ft8_profile.py`):
`required_snr_dbhz = 13`, plus `snr_margin_db = 3` of real-world margin
(fading between 15 s slots, multi-signal QRM, imperfect audio chains) →
**REQ.SNR card = 16 dB-Hz**. The -21 dB figure is the *a-priori
single-decode* threshold; deep/AP decodes go a few dB lower but are not a
sound planning baseline.

## PSK Reporter etiquette (implemented)

* Phones never poll `retrieve.pskreporter.info` — this service is the single
  **centralized** aggregation point.
* `Accept-Encoding: gzip` on every request; responses decompressed by httpx.
* `appcontact=<PSK_CONTACT_EMAIL>` on every request.
* Per-parameter-set minimum interval of **5 minutes**, enforced twice:
  by the cache TTL and again inside the client.
* **15-minute backoff** after HTTP 429/503.
* XML parsed with `defusedxml`; malformed XML is rejected, reports with bad
  grids / absurd frequencies are dropped individually.

## Cache keying

| cache | key | TTL |
|---|---|---|
| VOACAP | `(tx_grid_4char, month, utc_hour, power_class, antenna_class, noise_class, target_region)` | 6 h (memory bound — predictions are stable) |
| PSK regional | `(observer_2char_FIELD, band\|ALL, mode, window)` — nearby users share entries | 5 min |
| PSK personal | `(callsign, mode, window)` | 10 min |

One voacapl run predicts all 24 hours, so a single engine invocation
populates 24 cache entries.

## Data retention

Only **aggregates** are stored (per-band counts, medians, octants). The
per-callsign cache lives at most its 10-minute TTL (≤ 1 h by policy) and is
memory-only. No long-term per-user storage; logs carry at most a 4-char
grid square, never GPS coordinates, callsigns-with-locations, or tokens.

## Configuration (environment variables)

| var | default | meaning |
|---|---|---|
| `VOACAPL_PATH` | `/usr/local/bin/voacapl` | engine binary |
| `ITSHFBC_PATH` | `/opt/itshfbc` | VOACAP data tree (runs live in `<itshfbc>/run/`) |
| `VOACAP_TIMEOUT_S` | `20` | subprocess timeout |
| `VOACAP_CACHE_TTL_S` | `21600` | prediction cache TTL |
| `VOACAP_CACHE_DISK_PATH` | unset | optional JSON persistence for the prediction cache |
| `SUNSPOT_NUMBER` | `68` | SSN for the SUNSPOT card (TODO: live feed) |
| `PSK_CONTACT_EMAIL` | `ops@ft8af.app` | `appcontact` parameter — set a real address |
| `PSK_TIMEOUT_S` / `PSK_WINDOW_S` | `20` / `900` | HTTP timeout / `flowStartSeconds` window |
| `PSK_CACHE_TTL_S` / `PSK_MIN_INTERVAL_S` / `PSK_BACKOFF_S` | `300` / `300` / `900` | etiquette knobs |
| `PERSONAL_CACHE_TTL_S` | `600` | per-callsign cache |
| `BASELINE_PATH` | `/var/lib/band-advisor/baseline.json` | EWMA activity baseline |
| `FEATURE_CONFIG_PATH` | `/etc/band-advisor/feature-config.json` | mounted flag document |
| `FEATURE_CONFIG_TTL_S` | `21600` | `expiresAt` horizon |
| `RECOMMENDATION_VALID_S` | `900` | `validUntil` horizon |

## Development

```
cd server/band-advisor
python3 -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'
pytest
```

No test touches the network or a real voacapl binary — HTTP is served by
`httpx.MockTransport`, the engine by fake runners, and the parser by the
checked-in fixture `tests/fixtures/voacapx.out`.

Run locally (upstream calls will be live — mind the PSK contact email):

```
uvicorn app.main:app --reload
```

## Deployment

```
docker build -t ft8af-band-advisor .
docker run -p 8000:8000 \
  -e PSK_CONTACT_EMAIL=you@example.com \
  -v $PWD/feature-config.example.json:/etc/band-advisor/feature-config.json:ro \
  ft8af-band-advisor
```

or `docker compose up --build`. The image is multi-stage: stage 1 builds
voacapl + the itshfbc data tree from source with gfortran/autotools; stage 2
is `python:3.12-slim` with a non-root user and a `/healthz` HEALTHCHECK.

**Fortran gotcha (do not "fix"):** voacapl inherits VOACAP's fixed-length
filename buffers. Run decks must live in `<itshfbc>/run/`, be passed as
short *relative* names on the command line, and stay under ~30 characters —
hence the `ba<6 hex>.dat` run names in `app/voacap/runner.py`.

## Not yet implemented / next steps (honest list)

* **voacapl deck/CLI verification** — the card column layout and the
  `voacapl <itshfbc> <in> <out>` invocation follow published samples and
  pythonprop conventions but have not yet been validated against a real
  voacapl build (tests use fixtures). Do one real run in the container and
  lock a real output file in as a fixture.
* **Antenna patterns** — power classes map to generic built-in antenna cards
  (isotrope/dipole/whip); `BEAM` is modelled as a dipole. Real per-band gain
  files are a follow-up.
* **Live sunspot number** — `SUNSPOT_NUMBER` is a static env var; wire a
  SIDC/NOAA feed.
* **DXCC lookup** — `countriesReached` is approximated by distinct receiver
  Maidenhead fields; replace with a real prefix→DXCC table.
* **`frange` server-side filtering** — the PSK query currently fetches all
  FT8 and filters by band locally; per-band `frange` could shrink payloads.
* **Multi-process cache coherence** — caches are per-process; run one worker
  or add redis before scaling out.
* **AuthN/rate limiting for the public API**, metrics/tracing, and FT4
  support are all out of scope for this scaffold.
