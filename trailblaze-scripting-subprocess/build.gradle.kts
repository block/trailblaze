import java.util.concurrent.TimeUnit

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":trailblaze-common"))
  api(project(":trailblaze-models"))
  // Shared MCP helpers (tool-meta filter, call-result mapper, context envelope, schema
  // projection) extracted so the on-device bundle runtime (`:trailblaze-scripting-bundle`,
  // PR A5) reads the same `_meta` keys and maps MCP shapes the same way. Kept as `api` so
  // consumers of this module can call the helpers directly without a transitive declare.
  api(project(":trailblaze-scripting-mcp-common"))

  implementation(libs.mcp.sdk)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.coroutines)

  // The synthesizer's generated wrapper does `pathToFileURL($sdkBundlePath)`, so it needs the
  // committed `trailblaze-sdk-bundle.js` materialized on disk. The bundle module owns the
  // classpath resource and the safe extract-to-temp-file logic
  // (`SdkBundleResource.extractToFile`); we depend on it for both the API call and so the
  // resource ends up on the host daemon's classpath / inside the installed uber jar (see
  // `scripts/install-trailblaze-from-source.sh`). Without this dep, any trail that triggers
  // an inline-script-tool runtime fails with "classpath resource ... not found".
  implementation(project(":trailblaze-scripting-bundle"))

  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

// Installs npm dependencies for the sample-app reference MCP tools so
// `SampleAppMcpToolsTest` can spawn `tools.ts` against the real `@modelcontextprotocol/sdk`.
// Best-effort: failures (bun missing, no network) don't fail the build — the test downgrades
// to an `assumeTrue(node_modules exists)` skip, matching the opt-in TS-fixture pattern. This
// keeps the "reference example is exercised in CI" property without requiring every build
// environment to have bun + registry access.
//
// Anchored against `projectDir` rather than `rootProject.layout.projectDirectory` so the
// path stays stable regardless of which `settings.gradle.kts` is active when this module is
// consumed (e.g. when it's included as a sub-build from a parent project). `projectDir` is
// always the module's own directory; a rootProject-relative path would change meaning when
// the rootProject does.
val sampleAppMcpToolsDir = layout.projectDirectory
  .dir("../examples/android-sample-app/trails/config/mcp")

// Sister install dir for the `@trailblaze/scripting` reference example under the same target.
// Resolves the SDK dependency via a `file:` link inside the example's package.json, so the
// install task ends up building the local SDK into this directory's node_modules. Same
// projectDir-relative anchoring rationale as [sampleAppMcpToolsDir] above.
val sampleAppMcpSdkToolsDir = layout.projectDirectory
  .dir("../examples/android-sample-app/trails/config/mcp-sdk")

// The `@trailblaze/scripting` SDK itself. Bun installs `file:` deps by symlinking each file in
// the linked package individually (not the package root), so a consumer's `import` from the
// SDK hits the SDK's REAL source path — which means transitive resolution (e.g. the SDK's
// `import "@modelcontextprotocol/sdk/server/mcp.js"`) walks up from `sdks/typescript/` and
// never reaches the consumer's `node_modules`. Giving the SDK its own `node_modules` here
// makes that walk-up find the deps on the first try on any machine, no workspace/bundling
// setup required. Without this, `SampleAppMcpSdkToolsTest` passes only on machines that
// happen to have these deps cached globally or hoisted by a parent node_modules — a
// reproducibility landmine.
//
// SUNSET CLAUSE: this "every consumer hoists the SDK's node_modules" approach is a pragmatic
// band-aid, not the long-term shape. Revisit when any of the following is true:
//   (a) the SDK grows a third runtime dep (it currently has two: @modelcontextprotocol/sdk
//       and zod), at which point per-consumer install cost starts to matter;
//   (b) a second consumer of `@trailblaze/scripting` ships (we'd start paying the hoist cost
//       more than once);
//   (c) bun changes `file:` linking semantics (current behaviour is file-level symlinks, not
//       package-level — a future bun release could shift to package symlinks or hardlink
//       copies and make this install unnecessary, or break it).
// Likely durable replacement: bundle the SDK with esbuild into a single `dist/index.js` that
// has no transitive-resolution problem because all deps are inlined. Tracking that migration
// isn't necessary *today*, but the call-out saves a future reader from rediscovering this.
val trailblazeScriptingSdkDir = layout.projectDirectory
  .dir("../sdks/typescript")

/**
 * Factored install registration so both the raw-SDK (`../mcp`) and SDK-authored (`../mcp-sdk`)
 * examples get the same bun→npm fallback + install log + bounded-wait behaviour without
 * duplicating the doLast body. [label] appears in the Gradle warning + log filename so CI
 * failures attribute to the right source.
 */
fun registerInstallTask(
  taskName: String,
  label: String,
  workingDirProvider: org.gradle.api.file.Directory,
  logName: String,
  testGateDocString: String,
) = tasks.register(taskName) {
  group = "verification"
  description = "Installs npm deps for $label (best-effort)."

  val packageJson = workingDirProvider.file("package.json").asFile
  val nodeModules = workingDirProvider.dir("node_modules").asFile
  // Sentinel file touched only after a successful install. Using it as the declared task
  // output (rather than the `node_modules` dir itself) fixes the Gradle up-to-date check on
  // partial/truncated installs: if a developer Ctrl-C's during the first install, or
  // `installTimeoutMinutes` fires mid-install, `node_modules/` exists but is incomplete.
  // Declaring `.install-ok` as the output means the task is only "up to date" when install
  // cleanly finished — subsequent `./gradlew test` runs re-install instead of starting the
  // subprocess against a broken node_modules and failing with an opaque import error.
  val installSentinel = workingDirProvider.file("node_modules/.install-ok").asFile
  val workingDir = workingDirProvider.asFile
  val installLog = layout.buildDirectory.file("tmp/$logName").get().asFile

  inputs.file(packageJson).withPropertyName("packageJson")
  outputs.file(installSentinel).withPropertyName("installSentinel")

  doLast {
    // 15 minutes is the default ceiling. It covers corporate proxies that aggressively
    // rate-limit or drop connections (e.g. an internal npm registry mirror, where npm routinely
    // sees ~70s/package with triple ECONNRESET retries before a 200). On CI and fast
    // public-registry environments the install completes in well under a minute, and
    // Gradle's input/output up-to-date check skips the task entirely on subsequent builds,
    // so the headroom is paid at most once per developer-machine. Override via Gradle
    // property for edge cases: `./gradlew test -PtrailblazeInstallTimeoutMinutes=30`.
    val installTimeoutMinutes = (project.findProperty("trailblazeInstallTimeoutMinutes") as? String)
      ?.toLongOrNull()
      ?: 15L

    // Surface the one-time install cost in the Gradle console so a developer watching a
    // cold `./gradlew test` understands what's happening instead of staring at a silent wait.
    // `lifecycle` is the visible-by-default log level; Gradle's up-to-date cache suppresses
    // this message (and the rest of `doLast`) on subsequent runs, so this only fires on
    // actual installs.
    logger.lifecycle(
      "Installing $label (one-time; Gradle's up-to-date check caches this on subsequent runs). " +
        "Log: ${installLog.absolutePath}",
    )

    installLog.parentFile.mkdirs()
    installLog.writeText("")
    // Clear any stale sentinel from a prior partial install before re-running. On a cold
    // run this is a no-op; on re-run after a failure it guarantees we don't mark the task
    // up-to-date if a subsequent install also fails silently.
    if (installSentinel.exists()) installSentinel.delete()

    fun tryInstall(command: List<String>): Int = try {
      installLog.appendText("\n\n==== ${command.joinToString(" ")} (cwd=$workingDir) ====\n")
      // Redirect child stdout+stderr to the install log file rather than capturing on
      // the Gradle thread. An earlier revision read stdout via `readText()` before
      // `waitFor(timeout)`, which defeated the timeout — `readText()` blocks on EOF, and a
      // hung install never closes its stdout. Redirecting to disk lets the kernel drain
      // output asynchronously, so `waitFor(timeout)` actually bounds the wait AND the
      // failure is diagnosable after the fact (vs `DISCARD` which loses everything).
      val proc = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(installLog))
        .start()
      if (proc.waitFor(installTimeoutMinutes, TimeUnit.MINUTES)) {
        proc.exitValue()
      } else {
        logger.warn(
          "${command.joinToString(" ")} did not finish within ${installTimeoutMinutes}m — killing.",
        )
        proc.destroyForcibly()
        proc.waitFor(10, TimeUnit.SECONDS)
        -1
      }
    } catch (e: Exception) {
      installLog.appendText("[launch failed: ${e.message}]\n")
      logger.info("${command.joinToString(" ")} failed to launch: ${e.message}")
      -1
    }

    val installSucceeded = if (tryInstall(listOf("bun", "install")) == 0) {
      true
    } else {
      logger.info("bun install failed or unavailable for $label; trying npm install")
      // `--prefer-offline` lets npm satisfy deps from its on-disk cache without re-fetching
      // manifests. Critical on flaky corporate proxies where a fresh install can take ~70s
      // per package but a cached install completes in under a second — across successive
      // runs this is the difference between "wait 15 minutes" and "wait 1 second."
      // `--no-audit --no-fund` drop registry round-trips that have no bearing on install
      // correctness and further shave the cold path.
      tryInstall(listOf("npm", "install", "--prefer-offline", "--no-audit", "--no-fund")) == 0
    }

    if (installSucceeded) {
      // Only touch the sentinel after a clean install. Gradle's up-to-date check then skips
      // this task on future builds as long as `package.json` is unchanged AND the sentinel
      // still exists.
      installSentinel.parentFile.mkdirs()
      installSentinel.writeText("ok\n")
    } else {
      logger.warn(
        "Failed to install $label deps via bun or npm. $testGateDocString\n" +
          "  Install output:  ${installLog.absolutePath}\n" +
          "  Manual install:  cd $workingDir && bun install  (or `npm install`)",
      )
    }
  }
}

val installSampleAppMcpTools = registerInstallTask(
  taskName = "installSampleAppMcpTools",
  label = "the sample-app reference MCP tools",
  workingDirProvider = sampleAppMcpToolsDir,
  logName = "install-sample-app-mcp.log",
  testGateDocString =
    "SampleAppMcpToolsTest will skip (or fail loud in CI — see TRAILBLAZE_SAMPLE_APP_MCP_TEST).",
)

val installSampleAppMcpSdkTools = registerInstallTask(
  taskName = "installSampleAppMcpSdkTools",
  label = "the sample-app SDK-authored MCP tools (`@trailblaze/scripting`)",
  workingDirProvider = sampleAppMcpSdkToolsDir,
  logName = "install-sample-app-mcp-sdk.log",
  testGateDocString =
    "SampleAppMcpSdkToolsTest will skip (or fail loud in CI — see TRAILBLAZE_SAMPLE_APP_MCP_TEST).",
)

// See [trailblazeScriptingSdkDir] for *why* the SDK gets its own node_modules (bun's
// file-level symlink strategy for `file:` deps makes the consumer's node_modules invisible
// to transitive resolution inside the SDK).
val installTrailblazeScriptingSdk = registerInstallTask(
  taskName = "installTrailblazeScriptingSdk",
  label = "the @trailblaze/scripting SDK itself",
  workingDirProvider = trailblazeScriptingSdkDir,
  logName = "install-trailblaze-scripting-sdk.log",
  testGateDocString =
    "SampleAppMcpSdkToolsTest doesn't gate explicitly on the SDK's own node_modules — " +
      "if this install fails the subprocess will error out on the first `@modelcontextprotocol/sdk` " +
      "import, surfacing as a hard test failure rather than an `assumeTrue` skip. Investigate the " +
      "install log (path printed above) before chasing the test symptom.",
)

tasks.test {
  useJUnit()
  dependsOn(installSampleAppMcpTools)
  dependsOn(installSampleAppMcpSdkTools)
  dependsOn(installTrailblazeScriptingSdk)
  // Pass absolute paths of both reference tools.ts files into the test JVM so the tests don't
  // rely on JVM cwd. Gradle sets cwd to `projectDir` for `./gradlew test`, but IDE test
  // runners (IntelliJ in particular) often set cwd to the repo root or the run-configuration's
  // dir — a `File("..")`-relative path silently breaks in that case. Resolving via Gradle at
  // configuration time gives both paths the same absolute path.
  systemProperty(
    "trailblaze.sampleApp.mcp.toolsTs",
    sampleAppMcpToolsDir.file("tools.ts").asFile.absolutePath,
  )
  systemProperty(
    "trailblaze.sampleApp.mcpSdk.toolsTs",
    sampleAppMcpSdkToolsDir.file("tools.ts").asFile.absolutePath,
  )
}
