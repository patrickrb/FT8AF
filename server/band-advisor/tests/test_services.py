"""VoacapService + PskService facades with faked runner/client."""

from datetime import datetime, timezone

import pytest

from app.pskreporter.baseline import ActivityBaseline
from app.pskreporter.client import FetchResult, PskReporterError, ReceptionReport
from app.pskreporter.service import PskService, build_personal_result
from app.voacap.input_builder import AntennaClass, NoiseClass, PowerClass
from app.voacap.runner import VoacapError
from app.voacap.service import VoacapService


class FakeRunner:
    def __init__(self, output: str):
        self.output = output
        self.calls = 0

    def run(self, deck_text: str) -> str:
        self.calls += 1
        self.last_deck = deck_text
        return self.output


PREDICT_ARGS = dict(
    tx_grid="EM28",
    month=9,
    year=2026,
    power_class=PowerClass.STANDARD,
    antenna_class=AntennaClass.WIRE,
    noise_class=NoiseClass.RESIDENTIAL,
    target_region="EUROPE",
)


class TestVoacapService:
    def test_predict_returns_band_dict(self, settings, fixed_clock, voacap_fixture_text):
        runner = FakeRunner(voacap_fixture_text)
        service = VoacapService(settings, fixed_clock, runner=runner)
        result = service.predict(utc_hour=12, **PREDICT_ARGS)
        assert result["target"] == "Europe"
        assert result["bands"]["20m"]["rel"] == pytest.approx(0.93)
        assert result["bands"]["20m"]["snrDbHz"] == pytest.approx(53.0)

    def test_one_run_caches_all_hours(self, settings, fixed_clock, voacap_fixture_text):
        runner = FakeRunner(voacap_fixture_text)
        service = VoacapService(settings, fixed_clock, runner=runner)
        service.predict(utc_hour=12, **PREDICT_ARGS)
        service.predict(utc_hour=13, **PREDICT_ARGS)
        service.predict(utc_hour=0, **PREDICT_ARGS)
        assert runner.calls == 1

    def test_different_region_reruns(self, settings, fixed_clock, voacap_fixture_text):
        runner = FakeRunner(voacap_fixture_text)
        service = VoacapService(settings, fixed_clock, runner=runner)
        service.predict(utc_hour=12, **PREDICT_ARGS)
        service.predict(utc_hour=12, **{**PREDICT_ARGS, "target_region": "ASIA"})
        assert runner.calls == 2

    def test_hour_missing_from_output_raises(
        self, settings, fixed_clock, voacap_fixture_text
    ):
        service = VoacapService(
            settings, fixed_clock, runner=FakeRunner(voacap_fixture_text)
        )
        with pytest.raises(VoacapError):
            service.predict(utc_hour=5, **PREDICT_ARGS)

    def test_runner_failure_propagates(self, settings, fixed_clock):
        class BoomRunner:
            def run(self, deck):
                raise VoacapError("boom")

        service = VoacapService(settings, fixed_clock, runner=BoomRunner())
        with pytest.raises(VoacapError):
            service.predict(utc_hour=12, **PREDICT_ARGS)


def _report(sender, sender_grid, receiver, receiver_grid, freq=14_074_500, snr=-10):
    return ReceptionReport(
        sender_callsign=sender,
        sender_grid=sender_grid,
        receiver_callsign=receiver,
        receiver_grid=receiver_grid,
        frequency_hz=freq,
        snr_db=snr,
        mode="FT8",
        flow_start_seconds=None,
    )


class FakeClient:
    def __init__(self, reports, fetched_at):
        self._reports = tuple(reports)
        self._fetched_at = fetched_at
        self.calls = []

    def fetch_reports(self, *, mode="FT8", sender_callsign=None, **_):
        self.calls.append(sender_callsign)
        reports = self._reports
        if sender_callsign:
            reports = tuple(
                r for r in reports if r.sender_callsign == sender_callsign.upper()
            )
        return FetchResult(reports=reports, fetched_at=self._fetched_at)


class FailingClient:
    def fetch_reports(self, **_):
        raise PskReporterError("down")


class TestPskService:
    def _service(self, settings, fixed_clock, client):
        return PskService(
            settings,
            fixed_clock,
            client=client,
            baseline=ActivityBaseline(path=settings.baseline_path),
        )

    def test_regional_aggregation(self, settings, fixed_clock):
        client = FakeClient(
            [
                _report("K0AAA", "EM28", "G0AAA", "JO01"),
                _report("K0BBB", "EM29", "G0BBB", "JN58"),
            ],
            fixed_clock.now(),
        )
        service = self._service(settings, fixed_clock, client)
        regional = service.get_regional_activity("EM28")
        assert regional.bands["20m"].unique_transmitters == 2
        assert regional.activity_ratio["20m"] > 0
        assert regional.fetched_at == fixed_clock.now()

    def test_regional_cached_within_ttl(self, settings, fixed_clock):
        client = FakeClient(
            [_report("K0AAA", "EM28", "G0AAA", "JO01")], fixed_clock.now()
        )
        service = self._service(settings, fixed_clock, client)
        service.get_regional_activity("EM28")
        service.get_regional_activity("EM29")  # same field EM -> same entry
        assert len(client.calls) == 1

    def test_failure_propagates(self, settings, fixed_clock):
        service = self._service(settings, fixed_clock, FailingClient())
        with pytest.raises(PskReporterError):
            service.get_regional_activity("EM28")

    def test_personal_analytics(self, settings, fixed_clock):
        client = FakeClient(
            [
                _report("K1AF", "EM28", "G0AAA", "JO01", snr=-11),
                _report("K1AF", "EM28", "VK2AAA", "QF56", snr=-15),
                _report("K0ZZZ", "EM28", "G0AAA", "JO01", snr=-5),
            ],
            fixed_clock.now(),
        )
        service = self._service(settings, fixed_clock, client)
        personal = service.get_personal_analytics("K1AF", "EM28")
        analytics = personal.analytics
        assert analytics["enabled"] is True
        assert analytics["reportsReceived"] == 2
        assert analytics["uniqueReceivers"] == 2
        assert analytics["maximumDistanceKm"] > 8000
        assert analytics["medianSnrDb"] == -13
        assert analytics["bestSnrDb"] == -11
        assert personal.per_band_performance["20m"] == 1.0

    def test_personal_cached(self, settings, fixed_clock):
        client = FakeClient(
            [_report("K1AF", "EM28", "G0AAA", "JO01")], fixed_clock.now()
        )
        service = self._service(settings, fixed_clock, client)
        service.get_personal_analytics("K1AF", "EM28")
        service.get_personal_analytics("K1AF", "EM28")
        assert client.calls == ["K1AF"]


class TestBuildPersonalResult:
    def test_empty_reports(self, fixed_clock):
        result = build_personal_result(
            FetchResult(reports=(), fetched_at=fixed_clock.now()),
            callsign="K1AF",
            regional=None,
            fetched_at=fixed_clock.now(),
        )
        assert result.analytics["reportsReceived"] == 0
        assert result.analytics["maximumDistanceKm"] == 0
        assert result.analytics["medianSnrDb"] is None
        assert result.per_band_performance == {}
