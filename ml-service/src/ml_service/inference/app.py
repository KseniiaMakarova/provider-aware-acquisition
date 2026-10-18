"""FastAPI application for partial-information prediction.

Start with:  python -m ml_service.inference [--host HOST] [--port PORT]
"""

from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from ml_service.artifacts import load_deployed_model
from ml_service.config import FeatureConfig, InvalidObservationError, load_feature_config
from ml_service.inference.predictor import Predictor
from ml_service.inference.schemas import ErrorResponse, HealthResponse, PredictRequest, PredictResponse
from ml_service.paths import artifacts_dir as default_artifacts_dir


def error_response(status: int, error: str, message: str, details: list[dict] | None = None) -> JSONResponse:
    body = ErrorResponse(error=error, message=message, details=details)
    return JSONResponse(status_code=status, content=body.model_dump(by_alias=True, exclude_none=True))


def create_app(artifacts: Path | None = None, config: FeatureConfig | None = None) -> FastAPI:
    """Build the app; fails immediately if the deployed model is missing or does not match the config."""
    config = config or load_feature_config()
    predictor = Predictor(load_deployed_model(artifacts or default_artifacts_dir(), config), config)

    app = FastAPI(title="Provider-aware acquisition ML service", version="1")

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_: Request, exc: RequestValidationError) -> JSONResponse:
        details = [{"location": ".".join(str(p) for p in e["loc"]), "message": e["msg"]} for e in exc.errors()]
        return error_response(422, "INVALID_REQUEST", "Request body does not match the schema", details)

    @app.exception_handler(InvalidObservationError)
    async def invalid_observation(_: Request, exc: InvalidObservationError) -> JSONResponse:
        return error_response(422, "INVALID_OBSERVATION", str(exc))

    @app.get("/health", response_model=HealthResponse)
    def health() -> HealthResponse:
        return HealthResponse(
            status="UP", model_version=predictor.model_version, feature_config_version=config.version
        )

    @app.post(
        "/internal/v1/predict",
        response_model=PredictResponse,
        responses={422: {"model": ErrorResponse}},
    )
    def predict(request: PredictRequest) -> PredictResponse:
        probability = predictor.predict(request.observed_features, request.observed_mask)
        return PredictResponse(
            probability=probability,
            model_version=predictor.model_version,
            feature_config_version=config.version,
        )

    return app
