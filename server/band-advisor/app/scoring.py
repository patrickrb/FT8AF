"""Deterministic band scoring and confidence — all pure functions.

Score and confidence are computed SEPARATELY:

* **score** answers "how good is this band for the stated goal" from the
  available signal components (each already normalized to 0..1);
* **confidence** answers "how much should you trust that score" from data
  quantity, freshness, cross-source agreement, source availability and
  station-profile completeness.

When a component is unavailable (``None``), it is dropped and the remaining
weights are renormalized to sum to 1, so a missing source never silently
drags a band to zero.  Per-component contributions are returned for
explainability (they serialize directly into the ``scoreComponents``
contract array).
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Mapping


class Goal(str, Enum):
    MAKE_CONTACT = "MAKE_CONTACT"
    DX = "DX"
    TARGET = "TARGET"
    POTA = "POTA"


#: Component names use the contract's camelCase so they serialize verbatim.
GOAL_WEIGHTS: dict[Goal, dict[str, float]] = {
    Goal.MAKE_CONTACT: {
        "voacapReliability": 0.30,
        "nearbyObservedSuccess": 0.25,
        "normalizedLiveActivity": 0.15,
        "distancePotential": 0.10,
        "activityTrend": 0.10,
        "personalPerformance": 0.10,
    },
    Goal.DX: {
        "distancePotential": 0.25,
        "voacapReliability": 0.25,
        "directionalDiversity": 0.15,
        "nearbyObservedSuccess": 0.15,
        "normalizedLiveActivity": 0.10,
        "personalPerformance": 0.10,
    },
    Goal.TARGET: {
        "targetReliability": 0.40,
        "targetObservedPaths": 0.30,
        "normalizedLiveActivity": 0.10,
        "voacapReliability": 0.10,
        "personalPerformance": 0.10,
    },
    Goal.POTA: {
        # voacapReliability here is a regional+national mix (see recommendation
        # layer: REGIONAL target prediction is used for POTA).
        "voacapReliability": 0.35,
        "nearbyObservedSuccess": 0.25,
        "normalizedLiveActivity": 0.20,
        "activityTrend": 0.10,
        "personalPerformance": 0.10,
    },
}


@dataclass(frozen=True)
class ScoreComponent:
    """One weighted component of a band score (contract ``scoreComponents``)."""

    name: str
    weight: float  # renormalized weight actually used
    value: float  # 0..1 input value
    contribution: float  # weight * value

    def to_json(self) -> dict:
        return {
            "name": self.name,
            "weight": round(self.weight, 4),
            "value": round(self.value, 4),
            "contribution": round(self.contribution, 4),
        }


@dataclass(frozen=True)
class ScoreResult:
    score: float
    components: tuple[ScoreComponent, ...]


def clamp01(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


def score_band(goal: Goal, components: Mapping[str, float | None]) -> ScoreResult:
    """Weighted score for one band.

    Args:
        goal: the operator goal (selects the weight table).
        components: component name -> value in [0, 1], or ``None`` when that
            signal is unavailable.  Unknown component names are ignored.

    Missing components are dropped and remaining weights renormalized to sum
    to 1.  If *nothing* is available the score is 0.0 with no components.
    """
    weights = GOAL_WEIGHTS[goal]
    available = {
        name: clamp01(components[name])
        for name in weights
        if components.get(name) is not None
    }
    if not available:
        return ScoreResult(score=0.0, components=())

    total_weight = sum(weights[name] for name in available)
    parts: list[ScoreComponent] = []
    score = 0.0
    for name in weights:  # weight-table order => deterministic output order
        if name not in available:
            continue
        w = weights[name] / total_weight
        v = available[name]
        contribution = w * v
        score += contribution
        parts.append(
            ScoreComponent(name=name, weight=w, value=v, contribution=contribution)
        )
    return ScoreResult(score=round(score, 4), components=tuple(parts))


def compute_confidence(
    observation_count: int,
    data_age_seconds: float,
    voacap_observed_agreement: float | None,
    sources_available: int,
    sources_total: int,
    profile_completeness: float,
) -> float:
    """Trust level for a recommendation, independent of the score itself.

    Heuristic blend (weights documented in README):

    * observation volume  (35 %): saturates at 20 observations;
    * freshness           (20 %): linear decay to 0 over 15 minutes;
    * VOACAP-vs-observed agreement (20 %): 1.0 when prediction and live
      observations tell the same story; ``None`` (not comparable) counts 0.5;
    * source availability (15 %): fraction of upstream sources that answered;
    * profile completeness (10 %): how fully the station described itself
      (grid/power/antenna/noise/callsign supplied vs defaulted).
    """
    obs = clamp01(observation_count / 20.0)
    freshness = clamp01(1.0 - max(0.0, data_age_seconds) / 900.0)
    agreement = 0.5 if voacap_observed_agreement is None else clamp01(
        voacap_observed_agreement
    )
    availability = clamp01(
        sources_available / sources_total if sources_total else 0.0
    )
    completeness = clamp01(profile_completeness)
    confidence = (
        0.35 * obs
        + 0.20 * freshness
        + 0.20 * agreement
        + 0.15 * availability
        + 0.10 * completeness
    )
    return round(clamp01(confidence), 4)


def agreement_between(
    voacap_reliability: float | None, observed_success: float | None
) -> float | None:
    """0..1 agreement between VOACAP reliability and observed activity.

    Returns ``None`` when either side is missing (not comparable).
    """
    if voacap_reliability is None or observed_success is None:
        return None
    return clamp01(1.0 - abs(clamp01(voacap_reliability) - clamp01(observed_success)))
