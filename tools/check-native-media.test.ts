import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { assertNativeMediaJourney } from "./check-native-media.ts";

function fixture(provider: "livekit" | "webrtc" = "livekit") {
  const nanos = (ms: number) => 10_000_000_000 + ms * 1_000_000;
  function sample(credentials: string) {
    const measurements: Record<string, number> = {};
    return {
      credentials,
      highPriority: true,
      pushNanos: nanos(0),
      reportedNanos: nanos(1),
      prewarmNanos: nanos(2),
      jsWarmNanos: nanos(2),
      prejoinedNanos: nanos(20),
      answerRequestedNanos: nanos(30),
      answerAcceptedNanos: nanos(31),
      audioAuthorizedNanos: nanos(40),
      firstAudioNanos: nanos(50),
      captureStartedNanos: nanos(41),
      playbackStartedNanos: nanos(10),
      remoteSubscribedNanos: nanos(11),
      decoderFirstPcmNanos: nanos(12),
      outputMutedNanos: nanos(13),
      outputFirstAudioNanos: nanos(51),
      answerToFirstAudioMs: 20,
      pcm: { frames: 480, peak: 128 },
      preanswerCapture: false,
      platformActiveCalls: 1,
      audioAuthority: { coreActive: 1, audioMode: 3, endpointObserved: 0 },
      events: [
        { stage: "mediaConnectStarted", nanos: nanos(3), measurements },
        ...(provider === "livekit" ? [1, 2] : [0]).map((role) => ({
          stage: "dtlsConnected",
          nanos: nanos(15),
          measurements: { role },
        })),
      ],
    };
  }
  const samples: [ReturnType<typeof sample>, ReturnType<typeof sample>] =
    provider === "livekit"
      ? [sample("push"), sample("fetcher")]
      : [sample("fetcher"), sample("livekitPush")];
  return {
    passed: true,
    provider,
    metric: "native-answer-request-to-first-remote-pcm",
    pcmThreshold: 128,
    budgetMs: 3000,
    targetMs: 300,
    profile: "optimized",
    samplesPerPath: 1,
    benchmarkOnly: false,
    samples,
    jsJoinHandlerCalls: 0,
    jsWarmRequests: 6,
    cleanup: [
      "hold-capture-stopped-resumed",
      "declined",
      "unanswered",
      "404",
      "410",
    ],
    declineFirstZeroObservation: { clients: 0, states: ["prejoined"] },
    declinePendingObserved: true,
    declineObservation: {
      clients: 0,
      states: ["prejoined", "declined"],
      terminalTimes: {
        receivedNanos: nanos(60),
        releasedNanos: nanos(70),
        recordedNanos: nanos(80),
      },
    },
    declineClosedNanos: nanos(65),
    declineTerminalSignal: {
      delivered: 1,
      httpErrorStatus: 0,
      nanos: nanos(81),
    },
  };
}

test("accepts both functional provider journeys and the unchanged inclusive PCM bound", () => {
  for (const provider of ["livekit", "webrtc"] as const) {
    const result = fixture(provider);
    assertNativeMediaJourney(result);
    const sample = result.samples[0];
    sample.firstAudioNanos = sample.answerRequestedNanos + 3_000_000_000;
    sample.answerToFirstAudioMs = 3000;
    sample.audioAuthority.endpointObserved = 1;
    assertNativeMediaJourney(result);
    sample.answerToFirstAudioMs += 0.0009;
    assertNativeMediaJourney(result);
  }
});

test("all functional ordering and warm-path controls remain fatal", () => {
  const changes: ((
    sample: ReturnType<typeof fixture>["samples"][0]
  ) => void)[] = [
    (s) => {
      s.highPriority = false;
    },
    (s) => {
      s.pushNanos = s.reportedNanos + 1;
    },
    (s) => {
      s.reportedNanos = s.prewarmNanos + 1;
    },
    (s) => {
      s.jsWarmNanos = s.reportedNanos - 1;
    },
    (s) => {
      s.prewarmNanos = s.prejoinedNanos + 1;
    },
    (s) => {
      s.prejoinedNanos = s.answerRequestedNanos + 1;
    },
    (s) => {
      s.answerAcceptedNanos = s.answerRequestedNanos - 1;
    },
    (s) => {
      s.audioAuthorizedNanos = s.answerAcceptedNanos - 1;
    },
    (s) => {
      s.firstAudioNanos = s.audioAuthorizedNanos - 1;
    },
    (s) => {
      s.captureStartedNanos = s.audioAuthorizedNanos - 1;
    },
    ...(
      [
        "playbackStartedNanos",
        "remoteSubscribedNanos",
        "decoderFirstPcmNanos",
        "outputMutedNanos",
      ] as const
    ).map((key) => (s: ReturnType<typeof fixture>["samples"][0]) => {
      s[key] = s.prejoinedNanos + 1;
    }),
    (s) => {
      s.outputFirstAudioNanos = s.audioAuthorizedNanos - 1;
    },
    (s) => {
      s.firstAudioNanos = s.answerRequestedNanos + 3_000_000_001;
      s.answerToFirstAudioMs = 3000.000001;
    },
    (s) => {
      s.answerToFirstAudioMs += 0.0011;
    },
    (s) => {
      s.pcm.frames = 0;
    },
    (s) => {
      s.pcm.peak = 127;
    },
    (s) => {
      s.preanswerCapture = true;
    },
    (s) => {
      s.platformActiveCalls = 0;
    },
    (s) => {
      s.audioAuthority.coreActive = 0;
    },
    (s) => {
      s.audioAuthority.audioMode = 0;
    },
    (s) => {
      s.audioAuthority.endpointObserved = 2;
    },
  ];
  for (const change of changes) {
    const result = fixture();
    change(result.samples[0]);
    assert.throws(() => assertNativeMediaJourney(result));
  }
});

test("unsafe audio, repeated connects, and missing or late role-specific DTLS fail", () => {
  for (const stage of [
    "captureBeforeAuthority",
    "outputBeforeAuthority",
    "outputObservationUnavailable",
    "mediaConnectStarted",
  ]) {
    const result = fixture();
    result.samples[0].events.push({ stage, nanos: 1, measurements: {} });
    assert.throws(() => assertNativeMediaJourney(result));
  }
  for (const provider of ["livekit", "webrtc"] as const) {
    for (const mode of ["missing", "late", "wrong-role"]) {
      const result = fixture(provider);
      const sample = result.samples[0];
      const event = sample.events.find(
        (event) => event.stage === "dtlsConnected"
      );
      assert.ok(event);
      if (mode === "missing")
        sample.events = sample.events.filter((value) => value !== event);
      if (mode === "late") event.nanos = sample.prejoinedNanos + 1;
      if (mode === "wrong-role") event.measurements.role = 99;
      assert.throws(() => assertNativeMediaJourney(result));
    }
  }
  const result = fixture();
  result.samples[0].events = result.samples[0].events.filter(
    (event) => event.stage !== "mediaConnectStarted"
  );
  assert.throws(() => assertNativeMediaJourney(result));
});

test("rejects incomplete results, changed bounds, and retired experiment profiles", () => {
  for (const patch of [
    { passed: false },
    { provider: "unknown" },
    { provider: null },
    { metric: "other" },
    { pcmThreshold: 127 },
    { budgetMs: 3001 },
    { benchmarkOnly: true },
    { profile: "baseline" },
    { samplesPerPath: 20 },
    { samplesPerPath: null },
    { jsJoinHandlerCalls: 1 },
    { jsWarmRequests: 5 },
    { samples: [] },
    { samples: null },
    { cleanup: ["ended"] },
    { cleanup: fixture().cleanup.filter((value) => value !== "404") },
  ])
    assert.throws(() => assertNativeMediaJourney({ ...fixture(), ...patch }));
  const result = fixture();
  result.samples[1].credentials = result.samples[0].credentials;
  assert.throws(() => assertNativeMediaJourney(result));
  assert.throws(() => assertNativeMediaJourney(null));
  const valid = fixture();
  for (const firstAudioNanos of ["100", null, undefined, NaN, Infinity])
    assert.throws(() =>
      assertNativeMediaJourney({
        ...valid,
        samples: [{ ...valid.samples[0], firstAudioNanos }, valid.samples[1]],
      })
    );
});

test("WebRTC transport closure never substitutes for independently acknowledged server receipt", () => {
  const changes: ((result: ReturnType<typeof fixture>) => void)[] = [
    (r) => {
      r.declineFirstZeroObservation.clients = 1;
    },
    (r) => {
      r.declineFirstZeroObservation.states.push("declined");
    },
    (r) => {
      r.declinePendingObserved = false;
    },
    (r) => {
      r.declineObservation.clients = 1;
    },
    (r) => {
      r.declineObservation.states = ["prejoined"];
    },
    (r) => {
      r.declineObservation.terminalTimes.receivedNanos = 0;
    },
    (r) => {
      r.declineObservation.terminalTimes.releasedNanos =
        r.declineObservation.terminalTimes.receivedNanos - 1;
    },
    (r) => {
      r.declineObservation.terminalTimes.recordedNanos =
        r.declineObservation.terminalTimes.releasedNanos - 1;
    },
    (r) => {
      r.declineTerminalSignal.delivered = 0;
    },
    (r) => {
      r.declineTerminalSignal.httpErrorStatus = 503;
    },
    (r) => {
      r.declineClosedNanos = 0;
    },
    (r) => {
      r.declineTerminalSignal.nanos = r.declineClosedNanos - 1;
    },
  ];
  for (const change of changes) {
    const result = fixture("webrtc");
    change(result);
    assert.throws(() => assertNativeMediaJourney(result));
  }
});

test("CLI prints the failed rule and assertion details, keeping parse errors brief", (t) => {
  const directory = mkdtempSync(join(tmpdir(), "native-media-check-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const file = join(directory, "result.json");
  const checker = fileURLToPath(
    new URL("./check-native-media.ts", import.meta.url)
  );
  const budget = fixture();
  budget.budgetMs = 3001;
  const order = fixture();
  order.samples[0].pushNanos = order.samples[0].reportedNanos + 1;
  const transport = fixture();
  transport.samples[0].events = transport.samples[0].events.filter(
    (event) => event.measurements.role !== 2
  );
  const measurement = fixture();
  const malformed = {
    ...measurement,
    samples: [
      { ...measurement.samples[0], firstAudioNanos: "invalid" },
      measurement.samples[1],
    ],
  };
  for (const [contents, rule] of [
    [JSON.stringify(fixture()), undefined],
    [JSON.stringify(budget), /budgetMs must equal 3000/],
    [
      JSON.stringify(order),
      /livekit sample\[0\]: pushNanos must not exceed reportedNanos/,
    ],
    [
      JSON.stringify(transport),
      /livekit sample\[0\]: DTLS role 2 must be ready by prejoinedNanos/,
    ],
    [
      JSON.stringify(malformed),
      /livekit sample\[0\]\.firstAudioNanos must be a finite number/,
    ],
    ["invalid JSON", /Native media evidence is not valid JSON\.\n/],
  ] as const) {
    writeFileSync(file, contents);
    const result = spawnSync(process.execPath, [checker, file], {
      encoding: "utf8",
      timeout: 15_000,
    });
    assert.equal(result.status, rule ? 1 : 0);
    if (rule) {
      assert.equal(result.stdout, "");
      assert.match(result.stderr, rule);
      if (contents.startsWith("invalid"))
        assert.equal(
          result.stderr,
          "Native media evidence is not valid JSON.\n"
        );
      else assert.match(result.stderr, /AssertionError/);
      if (contents === JSON.stringify(budget)) {
        assert.match(result.stderr, /actual: 3001/);
        assert.match(result.stderr, /expected: 3000/);
      }
    }
  }
});
