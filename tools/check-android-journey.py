"""Validate the result produced by the real Core-Telecom emulator journey."""
import json
import sys
from pathlib import Path

result = json.loads(Path(sys.argv[1]).read_text())
expected = [
    "ringing", "connecting", "connected", "held",
    "second-ringing", "second-ended", "connected", "incoming-ended",
    "dialing", "connecting", "connected", "outgoing-ended",
]
assert result.get("passed") is True, "Android journey did not pass"
assert result.get("steps") == expected, "Android journey steps are incomplete or out of order"
assert result.get("identityVectors") == "call-identity.json", "Missing shared identity fixture"
print("Core-Telecom incoming, concurrent held call and outgoing journeys passed")
