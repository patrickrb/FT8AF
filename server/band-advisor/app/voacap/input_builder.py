"""Build VOACAP input decks (``voacapx.dat`` card format) for voacapl.

VOACAP is driven by fixed-format "cards" — one keyword per line, values in
Fortran-style fixed-width fields.  This builder emits a Method-30 deck for
all 24 UTC hours at the 10 standard FT8 dial frequencies.

Card layout notes (kept close to the decks pythonprop/voacapl ship):

* ``SYSTEM   1. <noise>. 3.00  90. <rsn> 3.00 0.10`` — fields are
  power-flag(1.), man-made noise at 3 MHz as a positive -dBW number,
  minimum takeoff angle (deg), required circuit reliability (%),
  required SNR (dB-Hz — see :mod:`.ft8_profile`), multipath power
  tolerance (dB) and max multipath delay (ms).
* TX power lives on the TX ANTENNA card's final field, in **kW**.
* The FREQUENCY card takes up to 11 F5.2 MHz values, no separators.

TODO(scaffold): the column alignment below matches published voacapx.dat
samples; verify byte-for-byte against a real voacapl run once the container
image exists, and replace the generic antenna files with per-class gain
patterns.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from ..bands import FT8_DIAL_FREQ_MHZ
from ..geo import grid_to_latlon, normalize_grid4
from .ft8_profile import DEFAULT_FT8_PROFILE, FT8SnrProfile


class PowerClass(str, Enum):
    """Coarse TX power classes exposed by the API, mapped to watts."""

    QRP = "QRP"  # 5 W
    LOW = "LOW"  # 25 W
    STANDARD = "STANDARD"  # 100 W
    HIGH = "HIGH"  # 1000 W


POWER_CLASS_WATTS: dict[PowerClass, float] = {
    PowerClass.QRP: 5.0,
    PowerClass.LOW: 25.0,
    PowerClass.STANDARD: 100.0,
    PowerClass.HIGH: 1000.0,
}


class AntennaClass(str, Enum):
    """Coarse antenna classes mapped to built-in voacapl antenna cards.

    TODO(scaffold): these are simple built-in models (isotrope / dipole /
    whip); real gain-pattern files per band would be a follow-up.
    """

    ISOTROPE = "ISOTROPE"
    WIRE = "WIRE"  # horizontal dipole-ish
    VERTICAL = "VERTICAL"  # whip
    BEAM = "BEAM"  # placeholder: modelled as dipole until real patterns exist


ANTENNA_CLASS_FILE: dict[AntennaClass, str] = {
    AntennaClass.ISOTROPE: "default/isotrope",
    AntennaClass.WIRE: "default/dipole.voa",
    AntennaClass.VERTICAL: "default/swwhip.voa",
    AntennaClass.BEAM: "default/dipole.voa",
}


class NoiseClass(str, Enum):
    """Man-made noise environment (dBW/Hz at 3 MHz, ITU-R P.372 style)."""

    QUIET = "QUIET"  # -164
    RURAL = "RURAL"  # -154
    RESIDENTIAL = "RESIDENTIAL"  # -145
    URBAN = "URBAN"  # -136


NOISE_CLASS_DBW: dict[NoiseClass, float] = {
    NoiseClass.QUIET: -164.0,
    NoiseClass.RURAL: -154.0,
    NoiseClass.RESIDENTIAL: -145.0,
    NoiseClass.URBAN: -136.0,
}


@dataclass(frozen=True)
class DeckParams:
    """Everything needed to build one VOACAP run deck."""

    tx_grid: str  # 4-char Maidenhead square
    month: int  # 1..12
    year: int
    rx_lat: float
    rx_lon: float
    rx_label: str
    power_class: PowerClass
    antenna_class: AntennaClass
    noise_class: NoiseClass
    ssn: float
    ft8_profile: FT8SnrProfile = DEFAULT_FT8_PROFILE


def _coord(value: float, pos: str, neg: str) -> str:
    """Format one CIRCUIT-card coordinate: F6.2 + hemisphere letter."""
    hemi = pos if value >= 0 else neg
    return f"{abs(value):6.2f}{hemi}"


def build_deck(params: DeckParams) -> str:
    """Render the full voacapx.dat card deck as text (trailing newline included)."""
    if not 1 <= params.month <= 12:
        raise ValueError(f"month out of range: {params.month}")
    tx_grid = normalize_grid4(params.tx_grid)
    tx_lat, tx_lon = grid_to_latlon(tx_grid)

    power_kw = POWER_CLASS_WATTS[params.power_class] / 1000.0
    noise_pos = -NOISE_CLASS_DBW[params.noise_class]  # card wants positive
    rsn = params.ft8_profile.req_snr_card_db
    tx_ant = ANTENNA_CLASS_FILE[params.antenna_class]
    rx_label = (params.rx_label or "RX")[:20]

    freqs = "".join(f"{mhz:5.2f}" for mhz in FT8_DIAL_FREQ_MHZ.values())

    lines = [
        "LINEMAX      55       number of lines-per-page",
        "COEFFS    CCIR",
        "TIME          1   24    1    1",
        f"MONTH      {params.year:4d}{params.month:5.2f}",
        f"SUNSPOT    {params.ssn:4.0f}.",
        f"LABEL     {tx_grid:<20s} {rx_label}",
        (
            "CIRCUIT   "
            + _coord(tx_lat, "N", "S")
            + " "
            + _coord(tx_lon, "E", "W")
            + "    "
            + _coord(params.rx_lat, "N", "S")
            + " "
            + _coord(params.rx_lon, "E", "W")
            + "  S     0"
        ),
        f"SYSTEM       1. {noise_pos:4.0f}. 3.00  90. {rsn:4.1f} 3.00 0.10",
        "FPROB      1.00 1.00 1.00 0.00",
        f"ANTENNA       1    1    2   30     0.000[{tx_ant:<21s}]  0.0{power_kw:10.4f}",
        "ANTENNA       2    2    2   30     0.000[default/isotrope     ]  0.0    0.0000",
        f"FREQUENCY {freqs}",
        "METHOD       30    0",
        "EXECUTE",
        "QUIT",
    ]
    return "\n".join(lines) + "\n"
