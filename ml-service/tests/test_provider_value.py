import json

import pytest

from ml_service.analysis.provider_value import binary_entropy, record_log_loss, run, shapley
from ml_service.training.train import DEPLOYED_FILE


def test_shapley_of_additive_function_equals_each_contribution():
    weights = {"A": 0.3, "B": 0.1, "C": -0.05}
    states = [frozenset(s) for s in ([], ["A"], ["B"], ["C"], ["A", "B"], ["A", "C"], ["B", "C"], ["A", "B", "C"])]
    values = {s: sum(weights[p] for p in s) for s in states}
    assert shapley(values, ("A", "B", "C")) == pytest.approx(weights)


def test_shapley_splits_an_interaction_equally():
    # Value appears only when A and B are both present.
    states = [frozenset(s) for s in ([], ["A"], ["B"], ["A", "B"])]
    values = {s: 1.0 if s == frozenset({"A", "B"}) else 0.0 for s in states}
    assert shapley(values, ("A", "B")) == pytest.approx({"A": 0.5, "B": 0.5})


def test_record_level_helpers():
    assert record_log_loss([1, 0], [0.5, 0.5]) == pytest.approx([0.6931, 0.6931], abs=1e-4)
    assert binary_entropy([0.5])[0] == pytest.approx(1.0)
    assert binary_entropy([0.0])[0] == pytest.approx(0.0, abs=1e-9)


@pytest.fixture(scope="module")
def analysis(trained, data_dir, config):
    artifacts, _ = trained
    return run(data_dir, artifacts, config)


def test_analysis_uses_the_deployed_model_and_validation_split(analysis, trained, data_dir):
    artifacts, report = trained
    version, result = analysis
    assert version == json.loads((artifacts / DEPLOYED_FILE).read_text())["modelVersion"]
    assert result["records"] == 450  # 15% of the 3,000 synthetic records
    assert (artifacts / "analysis" / f"provider-value-{version}.json").exists()


def test_every_missing_provider_is_evaluated_from_every_state(analysis, config):
    _, result = analysis
    n = len(config.providers)
    assert len(result["transitions"]) == n * 2 ** (n - 1)
    assert len(result["bestProviderFromState"]) == 2**n - 1


def test_shapley_values_add_up_to_the_full_gain(analysis, config):
    _, result = analysis
    full = "+".join(["initial", *config.provider_ids])
    gain = result["states"][full]["rocAuc"] - result["states"]["initial"]["rocAuc"]
    assert sum(result["shapley"]["rocAuc"].values()) == pytest.approx(gain)


def test_shares_are_proportions(analysis):
    _, result = analysis
    for t in result["transitions"]:
        for key, value in t.items():
            if key.startswith("share"):
                assert 0 <= value <= 1
    assert sum(result["bestSingleProviderPerRecordFromInitial"].values()) == pytest.approx(1)


def test_model_from_another_feature_configuration_is_refused(trained, data_dir, config):
    artifacts, _ = trained
    other = config.__class__(**{**config.__dict__, "version": "fg-other"})
    with pytest.raises(ValueError, match="PAA_FEATURE_CONFIG"):
        run(data_dir, artifacts, other)
