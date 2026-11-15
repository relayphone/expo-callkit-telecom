import assert from "node:assert/strict";

interface Packet {
  capturedAtNs: bigint;
  timestampResolutionNs: bigint;
  sourcePort: number;
  destinationPort: number;
  payload: Buffer;
  sourceAddress: number;
  destinationAddress: number;
}
export type EmulatorPacket = Packet & ({ transport: "udp" } |
  { transport: "tcp"; sequence: number; syn: boolean });

/** Read complete IPv4 UDP/TCP packets from Ethernet or Linux cooked PCAP streams. */
export function* emulatorPackets(bytes: Uint8Array): Generator<EmulatorPacket> {
  const capture = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  assert.ok(capture.length >= 24, "Truncated PCAP header");
  const magic = capture.readUInt32BE(0);
  const little = magic === 0xd4c3b2a1 || magic === 0x4d3cb2a1;
  const nanos = magic === 0xa1b23c4d || magic === 0x4d3cb2a1;
  assert.ok(little || magic === 0xa1b2c3d4 || nanos, "Unsupported PCAP format");
  const u16 = (offset: number) => little ? capture.readUInt16LE(offset) : capture.readUInt16BE(offset);
  const u32 = (offset: number) => little ? capture.readUInt32LE(offset) : capture.readUInt32BE(offset);
  assert.equal(u16(4), 2, "Unsupported PCAP major version");
  assert.equal(u16(6), 4, "Unsupported PCAP minor version");
  const linkType = u32(20);
  assert.ok([1, 113, 276].includes(linkType), "Unsupported PCAP link type");
  for (let cursor = 24; cursor < capture.length;) {
    assert.ok(cursor + 16 <= capture.length, "Truncated PCAP record header");
    const seconds = u32(cursor), fraction = u32(cursor + 4);
    const capturedSize = u32(cursor + 8), original = u32(cursor + 12);
    assert.ok(fraction < (nanos ? 1e9 : 1e6), "Invalid PCAP timestamp fraction");
    assert.equal(capturedSize, original, "Truncated captured packet");
    cursor += 16;
    assert.ok(cursor + capturedSize <= capture.length, "Truncated PCAP packet");
    const packet = capture.subarray(cursor, cursor + capturedSize);
    cursor += capturedSize;
    let ip = linkType === 1 ? 14 : linkType === 113 ? 16 : 20;
    assert.ok(packet.length >= ip, "Truncated link header");
    let type = packet.readUInt16BE(linkType === 1 ? 12 : linkType === 113 ? 14 : 0);
    if (linkType === 1 && type === 0x8100) {
      assert.ok(packet.length >= 18, "Truncated VLAN packet");
      type = packet.readUInt16BE(16); ip = 18;
    }
    if (type !== 0x0800) continue;
    assert.ok(packet.length >= ip + 20 && packet[ip]! >>> 4 === 4, "Invalid IPv4 header");
    const header = (packet[ip]! & 15) * 4;
    const length = packet.readUInt16BE(ip + 2);
    assert.ok(header >= 20 && length >= header && packet.length >= ip + length, "Truncated IPv4 packet");
    const protocol = packet[ip + 9];
    if (protocol !== 17 && protocol !== 6) continue;
    assert.equal(packet.readUInt16BE(ip + 6) & 0x3fff, 0, "Fragmented IP could hide an earlier RTP packet");
    const start = ip + header, size = length - header;
    const common = {
      capturedAtNs: BigInt(seconds) * 1_000_000_000n + BigInt(fraction) * (nanos ? 1n : 1000n),
      timestampResolutionNs: nanos ? 1n : 1000n,
      sourceAddress: packet.readUInt32BE(ip + 12), destinationAddress: packet.readUInt32BE(ip + 16),
    };
    assert.ok(size >= (protocol === 17 ? 8 : 20), "Truncated transport header");
    const ports = { sourcePort: packet.readUInt16BE(start), destinationPort: packet.readUInt16BE(start + 2) };
    if (protocol === 17) {
      assert.equal(packet.readUInt16BE(start + 4), size, "Invalid UDP length");
      yield { ...common, ...ports, transport: "udp", payload: packet.subarray(start + 8, ip + length) };
    } else {
      const header = (packet[start + 12]! >>> 4) * 4;
      assert.ok(header >= 20 && header <= size, "Invalid TCP header");
      yield { ...common, ...ports, transport: "tcp", payload: packet.subarray(start + header, ip + length),
        sequence: packet.readUInt32BE(start + 4), syn: (packet[start + 13]! & 2) !== 0 };
    }
  }
}

/** SRTP retains the RTP header; exclude STUN, DTLS and RTCP multiplexed on this port. */
export function isRtp(payload: Uint8Array): boolean {
  return payload.length >= 12 && (payload[0]! & 0xc0) === 0x80 &&
    (payload[1]! < 192 || payload[1]! > 223) && payload.length >= 12 + (payload[0]! & 15) * 4;
}

export interface RtpEgress {
  firstByteAtNs: bigint;
  completeAtNs: bigint;
  transport: "udp" | "tcp";
  payloadType: number;
}

/** Observe outbound SRTP, including RFC 4571 frames split/coalesced across ICE-TCP segments. */
export function* outboundRtp(packets: Iterable<EmulatorPacket>, udpPort: number, tcpPort: number): Generator<RtpEgress> {
  interface Stream { next: number; bytes: Buffer; spans: { end: number; at: bigint }[] }
  const streams = new Map<string, Stream>();
  for (const packet of packets) {
    if (packet.transport === "udp") {
      if (packet.destinationPort === udpPort && isRtp(packet.payload)) yield {
        firstByteAtNs: packet.capturedAtNs, completeAtNs: packet.capturedAtNs,
        transport: "udp", payloadType: packet.payload[1]! & 127,
      };
      continue;
    }
    if (packet.destinationPort !== tcpPort) continue;
    const key = [packet.sourceAddress, packet.sourcePort, packet.destinationAddress, packet.destinationPort].join(":");
    const sequence = (packet.sequence + Number(packet.syn)) >>> 0;
    if (packet.syn) {
      assert.ok(!streams.get(key)?.bytes.length, "TCP connection replaced an incomplete frame");
      streams.set(key, { next: sequence, bytes: Buffer.alloc(0), spans: [] });
    }
    if (packet.payload.length === 0) continue;
    const stream = streams.get(key);
    assert.ok(stream, "Missing TCP connection start");
    const gap = (sequence - stream.next) | 0;
    assert.ok(gap <= 0, "Missing TCP bytes could hide an earlier RTP frame");
    const payload = packet.payload.subarray(Math.min(-gap, packet.payload.length));
    if (payload.length === 0) continue;
    stream.next = (stream.next + payload.length) >>> 0;
    stream.bytes = Buffer.concat([stream.bytes, payload]);
    stream.spans.push({ end: stream.bytes.length, at: packet.capturedAtNs });
    while (stream.bytes.length >= 2) {
      const size = stream.bytes.readUInt16BE(0) + 2;
      if (stream.bytes.length < size) break;
      const frame = stream.bytes.subarray(2, size);
      if (isRtp(frame)) yield {
        firstByteAtNs: stream.spans.find(span => span.end > 2)!.at,
        completeAtNs: stream.spans.find(span => span.end >= size)!.at,
        transport: "tcp", payloadType: frame[1]! & 127,
      };
      stream.bytes = stream.bytes.subarray(size);
      stream.spans = stream.spans.filter(span => span.end > size).map(span => ({ ...span, end: span.end - size }));
    }
  }
  assert.ok([...streams.values()].every(stream => stream.bytes.length === 0), "Incomplete captured ICE-TCP frame");
}
