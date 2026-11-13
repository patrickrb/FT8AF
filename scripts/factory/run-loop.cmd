@echo off
rem The factory session itself: start-factory.ps1 opens this in a
rem Windows Terminal tab. FT8AF_FACTORY=1 turns on scripts\factory\guard.py,
rem which blocks destructive git / gh commands for this session only.
set FT8AF_FACTORY=1
"%USERPROFILE%\.local\bin\claude.exe" --dangerously-skip-permissions "/loop /ft8af-factory"
