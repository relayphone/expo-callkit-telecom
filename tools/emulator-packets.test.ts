import assert from "node:assert/strict";
import { test } from "node:test";
import { emulatorPackets, isRtp, outboundRtp, type EmulatorPacket } from "./emulator-packets.ts";

function fixture(little = true, nanos = false, vlan = false) {
  const ip = vlan ? 18 : 14;
  const packet = Buffer.alloc(ip + 20 + 8 + 16);
  packet.writeUInt16BE(vlan ? 0x8100 : 0x0800, 12);
  if (vlan) packet.writeUInt16BE(0x0800, 16);
  packet[ip] = 0x45; packet[ip + 9] = 17;
  packet.writeUInt16BE(44, ip + 2);
  packet.writeUInt16BE(12345, ip + 20); packet.writeUInt16BE(17882, ip + 22);
  packet.writeUInt16BE(24, ip + 24);
  packet[ip + 28] = 0x80; packet[ip + 29] = 111;
  const capture = Buffer.alloc(40 + packet.length);
  const u16 = (value: number, offset: number) => little ? capture.writeUInt16LE(value, offset) : capture.writeUInt16BE(value, offset);
  const u32 = (value: number, offset: number) => little ? capture.writeUInt32LE(value, offset) : capture.writeUInt32BE(value, offset);
  u32(nanos ? 0xa1b23c4d : 0xa1b2c3d4, 0); u16(2, 4); u16(4, 6);
  u32(65535, 16); u32(1, 20); u32(7, 24); u32(123456, 28);
  u32(packet.length, 32); u32(packet.length, 36); packet.copy(capture, 40);
  return { capture, ip: 40 + ip };
}

test("emulator capture preserves timestamp precision and UDP direction in either byte order", () => {
  for (const little of [false, true]) for (const nanos of [false, true]) for (const vlan of [false, true]) {
    const { capture } = fixture(little, nanos, vlan);
    const records = [...emulatorPackets(capture)];
    assert.equal(records.length, 1);
    assert.equal(records[0]!.capturedAtNs, 7_000_000_000n + 123456n * (nanos ? 1n : 1000n));
    assert.equal(records[0]!.timestampResolutionNs, nanos ? 1n : 1000n);
    assert.equal(records[0]!.sourcePort, 12345);
    assert.equal(records[0]!.destinationPort, 17882);
    assert.equal(records[0]!.payload.length, 16);
    assert.equal(isRtp(records[0]!.payload), true);
  }
});

test("capture truncation and inconsistent lengths fail instead of hiding an earlier packet", () => {
  const { capture, ip } = fixture();
  for (const end of [0, 23, 25, 39, capture.length - 1])
    assert.throws(() => [...emulatorPackets(capture.subarray(0, end))]);
  for (const [offset, value] of [[32, 1], [36, 1000], [20, 101], [28, 1_000_000]] as const) {
    const invalid = Buffer.from(capture); invalid.writeUInt32LE(value, offset);
    assert.throws(() => [...emulatorPackets(invalid)]);
  }
  const invalid = Buffer.from(capture); invalid.writeUInt16BE(1000, ip + 24);
  assert.throws(() => [...emulatorPackets(invalid)]);
});

test("Linux cooked captures decode the same UDP packet without Ethernet addresses", () => {
  for (const [linkType, linkSize, protocolOffset] of [[113, 16, 14], [276, 20, 0]] as const) {
    const { capture } = fixture();
    const packet = Buffer.concat([Buffer.alloc(linkSize), capture.subarray(54)]);
    packet.writeUInt16BE(0x0800, protocolOffset);
    const converted = Buffer.concat([capture.subarray(0, 40), packet]);
    converted.writeUInt32LE(linkType, 20);
    converted.writeUInt32LE(packet.length, 32); converted.writeUInt32LE(packet.length, 36);
    const record = [...emulatorPackets(converted)][0]!;
    assert.equal(record.transport, "udp"); assert.equal(record.destinationPort, 17882);
    assert.equal(isRtp(record.payload), true);
  }
});

test("fragmented IP fails closed and unrelated IP protocols are skipped", () => {
  for (const fragment of [1, 0x2000]) {
    const { capture, ip } = fixture(); capture.writeUInt16BE(fragment, ip + 6);
    assert.throws(() => [...emulatorPackets(capture)], /Fragmented IP/);
  }
  const { capture, ip } = fixture(); capture[ip + 9] = 1;
  assert.deepEqual([...emulatorPackets(capture)], []);
});

function tcp(sequence: number, payload = Buffer.alloc(0), at = 0n, syn = false): EmulatorPacket {
  return { sourceAddress: 1, destinationAddress: 2, sourcePort: 12345, destinationPort: 17881,
    transport: "tcp", sequence, payload, capturedAtNs: at, timestampResolutionNs: 1n, syn };
}
const rtp = Buffer.from([0x80, 111, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
const framed = Buffer.concat([Buffer.from([0, rtp.length]), rtp]);

test("captured TCP headers feed framed RTP observation through the real decoder", () => {
  const header = fixture().capture.subarray(0, 24);
  const records = [Buffer.alloc(0), framed].map((payload, index) => {
    const packet = Buffer.alloc(14 + 20 + 20 + payload.length);
    packet.writeUInt16BE(0x0800, 12); packet[14] = 0x45; packet[23] = 6;
    packet.writeUInt16BE(40 + payload.length, 16);
    packet.writeUInt16BE(12345, 34); packet.writeUInt16BE(17881, 36);
    packet.writeUInt32BE(100 + index, 38); packet[46] = 0x50; packet[47] = index === 0 ? 2 : 0x18;
    payload.copy(packet, 54);
    const record = Buffer.alloc(16); record.writeUInt32LE(index + 1, 0);
    record.writeUInt32LE(packet.length, 8); record.writeUInt32LE(packet.length, 12);
    return Buffer.concat([record, packet]);
  });
  const packets = [...emulatorPackets(Buffer.concat([header, ...records]))];
  assert.deepEqual([...outboundRtp(packets, 17882, 17881)], [{ firstByteAtNs: 2_000_000_000n,
    completeAtNs: 2_000_000_000n, transport: "tcp", payloadType: 111 }]);
});

test("ICE-TCP framing keeps first and complete egress timestamps across split and coalesced segments", () => {
  const two = Buffer.concat([framed, framed]);
  const result = [...outboundRtp([tcp(100, Buffer.alloc(0), 0n, true),
    tcp(101, two.subarray(0, 5), 10n), tcp(106, two.subarray(5), 20n)], 17882, 17881)];
  assert.deepEqual(result, [{ firstByteAtNs: 10n, completeAtNs: 20n, transport: "tcp", payloadType: 111 },
    { firstByteAtNs: 20n, completeAtNs: 20n, transport: "tcp", payloadType: 111 }]);
});

test("TCP retransmissions and sequence wrap cannot duplicate or postpone first RTP", () => {
  const start = 0xfffffff9;
  const result = [...outboundRtp([tcp(start - 1, Buffer.alloc(0), 0n, true),
    tcp(start, framed.subarray(0, 8), 10n), tcp(start, framed.subarray(0, 8), 15n),
    tcp(1, framed.subarray(8), 20n), tcp(start, framed, 30n)], 17882, 17881)];
  assert.deepEqual(result, [{ firstByteAtNs: 10n, completeAtNs: 20n, transport: "tcp", payloadType: 111 }]);
});

test("missing TCP bytes, capture start and unfinished frames fail rather than report a later first RTP", () => {
  assert.throws(() => [...outboundRtp([tcp(100, framed)], 17882, 17881)], /connection start/);
  assert.throws(() => [...outboundRtp([tcp(99, Buffer.alloc(0), 0n, true), tcp(101, framed)], 17882, 17881)], /Missing TCP bytes/);
  assert.throws(() => [...outboundRtp([tcp(99, Buffer.alloc(0), 0n, true), tcp(100, framed.subarray(0, 5))], 17882, 17881)], /Incomplete/);
});

test("RTP recognition excludes STUN, DTLS, RTCP and truncated CSRC headers", () => {
  for (const [first, second, length] of [[0, 1, 20], [23, 254, 20], [0x80, 200, 20],
    [0x80, 111, 11], [0x8f, 111, 20]] as const) {
    const packet = new Uint8Array(length); packet[0] = first!; packet[1] = second!;
    assert.equal(isRtp(packet), false);
  }
});
