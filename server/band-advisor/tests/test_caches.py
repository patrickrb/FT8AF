from app.pskreporter.cache import PskCache, personal_cache_key, regional_cache_key
from app.ttl_cache import TTLCache
from app.voacap.cache import VoacapCache, voacap_cache_key


class FakeTime:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class TestTTLCache:
    def test_set_get(self):
        time = FakeTime()
        cache = TTLCache(60, time)
        cache.set(("a",), 1)
        assert cache.get(("a",)) == 1

    def test_expiry(self):
        time = FakeTime()
        cache = TTLCache(60, time)
        cache.set(("a",), 1)
        time.t += 61
        assert cache.get(("a",)) is None

    def test_not_expired_at_boundary(self):
        time = FakeTime()
        cache = TTLCache(60, time)
        cache.set(("a",), 1)
        time.t += 59
        assert cache.get(("a",)) == 1

    def test_max_entries_evicts(self):
        time = FakeTime()
        cache = TTLCache(60, time, max_entries=2)
        cache.set(("a",), 1)
        time.t += 1
        cache.set(("b",), 2)
        time.t += 1
        cache.set(("c",), 3)
        assert len(cache) <= 2
        assert cache.get(("c",)) == 3

    def test_disk_persistence(self, tmp_path):
        time = FakeTime()
        path = tmp_path / "cache.json"
        c1 = TTLCache(60, time, disk_path=path)
        c1.set(("a", 1), {"x": 2})
        c2 = TTLCache(60, time, disk_path=path)
        assert c2.get(("a", 1)) == {"x": 2}

    def _persisted(self, tmp_path, wall_at_save, wall_at_load, ttl=60):
        path = tmp_path / "cache.json"
        wall = FakeTime()
        wall.t = wall_at_save
        c1 = TTLCache(ttl, FakeTime(), disk_path=path, wall_time_fn=wall)
        c1.set(("a",), 1)
        # new process: monotonic clock restarts from an unrelated value
        mono2 = FakeTime()
        mono2.t = 5.0
        wall.t = wall_at_load
        return TTLCache(ttl, mono2, disk_path=path, wall_time_fn=wall), mono2

    def test_disk_reload_subtracts_downtime(self, tmp_path):
        c2, mono2 = self._persisted(tmp_path, 1_000_000.0, 1_000_040.0)
        assert c2.get(("a",)) == 1  # 20 s of the 60 s TTL left
        mono2.t += 21
        assert c2.get(("a",)) is None

    def test_disk_entry_expired_during_downtime_is_dropped(self, tmp_path):
        c2, _ = self._persisted(tmp_path, 1_000_000.0, 1_000_000.0 + 86_400)
        assert c2.get(("a",)) is None
        assert len(c2) == 0

    def test_wall_clock_backwards_never_extends_ttl(self, tmp_path):
        c2, mono2 = self._persisted(tmp_path, 1_000_000.0, 999_000.0)
        mono2.t += 61
        assert c2.get(("a",)) is None

    def test_legacy_relative_format_ignored(self, tmp_path):
        path = tmp_path / "cache.json"
        path.write_text('[{"key": ["a"], "ttl_remaining": 60, "value": 1}]')
        assert TTLCache(60, FakeTime(), disk_path=path).get(("a",)) is None


class TestVoacapKey:
    def test_exact_key_shape(self):
        key = voacap_cache_key("em28xx", 9, 12, "STANDARD", "WIRE", "RESIDENTIAL", "EUROPE")
        assert key == ("EM28", 9, 12, "STANDARD", "WIRE", "RESIDENTIAL", "EUROPE")

    def test_cache_roundtrip(self):
        time = FakeTime()
        cache = VoacapCache(60, time)
        parts = dict(
            tx_grid="EM28",
            month=9,
            utc_hour=12,
            power_class="STANDARD",
            antenna_class="WIRE",
            noise_class="RESIDENTIAL",
            target_region="EUROPE",
        )
        cache.set({"bands": {}}, **parts)
        assert cache.get(**parts) == {"bands": {}}
        assert cache.get(**{**parts, "utc_hour": 13}) is None


class TestPskKeys:
    def test_square_granularity(self):
        # aggregates are observer-relative: a different square in the same
        # 2-char field must NOT reuse them (the raw upstream fetch is shared
        # separately by PskService)
        assert regional_cache_key("EM28", None, "FT8", 900) != regional_cache_key(
            "EM19", None, "FT8", 900
        )
        assert regional_cache_key("em28xx", None, "FT8", 900) == regional_cache_key(
            "EM28", None, "FT8", 900
        )
        assert regional_cache_key("EM28", None, "FT8", 900) != regional_cache_key(
            "FN31", None, "FT8", 900
        )

    def test_band_in_key(self):
        assert regional_cache_key("EM28", "20m", "FT8", 900) != regional_cache_key(
            "EM28", "40m", "FT8", 900
        )

    def test_personal_key_uppercases(self):
        assert personal_cache_key("k1af", "FT8", 900) == personal_cache_key(
            "K1AF", "FT8", 900
        )

    def test_regional_and_personal_isolated(self):
        time = FakeTime()
        cache = PskCache(300, 600, time)
        cache.set_regional("EM28", None, "FT8", 900, "regional")
        cache.set_personal("K1AF", "FT8", 900, "personal")
        assert cache.get_regional("EM28", None, "FT8", 900) == "regional"
        assert cache.get_personal("K1AF", "FT8", 900) == "personal"

    def test_personal_ttl_longer(self):
        time = FakeTime()
        cache = PskCache(300, 600, time)
        cache.set_regional("EM28", None, "FT8", 900, "regional")
        cache.set_personal("K1AF", "FT8", 900, "personal")
        time.t += 301
        assert cache.get_regional("EM28", None, "FT8", 900) is None
        assert cache.get_personal("K1AF", "FT8", 900) == "personal"
