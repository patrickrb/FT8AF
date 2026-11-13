import pytest

from app.geo import grid_distance_km, grid_to_latlon, haversine_km
from app.voacap.regions import (
    REGIONS,
    TARGET_REGION_NAMES,
    farthest_region,
    home_region_for_field,
    resolve_target,
)


class TestRegionTable:
    def test_seven_concrete_regions(self):
        assert set(REGIONS) == {
            "EUROPE",
            "NORTH_AMERICA_EAST",
            "NORTH_AMERICA_WEST",
            "SOUTH_AMERICA",
            "AFRICA",
            "ASIA",
            "OCEANIA",
        }

    def test_pseudo_regions_accepted_by_api(self):
        assert "REGIONAL" in TARGET_REGION_NAMES
        assert "LONG_DX" in TARGET_REGION_NAMES

    def test_grids_are_valid(self):
        for region in REGIONS.values():
            for grid in region.grids:
                grid_to_latlon(grid)  # must not raise


class TestHomeRegion:
    @pytest.mark.parametrize(
        "grid,expected",
        [
            ("EM28", "NORTH_AMERICA_EAST"),
            ("CN87", "NORTH_AMERICA_WEST"),
            ("JO01", "EUROPE"),
            ("PM95", "ASIA"),
            ("QF56", "OCEANIA"),
            ("GG66", "SOUTH_AMERICA"),
            ("KG33", "AFRICA"),
        ],
    )
    def test_field_mapping(self, grid, expected):
        assert home_region_for_field(grid) == expected


class TestResolveTarget:
    def test_concrete_region(self):
        name, lat, lon = resolve_target("EM28", "EUROPE")
        assert name == "Europe"
        assert (lat, lon) == (50.0, 12.0)

    def test_regional_is_1500km_ring(self):
        _, lat, lon = resolve_target("EM28", "REGIONAL")
        obs_lat, obs_lon = grid_to_latlon("EM28")
        assert haversine_km(obs_lat, obs_lon, lat, lon) == pytest.approx(
            1500.0, abs=2.0
        )

    def test_long_dx_is_farthest_region(self):
        name, lat, lon = resolve_target("EM28", "LONG_DX")
        region = farthest_region("EM28")
        assert name == region.display_name
        # from Kansas, the far side of the planet is the Indian Ocean side
        assert region.name in ("OCEANIA", "AFRICA", "ASIA")
        assert grid_distance_km("EM28", "EM28") < haversine_km(
            *grid_to_latlon("EM28"), lat, lon
        )
