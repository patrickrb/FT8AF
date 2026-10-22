import pytest

from app.pskreporter.baseline import ActivityBaseline


class TestBaselineMath:
    def test_first_observation_seeds_mean(self):
        b = ActivityBaseline()
        b.update("20m", 12, 40)
        assert b.baseline("20m", 12) == 40.0

    def test_ewma_moves_toward_new_values(self):
        b = ActivityBaseline(alpha=0.5)
        b.update("20m", 12, 40)
        b.update("20m", 12, 80)
        assert b.baseline("20m", 12) == pytest.approx(60.0)

    def test_floor_prevents_divide_by_tiny(self):
        b = ActivityBaseline(floor=5.0)
        b.update("20m", 3, 1)  # very quiet band-hour
        # 10 observations on a normally dead band: ratio bounded by floor
        assert b.activity_ratio("20m", 3, 10) == pytest.approx(10 / 5.0)

    def test_ratio_normal_hour_is_one(self):
        b = ActivityBaseline()
        b.update("20m", 12, 40)
        assert b.activity_ratio("20m", 12, 40) == pytest.approx(1.0)

    def test_per_hour_keys_are_independent(self):
        b = ActivityBaseline()
        b.update("20m", 12, 100)
        b.update("20m", 3, 5)
        assert b.baseline("20m", 12) != b.baseline("20m", 3)

    def test_unseen_band_uses_floor(self):
        b = ActivityBaseline(floor=5.0)
        assert b.activity_ratio("12m", 12, 10) == pytest.approx(2.0)


class TestTrend:
    def test_no_history_flat(self):
        b = ActivityBaseline()
        assert b.activity_trend("20m") == 0.0

    def test_rising(self):
        b = ActivityBaseline()
        for count in (10, 20, 30):
            b.update("20m", 12, count)
        assert b.activity_trend("20m") > 0

    def test_falling(self):
        b = ActivityBaseline()
        for count in (30, 20, 10):
            b.update("20m", 12, count)
        assert b.activity_trend("20m") < 0

    def test_clamped(self):
        b = ActivityBaseline()
        b.update("20m", 12, 1)
        b.update("20m", 12, 1000)
        assert b.activity_trend("20m") == 1.0

    def test_only_last_three_windows(self):
        b = ActivityBaseline()
        for count in (1000, 10, 10, 10):
            b.update("20m", 12, count)
        assert b.activity_trend("20m") == 0.0


class TestPersistence:
    def test_roundtrip(self, tmp_path):
        path = tmp_path / "baseline.json"
        b1 = ActivityBaseline(path=path)
        b1.update("20m", 12, 40)
        b2 = ActivityBaseline(path=path)
        assert b2.baseline("20m", 12) == 40.0

    def test_corrupt_file_starts_fresh(self, tmp_path):
        path = tmp_path / "baseline.json"
        path.write_text("{not json")
        b = ActivityBaseline(path=path)
        assert b.baseline("20m", 12) == b._floor
