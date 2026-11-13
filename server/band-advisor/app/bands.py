"""Standard FT8 dial frequencies and band mapping.

The 10 conventional FT8 dial frequencies (WSJT-X defaults).  A reception
report's frequency maps to a band when it falls within the dial frequency
plus the ~3 kHz audio passband, widened to +-4 kHz to absorb off-dial
operation and reporting jitter.
"""

from __future__ import annotations

# Ordered low band -> high band.
FT8_DIAL_FREQ_HZ: dict[str, int] = {
    "160m": 1_840_000,
    "80m": 3_573_000,
    "60m": 5_357_000,
    "40m": 7_074_000,
    "30m": 10_136_000,
    "20m": 14_074_000,
    "17m": 18_100_000,
    "15m": 21_074_000,
    "12m": 24_915_000,
    "10m": 28_074_000,
}

BAND_ORDER: list[str] = list(FT8_DIAL_FREQ_HZ.keys())

#: Frequencies for the VOACAP FREQUENCY card, in MHz.
FT8_DIAL_FREQ_MHZ: dict[str, float] = {
    band: hz / 1e6 for band, hz in FT8_DIAL_FREQ_HZ.items()
}

BAND_MATCH_TOLERANCE_HZ = 4_000


def band_for_frequency_hz(freq_hz: float) -> str | None:
    """Map a reported frequency to an FT8 band, or None if it is off-dial.

    Accepts dial .. dial + 4 kHz (FT8 audio sits above the dial), plus a
    4 kHz guard below the dial for below-dial reporting quirks.
    """
    try:
        f = float(freq_hz)
    except (TypeError, ValueError):
        return None
    for band, dial in FT8_DIAL_FREQ_HZ.items():
        if dial - BAND_MATCH_TOLERANCE_HZ <= f <= dial + BAND_MATCH_TOLERANCE_HZ:
            return band
    return None


def band_for_frequency_mhz(freq_mhz: float, tolerance_mhz: float = 0.1) -> str | None:
    """Map a VOACAP output frequency column (MHz, 1-2 decimals) to a band."""
    try:
        f = float(freq_mhz)
    except (TypeError, ValueError):
        return None
    for band, dial in FT8_DIAL_FREQ_MHZ.items():
        if abs(f - dial) <= tolerance_mhz:
            return band
    return None
