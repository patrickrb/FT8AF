import math

import pytest

from app.geo import (
    GridError,
    destination_point,
    grid_azimuth_deg,
    grid_distance_km,
    grid_to_latlon,
    haversine_km,
    is_valid_callsign,
    normalize_grid4,
)


class TestNormalizeGrid4:
    def test_truncates_and_uppercases(self):
        assert normalize_grid4("em28ax") == "EM28"

    def test_valid_passthrough(self):
        assert normalize_grid4("FN31") == "FN31"

    @pytest.mark.parametrize("bad", ["", "E", "1234", "ZZ99", "EM2", "em2x", "SS00"])
    def test_rejects_invalid(self, bad):
        with pytest.raises(GridError):
            normalize_grid4(bad)

    def test_rejects_non_string(self):
        with pytest.raises(GridError):
            normalize_grid4(1234)  # type: ignore[arg-type]


class TestGridToLatLon:
    def test_em28_center(self):
        lat, lon = grid_to_latlon("EM28")
        assert lat == pytest.approx(38.5)
        assert lon == pytest.approx(-95.0)

    def test_six_char(self):
        lat, lon = grid_to_latlon("JO01ab")
        # subsquare 'ab' center: lon subsquare 0, lat subsquare 1
        assert lat == pytest.approx(51.0 + 1.5 / 24.0)
        assert lon == pytest.approx(0.0 + 1.0 / 24.0)

    def test_field_center(self):
        lat, lon = grid_to_latlon("EM")
        assert lat == pytest.approx(35.0)
        assert lon == pytest.approx(-90.0)

    def test_invalid(self):
        with pytest.raises(GridError):
            grid_to_latlon("Q!")


class TestDistanceAzimuth:
    def test_known_distance(self):
        # Kansas City area (EM28) to central Europe (JN58): about 8000 km
        km = grid_distance_km("EM28", "JN58")
        assert 7500 < km < 8500

    def test_zero_distance(self):
        assert grid_distance_km("EM28", "EM28") == pytest.approx(0.0)

    def test_azimuth_northeast_toward_europe(self):
        az = grid_azimuth_deg("EM28", "JN58")
        assert 20 < az < 60  # great-circle path to Europe leaves to the NE

    def test_haversine_equator_degree(self):
        km = haversine_km(0, 0, 0, 1)
        assert km == pytest.approx(111.2, abs=0.5)

    def test_destination_point_roundtrip(self):
        lat, lon = destination_point(38.5, -95.0, 45.0, 1500.0)
        assert haversine_km(38.5, -95.0, lat, lon) == pytest.approx(1500.0, abs=1.0)


class TestCallsign:
    @pytest.mark.parametrize("call", ["K1AF", "KS3CKC", "W1AW", "VE3XYZ", "2E0ABC"])
    def test_valid(self, call):
        assert is_valid_callsign(call)

    @pytest.mark.parametrize("call", ["", "K", "123", "K1AF!!", "THIS-IS-NOT"])
    def test_invalid(self, call):
        assert not is_valid_callsign(call)
