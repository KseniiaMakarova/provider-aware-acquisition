"""End-to-end smoke test of the running platform through its public API.

Runs one complete acquisition session and checks the main safeguards on the way:
only the initial features are visible at the start, each provider reveals exactly its
own group, unacquired features never appear in a response, a finished session cannot
be advanced, and records outside the served split are refused.

Uses only the Python standard library. Typical use from the repository root:

    python scripts/smoke_test.py --compose        # build and start all containers, test, stop them
    python scripts/smoke_test.py                  # test services that are already running

The --compose mode needs the generated files first: data/features/validation.csv
(python -m ml_service.data.prepare) and ml-service/artifacts (python -m ml_service.training.train).
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
FEATURE_CONFIG = REPO_ROOT / "config" / "feature-groups.json"
SPLIT_MANIFEST = REPO_ROOT / "data" / "split-manifest.csv"
REQUIRED_FOR_COMPOSE = [
    REPO_ROOT / "data" / "features" / "validation.csv",
    REPO_ROOT / "ml-service" / "artifacts" / "deployed.json",
]


class SmokeTestFailure(Exception):
    pass


class Results:
    def __init__(self) -> None:
        self.passed = 0

    def check(self, condition: bool, description: str, detail: str = "") -> None:
        if not condition:
            raise SmokeTestFailure(f"{description}{': ' + detail if detail else ''}")
        self.passed += 1
        print(f"  PASS  {description}")


def request(method: str, url: str, body: dict | None = None) -> tuple[int, dict | str]:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            status, text = response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        status, text = error.code, error.read().decode()
    try:
        return status, json.loads(text)
    except json.JSONDecodeError:
        return status, text


def wait_until_up(url: str, timeout_s: float) -> None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            if request("GET", url)[0] == 200:
                return
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(1)
    raise SmokeTestFailure(f"{url} did not respond within {timeout_s:.0f} s")


def first_record_of(split: str) -> str:
    with SPLIT_MANIFEST.open(newline="") as f:
        return next(row["ID"] for row in csv.DictReader(f) if row["split"] == split)


def compose(*args: str) -> None:
    command = ["docker", "compose", "--profile", "app", *args]
    print(f"$ {' '.join(command)}", flush=True)
    subprocess.run(command, cwd=REPO_ROOT, check=True)


def run_session(api: str, config: dict, results: Results) -> None:
    initial = config["initialFeatures"]
    groups = {p["id"]: p["features"] for p in config["providers"]}
    never_visible = config["excludedColumns"] + [config["dataset"]["targetColumn"]]

    def hidden_names(acquired: list[str]) -> list[str]:
        return [f for pid, feats in groups.items() if pid not in acquired for f in feats] + never_visible

    def assert_nothing_hidden(body: dict, acquired: list[str], where: str) -> None:
        text = json.dumps(body)
        leaked = [name for name in hidden_names(acquired) if f'"{name}"' in text]
        results.check(not leaked, f"{where}: no unacquired feature, label or demographic in the response", str(leaked))

    status, providers = request("GET", f"{api}/api/v1/providers")
    results.check(status == 200, "GET /providers returns 200", str(status))
    results.check([p["id"] for p in providers["providers"]] == list(groups),
                  "providers match config/feature-groups.json")

    record = first_record_of("validation")
    status, session = request("POST", f"{api}/api/v1/sessions", {"recordId": record, "policyId": "FIXED_ORDER"})
    results.check(status == 201, f"POST /sessions for validation record {record} returns 201", f"{status} {session}")
    session_id = session["sessionId"]
    results.check(session["observedMask"] == initial, "new session observes exactly the initial features")
    results.check(session["latestPrediction"]["sequenceNumber"] == 0, "initial prediction has sequence number 0")
    assert_nothing_hidden(session, [], "new session")
    print(f"        initial probability {session['latestPrediction']['probability']:.4f} "
          f"(model {session['modelVersion']})")

    acquired: list[str] = []
    for _ in range(len(groups) + 1):
        status, result = request("POST", f"{api}/api/v1/sessions/{session_id}/advance")
        results.check(status == 200, "POST /advance returns 200", f"{status} {result}")
        outcome = result["outcome"]
        state = result["session"]
        if outcome == "STOPPED":
            results.check(state["status"] == "COMPLETED", f"session completed ({state['stopReason']})")
            break
        step = result["step"]
        provider = step["providerId"]
        if outcome == "ACQUIRED":
            acquired.append(provider)
            results.check(list(step["acquiredFeatures"]) == groups[provider],
                          f"{provider} revealed exactly its own features")
        else:
            results.check(step["acquiredFeatures"] is None, f"failed {provider} revealed nothing")
        probability = state["latestPrediction"]["probability"]
        results.check(0.0 <= probability <= 1.0, "prediction is a probability")
        assert_nothing_hidden(result, acquired, f"after {provider}")
        print(f"        {outcome:<15} {provider:<18} probability {probability:.4f}  "
              f"cost {state['totalCost']}  simulated {state['accumulatedSimulatedMs']} ms")
    else:
        raise SmokeTestFailure("session did not stop after every provider was tried")

    status, error = request("POST", f"{api}/api/v1/sessions/{session_id}/advance")
    results.check(status == 409 and error.get("error") == "SESSION_NOT_ACTIVE",
                  "finished session cannot be advanced (409)", f"{status} {error}")

    status, history = request("GET", f"{api}/api/v1/sessions/{session_id}")
    results.check(status == 200 and len(history["predictions"]) == len(acquired) + 1,
                  "GET /sessions/{id} returns one prediction per successful acquisition plus the initial one")

    status, error = request("POST", f"{api}/api/v1/sessions",
                            {"recordId": first_record_of("train"), "policyId": "FIXED_ORDER"})
    results.check(status == 404 and error.get("error") == "RECORD_NOT_AVAILABLE",
                  "training-split record is refused (404)", f"{status} {error}")

    status, error = request("POST", f"{api}/api/v1/sessions",
                            {"recordId": record, "policyId": "FIXED_ORDER", "label": 1})
    results.check(status == 400, "request with an unknown field such as a label is refused (400)", str(status))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--compose", action="store_true",
                        help="build and start all services with Docker Compose, and stop them afterwards")
    parser.add_argument("--keep-running", action="store_true", help="with --compose, leave the containers running")
    parser.add_argument("--backend-url", default="http://localhost:8080")
    parser.add_argument("--frontend-url", default="http://localhost:4200",
                        help="used for the session when it responds (tests the nginx proxy); else the backend is used")
    parser.add_argument("--timeout", type=float, default=180, help="seconds to wait for the services to start")
    args = parser.parse_args(argv)

    config = json.loads(FEATURE_CONFIG.read_text(encoding="utf-8"))
    results = Results()
    started = False
    try:
        if args.compose:
            missing = [str(p.relative_to(REPO_ROOT)) for p in REQUIRED_FOR_COMPOSE if not p.exists()]
            if missing:
                raise SmokeTestFailure(f"generate these files first: {missing}")
            compose("up", "-d", "--build", "--wait")
            started = True

        print("Checking services")
        wait_until_up(f"{args.backend_url}/actuator/health", args.timeout)
        results.check(True, "backend is up")
        api = args.backend_url
        try:
            status, page = request("GET", args.frontend_url)
            if status == 200 and "<title>Provider-Aware Acquisition</title>" in str(page):
                results.check(True, "frontend page is served")
                api = args.frontend_url
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            print(f"  SKIP  frontend not reachable at {args.frontend_url}; using the backend directly")

        print(f"Running one session through {api}")
        run_session(api, config, results)
    except SmokeTestFailure as failure:
        print(f"  FAIL  {failure}")
        print(f"Smoke test FAILED after {results.passed} passed checks")
        return 1
    finally:
        if started and not args.keep_running:
            compose("down")

    print(f"Smoke test passed: {results.passed} checks")
    return 0


if __name__ == "__main__":
    sys.exit(main())
