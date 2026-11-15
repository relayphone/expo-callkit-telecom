import assert from "node:assert/strict";
import { test } from "node:test";
import { firstClaimRtp, type ClaimTransportInput } from "./claim-transport-timing.ts";
import type { EmulatorPacket } from "./emulator-packets.ts";

function fixture(): ClaimTransportInput {
  const packets: EmulatorPacket[] = [];
  const probes = [1n, 2n, 3n, 5n, 6n, 7n].map(sequence => {
    const at = sequence * 1_000_000n;
    const payload = Buffer.alloc(16); payload.write("claimclk"); payload.writeBigUInt64BE(sequence, 8);
    for (const received of [false, true]) packets.push({ transport: "udp", capturedAtNs: at + (received ? 100n : 0n),
      timestampResolutionNs: 1n, sourceAddress: 1, destinationAddress: 2,
      sourcePort: received ? 17883 : 12345, destinationPort: received ? 12345 : 17883, payload });
    return { sequence, beforeNs: at + 49_900n, afterNs: at + 50_200n,
      fixtureBeforeNs: at + 50_200n, fixtureAtNs: at + 60_300n, fixtureAfterNs: at + 50_400n };
  });
  packets.push({ transport: "udp", capturedAtNs: 4_000_000n, timestampResolutionNs: 1n,
    sourceAddress: 1, destinationAddress: 2, sourcePort: 12346, destinationPort: 17882,
    payload: Buffer.from([0x80, 111, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]) });
  packets.sort((a, b) => a.capturedAtNs < b.capturedAtNs ? -1 : a.capturedAtNs > b.capturedAtNs ? 1 : 0);
  return { packets, probes, capturedPackets: packets.length, droppedPackets: 0, prejoinStartedNs: 3_250_000n,
    answerRequestedNs: 3_500_000n, markerObservedNs: 4_500_000n, clockProbePort: 17883, mediaPorts: { udp: 17882, tcp: 17881 } };
}

test("echo and fixture brackets bound RTP across different clock epochs", () => {
  const input = fixture(), result = firstClaimRtp(input);
  assert.deepEqual(result.device, { lowerNs: 4_049_899n, upperNs: 4_050_101n });
  assert.deepEqual(result.fixture, { lowerNs: 4_059_799n, upperNs: 4_060_201n });
  const offset = 1_000_000_000_000n;
  const shifted = firstClaimRtp({ ...input, probes: input.probes.map(p => ({ ...p, fixtureAtNs: p.fixtureAtNs + offset })) });
  assert.deepEqual(shifted.device, result.device);
  assert.deepEqual(shifted.fixture, { lowerNs: result.fixture.lowerNs + offset, upperNs: result.fixture.upperNs + offset });
});

test("drops and unsupported captured packets prevent a first-RTP claim", () => {
  const input = fixture();
  assert.throws(() => firstClaimRtp({ ...input, droppedPackets: 1 }), /Capture drops/);
  assert.throws(() => firstClaimRtp({ ...input, capturedPackets: input.capturedPackets + 1 }), /Unsupported captured/);
});

test("missing, duplicate, misplaced and inconsistent calibration remains fatal", () => {
  const input = fixture();
  assert.throws(() => firstClaimRtp({ ...input, probes: input.probes.slice(1) }), /three clock probes/);
  assert.throws(() => firstClaimRtp({ ...input, probes: input.probes.map((p, i) => i === 1 ? input.probes[0]! : p) }), /Duplicate/);
  assert.throws(() => firstClaimRtp({ ...input, packets: input.packets.slice(1), capturedPackets: input.capturedPackets - 1 }), /Missing captured/);
  assert.throws(() => firstClaimRtp({ ...input, probes: input.probes.map((p, i) => i === 5 ? { ...p, fixtureAtNs: p.fixtureAtNs + 1000n } : p) }), /do not intersect/);
  assert.throws(() => firstClaimRtp({ ...input, markerObservedNs: 6_000_000n }), /post-marker/);
});

test("pre-Answer RTP fails even when later RTP would pass", () => {
  const input = fixture();
  assert.throws(() => firstClaimRtp({ ...input, answerRequestedNs: 4_100_000n }), /RTP before Answer/);
});

test("capture probes must cover prejoin rather than starting only before Answer", () => {
  const input = fixture();
  assert.throws(() => firstClaimRtp({ ...input, prejoinStartedNs: 500_000n }), /precede prejoin/);
});

test("an earlier split TCP frame remains first when UDP completes ahead of it", () => {
  const input = fixture();
  const frame = Buffer.from([0, 12, 0x80, 111, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
  const tcp = (sequence: number, at: bigint, payload: Buffer, syn = false): EmulatorPacket => ({
    transport: "tcp", sequence, capturedAtNs: at, timestampResolutionNs: 1n, payload, syn,
    sourceAddress: 1, destinationAddress: 2, sourcePort: 12347, destinationPort: 17881,
  });
  const packets = [...input.packets, tcp(100, 3_600_000n, Buffer.alloc(0), true),
    tcp(101, 3_700_000n, frame.subarray(0, 5)), tcp(106, 4_100_000n, frame.subarray(5))]
    .sort((a, b) => a.capturedAtNs < b.capturedAtNs ? -1 : a.capturedAtNs > b.capturedAtNs ? 1 : 0);
  const result = firstClaimRtp({ ...input, packets, capturedPackets: packets.length });
  assert.equal(result.transport, "tcp");
  assert.deepEqual(result.device, { lowerNs: 3_749_899n, upperNs: 4_150_101n });
});

test("microsecond capture precision widens the uncertainty instead of inventing nanoseconds", () => {
  const input = fixture();
  const result = firstClaimRtp({ ...input, packets: input.packets.map(p => ({ ...p, timestampResolutionNs: 1000n })) });
  assert.deepEqual(result.device, { lowerNs: 4_048_900n, upperNs: 4_051_100n });
});
