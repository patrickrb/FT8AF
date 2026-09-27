"""FT8 required-SNR profile for VOACAP decks.

Why this module exists — the units are a classic trap
-----------------------------------------------------
VOACAP's REQ.SNR (the ``RSN`` field on the SYSTEM card) is specified in
**dB-Hz**: the signal-to-noise ratio referenced to a 1 Hz noise bandwidth.

The commonly quoted FT8 decode threshold of **-21 dB** is instead referenced
to a **2500 Hz** SSB bandwidth (the WSJT-X convention for reporting SNR).
Converting between the two:

    SNR(dB-Hz) = SNR(2500 Hz) + 10*log10(2500)
               = -21 + 33.98
               ~ 13 dB-Hz

So the correct VOACAP requirement for "an FT8 signal is just decodable" is
about **13 dB-Hz**, not -21.  Feeding -21 into REQ.SNR would ask VOACAP for a
circuit ~34 dB worse than intended and *everything* would look reliable.

Assumptions (documented deliberately):

* -21 dB is the *a-priori single-signal decode threshold* of the standard
  FT8 decoder.  Deep decodes, AP (a-priori) decoding, and multi-pass
  subtraction can pull decodes from a few dB lower, but those are
  opportunistic and should not be the planning baseline.
* ``snr_margin_db`` (default 3 dB) is a real-world margin over the bare
  threshold: QSB/fading between 15 s slots, multi-signal QRM in the 3 kHz
  passband, and imperfect audio chains all eat into the budget.  The margin
  is added to the REQ.SNR card, so the default card value is 13 + 3 = 16.
* VOACAP then reports REL as the probability that the *median-hour* SNR
  exceeds this requirement, which is exactly the "can I work someone there"
  question the Band Advisor asks.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

#: WSJT-X SNR reporting reference bandwidth (Hz).
FT8_REFERENCE_BANDWIDTH_HZ = 2500.0

#: A-priori single-signal FT8 decode threshold in the 2500 Hz reference.
FT8_DECODE_THRESHOLD_DB_2500 = -21.0


def snr_dbhz_from_2500(snr_db_2500: float, bandwidth_hz: float = FT8_REFERENCE_BANDWIDTH_HZ) -> float:
    """Convert an SNR referenced to ``bandwidth_hz`` into dB-Hz (1 Hz reference)."""
    return snr_db_2500 + 10.0 * math.log10(bandwidth_hz)


def snr_2500_from_dbhz(snr_dbhz: float, bandwidth_hz: float = FT8_REFERENCE_BANDWIDTH_HZ) -> float:
    """Convert a dB-Hz SNR back into the 2500 Hz WSJT-X reporting reference."""
    return snr_dbhz - 10.0 * math.log10(bandwidth_hz)


@dataclass(frozen=True)
class FT8SnrProfile:
    """Configurable FT8 SNR requirement for the VOACAP SYSTEM card.

    Attributes:
        required_snr_dbhz: bare decode requirement in dB-Hz.  Default 13,
            i.e. round(-21 + 10*log10(2500)).
        snr_margin_db: planning margin added on top (fading, QRM, audio
            chain).  Default 3.
    """

    required_snr_dbhz: float = 13.0
    snr_margin_db: float = 3.0

    @property
    def req_snr_card_db(self) -> float:
        """Value written to the VOACAP REQ.SNR (RSN) field: threshold + margin."""
        return self.required_snr_dbhz + self.snr_margin_db

    @classmethod
    def from_2500hz_threshold(
        cls,
        threshold_db_2500: float = FT8_DECODE_THRESHOLD_DB_2500,
        snr_margin_db: float = 3.0,
    ) -> "FT8SnrProfile":
        """Build a profile from a 2500 Hz-referenced threshold (e.g. -21 dB)."""
        return cls(
            required_snr_dbhz=round(snr_dbhz_from_2500(threshold_db_2500)),
            snr_margin_db=snr_margin_db,
        )


DEFAULT_FT8_PROFILE = FT8SnrProfile()
