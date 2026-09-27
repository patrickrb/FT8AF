"""FastAPI application: versioned, deterministic JSON endpoints.

Error contract (every non-2xx body has this exact shape)::

    {"error": {"code": "<MACHINE_CODE>", "message": "<human text>"}}

Degradation contract: `/v1/recommendation` and `/v1/conditions` return 200
with ``sources.*Available: false`` when an upstream is down — an upstream
outage is NEVER a 500.
"""

from __future__ import annotations

import json
import logging
import time
from datetime import timedelta

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import __version__
from .clock import Clock, SystemClock, isoformat_z
from .config import Settings
from .geo import GridError, is_valid_callsign, normalize_grid4
from .logging_config import configure_logging
from .pskreporter.service import PskService
from .recommendation import RecommendationRequest, RecommendationService
from .scoring import Goal
from .voacap.input_builder import AntennaClass, NoiseClass, PowerClass
from .voacap.regions import TARGET_REGION_NAMES
from .voacap.service import VoacapService

log = logging.getLogger(__name__)

SUPPORTED_MODES = ("FT8",)


class ApiError(Exception):
    """Client error carrying the deterministic error body."""

    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message


def _error_response(status_code: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"error": {"code": code, "message": message}},
    )


def _parse_enum(kind, raw: str | None, default, param: str):
    if raw is None:
        return default, False
    try:
        return kind(raw.upper()), True
    except ValueError:
        valid = ", ".join(e.value for e in kind)
        raise ApiError(400, "INVALID_PARAMETER", f"{param} must be one of: {valid}")


def create_app(
    settings: Settings | None = None,
    clock: Clock | None = None,
    voacap_service: VoacapService | None = None,
    psk_service: PskService | None = None,
    recommendation_service: RecommendationService | None = None,
) -> FastAPI:
    """App factory.  All collaborators are injectable for tests."""
    configure_logging()
    settings = settings or Settings.from_env()
    clock = clock or SystemClock()
    voacap = voacap_service or VoacapService(settings, clock)
    psk = psk_service or PskService(settings, clock)
    recommender = recommendation_service or RecommendationService(
        settings, clock, voacap, psk
    )

    app = FastAPI(title="ft8af-band-advisor", version=__version__, docs_url=None)
    app.state.settings = settings
    app.state.clock = clock

    # ------------------------------------------------------------- middleware

    @app.middleware("http")
    async def request_logging(request: Request, call_next):
        start = time.monotonic()
        response = await call_next(request)
        grid = request.query_params.get("grid", "")
        log.info(
            "request",
            extra={
                "method": request.method,
                "path": request.url.path,
                "status": response.status_code,
                "durationMs": round((time.monotonic() - start) * 1000, 1),
                # privacy: only ever a 4-char square, never finer
                "grid": grid.upper()[:4] if grid else None,
            },
        )
        return response

    # --------------------------------------------------------------- handlers

    @app.exception_handler(ApiError)
    async def handle_api_error(_req: Request, exc: ApiError):
        return _error_response(exc.status_code, exc.code, exc.message)

    @app.exception_handler(RequestValidationError)
    async def handle_validation(_req: Request, exc: RequestValidationError):
        return _error_response(400, "INVALID_PARAMETER", "request validation failed")

    @app.exception_handler(Exception)
    async def handle_unexpected(_req: Request, exc: Exception):
        log.error("unhandled error", exc_info=exc)
        return _error_response(
            502, "UPSTREAM_FAILURE", "an internal dependency failed"
        )

    # -------------------------------------------------------------- endpoints

    @app.get("/healthz")
    async def healthz():
        return {
            "status": "ok",
            "service": "ft8af-band-advisor",
            "version": __version__,
            "voacaplPresent": voacap.binary_available(),
            "voacaplPath": settings.voacapl_path,
        }

    @app.get("/v1/feature-config")
    async def feature_config():
        try:
            document = json.loads(settings.feature_config_path.read_text())
            flags = document["flags"]
        except (OSError, ValueError, KeyError) as exc:
            log.error("feature config unreadable: %s", exc)
            return _error_response(
                503, "FEATURE_CONFIG_UNAVAILABLE", "feature config not available"
            )
        now = clock.now()
        return {
            "version": int(document.get("version", 1)),
            "generatedAt": isoformat_z(now),
            "expiresAt": isoformat_z(
                now + timedelta(seconds=settings.feature_config_ttl_s)
            ),
            "flags": {
                "bandAdvisor": bool(flags.get("bandAdvisor", False)),
                "personalPskAnalytics": bool(flags.get("personalPskAnalytics", False)),
                "propagationAlerts": bool(flags.get("propagationAlerts", False)),
            },
        }

    @app.get("/v1/recommendation")
    async def recommendation(
        grid: str | None = None,
        goal: str | None = None,
        mode: str | None = None,
        power: str | None = None,
        antenna: str | None = None,
        noise: str | None = None,
        callsign: str | None = None,
        targetRegion: str | None = None,
    ):
        req = _build_request(
            grid=grid,
            goal=goal,
            mode=mode,
            power=power,
            antenna=antenna,
            noise=noise,
            callsign=callsign,
            target_region=targetRegion,
        )
        return recommender.recommend(req)

    @app.get("/v1/conditions")
    async def conditions(grid: str | None = None):
        if grid is None:
            raise ApiError(400, "MISSING_PARAMETER", "grid is required")
        try:
            normalized = normalize_grid4(grid)
        except GridError:
            raise ApiError(400, "INVALID_GRID", "grid must match [A-R]{2}[0-9]{2}")
        return recommender.conditions(normalized)

    return app


def _build_request(
    grid: str | None,
    goal: str | None,
    mode: str | None,
    power: str | None,
    antenna: str | None,
    noise: str | None,
    callsign: str | None,
    target_region: str | None,
) -> RecommendationRequest:
    """Validate raw query params into a :class:`RecommendationRequest`."""
    if grid is None:
        raise ApiError(400, "MISSING_PARAMETER", "grid is required")
    try:
        normalized_grid = normalize_grid4(grid)
    except GridError:
        raise ApiError(400, "INVALID_GRID", "grid must match [A-R]{2}[0-9]{2}")

    goal_value, _ = _parse_enum(Goal, goal, Goal.MAKE_CONTACT, "goal")

    mode_value = (mode or "FT8").upper()
    if mode_value not in SUPPORTED_MODES:
        raise ApiError(
            400, "INVALID_PARAMETER", f"mode must be one of: {', '.join(SUPPORTED_MODES)}"
        )

    power_value, power_given = _parse_enum(PowerClass, power, PowerClass.STANDARD, "power")
    antenna_value, antenna_given = _parse_enum(
        AntennaClass, antenna, AntennaClass.WIRE, "antenna"
    )
    noise_value, noise_given = _parse_enum(
        NoiseClass, noise, NoiseClass.RESIDENTIAL, "noise"
    )

    callsign_value: str | None = None
    if callsign is not None:
        callsign_value = callsign.strip().upper()
        if not is_valid_callsign(callsign_value):
            raise ApiError(400, "INVALID_CALLSIGN", "callsign is not a valid callsign")

    region_value: str | None = None
    if target_region is not None:
        region_value = target_region.strip().upper()
        if region_value not in TARGET_REGION_NAMES:
            raise ApiError(
                400,
                "INVALID_PARAMETER",
                f"targetRegion must be one of: {', '.join(TARGET_REGION_NAMES)}",
            )
    if goal_value is Goal.TARGET and region_value is None:
        raise ApiError(400, "MISSING_PARAMETER", "goal=TARGET requires targetRegion")

    provided = sum([power_given, antenna_given, noise_given, callsign_value is not None])
    completeness = (1 + provided) / 5.0  # grid always counts

    return RecommendationRequest(
        grid=normalized_grid,
        goal=goal_value,
        mode=mode_value,
        power=power_value,
        antenna=antenna_value,
        noise=noise_value,
        callsign=callsign_value,
        target_region=region_value,
        profile_completeness=completeness,
    )
