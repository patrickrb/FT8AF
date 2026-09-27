"""Facade: fetch + cache + aggregate + baseline in one call.

The recommendation layer only talks to :class:`PskService`.  Every method
degrades by raising :class:`PskReporterError` (never returns partial junk);
the caller flags the source unavailable and renormalizes scores.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime
from statistics import median

from ..clock import Clock
from ..config import Settings
from ..geo import grid_distance_km, GridError
from .aggregator import BandActivity, aggregate_band_activity
from .baseline import ActivityBaseline
from .cache import PskCache
from .client import (
    FetchResult,
    PskReporterBackoff,
    PskReporterClient,
    PskReporterError,
)

log = logging.getLogger(__name__)


@dataclass
class RegionalActivity:
    """Aggregated regional picture for one observer field."""

    bands: dict[str, BandActivity]
    #: band -> live-vs-typical ratio (1.0 == normal for this hour)
    activity_ratio: dict[str, float]
    #: band -> short-term trend in [-1, 1]
    activity_trend: dict[str, float]
    fetched_at: datetime


@dataclass
class PersonalResult:
    """Per-callsign analytics: contract JSON block + per-band performance."""

    analytics: dict
    #: band -> 0..1 relative personal performance (for the scoring component)
    per_band_performance: dict[str, float]
    fetched_at: datetime


class PskService:
    """Cached, rate-limit-respecting PSK Reporter aggregation."""

    def __init__(
        self,
        settings: Settings,
        clock: Clock,
        client: PskReporterClient | None = None,
        baseline: ActivityBaseline | None = None,
    ) -> None:
        self._settings = settings
        self._clock = clock
        self._client = client or PskReporterClient(settings, clock)
        self._baseline = baseline or ActivityBaseline(path=settings.baseline_path)
        self._cache = PskCache(
            regional_ttl_s=settings.psk_cache_ttl_s,
            personal_ttl_s=settings.personal_cache_ttl_s,
            time_fn=clock.monotonic,
        )

    # -- regional --------------------------------------------------------------

    def get_regional_activity(
        self, observer_grid: str, mode: str = "FT8"
    ) -> RegionalActivity:
        window = self._settings.psk_window_s
        cached = self._cache.get_regional(observer_grid, None, mode, window)
        if cached is not None:
            return cached

        try:
            fetch: FetchResult = self._client.fetch_reports(mode=mode)
        except PskReporterBackoff:
            raise PskReporterError("rate-limited and no cached regional data")

        bands = aggregate_band_activity(list(fetch.reports), observer_grid)
        utc_hour = fetch.fetched_at.hour
        ratios: dict[str, float] = {}
        trends: dict[str, float] = {}
        for band, activity in bands.items():
            self._baseline.update(band, utc_hour, activity.observation_count)
            ratios[band] = self._baseline.activity_ratio(
                band, utc_hour, activity.observation_count
            )
            trends[band] = self._baseline.activity_trend(band)

        result = RegionalActivity(
            bands=bands,
            activity_ratio=ratios,
            activity_trend=trends,
            fetched_at=fetch.fetched_at,
        )
        self._cache.set_regional(observer_grid, None, mode, window, result)
        return result

    # -- personal ----------------------------------------------------------------

    def get_personal_analytics(
        self,
        callsign: str,
        observer_grid: str,
        mode: str = "FT8",
        regional: RegionalActivity | None = None,
    ) -> PersonalResult:
        """Per-callsign TX analytics (10-min cache; same rate limits)."""
        window = self._settings.psk_window_s
        cached = self._cache.get_personal(callsign, mode, window)
        if cached is not None:
            return cached

        try:
            fetch = self._client.fetch_reports(mode=mode, sender_callsign=callsign)
        except PskReporterBackoff:
            raise PskReporterError("rate-limited and no cached personal data")

        result = build_personal_result(
            fetch, callsign=callsign, regional=regional, fetched_at=fetch.fetched_at
        )
        self._cache.set_personal(callsign, mode, window, result)
        return result


def build_personal_result(
    fetch: FetchResult,
    callsign: str,
    regional: RegionalActivity | None,
    fetched_at: datetime,
) -> PersonalResult:
    """Pure aggregation of a per-callsign fetch into the contract block.

    ``countriesReached`` is approximated by distinct receiver Maidenhead
    FIELDS — TODO(scaffold): replace with a real callsign-prefix -> DXCC
    lookup (the Android app already ships one).

    ``performanceComparedToBaseline`` compares the operator's median
    received SNR against the regional median on the same bands, scaled so
    +10 dB above the crowd => +1.0.
    """
    reports = [r for r in fetch.reports if r.sender_callsign == callsign.upper()]
    per_band_counts: dict[str, int] = {}
    distances: list[float] = []
    snrs: list[int] = []
    receivers: set[str] = set()
    receiver_fields: set[str] = set()

    for r in reports:
        if r.band is not None:
            per_band_counts[r.band] = per_band_counts.get(r.band, 0) + 1
        receivers.add(r.receiver_callsign)
        receiver_fields.add(r.receiver_grid[:2].upper())
        if r.snr_db is not None:
            snrs.append(r.snr_db)
        try:
            distances.append(grid_distance_km(r.sender_grid, r.receiver_grid))
        except GridError:
            pass

    performance = 0.0
    if snrs and regional is not None:
        regional_snrs = [
            a.median_snr_db
            for b, a in regional.bands.items()
            if b in per_band_counts and a.median_snr_db is not None
        ]
        if regional_snrs:
            performance = max(
                -1.0, min(1.0, (median(snrs) - median(regional_snrs)) / 10.0)
            )

    analytics = {
        "enabled": True,
        "reportsReceived": len(reports),
        "uniqueReceivers": len(receivers),
        "countriesReached": len(receiver_fields),
        "maximumDistanceKm": round(max(distances)) if distances else 0,
        "medianSnrDb": round(median(snrs)) if snrs else None,
        "bestSnrDb": max(snrs) if snrs else None,
        "performanceComparedToBaseline": round(performance, 2),
    }
    max_count = max(per_band_counts.values()) if per_band_counts else 0
    per_band_perf = {
        band: (count / max_count if max_count else 0.0)
        for band, count in per_band_counts.items()
    }
    return PersonalResult(
        analytics=analytics,
        per_band_performance=per_band_perf,
        fetched_at=fetched_at,
    )
