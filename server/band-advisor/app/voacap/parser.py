"""Parse voacapl Method-30 text output into structured per-band predictions.

The Method 30 report prints, for every predicted hour, a block of rows in
fixed 5-character Fortran columns.  The first row of a block ends with the
label ``FREQ`` and carries: hour (col 1), MUF (col 2), then one column per
frequency from the FREQUENCY card (unused slots print ``0.0``).  Each
following row carries one quantity per frequency column with its label at
the end of the line (``MODE``, ``MUFday``, ``SNR``, ``REL``, ``SNRxx`` ...).

This parser is deliberately defensive: unknown rows are ignored, unparsable
cells become ``None``, and FT8 bands whose frequency column is absent from
the output are reported as ``None`` entries rather than raising.

Units: ``SNR``/``SNRxx`` are median / 90th-percentile-exceeded SNR in
**dB-Hz** (see :mod:`.ft8_profile`), ``REL`` is 0.00-1.00 circuit
reliability, ``MUFday`` is the fraction of days the band is open.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from ..bands import BAND_ORDER, band_for_frequency_mhz

_COL_WIDTH = 5

#: Row labels we extract, mapped to attribute names.
_ROW_LABELS = {
    "MODE": "mode",
    "MUFday": "mufday",
    "SNR": "snr_dbhz",
    "REL": "rel",
    "SNRxx": "snrxx_dbhz",
    "SNR90": "snrxx_dbhz",  # some builds label the percentile row explicitly
}

_SSN_RE = re.compile(r"SSN\s*=\s*([0-9.]+)")


@dataclass
class BandPrediction:
    """One (hour, band) VOACAP prediction cell."""

    band: str
    freq_mhz: float
    mode: str | None = None
    rel: float | None = None
    snr_dbhz: float | None = None
    snrxx_dbhz: float | None = None
    mufday: float | None = None


@dataclass
class VoacapPrediction:
    """Parsed prediction: ``hours[utc_hour][band]`` -> BandPrediction or None."""

    ssn: float | None = None
    hours: dict[int, dict[str, BandPrediction | None]] = field(default_factory=dict)


def _chunks(line: str) -> list[str]:
    return [
        line[i : i + _COL_WIDTH] for i in range(0, len(line), _COL_WIDTH)
    ]


def _try_float(cell: str) -> float | None:
    cell = cell.strip()
    if not cell or cell == "-":
        return None
    try:
        return float(cell)
    except ValueError:
        return None


def parse_output(text: str) -> VoacapPrediction:
    """Parse a full voacapl Method-30 report. Never raises on malformed rows."""
    result = VoacapPrediction()

    ssn_match = _SSN_RE.search(text)
    if ssn_match:
        try:
            result.ssn = float(ssn_match.group(1))
        except ValueError:
            pass

    current_hour: int | None = None
    current_cols: list[tuple[int, str]] = []  # (column index, band name)
    n_value_cols = 0

    for raw_line in text.splitlines():
        line = raw_line.rstrip("\n")
        stripped = line.strip()

        if stripped.endswith("FREQ"):
            hour, cols = _parse_freq_line(line)
            if hour is None:
                current_hour = None
                continue
            current_hour = hour
            current_cols = cols
            n_value_cols = _count_numeric_cols(line)
            result.hours[hour] = {
                band: BandPrediction(band=band, freq_mhz=freq)
                for _, band, freq in _named(cols)
            }
            _fill_missing_bands(result.hours[hour])
            continue

        if current_hour is None or not stripped:
            continue

        label = line[n_value_cols * _COL_WIDTH :].strip()
        attr = _ROW_LABELS.get(label)
        if attr is None:
            continue
        cells = _chunks(line)
        hour_bands = result.hours[current_hour]
        for col_idx, band, _freq in _named(current_cols):
            pred = hour_bands.get(band)
            if pred is None or col_idx >= len(cells):
                continue
            cell = cells[col_idx]
            if attr == "mode":
                value: object = cell.strip() or None
                if value == "-":
                    value = None
            else:
                value = _try_float(cell)
            setattr(pred, attr, value)

    return result


def _parse_freq_line(line: str) -> tuple[int | None, list[tuple[int, str]]]:
    """Return (utc_hour, [(column_index, band, freq_mhz), ...]) for a FREQ row."""
    cells = _chunks(line)
    values: list[float | None] = [_try_float(c) for c in cells]
    if len(values) < 3 or values[0] is None:
        return None, []
    hour_raw = values[0]
    # VOACAP hours run 1..24 where 24 means 00 UTC.
    utc_hour = int(round(hour_raw)) % 24
    cols: list[tuple[int, str, float]] = []
    for idx in range(2, len(values)):
        freq = values[idx]
        if freq is None or freq <= 0.0:
            continue
        band = band_for_frequency_mhz(freq)
        if band is None:
            continue
        cols.append((idx, band, freq))
    return utc_hour, cols  # type: ignore[return-value]


def _count_numeric_cols(freq_line: str) -> int:
    """Number of 5-char value columns before the trailing FREQ label."""
    cells = _chunks(freq_line)
    n = 0
    for cell in cells:
        if _try_float(cell) is not None:
            n += 1
        else:
            break
    return n


def _named(cols: list) -> list[tuple[int, str, float]]:
    return list(cols)


def _fill_missing_bands(hour_bands: dict[str, BandPrediction | None]) -> None:
    """Guarantee an entry (possibly None) for every FT8 band."""
    for band in BAND_ORDER:
        hour_bands.setdefault(band, None)
