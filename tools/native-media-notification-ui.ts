import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { appendFileSync, cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { captureMediaCommand, type CapturedOutput } from './native-media-evidence.ts';

const suite = 'expo.modules.callkittelecom.conversations.';
const classes = [['ConversationAnswerIntentTest', 9], ['NotificationActivityCleanupTest', 2]] as const;
const certificate = 'android/instrumentation/src/main/res/raw/media_ci_ca.pem';
type Run = (program: 'gradle', args: string[], timeout: number) => string | CapturedOutput;
const execute: Run = captureMediaCommand;
interface ClassResult { className: string; executed: number; elapsedMs: number; passed: boolean; failures: string[] }
function decode(value: string): string {
  return value.replace(/&#(x[0-9a-f]+|\d+);/gi, (_, code: string) =>
    String.fromCodePoint(code.toLowerCase().startsWith('x') ? parseInt(code.slice(1), 16) : Number(code)))
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
}
function attribute(attributes: string, name: string): string {
  return decode(new RegExp(`\\b${name}="([^"]*)"`).exec(attributes)?.[1] ?? '');
}
/** Read AGP's bounded JUnit reports, including each named failure, error or skipped test. */
export function notificationUiResults(reports: readonly string[], expected: readonly (readonly [string, number])[] = classes): ClassResult[] {
  assert.ok(reports.length > 0 && reports.length <= 16, 'Missing connected JUnit reports or too many reports');
  const rows = expected.map(([className]) => ({ className, executed: 0, elapsedMs: 0, passed: true, failures: [] as string[] }));
  const seen = new Set<string>();
  for (const xml of reports) {
    assert.ok(Buffer.byteLength(xml) <= 1024 * 1024 && !/<!DOCTYPE|<!ENTITY/.test(xml), 'Unexpected connected XML');
    const header = /^\s*(?:<\?xml[^>]*>\s*)?<testsuite\b([^>]*)>[\s\S]*<\/testsuite>\s*$/.exec(xml)?.[1];
    assert.ok(header, 'Missing complete testsuite');
    const cases = [...xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)];
    assert.equal(cases.length, (xml.match(/<testcase\b/g) ?? []).length, 'Incomplete testcase');
    const counts = { tests: cases.length, failures: 0, errors: 0, skipped: 0 };
    for (const [, attributes, content = ''] of cases) {
      const className = attribute(attributes!, 'classname'), name = attribute(attributes!, 'name');
      const row = rows.find(row => suite + row.className === className);
      assert.ok(row && name && !seen.has(`${className}#${name}`), `Missing, unexpected or repeated test: ${className}#${name}`);
      seen.add(`${className}#${name}`); row.executed++;
      const time = attribute(attributes!, 'time');
      assert.ok(time !== '' && Number.isFinite(Number(time)) && Number(time) >= 0, 'Missing or invalid testcase timing');
      row.elapsedMs += Number(time) * 1000;
      const failure = /<(failure|error|skipped)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/\1>)/.exec(content);
      if (failure) {
        const kind = failure[1] as 'failure' | 'error' | 'skipped';
        counts[kind === 'failure' ? 'failures' : kind === 'error' ? 'errors' : 'skipped']++;
        const detail = decode(failure[3] ?? attribute(failure[2]!, 'message')).trim().split(/\r?\n/)[0] || '<no stack reported>';
        row.passed = false; row.failures.push(`${className}#${name} status=${kind}: ${detail}`);
      }
    }
    for (const [key, count] of Object.entries(counts)) assert.equal(attribute(header, key), String(count), `Incorrect report ${key}`);
  }
  rows.forEach((row, index) => assert.equal(row.executed, expected[index]![1], `Incomplete class: ${row.className}`));
  return rows;
}
function files(directory: string, suffix: string): string[] {
  return existsSync(directory) ? readdirSync(directory, { recursive: true }).map(name => join(directory, name.toString()))
    .filter(file => file.endsWith(suffix) && statSync(file).isFile()) : [];
}
function hasUiArtifactPair(directory: string): boolean {
  return files(directory, '.xml').some(xml => {
    const png = xml.replace(/\.xml$/, '.png');
    return existsSync(png) && statSync(xml).size > 0 && statSync(png).size > 0;
  });
}
/** Use the ordinary connected runner: AGP installs/grants permissions and both classes share one instrumentation. */
export function qualifyNotificationUi(batch: number, output: string, run: Run = execute,
  summary: string | null = process.env.GITHUB_STEP_SUMMARY ?? null): void {
  assert.ok(Number.isInteger(batch) && batch >= 1 && batch <= 4, 'Expected batch 1–4');
  rmSync(output, { recursive: true, force: true }); mkdirSync(output, { recursive: true });
  const build = dirname(output), reports = join(build, 'outputs/androidTest-results/connected/debug');
  const additional = join(build, 'outputs/connected_android_test_additional_output');
  const rows: { run: number; elapsedMs: number; passed: boolean; classes: ClassResult[] }[] = [];
  const instrument = (expected: readonly (readonly [string, number])[], id: number) => {
    const destination = join(output, String(id)); mkdirSync(destination);
    rmSync(reports, { recursive: true, force: true }); rmSync(additional, { recursive: true, force: true });
    const started = performance.now(); let log = '', stderr = '', failure: unknown, failed = false, results: ClassResult[] = [];
    try {
      try {
        const captured = run('gradle', ['-p', 'android/instrumentation', ':connectedDebugAndroidTest', '--rerun',
          '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true',
          `-Pandroid.testInstrumentationRunnerArguments.class=${expected.map(([name]) => suite + name).join(',')}`,
          '--no-daemon', '--max-workers=2', '--stacktrace'], 180_000);
        if (typeof captured === 'string') log = captured;
        else {
          log = captured.stdout; stderr = captured.stderr;
          if (captured.exitCode !== 0) {
            failed = true; failure = new Error(`Gradle connected run failed (exit ${captured.exitCode ?? 'unavailable'})`);
          }
        }
      } catch (error) {
        failed = true; failure = error;
        if (error && typeof error === 'object' && 'stdout' in error && typeof error.stdout === 'string') log = error.stdout;
        if (error && typeof error === 'object' && 'stderr' in error && typeof error.stderr === 'string') stderr = error.stderr;
      }
      try {
        if (existsSync(reports)) cpSync(reports, join(destination, 'reports'), { recursive: true });
        if (existsSync(additional)) cpSync(additional, join(destination, 'additional-output'), { recursive: true });
        results = notificationUiResults(files(join(destination, 'reports'), '.xml').map(file => readFileSync(file, 'utf8')), expected);
      } catch (error) { if (failed) throw failure; throw error; }
      assert.ok(results.every(row => row.passed), results.flatMap(row => row.failures).join('\n'));
      if (failed) throw failure;
    } finally {
      writeFileSync(join(destination, 'gradle.txt'), log);
      writeFileSync(join(destination, 'gradle-stderr.txt'), stderr);
      rows.push({ run: id, elapsedMs: performance.now() - started, passed: !failed && results.length > 0 && results.every(row => row.passed), classes: results });
      writeFileSync(join(output, 'timings.json'), JSON.stringify(rows));
    }
  };
  try {
    for (let iteration = 1; iteration <= 5; iteration++) {
      instrument(classes, (batch - 1) * 5 + iteration);
    }
    // The deliberate timeout must not leave UI state ahead of the measured classes.
    instrument([['NotificationUiArtifactsTest', 1]], 0);
    assert.ok(hasUiArtifactPair(join(output, '0', 'additional-output')), 'Missing transferred UI timeout XML/screenshot pair');
  } finally {
    if (summary) appendFileSync(summary,
      '| Run | Class | Test milliseconds | Class passed | Connected run passed |\n| --- | --- | --- | --- | --- |\n' + rows.flatMap(row => row.classes.map(result =>
        `| ${row.run} | ${result.className} | ${result.elapsedMs.toFixed(1)} | ${result.passed} | ${row.passed} |`)).join('\n') + '\n');
  }
}
function build() {
  const key = join(process.env.RUNNER_TEMP!, 'notification-compile.key');
  try {
    execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=CompileOnly',
      '-keyout', key, '-out', certificate], { timeout: 30_000, stdio: 'ignore' });
    execFileSync('gradle', ['-p', 'android/instrumentation', 'testDebugUnitTest', ':assembleDebug', ':assembleDebugAndroidTest',
      '--no-daemon', '--max-workers=2'], { timeout: 300_000, killSignal: 'SIGKILL', stdio: 'inherit' });
  } catch (error) { rmSync(certificate, { force: true }); throw error; }
  finally { rmSync(key, { force: true }); }
}
if (import.meta.main) {
  if (process.argv[2] === 'build') build();
  else if (process.argv[2] === 'run') {
    try { qualifyNotificationUi(Number(process.argv[3]), `android/instrumentation/build/notification-ui-${process.argv[3]}`); }
    finally { rmSync(certificate, { force: true }); }
  } else throw Error('Expected build or run BATCH');
}
