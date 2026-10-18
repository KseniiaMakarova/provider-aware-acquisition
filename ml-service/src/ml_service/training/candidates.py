"""Candidate classifiers with small, fixed settings (no hyperparameter search for Alpha).

Two model families, each trained in two variants:

- full information: the 19 model features, all observed;
- mask-aware: the encoded input of ``ml_service.encoding`` (hidden groups as NaN plus
  group indicators), trained on every training record in all eight observation states.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass, field

from sklearn.base import ClassifierMixin
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline, make_pipeline
from sklearn.preprocessing import FunctionTransformer, StandardScaler

from ml_service.encoding import INDICATOR_PREFIX
from ml_service.transforms import signed_log1p

SEED = 42

FULL_INFORMATION = "full_information"
MASK_AWARE = "mask_aware"

AMOUNT_PREFIXES = ("LIMIT_BAL", "BILL_AMT", "PAY_AMT")

LR_PARAMS = {"C": 1.0, "max_iter": 2000}
HGB_PARAMS = {
    "learning_rate": 0.05,
    "max_iter": 300,
    "max_leaf_nodes": 31,
    "min_samples_leaf": 50,
    "l2_regularization": 1.0,
    "early_stopping": False,
    "random_state": SEED,
}


def logistic_regression(columns: Sequence[str], **params) -> Pipeline:
    """Signed-log monetary amounts, mean-impute hidden values, standardize, then logistic regression.

    Repayment statuses (PAY_*) are treated as ordinal integers. Group indicators pass
    through unchanged so the model can adjust for which groups were imputed.
    """
    indicators = [c for c in columns if c.startswith(INDICATOR_PREFIX)]
    amounts = [c for c in columns if c.startswith(AMOUNT_PREFIXES)]
    statuses = [c for c in columns if c not in amounts and c not in indicators]
    preprocess = ColumnTransformer(
        [
            (
                "amounts",
                make_pipeline(
                    FunctionTransformer(signed_log1p, feature_names_out="one-to-one"),
                    SimpleImputer(strategy="mean"),
                    StandardScaler(),
                ),
                amounts,
            ),
            ("statuses", make_pipeline(SimpleImputer(strategy="mean"), StandardScaler()), statuses),
            ("indicators", "passthrough", indicators),
        ]
    )
    return Pipeline([("preprocess", preprocess), ("model", LogisticRegression(**{**LR_PARAMS, **params}))])


def gradient_boosting(columns: Sequence[str], **params) -> HistGradientBoostingClassifier:
    """Histogram gradient boosting; handles NaN for hidden groups natively."""
    return HistGradientBoostingClassifier(**{**HGB_PARAMS, **params})


@dataclass(frozen=True)
class Candidate:
    name: str
    kind: str
    family: str
    builder: Callable[..., ClassifierMixin]
    params: dict = field(default_factory=dict)

    def build(self, columns: Sequence[str]) -> ClassifierMixin:
        return self.builder(columns, **self.params)

    def effective_params(self) -> dict:
        defaults = LR_PARAMS if self.family == "logistic_regression" else HGB_PARAMS
        return {**defaults, **self.params}


def default_candidates(hgb_overrides: dict | None = None) -> list[Candidate]:
    hgb = dict(hgb_overrides or {})
    return [
        Candidate("full-lr", FULL_INFORMATION, "logistic_regression", logistic_regression),
        Candidate("full-hgb", FULL_INFORMATION, "gradient_boosting", gradient_boosting, hgb),
        Candidate("mask-lr", MASK_AWARE, "logistic_regression", logistic_regression),
        Candidate("mask-hgb", MASK_AWARE, "gradient_boosting", gradient_boosting, hgb),
    ]
