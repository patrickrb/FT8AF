import logging

from app.logging_config import QUIET_URL_LOGGERS, configure_logging


class TestConfigureLogging:
    def test_httpx_request_urls_not_logged_at_info(self):
        # HTTPX logs "HTTP Request: GET <full url>" at INFO; the PSK Reporter
        # URL carries appcontact (email) and senderCallsign.
        for name in QUIET_URL_LOGGERS:
            logging.getLogger(name).setLevel(logging.NOTSET)
        configure_logging(logging.INFO)
        assert not logging.getLogger("httpx").isEnabledFor(logging.INFO)
        assert not logging.getLogger("httpcore").isEnabledFor(logging.INFO)
        assert logging.getLogger("httpx").isEnabledFor(logging.WARNING)

    def test_quieting_applies_even_when_already_configured(self):
        configure_logging(logging.INFO)
        logging.getLogger("httpx").setLevel(logging.NOTSET)
        configure_logging(logging.INFO)  # idempotent root setup, still caps httpx
        assert logging.getLogger("httpx").level == logging.WARNING
