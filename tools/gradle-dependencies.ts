import { mkdirSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export const centralMirror =
  "https://maven-central.storage-download.googleapis.com/maven2/";

/** Install before Gradle starts, including settings-plugin and included-build resolution. */
export function configureGradleDependencies(
  home: string,
  mirror = new URL(centralMirror)
): void {
  if (
    mirror.username ||
    mirror.password ||
    (mirror.protocol !== "https:" &&
      !(mirror.protocol === "http:" && mirror.hostname === "127.0.0.1"))
  )
    throw new Error("Expected an HTTPS Maven mirror");
  const url = mirror.href.replaceAll("\\", "\\\\").replaceAll("'", "\\'");
  const directory = join(home, "init.d");
  mkdirSync(directory, { recursive: true });
  writeFileSync(
    join(directory, "android-dependencies.gradle"),
    String.raw`
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.artifacts.DependencyResolutionListener
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.initialization.resolve.RepositoriesMode

def mirrorUrl = '${url}'
def addMirrorFirst = { repositories ->
    def repository = repositories.find { it instanceof MavenArtifactRepository && it.url.toString() == mirrorUrl }
    if (repository == null) {
        repository = repositories.maven {
            name = 'GoogleMavenCentralMirror'
            url = mirrorUrl
            allowInsecureProtocol = mirrorUrl.startsWith('http:')
        }
    }
    repositories.remove(repository)
    repositories.addFirst(repository)
}
gradle.beforeSettings { settings ->
    addMirrorFirst(settings.pluginManagement.repositories)
    addMirrorFirst(settings.dependencyResolutionManagement.repositories)
}
gradle.settingsEvaluated { settings ->
    gradle.beforeProject { project ->
        addMirrorFirst(project.buildscript.repositories)
        if (settings.dependencyResolutionManagement.repositoriesMode.get() == RepositoriesMode.PREFER_PROJECT) {
            addMirrorFirst(project.repositories)
        }
    }
}
def rateLimited = false
def observeFailure = { root ->
    def pending = root == null ? [] : [root]
    def seen = new HashSet()
    while (!pending.isEmpty()) {
        def failure = pending.remove(0)
        if (failure == null || !seen.add(failure)) continue
        def message = failure.message ?: ''
        if (message =~ /(?i)(?:status code|response code|HTTP)\s*[:=]?\s*429\b|\b429\s+(?:Too Many Requests)/) rateLimited = true
        if (failure.cause != null) pending.add(failure.cause)
        pending.addAll(failure.suppressed.toList())
        if (failure.respondsTo('getCauses')) pending.addAll(failure.getCauses())
    }
}
gradle.addListener([
    beforeResolve: { dependencies -> },
    afterResolve: { dependencies ->
        try {
            dependencies.resolutionResult.allDependencies.each { result ->
                if (result instanceof UnresolvedDependencyResult) observeFailure(result.failure)
            }
        } catch (Exception ignored) { /* The original resolution failure remains authoritative. */ }
    }
] as DependencyResolutionListener)
gradle.buildFinished { result ->
    if (rateLimited && result.failure != null) {
        def message = 'Infrastructure: HTTP 429 during Maven dependency resolution.'
        logger.error(message)
        def summary = System.getenv('GITHUB_STEP_SUMMARY')
        if (summary) {
            try { new File(summary).append(['### Android dependency resolution', '', message, '', ''].join(System.lineSeparator()), 'UTF-8') }
            catch (Exception ignored) { logger.warn('Dependency-resolution summary could not be retained.') }
        }
    }
}
`
  );
}

if (import.meta.main) {
  const home = process.env.GRADLE_USER_HOME ?? join(homedir(), ".gradle");
  if (!home || process.argv.length !== 2)
    throw new Error("Expected GRADLE_USER_HOME from setup-gradle");
  configureGradleDependencies(home);
}
