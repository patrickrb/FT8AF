"""Injectable clock so every time-dependent code path is testable.

All timestamps in this service are timezone-aware UTC ``datetime`` objects.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Protocol


class Clock(Protocol):
    """Minimal clock interface used throughout the service."""

    def now(self) -> datetime:
        """Return the current UTC time (timezone-aware)."""
        ...

    def monotonic(self) -> float:
        """Return a monotonically increasing number of seconds."""
        ...


class SystemClock:
    """Real wall clock."""

    def now(self) -> datetime:
        return datetime.now(timezone.utc)

    def monotonic(self) -> float:
        import time

        return time.monotonic()


class FixedClock:
    """Deterministic clock for tests. ``advance()`` moves both time bases."""

    def __init__(self, now: datetime) -> None:
        if now.tzinfo is None:
            now = now.replace(tzinfo=timezone.utc)
        self._now = now
        self._mono = 1000.0

    def now(self) -> datetime:
        return self._now

    def monotonic(self) -> float:
        return self._mono

    def advance(self, seconds: float) -> None:
        from datetime import timedelta

        self._now += timedelta(seconds=seconds)
        self._mono += seconds


def isoformat_z(dt: datetime) -> str:
    """Render a UTC datetime as ``2026-09-27T12:00:00Z`` (contract format)."""
    return dt.astimezone(timezone.utc).replace(microsecond=0).strftime(
        "%Y-%m-%dT%H:%M:%SZ"
    )
