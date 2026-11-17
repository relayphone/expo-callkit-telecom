import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { warmNotificationUi } from './native-media-notification-warmup.ts';
import type { CapturedOutput } from './native-media-evidence.ts';

const home = '<hierarchy rotation="0"><node package="com.google.android.apps.nexuslauncher"/></hierarchy>';
const shade = '<hierarchy rotation="0"><node package="com.android.systemui" resource-id="com.android.systemui:id/notification_panel"/></hierarchy>';
const ok = (stdout = ''): CapturedOutput => ({ stdout, stderr: '', exitCode: 0 });
function fixture(body: (output: string) => void) {
  const output = mkdtempSync(join(tmpdir(), 'notification-warmup-'));
  try { body(output); } finally { rmSync(output, { recursive: true, force: true }); }
}
function emulator() {
  let dumps = 0;
  return (args: readonly string[]): CapturedOutput => {
    const command = args.join(' ');
    if (command.endsWith('ro.kernel.qemu')) return ok('1\n');
    if (command.endsWith('ro.build.version.sdk')) return ok('35\n');
    if (command.includes('resolve-activity')) return ok('priority=0\ncom.google.android.apps.nexuslauncher/.NexusLauncherActivity\n');
    if (command.includes('am start')) return ok('Starting: Intent\nStatus: ok\nComplete\n');
    if (command.includes('uiautomator dump')) { dumps++; return ok('UI hierchary dumped to: /data/local/tmp/notification-ui-warmup.xml\n'); }
    if (command.includes('exec-out cat')) return ok(dumps === 2 ? shade : home);
    return ok();
  };
}
test('observes the launcher and exactly one shade open/close with retained idle hierarchies', () => fixture(output => {
  const calls: { args: readonly string[]; timeout: number }[] = [], fake = emulator();
  warmNotificationUi(output, (args, timeout) => { calls.push({args, timeout}); return fake(args); }, () => 0);
  assert(calls.every(c => c.args[0] === '-s' && c.args[1] === 'emulator-5554' && c.timeout === 15_000));
  assert.deepEqual(calls.filter(c => c.args.includes('statusbar')).map(c => c.args.at(-1)), ['expand-notifications', 'collapse']);
  assert.equal(calls.filter(c => c.args.includes('dump')).length, 3);
  assert.deepEqual(['launcher', 'shade-open', 'shade-closed'].map(phase => readFileSync(join(output, phase + '.xml'), 'utf8')), [home, shade, home]);
  assert.deepEqual(JSON.parse(readFileSync(join(output, 'warmup.json'), 'utf8')),
    {completed: true, elapsedMs: 0, completedPhases: ['launcher', 'shade-open', 'shade-closed']});
}));
test('rejects non-emulators, other APIs, unresolved homes and failed launcher starts before opening the shade', () => {
  for (const [match, value] of [['ro.kernel.qemu', '0'], ['ro.build.version.sdk', '34'],
    ['resolve-activity', 'No activity found'], ['am start', 'Error: activity not found']]) fixture(output => {
    const fake = emulator(), calls: string[] = [];
    assert.throws(() => warmNotificationUi(output, args => {
      const command = args.join(' '); calls.push(command); return command.includes(match!) ? ok(value!) : fake(args);
    }, () => 0));
    assert(!calls.some(c => c.includes('expand-notifications')));
    assert.equal(JSON.parse(readFileSync(join(output, 'warmup.json'), 'utf8')).completed, false);
  });
});
test('idle errors, missing acknowledgements, truncated hierarchies and failed transitions stay fatal', () => {
  for (const phase of ['launcher', 'shade-open', 'shade-closed']) for (const problem of ['idle', 'ack', 'xml', 'state']) fixture(output => {
    const fake = emulator(); let observation = 0;
    const target = ['launcher', 'shade-open', 'shade-closed'].indexOf(phase) + 1;
    assert.throws(() => warmNotificationUi(output, args => {
      const command = args.join(' ');
      if (command.includes('uiautomator dump')) {
        observation++;
        if (observation === target && problem === 'idle') return {...ok(), stderr: 'ERROR: could not get idle state.'};
        if (observation === target && problem === 'ack') return ok();
      }
      if (observation === target && command.includes('exec-out cat')) {
        if (problem === 'xml') return ok('<hierarchy>');
        if (problem === 'state') return ok(phase === 'shade-open' ? home : shade);
      }
      return fake(args);
    }, () => 0));
    const report = JSON.parse(readFileSync(join(output, 'warmup.json'), 'utf8'));
    assert.equal(report.completed, false); assert.equal(report.completedPhases.length, target - 1);
  });
});
test('nonzero and unavailable command exits remain fatal without repeating any command', () => {
  for (const exitCode of [1, null]) fixture(output => {
    let calls = 0;
    assert.throws(() => warmNotificationUi(output, () => { calls++; return {...ok('1'), exitCode}; }, () => 0), /command failed/);
    assert.equal(calls, 1);
  });
});
test('one shared deadline bounds every command and rejects a response arriving after it', () => fixture(output => {
  const fake = emulator(); let elapsed = 0; const timeouts: number[] = [];
  assert.throws(() => warmNotificationUi(output, (args, timeout) => {
    timeouts.push(timeout); elapsed += 10_000; return fake(args);
  }, () => elapsed), /shared deadline/);
  assert.deepEqual(timeouts, [15_000, 15_000, 15_000, 15_000, 5_000]);
  assert.equal(JSON.parse(readFileSync(join(output, 'warmup.json'), 'utf8')).completed, false);
}));
test('an unsuccessful new attempt cannot retain a previous warm-up success or its snapshots', () => fixture(output => {
  for (const phase of ['launcher', 'shade-open', 'shade-closed']) writeFileSync(join(output, phase + '.xml'), home);
  writeFileSync(join(output, 'warmup.json'), JSON.stringify({completed: true}));
  assert.throws(() => warmNotificationUi(output, () => ok('0'), () => 0), /workflow emulator/);
  for (const phase of ['launcher', 'shade-open', 'shade-closed']) assert(!existsSync(join(output, phase + '.xml')));
  assert.equal(JSON.parse(readFileSync(join(output, 'warmup.json'), 'utf8')).completed, false);
}));
