import math

import pytest

from app.voacap.ft8_profile import (
    DEFAULT_FT8_PROFILE,
    FT8SnrProfile,
    snr_2500_from_dbhz,
    snr_dbhz_from_2500,
)


class TestConversion:
    def test_minus_21_in_2500hz_is_about_13_dbhz(self):
        # -21 dB @ 2500 Hz + 10*log10(2500) = -21 + 33.98 = 12.98
        assert snr_dbhz_from_2500(-21.0) == pytest.approx(12.98, abs=0.02)

    def test_roundtrip(self):
        assert snr_2500_from_dbhz(snr_dbhz_from_2500(-21.0)) == pytest.approx(-21.0)

    def test_bandwidth_term(self):
        assert snr_dbhz_from_2500(0.0) == pytest.approx(10 * math.log10(2500))


class TestProfile:
    def test_default_card_value_is_16(self):
        # 13 dB-Hz decode requirement + 3 dB planning margin
        assert DEFAULT_FT8_PROFILE.required_snr_dbhz == 13.0
        assert DEFAULT_FT8_PROFILE.snr_margin_db == 3.0
        assert DEFAULT_FT8_PROFILE.req_snr_card_db == 16.0

    def test_configurable(self):
        profile = FT8SnrProfile(required_snr_dbhz=10.0, snr_margin_db=0.0)
        assert profile.req_snr_card_db == 10.0

    def test_from_2500hz_threshold_rounds_to_13(self):
        profile = FT8SnrProfile.from_2500hz_threshold(-21.0, snr_margin_db=3.0)
        assert profile.required_snr_dbhz == 13
        assert profile.req_snr_card_db == 16
