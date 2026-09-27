"""HTTP client for https://retrieve.pskreporter.info/query.

Etiquette / safety requirements implemented (see package docstring):

* ``Accept-Encoding: gzip`` (httpx negotiates and transparently decompresses);
* ``appcontact=<configured email>`` on every request;
* per-parameter-set minimum interval of 5 minutes (their guidance) — the
  cache layer is the primary guard, this client enforces it again;
* 15-minute global backoff after HTTP 429/503;
* XML parsed with ``defusedxml`` (no external entities / bombs);
* malformed XML raises, out-of-range values (bad grids, absurd
  frequencies) are dropped per-report rather than failing the fetch.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime

import httpx
from defusedxml import ElementTree as SafeET

from ..bands import band_for_frequency_hz
from ..clock import Clock
from ..config import Settings
from ..geo import GridError, grid_to_latlon

log = logging.getLogger(__name__)

_MIN_FREQ_HZ = 1_000_000  # below 160 m: not an HF FT8 report we care about
_MAX_FREQ_HZ = 60_000_000
_SNR_RANGE = (-40, 60)


class PskReporterError(RuntimeError):
    """Fetch failed (network, HTTP error, malformed XML, or backoff active)."""


class PskReporterBackoff(PskReporterError):
    """Raised while the 429/503 backoff window or min-interval is active."""


@dataclass(frozen=True)
class ReceptionReport:
    """One validated PSK Reporter reception report."""

    sender_callsign: str
    sender_grid: str
    receiver_callsign: str
    receiver_grid: str
    frequency_hz: int
    snr_db: int | None
    mode: str
    flow_start_seconds: int | None

    @property
    def band(self) -> str | None:
        return band_for_frequency_hz(self.frequency_hz)


@dataclass(frozen=True)
class FetchResult:
    """Reports plus the wall-clock time they were fetched (for freshness)."""

    reports: tuple[ReceptionReport, ...]
    fetched_at: datetime


def _valid_grid(locator: str) -> bool:
    if not locator or len(locator) < 4:
        return False
    try:
        grid_to_latlon(locator[:6] if len(locator) >= 6 else locator[:4])
        return True
    except GridError:
        return False


def parse_reception_reports(xml_bytes: bytes) -> list[ReceptionReport]:
    """Parse a retrieve.pskreporter.info XML response into validated reports.

    Raises :class:`PskReporterError` on malformed XML.  Individual reports
    with bad grids, absurd frequencies, or missing fields are skipped.
    """
    try:
        root = SafeET.fromstring(xml_bytes)
    except Exception as exc:  # defusedxml raises several parse error types
        raise PskReporterError(f"malformed PSK Reporter XML: {exc}") from exc

    reports: list[ReceptionReport] = []
    for elem in root.iter("receptionReport"):
        sender = (elem.get("senderCallsign") or "").strip().upper()
        receiver = (elem.get("receiverCallsign") or "").strip().upper()
        sender_grid = (elem.get("senderLocator") or "").strip()
        receiver_grid = (elem.get("receiverLocator") or "").strip()
        mode = (elem.get("mode") or "").strip().upper()
        try:
            frequency_hz = int(float(elem.get("frequency", "")))
        except (TypeError, ValueError):
            continue
        snr_raw = elem.get("sNR")
        snr_db: int | None
        try:
            snr_db = int(float(snr_raw)) if snr_raw is not None else None
        except (TypeError, ValueError):
            snr_db = None
        if snr_db is not None and not _SNR_RANGE[0] <= snr_db <= _SNR_RANGE[1]:
            snr_db = None
        try:
            flow_start = int(float(elem.get("flowStartSeconds", "")))
        except (TypeError, ValueError):
            flow_start = None

        if not sender or not receiver:
            continue
        if not _MIN_FREQ_HZ <= frequency_hz <= _MAX_FREQ_HZ:
            continue
        if not _valid_grid(sender_grid) or not _valid_grid(receiver_grid):
            continue

        reports.append(
            ReceptionReport(
                sender_callsign=sender,
                sender_grid=sender_grid[:6],
                receiver_callsign=receiver,
                receiver_grid=receiver_grid[:6],
                frequency_hz=frequency_hz,
                snr_db=snr_db,
                mode=mode,
                flow_start_seconds=flow_start,
            )
        )
    return reports


class PskReporterClient:
    """Rate-limited fetcher.  ``http_client`` is injectable for tests."""

    def __init__(
        self,
        settings: Settings,
        clock: Clock,
        http_client: httpx.Client | None = None,
    ) -> None:
        self._settings = settings
        self._clock = clock
        self._http = http_client or httpx.Client(
            timeout=settings.psk_timeout_s,
            headers={
                "Accept-Encoding": "gzip",
                "User-Agent": f"ft8af-band-advisor ({settings.psk_contact_email})",
            },
        )
        self._last_fetch_mono: dict[tuple, float] = {}
        self._backoff_until_mono: float = 0.0

    def fetch_reports(
        self,
        *,
        mode: str = "FT8",
        sender_callsign: str | None = None,
        receiver_region_grid: str | None = None,
    ) -> FetchResult:
        """Fetch the last ``psk_window_s`` of reception reports.

        Either a per-callsign query (``senderCallsign``) or a broad-mode
        query later filtered client-side by band/region.  Raises
        :class:`PskReporterBackoff` when called inside the min-interval or a
        429/503 backoff window — callers should use the cache instead.
        """
        now_mono = self._clock.monotonic()
        if now_mono < self._backoff_until_mono:
            raise PskReporterBackoff(
                "PSK Reporter backoff active after 429/503; try later"
            )

        params: dict[str, str] = {
            "flowStartSeconds": str(-abs(self._settings.psk_window_s)),
            "mode": mode,
            "rronly": "1",
            "appcontact": self._settings.psk_contact_email,
        }
        if sender_callsign:
            params["senderCallsign"] = sender_callsign.upper()

        key = tuple(sorted(params.items()))
        last = self._last_fetch_mono.get(key)
        if last is not None and now_mono - last < self._settings.psk_min_interval_s:
            raise PskReporterBackoff(
                "per-parameter-set minimum interval (5 min) not elapsed"
            )

        try:
            response = self._http.get(
                self._settings.psk_base_url,
                params=params,
                timeout=self._settings.psk_timeout_s,
            )
        except httpx.HTTPError as exc:
            raise PskReporterError(f"PSK Reporter request failed: {exc}") from exc

        if response.status_code in (429, 503):
            self._backoff_until_mono = now_mono + self._settings.psk_backoff_s
            raise PskReporterError(
                f"PSK Reporter returned {response.status_code}; backing off "
                f"{self._settings.psk_backoff_s:.0f}s"
            )
        if response.status_code != 200:
            raise PskReporterError(
                f"PSK Reporter returned HTTP {response.status_code}"
            )

        self._last_fetch_mono[key] = now_mono
        reports = parse_reception_reports(response.content)
        log.info(
            "pskreporter fetch ok: %d valid reports (mode=%s personal=%s)",
            len(reports),
            mode,
            bool(sender_callsign),
        )
        return FetchResult(reports=tuple(reports), fetched_at=self._clock.now())
