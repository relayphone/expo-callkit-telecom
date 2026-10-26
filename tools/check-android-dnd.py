"""Check actual Core admission, notification ranking and typed filtering on API35."""
import json
from pathlib import Path
import sys

directory = Path(sys.argv[1])
expected = {"off-any": True, "on-any": False, "priority-any": True, "priority-none": False,
            "priority-contacts-unknown": False, "priority-contacts-contact": True,
            "priority-contacts-opaque": False,
            "priority-starred-contact": False, "priority-starred-starred": True,
            "priority-repeat-first": False, "priority-repeat-second": True, "off-restored": True}
for name, permitted in expected.items():
    value = json.loads((directory / f"{name}.json").read_text())
    assert value["passed"] and value["case"] == name
    assert value["coreAccepted"] is True
    assert value["coreRingingObserved"] is True and value["coreCallsAfterCleanup"] == 0
    assert value["callFilterAllowed"] is permitted
    assert value["notificationAllowed"] is permitted
    assert value["ringtonePlaybackObserved"] is permitted
    if permitted:
        assert value["ringVolume"] > 0 and value["ringerMode"] == 2
    assert value["notificationCategory"] == "call" and value["soundConfigured"] is True
    assert value["nativeHookCount"] == 1
    result = value["outcome"]
    assert result["status"] == ("reported" if value["nativeAllowedAfterRaw"] else "filtered")
    if result["status"] == "filtered":
        assert result["reason"] == "doNotDisturb" and "id" not in result
    print(f'{name}: Core accepted; notification {"eligible" if permitted else "suppressed"}; native {result["status"]}')
warm = json.loads((directory / "warm-cleanup.json").read_text())
assert warm["passed"] is True and warm["prejoinedBeforeFilter"] is True
assert warm["filteredReason"] == "doNotDisturb" and warm["serverClients"] == 0
assert warm["captureStarted"] is False and warm["nativeClosed"] is True
assert warm["coreCallsAfterCleanup"] == 0 and warm["delayedRejectPreventsAnswerAndNotification"] is True
assert warm["appReasons"] == ["blockList", "other"]
print("Prewarmed DND cleanup and native blockList/other outcomes passed")
