import pytest

from app.bands import BAND_ORDER
from app.voacap.parser import parse_output


class TestParseFixture:
    def test_parses_three_hour_blocks(self, voacap_fixture_text):
        result = parse_output(voacap_fixture_text)
        assert set(result.hours) == {12, 13, 0}  # 24.0 maps to 00 UTC

    def test_ssn_from_header(self, voacap_fixture_text):
        assert parse_output(voacap_fixture_text).ssn == 68.0

    def test_hour12_20m_values(self, voacap_fixture_text):
        pred = parse_output(voacap_fixture_text).hours[12]["20m"]
        assert pred is not None
        assert pred.freq_mhz == pytest.approx(14.1)
        assert pred.rel == pytest.approx(0.93)
        assert pred.snr_dbhz == pytest.approx(53.0)
        assert pred.snrxx_dbhz == pytest.approx(37.0)
        assert pred.mufday == pytest.approx(0.88)
        assert pred.mode == "F2"

    def test_hour12_all_ten_bands_present(self, voacap_fixture_text):
        bands = parse_output(voacap_fixture_text).hours[12]
        for band in BAND_ORDER:
            assert bands[band] is not None, band

    def test_hour0_night_profile(self, voacap_fixture_text):
        bands = parse_output(voacap_fixture_text).hours[0]
        assert bands["80m"].rel == pytest.approx(0.85)
        assert bands["10m"].rel == pytest.approx(0.00)
        assert bands["160m"].snr_dbhz == pytest.approx(28.0)

    def test_negative_snr_parsed(self, voacap_fixture_text):
        bands = parse_output(voacap_fixture_text).hours[0]
        assert bands["20m"].snr_dbhz == pytest.approx(-6.0)
        assert bands["10m"].snrxx_dbhz == pytest.approx(-79.0)


MISSING_FREQ_OUTPUT = """\
 some header text                                     SSN =  33.

 12.0 15.0  7.1 14.1 21.1  0.0  0.0                                    FREQ
        F2   F2   F2   F2    -    -                                    MODE
      0.50 0.98 0.88 0.41  0.0  0.0                                    MUFday
      53.0 31.1 53.0 39.0  0.0  0.0                                    SNR
      0.93 0.55 0.93 0.60  0.0  0.0                                    REL
      37.0 10.0 37.0 20.0  0.0  0.0                                    SNRxx
"""


class TestDefensiveness:
    def test_missing_frequency_rows_become_none(self):
        bands = parse_output(MISSING_FREQ_OUTPUT).hours[12]
        assert bands["40m"] is not None
        assert bands["20m"] is not None
        assert bands["15m"] is not None
        # every other FT8 band exists in the dict but is None
        for band in BAND_ORDER:
            if band not in ("40m", "20m", "15m"):
                assert bands[band] is None, band

    def test_garbage_input_returns_empty(self):
        result = parse_output("this is not voacap output\nat all\n")
        assert result.hours == {}

    def test_empty_input(self):
        assert parse_output("").hours == {}

    def test_missing_rows_leave_none_fields(self):
        # Only FREQ + REL rows: SNR/MUFday/etc stay None rather than raising.
        text = (
            " 12.0 15.0  7.1  0.0                                                   FREQ\n"
            "      0.50 0.55  0.0                                                   REL\n"
        )
        pred = parse_output(text).hours[12]["40m"]
        assert pred.rel == pytest.approx(0.55)
        assert pred.snr_dbhz is None
        assert pred.mufday is None

    def test_unknown_row_labels_ignored(self, voacap_fixture_text):
        # LOSS / DBU / TANGLE rows exist in the fixture and must not break parsing.
        result = parse_output(voacap_fixture_text)
        assert result.hours[12]["40m"].rel == pytest.approx(0.55)
