"""Check the result written by JavaScript through the production Expo native bridge."""
import json
import sys
from pathlib import Path

result = json.loads(Path(sys.argv[1]).read_text())
expected = [
    "ringing", "connecting", "connected", "held", "connected", "incoming-ended",
    "dialing", "connecting", "connected", "outgoing-ended",
]
assert result.get("passed") is True, result
assert result.get("steps") == expected, result
assert result.get("transport") == "expo-native-bridge", result
print("Expo example incoming, hold/resume and outgoing journeys passed")
