"""Request and response bodies of the internal prediction API."""

from __future__ import annotations

from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

# Numbers only: no strings, booleans, null, NaN or infinity.
FeatureValue = Annotated[float, Field(strict=True, allow_inf_nan=False)]


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class PredictRequest(CamelModel):
    observed_features: dict[str, FeatureValue] = Field(
        description="Values of the currently observed features, keyed by feature name"
    )
    observed_mask: list[str] = Field(
        description="Names of the observed features; must match the keys of observedFeatures exactly"
    )


class PredictResponse(CamelModel):
    probability: float = Field(description="Predicted probability of default")
    model_version: str
    feature_config_version: str


class HealthResponse(CamelModel):
    status: str
    model_version: str
    feature_config_version: str


class ErrorResponse(CamelModel):
    error: str
    message: str
    details: list[dict] | None = None
