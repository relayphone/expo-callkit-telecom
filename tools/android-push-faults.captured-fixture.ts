import type { VerifierDiagnostic } from "./android-push-faults-diagnostics.ts";
import type { ColdPushReceipt } from "./android-push-faults.ts";

// Captured from https://github.com/relayphone/expo-callkit-telecom/actions/runs/44021623643
// Artifact 14302071776: sanitized journal and structural rejection, not a raw Telecom row.
// Call/delivery/event/process identifiers are replaced; timestamps and diagnostic fields are captured.
// The raw rejected row was never retained. Transactional row fixtures remain explicitly synthetic.
export const capturedColdJournal: ColdPushReceipt["journal"] = {
  "schemaVersion": 1,
  "processId": 456,
  "events": [
    {
      "kind": "coldStart",
      "callId": "cold-call",
      "deliveryId": "cold-delivery",
      "eventId": "cold-invite",
      "delayMillis": 0,
      "stage": "processStarted",
      "elapsedRealtimeNanos": "804636000000"
    },
    {
      "kind": "coldStart",
      "callId": "cold-call",
      "deliveryId": "cold-delivery",
      "eventId": "cold-invite",
      "delayMillis": 0,
      "stage": "intake",
      "elapsedRealtimeNanos": "804917091520"
    },
    {
      "kind": "coldStart",
      "callId": "cold-call",
      "deliveryId": "cold-delivery",
      "eventId": "cold-invite",
      "delayMillis": 0,
      "stage": "ringing",
      "elapsedRealtimeNanos": "805332459530"
    },
    {
      "kind": "coldStart",
      "callId": "cold-call",
      "deliveryId": "cold-delivery",
      "eventId": "cold-invite",
      "delayMillis": 0,
      "stage": "presented",
      "elapsedRealtimeNanos": "805358875702"
    },
    {
      "kind": "coldStart",
      "callId": "cold-call",
      "deliveryId": "cold-delivery",
      "eventId": "cold-invite",
      "delayMillis": 0,
      "stage": "answered",
      "elapsedRealtimeNanos": "805584661470"
    },
    {
      "kind": "coldStart",
      "callId": "cold-call",
      "deliveryId": "cold-delivery",
      "eventId": "cold-invite",
      "delayMillis": 0,
      "stage": "cleaned",
      "elapsedRealtimeNanos": "806136621462"
    }
  ]
};

export const capturedRecordRejection: VerifierDiagnostic = {
  "schemaVersion": 1,
  "rule": "telecom.record",
  "expected": {
    "callPrefix": true,
    "platformIdentity": true,
    "identityComma": true,
    "stateComma": true
  },
  "observed": {
    "callPrefix": true,
    "platformIdentity": false,
    "identityComma": false,
    "stateComma": true,
    "state": "DISCONNECTED",
    "indentation": 4,
    "length": 321
  },
  "lastColdStage": {
    "stage": "cleaned",
    "timestamp": "806136621462"
  },
  "platform": {
    "calls": null,
    "callNotifications": null,
    "processStart": {
      "clock": "android.elapsedRealtimeNanos",
      "timestamp": "804636000000"
    }
  },
  "lastJournalFailure": null,
  "priorFailure": {
    "rule": "telecom.record",
    "expected": {
      "callPrefix": true,
      "platformIdentity": true,
      "identityComma": true,
      "stateComma": true
    },
    "observed": {
      "callPrefix": true,
      "platformIdentity": false,
      "identityComma": false,
      "stateComma": true,
      "state": "DISCONNECTED",
      "indentation": 4,
      "length": 321
    }
  }
};
