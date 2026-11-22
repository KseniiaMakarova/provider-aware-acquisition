"""End-to-end smoke test of the running platform through its public API.

Runs complete acquisition sessions with every policy and several provider scenarios and checks
the main safeguards on the way: only the initial features are visible at the start, each
provider reveals exactly its own group, unacquired features, labels and demographics never
appear in any response, failed and timed-out calls reveal nothing and cost nothing, every
decision is explained, a finished session cannot be advanced, and records outside the served
split are refused.

The MVP checks find suitable validation records themselves (an adaptive session that stops
early, one that stops without any provider, one whose choice changes when Repayment History is
expensive, a timeout and a failure), so they do not depend on fixed record IDs.

Uses only the Python standard library. Typical use from the repository root:

    python scripts/smoke_test.py --compose        # build and start all containers, test, stop them
    python scripts/smoke_test.py                  # test services that are already running

The --compose mode needs the generated files first: data/features/validation.csv
(python -m ml_service.data.prepare) and ml-service/artifacts (python -m ml_service.training.train and
python -m ml_service.training.reference).
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
    REPO_ROOT / "ml-service" / "artifacts" / "value-reference" / "metadata.json",
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
    return records_of(split, 1)[0]


def records_of(split: str, count: int) -> list[str]:
    with SPLIT_MANIFEST.open(newline="") as f:
        return [row["ID"] for row in csv.DictReader(f) if row["split"] == split][:count]


def compose(*args: str) -> None:
    command = ["docker", "compose", "--profile", "app", *args]
    print(f"$ {' '.join(command)}", flush=True)
    subprocess.run(command, cwd=REPO_ROOT, check=True)


def hidden_names(config: dict, acquired: list[str]) -> list[str]:
    """Feature names that must not appear while only the given providers are acquired."""
    unacquired = [f for p in config["providers"] if p["id"] not in acquired for f in p["features"]]
    return unacquired + config["excludedColumns"] + [config["dataset"]["targetColumn"]]


def leaked(config: dict, body: dict, acquired: list[str]) -> list[str]:
    text = json.dumps(body)
    return [name for name in hidden_names(config, acquired) if f'"{name}"' in text]


def run_session(api: str, config: dict, results: Results) -> None:
    initial = config["initialFeatures"]
    groups = {p["id"]: p["features"] for p in config["providers"]}

    def assert_nothing_hidden(body: dict, acquired: list[str], where: str) -> None:
        names = leaked(config, body, acquired)
        results.check(not names, f"{where}: no unacquired feature, label or demographic in the response", str(names))

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


class Sessions:
    """Runs complete sessions and checks every response of each for leaks."""

    def __init__(self, api: str, config: dict) -> None:
        self.api = api
        self.config = config
        self.count = 0

    def play(self, record: str, policy: str, scenario: str | None = None) -> dict:
        """Creates a session, advances it until it stops and returns its final state."""
        body = {"recordId": record, "policyId": policy, **({"scenarioId": scenario} if scenario else {})}
        status, session = request("POST", f"{self.api}/api/v1/sessions", body)
        if status != 201:
            raise SmokeTestFailure(f"POST /sessions {body} returned {status} {session}")
        self._scan(session, [], f"new {policy} session for record {record}")
        for _ in range(len(self.config["providers"]) + 2):
            status, result = request("POST", f"{self.api}/api/v1/sessions/{session['sessionId']}/advance")
            if status != 200:
                raise SmokeTestFailure(f"POST /advance returned {status} {result}")
            self._scan(result, result["session"]["acquiredProviders"], f"{policy} record {record} {result['outcome']}")
            if result["outcome"] == "STOPPED":
                break
        else:
            raise SmokeTestFailure(f"{policy} session for record {record} did not stop")
        status, final = request("GET", f"{self.api}/api/v1/sessions/{session['sessionId']}")
        self._scan(final, final["acquiredProviders"], f"{policy} record {record} history")
        self.count += 1
        return final

    def _scan(self, body: dict, acquired: list[str], where: str) -> None:
        names = leaked(self.config, body, acquired)
        if names:
            raise SmokeTestFailure(f"{where}: response contains {names}")


def first_acquisition(session: dict) -> str | None:
    return session["steps"][0]["providerId"] if session["steps"] else None


def candidate(decision: dict, provider: str) -> dict:
    return next(c for c in decision["candidates"] if c["providerId"] == provider)


def run_mvp_checks(api: str, config: dict, results: Results) -> None:
    provider_ids = [p["id"] for p in config["providers"]]
    repayment = provider_ids[0]
    sessions = Sessions(api, config)
    records = records_of("validation", 60)

    status, policies = request("GET", f"{api}/api/v1/policies")
    results.check(status == 200 and {p["id"] for p in policies["policies"]}
                  == {"ADAPTIVE", "FIXED_ORDER", "FULL_ACQUISITION"},
                  "GET /policies lists ADAPTIVE, FIXED_ORDER and FULL_ACQUISITION")
    status, scenarios = request("GET", f"{api}/api/v1/scenarios")
    scenario_ids = [s["id"] for s in scenarios["scenarios"]]
    results.check(status == 200 and scenarios["defaultScenario"] == "default-v1" and {
        "default-v1", "costly-repayment-v1", "repayment-unavailable-v1", "unreliable-v1"} <= set(scenario_ids),
                  "GET /scenarios lists the four demonstration scenarios, default-v1 by default")

    # 1 and 2: adaptive sessions that stop early, and one that stops before any provider.
    early, nothing = None, None
    for record in records:
        session = sessions.play(record, "ADAPTIVE")
        if session["stopReason"] != "INSUFFICIENT_EXPECTED_BENEFIT":
            continue
        if session["steps"] and early is None and len(session["acquiredProviders"]) < len(provider_ids):
            early = session
        if not session["steps"] and nothing is None:
            nothing = session
        if early and nothing:
            break
    results.check(early is not None, "an adaptive session acquires information and stops early")
    steps = early["steps"]
    results.check(all(s["decisionDetails"]["policyId"] == "ADAPTIVE"
                      and s["decisionDetails"]["estimation"]["method"] == "knn-eig-v1"
                      and candidate(s["decisionDetails"], s["providerId"])["score"] > 0 for s in steps),
                  "every adaptive acquisition is stored with its estimate and a positive score")
    stop = early["stopDecision"]
    results.check(stop["action"] == "STOP" and early["stopExplanation"]
                  and all(c["score"] <= 0 for c in stop["candidates"] if c["score"] is not None),
                  "the stop decision is stored with its explanation and no positive score")
    print(f"        record {early['recordId']}: {' + '.join(s['providerId'] for s in steps)}, then stop "
          f"(probability {early['predictions'][0]['probability']:.4f} -> {early['latestPrediction']['probability']:.4f}, "
          f"cost {early['totalCost']})")
    results.check(nothing is not None and nothing["totalCost"] == 0 and len(nothing["predictions"]) == 1,
                  "an adaptive session can stop without acquiring any provider")
    print(f"        record {nothing['recordId']}: stopped at the initial state")

    # 3: an expensive provider changes the decision for the same record.
    changed = None
    for record in records:
        neutral = sessions.play(record, "ADAPTIVE")
        if first_acquisition(neutral) != repayment:
            continue
        costly = sessions.play(record, "ADAPTIVE", "costly-repayment-v1")
        if first_acquisition(costly) != repayment:
            changed = (record, neutral, costly)
            break
    results.check(changed is not None, "an expensive Repayment History changes the adaptive decision for a record")
    record, neutral, costly = changed
    first_costly = costly["steps"][0]["decisionDetails"] if costly["steps"] else costly["stopDecision"]
    results.check(candidate(first_costly, repayment)["score"] < candidate(neutral["steps"][0]["decisionDetails"],
                                                                         repayment)["score"],
                  "its score is lower when it is expensive")
    print(f"        record {record}: default-v1 starts with {repayment}, costly-repayment-v1 "
          f"{'starts with ' + first_acquisition(costly) if costly['steps'] else 'stops at once'}")

    # 4: Repayment History unavailable.
    adaptive = sessions.play(records[0], "ADAPTIVE", "repayment-unavailable-v1")
    full = sessions.play(records[0], "FULL_ACQUISITION", "repayment-unavailable-v1")
    decisions = [s["decisionDetails"] for s in adaptive["steps"]] + [adaptive["stopDecision"]]
    results.check(all(s["providerId"] != repayment for s in adaptive["steps"] + full["steps"])
                  and all(candidate(d, repayment)["status"] == "UNAVAILABLE"
                          and candidate(d, repayment)["score"] is None for d in decisions),
                  "an unavailable provider is never estimated, chosen or called")
    results.check(full["stopReason"] == "NO_FEASIBLE_PROVIDERS"
                  and [s["providerId"] for s in full["steps"]] == provider_ids[1:],
                  "full acquisition without Repayment History stops with NO_FEASIBLE_PROVIDERS")

    # 5: simulated failures and timeouts.
    timeouts = {p["id"]: p["timeoutMs"] for s in scenarios["scenarios"] if s["id"] == "unreliable-v1"
                for p in s["providers"]}
    seen = set()
    for record in records:
        session = sessions.play(record, "FULL_ACQUISITION", "unreliable-v1")
        successes = [s for s in session["steps"] if s["status"] == "SUCCESS"]
        for step in session["steps"]:
            if step["status"] == "SUCCESS":
                continue
            seen.add(step["failureType"])
            if step["acquiredFeatures"] is not None or step["chargedCost"] != 0 or step["predictionSequenceNumber"]:
                raise SmokeTestFailure(f"failed step revealed or charged something: {step}")
            if step["failureType"] == "TIMEOUT" and step["simulatedLatencyMs"] != timeouts[step["providerId"]]:
                raise SmokeTestFailure(f"timed-out step was not charged its timeout: {step}")
        if len(session["predictions"]) != len(successes) + 1 or session["accumulatedSimulatedMs"] != sum(
                s["simulatedLatencyMs"] for s in session["steps"]):
            raise SmokeTestFailure(f"inconsistent session after failures: {session['sessionId']}")
        if seen >= {"FAILURE", "TIMEOUT"}:
            break
    results.check(seen >= {"FAILURE", "TIMEOUT"},
                  "failed and timed-out calls reveal nothing, cost nothing, add their simulated time and are not retried")

    # 6: full-acquisition reference.
    full = sessions.play(records[0], "FULL_ACQUISITION")
    results.check([s["providerId"] for s in full["steps"]] == provider_ids
                  and full["stopReason"] == "ALL_PROVIDERS_ACQUIRED"
                  and len(full["predictions"]) == len(provider_ids) + 1,
                  "full acquisition acquires every provider with the same model and stops")

    # 7: no leaks anywhere (every response of every session above was scanned).
    results.check(sessions.count > 0, f"no unacquired feature, label or demographic in any response of "
                                      f"{sessions.count} MVP sessions")


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

        print(f"Running a fixed-order session through {api}")
        run_session(api, config, results)
        print(f"Running the MVP checks through {api}")
        run_mvp_checks(api, config, results)
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
