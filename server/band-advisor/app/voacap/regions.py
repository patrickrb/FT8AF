"""Target region definitions for VOACAP receiver endpoints and PSK scoring.

Centroids are deliberately coarse "center of ham population" points, not
geographic centroids — e.g. EUROPE sits near the DL/OK border, ASIA is pulled
east toward JA.  Grid lists are representative squares used for scoring
observed paths into a region.

Two pseudo-regions are resolved per-observer:

* ``REGIONAL`` — a ~1500 km ring on the observer's own continent (close-in
  single-hop coverage, the POTA/"make contact" case).
* ``LONG_DX`` — the concrete region whose centroid is farthest from the
  observer (antipodal-ish long-path target).
"""

from __future__ import annotations

from dataclasses import dataclass

from ..geo import destination_point, grid_to_latlon, haversine_km


@dataclass(frozen=True)
class Region:
    """A scoring/prediction target region."""

    name: str
    display_name: str
    centroid_lat: float
    centroid_lon: float
    #: Representative 4-char squares, used to score observed paths.
    grids: tuple[str, ...]


#: Concrete geographic regions (pseudo-regions REGIONAL/LONG_DX are computed).
REGIONS: dict[str, Region] = {
    "EUROPE": Region("EUROPE", "Europe", 50.0, 12.0, ("JO01", "JN58", "JN18", "KO85", "JN61")),
    "NORTH_AMERICA_EAST": Region(
        "NORTH_AMERICA_EAST", "North America (East)", 40.0, -78.0, ("FN31", "FN20", "EM73", "FN03")
    ),
    "NORTH_AMERICA_WEST": Region(
        "NORTH_AMERICA_WEST", "North America (West)", 39.0, -114.0, ("DM04", "CN87", "DM33", "DN70")
    ),
    "SOUTH_AMERICA": Region(
        "SOUTH_AMERICA", "South America", -20.0, -55.0, ("GG66", "GF05", "FF95", "FH52")
    ),
    "AFRICA": Region("AFRICA", "Africa", 0.0, 20.0, ("KG33", "JJ58", "IL38", "KI88")),
    "ASIA": Region("ASIA", "Asia", 35.0, 115.0, ("PM95", "OM89", "MN66", "OL72")),
    "OCEANIA": Region("OCEANIA", "Oceania", -27.0, 145.0, ("QF56", "QG62", "RF73", "PF95")),
}

REGION_NAMES: tuple[str, ...] = tuple(REGIONS.keys())

#: Names accepted by the API ``targetRegion`` parameter.
TARGET_REGION_NAMES: tuple[str, ...] = REGION_NAMES + ("REGIONAL", "LONG_DX")

#: Radius of the REGIONAL pseudo-target ring, km.
REGIONAL_RING_KM = 1500.0


def home_region_for_field(field_or_grid: str) -> str:
    """Map a Maidenhead locator (2-char field is enough) to its home region.

    Nearest-centroid assignment — coarse, but stable and good enough to
    bucket "which continent is this station on".
    """
    lat, lon = grid_to_latlon(field_or_grid[:2])
    best_name = REGION_NAMES[0]
    best_km = float("inf")
    for region in REGIONS.values():
        km = haversine_km(lat, lon, region.centroid_lat, region.centroid_lon)
        if km < best_km:
            best_km = km
            best_name = region.name
    return best_name


def farthest_region(grid: str) -> Region:
    """The concrete region whose centroid is farthest from ``grid`` (LONG_DX)."""
    lat, lon = grid_to_latlon(grid)
    return max(
        REGIONS.values(),
        key=lambda r: haversine_km(lat, lon, r.centroid_lat, r.centroid_lon),
    )


def resolve_target(grid: str, target_region: str) -> tuple[str, float, float]:
    """Resolve a target-region name to (display_name, rx_lat, rx_lon).

    Handles the two pseudo-regions:

    * ``REGIONAL``: a point ~1500 km from the observer, in the direction of
      the observer's home-region centroid (due north if the centroid is
      within 500 km, to avoid a degenerate zero-length bearing).
    * ``LONG_DX``: centroid of the farthest concrete region.
    """
    if target_region == "LONG_DX":
        r = farthest_region(grid)
        return r.display_name, r.centroid_lat, r.centroid_lon
    if target_region == "REGIONAL":
        lat, lon = grid_to_latlon(grid)
        home = REGIONS[home_region_for_field(grid)]
        dist = haversine_km(lat, lon, home.centroid_lat, home.centroid_lon)
        if dist < 500.0:
            bearing = 0.0
        else:
            from ..geo import azimuth_deg

            bearing = azimuth_deg(lat, lon, home.centroid_lat, home.centroid_lon)
        rx_lat, rx_lon = destination_point(lat, lon, bearing, REGIONAL_RING_KM)
        return "Regional", rx_lat, rx_lon
    region = REGIONS[target_region]
    return region.display_name, region.centroid_lat, region.centroid_lon
