from app.pskreporter.aggregator import (
    SampleQuality,
    aggregate_band_activity,
    sample_quality_for_count,
)
from app.pskreporter.client import ReceptionReport

OBSERVER = "EM28"  # 38.5N 95.0W


def report(
    sender="K0AAA",
    sender_grid="EM28",
    receiver="G0AAA",
    receiver_grid="JO01",
    freq=14_074_500,
    snr=-10,
) -> ReceptionReport:
    return ReceptionReport(
        sender_callsign=sender,
        sender_grid=sender_grid,
        receiver_callsign=receiver,
        receiver_grid=receiver_grid,
        frequency_hz=freq,
        snr_db=snr,
        mode="FT8",
        flow_start_seconds=1_700_000_000,
    )


class TestBasicStats:
    def test_counts_and_uniques(self):
        reports = [
            report(sender="K0AAA", receiver="G0AAA"),
            report(sender="K0AAA", receiver="G0BBB"),
            report(sender="K0BBB", receiver="G0AAA"),
        ]
        stats = aggregate_band_activity(reports, OBSERVER)["20m"]
        assert stats.observation_count == 3
        assert stats.unique_transmitters == 2
        assert stats.unique_receivers == 2

    def test_median_and_max_distance(self):
        reports = [
            report(receiver_grid="JO01"),  # ~7000+ km to England
            report(receiver_grid="EM29"),  # ~100 km, next square up
            report(receiver_grid="EM48"),  # a few hundred km east
        ]
        stats = aggregate_band_activity(reports, OBSERVER)["20m"]
        assert stats.max_distance_km > 6000
        assert stats.median_distance_km < 600

    def test_median_snr(self):
        reports = [report(snr=-20), report(snr=-10), report(snr=-2)]
        stats = aggregate_band_activity(reports, OBSERVER)["20m"]
        assert stats.median_snr_db == -10

    def test_distance_thresholds(self):
        reports = [
            report(receiver_grid="EM29"),  # short
            report(receiver_grid="JO01"),  # > 3000 km (transatlantic)
            report(receiver_grid="QF56"),  # > 8000 km (Australia)
        ]
        stats = aggregate_band_activity(reports, OBSERVER)["20m"]
        assert stats.paths_over_3000km == 2
        assert stats.paths_over_8000km == 1

    def test_band_split(self):
        reports = [report(freq=14_074_500), report(freq=7_074_100)]
        result = aggregate_band_activity(reports, OBSERVER)
        assert result["20m"].observation_count == 1
        assert result["40m"].observation_count == 1

    def test_off_dial_frequency_ignored(self):
        result = aggregate_band_activity([report(freq=14_200_000)], OBSERVER)
        assert result == {}


class TestDirectionalDiversity:
    def test_octants_counted_for_nearby_transmitters(self):
        reports = [
            report(receiver_grid="JO01"),  # NE from EM28
            report(receiver_grid="QF56"),  # W/SW-ish long path
            report(receiver_grid="FF95"),  # SE to South America
        ]
        stats = aggregate_band_activity(reports, OBSERVER)["20m"]
        assert stats.directional_octants == 3

    def test_far_transmitters_do_not_count(self):
        # transmitter in Japan: not within 1500 km of the observer
        reports = [report(sender_grid="PM95", receiver_grid="QF56")]
        stats = aggregate_band_activity(reports, OBSERVER)["20m"]
        assert stats.directional_octants == 0


class TestNearbyTransmitters:
    def test_nearby_tx_reaching_regions(self):
        reports = [
            report(sender="K0AAA", sender_grid="EM28", receiver_grid="JO01"),  # EU
            report(sender="K0BBB", sender_grid="EM29", receiver_grid="JN58"),  # EU
            report(sender="K0AAA", sender_grid="EM28", receiver_grid="PM95"),  # Asia
            # not nearby (Japan, way beyond 500 km):
            report(sender="JA1AAA", sender_grid="PM95", receiver_grid="JO01"),
        ]
        stats = aggregate_band_activity(reports, OBSERVER)["20m"]
        assert stats.nearby_tx_to_region["EUROPE"] == 2
        assert stats.nearby_tx_to_region["ASIA"] == 1
        assert stats.nearby_tx_total == 2

    def test_distinct_transmitters_not_reports(self):
        reports = [
            report(sender="K0AAA", receiver="G0AAA", receiver_grid="JO01"),
            report(sender="K0AAA", receiver="G0BBB", receiver_grid="JO02"),
        ]
        stats = aggregate_band_activity(reports, OBSERVER)["20m"]
        assert stats.nearby_tx_to_region["EUROPE"] == 1


class TestSampleQuality:
    def test_thresholds(self):
        assert sample_quality_for_count(0) is SampleQuality.NONE
        assert sample_quality_for_count(4) is SampleQuality.NONE
        assert sample_quality_for_count(5) is SampleQuality.LOW
        assert sample_quality_for_count(19) is SampleQuality.LOW
        assert sample_quality_for_count(20) is SampleQuality.GOOD

    def test_quality_on_aggregate(self):
        reports = [report(sender=f"K{i}ABC") for i in range(25)]
        stats = aggregate_band_activity(reports, OBSERVER)["20m"]
        assert stats.sample_quality is SampleQuality.GOOD
