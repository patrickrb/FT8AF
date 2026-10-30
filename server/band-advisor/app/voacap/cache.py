"""TTL cache for VOACAP predictions.

Keyed EXACTLY by::

    (tx_grid_4char, month, utc_hour, power_class, antenna_class,
     noise_class, target_region)

Predictions for a given month/hour are deterministic (same deck -> same
output), so the TTL (default 6 h) is mainly a memory bound, not a freshness
requirement.  Optional JSON disk persistence survives container restarts.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Callable

from ..ttl_cache import TTLCache


def voacap_cache_key(
    tx_grid: str,
    month: int,
    utc_hour: int,
    power_class: str,
    antenna_class: str,
    noise_class: str,
    target_region: str,
) -> tuple:
    """Build the canonical VOACAP cache key."""
    return (
        tx_grid.upper()[:4],
        int(month),
        int(utc_hour),
        str(power_class),
        str(antenna_class),
        str(noise_class),
        str(target_region),
    )


class VoacapCache:
    """Thin wrapper enforcing the canonical key shape."""

    def __init__(
        self,
        ttl_seconds: float,
        time_fn: Callable[[], float],
        disk_path: Path | None = None,
    ) -> None:
        self._cache = TTLCache(
            ttl_seconds=ttl_seconds,
            time_fn=time_fn,
            max_entries=2048,
            disk_path=disk_path,
        )

    def get(self, **key_parts: Any) -> Any | None:
        return self._cache.get(voacap_cache_key(**key_parts))

    def set(self, value: Any, **key_parts: Any) -> None:
        self._cache.set(voacap_cache_key(**key_parts), value)
