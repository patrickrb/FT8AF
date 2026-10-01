"""End-to-end API tests with faked VOACAP + PSK layers (no network, no binary)."""

import json

import pytest
from fastapi.testclient import TestClient

from app.api import create_app
from app.pskreporter.aggregator import BandActivity, SampleQuality
from app.pskreporter.service import PersonalResult, RegionalActivity
from app.voacap.runner import VoacapError

# ---------------------------------------------------------------------- fakes


def voacap_entry(target="Europe", rel_by_band=None):
    rel_by_band = rel_by_band or {"20m": 0.93, "40m": 0.55, "17m": 0.81, "10m": 0.08}
    bands = {}
    for band, rel in rel_by_band.items():
        bands[band] = {
            "freqMhz": 14.1,
            "rel": rel,
            "snrDbHz": 40.0,
            "snr90DbHz": 20.0,
            "mufday": 0.8,
            "mode": "F2",
        }
    return {"target": target, "targetRegion": "EUROPE", "bands": bands}


class FakeVoacapService:
    def __init__(self, entry=None, fail=False):
        self.entry = entry or voacap_entry()
        self.fail = fail
        self.calls = []

    def predict(self, **kwargs):
        self.calls.append(kwargs)
        if self.fail:
            raise VoacapError("engine down")
        return self.entry

    def binary_available(self):
        return not self.fail


def band_activity(band, nearby=8, octants=5, max_km=8400.0, obs=25):
    return BandActivity(
        band=band,
        observation_count=obs,
        unique_transmitters=nearby + 3,
        unique_receivers=18,
        median_snr_db=-11,
        median_distance_km=2400.0,
        max_distance_km=max_km,
        paths_over_3000km=6,
        paths_over_8000km=1,
        directional_octants=octants,
        nearby_tx_to_region={"EUROPE": 4, "SOUTH_AMERICA": 2},
        nearby_tx_total=nearby,
        sample_quality=SampleQuality.GOOD,
    )


class FakePskService:
    def __init__(self, fixed_clock, fail=False, personal_fail=False):
        self._clock = fixed_clock
        self.fail = fail
        self.personal_fail = personal_fail

    def get_regional_activity(self, observer_grid, mode="FT8"):
        if self.fail:
            raise RuntimeError("pskreporter down")
        return RegionalActivity(
            bands={
                "20m": band_activity("20m"),
                "40m": band_activity("40m", nearby=4, octants=2, max_km=3200.0, obs=12),
                "17m": band_activity("17m", nearby=5, octants=4, max_km=9100.0, obs=15),
            },
            activity_ratio={"20m": 1.8, "40m": 0.9, "17m": 1.2},
            activity_trend={"20m": 0.4, "40m": -0.2, "17m": 0.1},
            fetched_at=self._clock.now(),
        )

    def get_personal_analytics(self, callsign, observer_grid, mode="FT8", regional=None):
        if self.fail or self.personal_fail:
            raise RuntimeError("personal query down")
        return PersonalResult(
            analytics={
                "enabled": True,
                "reportsReceived": 23,
                "uniqueReceivers": 18,
                "countriesReached": 7,
                "maximumDistanceKm": 8420,
                "medianSnrDb": -11,
                "bestSnrDb": -3,
                "performanceComparedToBaseline": 0.18,
            },
            per_band_performance={"20m": 1.0, "40m": 0.4},
            fetched_at=self._clock.now(),
        )


@pytest.fixture
def feature_config(settings):
    settings.feature_config_path.parent.mkdir(parents=True, exist_ok=True)
    settings.feature_config_path.write_text(
        json.dumps(
            {
                "version": 1,
                "flags": {
                    "bandAdvisor": True,
                    "personalPskAnalytics": True,
                    "propagationAlerts": False,
                },
            }
        )
    )


def make_client(settings, fixed_clock, voacap=None, psk=None) -> TestClient:
    app = create_app(
        settings=settings,
        clock=fixed_clock,
        voacap_service=voacap or FakeVoacapService(),
        psk_service=psk or FakePskService(fixed_clock),
    )
    return TestClient(app)


# ----------------------------------------------------------------------- tests


class TestHealthz:
    def test_ok(self, settings, fixed_clock):
        client = make_client(settings, fixed_clock)
        body = client.get("/healthz").json()
        assert body["status"] == "ok"
        assert body["service"] == "ft8af-band-advisor"
        assert isinstance(body["voacaplPresent"], bool)


class TestFeatureConfig:
    def test_serves_flags_with_expiry(self, settings, fixed_clock, feature_config):
        client = make_client(settings, fixed_clock)
        body = client.get("/v1/feature-config").json()
        assert body["version"] == 1
        assert body["generatedAt"] == "2026-09-27T12:00:00Z"
        assert body["expiresAt"] == "2026-09-27T18:00:00Z"  # +6h default TTL
        assert body["flags"] == {
            "bandAdvisor": True,
            "personalPskAnalytics": True,
            "propagationAlerts": False,
        }

    def test_missing_file_is_503(self, settings, fixed_clock):
        client = make_client(settings, fixed_clock)
        response = client.get("/v1/feature-config")
        assert response.status_code == 503
        assert response.json()["error"]["code"] == "FEATURE_CONFIG_UNAVAILABLE"


class TestRecommendationContract:
    URL = (
        "/v1/recommendation?grid=EM28&goal=MAKE_CONTACT&mode=FT8"
        "&power=STANDARD&antenna=WIRE&noise=RESIDENTIAL&callsign=K1AF"
    )

    def test_contract_shape(self, settings, fixed_clock):
        body = make_client(settings, fixed_clock).get(self.URL).json()
        assert body["generatedAt"] == "2026-09-27T12:00:00Z"
        assert body["validUntil"] == "2026-09-27T12:15:00Z"
        assert body["grid"] == "EM28"
        assert body["callsign"] == "K1AF"
        assert body["mode"] == "FT8"
        assert body["goal"] == "MAKE_CONTACT"
        assert body["recommendedBand"] == "20m"
        assert body["recommendedFrequencyHz"] == 14_074_000
        assert 0 < body["score"] <= 1
        assert 0 < body["confidence"] <= 1
        assert isinstance(body["summary"], str) and body["summary"]
        assert body["destinations"] == ["Europe", "South America"]
        evidence_types = [e["type"] for e in body["evidence"]]
        assert "VOACAP" in evidence_types
        assert "REGIONAL_PSK_REPORTER" in evidence_types
        assert "PERSONAL_PSK_REPORTER" in evidence_types
        for component in body["scoreComponents"]:
            assert set(component) == {"name", "weight", "value", "contribution"}
        assert body["personalAnalytics"]["reportsReceived"] == 23
        for alt in body["alternatives"]:
            assert set(alt) == {"band", "frequencyHz", "score", "reason"}
        assert body["sources"] == {
            "voacapAvailable": True,
            "regionalPskReporterAvailable": True,
            "personalPskReporterAvailable": True,
        }

    def test_score_components_weights_sum_to_one(self, settings, fixed_clock):
        body = make_client(settings, fixed_clock).get(self.URL).json()
        total = sum(c["weight"] for c in body["scoreComponents"])
        assert total == pytest.approx(1.0, abs=0.01)

    def test_no_callsign_omits_personal_fields(self, settings, fixed_clock):
        body = (
            make_client(settings, fixed_clock)
            .get("/v1/recommendation?grid=EM28&goal=DX")
            .json()
        )
        assert "callsign" not in body
        assert "personalAnalytics" not in body
        assert body["sources"]["personalPskReporterAvailable"] is False

    def test_grid_truncated_to_four_chars(self, settings, fixed_clock):
        body = (
            make_client(settings, fixed_clock)
            .get("/v1/recommendation?grid=em28ax&goal=DX")
            .json()
        )
        assert body["grid"] == "EM28"

    def test_deterministic(self, settings, fixed_clock):
        client = make_client(settings, fixed_clock)
        assert client.get(self.URL).json() == client.get(self.URL).json()

    def test_target_goal_uses_target_region(self, settings, fixed_clock):
        voacap = FakeVoacapService()
        client = make_client(settings, fixed_clock, voacap=voacap)
        body = client.get(
            "/v1/recommendation?grid=EM28&goal=TARGET&targetRegion=EUROPE"
        ).json()
        assert body["goal"] == "TARGET"
        assert body["targetRegion"] == "EUROPE"
        assert voacap.calls[0]["target_region"] == "EUROPE"


class TestRecommendationValidation:
    @pytest.mark.parametrize(
        "query,code",
        [
            ("", "MISSING_PARAMETER"),
            ("grid=ZZ99", "INVALID_GRID"),
            ("grid=1", "INVALID_GRID"),
            ("grid=EM28&goal=CONQUER", "INVALID_PARAMETER"),
            ("grid=EM28&power=MEGA", "INVALID_PARAMETER"),
            ("grid=EM28&antenna=TOWER", "INVALID_PARAMETER"),
            ("grid=EM28&noise=LOUD", "INVALID_PARAMETER"),
            ("grid=EM28&mode=CW", "INVALID_PARAMETER"),
            ("grid=EM28&callsign=NOT_A_CALL!", "INVALID_CALLSIGN"),
            ("grid=EM28&targetRegion=ATLANTIS", "INVALID_PARAMETER"),
            ("grid=EM28&goal=TARGET", "MISSING_PARAMETER"),
        ],
    )
    def test_bad_requests_are_400(self, settings, fixed_clock, query, code):
        response = make_client(settings, fixed_clock).get(
            f"/v1/recommendation?{query}"
        )
        assert response.status_code == 400
        body = response.json()
        assert set(body) == {"error"}
        assert body["error"]["code"] == code
        assert isinstance(body["error"]["message"], str)


class TestDegradation:
    def test_voacap_down_still_200(self, settings, fixed_clock):
        client = make_client(
            settings, fixed_clock, voacap=FakeVoacapService(fail=True)
        )
        response = client.get("/v1/recommendation?grid=EM28&goal=DX")
        assert response.status_code == 200
        body = response.json()
        assert body["sources"]["voacapAvailable"] is False
        assert body["sources"]["regionalPskReporterAvailable"] is True
        assert body["recommendedBand"]  # still recommends from PSK data alone

    def test_psk_down_still_200(self, settings, fixed_clock):
        client = make_client(
            settings, fixed_clock, psk=FakePskService(fixed_clock, fail=True)
        )
        response = client.get("/v1/recommendation?grid=EM28&goal=DX")
        assert response.status_code == 200
        body = response.json()
        assert body["sources"]["regionalPskReporterAvailable"] is False
        assert body["recommendedBand"] == "20m"  # VOACAP-only recommendation

    def test_everything_down_still_200(self, settings, fixed_clock):
        client = make_client(
            settings,
            fixed_clock,
            voacap=FakeVoacapService(fail=True),
            psk=FakePskService(fixed_clock, fail=True),
        )
        response = client.get("/v1/recommendation?grid=EM28&goal=DX&callsign=K1AF")
        assert response.status_code == 200
        body = response.json()
        assert body["sources"] == {
            "voacapAvailable": False,
            "regionalPskReporterAvailable": False,
            "personalPskReporterAvailable": False,
        }
        assert body["confidence"] < 0.4

    def test_personal_down_only(self, settings, fixed_clock):
        client = make_client(
            settings, fixed_clock, psk=FakePskService(fixed_clock, personal_fail=True)
        )
        body = client.get("/v1/recommendation?grid=EM28&callsign=K1AF").json()
        assert body["sources"]["personalPskReporterAvailable"] is False
        assert body["personalAnalytics"] is None  # requested but unavailable


class TestConditions:
    def test_shape(self, settings, fixed_clock):
        body = make_client(settings, fixed_clock).get("/v1/conditions?grid=EM28").json()
        assert body["grid"] == "EM28"
        assert len(body["bands"]) == 10
        top = body["bands"][0]
        assert set(top) == {
            "band",
            "score",
            "confidence",
            "activeRegions",
            "openingEvidence",
            # Machine-readable evidence for the Android alert policy
            # (PropagationAlertPolicy.kt) — names must stay in sync.
            "observationCount",
            "activityRatio",
            "voacapSupported",
            "unusualOpening",
        }
        assert top["band"] == "20m"
        assert "Europe" in top["activeRegions"]
        assert any("normal" in e for e in top["openingEvidence"])
        assert top["observationCount"] > 0
        assert top["activityRatio"] > 0
        assert isinstance(top["voacapSupported"], bool)
        assert isinstance(top["unusualOpening"], bool)

    def test_bad_grid(self, settings, fixed_clock):
        response = make_client(settings, fixed_clock).get("/v1/conditions?grid=XX")
        assert response.status_code == 400

    def test_missing_grid(self, settings, fixed_clock):
        response = make_client(settings, fixed_clock).get("/v1/conditions")
        assert response.status_code == 400


@pytest.mark.parametrize("path", ["/v1/recommendation?grid=EM28", "/v1/conditions?grid=EM28"])
def test_health_remains_responsive_during_blocking_upstream(settings, fixed_clock, path):
    import asyncio
    import threading
    import httpx

    started = threading.Event()
    release = threading.Event()

    class BlockingRecommender:
        def recommend(self, req):
            started.set()
            assert release.wait(3)
            return {"ok": True}

        def conditions(self, grid):
            return self.recommend(None)

    app = create_app(
        settings, fixed_clock,
        voacap_service=FakeVoacapService(),
        recommendation_service=BlockingRecommender(),
    )

    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            pending = asyncio.create_task(client.get(path))
            try:
                assert await asyncio.to_thread(started.wait, 1)
                assert not release.is_set()
                response = await asyncio.wait_for(client.get("/healthz"), 1)
                assert response.status_code == 200
            finally:
                release.set()
                await pending

    asyncio.run(run())
