"""PSK Reporter client tests — all HTTP is served by httpx.MockTransport,
never the network."""

import httpx
import pytest

from app.pskreporter.client import (
    PskReporterBackoff,
    PskReporterClient,
    PskReporterError,
    parse_reception_reports,
)

VALID_XML = b"""<?xml version="1.0"?>
<receptionReports currentSeconds="1790000000">
  <receptionReport receiverCallsign="G0AAA" receiverLocator="JO01ab"
      senderCallsign="K1AF" senderLocator="EM28ax" frequency="14074500"
      mode="FT8" sNR="-11" flowStartSeconds="1789999500"/>
  <receptionReport receiverCallsign="VK2AAA" receiverLocator="QF56"
      senderCallsign="K1AF" senderLocator="EM28" frequency="7074100"
      mode="FT8" sNR="-20" flowStartSeconds="1789999600"/>
</receptionReports>
"""

BAD_GRID_XML = b"""<?xml version="1.0"?>
<receptionReports>
  <receptionReport receiverCallsign="G0AAA" receiverLocator="ZZ99"
      senderCallsign="K1AF" senderLocator="EM28" frequency="14074500"
      mode="FT8" sNR="-11"/>
</receptionReports>
"""

ABSURD_FREQ_XML = b"""<?xml version="1.0"?>
<receptionReports>
  <receptionReport receiverCallsign="G0AAA" receiverLocator="JO01"
      senderCallsign="K1AF" senderLocator="EM28" frequency="999999999999"
      mode="FT8" sNR="-11"/>
</receptionReports>
"""


class TestParseReceptionReports:
    def test_valid_reports(self):
        reports = parse_reception_reports(VALID_XML)
        assert len(reports) == 2
        assert reports[0].sender_callsign == "K1AF"
        assert reports[0].receiver_grid == "JO01ab"
        assert reports[0].frequency_hz == 14_074_500
        assert reports[0].snr_db == -11
        assert reports[0].band == "20m"
        assert reports[1].band == "40m"

    def test_malformed_xml_raises(self):
        with pytest.raises(PskReporterError):
            parse_reception_reports(b"<receptionReports><unclosed")

    def test_external_entities_rejected(self):
        evil = (
            b'<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>'
            b"<receptionReports>&x;</receptionReports>"
        )
        with pytest.raises(PskReporterError):
            parse_reception_reports(evil)

    def test_bad_grid_dropped(self):
        assert parse_reception_reports(BAD_GRID_XML) == []

    def test_absurd_frequency_dropped(self):
        assert parse_reception_reports(ABSURD_FREQ_XML) == []

    def test_missing_callsign_dropped(self):
        xml = (
            b'<receptionReports><receptionReport receiverLocator="JO01" '
            b'senderCallsign="K1AF" senderLocator="EM28" frequency="14074000"/>'
            b"</receptionReports>"
        )
        assert parse_reception_reports(xml) == []

    def test_out_of_range_snr_nulled(self):
        xml = (
            b'<receptionReports><receptionReport receiverCallsign="G0AAA" '
            b'receiverLocator="JO01" senderCallsign="K1AF" senderLocator="EM28" '
            b'frequency="14074000" sNR="999"/></receptionReports>'
        )
        reports = parse_reception_reports(xml)
        assert len(reports) == 1
        assert reports[0].snr_db is None


def make_client(settings, fixed_clock, handler):
    transport = httpx.MockTransport(handler)
    return PskReporterClient(
        settings,
        fixed_clock,
        http_client=httpx.Client(transport=transport),
    )


class TestClientBehavior:
    def test_fetch_sends_required_params(self, settings, fixed_clock):
        seen = {}

        def handler(request: httpx.Request) -> httpx.Response:
            seen.update(dict(request.url.params))
            return httpx.Response(200, content=VALID_XML)

        client = make_client(settings, fixed_clock, handler)
        result = client.fetch_reports(mode="FT8")
        assert len(result.reports) == 2
        assert result.fetched_at == fixed_clock.now()
        assert seen["flowStartSeconds"] == "-900"
        assert seen["mode"] == "FT8"
        assert seen["rronly"] == "1"
        assert seen["appcontact"] == settings.psk_contact_email

    def test_min_interval_enforced_per_parameter_set(self, settings, fixed_clock):
        client = make_client(
            settings, fixed_clock, lambda r: httpx.Response(200, content=VALID_XML)
        )
        client.fetch_reports(mode="FT8")
        with pytest.raises(PskReporterBackoff):
            client.fetch_reports(mode="FT8")
        # a *different* parameter set (personal query) is allowed immediately
        client.fetch_reports(mode="FT8", sender_callsign="K1AF")
        # and after 5 minutes the original is allowed again
        fixed_clock.advance(301)
        client.fetch_reports(mode="FT8")

    def test_429_starts_backoff(self, settings, fixed_clock):
        calls = {"n": 0}

        def handler(request):
            calls["n"] += 1
            return httpx.Response(429)

        client = make_client(settings, fixed_clock, handler)
        with pytest.raises(PskReporterError):
            client.fetch_reports(mode="FT8")
        with pytest.raises(PskReporterBackoff):
            client.fetch_reports(mode="FT8")
        assert calls["n"] == 1  # second attempt never hit the wire
        fixed_clock.advance(settings.psk_backoff_s + 1)
        with pytest.raises(PskReporterError):  # allowed to try again (still 429)
            client.fetch_reports(mode="FT8")
        assert calls["n"] == 2

    def test_503_starts_backoff(self, settings, fixed_clock):
        client = make_client(settings, fixed_clock, lambda r: httpx.Response(503))
        with pytest.raises(PskReporterError):
            client.fetch_reports(mode="FT8")
        with pytest.raises(PskReporterBackoff):
            client.fetch_reports(mode="FT8")

    def test_http_error_raises(self, settings, fixed_clock):
        def handler(request):
            raise httpx.ConnectError("boom")

        client = make_client(settings, fixed_clock, handler)
        with pytest.raises(PskReporterError):
            client.fetch_reports(mode="FT8")

    def test_malformed_body_raises(self, settings, fixed_clock):
        client = make_client(
            settings, fixed_clock, lambda r: httpx.Response(200, content=b"not xml")
        )
        with pytest.raises(PskReporterError):
            client.fetch_reports(mode="FT8")

    def test_sender_callsign_param(self, settings, fixed_clock):
        seen = {}

        def handler(request):
            seen.update(dict(request.url.params))
            return httpx.Response(200, content=VALID_XML)

        client = make_client(settings, fixed_clock, handler)
        client.fetch_reports(mode="FT8", sender_callsign="k1af")
        assert seen["senderCallsign"] == "K1AF"
