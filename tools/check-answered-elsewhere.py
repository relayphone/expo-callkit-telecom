"""Check six real Core/SystemUI native answered-elsewhere samples."""
import json
import sys
from pathlib import Path

result = json.loads(Path(sys.argv[1]).read_text())
assert result.get("passed") is True, "Answered-elsewhere native journey failed"
assert result["nativeBudgetMs"] == 1000
assert isinstance(result["headsUpMinimumMs"], int) and result["headsUpMinimumMs"] >= 0
assert result["uiObserverAllowanceMs"] == 1000
assert result["uiBudgetMs"] == result["headsUpMinimumMs"] + result["uiObserverAllowanceMs"]
samples = result["samples"]
assert len(samples) == 6
assert {(sample["source"], sample["sampleIndex"]) for sample in samples} == {
    (source, index) for source in ("host", "push") for index in (1, 2, 3)
}
assert len({sample["eventId"] for sample in samples}) == 6
assert len({sample["serverCallId"] for sample in samples}) == 6
maximum_native_ms = 0.0
maximum_ui_ms = 0.0
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
    assert sample["incomingNanos"] <= sample["reportedNanos"] <= sample["visibleBeforeNanos"] <= start
    assert start <= sample["terminalSnapshotNanos"] <= sample["eventNanos"] <= sample["emptyHookNanos"]
    assert sample["acceptedNanos"] >= start
    for key in ("terminalSnapshotNanos", "eventNanos", "emptyHookNanos", "notificationGoneNanos", "platformGoneNanos"):
        elapsed_ms = (sample[key] - start) / 1_000_000
        assert 0 <= elapsed_ms <= result["nativeBudgetMs"], f"{sample['source']}/{sample['sampleIndex']} {key}: {elapsed_ms} ms"
        maximum_native_ms = max(maximum_native_ms, elapsed_ms)
    ui_ms = (sample["uiGoneNanos"] - start) / 1_000_000
    assert 0 <= ui_ms <= result["uiBudgetMs"]
    assert start <= sample["firstUiCallerAbsentNanos"] <= sample["uiGoneNanos"]
    assert sample["uiScans"] >= sample["uiCompleteScans"] >= 2
    assert sample["maximumUiScanNanos"] >= 0
    for scan in sample["uiScanTimings"]:
        assert start <= scan["requestedNanos"] <= scan["returnedNanos"] <= sample["uiGoneNanos"]
        assert scan["returnedNanos"] - scan["requestedNanos"] <= sample["maximumUiScanNanos"]
    final_scans = sample["uiScanTimings"][-2:]
    assert len(final_scans) == 2
    assert all(scan["sameWindow"] is True and scan["complete"] is True and scan["callerPresent"] is False for scan in final_scans)
    maximum_ui_ms = max(maximum_ui_ms, ui_ms)
    print(f"{sample['source']}/{sample['sampleIndex']}: visible ring removed in {ui_ms:.1f} ms; maximum UI scan {sample['maximumUiScanNanos'] / 1_000_000:.1f} ms")
print(f"Six samples passed: native maximum {maximum_native_ms:.1f} ms (1000 ms budget); UI maximum {maximum_ui_ms:.1f} ms ({result['uiBudgetMs']} ms budget, configured heads-up minimum {result['headsUpMinimumMs']} ms)")
