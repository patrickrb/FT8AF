"""ft8af-band-advisor: server-side Band Advisor scaffold for FT8AF.

License note
------------
Propagation predictions are produced by invoking ``voacapl`` (James Watson's
GPL-licensed Linux port of NTIA/ITS VOACAP, https://github.com/jawatson/voacapl)
as an EXTERNAL binary via subprocess.  No VOACAP/voacapl source code is copied,
vendored, or linked into this service; the binary is built and installed
separately (see Dockerfile).
"""

__version__ = "0.1.0"
