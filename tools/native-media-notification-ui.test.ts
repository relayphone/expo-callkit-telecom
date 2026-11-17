import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { checkNotificationUiInstrumentation, qualifyNotificationUi } from './native-media-notification-ui.ts';

function result(name: string, count: number) {
  return Array.from({ length: count }, (_, index) => `INSTRUMENTATION_STATUS: class=expo.modules.callkittelecom.conversations.${name}\n` +
    `INSTRUMENTATION_STATUS: test=test${index}\nINSTRUMENTATION_STATUS_CODE: 0`).join('\n') +
    `\nINSTRUMENTATION_RESULT: stream=\nOK (${count} tests)\nINSTRUMENTATION_CODE: -1\n`;
}
test('instrumentation assertions, skips, crashes, missing tests and duplicates remain fatal despite adb exit zero', () => {
  const good = result('ConversationAnswerIntentTest', 9);
  checkNotificationUiInstrumentation(good, 'ConversationAnswerIntentTest', 9);
  checkNotificationUiInstrumentation('INSTRUMENTATION_STATUS: answerJourneyPhases={}\nINSTRUMENTATION_STATUS_CODE: 0\n' +
    good, 'ConversationAnswerIntentTest', 9);
  for (const bad of [good.replace('STATUS_CODE: 0', 'STATUS_CODE: -2'), good.replace('STATUS_CODE: 0', 'STATUS_CODE: -3'),
    good.replace('INSTRUMENTATION_CODE: -1', ''), good.replace('test=test1', 'test=test0'),
    result('ConversationAnswerIntentTest', 8), result('WrongClass', 9)])
    assert.throws(() => checkNotificationUiInstrumentation(bad, 'ConversationAnswerIntentTest', 9));
});
test('a failed instrumentation status names its class, test, code and first stack line', () => {
  const className = 'expo.modules.callkittelecom.conversations.ConversationAnswerIntentTest';
  const testName = 'notificationVideoAnswerRemainsVideo';
  const firstStackLine = 'java.lang.AssertionError: Answer wait ACTIVITY_FOREGROUND timed out';
  for (const code of [-1, -2, -3, -4]) {
    const log = `INSTRUMENTATION_STATUS: class=${className}\nINSTRUMENTATION_STATUS: test=${testName}\n` +
      `INSTRUMENTATION_STATUS: stack=${firstStackLine}\n\tat fixture.await(Fixture.kt:42)\n` +
      `INSTRUMENTATION_STATUS_CODE: ${code}\nINSTRUMENTATION_CODE: -1\n`;
    assert.throws(() => checkNotificationUiInstrumentation(log, 'ConversationAnswerIntentTest', 9), error => {
      assert.ok(error instanceof assert.AssertionError);
      assert.ok(error.message.includes(`${className}#${testName}`));
      assert.ok(error.message.includes(`status=${code}`));
      assert.ok(error.message.includes(firstStackLine));
      assert.ok(!error.message.includes('Fixture.kt:42'));
      return true;
    });
  }
});
test('a runner failure without test metadata reports explicit missing fields and clears earlier stacks', () => {
  const log = 'INSTRUMENTATION_STATUS: stack=earlier status detail\nINSTRUMENTATION_STATUS_CODE: 0\n' +
    'INSTRUMENTATION_STATUS_CODE: -1\n';
  assert.throws(() => checkNotificationUiInstrumentation(log, 'ConversationAnswerIntentTest', 9), error => {
    assert.ok(error instanceof assert.AssertionError);
    assert.ok(error.message.includes('expo.modules.callkittelecom.conversations.ConversationAnswerIntentTest#<runner>'));
    assert.ok(error.message.includes('status=-1'));
    assert.ok(error.message.includes('<no stack reported>'));
    assert.ok(!error.message.includes('earlier status detail'));
    return true;
  });
});
test('each batch executes both complete classes five times with distinct timings and no assertion retries', () => {
  const output = mkdtempSync(join(tmpdir(), 'notification-ui-')); const calls: string[][] = [];
  try {
    qualifyNotificationUi(2, output, args => {
      calls.push(args);
      const name = args[7]?.split('.').at(-1);
      if (args[0] === 'pull') {
        const directory = join(output, 'systemui-failures'); mkdirSync(directory);
        writeFileSync(join(directory, 'timeout.xml'), '<hierarchy/>'); writeFileSync(join(directory, 'timeout.png'), 'image');
        return '';
      }
      return result(name!, name === 'NotificationUiArtifactsTest' ? 1 : name === 'ConversationAnswerIntentTest' ? 9 : 2);
    }, null);
    const rows = JSON.parse(readFileSync(join(output, 'timings.json'), 'utf8')) as { run: number; className: string; passed: boolean }[];
    assert.equal(rows.length, 11); assert.ok(rows.every(row => row.passed));
    assert.deepEqual(rows.slice(1).map(row => row.run), [6, 6, 7, 7, 8, 8, 9, 9, 10, 10]);
    assert.equal(calls.filter(args => args[0] === 'pull').length, 1);
    assert.ok(calls.filter(args => args[0] === 'shell').every(args =>
      args[8] === '-e' && args[9] === 'additionalTestOutputDir' &&
      args[10] === '/sdcard/Android/media/expo.modules.callkittelecom.instrumentation/additional_test_output'));
    assert.equal(calls.find(args => args[0] === 'pull')?.[1],
      '/sdcard/Android/media/expo.modules.callkittelecom.instrumentation/additional_test_output/systemui-failures');
  } finally { rmSync(output, { recursive: true, force: true }); }
});
test('passing device tests cannot qualify when the mandatory timeout artifacts fail to transfer', () => {
  for (const pullThrows of [false, true]) {
    const output = mkdtempSync(join(tmpdir(), 'notification-ui-'));
    try {
      assert.throws(() => qualifyNotificationUi(1, output, args => {
        if (args[0] === 'pull') { if (pullThrows) throw Error('device offline'); return ''; }
        const name = args[7]!.split('.').at(-1)!;
        return result(name, name === 'NotificationUiArtifactsTest' ? 1 : name === 'ConversationAnswerIntentTest' ? 9 : 2);
      }, null), /Missing transferred UI timeout XML\/screenshot pair/);
    } finally { rmSync(output, { recursive: true, force: true }); }
  }
});
test('the first failed class stops qualification and persists its failure plus UI artifacts', () => {
  const output = mkdtempSync(join(tmpdir(), 'notification-ui-')); let instruments = 0, pulls = 0;
  try {
    assert.throws(() => qualifyNotificationUi(1, output, args => {
      if (args[0] === 'pull') { pulls++; throw Error('device offline'); }
      instruments++;
      return instruments === 1 ? result('NotificationUiArtifactsTest', 1) : 'INSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_CODE: -1';
    }, null), /Test failure, skip or runner error/);
    assert.equal(instruments, 2); assert.equal(pulls, 1);
    const rows = JSON.parse(readFileSync(join(output, 'timings.json'), 'utf8'));
    assert.equal(rows.length, 2); assert.equal(rows[1].passed, false);
  } finally { rmSync(output, { recursive: true, force: true }); }
});
