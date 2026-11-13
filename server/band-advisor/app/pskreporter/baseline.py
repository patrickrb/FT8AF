"""Typical-activity baseline per (band, UTC hour).

Raw spot volume must NOT be the dominant recommendation signal: 20 m at
14:00 UTC is *always* busy, so "lots of spots on 20 m" carries little
information.  This module maintains an exponentially-weighted mean of
observation counts per (band, utc_hour) so live activity can be expressed
as a *ratio against what is normal for that band at that hour*:

    activity_ratio = current / max(baseline, floor)

plus a short-term ``activity_trend`` computed from the last 3 observation
windows (rising/falling live activity).

The baseline persists to a small JSON file so it survives restarts and
slowly learns the deployment's real traffic pattern.
"""

from __future__ import annotations

import json
import logging
from collections import deque
from pathlib import Path

log = logging.getLogger(__name__)

DEFAULT_ALPHA = 0.2  # EWMA smoothing factor per window
DEFAULT_FLOOR = 5.0  # avoid divide-by-tiny for quiet band-hours
TREND_WINDOWS = 3


def _key(band: str, utc_hour: int) -> str:
    return f"{band}|{int(utc_hour) % 24:02d}"


class ActivityBaseline:
    """EWMA baseline of observation counts per (band, utc_hour)."""

    def __init__(
        self,
        path: Path | None = None,
        alpha: float = DEFAULT_ALPHA,
        floor: float = DEFAULT_FLOOR,
    ) -> None:
        self._path = path
        self._alpha = alpha
        self._floor = floor
        self._means: dict[str, float] = {}
        # recent raw counts per band (across hours) for the trend signal
        self._recent: dict[str, deque[int]] = {}
        self._load()

    # -- updates -------------------------------------------------------------

    def update(self, band: str, utc_hour: int, observation_count: int) -> None:
        """Fold one observation window into the baseline and trend history."""
        k = _key(band, utc_hour)
        prev = self._means.get(k)
        if prev is None:
            self._means[k] = float(observation_count)
        else:
            self._means[k] = (1 - self._alpha) * prev + self._alpha * observation_count
        window = self._recent.setdefault(band, deque(maxlen=TREND_WINDOWS))
        window.append(int(observation_count))
        self._save()

    # -- queries -------------------------------------------------------------

    def baseline(self, band: str, utc_hour: int) -> float:
        return max(self._means.get(_key(band, utc_hour), 0.0), self._floor)

    def activity_ratio(self, band: str, utc_hour: int, current: int) -> float:
        """current / max(baseline, floor); 1.0 means "normal for this hour"."""
        return float(current) / self.baseline(band, utc_hour)

    def activity_trend(self, band: str) -> float:
        """Relative change across the last 3 windows, clamped to [-1, 1].

        0.0 = flat or insufficient history; positive = activity rising.
        """
        window = self._recent.get(band)
        if not window or len(window) < 2:
            return 0.0
        oldest, newest = window[0], window[-1]
        trend = (newest - oldest) / max(float(oldest), self._floor)
        return max(-1.0, min(1.0, trend))

    # -- persistence -----------------------------------------------------------

    def _save(self) -> None:
        if self._path is None:
            return
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            self._path.write_text(json.dumps({"means": self._means}, sort_keys=True))
        except OSError as exc:
            log.warning("baseline save failed (ignored): %s", exc)

    def _load(self) -> None:
        if self._path is None or not self._path.exists():
            return
        try:
            data = json.loads(self._path.read_text())
            means = data.get("means", {})
            self._means = {str(k): float(v) for k, v in means.items()}
        except (OSError, ValueError, TypeError, AttributeError) as exc:
            log.warning("baseline load failed (starting fresh): %s", exc)
            self._means = {}
