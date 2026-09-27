"""TTL caches for PSK Reporter data.

Regional cache keying: the observer's 4-char grid is reduced to its 2-char
FIELD so that nearby users share cache entries (one upstream fetch serves
everyone in, say, EM** for the whole window).  Key shape::

    (observer_field_2char, band_or_ALL, mode, window_seconds)

TTL 5 minutes — matching retrieve.pskreporter.info's minimum polling
guidance, so the cache itself enforces the etiquette.

Personal (per-callsign) results are cached separately for 10 minutes and are
never persisted to disk (data-retention: aggregates only; per-callsign data
lives at most the cache TTL, well under 1 h).
"""

from __future__ import annotations

from typing import Any, Callable

from ..geo import normalize_grid4
from ..ttl_cache import TTLCache


def regional_cache_key(
    observer_grid: str, band: str | None, mode: str, window_s: int
) -> tuple:
    """Canonical regional key — observer grid collapses to its 2-char field."""
    field = normalize_grid4(observer_grid)[:2]
    return (field, band or "ALL", mode.upper(), int(window_s))


def personal_cache_key(callsign: str, mode: str, window_s: int) -> tuple:
    return ("personal", callsign.strip().upper(), mode.upper(), int(window_s))


class PskCache:
    """Regional (5 min) + personal (10 min) TTL caches, memory only."""

    def __init__(
        self,
        regional_ttl_s: float,
        personal_ttl_s: float,
        time_fn: Callable[[], float],
    ) -> None:
        self._regional = TTLCache(regional_ttl_s, time_fn, max_entries=1024)
        self._personal = TTLCache(personal_ttl_s, time_fn, max_entries=1024)

    def get_regional(
        self, observer_grid: str, band: str | None, mode: str, window_s: int
    ) -> Any | None:
        return self._regional.get(regional_cache_key(observer_grid, band, mode, window_s))

    def set_regional(
        self,
        observer_grid: str,
        band: str | None,
        mode: str,
        window_s: int,
        value: Any,
    ) -> None:
        self._regional.set(
            regional_cache_key(observer_grid, band, mode, window_s), value
        )

    def get_personal(self, callsign: str, mode: str, window_s: int) -> Any | None:
        return self._personal.get(personal_cache_key(callsign, mode, window_s))

    def set_personal(
        self, callsign: str, mode: str, window_s: int, value: Any
    ) -> None:
        self._personal.set(personal_cache_key(callsign, mode, window_s), value)
