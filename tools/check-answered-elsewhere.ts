import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

function object(value: unknown, field: string): Record<string, unknown> {
  assert.ok(
    value !== null && typeof value === "object" && !Array.isArray(value),
    `${field} must be an object`
  );
  return value as Record<string, unknown>;
}

function rows(value: unknown, field: string): Record<string, unknown>[] {
  assert.ok(Array.isArray(value), `${field} must be an array`);
  return value.map((row, index) => object(row, `${field}[${index}]`));
}

function number(value: unknown, field: string): number {
  assert.ok(
    typeof value === "number" && Number.isFinite(value),
    `${field} must be a finite number`
  );
  return value;
}

function integer(value: unknown, field: string): number {
  const result = number(value, field);
  assert.ok(Number.isInteger(result), `${field} must be an integer`);
  return result;
}

function exactInteger(value: unknown, field: string): bigint {
  assert.ok(
    typeof value === "bigint" ||
      (typeof value === "number" && Number.isSafeInteger(value)),
    `${field} must be an exact integer`
  );
  return BigInt(value);
}

function reviveInteger(
  _key: string,
  value: unknown,
  context?: { source?: string }
): unknown {
  // Native Long timestamps can exceed Number precision after prolonged uptime.
  if (
    typeof value === "number" &&
    Number.isInteger(value) &&
    !Number.isSafeInteger(value) &&
    context?.source &&
    /^-?\d+$/.test(context.source)
  )
    return BigInt(context.source);
  return value;
}

function string(value: unknown, field: string): string {
  assert.equal(typeof value, "string", `${field} must be a string`);
  return value as string;
}

function withinBudget(ms: number, budget: number, field: string): void {
  assert.ok(
    ms >= 0 && ms <= budget,
    `${field}: ${ms} ms must be within 0..${budget} ms`
  );
}

function oneDecimal(ms: number): string {
  // Exact .25 ties round down to the even tenth; toFixed already handles .75.
  // Other decimal ties are not exact in binary and retain toFixed's rounding.
  if (Number.isInteger(ms * 4) && (ms * 4) % 4 === 1)
    return (Math.floor(ms * 10) / 10).toFixed(1);
  return ms.toFixed(1);
}

/** Validate six native answered-elsewhere samples and return their timing report. */
export function assertAnsweredElsewhereJourney(value: unknown): string[] {
  const result = object(value, "Answered-elsewhere evidence");
  assert.equal(result.passed, true, "Answered-elsewhere native journey failed");
  assert.equal(result.nativeBudgetMs, 1000, "nativeBudgetMs must equal 1000");
  assert.equal(
    result.timelineTimestampFormat,
    "HH:mm:ss.SSS",
    "timelineTimestampFormat must equal HH:mm:ss.SSS"
  );
  for (const field of ["timelineTimeZone", "platformTimeZone"])
    assert.ok(
      string(result[field], field).length > 0,
      `${field} must not be empty`
    );
  const headsUpMinimumMs = integer(result.headsUpMinimumMs, "headsUpMinimumMs");
  assert.ok(headsUpMinimumMs >= 0, "headsUpMinimumMs must be nonnegative");
  assert.equal(
    result.uiObserverAllowanceMs,
    1000,
    "uiObserverAllowanceMs must equal 1000"
  );
  const uiBudgetMs = number(result.uiBudgetMs, "uiBudgetMs");
  assert.equal(
    uiBudgetMs,
    headsUpMinimumMs + 1000,
    "uiBudgetMs must equal headsUpMinimumMs + uiObserverAllowanceMs"
  );
  const samples = rows(result.samples, "samples");
  assert.equal(
    samples.length,
    6,
    "Exactly six answered-elsewhere samples are required"
  );
  assert.deepEqual(
    new Set(
      samples.map(
        (sample, index) =>
          `${string(sample.source, `samples[${index}].source`)}/${number(
            sample.sampleIndex,
            `samples[${index}].sampleIndex`
          )}`
      )
    ),
    new Set(["host/1", "host/2", "host/3", "push/1", "push/2", "push/3"]),
    "samples must contain host and push samples 1, 2 and 3 exactly once"
  );
  for (const field of ["eventId", "serverCallId"])
    assert.equal(
      new Set(
        samples.map((sample, index) =>
          string(sample[field], `samples[${index}].${field}`)
        )
      ).size,
      6,
      `samples must contain six distinct ${field} values`
    );

  let maximumNativeMs = 0;
  let maximumUiMs = 0;
  let maximumRecordedMs = 0;
  let maximumObservedMs = 0;
  const report: string[] = [];
  for (const sample of samples) {
    const label = `${sample.source}/${sample.sampleIndex}`;
    const n = (field: string): bigint =>
      exactInteger(sample[field], `${label}.${field}`);
    const before = object(sample.uiBefore, `${label}.uiBefore`);
    assert.equal(
      before.packageName,
      "com.android.systemui",
      `${label}.uiBefore.packageName must equal com.android.systemui`
    );
    const windowId = integer(before.windowId, `${label}.uiBefore.windowId`);
    assert.ok(windowId >= 0, `${label}.uiBefore.windowId must be nonnegative`);
    for (const field of ["callerVisible", "answerVisible", "declineVisible"])
      assert.equal(
        before[field],
        true,
        `${label}.uiBefore.${field} must be true`
      );
    assert.equal(
      sample.uiAbsenceObservations,
      2,
      `${label}.uiAbsenceObservations must equal 2`
    );
    assert.equal(
      sample.uiAbsentWindowId,
      windowId,
      `${label}.uiAbsentWindowId must match uiBefore.windowId`
    );
    assert.equal(
      sample.nativeEvidence,
      "telecom-historical-SET_DISCONNECTED",
      `${label}.nativeEvidence must be telecom-historical-SET_DISCONNECTED`
    );
    assert.equal(
      sample.nativeCode,
      3,
      `${label}.nativeCode: API35 transactional Core must use supported REMOTE (3)`
    );
    assert.equal(
      sample.nativeReason,
      "answeredElsewhere",
      `${label}.nativeReason must equal answeredElsewhere`
    );
    assert.equal(
      sample.correlatedEvents,
      1,
      `${label}.correlatedEvents must equal 1`
    );
    assert.equal(
      sample.endHandlerCalls,
      0,
      `${label}.endHandlerCalls must equal 0`
    );
    const start = n("signalNanos");
    assert.ok(
      n("incomingNanos") <= n("reportedNanos") &&
        n("reportedNanos") <= n("visibleBeforeNanos") &&
        n("visibleBeforeNanos") <= start,
      `${label}: incomingNanos <= reportedNanos <= visibleBeforeNanos <= signalNanos is required`
    );
    assert.ok(
      start <= n("terminalSnapshotNanos") &&
        n("terminalSnapshotNanos") <= n("eventNanos") &&
        n("eventNanos") <= n("emptyHookNanos"),
      `${label}: signalNanos <= terminalSnapshotNanos <= eventNanos <= emptyHookNanos is required`
    );
    assert.ok(
      n("acceptedNanos") >= start,
      `${label}.acceptedNanos must not precede signalNanos`
    );
    for (const field of [
      "terminalSnapshotNanos",
      "eventNanos",
      "emptyHookNanos",
      "notificationGoneNanos",
    ]) {
      const elapsedMs = Number(n(field) - start) / 1_000_000;
      withinBudget(elapsedMs, 1000, `${label}.${field}`);
      maximumNativeMs = Math.max(maximumNativeMs, elapsedMs);
    }
    const wall = exactInteger(
      sample.signalWallMillis,
      `${label}.signalWallMillis`
    );
    const disconnectedWall = exactInteger(
      sample.platformDisconnectWallMillis,
      `${label}.platformDisconnectWallMillis`
    );
    const recordedMs = Number(disconnectedWall - wall);
    withinBudget(recordedMs, 1000, `${label}: Recorded SET_DISCONNECTED`);
    maximumRecordedMs = Math.max(maximumRecordedMs, recordedMs);
    const gone = n("platformGoneNanos");
    const observed = n("platformDisconnectObservedNanos");
    const observedMs = Number(gone - start) / 1_000_000;
    assert.ok(
      start <= observed && observed <= gone,
      `${label}: signalNanos <= platformDisconnectObservedNanos <= platformGoneNanos is required`
    );
    withinBudget(
      observedMs,
      uiBudgetMs,
      `${label}: Tracked call removal observed`
    );
    maximumObservedMs = Math.max(maximumObservedMs, observedMs);

    const polls = rows(sample.platformPolls, `${label}.platformPolls`);
    assert.ok(polls.length > 0, `${label}: Missing Telecom poll evidence`);
    let previousReturn = start;
    for (const [index, poll] of polls.entries()) {
      const field = `${label}.platformPolls[${index}]`;
      const requested = exactInteger(
        poll.requestedNanos,
        `${field}.requestedNanos`
      );
      const returned = exactInteger(
        poll.returnedNanos,
        `${field}.returnedNanos`
      );
      assert.ok(
        previousReturn <= requested &&
          requested <= returned &&
          returned <= gone,
        `${field}: previous return <= requestedNanos <= returnedNanos <= platformGoneNanos is required`
      );
      assert.equal(
        typeof poll.trackedCall,
        "boolean",
        `${field}.trackedCall must be a boolean`
      );
      assert.equal(
        typeof poll.disconnectEventFound,
        "boolean",
        `${field}.disconnectEventFound must be a boolean`
      );
      if (poll.disconnectEventFound) {
        assert.equal(
          poll.remoteCodeFound,
          true,
          `${field}.remoteCodeFound must be true`
        );
        assert.equal(
          poll.answeredReasonFound,
          true,
          `${field}.answeredReasonFound must be true`
        );
        assert.equal(
          exactInteger(
            poll.recordedDisconnectWallMillis,
            `${field}.recordedDisconnectWallMillis`
          ),
          disconnectedWall,
          `${field}.recordedDisconnectWallMillis must match platformDisconnectWallMillis`
        );
        assert.equal(
          exactInteger(
            poll.recordedDisconnectElapsedMs,
            `${field}.recordedDisconnectElapsedMs`
          ),
          disconnectedWall - wall,
          `${field}.recordedDisconnectElapsedMs must match recorded SET_DISCONNECTED elapsed time`
        );
      }
      previousReturn = returned;
    }
    const finalPoll = polls.at(-1)!;
    assert.equal(
      finalPoll.trackedCall,
      false,
      `${label}: final Telecom poll must have no tracked call`
    );
    assert.equal(
      finalPoll.disconnectEventFound,
      true,
      `${label}: final Telecom poll must contain SET_DISCONNECTED`
    );
    assert.equal(
      exactInteger(finalPoll.returnedNanos, `${label}.finalPoll.returnedNanos`),
      gone,
      `${label}: final Telecom poll must return at platformGoneNanos`
    );
    assert.equal(
      exactInteger(
        polls.find((poll) => poll.disconnectEventFound)?.returnedNanos,
        `${label}.firstDisconnectPoll.returnedNanos`
      ),
      observed,
      `${label}: first SET_DISCONNECTED poll must return at platformDisconnectObservedNanos`
    );

    const uiGone = n("uiGoneNanos");
    const uiMs = Number(uiGone - start) / 1_000_000;
    withinBudget(uiMs, uiBudgetMs, `${label}.uiGoneNanos`);
    const firstAbsent = n("firstUiCallerAbsentNanos");
    assert.ok(
      start <= firstAbsent && firstAbsent <= uiGone,
      `${label}: signalNanos <= firstUiCallerAbsentNanos <= uiGoneNanos is required`
    );
    assert.ok(
      number(sample.uiScans, `${label}.uiScans`) >=
        number(sample.uiCompleteScans, `${label}.uiCompleteScans`) &&
        number(sample.uiCompleteScans, `${label}.uiCompleteScans`) >= 2,
      `${label}: uiScans >= uiCompleteScans >= 2 is required`
    );
    const maximumScan = n("maximumUiScanNanos");
    assert.ok(
      maximumScan >= 0,
      `${label}.maximumUiScanNanos must be nonnegative`
    );
    const scans = rows(sample.uiScanTimings, `${label}.uiScanTimings`);
    for (const [index, scan] of scans.entries()) {
      const field = `${label}.uiScanTimings[${index}]`;
      const requested = exactInteger(
        scan.requestedNanos,
        `${field}.requestedNanos`
      );
      const returned = exactInteger(
        scan.returnedNanos,
        `${field}.returnedNanos`
      );
      assert.ok(
        start <= requested && requested <= returned && returned <= uiGone,
        `${field}: signalNanos <= requestedNanos <= returnedNanos <= uiGoneNanos is required`
      );
      assert.ok(
        returned - requested <= maximumScan,
        `${field}: scan duration must not exceed maximumUiScanNanos`
      );
    }
    const finalScans = scans.slice(-2);
    assert.equal(
      finalScans.length,
      2,
      `${label}: two final UI scans are required`
    );
    for (const [index, scan] of finalScans.entries()) {
      const field = `${label}.finalUiScans[${index}]`;
      assert.equal(scan.sameWindow, true, `${field}.sameWindow must be true`);
      assert.equal(scan.complete, true, `${field}.complete must be true`);
      assert.equal(
        scan.callerPresent,
        false,
        `${field}.callerPresent must be false`
      );
    }
    maximumUiMs = Math.max(maximumUiMs, uiMs);
    report.push(
      `${label}: SET_DISCONNECTED recorded ${recordedMs} ms; event observed ${oneDecimal(
        Number(observed - start) / 1_000_000
      )} ms; tracked call removal observed ${oneDecimal(
        observedMs
      )} ms; visible ring removed ${oneDecimal(uiMs)} ms`
    );
  }
  report.push(
    `Six samples passed: app maximum ${oneDecimal(
      maximumNativeMs
    )} ms and recorded disconnect maximum ${maximumRecordedMs} ms (1000 ms budget); tracked removal observed maximum ${oneDecimal(
      maximumObservedMs
    )} ms; UI maximum ${oneDecimal(
      maximumUiMs
    )} ms (${uiBudgetMs} ms observation budget, configured heads-up minimum ${headsUpMinimumMs} ms)`
  );
  return report;
}

if (import.meta.main) {
  try {
    const file = process.argv[2];
    assert.ok(file, "Missing answered-elsewhere evidence path");
    const result: unknown = JSON.parse(
      readFileSync(file, "utf8"),
      reviveInteger
    );
    console.log(assertAnsweredElsewhereJourney(result).join("\n"));
  } catch (error) {
    if (error instanceof SyntaxError)
      console.error("Answered-elsewhere evidence is not valid JSON.");
    else console.error(error);
    process.exitCode = 1;
  }
}
