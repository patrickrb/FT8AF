"""Maidenhead grid + great-circle geometry helpers (pure functions).

Supports 2-char fields, 4-char squares and 6-char subsquares.  All conversions
return the *center* of the grid cell.  Distance is a plain spherical haversine
(mean Earth radius 6371 km) — accurate to well under 0.5 %, which is far more
precision than band-advice needs.
"""

from __future__ import annotations

import math
import re

EARTH_RADIUS_KM = 6371.0

GRID4_RE = re.compile(r"^[A-R]{2}[0-9]{2}$")
GRID6_RE = re.compile(r"^[A-R]{2}[0-9]{2}[A-X]{2}$")
FIELD_RE = re.compile(r"^[A-R]{2}$")

CALLSIGN_RE = re.compile(r"^[A-Z0-9]{1,3}[0-9][A-Z0-9]{0,3}[A-Z](/[A-Z0-9]{1,4})?$")


class GridError(ValueError):
    """Raised for malformed Maidenhead locators."""


def normalize_grid4(grid: str) -> str:
    """Uppercase, truncate to 4 characters and validate as a Maidenhead square.

    Raises :class:`GridError` if the result does not match ``^[A-R]{2}[0-9]{2}$``.
    """
    if not isinstance(grid, str):
        raise GridError("grid must be a string")
    g = grid.strip().upper()[:4]
    if not GRID4_RE.match(g):
        raise GridError(f"invalid Maidenhead grid: {grid!r}")
    return g


def grid_to_latlon(grid: str) -> tuple[float, float]:
    """Convert a 2/4/6-char Maidenhead locator to the (lat, lon) of its center."""
    if not isinstance(grid, str):
        raise GridError("grid must be a string")
    g = grid.strip()
    # Canonical case: fields upper, subsquares lower (accept any input case).
    g = g[:2].upper() + g[2:4] + g[4:6].lower()
    if len(g) == 2 and FIELD_RE.match(g):
        lon = (ord(g[0]) - ord("A")) * 20.0 - 180.0 + 10.0
        lat = (ord(g[1]) - ord("A")) * 10.0 - 90.0 + 5.0
        return lat, lon
    if len(g) == 4 and GRID4_RE.match(g):
        lon = (ord(g[0]) - ord("A")) * 20.0 - 180.0 + int(g[2]) * 2.0 + 1.0
        lat = (ord(g[1]) - ord("A")) * 10.0 - 90.0 + int(g[3]) * 1.0 + 0.5
        return lat, lon
    if len(g) == 6 and GRID6_RE.match(g.upper()):
        lon = (
            (ord(g[0].upper()) - ord("A")) * 20.0
            - 180.0
            + int(g[2]) * 2.0
            + (ord(g[4]) - ord("a")) * (2.0 / 24.0)
            + (1.0 / 24.0)
        )
        lat = (
            (ord(g[1].upper()) - ord("A")) * 10.0
            - 90.0
            + int(g[3]) * 1.0
            + (ord(g[5]) - ord("a")) * (1.0 / 24.0)
            + (0.5 / 24.0)
        )
        return lat, lon
    raise GridError(f"invalid Maidenhead grid: {grid!r}")


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance in km between two lat/lon points."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(min(1.0, math.sqrt(a)))


def azimuth_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Initial great-circle bearing from point 1 to point 2, degrees 0..360."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dlmb = math.radians(lon2 - lon1)
    y = math.sin(dlmb) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dlmb)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def destination_point(
    lat: float, lon: float, bearing_deg: float, distance_km: float
) -> tuple[float, float]:
    """Point reached from (lat, lon) travelling ``distance_km`` at ``bearing_deg``."""
    d = distance_km / EARTH_RADIUS_KM
    b = math.radians(bearing_deg)
    p1 = math.radians(lat)
    l1 = math.radians(lon)
    p2 = math.asin(
        math.sin(p1) * math.cos(d) + math.cos(p1) * math.sin(d) * math.cos(b)
    )
    l2 = l1 + math.atan2(
        math.sin(b) * math.sin(d) * math.cos(p1),
        math.cos(d) - math.sin(p1) * math.sin(p2),
    )
    lon2 = (math.degrees(l2) + 540.0) % 360.0 - 180.0
    return math.degrees(p2), lon2


def grid_distance_km(grid1: str, grid2: str) -> float:
    """Great-circle distance in km between two Maidenhead grid centers."""
    lat1, lon1 = grid_to_latlon(grid1)
    lat2, lon2 = grid_to_latlon(grid2)
    return haversine_km(lat1, lon1, lat2, lon2)


def grid_azimuth_deg(grid1: str, grid2: str) -> float:
    """Initial bearing from grid1 to grid2, degrees 0..360."""
    lat1, lon1 = grid_to_latlon(grid1)
    lat2, lon2 = grid_to_latlon(grid2)
    return azimuth_deg(lat1, lon1, lat2, lon2)


def is_valid_callsign(callsign: str) -> bool:
    """Loose amateur callsign shape check (uppercased before matching)."""
    return bool(CALLSIGN_RE.match(callsign.strip().upper()))
