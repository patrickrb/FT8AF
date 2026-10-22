"""Build VOACAP input decks (``voacapx.dat`` card format) for voacapl.

VOACAP is driven by fixed-format "cards" — one keyword per line, values in
Fortran-style fixed-width fields.  This builder emits a Method-30 deck for
all 24 UTC hours at the 10 standard FT8 dial frequencies.

Card layout notes (kept close to the decks pythonprop/voacapl ship):

Every card is a 10-column keyword field followed by fixed-width numeric
fields, matching the formats voacapl's own deck writer uses
(``src/voacapw/voacap.for``)::

    SUNSPOT   (10X, F5.x ...)        e.g. ``SUNSPOT    68.0``
    CIRCUIT   (10X, F5.2,A1, 3(F9.2,A1), 2X,A1, 1X,I5)
    SYSTEM    (10X, 7F5.x)

A value that strays one column outside its field is silently misread by
the Fortran reader (``  68`` + ``.`` in the next column reads as 0.68), so
these widths are tested byte-for-byte against upstream's sample deck.

* ``SYSTEM       1.  145. 3.00  90. 16.0 3.00 0.10`` — fields are
  power-flag(1.), man-made noise at 3 MHz as a positive -dBW number,
  minimum takeoff angle (deg), required circuit reliability (%),
  required SNR (dB-Hz — see :mod:`.ft8_profile`), multipath power
  tolerance (dB) and max multipath delay (ms).
* TX power lives on the TX ANTENNA card's final field, in **kW**.
* The FREQUENCY card takes up to 11 F5.2 MHz values, no separators.

TODO(scaffold): the antenna classes use voacapl's stock antenna files
(see :data:`ANTENNA_CLASS_FILE`); per-class, per-band gain patterns are a
follow-up.
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

    Each maps to a file that voacapl's ``makeitshfbc`` installs under
    ``<itshfbc>/antennas`` (``default/`` and ``samples/`` -- there is no
    ``default/dipole.voa``). TODO(scaffold): real gain-pattern files per
    band would be a follow-up.
    """

    ISOTROPE = "ISOTROPE"
    WIRE = "WIRE"  # horizontal dipole-ish
    VERTICAL = "VERTICAL"  # whip
    BEAM = "BEAM"  # placeholder: modelled as dipole until real patterns exist


ANTENNA_CLASS_FILE: dict[AntennaClass, str] = {
    AntennaClass.ISOTROPE: "default/isotrope",
    # ITSA-1 type 23 horizontal half-wave dipole, quarter-wave high.
    AntennaClass.WIRE: "samples/sample.23",
    AntennaClass.VERTICAL: "default/swwhip.voa",
    AntennaClass.BEAM: "samples/sample.23",
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


def _coord(value: float, pos: str, neg: str, width: int) -> str:
    """Format one CIRCUIT-card coordinate: F<width>.2 + hemisphere letter."""
    hemi = pos if value >= 0 else neg
    return f"{abs(value):{width}.2f}{hemi}"


def _f5(value: float, decimals: int) -> str:
    """Render one 5-column Fortran F5.x field; raise rather than overflow it."""
    text = f"{value:5.{decimals}f}"
    if decimals == 0:
        text = f"{value:4.0f}."
    if len(text) != 5:
        raise ValueError(f"value {value!r} does not fit a 5-column card field")
    return text


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
        f"SUNSPOT   {_f5(params.ssn, 1)}",
        f"LABEL     {tx_grid:<20s}{rx_label}",
        (
            "CIRCUIT   "
            + _coord(tx_lat, "N", "S", 5)
            + _coord(tx_lon, "E", "W", 9)
            + _coord(params.rx_lat, "N", "S", 9)
            + _coord(params.rx_lon, "E", "W", 9)
            + "  S     0"
        ),
        (
            "SYSTEM    "
            + _f5(1, 0)
            + _f5(noise_pos, 0)
            + _f5(3.0, 2)
            + _f5(90, 0)
            + _f5(rsn, 1)
            + _f5(3.0, 2)
            + _f5(0.1, 2)
        ),
        "FPROB      1.00 1.00 1.00 0.00",
        f"ANTENNA       1    1    2   30     0.000[{tx_ant:<21s}]  0.0{power_kw:10.4f}",
        "ANTENNA       2    2    2   30     0.000[default/isotrope     ]  0.0    0.0000",
        f"FREQUENCY {freqs}",
        "METHOD       30    0",
        "EXECUTE",
        "QUIT",
    ]
    return "\n".join(lines) + "\n"
