import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

function object(value: unknown, label: string): Record<string, unknown> {
  assert.ok(
    value !== null && typeof value === "object" && !Array.isArray(value),
    `${label} must be an object`
  );
  return value as Record<string, unknown>;
}

function rows(value: unknown, label: string): Record<string, unknown>[] {
  assert.ok(Array.isArray(value), `${label} must be an array`);
  return value.map((row, index) => object(row, `${label}[${index}]`));
}

function number(value: unknown, label: string): number {
  assert.ok(
    typeof value === "number" && Number.isFinite(value),
    `${label} must be a finite number`
  );
  return value;
}

function strings(value: unknown, label: string): string[] {
  assert.ok(
    Array.isArray(value) && value.every((item) => typeof item === "string"),
    `${label} must be an array of strings`
  );
  return value;
}

/** Checks functional native media evidence, independently of the producer's passed flag. */
export function assertNativeMediaJourney(value: unknown): void {
  const result = object(value, "result");
  assert.equal(result.passed, true, "passed must be true");
  assert.equal(
    result.metric,
    "native-answer-request-to-first-remote-pcm",
    "metric must identify native answer-to-PCM"
  );
  assert.equal(result.pcmThreshold, 128, "pcmThreshold must equal 128");
  assert.equal(result.budgetMs, 3000, "budgetMs must equal 3000");
  assert.equal(
    result.benchmarkOnly ?? false,
    false,
    "benchmarkOnly must be false"
  );
  assert.equal(
    result.samplesPerPath === undefined ? 1 : result.samplesPerPath,
    1,
    "samplesPerPath must equal 1"
  );
  assert.equal(result.profile, "optimized", "profile must be optimized");
  const provider = result.provider === undefined ? "livekit" : result.provider;
  assert.ok(
    provider === "livekit" || provider === "webrtc",
    "provider must be livekit or webrtc"
  );
  const sources =
    provider === "livekit" ? ["push", "fetcher"] : ["fetcher", "livekitPush"];
  const samples = rows(result.samples, "samples");
  assert.equal(
    samples.length,
    sources.length,
    "samples must contain both credential paths exactly once"
  );
  assert.deepEqual(
    new Set(samples.map((sample) => sample.credentials)),
    new Set(sources),
    "samples must use the provider's two credential paths"
  );
  for (const [index, sample] of samples.entries()) {
    const label: string = `${provider} sample[${index}]`;
    const rule = (message: string): string => `${label}: ${message}`;
    const n = (key: string): number => number(sample[key], `${label}.${key}`);
    const prejoined = n("prejoinedNanos");
    const requested = n("answerRequestedNanos");
    const authorized = n("audioAuthorizedNanos");
    assert.equal(sample.highPriority, true, rule("highPriority must be true"));
    assert.ok(
      n("pushNanos") <= n("reportedNanos"),
      rule("pushNanos must not exceed reportedNanos")
    );
    assert.ok(
      n("reportedNanos") <= Math.min(n("prewarmNanos"), n("jsWarmNanos")),
      rule("reportedNanos must precede prewarmNanos and jsWarmNanos")
    );
    assert.ok(
      n("prewarmNanos") <= prejoined && prejoined <= requested,
      rule("prewarmNanos <= prejoinedNanos <= answerRequestedNanos")
    );
    assert.ok(
      requested <= n("answerAcceptedNanos") &&
        n("answerAcceptedNanos") <= authorized &&
        authorized <= n("firstAudioNanos"),
      rule(
        "answerRequestedNanos <= answerAcceptedNanos <= audioAuthorizedNanos <= firstAudioNanos"
      )
    );
    assert.ok(
      n("captureStartedNanos") >= authorized,
      rule("captureStartedNanos must not precede audioAuthorizedNanos")
    );
    for (const key of [
      "playbackStartedNanos",
      "remoteSubscribedNanos",
      "decoderFirstPcmNanos",
      "outputMutedNanos",
    ])
      assert.ok(
        n(key) <= prejoined,
        rule(`${key} must not exceed prejoinedNanos`)
      );
    assert.ok(
      n("outputFirstAudioNanos") >= authorized,
      rule("outputFirstAudioNanos must not precede audioAuthorizedNanos")
    );
    const events = rows(sample.events, `${label}.events`);
    assert.ok(
      !events.some((event) =>
        [
          "captureBeforeAuthority",
          "outputBeforeAuthority",
          "outputObservationUnavailable",
        ].includes(String(event.stage))
      ),
      rule(
        "events must contain no unsafe capture, output or unavailable output observations"
      )
    );
    assert.equal(
      events.filter((event) => event.stage === "mediaConnectStarted").length,
      1,
      rule("events must contain exactly one mediaConnectStarted")
    );
    for (const role of provider === "livekit" ? [1, 2] : [0]) {
      assert.ok(
        events.some(
          (event) =>
            event.stage === "dtlsConnected" &&
            object(event.measurements, `${label}.dtlsConnected.measurements`)
              .role === role &&
            number(event.nanos, `${label}.dtlsConnected.nanos`) <= prejoined
        ),
        rule(`DTLS role ${role} must be ready by prejoinedNanos`)
      );
    }
    const elapsed = (n("firstAudioNanos") - requested) / 1_000_000;
    assert.ok(
      Math.abs(elapsed - n("answerToFirstAudioMs")) < 0.001,
      rule("answerToFirstAudioMs must match timestamps within 0.001 ms")
    );
    assert.ok(
      elapsed >= 0 && elapsed <= 3000,
      rule("answer-to-first-PCM elapsed time must be within 0..3000 ms")
    );
    const pcm = object(sample.pcm, `${label}.pcm`);
    assert.ok(
      number(pcm.frames, `${label}.pcm.frames`) > 0 &&
        number(pcm.peak, `${label}.pcm.peak`) >= 128,
      rule("PCM must have positive frames and peak >= 128")
    );
    assert.equal(
      sample.preanswerCapture,
      false,
      rule("preanswerCapture must be false")
    );
    assert.equal(
      sample.platformActiveCalls,
      1,
      rule("platformActiveCalls must equal 1")
    );
    const authority = object(sample.audioAuthority, `${label}.audioAuthority`);
    assert.equal(
      authority.coreActive,
      1,
      rule("audioAuthority.coreActive must equal 1")
    );
    assert.equal(
      authority.audioMode,
      3,
      rule("audioAuthority.audioMode must equal 3")
    );
    assert.ok(
      authority.endpointObserved === 0 || authority.endpointObserved === 1,
      rule("audioAuthority.endpointObserved must be 0 or 1")
    );
  }
  assert.equal(result.jsJoinHandlerCalls, 0, "jsJoinHandlerCalls must equal 0");
  assert.equal(
    result.jsWarmRequests,
    sources.length + 4,
    "jsWarmRequests must cover both paths and four cleanup calls"
  );
  assert.deepEqual(
    new Set(strings(result.cleanup, "cleanup")),
    new Set([
      "hold-capture-stopped-resumed",
      "declined",
      "unanswered",
      "404",
      "410",
    ]),
    "cleanup must cover hold/resume, declined, unanswered, 404 and 410"
  );
  if (provider === "webrtc") {
    const first = object(
      result.declineFirstZeroObservation,
      "declineFirstZeroObservation"
    );
    const receipt = object(result.declineObservation, "declineObservation");
    assert.equal(
      first.clients,
      0,
      "declineFirstZeroObservation.clients must equal 0"
    );
    assert.ok(
      !strings(first.states, "declineFirstZeroObservation.states").includes(
        "declined"
      ),
      "declineFirstZeroObservation must precede the declined receipt"
    );
    assert.equal(
      result.declinePendingObserved,
      true,
      "declinePendingObserved must be true"
    );
    assert.equal(receipt.clients, 0, "declineObservation.clients must equal 0");
    assert.ok(
      strings(receipt.states, "declineObservation.states").includes("declined"),
      "declineObservation must include the declined receipt"
    );
    const times = object(
      receipt.terminalTimes,
      "declineObservation.terminalTimes"
    );
    const n = (key: string): number =>
      number(times[key], `declineObservation.terminalTimes.${key}`);
    assert.ok(
      n("receivedNanos") > 0 &&
        n("receivedNanos") <= n("releasedNanos") &&
        n("releasedNanos") <= n("recordedNanos"),
      "decline receipt requires 0 < receivedNanos <= releasedNanos <= recordedNanos"
    );
    const acknowledgment = object(
      result.declineTerminalSignal,
      "declineTerminalSignal"
    );
    assert.equal(
      acknowledgment.delivered,
      1,
      "declineTerminalSignal.delivered must equal 1"
    );
    assert.equal(
      acknowledgment.httpErrorStatus,
      0,
      "declineTerminalSignal.httpErrorStatus must equal 0"
    );
    assert.ok(
      number(result.declineClosedNanos, "declineClosedNanos") > 0 &&
        number(acknowledgment.nanos, "declineTerminalSignal.nanos") >=
          number(result.declineClosedNanos, "declineClosedNanos"),
      "declineTerminalSignal.nanos must not precede positive declineClosedNanos"
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
  } catch (error) {
    if (error instanceof SyntaxError)
      console.error("Native media evidence is not valid JSON.");
    else console.error(error);
    process.exitCode = 1;
  }
}
