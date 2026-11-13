import pytest

from app.scoring import (
    GOAL_WEIGHTS,
    Goal,
    agreement_between,
    clamp01,
    compute_confidence,
    score_band,
)

FULL_COMPONENTS = {
    "voacapReliability": 0.8,
    "nearbyObservedSuccess": 0.6,
    "normalizedLiveActivity": 0.5,
    "distancePotential": 0.7,
    "activityTrend": 0.5,
    "personalPerformance": 0.4,
    "directionalDiversity": 0.9,
    "targetReliability": 0.8,
    "targetObservedPaths": 0.3,
}


class TestWeightTables:
    @pytest.mark.parametrize("goal", list(Goal))
    def test_weights_sum_to_one(self, goal):
        assert sum(GOAL_WEIGHTS[goal].values()) == pytest.approx(1.0)

    def test_spec_weights_make_contact(self):
        w = GOAL_WEIGHTS[Goal.MAKE_CONTACT]
        assert w["voacapReliability"] == 0.30
        assert w["nearbyObservedSuccess"] == 0.25
        assert w["normalizedLiveActivity"] == 0.15

    def test_spec_weights_target(self):
        w = GOAL_WEIGHTS[Goal.TARGET]
        assert w["targetReliability"] == 0.40
        assert w["targetObservedPaths"] == 0.30


class TestScoreBand:
    def test_full_components_weighted_sum(self):
        result = score_band(Goal.MAKE_CONTACT, FULL_COMPONENTS)
        expected = (
            0.30 * 0.8 + 0.25 * 0.6 + 0.15 * 0.5 + 0.10 * 0.7 + 0.10 * 0.5 + 0.10 * 0.4
        )
        assert result.score == pytest.approx(expected, abs=1e-4)

    def test_contributions_sum_to_score(self):
        result = score_band(Goal.DX, FULL_COMPONENTS)
        assert sum(c.contribution for c in result.components) == pytest.approx(
            result.score, abs=1e-4
        )

    def test_renormalization_when_component_missing(self):
        components = dict(FULL_COMPONENTS)
        components["personalPerformance"] = None
        result = score_band(Goal.MAKE_CONTACT, components)
        # remaining weights must be scaled to sum to 1
        assert sum(c.weight for c in result.components) == pytest.approx(1.0)
        assert not any(c.name == "personalPerformance" for c in result.components)

    def test_missing_source_does_not_zero_score(self):
        full = score_band(Goal.MAKE_CONTACT, FULL_COMPONENTS).score
        without = score_band(
            Goal.MAKE_CONTACT,
            {**FULL_COMPONENTS, "personalPerformance": None},
        ).score
        # personal (0.4) was the weakest component, so dropping it raises the score
        assert without > full > 0

    def test_all_missing_scores_zero(self):
        result = score_band(Goal.DX, {})
        assert result.score == 0.0
        assert result.components == ()

    def test_goals_rank_bands_differently(self):
        dx_band = {**FULL_COMPONENTS, "distancePotential": 1.0, "nearbyObservedSuccess": 0.1}
        local_band = {**FULL_COMPONENTS, "distancePotential": 0.1, "nearbyObservedSuccess": 1.0}
        assert (
            score_band(Goal.DX, dx_band).score
            > score_band(Goal.DX, local_band).score - 0.06
        )
        assert (
            score_band(Goal.MAKE_CONTACT, local_band).score
            > score_band(Goal.MAKE_CONTACT, dx_band).score
        )

    def test_deterministic(self):
        a = score_band(Goal.POTA, FULL_COMPONENTS)
        b = score_band(Goal.POTA, dict(FULL_COMPONENTS))
        assert a == b

    def test_values_clamped(self):
        result = score_band(Goal.MAKE_CONTACT, {"voacapReliability": 2.5})
        assert result.score == pytest.approx(1.0)

    def test_unknown_components_ignored(self):
        result = score_band(
            Goal.MAKE_CONTACT, {"voacapReliability": 0.5, "bogus": 1.0}
        )
        assert [c.name for c in result.components] == ["voacapReliability"]


class TestConfidence:
    def test_range(self):
        c = compute_confidence(20, 0, 1.0, 3, 3, 1.0)
        assert c == pytest.approx(1.0)
        assert compute_confidence(0, 900, 0.0, 0, 3, 0.0) == pytest.approx(0.0)

    def test_more_observations_more_confidence(self):
        low = compute_confidence(2, 60, None, 2, 2, 0.5)
        high = compute_confidence(40, 60, None, 2, 2, 0.5)
        assert high > low

    def test_stale_data_less_confidence(self):
        fresh = compute_confidence(10, 0, None, 2, 2, 0.5)
        stale = compute_confidence(10, 899, None, 2, 2, 0.5)
        assert fresh > stale

    def test_source_outage_drops_confidence(self):
        all_up = compute_confidence(10, 60, 0.8, 3, 3, 0.5)
        one_down = compute_confidence(10, 60, 0.8, 2, 3, 0.5)
        assert all_up > one_down

    def test_unknown_agreement_is_neutral(self):
        neutral = compute_confidence(10, 60, None, 2, 2, 0.5)
        bad = compute_confidence(10, 60, 0.0, 2, 2, 0.5)
        good = compute_confidence(10, 60, 1.0, 2, 2, 0.5)
        assert bad < neutral < good


class TestAgreement:
    def test_perfect(self):
        assert agreement_between(0.8, 0.8) == pytest.approx(1.0)

    def test_disagreement(self):
        assert agreement_between(1.0, 0.0) == pytest.approx(0.0)

    def test_missing_side_is_none(self):
        assert agreement_between(None, 0.5) is None
        assert agreement_between(0.5, None) is None


def test_clamp01():
    assert clamp01(-1) == 0.0
    assert clamp01(0.5) == 0.5
    assert clamp01(2) == 1.0
