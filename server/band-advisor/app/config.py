"""Service configuration from environment variables (12-factor style).

Plain ``os.environ`` with typed defaults rather than pydantic-settings, to
keep the dependency surface small.  All knobs are documented in the README.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env_str(name: str, default: str) -> str:
    return os.environ.get(name, default)


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None:
        return default
    try:
        return float(raw)
    except ValueError:
        raise ValueError(f"environment variable {name}={raw!r} is not a number")


@dataclass
class Settings:
    """All service configuration.  Construct with ``Settings.from_env()``."""

    # -- VOACAP -------------------------------------------------------------
    voacapl_path: str = "/usr/local/bin/voacapl"
    itshfbc_path: str = "/opt/itshfbc"
    voacap_timeout_s: float = 20.0
    voacap_cache_ttl_s: float = 6 * 3600.0
    voacap_cache_disk_path: Path | None = None
    #: Smoothed sunspot number used on the SUNSPOT card.
    #: TODO: wire to a live SIDC/NOAA SSN feed; static default for the scaffold.
    sunspot_number: float = 68.0

    # -- PSK Reporter ---------------------------------------------------------
    psk_base_url: str = "https://retrieve.pskreporter.info/query"
    psk_contact_email: str = "ops@ft8af.app"
    psk_timeout_s: float = 20.0
    psk_cache_ttl_s: float = 300.0  # 5 min: retrieve.pskreporter.info guidance
    psk_min_interval_s: float = 300.0
    psk_backoff_s: float = 900.0  # 15 min after HTTP 429/503
    psk_window_s: int = 900  # flowStartSeconds=-900
    personal_cache_ttl_s: float = 600.0  # 10 min per-callsign cache
    baseline_path: Path = Path("/var/lib/band-advisor/baseline.json")

    # -- API ------------------------------------------------------------------
    feature_config_path: Path = Path("/etc/band-advisor/feature-config.json")
    feature_config_ttl_s: float = 6 * 3600.0
    recommendation_valid_s: float = 900.0

    extra: dict[str, str] = field(default_factory=dict)

    @classmethod
    def from_env(cls) -> "Settings":
        disk = os.environ.get("VOACAP_CACHE_DISK_PATH")
        return cls(
            voacapl_path=_env_str("VOACAPL_PATH", cls.voacapl_path),
            itshfbc_path=_env_str("ITSHFBC_PATH", cls.itshfbc_path),
            voacap_timeout_s=_env_float("VOACAP_TIMEOUT_S", cls.voacap_timeout_s),
            voacap_cache_ttl_s=_env_float(
                "VOACAP_CACHE_TTL_S", cls.voacap_cache_ttl_s
            ),
            voacap_cache_disk_path=Path(disk) if disk else None,
            sunspot_number=_env_float("SUNSPOT_NUMBER", cls.sunspot_number),
            psk_base_url=_env_str("PSK_BASE_URL", cls.psk_base_url),
            psk_contact_email=_env_str("PSK_CONTACT_EMAIL", cls.psk_contact_email),
            psk_timeout_s=_env_float("PSK_TIMEOUT_S", cls.psk_timeout_s),
            psk_cache_ttl_s=_env_float("PSK_CACHE_TTL_S", cls.psk_cache_ttl_s),
            psk_min_interval_s=_env_float(
                "PSK_MIN_INTERVAL_S", cls.psk_min_interval_s
            ),
            psk_backoff_s=_env_float("PSK_BACKOFF_S", cls.psk_backoff_s),
            psk_window_s=int(_env_float("PSK_WINDOW_S", cls.psk_window_s)),
            personal_cache_ttl_s=_env_float(
                "PERSONAL_CACHE_TTL_S", cls.personal_cache_ttl_s
            ),
            baseline_path=Path(
                _env_str("BASELINE_PATH", str(cls.baseline_path))
            ),
            feature_config_path=Path(
                _env_str("FEATURE_CONFIG_PATH", str(cls.feature_config_path))
            ),
            feature_config_ttl_s=_env_float(
                "FEATURE_CONFIG_TTL_S", cls.feature_config_ttl_s
            ),
            recommendation_valid_s=_env_float(
                "RECOMMENDATION_VALID_S", cls.recommendation_valid_s
            ),
        )
