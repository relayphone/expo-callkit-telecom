import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

function object(value: unknown): Record<string, unknown> {
  assert.ok(
    value !== null && typeof value === "object" && !Array.isArray(value),
    "Missing media evidence object"
  );
  return value as Record<string, unknown>;
}

function rows(value: unknown): Record<string, unknown>[] {
  assert.ok(Array.isArray(value), "Missing media evidence rows");
  return value.map(object);
}

function number(value: unknown): number {
  assert.ok(
    typeof value === "number" && Number.isFinite(value),
    "Invalid media measurement"
  );
  return value;
}

function strings(value: unknown): string[] {
  assert.ok(
    Array.isArray(value) && value.every((item) => typeof item === "string"),
    "Missing media labels"
  );
  return value;
}

/** Checks functional native media evidence, independently of the producer's passed flag. */
export function assertNativeMediaJourney(value: unknown): void {
  const result = object(value);
  assert.equal(result.passed, true);
  assert.equal(result.metric, "native-answer-request-to-first-remote-pcm");
  assert.equal(result.pcmThreshold, 128);
  assert.equal(result.budgetMs, 3000);
  assert.equal(result.benchmarkOnly ?? false, false);
  assert.equal(
    result.samplesPerPath === undefined ? 1 : result.samplesPerPath,
    1
  );
  assert.equal(result.profile, "optimized");
  const provider = result.provider === undefined ? "livekit" : result.provider;
  assert.ok(provider === "livekit" || provider === "webrtc");
  const sources =
    provider === "livekit" ? ["push", "fetcher"] : ["fetcher", "livekitPush"];
  const samples = rows(result.samples);
  assert.equal(samples.length, sources.length);
  assert.deepEqual(
    new Set(samples.map((sample) => sample.credentials)),
    new Set(sources)
  );
  for (const sample of samples) {
    const n = (key: string) => number(sample[key]);
    const prejoined = n("prejoinedNanos");
    const requested = n("answerRequestedNanos");
    const authorized = n("audioAuthorizedNanos");
    assert.equal(sample.highPriority, true);
    assert.ok(n("pushNanos") <= n("reportedNanos"));
    assert.ok(
      n("reportedNanos") <= Math.min(n("prewarmNanos"), n("jsWarmNanos"))
    );
    assert.ok(n("prewarmNanos") <= prejoined && prejoined <= requested);
    assert.ok(
      requested <= n("answerAcceptedNanos") &&
        n("answerAcceptedNanos") <= authorized &&
        authorized <= n("firstAudioNanos")
    );
    assert.ok(n("captureStartedNanos") >= authorized);
    for (const key of [
      "playbackStartedNanos",
      "remoteSubscribedNanos",
      "decoderFirstPcmNanos",
      "outputMutedNanos",
    ])
      assert.ok(n(key) <= prejoined);
    assert.ok(n("outputFirstAudioNanos") >= authorized);
    const events = rows(sample.events);
    assert.ok(
      !events.some((event) =>
        [
          "captureBeforeAuthority",
          "outputBeforeAuthority",
          "outputObservationUnavailable",
        ].includes(String(event.stage))
      )
    );
    assert.equal(
      events.filter((event) => event.stage === "mediaConnectStarted").length,
      1
    );
    for (const role of provider === "livekit" ? [1, 2] : [0]) {
      assert.ok(
        events.some(
          (event) =>
            event.stage === "dtlsConnected" &&
            object(event.measurements).role === role &&
            number(event.nanos) <= prejoined
        ),
        "Missing prejoined transport role"
      );
    }
    const elapsed = (n("firstAudioNanos") - requested) / 1_000_000;
    assert.ok(Math.abs(elapsed - n("answerToFirstAudioMs")) < 0.001);
    assert.ok(elapsed >= 0 && elapsed <= 3000);
    const pcm = object(sample.pcm);
    assert.ok(number(pcm.frames) > 0 && number(pcm.peak) >= 128);
    assert.equal(sample.preanswerCapture, false);
    assert.equal(sample.platformActiveCalls, 1);
    const authority = object(sample.audioAuthority);
    assert.equal(authority.coreActive, 1);
    assert.equal(authority.audioMode, 3);
    assert.ok(
      authority.endpointObserved === 0 || authority.endpointObserved === 1
    );
  }
  assert.equal(result.jsJoinHandlerCalls, 0);
  assert.equal(result.jsWarmRequests, sources.length + 4);
  assert.deepEqual(
    new Set(strings(result.cleanup)),
    new Set([
      "hold-capture-stopped-resumed",
      "declined",
      "unanswered",
      "404",
      "410",
    ])
  );
  if (provider === "webrtc") {
    const first = object(result.declineFirstZeroObservation);
    const receipt = object(result.declineObservation);
    assert.equal(first.clients, 0);
    assert.ok(!strings(first.states).includes("declined"));
    assert.equal(result.declinePendingObserved, true);
    assert.equal(receipt.clients, 0);
    assert.ok(strings(receipt.states).includes("declined"));
    const times = object(receipt.terminalTimes);
    assert.ok(
      number(times.receivedNanos) > 0 &&
        number(times.receivedNanos) <= number(times.releasedNanos) &&
        number(times.releasedNanos) <= number(times.recordedNanos)
    );
    const acknowledgment = object(result.declineTerminalSignal);
    assert.equal(acknowledgment.delivered, 1);
    assert.equal(acknowledgment.httpErrorStatus, 0);
    assert.ok(
      number(result.declineClosedNanos) > 0 &&
        number(acknowledgment.nanos) >= number(result.declineClosedNanos)
    );
  }
}

if (import.meta.main) {
  try {
    const file = process.argv[2];
    assert.ok(file, "Missing media evidence path");
    const result: unknown = JSON.parse(readFileSync(file, "utf8"));
    assertNativeMediaJourney(result);
    console.log("Native media ordering, audio and cleanup evidence passed.");
  } catch {
    // Invalid JSON or assertion values may contain untrusted content.
    console.error("Native media evidence failed validation.");
    process.exitCode = 1;
  }
}
