"""Check six real Core/SystemUI native answered-elsewhere samples."""
import json
import sys
from pathlib import Path

result = json.loads(Path(sys.argv[1]).read_text())
assert result.get("passed") is True, "Answered-elsewhere native journey failed"
assert result["budgetMs"] == 1000
samples = result["samples"]
assert len(samples) == 6
assert {(sample["source"], sample["sampleIndex"]) for sample in samples} == {
    (source, index) for source in ("host", "push") for index in (1, 2, 3)
}
assert len({sample["eventId"] for sample in samples}) == 6
assert len({sample["serverCallId"] for sample in samples}) == 6
maximum_ms = 0.0
for sample in samples:
    before = sample["uiBefore"]
    assert before["packageName"] == "com.android.systemui"
    assert isinstance(before["windowId"], int) and before["windowId"] >= 0
    assert all(before[key] is True for key in ("callerVisible", "answerVisible", "declineVisible"))
    assert sample["uiAbsenceObservations"] == 2
    assert sample["uiAbsentWindowId"] == before["windowId"]
    assert sample["nativeEvidence"] == "telecom-historical-SET_DISCONNECTED"
    assert sample["nativeCode"] == 3, "API35 transactional Core must use supported REMOTE"
    assert sample["nativeReason"] == "answeredElsewhere"
    assert sample["correlatedEvents"] == 1
    assert sample["endHandlerCalls"] == 0
    start = sample["signalNanos"]
    assert start <= sample["terminalSnapshotNanos"] <= sample["eventNanos"] <= sample["emptyHookNanos"]
    assert sample["acceptedNanos"] >= start
    for key in ("terminalSnapshotNanos", "eventNanos", "emptyHookNanos", "notificationGoneNanos", "uiGoneNanos", "platformGoneNanos"):
        elapsed_ms = (sample[key] - start) / 1_000_000
        assert 0 <= elapsed_ms <= result["budgetMs"], f"{sample['source']}/{sample['sampleIndex']} {key}: {elapsed_ms} ms"
        maximum_ms = max(maximum_ms, elapsed_ms)
    print(f"{sample['source']}/{sample['sampleIndex']}: visible ring removed in {(sample['uiGoneNanos'] - start) / 1_000_000:.1f} ms")
print(f"Six native answered-elsewhere samples passed; maximum completion {maximum_ms:.1f} ms (1000 ms engineering budget)")
