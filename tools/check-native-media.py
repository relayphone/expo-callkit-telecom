"""Verify the emulator's native media ordering, PCM timing and cleanup evidence."""
import json
import sys
from pathlib import Path

result = json.loads(Path(sys.argv[1]).read_text())
assert result["passed"] is True, result
assert result["metric"] == "native-answer-request-to-first-remote-pcm"
assert result["pcmThreshold"] == 128
provider = result.get("provider", "livekit")
assert provider in ("livekit", "webrtc")
expected_sources = {"push", "fetcher"} if provider == "livekit" else {"fetcher", "livekitPush"}
assert {sample["credentials"] for sample in result["samples"]} == expected_sources
samples_per_path = result.get("samplesPerPath", 1)
assert len(result["samples"]) == len(expected_sources) * samples_per_path
for source in expected_sources:
    selected = [sample for sample in result["samples"] if sample["credentials"] == source]
    assert len(selected) == samples_per_path
    if samples_per_path > 1:
        assert {sample["sampleIndex"] for sample in selected} == set(range(1, samples_per_path + 1))
for sample in result["samples"]:
    assert sample["highPriority"] is True
    assert sample["pushNanos"] <= sample["reportedNanos"]
    assert sample["reportedNanos"] <= min(sample["prewarmNanos"], sample["jsWarmNanos"])
    assert sample["prewarmNanos"] <= sample["prejoinedNanos"] <= sample["answerRequestedNanos"]
    assert sample["answerRequestedNanos"] <= sample["answerAcceptedNanos"] <= sample["audioAuthorizedNanos"] <= sample["firstAudioNanos"]
    assert all(sample[key] >= sample["audioAuthorizedNanos"] for key in
               ("captureStartedNanos", "playbackStartedNanos", "remoteSubscribedNanos"))
    elapsed = (sample["firstAudioNanos"] - sample["answerRequestedNanos"]) / 1_000_000
    assert abs(elapsed - sample["answerToFirstAudioMs"]) < .001
    assert 0 <= elapsed <= result["budgetMs"]
    assert sample["pcm"]["frames"] > 0 and sample["pcm"]["peak"] >= 128
    assert sample["preanswerCapture"] is False
    if samples_per_path > 1:
        assert sample["preanswerRecording"]["newObservedCount"] == 0
        assert sample["activeRecording"]["newActiveCount"] > 0
    print(f'{sample["credentials"]}: native answer to remote PCM {elapsed:.1f} ms (target {result["targetMs"]} ms)')
assert result["jsJoinHandlerCalls"] == 0
assert result["jsWarmRequests"] == len(expected_sources) * samples_per_path + 4
assert set(result["cleanup"]) == {"hold-capture-stopped-resumed", "declined", "unanswered", "404", "410"}

if provider == "webrtc":
    first = result["declineFirstZeroObservation"]
    receipt = result["declineObservation"]
    assert first["clients"] == 0 and "declined" not in first["states"]
    assert result["declinePendingObserved"] is True
    assert receipt["clients"] == 0 and "declined" in receipt["states"]
    times = receipt["terminalTimes"]
    assert 0 < times["receivedNanos"] <= times["releasedNanos"] <= times["recordedNanos"]
    acknowledgment = result["declineTerminalSignal"]
    assert acknowledgment["delivered"] == 1 and acknowledgment["httpErrorStatus"] == 0
    assert acknowledgment["nanos"] >= result["declineClosedNanos"] > 0
    print("WebRTC decline: local closure precedes independently acknowledged server receipt")
