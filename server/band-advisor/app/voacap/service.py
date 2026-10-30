"""Facade tying deck builder + runner + parser + cache together.

The recommendation layer talks to this service only through
:meth:`VoacapService.predict`, which returns plain JSON-serializable dicts
(so results can be disk-cached).
"""

from __future__ import annotations

import logging
import threading
from typing import Any

from ..clock import Clock
from ..config import Settings
from .cache import VoacapCache
from .ft8_profile import DEFAULT_FT8_PROFILE, FT8SnrProfile
from .input_builder import (
    AntennaClass,
    DeckParams,
    NoiseClass,
    PowerClass,
    build_deck,
)
from .parser import parse_output
from .regions import resolve_target
from .runner import VoacapError, VoacapRunner

log = logging.getLogger(__name__)


class VoacapService:
    """Cached VOACAP predictions per (grid, month, hour, station, region)."""

    def __init__(
        self,
        settings: Settings,
        clock: Clock,
        runner: VoacapRunner | None = None,
        profile: FT8SnrProfile = DEFAULT_FT8_PROFILE,
    ) -> None:
        self._lock = threading.Lock()
        self._settings = settings
        self._clock = clock
        self._runner = runner or VoacapRunner(settings)
        self._profile = profile
        self._cache = VoacapCache(
            ttl_seconds=settings.voacap_cache_ttl_s,
            time_fn=clock.monotonic,
            disk_path=settings.voacap_cache_disk_path,
        )

    def predict(
        self,
        tx_grid: str,
        month: int,
        year: int,
        utc_hour: int,
        power_class: PowerClass,
        antenna_class: AntennaClass,
        noise_class: NoiseClass,
        target_region: str,
    ) -> dict[str, Any]:
        """Per-band prediction dict for one UTC hour toward one target region.

        Returns ``{"target": display_name, "bands": {band: {...} | None}}``.
        Raises :class:`VoacapError` when the engine fails (callers degrade).
        """
        with self._lock:
            key = dict(
                tx_grid=tx_grid,
                month=month,
                utc_hour=utc_hour,
                power_class=power_class.value,
                antenna_class=antenna_class.value,
                noise_class=noise_class.value,
                target_region=target_region,
            )
            cached = self._cache.get(**key)
            if cached is not None:
                return cached

            display_name, rx_lat, rx_lon = resolve_target(tx_grid, target_region)
            deck = build_deck(
                DeckParams(
                    tx_grid=tx_grid,
                    month=month,
                    year=year,
                    rx_lat=rx_lat,
                    rx_lon=rx_lon,
                    rx_label=target_region,
                    power_class=power_class,
                    antenna_class=antenna_class,
                    noise_class=noise_class,
                    ssn=self._settings.sunspot_number,
                    ft8_profile=self._profile,
                )
            )
            output = self._runner.run(deck)  # may raise VoacapError
            parsed = parse_output(output)

            # The deck predicts all 24 hours; populate the cache for each of them
            # (same key shape, different utc_hour) in one engine invocation.
            result: dict[str, Any] | None = None
            for hour, bands in parsed.hours.items():
                entry = {
                    "target": display_name,
                    "targetRegion": target_region,
                    "bands": {
                        band: (
                            None
                            if pred is None
                            else {
                                "freqMhz": pred.freq_mhz,
                                "rel": pred.rel,
                                "snrDbHz": pred.snr_dbhz,
                                "snr90DbHz": pred.snrxx_dbhz,
                                "mufday": pred.mufday,
                                "mode": pred.mode,
                            }
                        )
                        for band, pred in bands.items()
                    },
                }
                self._cache.set(entry, **{**key, "utc_hour": hour})
                if hour == utc_hour:
                    result = entry

            if result is None:
                raise VoacapError(
                    f"voacapl output contained no prediction for hour {utc_hour:02d}Z"
                )
            return result

    def binary_available(self) -> bool:
        """Cheap liveness check used by /healthz."""
        import os

        return os.path.isfile(self._settings.voacapl_path) and os.access(
            self._settings.voacapl_path, os.X_OK
        )
