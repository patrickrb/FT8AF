"""ASGI entry point: ``uvicorn app.main:app``."""

from .api import create_app

app = create_app()
