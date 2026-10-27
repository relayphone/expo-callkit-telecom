#!/usr/bin/env python3
"""Summarize sanitized native latency samples without adding overlapping phases."""

import argparse
import json
import math
import re
import statistics
from collections import defaultdict
from pathlib import Path


MINIMUM_SAMPLES = 5
ROLES = {0: "raw", 1: "publisher", 2: "subscriber"}
TRANSPORT_STAGES = ("iceChecking", "iceConnected", "dtlsConnected")
TIMESTAMPS = {
    "push": ("pushNanos", "pushReceived"),
    "reported": ("reportedNanos", "reported"),
    "prewarm": ("prewarmNanos", "prewarmStarted"),
    "prejoined": ("prejoinedNanos", "prejoined"),
    "answer": ("answerRequestedNanos", "answerRequested"),
    "accepted": ("answerAcceptedNanos", "answerAccepted"),
    "authorized": ("audioAuthorizedNanos", "audioAuthorized"),
    "firstAudio": ("firstAudioNanos", "firstAudio"),
}
METRICS = {
    "pushToReportedMs": ("push", "reported"),
    "pushToPrewarmMs": ("push", "prewarm"),
    "pushToPrejoinedMs": ("push", "prejoined"),
    "answerToAcceptedMs": ("answer", "accepted"),
    "acceptedToAuthorizedMs": ("accepted", "authorized"),
    "authorizedToFirstAudioMs": ("authorized", "firstAudio"),
    "answerToFirstAudioMs": ("answer", "firstAudio"),
    "coreAddMs": ("coreAddRequested", "coreAdmitted"),
    "credentialsMs": ("credentialsStarted", "credentialsResolved"),
    "subscriptionToRemoteTrackMs": ("subscriptionRequested", "remoteTrackAvailable"),
    "remoteTrackToDecoderMs": ("remoteTrackAvailable", "decoderFirstPcm"),
    "microphonePublishMs": ("microphonePublishStarted", "microphonePublished"),
    "audioWaitToModeMs": ("audioWaitStarted", "audioModeReady"),
    "endpointReadyRelativeToAnswerMs": ("answer", "endpointReady"),
    "decoderFirstPcmRelativeToAnswerMs": ("answer", "decoderFirstPcm"),
    "playbackStartRelativeToAnswerMs": ("answer", "playbackStarted"),
    "microphonePublishRelativeToAnswerMs": ("answer", "microphonePublished"),
    "authorizedToCaptureCallbackMs": ("authorized", "captureStarted"),
    "authorizedToOutputMs": ("authorized", "outputFirstNonzero"),
    "answerToOutputMs": ("answer", "outputFirstNonzero"),
}
EVENT_STAGES = {stage for pair in METRICS.values() for stage in pair} | {
    stage for _, stage in TIMESTAMPS.values()
} | set(TRANSPORT_STAGES)
TIMING_NOTE = (
    "Transport timestamps describe first observed state, not exact handshake completion. "
    "Stats observations use a 25 ms poll and up to 50 ms stats cache unless recorded "
    "otherwise. Request-to-callback timing includes stats work and callback scheduling; "
    "missing request timestamps remain null. Callback observations are identified separately. Phase intervals overlap "
    "and must not be summed. Missing measurements are null, never zero."
)


def number(value, label, integer=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{label} must be numeric")
    if not math.isfinite(value) or value < 0 or (integer and not isinstance(value, int)):
        raise ValueError(f"{label} must be a finite nonnegative {'integer' if integer else 'number'}")
    return value


def label(value, name):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError(f"{name} must be a nonempty path label")
    return value


def summarize(values):
    observed = [value for value in values if value is not None]
    return {
        "observedCount": len(observed),
        "min": min(observed) if observed else None,
        "median": statistics.median(observed) if observed else None,
        "max": max(observed) if observed else None,
    }


def difference(start, end):
    return None if start is None or end is None else (end - start) / 1_000_000


def sample_summary(sample):
    index = number(sample.get("sampleIndex"), "sampleIndex", integer=True)
    if index < 1:
        raise ValueError("sampleIndex must start at 1; historical samples are unsupported")
    age = sample.get("processAgeMs")
    if age is not None:
        number(age, "processAgeMs")
    events = sample.get("events", [])
    if not isinstance(events, list):
        raise ValueError("events must be a list")
    first = {}
    transports = {role: {} for role in ROLES.values()}
    for event in events:
        if not isinstance(event, dict):
            raise ValueError("each event must be an object")
        stage = event.get("stage")
        # Copy no raw events, candidate details, stats reports, or unknown measurements.
        if stage not in EVENT_STAGES:
            continue
        nanos = number(event.get("nanos"), f"{stage}.nanos", integer=True)
        first[stage] = min(first.get(stage, nanos), nanos)
        if stage not in TRANSPORT_STAGES:
            continue
        measurements = event.get("measurements", {})
        if not isinstance(measurements, dict):
            raise ValueError("event measurements must be an object")
        role = ROLES.get(measurements.get("role"))
        if role is None:
            continue
        requested = measurements.get("requestNanos")
        if requested is not None:
            number(requested, "requestNanos", integer=True)
            if requested > nanos:
                raise ValueError("requestNanos must not follow the observed callback timestamp")
        previous = transports[role].get(stage)
        if previous is not None and previous["nanos"] <= nanos:
            continue
        sampled = measurements.get("sampled", 0)
        if isinstance(sampled, bool) or sampled not in (0, 1):
            raise ValueError("sampled must be 0 or 1")
        timing = {"nanos": nanos, "sampled": sampled,
                  "requestToCallbackMs": difference(requested, nanos)}
        for key, default in (("pollIntervalMs", 25), ("statsCacheMs", 50)):
            value = measurements.get(key, default if sampled else None)
            if value is not None:
                number(value, key)
            timing[key] = value
        transports[role][stage] = timing
    times = dict(first)
    for name, (field, stage) in TIMESTAMPS.items():
        value = sample.get(field)
        times[name] = number(value, field, integer=True) if value is not None else first.get(stage)
    metrics = {name: difference(times.get(start), times.get(end)) for name, (start, end) in METRICS.items()}
    for role in transports:
        for stage in TRANSPORT_STAGES:
            observation = transports[role].get(stage)
            if observation is None:
                transports[role][stage] = None
                continue
            nanos = observation.pop("nanos")
            observation["fromPushMs"] = difference(times["push"], nanos)
            observation["fromAnswerMs"] = difference(times["answer"], nanos)
            observation["observation"] = "stats-observed" if observation["sampled"] else "callback-observed"
    return {"sampleIndex": index, "processAgeMs": age, "metricsMs": metrics, "transports": transports}


def build_report(inputs):
    groups = defaultdict(list)
    for path in inputs:
        value = json.loads(path.read_text())
        if not isinstance(value, dict) or not isinstance(value.get("samples"), list):
            raise ValueError("each input must contain a samples list")
        profile = label(value.get("profile"), "profile")
        provider = label(value.get("provider"), "provider")
        if profile not in ("baseline", "optimized"):
            raise ValueError("profile must be baseline or optimized")
        sources = value.get("sources", [])
        if not isinstance(sources, list):
            raise ValueError("sources must be a list")
        for source in sources:
            groups[(profile, provider, label(source, "source"))]
        for sample in value["samples"]:
            if not isinstance(sample, dict):
                raise ValueError("each sample must be an object")
            key = (profile, provider, label(sample.get("credentials"), "credentials"))
            groups[key].append(sample_summary(sample))
    if not groups:
        raise ValueError("no sample paths were supplied")
    paths = []
    for (profile, provider, source), samples in sorted(groups.items()):
        indexes = {sample["sampleIndex"] for sample in samples}
        name = f"{profile}/{provider}/{source}"
        if len(samples) < MINIMUM_SAMPLES or len(indexes) < MINIMUM_SAMPLES:
            raise ValueError(f"{name} requires at least 5 samples with 5 distinct sampleIndex values")
        if len(indexes) != len(samples):
            raise ValueError(f"{name} contains duplicate sampleIndex values")
        transport_summary = {}
        for role in ROLES.values():
            transport_summary[role] = {}
            for stage in TRANSPORT_STAGES:
                observations = [sample["transports"][role][stage] for sample in samples]
                observed = [item for item in observations if item is not None]
                transport_summary[role][stage] = {
                    "observedCount": len(observed),
                    "sampledCount": sum(item["sampled"] for item in observed),
                    "callbackCount": sum(not item["sampled"] for item in observed),
                    **{key: summarize([item[key] for item in observed]) for key in
                       ("fromPushMs", "fromAnswerMs", "pollIntervalMs", "statsCacheMs", "requestToCallbackMs")},
                }
        paths.append({
            "profile": profile, "provider": provider, "credentials": source,
            "sampleCount": len(samples), "distinctSampleCount": len(indexes),
            "processAgeMs": summarize([sample["processAgeMs"] for sample in samples]),
            "metricsMs": {metric: summarize([sample["metricsMs"][metric] for sample in samples]) for metric in METRICS},
            "transports": transport_summary,
            "samples": sorted(samples, key=lambda sample: sample["sampleIndex"]),
        })
    return {"schemaVersion": 1, "minimumSamplesPerPath": MINIMUM_SAMPLES,
            "timingNote": TIMING_NOTE, "paths": paths}


def markdown(report):
    def cell(summary):
        if summary["observedCount"] == 0:
            return "— (0)"
        return f'{summary["min"]:.2f} / {summary["median"]:.2f} / {summary["max"]:.2f} ({summary["observedCount"]})'

    lines = ["# Native latency comparison", "", report["timingNote"], "",
             "Values are min / median / max in milliseconds (observed count). "
             "Signed endpoint/decoder offsets below zero occurred before Answer. "
             "Process age is descriptive; these samples do not establish a cold-start percentile.", ""]
    paths = report["paths"]
    lines += ["| Metric | " + " | ".join(f'{p["profile"]} / {p["provider"]} / {p["credentials"]} (n={p["sampleCount"]})' for p in paths) + " |",
              "| --- | " + " | ".join("---" for _ in paths) + " |"]
    for metric in METRICS:
        lines.append("| " + metric + " | " + " | ".join(cell(path["metricsMs"][metric]) for path in paths) + " |")
    lines.append("| processAgeMs | " + " | ".join(cell(path["processAgeMs"]) for path in paths) + " |")
    lines += ["", "## First observed transport states", "",
              "| Path | Role / state | From push, ms | From Answer, ms | Stats / callback count | Poll / cache, ms | Request to callback, ms |",
              "| --- | --- | --- | --- | --- | --- | --- |"]
    for path in paths:
        name = f'{path["profile"]} / {path["provider"]} / {path["credentials"]}'
        for role, stages in path["transports"].items():
            for stage, observed in stages.items():
                if not observed["observedCount"]:
                    continue
                lines.append(f'| {name} | {role} / {stage} | {cell(observed["fromPushMs"])} | '
                             f'{cell(observed["fromAnswerMs"])} | {observed["sampledCount"]} / '
                             f'{observed["callbackCount"]} | {cell(observed["pollIntervalMs"])} / '
                             f'{cell(observed["statsCacheMs"])} | {cell(observed["requestToCallbackMs"])} |')
    lines += ["", "Individual derived samples and missing-state counts are retained in the JSON output.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, action="append", required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-markdown", type=Path, required=True)
    args = parser.parse_args()
    try:
        outputs = (args.output_json, args.output_markdown)
        if len({path.resolve() for path in outputs}) != 2 or any(
            output.resolve() == source.resolve() for output in outputs for source in args.input
        ):
            raise ValueError("output paths must be distinct from each other and the inputs")
        report = build_report(args.input)
        rendered = markdown(report)
        for path in outputs:
            path.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
        args.output_markdown.write_text(rendered)
    except (OSError, ValueError, TypeError) as error:
        parser.exit(1, f"latency summary: {error}\n")


if __name__ == "__main__":
    main()
