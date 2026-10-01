"""Run the external ``voacapl`` binary on a deck and return its text output.

Fortran filename gotcha (IMPORTANT, do not "fix" this)
------------------------------------------------------
voacapl inherits VOACAP's fixed-length Fortran filename buffers.  Run files
must live in the classic itshfbc layout — ``<itshfbc>/run/`` — and the
*names passed on the command line are relative to that directory* and must
stay short (roughly <=30 characters; long absolute paths inside the deck or
on the command line overflow the buffers and fail in bizarre ways, e.g.
truncated paths or silent empty output).  That is why this runner:

* writes decks only into ``<itshfbc>/run/``;
* uses short random names like ``ba3f9a1c.dat`` / ``ba3f9a1c.out``;
* invokes ``voacapl <itshfbc> <name>.dat <name>.out`` with bare names,
  never absolute paths.
"""

from __future__ import annotations

import logging
import secrets
import subprocess
from pathlib import Path
from typing import Callable

from ..config import Settings

log = logging.getLogger(__name__)


class VoacapError(RuntimeError):
    """voacapl failed, timed out, or produced no output."""


RunFn = Callable[..., "subprocess.CompletedProcess[str]"]


class VoacapRunner:
    """Writes a deck under ``<itshfbc>/run/`` and executes voacapl on it.

    ``run_fn`` is injectable (defaults to :func:`subprocess.run`) so tests
    never execute a real binary.
    """

    def __init__(self, settings: Settings, run_fn: RunFn | None = None) -> None:
        self._settings = settings
        self._run_fn: RunFn = run_fn or subprocess.run

    def run(self, deck_text: str) -> str:
        """Execute one prediction; returns the raw voacapl output text."""
        run_dir = Path(self._settings.itshfbc_path) / "run"
        run_dir.mkdir(parents=True, exist_ok=True)

        name = f"ba{secrets.token_hex(3)}"  # e.g. ba3f9a1c — 8 chars, Fortran-safe
        in_name, out_name = f"{name}.dat", f"{name}.out"
        in_path, out_path = run_dir / in_name, run_dir / out_name

        in_path.write_text(deck_text)
        try:
            try:
                proc = self._run_fn(
                    [
                        self._settings.voacapl_path,
                        self._settings.itshfbc_path,
                        in_name,
                        out_name,
                    ],
                    capture_output=True,
                    text=True,
                    timeout=self._settings.voacap_timeout_s,
                )
            except subprocess.TimeoutExpired as exc:
                raise VoacapError(
                    f"voacapl timed out after {self._settings.voacap_timeout_s}s"
                ) from exc
            except OSError as exc:
                raise VoacapError(f"voacapl could not be executed: {exc}") from exc

            if proc.returncode != 0:
                log.warning(
                    "voacapl exited rc=%d stderr=%.200s", proc.returncode, proc.stderr
                )
                raise VoacapError(f"voacapl exited with rc={proc.returncode}")
            if not out_path.exists():
                raise VoacapError("voacapl produced no output file")
            output = out_path.read_text(errors="replace")
            if not output.strip():
                raise VoacapError("voacapl output file is empty")
            return output
        finally:
            for p in (in_path, out_path):
                try:
                    p.unlink(missing_ok=True)
                except OSError:  # cleanup is best-effort
                    pass
