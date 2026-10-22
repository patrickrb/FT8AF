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
SUNSPOT    68.0
LABEL     EM28                EUROPE
CIRCUIT   38.50N    95.00W    50.00N    12.00E  S     0
SYSTEM       1. 145. 3.00  90. 16.0 3.00 0.10
FPROB      1.00 1.00 1.00 0.00
ANTENNA       1    1    2   30     0.000[samples/sample.23    ]  0.0    0.1000
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
        assert "CIRCUIT   38.50N" in deck

    def test_southern_western_hemispheres(self):
        # OCEANIA-ish receiver
        deck = build_deck(_params(rx_lat=-27.0, rx_lon=145.0))
        assert "    27.00S   145.00E" in deck

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


# --- Fortran fixed-column reads -------------------------------------------
#
# voacapl reads each card with a Fortran FORMAT; these helpers slice our
# output the same way so a field that drifts by one column fails loudly.
# Formats are the ones voacapl's own deck writer uses (src/voacapw/voacap.for).

#: Stock antenna files that voacapl's makeitshfbc installs under antennas/.
UPSTREAM_ANTENNA_FILES = {
    "default/isotrope",
    "default/swwhip.voa",
    "default/const17.voa",
    *(
        f"samples/sample.{n:02d}"
        for n in [*range(0, 15), *range(21, 33), *range(34, 40), 41, *range(43, 49), 90, 99]
    ),
}


def _card(deck: str, keyword: str) -> str:
    return next(l for l in deck.splitlines() if l.startswith(keyword))


def _fortran_f(field: str, decimals: int) -> float:
    """Fortran Fw.d read: no decimal point means `decimals` implied places."""
    text = field.strip()
    if "." in text:
        return float(text)
    return int(text) / (10**decimals)


class TestFortranColumns:
    def test_sunspot_value_inside_first_f5_field(self):
        line = _card(build_deck(_params(ssn=68.0)), "SUNSPOT")
        # 10X,13F5.2: an SSN of 68 must read as 68, not 0.68.
        assert _fortran_f(line[10:15], 2) == 68.0

    @pytest.mark.parametrize("ssn", [0.0, 5.0, 150.0, 250.0])
    def test_sunspot_range(self, ssn):
        line = _card(build_deck(_params(ssn=ssn)), "SUNSPOT")
        assert _fortran_f(line[10:15], 2) == ssn

    def test_sunspot_overflow_rejected(self):
        with pytest.raises(ValueError):
            build_deck(_params(ssn=12345.0))

    @pytest.mark.parametrize(
        "rx_lat,rx_lon", [(50.0, 12.0), (-27.0, 145.0), (-33.9, -70.65), (0.0, -179.99)]
    )
    def test_circuit_card_layout(self, rx_lat, rx_lon):
        line = _card(build_deck(_params(rx_lat=rx_lat, rx_lon=rx_lon)), "CIRCUIT")
        # 10X, F5.2,A1, 3(F9.2,A1), 2X,A1, 1X,I5
        assert _fortran_f(line[10:15], 2) == 38.5
        assert line[15] == "N"
        assert _fortran_f(line[16:25], 2) == 95.0
        assert line[25] == "W"
        assert _fortran_f(line[26:35], 2) == pytest.approx(abs(rx_lat))
        assert line[35] == ("N" if rx_lat >= 0 else "S")
        assert _fortran_f(line[36:45], 2) == pytest.approx(abs(rx_lon))
        assert line[45] == ("E" if rx_lon >= 0 else "W")
        assert line[48] == "S"
        assert int(line[50:55]) == 0

    def test_system_card_seven_f5_fields(self):
        line = _card(build_deck(_params()), "SYSTEM")
        fields = [line[10 + 5 * i : 15 + 5 * i] for i in range(7)]
        assert len(line) == 10 + 7 * 5
        values = [_fortran_f(f, 2) for f in fields]
        assert values == [1.0, 145.0, 3.0, 90.0, 16.0, 3.0, 0.1]

    @pytest.mark.parametrize("noise", list(NoiseClass))
    def test_system_noise_field_every_class(self, noise):
        line = _card(build_deck(_params(noise_class=noise)), "SYSTEM")
        assert _fortran_f(line[15:20], 0) > 100  # positive -dBW, own field
        assert _fortran_f(line[20:25], 2) == 3.0  # min angle not polluted

    def test_label_two_a20_fields(self):
        line = _card(build_deck(_params()), "LABEL")
        assert line[10:30].strip() == "EM28"
        assert line[30:50].strip() == "EUROPE"

    @pytest.mark.parametrize("antenna", list(AntennaClass))
    def test_antenna_files_exist_upstream(self, antenna):
        line = _card(build_deck(_params(antenna_class=antenna)), "ANTENNA       1")
        name = line[line.index("[") + 1 : line.index("]")].strip()
        assert name in UPSTREAM_ANTENNA_FILES
        assert len(line[line.index("[") + 1 : line.index("]")]) == 21
