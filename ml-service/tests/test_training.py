import json
import shutil
import subprocess
import sys

import joblib
import numpy as np
import pandas as pd
import pytest

from conftest import synthetic_records
from ml_service.data.prepare import SOURCE_FILE, make_split_manifest, write_csv, write_split_files
from ml_service.encoding import encode_state, input_columns
from ml_service.paths import data_dir as real_data_dir
from ml_service.training import train as train_module
from ml_service.training.train import DEPLOYED_FILE, REPORT_FILE, load_split, run, select_model

FAST = {"max_iter": 30}


@pytest.fixture(scope="module")
def data_dir(tmp_path_factory, config):
    """A data directory with the real layout, filled with synthetic records."""
    directory = tmp_path_factory.mktemp("data")
    shutil.copy(real_data_dir() / SOURCE_FILE, directory / SOURCE_FILE)
    records = synthetic_records(config, 3000, seed=1)
    manifest = make_split_manifest(records, config, seed=42, validation_fraction=0.15, test_fraction=0.15)
    write_csv(manifest, directory / "split-manifest.csv")
    write_split_files(records, manifest, config, directory)
    return directory


@pytest.fixture(scope="module")
def trained(data_dir, config, tmp_path_factory):
    out = tmp_path_factory.mktemp("artifacts")
    return out, run(data_dir, out, config, hgb_overrides=FAST)


# --- Report contents ---


def test_report_covers_all_candidates_and_states(trained):
    _, report = trained
    candidates = report["candidates"]
    assert set(candidates) == {"full-lr", "full-hgb", "mask-lr", "mask-hgb"}
    for name in ("mask-lr", "mask-hgb"):
        assert len(candidates[name]["states"]) == 8
        assert set(candidates[name]["mean"]) == {"rocAuc", "prAuc", "brier", "logLoss"}
    for name in ("full-lr", "full-hgb"):
        assert list(candidates[name]["states"]) == ["initial+REPAYMENT_HISTORY+BILLING_HISTORY+PAYMENT_HISTORY"]


def test_metrics_are_in_valid_ranges(trained):
    _, report = trained
    for result in report["candidates"].values():
        for m in result["states"].values():
            assert 0 <= m["rocAuc"] <= 1 and 0 <= m["prAuc"] <= 1 and 0 <= m["brier"] <= 1
            assert m["logLoss"] > 0


def test_selection_uses_mean_roc_auc_of_mask_aware_candidates(trained):
    _, report = trained
    mask = {n: r["mean"]["rocAuc"] for n, r in report["candidates"].items() if r["kind"] == "mask_aware"}
    assert report["selection"]["selected"] == max(mask, key=mask.get)


# --- Data access ---


def test_training_never_reads_the_test_split(data_dir, config, tmp_path, monkeypatch):
    read_paths = []
    original = pd.read_csv

    def recording_read_csv(path, *args, **kwargs):
        read_paths.append(str(path))
        return original(path, *args, **kwargs)

    monkeypatch.setattr(pd, "read_csv", recording_read_csv)
    run(data_dir, tmp_path, config, hgb_overrides=FAST)
    assert read_paths, "expected training to read split files"
    assert not [p for p in read_paths if p.endswith("test.csv")]


def test_test_split_cannot_be_loaded_for_training(data_dir):
    with pytest.raises(ValueError, match="reserved"):
        load_split(data_dir, "test")


# --- Reproducibility and artifacts ---


def test_training_is_reproducible(data_dir, config, trained, tmp_path):
    _, first = trained
    second = run(data_dir, tmp_path, config, hgb_overrides=FAST)
    assert second["candidates"] == first["candidates"]
    assert second["selection"]["modelVersion"] == first["selection"]["modelVersion"]


def test_deployed_pointer_and_metadata(trained, config):
    out, report = trained
    deployed = json.loads((out / DEPLOYED_FILE).read_text())
    assert deployed["modelVersion"] == report["selection"]["modelVersion"]
    metadata = json.loads((out / deployed["path"] / "metadata.json").read_text())
    assert metadata["featureConfigVersion"] == config.version
    assert metadata["inputColumns"] == list(input_columns(config))
    assert metadata["trainingSplits"] == ["train", "validation"]
    assert len(metadata["validation"]["states"]) == 8
    assert (out / REPORT_FILE).exists()


def test_saved_model_reproduces_reported_validation_metrics(trained, data_dir, config):
    out, report = trained
    deployed = json.loads((out / DEPLOYED_FILE).read_text())
    model = joblib.load(out / deployed["path"] / "model.joblib")
    validation = load_split(data_dir, "validation")
    probability = model.predict_proba(encode_state(validation, config, []))[:, 1]
    from ml_service.metrics import binary_metrics

    expected = report["candidates"][report["selection"]["selected"]]["states"]["initial"]
    assert binary_metrics(validation[config.target_column].to_numpy(), probability) == pytest.approx(expected)


def test_loading_a_model_does_not_import_training_or_data_code(trained):
    out, _ = trained
    deployed = json.loads((out / DEPLOYED_FILE).read_text())
    script = (
        "import sys, joblib; joblib.load(sys.argv[1]); "
        "print(sorted(m for m in sys.modules if m.startswith(('ml_service.training', 'ml_service.data'))))"
    )
    result = subprocess.run(
        [sys.executable, "-c", script, str(out / deployed["path"] / "model.joblib")],
        capture_output=True, text=True, check=True,
    )
    assert result.stdout.strip() == "[]"


# --- Review flags ---


def fake_results(mask_initial, mask_full, other_initial, other_full, full_info):
    states = lambda initial, full: {  # noqa: E731
        "initial": {"rocAuc": initial},
        "initial+REPAYMENT_HISTORY+BILLING_HISTORY+PAYMENT_HISTORY": {"rocAuc": full},
    }
    return {
        "mask-a": {"kind": "mask_aware", "states": states(mask_initial, mask_full), "mean": {"rocAuc": 0.80}},
        "mask-b": {"kind": "mask_aware", "states": states(other_initial, other_full), "mean": {"rocAuc": 0.70}},
        "full-x": {"kind": "full_information",
                   "states": {"initial+REPAYMENT_HISTORY+BILLING_HISTORY+PAYMENT_HISTORY": {"rocAuc": full_info}}},
    }


def test_no_flags_when_selected_model_is_competitive(config):
    selection = select_model(fake_results(0.75, 0.78, 0.745, 0.775, 0.785), config)
    assert selection["selected"] == "mask-a"
    assert not selection["reviewRequired"]


@pytest.mark.parametrize(
    ("results", "fragment"),
    [
        (fake_results(0.70, 0.78, 0.75, 0.78, 0.78), "state 'initial'"),
        (fake_results(0.75, 0.74, 0.75, 0.78, 0.78), "state 'initial+REPAYMENT"),
        (fake_results(0.75, 0.76, 0.75, 0.76, 0.79), "full-information baseline"),
    ],
)
def test_substantially_worse_important_state_is_flagged(config, results, fragment):
    selection = select_model(results, config)
    assert selection["reviewRequired"]
    assert any(fragment in flag for flag in selection["flags"])


def test_flagged_selection_is_not_deployed_without_acceptance(data_dir, config, tmp_path, monkeypatch):
    original = select_model

    def flagged(results, cfg):
        return {**original(results, cfg), "flags": ["forced for test"], "reviewRequired": True}

    monkeypatch.setattr(train_module, "select_model", flagged)
    run(data_dir, tmp_path, config, hgb_overrides=FAST)
    assert not (tmp_path / DEPLOYED_FILE).exists()

    run(data_dir, tmp_path, config, hgb_overrides=FAST, accept_flagged=True)
    assert json.loads((tmp_path / DEPLOYED_FILE).read_text())["acceptedWithFlags"] is True
