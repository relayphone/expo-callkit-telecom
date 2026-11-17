import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { appendFileSync, existsSync, mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const app = 'expo.modules.callkittelecom.instrumentation';
const suite = 'expo.modules.callkittelecom.conversations.';
const classes = [['ConversationAnswerIntentTest', 9], ['NotificationActivityCleanupTest', 2]] as const;
type Run = (args: string[], timeout: number) => string;
const adb: Run = (args, timeout) => execFileSync('adb', ['-s', 'emulator-5554', ...args],
  { encoding: 'utf8', timeout, maxBuffer: 8 * 1024 * 1024 });

/** adb can exit zero for an instrumentation assertion or crash; require every named test to pass. */
export function checkNotificationUiInstrumentation(log: string, className: string, expected: number): void {
  let currentClass = '', currentTest = ''; const completed = new Set<string>(); let terminal = false;
  for (const line of log.split(/\r?\n/)) {
    if (line.startsWith('INSTRUMENTATION_STATUS: class=')) currentClass = line.slice(line.indexOf('=') + 1);
    if (line.startsWith('INSTRUMENTATION_STATUS: test=')) currentTest = line.slice(line.indexOf('=') + 1);
    const status = /^INSTRUMENTATION_STATUS_CODE: (-?\d+)$/.exec(line);
    if (status) {
      const code = Number(status[1]); assert.ok(code === 0 || code === 1, 'Test failure, skip or runner error');
      if (code === 0 && (currentClass || currentTest)) {
        assert.equal(currentClass, suite + className); assert.ok(currentTest && !completed.has(currentTest), 'Missing or repeated test');
        completed.add(currentTest);
      }
      currentClass = ''; currentTest = '';
    }
    if (line === 'INSTRUMENTATION_CODE: -1') terminal = true;
  }
  assert.ok(terminal, 'Missing successful terminal result'); assert.equal(completed.size, expected, 'Incomplete class');
  assert.match(log, new RegExp(`OK \\(${expected} tests?\\)`));
}

/** Always collect before the emulator action shuts down; diagnostic failure cannot replace an assertion. */
export function collectNotificationUiArtifacts(output: string, run: Run = adb): boolean {
  try { run(['pull', `/sdcard/Android/data/${app}/files/systemui-failures`, join(output, 'systemui-failures')], 10_000); return true; }
  catch { return false; /* An ordinary successful run may have no UI failure artifacts. */ }
}
function hasUiArtifactPair(output: string): boolean {
  const directory = join(output, 'systemui-failures');
  return existsSync(directory) && readdirSync(directory, { recursive: true }).some(file => {
    const xml = join(directory, file.toString()), png = xml.replace(/\.xml$/, '.png');
    return xml.endsWith('.xml') && existsSync(png) && statSync(xml).size > 0 && statSync(png).size > 0;
  });
}

export function qualifyNotificationUi(batch: number, output: string, run: Run = adb,
  summary: string | null = process.env.GITHUB_STEP_SUMMARY ?? null): void {
  assert.ok(Number.isInteger(batch) && batch >= 1 && batch <= 4, 'Expected batch 1–4');
  mkdirSync(output, { recursive: true });
  rmSync(join(output, 'systemui-failures'), { recursive: true, force: true });
  let completed = false;
  const rows: { run: number; className: string; elapsedMs: number; passed: boolean }[] = [];
  const instrument = (className: string, expected: number, id: number) => {
    const started = performance.now(); let passed = false, log = '';
    try {
      log = run(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', suite + className,
        `${app}.test/androidx.test.runner.AndroidJUnitRunner`], 180_000);
      checkNotificationUiInstrumentation(log, className, expected); passed = true;
    } catch (error) {
      if (error && typeof error === 'object' && 'stdout' in error && typeof error.stdout === 'string') log = error.stdout;
      throw error;
    } finally {
      writeFileSync(join(output, `${id}-${className}.txt`), log);
      rows.push({ run: id, className, elapsedMs: performance.now() - started, passed });
      writeFileSync(join(output, 'timings.json'), JSON.stringify(rows));
    }
  };
  try {
    instrument('NotificationUiArtifactsTest', 1, 0);
    for (let iteration = 1; iteration <= 5; iteration++) {
      const id = (batch - 1) * 5 + iteration;
      for (const [className, expected] of classes) instrument(className, expected, id);
    }
    completed = true;
  } finally {
    const collected = collectNotificationUiArtifacts(output, run);
    if (summary) appendFileSync(summary,
      '| Run | Class | Milliseconds | Passed |\n| --- | --- | --- | --- |\n' + rows.map(row =>
        `| ${row.run} | ${row.className} | ${row.elapsedMs.toFixed(1)} | ${row.passed} |`).join('\n') + '\n');
    if (completed) assert.ok(collected && hasUiArtifactPair(output), 'Missing transferred UI timeout XML/screenshot pair');
  }
}

function build() {
  const key = join(process.env.RUNNER_TEMP!, 'notification-compile.key');
  const certificate = 'android/instrumentation/src/main/res/raw/media_ci_ca.pem';
  try {
    execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=CompileOnly',
      '-keyout', key, '-out', certificate], { timeout: 30_000, stdio: 'ignore' });
    execFileSync('gradle', ['-p', 'android/instrumentation', 'testDebugUnitTest', ':assembleDebug', ':assembleDebugAndroidTest',
      '--no-daemon', '--max-workers=2'], { timeout: 300_000, stdio: 'inherit' });
  } finally { rmSync(key, { force: true }); rmSync(certificate, { force: true }); }
}
if (import.meta.main) {
  if (process.argv[2] === 'build') build();
  else if (process.argv[2] === 'run') {
    for (const path of ['debug/ConversationsInstrumentation-debug.apk', 'androidTest/debug/ConversationsInstrumentation-debug-androidTest.apk'])
      adb(['install', '-r', `android/instrumentation/build/outputs/apk/${path}`], 30_000);
    qualifyNotificationUi(Number(process.argv[3]), `android/instrumentation/build/notification-ui-${process.argv[3]}`);
  } else throw Error('Expected build or run BATCH');
}
