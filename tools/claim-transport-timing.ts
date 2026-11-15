import assert from "node:assert/strict";
import { outboundRtp, type EmulatorPacket } from "./emulator-packets.ts";

export interface ClockProbe {
  sequence: bigint;
  beforeNs: bigint;
  afterNs: bigint;
  fixtureBeforeNs: bigint;
  fixtureAtNs: bigint;
  fixtureAfterNs: bigint;
}
export interface TimeBounds { lowerNs: bigint; upperNs: bigint }
export interface ClaimTransportInput {
  packets: readonly EmulatorPacket[];
  capturedPackets: number;
  droppedPackets: number;
  probes: readonly ClockProbe[];
  prejoinStartedNs: bigint;
  answerRequestedNs: bigint;
  markerObservedNs: bigint;
  clockProbePort: number;
  mediaPorts: { udp: number; tcp: number };
}

function intersect(ranges: TimeBounds[]): TimeBounds {
  const lowerNs = ranges.reduce((v, r) => r.lowerNs > v ? r.lowerNs : v, ranges[0]!.lowerNs);
  const upperNs = ranges.reduce((v, r) => r.upperNs < v ? r.upperNs : v, ranges[0]!.upperNs);
  assert.ok(lowerNs <= upperNs, "Clock calibration intervals do not intersect");
  return { lowerNs, upperNs };
}

/** Bound first RTP using packet echoes and timestamps from the fixture process itself. */
export function firstClaimRtp(input: ClaimTransportInput) {
  assert.equal(input.droppedPackets, 0, "Capture drops prevent an exact first-packet claim");
  assert.equal(input.packets.length, input.capturedPackets, "Unsupported captured packets could hide earlier media");
  assert.equal(input.probes.length, 6, "Require three clock probes before and after Answer");
  assert.equal(new Set(input.probes.map(p => p.sequence)).size, 6, "Duplicate clock probe");
  assert.ok(input.prejoinStartedNs <= input.answerRequestedNs && input.answerRequestedNs < input.markerObservedNs);
  for (const port of [input.clockProbePort, input.mediaPorts.udp, input.mediaPorts.tcp])
    assert.ok(Number.isInteger(port) && port > 0 && port <= 65535, "Invalid capture port");
  assert.ok(input.clockProbePort !== input.mediaPorts.udp && input.clockProbePort !== input.mediaPorts.tcp);
  const resolutionNs = input.packets[0]?.timestampResolutionNs;
  assert.ok(resolutionNs === 1n || resolutionNs === 1000n, "Missing capture precision");
  const echoes = new Map<bigint, { sent?: bigint; received?: bigint }>();
  let previous: bigint | undefined;
  for (const p of input.packets) {
    assert.equal(p.timestampResolutionNs, resolutionNs, "Mixed capture precision");
    assert.ok(previous === undefined || p.capturedAtNs >= previous, "Capture clock moved backwards");
    previous = p.capturedAtNs;
    if (p.transport !== "udp" || (p.sourcePort !== input.clockProbePort && p.destinationPort !== input.clockProbePort) ||
        p.payload.length !== 16 || p.payload.subarray(0, 8).toString("ascii") !== "claimclk") continue;
    const sequence = p.payload.readBigUInt64BE(8), echo = echoes.get(sequence) ?? {};
    const field = p.destinationPort === input.clockProbePort ? "sent" : "received";
    assert.equal(echo[field], undefined, "Duplicate captured probe packet");
    echo[field] = p.capturedAtNs; echoes.set(sequence, echo);
  }
  const deviceRanges: TimeBounds[] = [], fixtureRanges: TimeBounds[] = [];
  for (const p of input.probes) {
    assert.ok(p.beforeNs <= p.afterNs && p.fixtureBeforeNs <= p.fixtureAfterNs, "Invalid probe bracket");
    const echo = echoes.get(p.sequence);
    assert.ok(echo?.sent !== undefined && echo.received !== undefined, "Missing captured clock probe");
    assert.ok(echo.sent <= echo.received, "Invalid captured echo order");
    deviceRanges.push({ lowerNs: p.beforeNs - echo.sent - resolutionNs, upperNs: p.afterNs - echo.received });
    // Device time minus fixture time. A different probe process's clock is never substituted here.
    fixtureRanges.push({ lowerNs: p.fixtureBeforeNs - p.fixtureAtNs, upperNs: p.fixtureAfterNs - p.fixtureAtNs });
  }
  assert.ok(input.probes.slice(0, 3).every(p => p.fixtureAfterNs <= input.prejoinStartedNs), "Capture probes must precede prejoin");
  assert.ok(input.probes.slice(3).every(p => p.beforeNs >= input.markerObservedNs), "Missing post-marker calibration");
  const deviceOffset = intersect(deviceRanges), fixtureOffset = intersect(fixtureRanges);
  const mapDevice = (first: bigint, complete: bigint): TimeBounds => ({
    lowerNs: first + deviceOffset.lowerNs, upperNs: complete + resolutionNs + deviceOffset.upperNs,
  });
  const media = [...outboundRtp(input.packets, input.mediaPorts.udp, input.mediaPorts.tcp)];
  const window = media.map(packet => ({ packet, device: mapDevice(packet.firstByteAtNs, packet.completeAtNs) }))
    .filter(({ device }) => device.upperNs >= input.prejoinStartedNs && device.lowerNs <= input.markerObservedNs);
  assert.ok(window.length > 0, "No outbound RTP in the Answer window");
  assert.ok(window.every(({ device }) => device.lowerNs >= input.answerRequestedNs), "RTP before Answer");
  const first = window.reduce((earliest, candidate) =>
    candidate.packet.firstByteAtNs < earliest.packet.firstByteAtNs ? candidate : earliest);
  return {
    transport: first.packet.transport, payloadType: first.packet.payloadType, resolutionNs,
    device: first.device,
    fixture: { lowerNs: first.device.lowerNs - fixtureOffset.upperNs, upperNs: first.device.upperNs - fixtureOffset.lowerNs },
    deviceOffset, fixtureOffset,
  };
}
