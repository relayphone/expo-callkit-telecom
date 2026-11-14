const frequencies = [650, 950, 1350, 1850, 1850, 1850, 950, 1350, 650, 650, 1850, 1350];

// SDK amendment 2a: splice at original slot+45 ms, outside the central scoring window.
export function emissionVector(rate: number, tones: readonly number[] = frequencies): Float32Array {
  const pre = rate * 3 / 20, slot = rate * 3 / 50;
  return Float32Array.from({length: rate / 50 * 51}, (_, position) => {
    const marker = position >= pre && position < pre + 12 * slot;
    const local = marker ? (position - pre) % slot : position < pre ? position : position - pre - 12 * slot;
    const edge = marker ? Math.min(local, slot - local) : position < pre ? local : rate / 50 * 51 - position;
    const frequency = marker ? tones[Math.floor((position - pre) / slot)]! : 400;
    return .2 * .5 * (1 - Math.cos(Math.PI * Math.min(1, edge / (rate / 200)))) *
      Math.sin(2 * Math.PI * frequency * local / rate);
  });
}

export function detectionVector(rate: number, changeMs: number): number[] {
  const pre = rate * 3 / 20, slot = rate * 3 / 50;
  let pcm = emissionVector(rate);
  let shift = 0;
  const count = rate / 100, fade = rate / 400;
  for (const edited of changeMs === 80 ? [1, 2, 3, 4, 5, 6, 8, 10] : [2, 5, 8, 10]) {
    const position = pre + edited * slot + rate * 9 / 200 + shift;
    const next = new Float32Array(pcm.length + (changeMs > 0 ? count : -count));
    next.set(pcm.subarray(0, position));
    if (changeMs > 0) {
      next.set(pcm.subarray(position - count, position), position);
      next.set(pcm.subarray(position), position + count);
    } else next.set(pcm.subarray(position + count), position);
    for (let i = 0; i < fade; i++) {
      const weight = i / (fade - 1);
      next[position + i] = (1 - weight) * pcm[position + i]! + weight * next[position + i]!;
    }
    pcm = next; shift += changeMs > 0 ? count : -count;
  }
  return Array.from(pcm, value => Math.round(value * 32767));
}

export function markerFrequencies(id: string) {
  let hash = 0x811c9dc5;
  for (const byte of new TextEncoder().encode(id)) hash = Math.imul(hash ^ byte, 0x01000193) >>> 0;
  const base = [650, 950, 1350, 1850];
  return [...base, ...Array.from({length: 8}, (_, i) => base[(hash >>> (i * 2)) & 3]!)];
}

export function repeatedVector(rate: number, variant: "deleted" | "wrong" | "silent" | "alias") {
  const id = variant === "alias" ? "wrong-each-slot" : "repeat-v2-8233";
  const tones = markerFrequencies(id);
  const pcm = emissionVector(rate, tones);
  if (variant === "alias") {
    for (let i = rate * 81 / 100; i < rate * 87 / 100; i++) pcm[i] = .2 * Math.sin(2 * Math.PI * 950 * i / rate);
    return {id, pcm};
  }
  for (let i = rate * 39 / 100; i < rate * 81 / 100; i++)
    pcm[i] = .2 * Math.sin(2 * Math.PI * 650 * (i - rate * 39 / 100) / rate);
  const position = rate * 675 / 1000, count = rate / 100, fade = rate / 400;
  const next = new Float32Array(pcm.length - count);
  next.set(pcm.subarray(0, position)); next.set(pcm.subarray(position + count), position);
  for (let i = 0; i < fade; i++) {
    const weight = i / (fade - 1);
    next[position + i] = (1 - weight) * pcm[position + i]! + weight * next[position + i]!;
  }
  for (let i = 0; i < next.length; i++)
    next[i] = next[i]! + .002 * (1 + i / next.length) * Math.sin(2 * Math.PI * 400 * i / rate);
  if (variant === "wrong") {
    for (let i = rate * 80 / 100; i < rate * 86 / 100; i++) next[i] = .2 * Math.sin(2 * Math.PI * 1350 * i / rate);
  } else if (variant === "silent") next.fill(0, rate * 63 / 100, rate * 68 / 100);
  return {id, pcm: next};
}
