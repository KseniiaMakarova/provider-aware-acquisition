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


class AcquisitionValueRequest(CamelModel):
    observed_features: dict[str, FeatureValue] = Field(
        description="Values of the currently observed features, keyed by feature name"
    )
    observed_mask: list[str] = Field(
        description="Names of the observed features; must match the keys of observedFeatures exactly"
    )
    candidate_providers: list[str] = Field(
        description="Providers to estimate; configured providers that are not acquired yet"
    )
    sample_size: Annotated[int, Field(strict=True, ge=1, le=1000)] = Field(
        default=100, description="Number of nearest reference records (K)"
    )


class ProviderValueResponse(CamelModel):
    provider_id: str
    expected_information_gain_bits: float = Field(description="EIG = H(mean p_k) - mean H(p_k), base-2 logarithms")
    expected_probability: float = Field(description="Mean of the sampled predictions")
    expected_absolute_change: float = Field(description="Mean absolute change from the current probability")


class AcquisitionValueResponse(CamelModel):
    method: str
    sample_size: int
    reference_version: str
    model_version: str
    feature_config_version: str
    current_probability: float
    estimates: list[ProviderValueResponse]


class HealthResponse(CamelModel):
    status: str
    model_version: str
    feature_config_version: str
    value_reference_version: str | None = None


class ErrorResponse(CamelModel):
    error: str
    message: str
    details: list[dict] | None = None
