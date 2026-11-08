import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export function configureWrapperTimeout(androidProject: string): void {
  const file = join(androidProject, "gradle/wrapper/gradle-wrapper.properties");
  const contents = readFileSync(file, "utf8");
  const newline = contents.includes("\r\n") ? "\r\n" : "\n";
  const lines = contents
    .split(/\r?\n/)
    .filter(
      (line) => !/^[ \t]*networkTimeout(?:[ \t]*[=:]|[ \t]+|$)/.test(line)
    );
  if (lines.at(-1) === "") lines.pop();
  writeFileSync(file, [...lines, "networkTimeout=120000", ""].join(newline));
}

if (import.meta.main) {
  const project = process.argv[2];
  if (!project || process.argv.length !== 3)
    throw new Error(
      "Usage: node tools/gradle-wrapper-timeout.ts ANDROID_PROJECT"
    );
  configureWrapperTimeout(project);
}
