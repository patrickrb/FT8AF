"""VOACAP propagation engine wrapper.

The VOACAP engine is invoked as an EXTERNAL tool: ``voacapl``
(https://github.com/jawatson/voacapl, GPL), James Watson's Linux port of
NTIA/ITS VOACAP.  This package only builds input decks, runs the binary via
subprocess, and parses its text output — no VOACAP code is copied or linked.
"""
