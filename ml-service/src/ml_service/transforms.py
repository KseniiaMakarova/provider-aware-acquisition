"""Feature transforms referenced by saved model pipelines.

Kept outside the training package so that loading a model artifact for inference
does not import offline training code.
"""

from __future__ import annotations

import numpy as np


def signed_log1p(x):
    """sign(x) * log(1 + |x|): compresses skewed monetary amounts, keeps sign, zero and NaN."""
    return np.sign(x) * np.log1p(np.abs(x))
