"""Small in-memory TTL cache with optional JSON disk persistence.

Deliberately simple (a dict + expiry timestamps) — this service runs as a
single process per container; anything fancier (redis, etc.) is a deployment
decision left for later.  The clock is injected so tests are deterministic.
"""

from __future__ import annotations

import json
import logging
import threading
from pathlib import Path
from typing import Any, Callable

log = logging.getLogger(__name__)

# Cache keys are tuples of primitives; stored values must be JSON-serializable
# when disk persistence is enabled.
CacheKey = tuple[Any, ...]


class TTLCache:
    """Thread-safe TTL cache keyed by tuples of primitives.

    Args:
        ttl_seconds: entry lifetime.
        time_fn: monotonic-seconds source (injectable for tests).
        max_entries: soft bound; oldest-expiring entries are evicted first.
        disk_path: optional JSON file for persistence across restarts.
            Only enable for values that are plain JSON data.
    """

    def __init__(
        self,
        ttl_seconds: float,
        time_fn: Callable[[], float],
        max_entries: int = 512,
        disk_path: Path | None = None,
    ) -> None:
        self.ttl_seconds = float(ttl_seconds)
        self._time_fn = time_fn
        self._max_entries = max_entries
        self._disk_path = disk_path
        self._lock = threading.Lock()
        # key -> (expires_at_monotonic, value)
        self._entries: dict[CacheKey, tuple[float, Any]] = {}
        if disk_path is not None:
            self._load_disk()

    def get(self, key: CacheKey) -> Any | None:
        now = self._time_fn()
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return None
            expires_at, value = entry
            if now >= expires_at:
                del self._entries[key]
                return None
            return value

    def set(self, key: CacheKey, value: Any) -> None:
        now = self._time_fn()
        with self._lock:
            self._entries[key] = (now + self.ttl_seconds, value)
            if len(self._entries) > self._max_entries:
                self._evict_locked(now)
        if self._disk_path is not None:
            self._save_disk()

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()

    def __len__(self) -> int:
        with self._lock:
            return len(self._entries)

    def _evict_locked(self, now: float) -> None:
        # Drop expired first, then oldest-expiring until within bounds.
        self._entries = {
            k: v for k, v in self._entries.items() if v[0] > now
        }
        while len(self._entries) > self._max_entries:
            oldest = min(self._entries, key=lambda k: self._entries[k][0])
            del self._entries[oldest]

    # -- optional disk persistence ------------------------------------------

    def _save_disk(self) -> None:
        assert self._disk_path is not None
        try:
            now = self._time_fn()
            payload = [
                {"key": list(k), "ttl_remaining": exp - now, "value": v}
                for k, (exp, v) in self._entries.items()
                if exp > now
            ]
            self._disk_path.parent.mkdir(parents=True, exist_ok=True)
            self._disk_path.write_text(json.dumps(payload))
        except OSError as exc:  # persistence is best-effort
            log.warning("cache disk save failed: %s", exc)

    def _load_disk(self) -> None:
        assert self._disk_path is not None
        try:
            if not self._disk_path.exists():
                return
            payload = json.loads(self._disk_path.read_text())
            now = self._time_fn()
            for item in payload:
                remaining = float(item.get("ttl_remaining", 0))
                if remaining > 0:
                    self._entries[tuple(item["key"])] = (
                        now + remaining,
                        item["value"],
                    )
        except (OSError, ValueError, KeyError, TypeError) as exc:
            log.warning("cache disk load failed (ignored): %s", exc)
