#!/usr/bin/env python3
"""Run the draft adapter's real simulator tests; publish only checked summaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JOURNEYS = {
    "native": "NativeJoinJourneyTests/testNativeJoinAndReportGate()",
    "handler": "HandlerJourneyTests/testModuleScopeHandler()",
}


def command(args, timeout=60):
    return subprocess.check_output(args, text=True, timeout=timeout)


def cases(nodes):
    for node in nodes:
        if node["nodeType"] == "Test Case":
            yield node
        yield from cases(node.get("children", []))


def checked_result(summary, tree, expected):
    assert summary["result"] == "Passed", "Simulator journey failed"
    for key, value in {"totalTestCount": 1, "passedTests": 1, "failedTests": 0,
                       "skippedTests": 0, "expectedFailures": 0}.items():
        assert summary[key] == value, f"Unexpected {key}"
    assert not summary["testFailures"], "Unexpected test failures"
    observed = list(cases(tree["testNodes"]))
    assert len(observed) == 1, "Expected one named journey"
    test = observed[0]
    assert test["nodeIdentifier"].endswith(expected), "Wrong journey executed"
    assert test["result"] == "Passed", "Named journey did not pass"
    devices = tree["devices"]
    assert len(devices) == 1, "Expected one simulator"
    device = devices[0]
    assert int(device["osVersion"].split(".")[0]) >= 27, "Requires iOS27 SDK runtime"
    return {
        "test": expected, "result": "Passed", "passed": 1, "failed": 0, "skipped": 0,
        "durationSeconds": test["durationInSeconds"],
        "device": {key: device[key] for key in ("modelName", "osVersion", "architecture")},
        "runtimeWarningCount": len(summary.get("runtimeWarnings", [])),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--derived-data", type=Path, required=True)
    options = parser.parse_args()
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    revision = (ROOT / "apple/sdk-revision").read_text().strip()
    assert re.fullmatch(r"[0-9a-f]{40}", revision)
    actual = command(["git", "-C", str(ROOT / "apple/.sdk/ios-voip-sdk"), "rev-parse", "HEAD"]).strip()
    assert actual == revision, "SDK checkout differs from pin"
    runtimes = json.loads(command(["xcrun", "simctl", "list", "runtimes", "--json"]))["runtimes"]
    available = [r for r in runtimes if r.get("isAvailable") and r["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")
                 and int(r["version"].split(".")[0]) >= 27]
    assert available, "No available iOS27+ simulator runtime"
    runtime = max(available, key=lambda r: tuple(int(v) for v in r["version"].split(".")))
    device_types = json.loads(command(["xcrun", "simctl", "list", "devicetypes", "--json"]))["devicetypes"]
    phone = next(d for d in device_types if d["name"] == "iPhone 17 Pro")
    device = command(["xcrun", "simctl", "create", "Expo v2 M35 CI", phone["identifier"], runtime["identifier"]]).strip()
    report = {"schemaVersion": 1, "sourceRevision": command(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).strip(),
              "sourceDirty": bool(command(["git", "-C", str(ROOT), "status", "--porcelain", "--untracked-files=no"]).strip()),
              "sourceHashes": {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in (
                  "apple/Package.swift", "apple/Sources/ExpoConversationsBoundary/SDKConversationAdapter.swift",
                  "apple/Tests/ExpoConversationsBoundaryTests/AdapterJourneyTests.swift", "tools/run-ios-m35-journeys.py")},
              "sdkRevision": revision, "runId": os.environ.get("GITHUB_RUN_ID"), "status": "running", "journeys": {}}
    phase = "boot"
    try:
        command(["xcrun", "simctl", "boot", device])
        command(["xcrun", "simctl", "bootstatus", device, "-b"], timeout=180)
        for name, expected in JOURNEYS.items():
            phase = f"{name}.build-test"
            bundle = output / f"{name}.xcresult"
            with (output / f"{name}.log").open("w") as log:
                # Each invocation launches a new process: native launch selection
                # is intentionally immutable for that process's entire lifetime.
                result = subprocess.run([
                    "xcodebuild", "-scheme", "ExpoConversationsBoundary", "-destination", f"platform=iOS Simulator,id={device}",
                    "-derivedDataPath", str(options.derived_data.resolve()), "-resultBundlePath", str(bundle),
                    "-parallel-testing-enabled", "NO", "-maximum-concurrent-test-simulator-destinations", "1",
                    f"-only-testing:ExpoConversationsBoundaryTests/{expected.removesuffix('()')}",
                    "CODE_SIGNING_ALLOWED=NO", "test"], cwd=ROOT / "apple", stdout=log, stderr=subprocess.STDOUT,
                    timeout=600, check=False)
            # Keep raw diagnostics local; artifacts contain only this allowlist.
            phase = f"{name}.extract-results"
            summary = json.loads(command(["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(bundle)]))
            tree = json.loads(command(["xcrun", "xcresulttool", "get", "test-results", "tests", "--path", str(bundle)]))
            phase = f"{name}.check-results"
            report["lastAttempt"] = {"journey": name, "exitCode": result.returncode,
                "counts": {key: summary.get(key) if type(summary.get(key)) is int else None
                           for key in ("totalTestCount", "passedTests", "failedTests", "skippedTests", "expectedFailures")}}
            report["journeys"][name] = checked_result(summary, tree, expected)
            assert result.returncode == 0, "xcodebuild failed despite result summary"
            (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        report["status"] = "passed"
        (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))
    except Exception as error:
        report["status"] = "failed"
        report["failure"] = {"phase": phase, "kind": type(error).__name__}
        (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        raise
    finally:
        subprocess.run(["xcrun", "simctl", "shutdown", device], timeout=45, check=False, capture_output=True)
        subprocess.run(["xcrun", "simctl", "delete", device], timeout=45, check=True, capture_output=True)


if __name__ == "__main__":
    main()
