import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { test } from "node:test";
import { detectionVector, repeatedVector } from "./fixtures/media-marker-vectors.ts";

// SDK createMarker("parity-v2"); twelve tones-v2 slots, phase reset and raised-cosine edges.
const frequencies = [650, 950, 1350, 1850, 1850, 1850, 950, 1350, 650, 650, 1850, 1350];
function samples(rate: number) {
  const slot = rate / 50 * 3;
  return Array.from({ length: slot * 12 }, (_, position) => {
    const local = position % slot;
    const fade = Math.min(1, Math.min(local, slot - local) / (rate / 200));
    const envelope = 0.5 * (1 - Math.cos(Math.PI * fade));
    return Math.round(32767 * Math.fround(0.2 * envelope *
      Math.sin(2 * Math.PI * frequencies[Math.floor(position / slot)]! * local / rate)));
  });
}
const pcm = samples(48_000);
const receiver = `
import array, asyncio, hashlib, importlib.util, json, math, random, sys, tempfile, types
spec = importlib.util.spec_from_file_location("fixture", "tools/native-media-fixture.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
async def check():
    pcm = json.load(sys.stdin)
    hashes = {str(rate): {
        kind: hashlib.sha256(fixture.TonesV2Signal("parity-v2", rate).pcm(
            rate * (1020 if emission else 720) // 1000, emission=emission)).hexdigest()
        for kind, emission in [("marker", False), ("emission", True)]}
        for rate in [8000, 48000]}
    receiver = fixture.MarkerObservation("parity-v2")
    receiver.pcm = pcm[:34559]
    receiver.frames = len(receiver.pcm)
    partial = (await receiver.measure())["markers"]
    receiver.pcm = pcm[:]
    receiver.frames = len(receiver.pcm)
    full = dict(await receiver.measure())
    receiver.pcm += [0] * 480 + pcm
    receiver.frames = len(receiver.pcm)
    tail = dict(await receiver.measure())
    receiver.pcm += [0] * 9600
    receiver.frames = len(receiver.pcm)
    post_roll = dict(await receiver.measure())
    truncated = fixture.MarkerObservation("parity-v2")
    truncated.pcm = list(array.array("h", fixture.TonesV2Signal("parity-v2", 48000).pcm(48480)))
    truncated.frames = len(truncated.pcm)
    truncated_coverage = (await truncated.measure())["markersWithPostRoll"]
    wrong = fixture.MarkerObservation("different-identity")
    wrong_id = wrong.analyze(pcm, wrong.frequencies)["markers"]
    damaged = [0 if 11520 <= i < 14400 else value for i, value in enumerate(pcm)]
    missing = receiver.analyze(damaged, receiver.frequencies)["markers"]
    central = [value if 960 <= i % 2880 < 1920 else 0 for i, value in enumerate(pcm)]
    central_only = receiver.analyze(central, receiver.frequencies)["markers"]
    zero = receiver.analyze([0] * len(pcm), receiver.frequencies)["markers"]
    # Synthetic receive timing changes preserve pitch; the emitted source stays canonical.
    def timing_capture(factor, central=False):
        length = round(2880 * factor)
        values = [0] * 7200
        for frequency in receiver.frequencies:
            for local in range(length):
                fade = min(1, min(local, length - local) / 240)
                value = .2 * .5 * (1 - math.cos(math.pi * fade)) * math.sin(2 * math.pi * frequency * local / 48000)
                values.append(math.floor(value * 32767 + .5) if not central or 960 <= local < 1920 else 0)
        return values + [0] * 9600
    elastic = {str(factor): receiver.analyze(timing_capture(factor), receiver.frequencies)
               for factor in [.96, 1.04, 1.08, 1.3]}
    cap_rejected = receiver.analyze(timing_capture(1.12, central=True), receiver.frequencies)
    aligned = fixture.MarkerObservation("parity-v2")
    aligned.pcm = timing_capture(1.08)
    aligned.frames = len(aligned.pcm)
    def stats(frame, inserted, removed):
        return {"frame": frame, "inbound": [{"inserted_samples_for_deceleration": inserted,
            "removed_samples_for_acceleration": removed, "concealed_samples": 5, "packets_lost": 0}]}
    aligned.stats = [stats(0, 100, 20), stats(aligned.frames, 157, 29)]
    aligned_receipt = dict(await aligned.measure())
    transport = fixture.MarkerObservation("parity-v2")
    repeated = list(array.array("h", fixture.TonesV2Signal("parity-v2", 48000).pcm(107520)))
    def counters(frame, lost, concealed, inserted, removed):
        return {"fromFrame": frame, "frame": frame, "inbound": [{"packets_lost": lost,
            "concealed_samples": concealed, "inserted_samples_for_deceleration": inserted,
            "removed_samples_for_acceleration": removed}]}
    transport.pcm = repeated[:50000]
    transport.frames = len(transport.pcm)
    transport.stats = [counters(0, 0, 0, 0, 0), counters(48960, 1, 480, 432, 96)]
    impaired = dict(await transport.measure())
    transport.pcm = repeated
    transport.frames = len(repeated)
    transport.stats += [counters(97920, 1, 480, 552, 120), counters(107520, 1, 480, 552, 120)]
    next_clean = dict(await transport.measure())
    unknown = fixture.MarkerObservation("parity-v2")
    unknown.pcm = repeated[:50000]
    unknown.frames = len(unknown.pcm)
    unavailable = dict(await unknown.measure())
    recovered = fixture.MarkerObservation("parity-v2")
    recovered.pcm = repeated[:50000]
    recovered.frames = len(recovered.pcm)
    recovered.stats = [counters(0,0,0,0,0),counters(20000,1,0,0,0),counters(48960,0,0,0,0)]
    recovered_loss = dict(await recovered.measure())
    recovered.stats = [counters(0,5,0,0,0),counters(20000,4,0,0,0),counters(48960,5,0,0,0)]
    new_loss_after_recovery = dict(await recovered.measure())
    snapshot = fixture.MarkerObservation("hooks-9:fromDevice")
    snapshot.pcm = list(array.array("h", fixture.TonesV2Signal(snapshot.identity, 48000).pcm(50000)))
    snapshot.frames = len(snapshot.pcm)
    original_pcm = snapshot.pcm[:]
    original_analyze = fixture.analysis_worker.analyze
    async def while_receiving(pcm, frequencies):
        result = await original_analyze(pcm, frequencies)
        snapshot.pcm = [0] * 144000
        snapshot.frames += 144000
        return result
    try:
        fixture.analysis_worker.analyze = while_receiving
        with tempfile.TemporaryDirectory() as folder:
            fixture.directory = fixture.Path(folder)
            await snapshot.measure()
            saved = next(fixture.directory.glob("media-evidence/*/50000.pcm16le")).read_bytes()
            saved_analysis = json.loads(next(fixture.directory.glob("media-evidence/*/50000.json")).read_text())
            snapshot_matches = saved == array.array("h", original_pcm).tobytes()
            snapshot_end = saved_analysis["windowEndSamples"]
    finally:
        fixture.directory = None
        fixture.analysis_worker.analyze = original_analyze
    noise = random.Random(42)
    noise_rejected = receiver.analyze([noise.randrange(-6553, 6554) for _ in pcm], receiver.frequencies)["markers"]
    tone_rejected = receiver.analyze([round(6553 * math.sin(2 * math.pi * 400 * i / 48000)) for i in range(len(pcm))], receiver.frequencies)["markers"]
    receiver.reset()
    reset = (await receiver.measure())["markers"]
    late = fixture.MarkerObservation("parity-v2")
    data = pcm + [0] * (192000 - len(pcm)) + pcm + [0] * 9600
    first = None
    class Stream:
        async def __aiter__(self):
            nonlocal first
            for start in range(0, len(data), 960):
                yield types.SimpleNamespace(frame=types.SimpleNamespace(sample_rate=48000,
                    num_channels=1, data=array.array("h", data[start:start + 960])))
                if start + 960 == 34560:
                    first = (await late.measure())["markers"]
        async def aclose(self):
            pass
    original = fixture.rtc.AudioStream
    try:
        fixture.rtc.AudioStream = lambda *args, **kwargs: Stream()
        await late.receive(None)
    finally:
        fixture.rtc.AudioStream = original
    late_markers = (await late.measure())["markers"]
    late.pcm = [0] * 144000
    late.frames += 144000
    remembered = (await late.measure())["markers"]
    result = dict(hashes=hashes, partial=partial, full=full, tail=tail, postRoll=post_roll,
                truncatedCoverage=truncated_coverage, first=first, centralOnly=central_only,
                wrongId=wrong_id, missing=missing, zero=zero, reset=reset,
                lateMarkers=late_markers, retained=len(late.pcm), remembered=remembered,
                elastic=elastic, capRejected=cap_rejected, alignedReceipt=aligned_receipt,
                impaired=impaired, nextClean=next_clean, unavailable=unavailable,
                recoveredLoss=recovered_loss,
                newLossAfterRecovery=new_loss_after_recovery,
                snapshotMatches=snapshot_matches, snapshotEnd=snapshot_end,
                noiseRejected=noise_rejected, toneRejected=tone_rejected)
    await fixture.analysis_worker.close()
    now = 0.0
    class Source:
        async def capture_frame(self, frame):
            nonlocal now
            now += .003
    async def pause(seconds):
        nonlocal now
        now += seconds
    await fixture.publish_frames(Source(), fixture.TonesV2Signal("parity-v2", 48000),
                                 clock=lambda: now, pause=pause, limit=100)
    result["publisherElapsed"] = now
    return result
print(json.dumps(asyncio.run(check())))
`;

test("the real fixture requires complete tones-v2 slots, detects short leaks and measures the final tail", () => {
  const result = JSON.parse(execFileSync(process.env.NATIVE_MEDIA_PYTHON ?? "python3", ["-c", receiver], {
    input: JSON.stringify(pcm), encoding: "utf8", timeout: 30_000, killSignal: "SIGKILL",
  }));
  assert.equal(result.full.markers, 1);
  assert.equal(result.full.profile, "tones-v2");
  assert.equal(result.full.slots.length, 12);
  assert.ok(result.full.minimumConfidence > 0.9999);
  assert.ok(result.full.slots.every((slot: { margin: number }) => slot.margin > 0.1999));
  assert.equal(result.tail.markers, 2);
  assert.equal(result.full.markersWithPostRoll, 0);
  assert.equal(result.postRoll.markersWithPostRoll, 2);
  assert.equal(result.truncatedCoverage, 0, "detector start uncertainty cannot hide a truncated post-roll");
  assert.equal(result.first, 1);
  assert.deepEqual(result.hashes, {
    "8000": {marker: "7305be4e1d4ba73d2f2f876869fa5cb27c12bb91450081452139f41262f7c003",
      emission: "e339576ee90e3d72ef65c7a5e44ab4a74345be472fdcd39b8f25072448a5fbab"},
    "48000": {marker: "82f79efb9f1b24a5c839cd500f5c9ad291ef5969348c3d68b775fcef6f8fa3b4",
      emission: "0a79ca1399b4fc018584bcf8e90be7c476618fab47f903b9670ce1282eafbaa1"},
  });
  assert.equal(result.centralOnly, 1);
  assert.equal(result.lateMarkers, 2, "late markers must stay observable during the existing five-second phase");
  assert.equal(result.retained, 144_000, "the capture remains capped at three seconds");
  assert.ok(Math.abs(result.publisherElapsed - 1) < 1e-9, "100 ten-millisecond frames must not accrue callback work time");
  for (const factor of ["0.96", "1.04", "1.08"]) {
    const detection = result.elastic[factor];
    assert.equal(detection.markers, 1, factor);
    assert.equal(detection.detections[0].slots.length, 12);
    assert.ok(detection.slots.every((slot: { confidence: number; driftMs: number }) =>
      slot.confidence >= .8 && Math.abs(slot.driftMs) <= 60));
  }
  assert.ok(result.elastic["1.08"].slots.some((slot: { driftMs: number }) => slot.driftMs !== 0));
  assert.equal(result.elastic["1.3"].markers, 0, "cumulative drift outside the cap must fail");
  assert.equal(result.capRejected.markers, 0);
  assert.ok(result.capRejected.driftCapRejected.minimumConfidence >= .8,
    "the cap control must clear every tone score and fail specifically on cumulative drift");
  assert.ok(Math.abs(result.capRejected.driftCapRejected.slots[11].driftMs) > 60);
  assert.equal(result.alignedReceipt.markersWithPostRoll, 1);
  assert.equal(result.alignedReceipt.cleanCyclesWithPostRoll, 1, "one clean cycle is sufficient");
  assert.deepEqual(result.alignedReceipt.phaseDetections[0].receiveStats.delta, {
    inserted_samples_for_deceleration: 57, removed_samples_for_acceleration: 9,
    concealed_samples: 0, packets_lost: 0,
  });
  assert.equal(result.noiseRejected, 0);
  assert.equal(result.impaired.markers, 1, "transport impairment must not hide a detected publication leak");
  assert.equal(result.impaired.cleanCyclesWithPostRoll, 0, "PLC/loss disqualify this cycle");
  assert.equal(result.impaired.firstCleanCycle, null);
  assert.equal(result.nextClean.cleanCyclesWithPostRoll, 1);
  assert.ok(result.nextClean.firstCleanCycle.offsetSamples > result.impaired.phaseDetections[0].endSamples);
  assert.equal(result.nextClean.cycles[0].receiveStats.status, "impaired");
  assert.equal(result.nextClean.firstCleanCycle.receiveStats.delta.inserted_samples_for_deceleration, 120);
  assert.equal(result.nextClean.firstCleanCycle.receiveStats.delta.removed_samples_for_acceleration, 24);
  assert.equal(result.unavailable.cleanCyclesWithPostRoll, 0, "missing transport counters are never clean");
  assert.equal(result.recoveredLoss.cleanCyclesWithPostRoll, 0, "late recovery cannot erase an observed loss spike");
  assert.equal(result.recoveredLoss.phaseDetections[0].receiveStats.delta.packets_lost, 0);
  assert.equal(result.recoveredLoss.phaseDetections[0].receiveStats.maximumIncrease.packets_lost, 1);
  assert.equal(result.newLossAfterRecovery.cleanCyclesWithPostRoll, 0);
  assert.equal(result.newLossAfterRecovery.phaseDetections[0].receiveStats.delta.packets_lost, 0);
  assert.equal(result.newLossAfterRecovery.phaseDetections[0].receiveStats.maximumIncrease.packets_lost, 0);
  assert.equal(result.newLossAfterRecovery.phaseDetections[0].receiveStats.positiveIncreases.packets_lost, 1);
  assert.ok(result.snapshotMatches, "persist the analyzed PCM, not the buffer that advanced during analysis");
  assert.equal(result.snapshotEnd, 50000);
  assert.equal(result.toneRejected, 0);
  assert.equal(result.remembered, 2, "a leak cannot disappear when older PCM rolls out");
  for (const control of ["partial", "wrongId", "missing", "zero", "reset"])
    assert.equal(result[control], 0, control);
});

test("path selection gives movement precedence and treats confidence sums within 1e-9 as ties", () => {
  const code = `import importlib.util
s=importlib.util.spec_from_file_location("fixture","tools/native-media-fixture.py")
f=importlib.util.module_from_spec(s);s.loader.exec_module(f)
def path(movement,total,starts): return f.MarkerPath(movement,total,.9,starts,(.9,)*len(starts))
assert f.prefer_marker_path(path(0,9,(0,60)),path(1,10,(0,59)))
assert not f.prefer_marker_path(path(1,10+5e-10,(0,61)),path(1,10,(0,59)))
assert f.prefer_marker_path(path(1,10+2e-9,(0,61)),path(1,10,(0,59)))
assert f.prefer_marker_path(path(1,10,(0,59,120)),path(1,10,(0,60,119)))`;
  execFileSync(process.env.NATIVE_MEDIA_PYTHON ?? "python3", ["-c", code], {timeout: 15_000, killSignal: "SIGKILL"});
});

test("both PCM16 little-endian parity vectors match the SDK", () => {
  for (const [rate, expected] of [
    [8000, "7305be4e1d4ba73d2f2f876869fa5cb27c12bb91450081452139f41262f7c003"],
    [48000, "82f79efb9f1b24a5c839cd500f5c9ad291ef5969348c3d68b775fcef6f8fa3b4"],
  ] as const) {
    const pcm = samples(rate);
    const bytes = Buffer.alloc(pcm.length * 2);
    pcm.forEach((value, index) => bytes.writeInt16LE(value, index * 2));
    assert.equal(createHash("sha256").update(bytes).digest("hex"), expected);
  }
});

test("the Python detector matches all six SDK splice hashes and exact slot drifts", () => {
  const inputs = [
    [8000, 40, "8cc156f34b514ad4f15d11420771678c3e0f1e443281e8d374ef86e70839370b"],
    [8000, -40, "cb31a159001ca9041fe6f1068bec30b77c3941ab467c39587616a4e140d8bdd5"],
    [8000, 80, "4e1daed9aa5681e48fe244f491ba5cea969ed971a06126cc8557275f49b7f124"],
    [48000, 40, "9df61e48608381ae9a50b8a297884397c72a18e603d3317926b0fb43d73b176a"],
    [48000, -40, "02030079b20fcd511fec09037b09d4852ff30fdcd9ca30f86f920d945e95a939"],
    [48000, 80, "62459596128b7f8e181c8ef1edd5a69039f37fdfa89a03e4f29e12d55aad7c14"],
  ] as const;
  const vectors = inputs.map(([rate, changeMs, sha]) => {
    const pcm = detectionVector(rate, changeMs), bytes = Buffer.alloc(pcm.length * 2);
    pcm.forEach((value, index) => bytes.writeInt16LE(value, index * 2));
    assert.equal(createHash("sha256").update(bytes).digest("hex"), sha);
    return {rate, changeMs, pcm};
  });
  const code = `import importlib.util,json,sys
s=importlib.util.spec_from_file_location("fixture","tools/native-media-fixture.py")
f=importlib.util.module_from_spec(s);s.loader.exec_module(f)
print(json.dumps([f.MarkerObservation.analyze(x["pcm"],f.TonesV2Signal("parity-v2",x["rate"]).frequencies,x["rate"]) for x in json.load(sys.stdin)]))`;
  const results = JSON.parse(execFileSync(process.env.NATIVE_MEDIA_PYTHON ?? "python3", ["-c", code], {
    input: JSON.stringify(vectors), encoding: "utf8", timeout: 30_000, killSignal: "SIGKILL",
  }));
  for (const [index, {changeMs}] of vectors.entries()) {
    const result = results[index];
    if (changeMs === 80) {
      assert.equal(result.markers, 0);
      assert.ok(result.driftCapRejected.minimumConfidence >= .8);
      assert.deepEqual(result.driftCapRejected.slots.map((slot: {driftMs: number}) => slot.driftMs),
        [0, 0, 10, 20, 30, 40, 50, 60, 60, 70, 70, 80]);
    } else {
      assert.equal(result.markers, 1);
      assert.deepEqual(result.slots.map((slot: {driftMs: number}) => slot.driftMs), changeMs > 0 ?
        [0, 0, 0, 10, 10, 10, 20, 20, 30, 30, 30, 33] : [0, 0, 0, -7, -7, -7, -7, -7, -7, -7, -7, -7]);
    }
  }
});

test("the Python detector matches all eight published repeated-tone path vectors", () => {
  const inputs = [
    [8000, "deleted", "dae143f33211f6fcf9617e2e4a99de5e00831e168adafa8a7b2d8fa041811a0b"],
    [8000, "wrong", "236233ac4ebee517862f86fda82eb5e9fe38b2168a42fd90df090ce837c0199f"],
    [8000, "silent", "2ec6a4f8d98a1a85d5aa0bb29bd5f707c0dfd7102a78190761da7e8d19ba1531"],
    [8000, "alias", "35770229b8db7382e249035c555d399962bcedf12cdcbd86d10367a1953a4b5e"],
    [48000, "deleted", "67c9aaf0dc8265d63d8981ad26e800673597647ccd0bedbfe04596ee73cb9bb0"],
    [48000, "wrong", "4afd4bfc1fe255d655c19391cc60f18e8ec95c91f04fef7a9bb059c28f9a34ea"],
    [48000, "silent", "2f7c12aecbb02fe41c280d382587dbd7f907cb433c08483b8bfef50fdd12673c"],
    [48000, "alias", "b94c47f58ff2975a232ef00c4f2b0b13f41b92aae53c077cfeb102d04737d612"],
  ] as const;
  const vectors = inputs.map(([rate, variant, sha]) => {
    const {id, pcm} = repeatedVector(rate, variant), bytes = Buffer.alloc(pcm.length * 2);
    pcm.forEach((value, i) => bytes.writeInt16LE(Math.round(value * 32767), i * 2));
    assert.equal(createHash("sha256").update(bytes).digest("hex"), sha);
    return {id, rate, variant, pcm: Array.from({length: pcm.length}, (_, i) => bytes.readInt16LE(i * 2))};
  });
  const code = `import importlib.util,json,sys
s=importlib.util.spec_from_file_location("fixture","tools/native-media-fixture.py")
f=importlib.util.module_from_spec(s);s.loader.exec_module(f)
print(json.dumps([f.MarkerObservation.analyze(x["pcm"],f.TonesV2Signal(x["id"],x["rate"]).frequencies,x["rate"]) for x in json.load(sys.stdin)]))`;
  const results = JSON.parse(execFileSync(process.env.NATIVE_MEDIA_PYTHON ?? "python3", ["-c", code], {
    input: JSON.stringify(vectors), encoding: "utf8", timeout: 30_000, killSignal: "SIGKILL",
  }));
  vectors.forEach(({variant}, index) => {
    const result = results[index];
    assert.equal(result.markers, variant === "deleted" ? 1 : 0);
    if (variant === "deleted") {
      assert.deepEqual(result.slots.map((slot: {driftMs: number}) => slot.driftMs), Array(12).fill(0));
      assert.ok(result.minimumConfidence > .99966);
    }
  });
});

for (const counterFormat of ["numeric", "Android decimal text"] as const) {
  test(`the device-output protocol preserves clean-cycle and phase isolation semantics with ${counterFormat} counters`, () => {
    const code = `import asyncio,base64,importlib.util,json,sys
s=importlib.util.spec_from_file_location("fixture","tools/native-media-fixture.py")
f=importlib.util.module_from_spec(s);s.loader.exec_module(f)
f.settings["deviceAuth"]="host-fixture"
class Request:
    match_info={"server_id":"hooks-7"}
    headers={"Authorization":"Bearer host-fixture"}
    def __init__(self,body): self.body=body
    async def json(self): return self.body
async def check():
    text_counters=${counterFormat === "Android decimal text" ? "True" : "False"}
    async def send(first,data,frame,loss,plc,reset=False,rate=48000):
        return await f.device_output(Request({"reset":reset,"sampleRate":rate,"firstFrame":first,
            "pcm16":base64.b64encode(data).decode(),"stats":{"fromFrame":frame,"frame":frame,
                "counters":{"packets_lost":loss,"concealed_samples":str(plc) if text_counters else plc,
                            "inserted_samples_for_deceleration":"0" if text_counters else 0,
                            "removed_samples_for_acceleration":"0" if text_counters else 0}}}))
    initial=await send(0,b"",0,0,0,True)
    initial=json.loads(initial.text)
    data=f.TonesV2Signal("hooks-7:toDevice",48000).pcm(107520)
    impaired=None
    for start in range(0,107520,4800):
        end=min(107520,start+4800)
        result=await send(start,data[start*2:end*2],end,1 if end>=43200 else 0,480 if end>=43200 else 0)
        assert result.status==200
        result=json.loads(result.text)
        if result["markers"] and not result["cleanCyclesWithPostRoll"]: impaired=result
    gap=await send(107521,b"",107520,1,480)
    unsupported=await send(107520,b"",107520,1,480,rate=8000)
    reset=await send(0,b"",0,0,0,True)
    reset=json.loads(reset.text)
    await f.analysis_worker.close()
    return {"impaired":impaired,"final":result,"gap":gap.status,"unsupported":unsupported.status,
            "generationChanged":initial["generation"]!=reset["generation"],"reset":reset}
print(json.dumps(asyncio.run(check())))`;
    const result = JSON.parse(
      execFileSync(process.env.NATIVE_MEDIA_PYTHON ?? "python3", ["-c", code], {
        encoding: "utf8",
        timeout: 30_000,
        killSignal: "SIGKILL",
      })
    );
    assert.equal(result.impaired.cleanCyclesWithPostRoll, 0);
    assert.equal(
      result.impaired.phaseDetections[0].receiveStats.status,
      "impaired"
    );
    assert.equal(result.final.cleanCyclesWithPostRoll, 1);
    assert.equal(result.final.firstCleanCycle.receiveStats.status, "clean");
    assert.equal(result.gap, 400);
    assert.equal(result.unsupported, 400);
    assert.ok(result.generationChanged);
    assert.equal(result.reset.cleanCyclesWithPostRoll, 0);
    assert.equal(result.reset.firstCleanCycle, null);
  });
}

test("device-output counter normalization preserves integer precision and rejects malformed counters", () => {
  const code = `import asyncio,base64,importlib.util,json
s=importlib.util.spec_from_file_location("fixture","tools/native-media-fixture.py")
f=importlib.util.module_from_spec(s);s.loader.exec_module(f)
f.settings["deviceAuth"]="host-fixture"
class Request:
    match_info={"server_id":"hooks-7"}
    headers={"Authorization":"Bearer host-fixture"}
    def __init__(self,body):self.body=body
    async def json(self):return self.body
async def check():
    data=f.TonesV2Signal("hooks-7:toDevice",48000).pcm(57600)
    async def phase(counter):
        async def send(first,pcm,frame,reset=False):
            result=await f.device_output(Request({"reset":reset,"sampleRate":48000,"firstFrame":first,
                "pcm16":base64.b64encode(pcm).decode(),"stats":{"fromFrame":frame,"frame":frame,
                "counters":{"packets_lost":0,"concealed_samples":counter,
                            "inserted_samples_for_deceleration":"0","removed_samples_for_acceleration":"0"}}}))
            assert result.status==200
            return json.loads(result.text)
        await send(0,b"",0,True)
        for start in range(0,57600,19200):
            result=await send(start,data[start*2:(start+19200)*2],start+19200)
        return result
    try:
        large=await phase("9007199254740993")
        exact=all(s["inbound"][0]["concealed_samples"]==9007199254740993 for s in large["rtc"])
        invalid=[]
        for value in [None,True,False,1.5,"1.5","not-a-count"," 1","1_000",{},[]]:
            result=await phase(value)
            invalid.append({"markers":result["markers"],"postRoll":result["markersWithPostRoll"],
                "clean":result["cleanCyclesWithPostRoll"],"first":result["firstCleanCycle"],
                "statuses":[c["receiveStats"]["status"] for c in result["phaseDetections"]],
                "unknown":all(s["inbound"][0]["concealed_samples"] is None for s in result["rtc"])})
        return {"largeClean":large["cleanCyclesWithPostRoll"],"exact":exact,"invalid":invalid}
    finally:await f.analysis_worker.close()
print(json.dumps(asyncio.run(check())))`;
  const result = JSON.parse(
    execFileSync(process.env.NATIVE_MEDIA_PYTHON ?? "python3", ["-c", code], {
      encoding: "utf8",
      timeout: 30_000,
      killSignal: "SIGKILL",
    })
  );
  assert.equal(result.largeClean, 1);
  assert.equal(result.exact, true);
  for (const invalid of result.invalid) {
    assert.ok(invalid.markers > 0);
    assert.ok(invalid.postRoll > 0);
    assert.equal(invalid.clean, 0);
    assert.equal(invalid.first, null);
    assert.ok(invalid.statuses.every((status: string) => status === "unknown"));
    assert.equal(invalid.unknown, true);
  }
});
