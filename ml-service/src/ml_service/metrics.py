"""Prediction-quality metrics used for model selection and, later, experiment evaluation."""

from __future__ import annotations

import numpy as np
from sklearn.metrics import average_precision_score, brier_score_loss, log_loss, roc_auc_score

METRICS = ("rocAuc", "prAuc", "brier", "logLoss")
HIGHER_IS_BETTER = {"rocAuc": True, "prAuc": True, "brier": False, "logLoss": False}


def binary_metrics(y_true: np.ndarray, probability: np.ndarray) -> dict[str, float]:
    """ROC-AUC and PR-AUC (ranking) plus Brier score and log loss (probability quality)."""
    return {
        "rocAuc": float(roc_auc_score(y_true, probability)),
        "prAuc": float(average_precision_score(y_true, probability)),
        "brier": float(brier_score_loss(y_true, probability)),
        "logLoss": float(log_loss(y_true, probability, labels=[0, 1])),
    }
