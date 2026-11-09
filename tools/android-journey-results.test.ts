import assert from "node:assert/strict";
import { test } from "node:test";

import { assertAndroidJourney } from "./android-journey-results.ts";

function fixture() {
  const stages = [
    ["incoming:ringing", "incoming", "ringing", "RINGING"],
    ["incoming:connected", "incoming", "connected", "ACTIVE"],
    ["incoming:held", "incoming", "held", "ON_HOLD"],
    ["incoming:removed", "incoming", "absent", "ABSENT"],
    ["outgoing:connected", "outgoing", "connected", "ACTIVE"],
    ["outgoing:removed", "outgoing", "absent", "ABSENT"],
    ["elsewhere:ringing", "elsewhere", "ringing", "RINGING"],
    ["elsewhere:removed", "elsewhere", "absent", "ABSENT"],
    ["filtered:removed", "filtered", "absent", "ABSENT"],
  ];
  const actions = [
    "incoming:answer",
    "incoming:hold",
    "incoming:end",
    "outgoing:start",
    "outgoing:end",
  ].map((value) => {
    const [name, kind] = value.split(":");
    return {
      id: `run-${name}`,
      kind,
      origin: "app",
      ...(kind === "hold" ? { held: true } : {}),
    };
  });
  return {
    app: {
      schemaVersion: 1,
      runID: "run",
      stage: "passed",
      previewJoin: "unsupported",
      snapshots: stages.map(([step, name, status]) => ({
        step,
        id: `run-${name}`,
        status,
        direction: name === "outgoing" ? "outgoing" : "incoming",
      })),
      handlers: [{ kind: "start", id: "run-outgoing", origin: "app" }],
      deliveries: [{ kind: "start", id: "run-outgoing", origin: "app" }],
      actions,
      outcomes: ["incoming", "elsewhere", "filtered"].map((name) => ({
        id: `run-${name}`,
        eventId: `run-${name}-push`,
        status: name === "filtered" ? "filtered" : "reported",
        ...(name === "filtered" ? { reason: "doNotDisturb" } : {}),
      })),
      elsewhere: [
        {
          conversationId: "run-elsewhere",
          serverCallId: "run-elsewhere",
          eventId: "run-elsewhere-ended",
        },
      ],
      cancelled: [],
      terminals: ["incoming", "outgoing", "elsewhere", "filtered"].map(
        (name) => ({
          id: `run-${name}`,
          reason: name === "elsewhere" ? "answeredElsewhere" : "local",
        })
      ),
      lifecycle: ["incoming", "outgoing", "elsewhere"]
        .flatMap((name) =>
          [
            "ended",
            name === "elsewhere" ? "elsewhere" : "performedEnd",
            "removed",
          ].map((kind) => ({ id: `run-${name}`, kind }))
        )
        .concat(
          ["ended", "removed"].map((kind) => ({ id: "run-filtered", kind }))
        ),
      completed: ["incoming", "outgoing", "answeredElsewhere", "filtered"],
    },
    native: {
      schemaVersion: 1,
      runID: "run",
      packageName: "com.example.expocallkittelecom",
      processName: "com.example.expocallkittelecom",
      pid: 1234,
      api: 35,
      stage: "passed",
      reports: ["incoming", "elsewhere", "filtered"].map((name) => ({
        id: `run-${name}`,
        status: name === "filtered" ? "filtered" : "reported",
        ...(name === "filtered" ? { reason: "doNotDisturb" } : {}),
      })),
      checkpoints: stages.map(([step, name, status, telecom], index) => ({
        sequence: index + 1,
        step,
        id: `run-${name}`,
        status,
        telecom,
        platformID: name === "filtered" ? null : `platform-${name}`,
        notifications: status === "absent" ? 0 : 1,
        notificationID: status === "absent" ? null : `run-${name}`,
        warmups: name === "filtered" || name === "outgoing" ? 0 : 1,
        mediaStarted: 0,
      })),
    },
  };
}

test("accepts real Android expectations with explicitly unsupported preview join", () => {
  const { app, native } = fixture();
  assertAndroidJourney(app, native, "run");
});

test("rejects missing, stale, reordered, failed and uncorrelated evidence", () => {
  const mutations: ((value: ReturnType<typeof fixture>) => void)[] = [
    (value) => {
      value.app.runID = "old";
    },
    (value) => {
      value.native.runID = "old";
    },
    (value) => {
      value.native.pid = 0;
    },
    (value) => {
      value.native.processName = "other.process";
    },
    (value) => {
      value.native.packageName = "other.app";
    },
    (value) => {
      value.native.api = 34;
    },
    (value) => {
      value.app.stage = "failed";
    },
    (value) => {
      value.native.stage = "failed";
    },
    (value) => {
      value.app.previewJoin = "passed";
    },
    (value) => {
      value.app.snapshots.splice(1, 1);
    },
    (value) => {
      value.app.actions.reverse();
    },
    (value) => {
      value.app.actions[1]!.held = false;
    },
    (value) => {
      value.app.deliveries.push({
        kind: "end",
        id: "run-incoming",
        origin: "app",
      });
    },
    (value) => {
      value.app.handlers.length = 0;
    },
    (value) => {
      value.app.outcomes[2]!.reason = "other";
    },
    (value) => {
      value.app.elsewhere[0]!.eventId = "wrong";
    },
    (value) => {
      value.app.terminals.length = 0;
    },
    (value) => {
      value.app.lifecycle.reverse();
    },
    (value) => {
      value.native.reports.length = 0;
    },
    (value) => {
      value.native.checkpoints.length = 0;
    },
    (value) => {
      value.native.checkpoints[1]!.sequence = 1;
    },
    (value) => {
      value.native.checkpoints[1]!.telecom = "RINGING";
    },
    (value) => {
      value.native.checkpoints[1]!.platformID = "unrelated";
    },
    (value) => {
      value.native.checkpoints[4]!.platformID = "platform-incoming";
    },
    (value) => {
      value.native.checkpoints[3]!.notifications = 1;
    },
    (value) => {
      value.native.checkpoints[0]!.notificationID = "unrelated";
    },
    (value) => {
      value.native.checkpoints[8]!.warmups = 1;
    },
    (value) => {
      value.native.checkpoints[8]!.mediaStarted = 1;
    },
  ];
  for (const mutate of mutations) {
    const value = fixture();
    mutate(value);
    assert.throws(
      () => assertAndroidJourney(value.app, value.native, "run"),
      mutate.toString()
    );
  }
});
