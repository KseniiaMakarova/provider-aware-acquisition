"""FastAPI application for partial-information prediction and provider value estimation.

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
from ml_service.inference.schemas import (
    AcquisitionValueRequest,
    AcquisitionValueResponse,
    ErrorResponse,
    HealthResponse,
    PredictRequest,
    PredictResponse,
    ProviderValueResponse,
)
from ml_service.inference.value import METHOD, ValueEstimator
from ml_service.paths import artifacts_dir as default_artifacts_dir
from ml_service.value_reference import load_value_reference


def error_response(status: int, error: str, message: str, details: list[dict] | None = None) -> JSONResponse:
    body = ErrorResponse(error=error, message=message, details=details)
    return JSONResponse(status_code=status, content=body.model_dump(by_alias=True, exclude_none=True))


def create_app(artifacts: Path | None = None, config: FeatureConfig | None = None) -> FastAPI:
    """Build the app; fails immediately if the deployed model or the value reference does not match the config.

    Without a value reference the service still predicts, and value estimation answers 503.
    """
    config = config or load_feature_config()
    artifacts = artifacts or default_artifacts_dir()
    predictor = Predictor(load_deployed_model(artifacts, config), config)
    reference = load_value_reference(artifacts, config)
    estimator = ValueEstimator(predictor, reference) if reference else None

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
            status="UP",
            model_version=predictor.model_version,
            feature_config_version=config.version,
            value_reference_version=estimator.reference_version if estimator else None,
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

    @app.post(
        "/internal/v1/acquisition-value",
        response_model=AcquisitionValueResponse,
        responses={422: {"model": ErrorResponse}, 503: {"model": ErrorResponse}},
    )
    def acquisition_value(request: AcquisitionValueRequest):
        if estimator is None:
            return error_response(
                503,
                "VALUE_REFERENCE_UNAVAILABLE",
                "No value reference is loaded; run python -m ml_service.training.reference",
            )
        result = estimator.estimate(
            request.observed_features, request.observed_mask, request.candidate_providers, request.sample_size
        )
        return AcquisitionValueResponse(
            method=METHOD,
            sample_size=result.sample_size,
            reference_version=estimator.reference_version,
            model_version=predictor.model_version,
            feature_config_version=config.version,
            current_probability=result.current_probability,
            estimates=[
                ProviderValueResponse(
                    provider_id=v.provider_id,
                    expected_information_gain_bits=v.expected_information_gain_bits,
                    expected_probability=v.expected_probability,
                    expected_absolute_change=v.expected_absolute_change,
                )
                for v in result.values
            ],
        )

    return app
