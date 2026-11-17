import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

interface Marker { fixture: string; direction: 0 | 1; epoch: number; counter: number; sourceTimeUs: number }
interface MarkerAPI { videoLuma(marker: Marker): Uint8Array; decodeVideoMarker(data: Uint8Array, width: number, height: number): Marker }
interface Source { generated: Marker[]; pacingTaskJoined: boolean; postStopCaptureRejects: number; finalCounter: number; finalEpoch: number }
interface Peer {
  observe(participant: string, epoch: number): Promise<void>; publish(epoch: number): Promise<void>;
  stop(): Promise<Source>; close(): Promise<void>;
  observation(): { overflow: boolean; arrivals: { image: { kind: string; marker?: Marker } }[] } | undefined;
}
const sdk = resolve(process.env.SDK_MEDIA_SOURCE ?? '.sdk-media-tools');
const tools = join(sdk, 'tools/media-test');
const assets = 'android/instrumentation/src/androidTest/assets';
const output = 'android/instrumentation/build/video-interop';
const golden = (direction: 0 | 1): Marker => ({ fixture: '01234567-89ab-cdef-0123-456789abcdef',
  direction, epoch: 7, counter: 0xffffffff, sourceTimeUs: Number.MAX_SAFE_INTEGER });
const identity = (marker: Marker) => JSON.stringify([marker.fixture.toLowerCase(), marker.direction, marker.epoch, marker.counter, marker.sourceTimeUs]);
const json = (path: string): unknown => JSON.parse(readFileSync(path, 'utf8'));
async function markers(): Promise<MarkerAPI> {
  if (process.env.SDK_MEDIA_COMMIT) assert.equal(execFileSync('git', ['-C', sdk, 'rev-parse', 'HEAD'],
    { encoding: 'utf8', timeout: 10_000 }).trim(), process.env.SDK_MEDIA_COMMIT, 'SDK tools revision mismatch');
  return import(pathToFileURL(join(tools, 'video-marker.ts')).href);
}
function credentials(identity: string, room: string) {
  const settings = json(join(process.env.RUNNER_TEMP!, 'native-media-fixture/settings.json'));
  assert.ok(settings && typeof settings === 'object' && 'key' in settings && typeof settings.key === 'string' &&
    'secret' in settings && typeof settings.secret === 'string', 'Invalid fixture settings');
  const part = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const data = part({ alg: 'HS256', typ: 'JWT' }) + '.' + part({ iss: settings.key, sub: identity,
    exp: Math.floor(Date.now() / 1000) + 3600, video: { roomJoin: true, room, canPublish: true, canSubscribe: true } });
  return data + '.' + createHmac('sha256', settings.secret).update(data).digest('base64url');
}
async function prepare() {
  const marker = await markers(); mkdirSync(assets, { recursive: true }); mkdirSync(output, { recursive: true });
  for (const direction of [0, 1] as const) writeFileSync(join(assets, `video-interop-${direction}.y`), marker.videoLuma(golden(direction)));
  assert.equal(createHash('sha256').update(marker.videoLuma(golden(0))).digest('hex'),
    '94ba28480beb3d98dcb027a5bfca34428cdd0715b3a0a8c43ee54555e913f51a');
  const fixture = randomUUID(), room = `video-${fixture}`;
  writeFileSync(join(assets, 'video-fixture.json'), JSON.stringify({ fixture, room, url: 'wss://10.0.2.2:9443',
    identity: 'video-app', token: credentials('video-app', room) }), { mode: 0o600 });
}
async function verify() {
  const marker = await markers();
  for (const direction of [0, 1] as const) {
    const data = readFileSync(join(output, `kotlin-${direction}.y`));
    assert.deepEqual(marker.decodeVideoMarker(data, 160, 120), golden(direction));
    assert.deepEqual(data, Buffer.from(marker.videoLuma(golden(direction))));
  }
}
async function roundTrip() {
  await markers();
  const localRequire = createRequire(join(tools, 'package.json'));
  const rtc = await import(pathToFileURL(localRequire.resolve('@livekit/rtc-node')).href) as {
    Room: new () => { connect(url: string, token: string): Promise<void>; disconnect(): Promise<void> }; dispose(): Promise<void> };
  const { VideoPeer } = await import(pathToFileURL(join(tools, 'video-peer.ts')).href) as {
    VideoPeer: new (room: unknown, fixture: string, clockID: string) => Peer };
  const settings = json(join(assets, 'video-fixture.json')) as { fixture: string; room: string };
  const room = new rtc.Room(); let peer: Peer | undefined;
  try {
    await room.connect('ws://127.0.0.1:7880', credentials('video-peer', settings.room));
    peer = new VideoPeer(room, settings.fixture, randomUUID());
    await peer.observe('video-app', 1); await peer.publish(1);
    await new Promise<void>((resolve, reject) => {
      const child = spawn('gradle', ['-p', 'android/instrumentation', ':connectedEmulatorTestAndroidTest',
        '-PmediaTestBuildType=emulatorTest', '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true',
        '-Pandroid.testInstrumentationRunnerArguments.class=expo.modules.callkittelecom.conversations.SyntheticVideoRoundTripTest',
        '--no-daemon', '--max-workers=2', '--stacktrace'], { stdio: 'inherit', timeout: 300_000, killSignal: 'SIGKILL' });
      child.once('error', reject); child.once('exit', (code, signal) => code === 0 && !signal ? resolve() : reject(Error('Video instrumentation failed')));
    });
    const native = JSON.parse(execFileSync('adb', ['-s', 'emulator-5554', 'exec-out', 'run-as',
      'expo.modules.callkittelecom.instrumentation', 'cat', 'files/video-round-trip.json'], { encoding: 'utf8', timeout: 10_000 })) as {
      source: Source; received: Marker[]; ambiguous: number; overflow: boolean; codec: string };
    const observation = peer.observation(); assert.ok(observation && !observation.overflow);
    const received = observation.arrivals.filter(a => a.image.kind === 'source').map(a => a.image.marker!);
    assert.ok(received.length > 0); assert.ok(native.received.length > 0);
    assert.equal(native.ambiguous, 0); assert.equal(native.overflow, false); assert.equal(native.codec.toLowerCase(), 'video/vp8');
    const source = await peer.stop();
    for (const [delivered, original] of [[received, native.source.generated], [native.received, source.generated]] as const) {
      const ledger = new Set(original.map(identity)); assert.ok(delivered.every(m => ledger.has(identity(m))));
    }
    for (const final of [native.source, source]) {
      assert.equal(final.pacingTaskJoined, true); assert.ok(final.postStopCaptureRejects > 0);
      assert.equal(final.finalCounter, final.generated.at(-1)?.counter); assert.equal(final.finalEpoch, 1);
    }
    assert.equal(observation.arrivals.filter(a => a.image.kind === 'ambiguous').length, 0);
    writeFileSync(join(output, 'round-trip.json'), JSON.stringify({ codec: native.codec, native, observation, source }));
    console.log(`VP8 decoded marker round trip: uplink=${received.length}, downlink=${native.received.length}; no timing qualification`);
  } finally {
    const junit = 'android/instrumentation/build/outputs/androidTest-results';
    if (existsSync(junit)) cpSync(junit, join(output, 'junit'), { recursive: true });
    try { await peer?.close(); } finally { await room.disconnect(); await rtc.dispose(); }
  }
}
if (process.argv[2] === 'prepare') await prepare();
else if (process.argv[2] === 'verify') await verify();
else if (process.argv[2] === 'round-trip') await roundTrip();
else throw Error('Expected prepare, verify or round-trip');
