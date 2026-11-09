import assert from "node:assert/strict";

import { androidStages } from "../example/client/journeys/android-results.ts";

function object(value: unknown): Record<string, unknown> {
  assert.ok(
    value && typeof value === "object" && !Array.isArray(value),
    "Missing evidence object"
  );
  return value as Record<string, unknown>;
}
function rows(value: unknown): Record<string, unknown>[] {
  assert.ok(Array.isArray(value), "Missing evidence rows");
  return value.map(object);
}

/** Validate app observations and independent API35 system evidence for the same run. */
export function assertAndroidJourney(
  app: unknown,
  native: unknown,
  runID: string
): void {
  const js = object(app),
    system = object(native);
  for (const result of [js, system]) {
    assert.equal(result.schemaVersion, 1);
    assert.equal(result.runID, runID, "Stale journey evidence");
    assert.equal(
      result.stage,
      "passed",
      String(result.failure ?? "Incomplete journey")
    );
    assert.equal(result.failure, undefined);
  }
  assert.equal(system.packageName, "com.example.expocallkittelecom");
  assert.equal(system.processName, system.packageName);
  assert.ok(Number.isSafeInteger(system.pid) && Number(system.pid) > 0);
  assert.equal(system.api, 35);
  assert.equal(js.previewJoin, "unsupported");
  assert.deepEqual(js.completed, [
    "incoming",
    "outgoing",
    "answeredElsewhere",
    "filtered",
  ]);
  assert.deepEqual(
    js.snapshots,
    androidStages.map(([step, status]) => ({
      step,
      id: `${runID}-${step.split(":")[0]}`,
      status,
      direction: step.startsWith("outgoing:") ? "outgoing" : "incoming",
    }))
  );
  const handler = [{ kind: "start", id: `${runID}-outgoing`, origin: "app" }];
  assert.deepEqual(js.handlers, handler, "Unexpected handler invocation");
  assert.deepEqual(
    js.deliveries,
    handler,
    "Nonplacement native handler delivery"
  );
  assert.deepEqual(
    js.cancelled,
    [],
    "Native placement was cancelled or expired"
  );
  assert.deepEqual(
    js.actions,
    [
      "incoming:answer",
      "incoming:hold",
      "incoming:end",
      "outgoing:start",
      "outgoing:end",
    ].map((value) => {
      const [name, kind] = value.split(":");
      return {
        id: `${runID}-${name}`,
        kind,
        origin: "app",
        ...(kind === "hold" ? { held: true } : {}),
      };
    })
  );
  const reports = ["incoming", "elsewhere", "filtered"].map((name) => ({
    id: `${runID}-${name}`,
    status: name === "filtered" ? "filtered" : "reported",
    ...(name === "filtered" ? { reason: "doNotDisturb" } : {}),
  }));
  assert.deepEqual(
    js.outcomes,
    reports.map((row) => ({ ...row, eventId: `${row.id}-push` }))
  );
  assert.deepEqual(js.elsewhere, [
    {
      conversationId: `${runID}-elsewhere`,
      serverCallId: `${runID}-elsewhere`,
      eventId: `${runID}-elsewhere-ended`,
    },
  ]);
  assert.deepEqual(
    js.terminals,
    ["incoming", "outgoing", "elsewhere", "filtered"].map((name) => ({
      id: `${runID}-${name}`,
      reason:
        name === "filtered"
          ? "failed"
          : name === "elsewhere"
          ? "answeredElsewhere"
          : "local",
      ...(name === "filtered"
        ? { failure: { kind: "filtered", cause: "doNotDisturb" } }
        : {}),
    }))
  );
  assert.deepEqual(
    js.lifecycle,
    ["incoming", "outgoing", "elsewhere"]
      .flatMap((name) =>
        [
          "ended",
          name === "elsewhere" ? "elsewhere" : "performedEnd",
          "removed",
        ].map((kind) => ({ id: `${runID}-${name}`, kind }))
      )
      .concat(
        ["ended", "removed"].map((kind) => ({ id: `${runID}-filtered`, kind }))
      )
  );
  assert.deepEqual(system.reports, reports);
  const checkpoints = rows(system.checkpoints);
  assert.equal(checkpoints.length, androidStages.length);
  const identities = new Map<string, string>();
  for (const [index, [step, status, telecom]] of androidStages.entries()) {
    const row = checkpoints[index]!;
    const name = step.split(":")[0]!;
    assert.equal(row.sequence, index + 1);
    assert.equal(row.step, step);
    assert.equal(row.id, `${runID}-${name}`);
    assert.equal(row.status, status);
    assert.equal(row.telecom, telecom);
    assert.equal(row.notifications, status === "absent" ? 0 : 1);
    assert.equal(
      row.notificationID,
      status === "absent" ? null : `${runID}-${name}`
    );
    assert.equal(row.mediaStarted, 0, "The example has native media disabled");
    assert.equal(
      row.warmups,
      name === "filtered" || name === "outgoing" ? 0 : 1
    );
    if (name === "filtered") assert.equal(row.platformID, null);
    else {
      assert.ok(
        typeof row.platformID === "string" && row.platformID.length > 0
      );
      const previous = identities.get(name);
      if (previous)
        assert.equal(row.platformID, previous, "Call identity changed");
      else {
        assert.ok(
          ![...identities.values()].includes(row.platformID),
          "Call identity reused"
        );
        identities.set(name, row.platformID);
      }
    }
  }
}
