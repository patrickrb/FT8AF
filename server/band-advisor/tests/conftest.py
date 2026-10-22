"""Shared test fixtures.  NO test in this suite touches the network or runs
a real voacapl binary — everything external is faked or fixture-driven."""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pytest

from app.clock import FixedClock
from app.config import Settings

FIXTURES = Path(__file__).parent / "fixtures"

#: The canonical "now" used across tests: 2026-09-27 12:00:00Z (hour 12,
#: matching the first hour block in tests/fixtures/voacapx.out).
TEST_NOW = datetime(2026, 9, 27, 12, 0, 0, tzinfo=timezone.utc)


@pytest.fixture
def fixed_clock() -> FixedClock:
    return FixedClock(TEST_NOW)


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        voacapl_path=str(tmp_path / "bin" / "voacapl"),
        itshfbc_path=str(tmp_path / "itshfbc"),
        baseline_path=tmp_path / "baseline.json",
        feature_config_path=tmp_path / "feature-config.json",
        psk_contact_email="test@example.invalid",
    )


@pytest.fixture
def voacap_fixture_text() -> str:
    return (FIXTURES / "voacapx.out").read_text()
