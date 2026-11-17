import assert from 'node:assert/strict';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { captureMediaCommand, type CapturedOutput } from './native-media-evidence.ts';

type Phase = 'launcher' | 'shade-open' | 'shade-closed';
type Execute = (args: readonly string[], timeout: number) => CapturedOutput;
const execute: Execute = (args, timeout) => captureMediaCommand('adb', args, timeout);

/** Observe an idle API 35 launcher, then one shade open/close, within a shared 45-second bound. */
export function warmNotificationUi(output: string, run: Execute = execute, now: () => number = () => performance.now()): void {
  mkdirSync(output, { recursive: true });
  for (const phase of ['launcher', 'shade-open', 'shade-closed']) rmSync(join(output, phase + '.xml'), { force: true });
  const started = now(), deadline = started + 45_000;
  const completedPhases: Phase[] = [];
  let completed = false;
  const command = (...args: string[]): string => {
    const remaining = Math.floor(deadline - now());
    assert(remaining > 0, 'Notification warm-up exceeded its shared deadline');
    const result = run(['-s', 'emulator-5554', ...args], Math.min(15_000, remaining));
    assert(now() <= deadline, 'Notification warm-up exceeded its shared deadline');
    assert.equal(result.exitCode, 0, 'Notification warm-up command failed');
    assert(!/ERROR:|Exception/.test(result.stderr), 'Notification warm-up command reported an error');
    return result.stdout;
  };
  try {
    assert.equal(command('shell', 'getprop', 'ro.kernel.qemu').trim(), '1', 'Expected the workflow emulator');
    assert.equal(command('shell', 'getprop', 'ro.build.version.sdk').trim(), '35', 'Expected API 35');
    const component = command('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a',
      'android.intent.action.MAIN', '-c', 'android.intent.category.HOME').trim().split(/\r?\n/).at(-1)!;
    const launcher = /^([a-zA-Z][\w]*(?:\.[\w]+)+)\/[\w.$]+$/.exec(component)?.[1];
    assert(launcher, 'Could not resolve the launcher');
    command('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
    command('shell', 'wm', 'dismiss-keyguard');
    assert.match(command('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c',
      'android.intent.category.HOME'), /^Status: ok\s*$/m, 'Launcher did not start');
    const observe = (phase: Phase, shade: boolean) => {
      const remote = '/data/local/tmp/notification-ui-warmup.xml';
      command('shell', 'rm', '-f', remote);
      // The platform dump waits for one second of accessibility idle, bounded by ten seconds.
      assert(command('shell', 'uiautomator', 'dump', remote).includes(`UI hierchary dumped to: ${remote}`),
        'Missing idle hierarchy acknowledgement');
      const xml = command('exec-out', 'cat', remote);
      writeFileSync(join(output, phase + '.xml'), xml);
      assert(Buffer.byteLength(xml) <= 1024 * 1024 && !/<!DOCTYPE|<!ENTITY/.test(xml) &&
        /^\s*(?:<\?xml[^>]*>\s*)?<hierarchy\b[^>]*>[\s\S]*<\/hierarchy>\s*$/.test(xml), 'Incomplete UI hierarchy');
      assert.equal(xml.includes('resource-id="com.android.systemui:id/notification_panel"'), shade,
        'Unexpected notification shade state');
      if (!shade) assert(xml.includes(`package="${launcher}"`), 'Launcher is not the active window');
      completedPhases.push(phase);
    };
    observe('launcher', false);
    command('shell', 'cmd', 'statusbar', 'expand-notifications');
    observe('shade-open', true);
    command('shell', 'cmd', 'statusbar', 'collapse');
    observe('shade-closed', false);
    command('shell', 'rm', '-f', '/data/local/tmp/notification-ui-warmup.xml');
    completed = true;
  } finally {
    writeFileSync(join(output, 'warmup.json'), JSON.stringify({ completed, elapsedMs: now() - started, completedPhases }) + '\n');
  }
}

if (import.meta.main) {
  assert(process.argv.length === 3 && process.argv[2], 'Expected an artifact output directory');
  warmNotificationUi(process.argv[2]);
}
