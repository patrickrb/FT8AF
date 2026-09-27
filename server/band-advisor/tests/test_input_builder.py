import pytest

from app.voacap.ft8_profile import FT8SnrProfile
from app.voacap.input_builder import (
    AntennaClass,
    DeckParams,
    NoiseClass,
    PowerClass,
    build_deck,
)

GOLDEN_DECK = """\
LINEMAX      55       number of lines-per-page
COEFFS    CCIR
TIME          1   24    1    1
MONTH      2026 9.00
SUNSPOT      68.
LABEL     EM28                 EUROPE
CIRCUIT    38.50N  95.00W     50.00N  12.00E  S     0
SYSTEM       1.  145. 3.00  90. 16.0 3.00 0.10
FPROB      1.00 1.00 1.00 0.00
ANTENNA       1    1    2   30     0.000[default/dipole.voa   ]  0.0    0.1000
ANTENNA       2    2    2   30     0.000[default/isotrope     ]  0.0    0.0000
FREQUENCY  1.84 3.57 5.36 7.0710.1414.0718.1021.0724.9128.07
METHOD       30    0
EXECUTE
QUIT
"""


def _params(**overrides) -> DeckParams:
    base = dict(
        tx_grid="EM28",
        month=9,
        year=2026,
        rx_lat=50.0,
        rx_lon=12.0,
        rx_label="EUROPE",
        power_class=PowerClass.STANDARD,
        antenna_class=AntennaClass.WIRE,
        noise_class=NoiseClass.RESIDENTIAL,
        ssn=68.0,
    )
    base.update(overrides)
    return DeckParams(**base)


class TestBuildDeck:
    def test_golden_deck(self):
        assert build_deck(_params()) == GOLDEN_DECK

    def test_power_class_kw_conversion(self):
        deck = build_deck(_params(power_class=PowerClass.QRP))
        assert "    0.0050" in deck  # 5 W = 0.005 kW on the TX antenna card
        deck = build_deck(_params(power_class=PowerClass.HIGH))
        assert "    1.0000" in deck  # 1000 W = 1 kW

    def test_noise_classes(self):
        assert " 164." in build_deck(_params(noise_class=NoiseClass.QUIET))
        assert " 136." in build_deck(_params(noise_class=NoiseClass.URBAN))

    def test_req_snr_from_profile(self):
        deck = build_deck(
            _params(ft8_profile=FT8SnrProfile(required_snr_dbhz=13, snr_margin_db=6))
        )
        assert " 19.0 " in deck

    def test_grid_truncated_and_converted(self):
        deck = build_deck(_params(tx_grid="em28ax"))
        assert "LABEL     EM28" in deck
        assert " 38.50N" in deck

    def test_southern_western_hemispheres(self):
        # OCEANIA-ish receiver
        deck = build_deck(_params(rx_lat=-27.0, rx_lon=145.0))
        assert " 27.00S 145.00E" in deck

    def test_invalid_month_rejected(self):
        with pytest.raises(ValueError):
            build_deck(_params(month=13))

    def test_all_ten_ft8_frequencies_present(self):
        deck = build_deck(_params())
        freq_line = next(l for l in deck.splitlines() if l.startswith("FREQUENCY"))
        assert len(freq_line) == len("FREQUENCY ") + 10 * 5

    def test_isotrope_antenna(self):
        deck = build_deck(_params(antenna_class=AntennaClass.ISOTROPE))
        assert "ANTENNA       1    1    2   30     0.000[default/isotrope     ]" in deck
