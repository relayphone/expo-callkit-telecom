import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

/** Keeps diagnostics while removing fixture secrets and generated access tokens. */
export function sanitizeFixtureLog(
  log: string,
  secrets: readonly string[]
): string {
  log = log
    .replace(
      /\beyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g,
      "<REDACTED>"
    )
    .replace(/\b(Bearer|Basic)[ \t]+[A-Za-z0-9._~+/-]+=*/gi, "$1 <REDACTED>");
  for (const secret of [...secrets]
    .filter(Boolean)
    .sort((a, b) => b.length - a.length))
    log = log.split(secret).join("<REDACTED>");
  return log;
}

/** Writes only a sanitized artifact; missing logs are normal before fixture startup. */
export function retainFixtureLog(directory: string, output: string): boolean {
  rmSync(output, { force: true });
  let log: string;
  try {
    log = readFileSync(join(directory, "server.log"), "utf8");
  } catch (error) {
    if (error instanceof Error && "code" in error && error.code === "ENOENT")
      return false;
    throw error;
  }
  const settings: unknown = JSON.parse(
    readFileSync(join(directory, "settings.json"), "utf8")
  );
  if (
    typeof settings !== "object" ||
    settings === null ||
    Array.isArray(settings) ||
    !("secret" in settings) ||
    typeof settings.secret !== "string" ||
    !settings.secret ||
    !("deviceAuth" in settings) ||
    typeof settings.deviceAuth !== "string" ||
    !settings.deviceAuth
  )
    throw new Error("Invalid fixture settings");
  const secrets: unknown[] = Object.values(settings);
  if (!secrets.every((value) => typeof value === "string"))
    throw new Error("Invalid fixture settings");
  const sanitized = sanitizeFixtureLog(log, secrets);
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, sanitized);
  return true;
}

if (import.meta.main) {
  try {
    const [directory, output] = process.argv.slice(2);
    if (!directory || !output) throw new Error("Missing paths");
    retainFixtureLog(directory, output);
  } catch {
    // Parsing errors can contain settings values. Keep CLI failures content-free.
    console.error("Could not retain sanitized native media fixture log.");
    process.exitCode = 1;
  }
}
