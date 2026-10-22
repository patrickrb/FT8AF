"""Aggregate reception reports into per-band activity statistics.

All statistics are computed relative to an *observer* 4-char grid:

* distances/azimuths use the report's own TX->RX path;
* "directional diversity" counts distinct 45-degree azimuth octants among
  paths whose transmitter is near the observer (within
  ``NEARBY_REGION_KM``), i.e. "from stations like me, which directions are
  working right now";
* "nearby transmitters reaching region R" counts distinct transmitters
  within ``NEARBY_TX_KM`` of the observer whose signal was heard by a
  receiver in region R (region = nearest-centroid of the receiver's field).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from statistics import median

from ..geo import GridError, grid_azimuth_deg, grid_distance_km
from ..voacap.regions import home_region_for_field
from .client import ReceptionReport

#: Transmitters within this range of the observer count as "nearby" (stations
#: with comparable propagation to the observer's own).
NEARBY_TX_KM = 500.0

#: Paths originating within this range feed the directional-diversity octants.
NEARBY_REGION_KM = 1500.0

PATH_THRESHOLD_1_KM = 3000.0
PATH_THRESHOLD_2_KM = 8000.0


class SampleQuality(str, Enum):
    """How trustworthy a band's statistics are, by observation count."""

    NONE = "NONE"  # < 5 observations
    LOW = "LOW"  # 5..19
    GOOD = "GOOD"  # >= 20


def sample_quality_for_count(count: int) -> SampleQuality:
    if count < 5:
        return SampleQuality.NONE
    if count < 20:
        return SampleQuality.LOW
    return SampleQuality.GOOD


@dataclass
class BandActivity:
    """Per-band aggregate over one observation window."""

    band: str
    observation_count: int = 0
    unique_transmitters: int = 0
    unique_receivers: int = 0
    median_snr_db: float | None = None
    median_distance_km: float | None = None
    max_distance_km: float | None = None
    paths_over_3000km: int = 0
    paths_over_8000km: int = 0
    #: Distinct 45-degree octants (0..8) with at least one observer-area path.
    directional_octants: int = 0
    #: Distinct nearby (<500 km) transmitters heard in each region.
    nearby_tx_to_region: dict[str, int] = field(default_factory=dict)
    #: Distinct nearby transmitters heard anywhere.
    nearby_tx_total: int = 0
    sample_quality: SampleQuality = SampleQuality.NONE


def aggregate_band_activity(
    reports: list[ReceptionReport] | tuple[ReceptionReport, ...],
    observer_grid: str,
) -> dict[str, BandActivity]:
    """Compute :class:`BandActivity` for every band present in ``reports``.

    Reports with off-dial frequencies or grid math failures are skipped —
    the aggregator never raises on a bad report.
    """
    per_band: dict[str, dict] = {}

    for report in reports:
        band = report.band
        if band is None:
            continue
        try:
            path_km = grid_distance_km(report.sender_grid, report.receiver_grid)
            tx_from_observer_km = grid_distance_km(observer_grid, report.sender_grid)
        except GridError:
            continue

        acc = per_band.setdefault(
            band,
            {
                "snrs": [],
                "distances": [],
                "transmitters": set(),
                "receivers": set(),
                "over1": 0,
                "over2": 0,
                "octants": set(),
                "nearby_tx_by_region": {},
                "nearby_tx_all": set(),
                "count": 0,
            },
        )
        acc["count"] += 1
        acc["transmitters"].add(report.sender_callsign)
        acc["receivers"].add(report.receiver_callsign)
        acc["distances"].append(path_km)
        if report.snr_db is not None:
            acc["snrs"].append(report.snr_db)
        if path_km > PATH_THRESHOLD_1_KM:
            acc["over1"] += 1
        if path_km > PATH_THRESHOLD_2_KM:
            acc["over2"] += 1

        if tx_from_observer_km <= NEARBY_REGION_KM and path_km > 1.0:
            try:
                az = grid_azimuth_deg(report.sender_grid, report.receiver_grid)
                acc["octants"].add(int(az // 45.0) % 8)
            except GridError:
                pass

        if tx_from_observer_km <= NEARBY_TX_KM:
            acc["nearby_tx_all"].add(report.sender_callsign)
            try:
                region = home_region_for_field(report.receiver_grid)
            except GridError:
                region = None
            if region is not None:
                acc["nearby_tx_by_region"].setdefault(region, set()).add(
                    report.sender_callsign
                )

    result: dict[str, BandActivity] = {}
    for band, acc in per_band.items():
        result[band] = BandActivity(
            band=band,
            observation_count=acc["count"],
            unique_transmitters=len(acc["transmitters"]),
            unique_receivers=len(acc["receivers"]),
            median_snr_db=median(acc["snrs"]) if acc["snrs"] else None,
            median_distance_km=(
                round(median(acc["distances"]), 1) if acc["distances"] else None
            ),
            max_distance_km=(
                round(max(acc["distances"]), 1) if acc["distances"] else None
            ),
            paths_over_3000km=acc["over1"],
            paths_over_8000km=acc["over2"],
            directional_octants=len(acc["octants"]),
            nearby_tx_to_region={
                region: len(calls)
                for region, calls in sorted(acc["nearby_tx_by_region"].items())
            },
            nearby_tx_total=len(acc["nearby_tx_all"]),
            sample_quality=sample_quality_for_count(acc["count"]),
        )
    return result
