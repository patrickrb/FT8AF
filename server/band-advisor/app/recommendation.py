"""Recommendation orchestrator: VOACAP + PSK Reporter + scoring -> contract JSON.

This module owns the exact response contract the mobile clients parse.  Field
names, omission rules (``callsign``/``personalAnalytics`` only when a callsign
was supplied) and rounding are all load-bearing — change them only together
with the client parsers.

Degradation policy: a failed upstream NEVER propagates an exception out of
:meth:`RecommendationService.recommend`.  The source is flagged unavailable in
``sources``, its score components become ``None`` (dropped + renormalized by
:mod:`app.scoring`) and confidence drops.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from .bands import BAND_ORDER, FT8_DIAL_FREQ_HZ
from .clock import Clock, isoformat_z
from .config import Settings
from .pskreporter.service import PersonalResult, PskService, RegionalActivity
from .scoring import (
    Goal,
    agreement_between,
    clamp01,
    compute_confidence,
    score_band,
)
from .voacap.input_builder import AntennaClass, NoiseClass, PowerClass
from .voacap.regions import REGIONS, farthest_region, home_region_for_field
from .voacap.service import VoacapService

log = logging.getLogger(__name__)

#: Path length treated as "full DX potential" for normalization.
MAX_DX_KM = 12000.0

_SUMMARIES: dict[Goal, str] = {
    Goal.MAKE_CONTACT: (
        "Best mix of proven nearby activity and predicted reliability right now."
    ),
    Goal.DX: "Best combination of live activity and long-distance reliability.",
    Goal.TARGET: "Strongest predicted and observed path toward {target}.",
    Goal.POTA: "Best regional coverage for activator-style contacts right now.",
}

_COMPONENT_LABELS = {
    "voacapReliability": "predicted reliability",
    "targetReliability": "predicted reliability to target",
    "targetObservedPaths": "observed paths to target",
    "nearbyObservedSuccess": "nearby stations being heard",
    "normalizedLiveActivity": "live activity vs normal",
    "distancePotential": "distance potential",
    "activityTrend": "rising activity",
    "directionalDiversity": "many directions open",
    "personalPerformance": "your recent results",
}


@dataclass(frozen=True)
class RecommendationRequest:
    """Validated inputs for one recommendation."""

    grid: str  # normalized 4-char
    goal: Goal
    mode: str
    power: PowerClass
    antenna: AntennaClass
    noise: NoiseClass
    callsign: str | None = None
    target_region: str | None = None
    #: 0..1 — how many optional station parameters the client actually set.
    profile_completeness: float = 0.5


class RecommendationService:
    """Combines both upstream services into the client-facing contract."""

    def __init__(
        self,
        settings: Settings,
        clock: Clock,
        voacap: VoacapService,
        psk: PskService,
    ) -> None:
        self._settings = settings
        self._clock = clock
        self._voacap = voacap
        self._psk = psk

    # ------------------------------------------------------------------ public

    def recommend(self, req: RecommendationRequest) -> dict:
        now = self._clock.now()
        gathered = self._gather(req)
        scored = self._score_all_bands(req.goal, gathered)

        best_band, best_result = scored[0]
        best_components = gathered["components"][best_band]

        confidence = self._confidence(req, gathered, best_band, best_components)

        response: dict = {
            "generatedAt": isoformat_z(now),
            "validUntil": isoformat_z(
                now.__class__.fromtimestamp(
                    now.timestamp() + self._settings.recommendation_valid_s,
                    tz=now.tzinfo,
                )
            ),
            "grid": req.grid,
        }
        if req.callsign:
            response["callsign"] = req.callsign
        response.update(
            {
                "mode": req.mode,
                "goal": req.goal.value,
                "recommendedBand": best_band,
                "recommendedFrequencyHz": FT8_DIAL_FREQ_HZ[best_band],
                "score": round(best_result.score, 2),
                "confidence": round(confidence, 2),
                "summary": self._summary(req, gathered),
                "destinations": self._destinations(req, gathered, best_band),
                "evidence": self._evidence(req, gathered, best_band, now),
                "scoreComponents": [c.to_json() for c in best_result.components],
            }
        )
        if req.callsign:
            personal: PersonalResult | None = gathered["personal"]
            response["personalAnalytics"] = (
                personal.analytics if personal is not None else None
            )
        response["alternatives"] = [
            {
                "band": band,
                "frequencyHz": FT8_DIAL_FREQ_HZ[band],
                "score": round(result.score, 2),
                "reason": self._top_component_reason(result),
            }
            for band, result in scored[1:3]
            if result.score > 0.0
        ]
        response["sources"] = {
            "voacapAvailable": gathered["voacap_available"],
            "regionalPskReporterAvailable": gathered["psk_available"],
            "personalPskReporterAvailable": gathered["personal_available"],
        }
        return response

    def conditions(self, grid: str) -> dict:
        """Cheap per-band summary for the mobile alert checker."""
        now = self._clock.now()
        req = RecommendationRequest(
            grid=grid,
            goal=Goal.MAKE_CONTACT,
            mode="FT8",
            power=PowerClass.STANDARD,
            antenna=AntennaClass.WIRE,
            noise=NoiseClass.RESIDENTIAL,
        )
        gathered = self._gather(req)
        scored = self._score_all_bands(req.goal, gathered)
        bands = []
        for band, result in scored:
            components = gathered["components"][band]
            confidence = self._confidence(req, gathered, band, components)
            entry = {
                "band": band,
                "score": round(result.score, 2),
                "confidence": round(confidence, 2),
                "activeRegions": self._active_regions(gathered, band),
                "openingEvidence": self._opening_evidence(gathered, band),
            }
            # Machine-readable evidence consumed by the Android alert policy
            # (PropagationAlertPolicy.kt) — keep names in sync with the client.
            entry.update(self._condition_fields(gathered, band))
            bands.append(entry)
        return {
            "generatedAt": isoformat_z(now),
            "validUntil": isoformat_z(
                now.__class__.fromtimestamp(
                    now.timestamp() + self._settings.recommendation_valid_s,
                    tz=now.tzinfo,
                )
            ),
            "grid": grid,
            "bands": bands,
            "sources": {
                "voacapAvailable": gathered["voacap_available"],
                "regionalPskReporterAvailable": gathered["psk_available"],
            },
        }

    # ------------------------------------------------------------ data fusion

    def _gather(self, req: RecommendationRequest) -> dict:
        """Fetch all upstream data with per-source degradation."""
        now = self._clock.now()

        primary_region = self._primary_region(req)
        voacap_pred: dict | None = None
        try:
            voacap_pred = self._voacap.predict(
                tx_grid=req.grid,
                month=now.month,
                year=now.year,
                utc_hour=now.hour,
                power_class=req.power,
                antenna_class=req.antenna,
                noise_class=req.noise,
                target_region=primary_region,
            )
        except Exception as exc:  # any engine failure degrades, never raises
            log.warning("voacap unavailable: %s", exc)

        regional: RegionalActivity | None = None
        try:
            regional = self._psk.get_regional_activity(req.grid, mode=req.mode)
        except Exception as exc:
            log.warning("pskreporter regional unavailable: %s", exc)

        personal: PersonalResult | None = None
        if req.callsign:
            try:
                personal = self._psk.get_personal_analytics(
                    req.callsign, req.grid, mode=req.mode, regional=regional
                )
            except Exception as exc:
                log.warning("pskreporter personal unavailable: %s", exc)

        components = {
            band: self._band_components(req, band, voacap_pred, regional, personal)
            for band in BAND_ORDER
        }
        return {
            "now": now,
            "primary_region": primary_region,
            "voacap": voacap_pred,
            "regional": regional,
            "personal": personal,
            "components": components,
            "voacap_available": voacap_pred is not None,
            "psk_available": regional is not None,
            "personal_available": personal is not None,
        }

    def _primary_region(self, req: RecommendationRequest) -> str:
        if req.goal is Goal.TARGET and req.target_region:
            return req.target_region
        if req.target_region:
            return req.target_region
        if req.goal is Goal.DX:
            return "LONG_DX"
        # MAKE_CONTACT and POTA: close-in coverage.  For POTA this REGIONAL
        # ring is the "regional+national mix" prediction.
        return "REGIONAL"

    def _band_components(
        self,
        req: RecommendationRequest,
        band: str,
        voacap_pred: dict | None,
        regional: RegionalActivity | None,
        personal: PersonalResult | None,
    ) -> dict[str, float | None]:
        """Normalize every scoring input to 0..1 (or None = unavailable)."""
        comp: dict[str, float | None] = {}

        rel: float | None = None
        if voacap_pred is not None:
            band_pred = voacap_pred["bands"].get(band)
            if band_pred is not None and band_pred.get("rel") is not None:
                rel = clamp01(band_pred["rel"])
        comp["voacapReliability"] = rel
        comp["targetReliability"] = rel if req.goal is Goal.TARGET else None

        if regional is not None:
            activity = regional.bands.get(band)
            if activity is not None:
                comp["nearbyObservedSuccess"] = clamp01(activity.nearby_tx_total / 10.0)
                comp["distancePotential"] = clamp01(
                    (activity.max_distance_km or 0.0) / MAX_DX_KM
                )
                comp["directionalDiversity"] = clamp01(
                    activity.directional_octants / 8.0
                )
                ratio = regional.activity_ratio.get(band, 0.0)
                comp["normalizedLiveActivity"] = clamp01(ratio / 2.0)
                trend = regional.activity_trend.get(band, 0.0)
                comp["activityTrend"] = clamp01((trend + 1.0) / 2.0)
                comp["targetObservedPaths"] = self._target_paths(req, activity)
            else:
                # PSK data arrived and this band simply has no activity: that
                # is evidence of a dead band, not a missing source.
                comp["nearbyObservedSuccess"] = 0.0
                comp["distancePotential"] = 0.0
                comp["directionalDiversity"] = 0.0
                comp["normalizedLiveActivity"] = 0.0
                comp["activityTrend"] = 0.5  # flat
                comp["targetObservedPaths"] = 0.0
        else:
            for name in (
                "nearbyObservedSuccess",
                "distancePotential",
                "directionalDiversity",
                "normalizedLiveActivity",
                "activityTrend",
                "targetObservedPaths",
            ):
                comp[name] = None

        comp["personalPerformance"] = (
            personal.per_band_performance.get(band, 0.0)
            if personal is not None
            else None
        )
        return comp

    def _target_paths(self, req: RecommendationRequest, activity) -> float:
        region_name = self._concrete_region_name(req)
        if region_name is None:
            return 0.0
        count = activity.nearby_tx_to_region.get(region_name, 0)
        return clamp01(count / 5.0)

    def _concrete_region_name(self, req: RecommendationRequest) -> str | None:
        name = req.target_region or self._primary_region(req)
        if name in REGIONS:
            return name
        if name == "LONG_DX":
            return farthest_region(req.grid).name
        if name == "REGIONAL":
            return home_region_for_field(req.grid)
        return None

    def _score_all_bands(self, goal: Goal, gathered: dict) -> list[tuple[str, object]]:
        scored = [
            (band, score_band(goal, gathered["components"][band]))
            for band in BAND_ORDER
        ]
        # Deterministic: score desc, then canonical band order.
        scored.sort(key=lambda item: (-item[1].score, BAND_ORDER.index(item[0])))
        return scored

    # ---------------------------------------------------------------- outputs

    def _confidence(
        self,
        req: RecommendationRequest,
        gathered: dict,
        band: str,
        components: dict[str, float | None],
    ) -> float:
        regional: RegionalActivity | None = gathered["regional"]
        obs = 0
        age_s = 900.0
        if regional is not None:
            activity = regional.bands.get(band)
            obs = activity.observation_count if activity is not None else 0
            age_s = max(
                0.0, (gathered["now"] - regional.fetched_at).total_seconds()
            )
        sources_total = 3 if req.callsign else 2
        sources_available = sum(
            [
                gathered["voacap_available"],
                gathered["psk_available"],
                gathered["personal_available"] if req.callsign else 0,
            ]
        )
        return compute_confidence(
            observation_count=obs,
            data_age_seconds=age_s,
            voacap_observed_agreement=agreement_between(
                components.get("voacapReliability"),
                components.get("nearbyObservedSuccess"),
            ),
            sources_available=sources_available,
            sources_total=sources_total,
            profile_completeness=req.profile_completeness,
        )

    def _summary(self, req: RecommendationRequest, gathered: dict) -> str:
        template = _SUMMARIES[req.goal]
        if req.goal is Goal.TARGET:
            target = (
                gathered["voacap"]["target"]
                if gathered["voacap"] is not None
                else (req.target_region or "target").replace("_", " ").title()
            )
            return template.format(target=target)
        return template

    def _destinations(
        self, req: RecommendationRequest, gathered: dict, band: str
    ) -> list[str]:
        regional: RegionalActivity | None = gathered["regional"]
        if regional is not None:
            activity = regional.bands.get(band)
            if activity is not None and activity.nearby_tx_to_region:
                ranked = sorted(
                    activity.nearby_tx_to_region.items(),
                    key=lambda kv: (-kv[1], kv[0]),
                )
                return [REGIONS[name].display_name for name, _ in ranked[:2]]
        if gathered["voacap"] is not None:
            return [gathered["voacap"]["target"]]
        return []

    def _evidence(
        self, req: RecommendationRequest, gathered: dict, band: str, now
    ) -> list[dict]:
        evidence: list[dict] = []
        voacap = gathered["voacap"]
        if voacap is not None:
            band_pred = voacap["bands"].get(band)
            if band_pred is not None and band_pred.get("rel") is not None:
                evidence.append(
                    {
                        "type": "VOACAP",
                        "message": (
                            f"VOACAP predicts {band_pred['rel'] * 100:.0f}% "
                            f"reliability toward {voacap['target']} on {band} "
                            f"at {now.hour:02d}00Z."
                        ),
                    }
                )
        regional: RegionalActivity | None = gathered["regional"]
        if regional is not None:
            activity = regional.bands.get(band)
            if activity is not None:
                ratio = regional.activity_ratio.get(band, 0.0)
                evidence.append(
                    {
                        "type": "REGIONAL_PSK_REPORTER",
                        "message": (
                            f"{activity.nearby_tx_total} stations within 500 km "
                            f"heard on {band} in the last "
                            f"{self._settings.psk_window_s // 60} minutes "
                            f"(activity {ratio:.1f}x normal)."
                        ),
                    }
                )
        personal: PersonalResult | None = gathered["personal"]
        if personal is not None:
            a = personal.analytics
            evidence.append(
                {
                    "type": "PERSONAL_PSK_REPORTER",
                    "message": (
                        f"Your signal was received {a['reportsReceived']} times "
                        f"recently; best DX {a['maximumDistanceKm']} km."
                    ),
                }
            )
        return evidence

    def _top_component_reason(self, result) -> str:
        if not result.components:
            return "No supporting data."
        top = max(result.components, key=lambda c: c.contribution)
        label = _COMPONENT_LABELS.get(top.name, top.name)
        return f"Strong {label} ({top.value:.2f})."

    def _active_regions(self, gathered: dict, band: str) -> list[str]:
        regional: RegionalActivity | None = gathered["regional"]
        if regional is None:
            return []
        activity = regional.bands.get(band)
        if activity is None:
            return []
        return [
            REGIONS[name].display_name
            for name, count in sorted(activity.nearby_tx_to_region.items())
            if count > 0
        ]

    def _condition_fields(self, gathered: dict, band: str) -> dict:
        """Machine-readable per-band evidence for the mobile alert policy.

        ``unusualOpening`` = live activity far above the band-hour baseline
        while VOACAP predicts little or nothing — the "the model missed this"
        case the client labels explicitly. ``personalImprovement`` is absent:
        /v1/conditions is anonymous by design (no callsign parameter).
        """
        regional: RegionalActivity | None = gathered["regional"]
        activity = regional.bands.get(band) if regional is not None else None
        ratio = regional.activity_ratio.get(band, 0.0) if regional is not None else 0.0
        voacap = gathered["voacap"]
        rel = None
        if voacap is not None:
            band_pred = voacap["bands"].get(band)
            if band_pred is not None:
                rel = band_pred.get("rel")
        return {
            "observationCount": activity.observation_count if activity else 0,
            "activityRatio": round(ratio, 2),
            "voacapSupported": rel is not None and rel >= 0.5,
            "unusualOpening": ratio >= 2.5 and (rel is None or rel < 0.4),
        }

    def _opening_evidence(self, gathered: dict, band: str) -> list[str]:
        regional: RegionalActivity | None = gathered["regional"]
        out: list[str] = []
        if regional is not None:
            activity = regional.bands.get(band)
            if activity is not None:
                ratio = regional.activity_ratio.get(band, 0.0)
                if ratio >= 1.5:
                    out.append(f"activity {ratio:.1f}x normal")
                if activity.paths_over_8000km > 0:
                    out.append(f"{activity.paths_over_8000km} paths over 8000 km")
                elif activity.paths_over_3000km > 0:
                    out.append(f"{activity.paths_over_3000km} paths over 3000 km")
        voacap = gathered["voacap"]
        if voacap is not None:
            band_pred = voacap["bands"].get(band)
            if (
                band_pred is not None
                and band_pred.get("rel") is not None
                and band_pred["rel"] >= 0.7
            ):
                out.append(f"VOACAP reliability {band_pred['rel'] * 100:.0f}%")
        return out
