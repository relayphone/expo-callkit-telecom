"""Verify the emulator's native media ordering, PCM timing and cleanup evidence."""
import json
import sys
from pathlib import Path

result = json.loads(Path(sys.argv[1]).read_text())
assert result["passed"] is True, result
assert result["metric"] == "native-answer-request-to-first-remote-pcm"
assert result["pcmThreshold"] == 128
assert {sample["credentials"] for sample in result["samples"]} == {"push", "fetcher"}
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
    print(f'{sample["credentials"]}: native answer to remote PCM {elapsed:.1f} ms (target {result["targetMs"]} ms)')
assert result["jsJoinHandlerCalls"] == 0
assert result["jsWarmRequests"] == 6
assert set(result["cleanup"]) == {"hold-capture-stopped-resumed", "declined", "unanswered", "404", "410"}
