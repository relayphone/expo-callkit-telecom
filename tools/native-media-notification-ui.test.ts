import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { notificationUiResults, qualifyNotificationUi } from './native-media-notification-ui.ts';

const answer = 'ConversationAnswerIntentTest', cleanup = 'NotificationActivityCleanupTest', control = 'NotificationUiArtifactsTest';
function report(name: string, count: number, failure = '') {
  return `<testsuite tests="${count}" failures="${failure ? 1 : 0}" errors="0" skipped="0">` +
    Array.from({ length: count }, (_, index) => `<testcase classname="expo.modules.callkittelecom.conversations.${name}" ` +
      `name="test${index}" time="0.25">${index === 0 ? failure : ''}</testcase>`).join('') + '</testsuite>';
}
const good = () => [report(answer, 9), report(cleanup, 2)];
test('the connected runner can put both classes into one AGP XML report with empty testcase elements', () => {
  const cases = good().map(xml => xml.replace(/^<testsuite[^>]*>/, '').replace('</testsuite>', '')).join('')
    .replaceAll('></testcase>', ' />');
  const rows = notificationUiResults([`<?xml version='1.0' encoding='UTF-8' ?>\r\n` +
    `<testsuite name="expo.modules.callkittelecom.conversations.${answer}" tests="11" failures="0" errors="0" skipped="0">${cases}</testsuite>`]);
  assert.deepEqual(rows.map(row => row.executed), [9, 2]);
});
test('connected reports require both complete classes with unique named cases and finite timings', () => {
  const rows = notificationUiResults(good());
  assert.deepEqual(rows.map(row => [row.className, row.executed, row.passed, row.elapsedMs]),
    [[answer, 9, true, 2250], [cleanup, 2, true, 500]]);
  for (const reports of [[], [report(answer, 8), report(cleanup, 2)],
    [report(answer, 9).replace('test1', 'test0'), report(cleanup, 2)],
    [report('WrongClass', 9), report(cleanup, 2)], [report(answer, 9).replace('0.25', 'NaN'), report(cleanup, 2)],
    [report(answer, 9).replace('tests="9"', 'tests="8"'), report(cleanup, 2)],
    [report(answer, 9).replace('</testsuite>', ''), report(cleanup, 2)]])
    assert.throws(() => notificationUiResults(reports));
});
test('failure, skip and runner error report their class, test and first stack line', () => {
  for (const kind of ['failure', 'error', 'skipped']) {
    const reports = [report(answer, 9, `<${kind}>java.lang.AssertionError: Answer wait SYSTEMUI_ACTION timed out\n` +
      `at Fixture.await(Fixture.kt:42)</${kind}>`).replace('failures="1"', `failures="${kind === 'failure' ? 1 : 0}"`)
      .replace('errors="0"', `errors="${kind === 'error' ? 1 : 0}"`)
      .replace('skipped="0"', `skipped="${kind === 'skipped' ? 1 : 0}"`), report(cleanup, 2)];
    const rows = notificationUiResults(reports);
    assert.equal(rows[0]!.passed, false);
    assert.deepEqual(rows[0]!.failures, [`expo.modules.callkittelecom.conversations.${answer}#test0 status=${kind}: ` +
      'java.lang.AssertionError: Answer wait SYSTEMUI_ACTION timed out']);
  }
  const rows = notificationUiResults([report(answer, 9, '<skipped/>').replace('failures="1"', 'failures="0"')
    .replace('skipped="0"', 'skipped="1"'), report(cleanup, 2)]);
  assert.match(rows[0]!.failures[0]!, /status=skipped: <no stack reported>/);
});
function fixture(body: (root: string, output: string) => void) {
  const root = mkdtempSync(join(tmpdir(), 'notification-ui-'));
  try { body(root, join(root, 'qualification')); } finally { rmSync(root, { recursive: true, force: true }); }
}
function results(root: string, selected: string[], fail = false, artifacts = true) {
  const reports = join(root, 'outputs/androidTest-results/connected/debug'); mkdirSync(reports, { recursive: true });
  for (const name of selected) writeFileSync(join(reports, `TEST-${name}.xml`),
    report(name, name === answer ? 9 : name === cleanup ? 2 : 1, fail && name === answer ? '<failure>original assertion</failure>' : ''));
  if (artifacts && selected.includes(control)) {
    const directory = join(root, 'outputs/connected_android_test_additional_output/debug/device/systemui-failures');
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, 'timeout.xml'), '<hierarchy/>'); writeFileSync(join(directory, 'timeout.png'), 'image');
  }
}
function selection(args: string[]) {
  return args.find(arg => arg.startsWith('-Pandroid.testInstrumentationRunnerArguments.class='))!
    .split('=')[1]!.split(',').map(name => name.split('.').at(-1)!);
}
test('five connected runs each execute both classes together; artifact control runs last', () => fixture((root, output) => {
  const calls: { program: string; args: string[]; timeout: number }[] = [];
  qualifyNotificationUi(2, output, (program, args, timeout) => {
    calls.push({ program, args, timeout }); results(root, selection(args)); return 'BUILD SUCCESSFUL';
  }, null);
  assert.equal(calls.length, 6);
  assert.deepEqual(calls.map(call => selection(call.args)), [...Array.from({ length: 5 }, () => [answer, cleanup]), [control]]);
  assert.ok(calls.every(call => call.program === 'gradle' && call.timeout === 180_000 &&
    call.args.includes(':connectedDebugAndroidTest') && call.args.includes('--rerun') &&
    call.args.includes('-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true')));
  const rows = JSON.parse(readFileSync(join(output, 'timings.json'), 'utf8'));
  assert.deepEqual(rows.map((row: { run: number }) => row.run), [6, 7, 8, 9, 10, 0]);
  assert.ok(rows.every((row: { passed: boolean }) => row.passed));
  assert.equal(readFileSync(join(output, '6', 'reports', `TEST-${answer}.xml`), 'utf8'), report(answer, 9));
  assert.equal(readFileSync(join(output, '0', 'additional-output/debug/device/systemui-failures/timeout.png'), 'utf8'), 'image');
}));
test('passing device tests cannot qualify with missing or empty mandatory timeout artifacts', () => {
  for (const empty of [false, true]) fixture((root, output) => {
    assert.throws(() => qualifyNotificationUi(1, output, (_program, args) => {
      results(root, selection(args), false, empty);
      if (empty && selection(args).includes(control))
        writeFileSync(join(root, 'outputs/connected_android_test_additional_output/debug/device/systemui-failures/timeout.png'), '');
      return '';
    }, null), /Missing transferred UI timeout XML\/screenshot pair/);
  });
});
test('a failed connected run stops without retries or the artifact control and preserves named failures', () => fixture((root, output) => {
  let calls = 0;
  const summary = join(root, 'summary.md');
  assert.throws(() => qualifyNotificationUi(1, output, (_program, args) => {
    calls++; results(root, selection(args), true);
    throw Object.assign(Error('Gradle exited 1'), { stdout: 'original Gradle output' });
  }, summary), /ConversationAnswerIntentTest#test0 status=failure: original assertion/);
  assert.equal(calls, 1);
  assert.equal(readFileSync(join(output, '1', 'gradle.txt'), 'utf8'), 'original Gradle output');
  const rows = JSON.parse(readFileSync(join(output, 'timings.json'), 'utf8'));
  assert.equal(rows.length, 1); assert.equal(rows[0].passed, false);
  assert.match(readFileSync(summary, 'utf8'), /ConversationAnswerIntentTest \| 2250.0 \| false \| false/);
  assert.match(readFileSync(summary, 'utf8'), /NotificationActivityCleanupTest \| 500.0 \| true \| false/);
}));
test('stale reports cannot turn a zero-exit runner error into a qualification pass', () => fixture((root, output) => {
  results(root, [answer, cleanup]);
  assert.throws(() => qualifyNotificationUi(1, output, () => '', null), /Missing connected JUnit reports/);
}));
test('a nonzero connected exit stays fatal even with passing reports', () => fixture((root, output) => {
  assert.throws(() => qualifyNotificationUi(1, output, (_program, args) => {
    results(root, selection(args)); throw Error('runner crashed after tests');
  }, null), /runner crashed after tests/);
}));
test('missing reports cannot replace the original connected command failure', () => fixture((_root, output) => {
  assert.throws(() => qualifyNotificationUi(1, output, () => { throw Error('original runner error'); }, null), /original runner error/);
}));
