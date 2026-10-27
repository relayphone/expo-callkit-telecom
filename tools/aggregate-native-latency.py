#!/usr/bin/env python3
"""Require all 20 fresh-process benchmark attempts before aggregating latency."""

import argparse
import json
import re
from pathlib import Path


SOURCES = {"livekit": ("push", "fetcher"), "webrtc": ("fetcher", "livekitPush")}
SAMPLE_INDEXES = range(1, 6)
REGIME = "fresh app process per call; media prejoined before Answer"
METADATA = {"metric": "native-answer-request-to-first-remote-pcm", "pcmThreshold": 128,
            "budgetMs": 3000, "targetMs": 300}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_instrumentation_log(path):
    output = path.read_text()
    require(not re.search(r"FAILURES!+|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|"
                          r"^INSTRUMENTATION_RESULT:\s*(?:shortMsg|longMsg)=", output, re.MULTILINE),
            "instrumentation reported a runner failure")
    codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output, re.MULTILINE)
    require(codes == ["-1"], "instrumentation must finish with result code -1")
    statuses = re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", output, re.MULTILINE)
    require(statuses == ["1", "0"], "instrumentation must start and successfully finish exactly one test")
    for key in ("numtests", "current"):
        values = re.findall(rf"^INSTRUMENTATION_STATUS:\s*{key}=(\d+)\s*$", output, re.MULTILINE)
        require(bool(values) and all(value == "1" for value in values),
                "instrumentation must report exactly one selected test")
    require(len(re.findall(r"^OK \(1 test\)\s*$", output, re.MULTILINE)) == 1,
            "instrumentation must report OK (1 test)")


def aggregate(directory):
    expected = {
        f"{provider}-{source}-{index}.json": (provider, source, index)
        for provider, sources in SOURCES.items() for source in sources for index in SAMPLE_INDEXES
    }
    actual = {path.name for path in directory.glob("*.json")}
    require(actual == set(expected),
            f"all 20 attempt files are required; missing={sorted(set(expected) - actual)}, "
            f"unexpected={sorted(actual - set(expected))}")
    grouped = {provider: [] for provider in SOURCES}
    profiles = set()
    for filename, (provider, source, index) in expected.items():
        value = json.loads((directory / filename).read_text())
        require(isinstance(value, dict), f"{filename}: result must be an object")
        require(value.get("passed") is True, f"{filename}: attempt failed")
        require(value.get("benchmarkOnly") is True, f"{filename}: dedicated benchmark result required")
        require(value.get("processIsolation") == "fresh-app-process", f"{filename}: fresh app process isolation is required")
        require(value.get("provider") == provider, f"{filename}: provider mismatch")
        require(value.get("sources") == [source], f"{filename}: source mismatch")
        require(type(value.get("samplesPerPath")) is int and value["samplesPerPath"] == 1,
                f"{filename}: exactly one sample per path is required")
        profile = value.get("profile")
        require(profile in ("baseline", "optimized"), f"{filename}: invalid profile")
        profiles.add(profile)
        for key, expected_value in METADATA.items():
            require(value.get(key) == expected_value, f"{filename}: incompatible {key}")
        require(value.get("cleanup") == ["ended"], f"{filename}: ended cleanup is required")
        require(value.get("jsJoinHandlerCalls") == 0, f"{filename}: native answer must not call JS Join")
        require(value.get("jsWarmRequests") == 1, f"{filename}: exactly one warm request is required")
        samples = value.get("samples")
        require(isinstance(samples, list) and len(samples) == 1,
                f"{filename}: exactly one sample is required")
        sample = samples[0]
        require(isinstance(sample, dict), f"{filename}: sample must be an object")
        require(sample.get("credentials") == source, f"{filename}: sample source mismatch")
        require(type(sample.get("sampleIndex")) is int and sample["sampleIndex"] == index,
                f"{filename}: unique expected sampleIndex {index} is required")
        grouped[provider].append(sample)
    require(len(profiles) == 1, "baseline and optimized attempts must not be mixed in one run")
    profile = profiles.pop()
    return {
        provider: {
            "passed": True, "profile": profile, "provider": provider,
            "benchmarkOnly": True, "processIsolation": "fresh-app-process", "isolation": REGIME,
            "sources": list(SOURCES[provider]), "samplesPerPath": 5,
            **METADATA, "samples": samples, "cleanup": ["ended"],
            "jsJoinHandlerCalls": 0, "jsWarmRequests": len(samples),
            "attemptCount": len(samples), "failedAttemptCount": 0,
        }
        for provider, samples in grouped.items()
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--check-instrumentation-log", type=Path)
    args = parser.parse_args()
    try:
        if args.check_instrumentation_log is not None:
            require(args.input_dir is None and args.output_dir is None,
                    "log validation cannot be combined with aggregation")
            check_instrumentation_log(args.check_instrumentation_log)
            return
        require(args.input_dir is not None and args.output_dir is not None,
                "aggregation requires --input-dir and --output-dir")
        reports = aggregate(args.input_dir)
        args.output_dir.mkdir(parents=True, exist_ok=True)
        for provider, report in reports.items():
            path = args.output_dir / f"native-latency-{provider}.json"
            path.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    except (OSError, ValueError, TypeError) as error:
        parser.exit(1, f"latency aggregate: {error}\n")


if __name__ == "__main__":
    main()
