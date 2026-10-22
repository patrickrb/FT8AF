import subprocess
from pathlib import Path

import pytest

from app.voacap.runner import VoacapError, VoacapRunner


def make_run_fn(settings, output_text="OUTPUT", rc=0, write_output=True):
    """Fake subprocess.run that emulates voacapl's file behavior."""
    calls = []

    def run_fn(argv, capture_output, text, timeout):
        calls.append({"argv": argv, "timeout": timeout})
        itshfbc = Path(argv[1])
        if write_output:
            (itshfbc / "run" / argv[3]).write_text(output_text)
        return subprocess.CompletedProcess(argv, rc, stdout="", stderr="")

    run_fn.calls = calls
    return run_fn


class TestRunner:
    def test_success_returns_output(self, settings):
        run_fn = make_run_fn(settings, output_text="HELLO VOACAP")
        runner = VoacapRunner(settings, run_fn=run_fn)
        assert runner.run("DECK") == "HELLO VOACAP"

    def test_invocation_uses_short_relative_names(self, settings):
        """Fortran fixed-length buffers: names must be short and relative."""
        run_fn = make_run_fn(settings)
        VoacapRunner(settings, run_fn=run_fn).run("DECK")
        argv = run_fn.calls[0]["argv"]
        assert argv[0] == settings.voacapl_path
        assert argv[1] == settings.itshfbc_path
        in_name, out_name = argv[2], argv[3]
        assert "/" not in in_name and "/" not in out_name
        assert in_name.startswith("ba") and in_name.endswith(".dat")
        assert out_name.startswith("ba") and out_name.endswith(".out")
        assert len(in_name) <= 30 and len(out_name) <= 30

    def test_deck_written_into_itshfbc_run_dir(self, settings):
        seen = {}

        def run_fn(argv, capture_output, text, timeout):
            deck_path = Path(argv[1]) / "run" / argv[2]
            seen["deck"] = deck_path.read_text()
            (Path(argv[1]) / "run" / argv[3]).write_text("OUT")
            return subprocess.CompletedProcess(argv, 0, stdout="", stderr="")

        VoacapRunner(settings, run_fn=run_fn).run("MY DECK\n")
        assert seen["deck"] == "MY DECK\n"

    def test_run_files_cleaned_up(self, settings):
        run_fn = make_run_fn(settings)
        VoacapRunner(settings, run_fn=run_fn).run("DECK")
        run_dir = Path(settings.itshfbc_path) / "run"
        assert list(run_dir.iterdir()) == []

    def test_nonzero_rc_raises(self, settings):
        run_fn = make_run_fn(settings, rc=1)
        with pytest.raises(VoacapError, match="rc=1"):
            VoacapRunner(settings, run_fn=run_fn).run("DECK")

    def test_missing_output_raises(self, settings):
        run_fn = make_run_fn(settings, write_output=False)
        with pytest.raises(VoacapError, match="no output"):
            VoacapRunner(settings, run_fn=run_fn).run("DECK")

    def test_timeout_raises(self, settings):
        def run_fn(argv, capture_output, text, timeout):
            raise subprocess.TimeoutExpired(argv, timeout)

        with pytest.raises(VoacapError, match="timed out"):
            VoacapRunner(settings, run_fn=run_fn).run("DECK")

    def test_missing_binary_raises_voacap_error(self, settings):
        def run_fn(argv, capture_output, text, timeout):
            raise FileNotFoundError(argv[0])

        with pytest.raises(VoacapError, match="could not be executed"):
            VoacapRunner(settings, run_fn=run_fn).run("DECK")

    def test_timeout_from_settings(self, settings):
        run_fn = make_run_fn(settings)
        VoacapRunner(settings, run_fn=run_fn).run("DECK")
        assert run_fn.calls[0]["timeout"] == settings.voacap_timeout_s
